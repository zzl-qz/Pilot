package com.pilot.server.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.NativeCapability;
import com.pilot.server.store.SettingsStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 按实际地址、模型、协议和凭证隔离的后台能力验证；探测不携带用户会话或文件。 */
@Component
public final class ModelCapabilityDiscovery implements AutoCloseable {
    private static final String CACHE_KEY = "model.capability-cache.v1";
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final long VERIFIED_TTL_MS = Duration.ofHours(6).toMillis();
    private static final long UNSUPPORTED_TTL_MS = Duration.ofMinutes(30).toMillis();
    private static final long UNKNOWN_TTL_MS = Duration.ofMinutes(5).toMillis();
    private final SettingsStore store;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Clock clock;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(2);
    private final Map<String, Report> cache = new LinkedHashMap<>();
    private boolean closed;
    private volatile boolean enabled = true;

    @org.springframework.beans.factory.annotation.Value("${pilot.model.capability-discovery.enabled:true}")
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public record Report(String state, String protocol, Set<NativeCapability> capabilities, long checkedAt,
        long expiresAt, String message, int probeRequests, long inputTokens, long outputTokens) {
        /** 正在后台验证，结果还没回来。 */
        public static final String CHECKING = "checking";
        /** 已用真实工具调用验证通过。 */
        public static final String VERIFIED = "verified";
        /** 服务端明确拒绝，短期内不再重试。 */
        public static final String UNSUPPORTED = "unsupported";
        /** 没法下结论，稍后自动重试。 */
        public static final String UNKNOWN = "unknown";

        public Report {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ModelCapabilityDiscovery(SettingsStore store, ObjectMapper mapper) {
        this(store, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC(), Duration.ofSeconds(6));
    }

    ModelCapabilityDiscovery(SettingsStore store, ObjectMapper mapper, HttpClient http, Clock clock, Duration timeout) {
        this.store = store;
        this.mapper = mapper;
        this.http = http;
        this.clock = clock;
        this.timeout = timeout;
        try {
            String saved = store.find(CACHE_KEY).orElse("{}");
            JsonNode entries = mapper.readTree(saved);
            for (var property : entries.properties()) {
                if (cache.size() >= MAX_ENTRIES) {
                    break;
                }
                Report value = mapper.treeToValue(property.getValue(), Report.class);
                if (value.expiresAt() > clock.millis() && !Report.CHECKING.equals(value.state())) {
                    cache.put(property.getKey(), value);
                }
            }
        } catch (RuntimeException ignored) {
            // 缓存损坏只影响加速，不影响正常任务与下一轮自动验证。
        }
    }

    public synchronized Report inspect(ModelSettings.Snapshot target, String hint) {
        if (!enabled) {
            return report("disabled", "", Set.of(), "自动验证已关闭，当前使用 Pilot 工具", 0, 0, 0);
        }
        String key = key(target, hint);
        Report previous = cache.get(key);
        if (previous != null && previous.expiresAt() > clock.millis()) {
            return previous;
        }
        if (closed || !slots.tryAcquire()) {
            return report(Report.UNKNOWN, "", Set.of(), "后台验证繁忙，当前使用 Pilot 工具", 0, 0, 0);
        }
        Report pending = new Report(Report.CHECKING, "", Set.of(), clock.millis(), Long.MAX_VALUE,
            "正在后台自动验证服务端能力，当前任务可继续使用 Pilot 工具", 0, 0, 0);
        if (cache.size() >= MAX_ENTRIES) {
            String removable =
                cache.entrySet().stream().filter(item -> !Report.CHECKING.equals(item.getValue().state()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (removable == null) {
                slots.release();
                return report(Report.UNKNOWN, "", Set.of(), "后台验证繁忙", 0, 0, 0);
            }
            cache.remove(removable);
        }
        cache.put(key, pending);
        executor.execute(() -> {
            Report result;
            try {
                result = discover(target, hint);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                result = report(Report.UNKNOWN, "", Set.of(), "后台验证已中断，当前使用 Pilot 工具", 0, 0, 0);
            } catch (Exception failure) {
                result = report(Report.UNKNOWN, "", Set.of(), "暂时无法确认服务端能力，稍后自动重试；当前使用 Pilot 工具", 0, 0, 0);
            }
            synchronized (this) {
                // 运行时拒绝、关闭或新一轮探测不能被迟到的后台结果覆盖。
                if (!closed && cache.get(key) == pending) {
                    cache.put(key, result);
                    persist();
                }
            }
            slots.release();
        });
        return pending;
    }

    public void reject(ModelSettings.Snapshot target, String hint, String protocol) {
        reject(target, hint, protocol, false);
    }

    public synchronized void reject(ModelSettings.Snapshot target, String hint, String protocol,
        boolean protocolRejected) {
        cache.put(key(target, hint), report(protocolRejected ? Report.UNKNOWN : Report.UNSUPPORTED, protocol, Set.of(),
            protocolRejected ? "当前协议路由不可用，正在自动选择兼容协议并使用 Pilot 工具" : "实际请求拒绝了原生工具，已自动切回 Pilot 工具，稍后重新验证", 0, 0, 0));
        while (cache.size() > MAX_ENTRIES) {
            String oldest = cache.entrySet().stream().filter(entry -> !Report.CHECKING.equals(entry.getValue().state()))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
            cache.remove(oldest);
        }
        persist();
    }

    private Report discover(ModelSettings.Snapshot target, String hint) throws Exception {
        URI base = URI.create(target.endpoint());
        if (base.getHost() == null || base.getUserInfo() != null
            || !Set.of("http", "https").contains(base.getScheme())) {
            return report(Report.UNKNOWN, "", Set.of(), "端点不适合自动验证，当前使用 Pilot 工具", 0, 0, 0);
        }
        JsonNode metadata = metadata(target);
        boolean messagesFirst = "anthropic-messages".equals(hint) || base.getPath().endsWith("/messages")
            || metadata.path("capabilities").has("server_tools");
        List<String> protocols = messagesFirst ? List.of("anthropic-messages", "openai-responses")
            : List.of("openai-responses", "anthropic-messages");
        if (!"auto".equals(hint)) {
            protocols = List.of(hint);
        }
        int requests = 0;
        long input = 0;
        long output = 0;
        boolean allRejected = true;
        for (String protocol : protocols) {
            Set<NativeCapability> verified = EnumSet.noneOf(NativeCapability.class);
            List<NativeCapability> candidates =
                "anthropic-messages".equals(protocol) ? List.of(NativeCapability.WEB_SEARCH, NativeCapability.WEB_FETCH)
                    : List.of(NativeCapability.WEB_SEARCH);
            for (NativeCapability capability : candidates) {
                if (capability == NativeCapability.WEB_SEARCH && "anthropic-messages".equals(protocol)
                    && metadata.path("capabilities").path("server_tools").path("web_search").has("supported")
                    && !metadata.path("capabilities").path("server_tools").path("web_search").path("supported")
                        .asBoolean()) {
                    continue;
                }
                HttpResponse<byte[]> response = send(target, protocol, probe(target.model(), protocol, capability));
                requests++;
                JsonNode body = read(response);
                int code = response.statusCode();
                if (code == 401 || code == 402 || code == 403 || code == 429 || code >= 500 || code == 408) {
                    return report(Report.UNKNOWN, "", Set.of(), "服务端验证暂不可用或受账号限制，当前使用 Pilot 工具", requests, input,
                        output);
                }
                input += body.path("usage").path("input_tokens").asLong(0);
                output += body.path("usage").path("output_tokens").asLong(0);
                if (code >= 200 && code < 300) {
                    allRejected = false;
                    if (executed(body, protocol, capability)) {
                        verified.add(capability);
                    }
                } else if (!com.pilot.core.model.NativeToolRejectedException.rejected(code, body.toString())) {
                    allRejected = false;
                }
                // 没有该协议的路由时无需再测试同一协议的另一个工具。
                if (code == 404 || code == 405) {
                    break;
                }
            }
            if (!verified.isEmpty()) {
                return report(Report.VERIFIED, protocol, verified, "已通过结构化工具执行结果自动验证，优先使用服务端能力", requests, input,
                    output);
            }
        }
        return report(allRejected ? Report.UNSUPPORTED : Report.UNKNOWN, "", Set.of(),
            allRejected ? "当前接口未接受已适配的原生工具，自动使用 Pilot 工具" : "接口未返回可验证的工具执行结果，自动使用 Pilot 工具", requests, input, output);
    }

    private JsonNode metadata(ModelSettings.Snapshot target) throws Exception {
        HttpResponse<byte[]> response = send(target, "models", null);
        JsonNode body = read(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return mapper.createObjectNode();
        }
        JsonNode items = body.path("data").isArray() ? body.path("data") : body.path("models");
        for (JsonNode item : items) {
            if (target.model().equals(item.path("id").asString())) {
                return item;
            }
        }
        return mapper.createObjectNode();
    }

    private HttpResponse<byte[]> send(ModelSettings.Snapshot target, String protocol, Map<String, Object> body)
        throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(target.endpoint(), protocol)).timeout(timeout)
            .header("Accept", "application/json");
        if (target.hasApiKey()) {
            builder.header("Authorization", "Bearer " + target.apiKey());
            // 仅发送到同一用户配置站点，不跟随重定向、不使用元数据给出的第三方 URL。
            if ("anthropic-messages".equals(protocol) || "models".equals(protocol)) {
                builder.header("x-api-key", target.apiKey());
            }
        }
        builder.header("anthropic-version", "2023-06-01");
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        }
        var pending = http.sendAsync(builder.build(), ignored -> new LimitedBody());
        try {
            return pending.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException | java.util.concurrent.TimeoutException failure) {
            pending.cancel(true);
            throw failure;
        }
    }

    private JsonNode read(HttpResponse<byte[]> response) {
        try {
            return mapper.readTree(new String(response.body(), StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            return mapper.createObjectNode();
        }
    }

    /** 在接收过程中限制体积；send 的超时覆盖完整 body，不能被慢速流拖住探测槽位。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long received;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            received += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (received > MAX_BODY_BYTES) {
                subscription.cancel();
                delegate.onError(new IllegalArgumentException("能力响应过大"));
            } else {
                delegate.onNext(buffers);
            }
        }

        @Override
        public void onError(Throwable failure) {
            delegate.onError(failure);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }

    static URI endpoint(String value, String protocol) {
        URI original = URI.create(value);
        String path = original.getRawPath() == null ? "" : original.getRawPath().replaceAll("/+$", "");
        boolean explicitProtocol = path.matches(".*/(?:chat/completions|responses|messages|models)$");
        path = path.replaceFirst("/(?:chat/completions|responses|messages|models)$", "");
        if (path.isEmpty() && !explicitProtocol) {
            path = "/v1";
        }
        String tail = switch (protocol) {
            case "models" -> "models";
            case "anthropic-messages" -> "messages";
            case "chat-completions" -> "chat/completions";
            default -> "responses";
        };
        return URI.create(original.getScheme() + "://" + original.getRawAuthority() + path + "/" + tail
            + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery()));
    }

    private static Map<String, Object> probe(String model, String protocol, NativeCapability capability) {
        String prompt = capability == NativeCapability.WEB_SEARCH
            ? "Use web search once to find the IANA example domains page. Give its title and URL briefly."
            : "Use web fetch once to read https://example.com and give the page title briefly.";
        if (!"anthropic-messages".equals(protocol)) {
            return Map.of("model", model, "input", prompt, "tools",
                List.of(Map.of("type", "web_search", "search_context_size", "low")), "tool_choice", "required",
                "max_output_tokens", 128, "max_tool_calls", 1, "store", false);
        }
        String name = capability == NativeCapability.WEB_SEARCH ? "web_search" : "web_fetch";
        String version = capability == NativeCapability.WEB_SEARCH ? "web_search_20250305" : "web_fetch_20250910";
        return Map.of("model", model, "messages", List.of(Map.of("role", "user", "content", prompt)), "tools",
            List.of(Map.of("type", version, "name", name, "max_uses", 1)), "tool_choice",
            Map.of("type", "tool", "name", name), "max_tokens", 128);
    }

    static boolean executed(JsonNode body, String protocol, NativeCapability capability) {
        if ("openai-responses".equals(protocol)) {
            if ("failed".equals(body.path("status").asString()) || body.hasNonNull("error")) {
                return false;
            }
            for (JsonNode item : body.path("output")) {
                if ("web_search_call".equals(item.path("type").asString())
                    && "completed".equals(item.path("status").asString())) {
                    return true;
                }
            }
            return false;
        }
        String name = capability == NativeCapability.WEB_SEARCH ? "web_search" : "web_fetch";
        Set<String> calls = new java.util.HashSet<>();
        for (JsonNode block : body.path("content")) {
            if ("server_tool_use".equals(block.path("type").asString()) && name.equals(block.path("name").asString())
                && !block.path("id").asString().isBlank()) {
                calls.add(block.path("id").asString());
            }
        }
        for (JsonNode block : body.path("content")) {
            if ((name + "_tool_result").equals(block.path("type").asString())
                && calls.contains(block.path("tool_use_id").asString())) {
                JsonNode result = block.path("content");
                if (capability == NativeCapability.WEB_SEARCH && result.isArray()) {
                    return true;
                }
                if (capability == NativeCapability.WEB_FETCH
                    && "web_fetch_result".equals(result.path("type").asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Report report(String state, String protocol, Set<NativeCapability> capabilities, String message,
        int requests, long input, long output) {
        long ttl = Report.VERIFIED.equals(state) ? VERIFIED_TTL_MS
            : Report.UNSUPPORTED.equals(state) ? UNSUPPORTED_TTL_MS : UNKNOWN_TTL_MS;
        return new Report(state, protocol, capabilities, clock.millis(), clock.millis() + ttl, message, requests, input,
            output);
    }

    private static String key(ModelSettings.Snapshot target, String hint) {
        return MeteredModelClient.hash(target.endpoint() + "\n" + target.model() + "\n"
            + MeteredModelClient.hash(target.apiKey() == null ? "" : target.apiKey()) + "\n" + hint);
    }

    private void persist() {
        try {
            Map<String, Report> saved = new LinkedHashMap<>();
            cache.forEach((key, value) -> {
                if (!Report.CHECKING.equals(value.state()) && value.expiresAt() > clock.millis()) {
                    saved.put(key, value);
                }
            });
            store.save(CACHE_KEY, mapper.writeValueAsString(saved));
        } catch (RuntimeException ignored) {
            // 落盘失败不重放已经完成的探测，也不影响当前任务。
        }
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        executor.shutdownNow();
    }
}
