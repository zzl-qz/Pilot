package com.pilot.core.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.pilot.core.http.BoundedHttp;
import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.ContextCompactor;
import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容的 chat completions 客户端。
 * 只负责协议转换：把 {@link Message} 序列化为请求体，把响应的 content 与 tool_calls 解析回来。
 *
 * <p>两个入口：{@link #chat(List, List)} 一次性返回完整响应；三参数重载带 {@code stream=true}，
 * 逐行读取 {@code data:} 分片，按 {@code index} 归并分散在多行里的 tool_calls，
 * 并把增量文本实时回调给调用方。AgentLoop 走的是流式路径。
 */
public final class OpenAiCompatibleModelClient implements ModelClient {
    private static final int ERROR_SNIPPET_CHARS = 300;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;
    private Duration idleTimeout;
    /** 是否给 system 提示词打显式缓存标记；默认关闭，只在端点支持 cache_control 时启用。 */
    private volatile boolean promptCaching;
    private ModelProfile profile;
    private volatile boolean cacheParametersDisabled;
    private volatile boolean streamUsageDisabled;

    public OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper, URI endpoint, String apiKey,
        String model) {
        this(httpClient, objectMapper, endpoint, apiKey, model, Duration.ofSeconds(90));
    }

    public OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper, URI endpoint, String apiKey,
        String model, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = resolveEndpoint(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.idleTimeout = requestTimeout;
        this.profile = ModelProfile.resolve(this.endpoint, model, "auto", "auto", "", null, null, "short", null);
    }

    /**
     * 打开后给 system 提示词加 {@code cache_control: ephemeral}，长系统提示词在多轮里只按缓存价计费。
     * 只应对支持该字段的端点开启；OpenAI 官方端点自动缓存，保持关闭即可。
     */
    public OpenAiCompatibleModelClient withPromptCaching(boolean enabled) {
        this.promptCaching = enabled && !profile.provider().equals("openai") && !profile.provider().equals("deepseek");
        return this;
    }

    public OpenAiCompatibleModelClient withProfile(ModelProfile value) {
        this.profile = java.util.Objects.requireNonNull(value);
        return this;
    }

    public OpenAiCompatibleModelClient withIdleTimeout(Duration timeout) {
        this.idleTimeout = java.util.Objects.requireNonNull(timeout);
        return this;
    }

    @Override
    public ModelProfile profile() {
        ModelProfile p = profile;
        return new ModelProfile(p.provider(), p.model(), cacheParametersDisabled ? "none" : p.cacheMode(), p.cacheTtl(),
            p.instructionMessages(), p.streamUsage() && !streamUsageDisabled, p.cacheKey() && !cacheParametersDisabled,
            p.compactionMode(), p.pricing());
    }

    @Override
    public synchronized boolean disableRejectedCache(Throwable failure) {
        if (!(failure instanceof ModelClientException) || failure.getMessage() == null) {
            return false;
        }
        String message = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (!message.contains("http 400") || !message.matches(
            "(?s).*(unknown|unsupported|unrecognized|unexpected|not allowed|not permitted|extra inputs|不支持).*")) {
            return false;
        }
        if (!cacheParametersDisabled && (message.contains("cache_control") || message.contains("prompt_cache_key"))) {
            cacheParametersDisabled = true;
            return true;
        }
        if (!streamUsageDisabled && message.contains("stream_options")) {
            streamUsageDisabled = true;
            return true;
        }
        return false;
    }

    /** 是否在聊天请求里带上输出上限；默认带上，可用系统属性或环境变量关闭以对照端点行为。 */
    static boolean sendOutputLimit() {
        String value = System.getProperty("pilot.model.chat-send-output-limit",
            System.getenv("PILOT_MODEL_CHAT_SEND_OUTPUT_LIMIT"));
        return value == null || !"false".equalsIgnoreCase(value.strip());
    }

    Map<String, Object> requestBody(List<Message> messages, List<ToolDefinition> definitions, boolean streaming) {
        ModelCapacity capacity = ModelCapacity.configured();
        RequestBudget.enforce(messages, definitions, capacity);
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>(messages.stream().map(this::messagePayload).toList());
        List<Map<String, Object>> tools = definitions == null ? new ArrayList<>()
            : new ArrayList<>(definitions.stream().map(this::toolPayload).map(LinkedHashMap::new).toList());
        body.put("model", model);
        String outputParameter = System.getProperty("pilot.model.chat-output-parameter",
            "openai".equals(profile.provider()) ? "max_completion_tokens" : "max_tokens");
        if (!java.util.Set.of("max_tokens", "max_completion_tokens").contains(outputParameter)) {
            throw new IllegalArgumentException("Invalid chat output parameter");
        }
        // 个别兼容网关把 max_tokens 当成「要写满」的目标，反而诱发长文复读；可关掉这个参数对照验证。
        if (sendOutputLimit()) {
            body.put(outputParameter, capacity.outputTokens());
        }
        body.put("messages", items);
        if (streaming) {
            body.put("stream", true);
            if (profile().streamUsage()) {
                body.put("stream_options", Map.of("include_usage", true));
            }
        }
        if (!tools.isEmpty()) {
            body.put("tools", tools);
            if (ModelRequestScope.current().toolsDisabled()) {
                body.put("tool_choice", "none");
            } else if (streaming) {
                body.put("tool_choice", "auto");
            }
        }
        if (profile().cacheKey() && !ModelRequestScope.current().cacheGroup().isBlank()) {
            body.put("prompt_cache_key",
                "pilot-" + MeteredModelClient.hash(ModelRequestScope.current().cacheGroup()).substring(0, 32));
        }
        if (!cacheParametersDisabled
            && ((promptCaching && !profile.cacheMode().equals("none")) || profile.cacheMode().equals("explicit"))) {
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("type", "ephemeral");
            if (!profile.cacheTtl().isBlank()) {
                marker.put("ttl", profile.cacheTtl());
            }
            // 三个边界：工具、固定 system、最近可缓存消息。不会随着对话积累无限标记。
            if (!tools.isEmpty()) {
                tools.getLast().put("cache_control", marker);
            }
            if (!items.isEmpty() && messages.getFirst().role().equals("system")) {
                markMessage(items.getFirst(), marker, true);
            }
            for (int index = items.size() - 1; index > 0; index--) {
                if (markMessage(items.get(index), marker, false)) {
                    break;
                }
            }
        }
        return body;
    }

    private static boolean markMessage(Map<String, Object> payload, Map<String, Object> marker, boolean stableSystem) {
        Object content = payload.get("content");
        if (content instanceof String text && !text.isBlank()) {
            int boundary = stableSystem ? text.indexOf("\n\n[Runtime task context]") : -1;
            if (boundary > 0) {
                payload.put("content",
                    List.of(Map.of("type", "text", "text", text.substring(0, boundary), "cache_control", marker),
                        Map.of("type", "text", "text", text.substring(boundary))));
            } else {
                payload.put("content", List.of(Map.of("type", "text", "text", text, "cache_control", marker)));
            }
            return true;
        }
        if (content instanceof List<?> blocks) {
            List<Object> changed = new ArrayList<>(blocks);
            for (int i = changed.size() - 1; i >= 0; i--) {
                if (changed.get(i) instanceof Map<?, ?> part && "text".equals(part.get("type"))
                    && part.get("text") instanceof String text && !text.isBlank()) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    part.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    copy.put("cache_control", marker);
                    changed.set(i, copy);
                    payload.put("content", changed);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 兼容常见的配置写法：服务根地址或 {@code /v1} 结尾时补齐 chat completions 路径。
     * 完整地址原样使用，避免猜错第三方网关的自定义路径。
     *
     * <p>「/v1 结尾」不限于裸 {@code /v1}：自建网关常把兼容层挂在 {@code /api/llm/v1}
     * 这类前缀下，只认裸 {@code /v1} 会把请求 POST 到网关根，得到与密钥无关的 401。
     */
    static URI resolveEndpoint(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("model endpoint must not be null");
        }
        String path = endpoint.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return appendPath(endpoint, "/v1/chat/completions");
        }
        if (path.endsWith("/v1") || path.endsWith("/v1/")) {
            return appendPath(endpoint, "/chat/completions");
        }
        return endpoint;
    }

    private static URI appendPath(URI endpoint, String suffix) {
        String raw = endpoint.toString();
        int queryIndex = raw.indexOf('?');
        String base = queryIndex >= 0 ? raw.substring(0, queryIndex) : raw;
        String query = queryIndex >= 0 ? raw.substring(queryIndex) : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + suffix + query);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        Map<String, Object> body = requestBody(messages, tools, false);

        HttpRequest request = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json").timeout(requestTimeout).POST(HttpRequest.BodyPublishers
                .ofString(objectMapper.writeValueAsString(CanonicalJson.ordered(body)), StandardCharsets.UTF_8))
            .build();

        long startedAt = System.nanoTime();
        BoundedHttp.TextResponse response;
        try {
            response = BoundedHttp.text(httpClient, request, idleTimeout, BoundedHttp.JSON_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelClientException(ModelFailureKind.INTERRUPTED, "模型请求被中断", e);
        } catch (IOException e) {
            throw new ModelClientException(httpFailure(e),
                "模型请求失败（" + elapsedMs(startedAt) + " ms, model=" + model + "）：" + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody = snippet(response.body());
            if ((response.statusCode() == 404 || response.statusCode() == 405)
                && NativeToolRejectedException.rejected(response.statusCode(), errorBody)) {
                throw new NativeToolRejectedException("端点未提供当前聊天协议路由", true);
            }
            throw new ModelClientException(ModelFailureSignals.statusKind(response.statusCode(), errorBody),
                "模型接口返回 HTTP " + response.statusCode() + "（" + elapsedMs(startedAt) + " ms, model=" + model + "）："
                    + errorBody);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "模型响应不是合法 JSON：" + snippet(response.body()), e);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "模型响应缺少 choices：" + snippet(response.body()));
        }
        JsonNode message = choices.get(0).path("message");
        if (message.isMissingNode()) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "模型响应缺少 message：" + snippet(response.body()));
        }
        String content = text(message.get("content"));
        ModelResponse.FinishReason finishReason =
            ModelResponse.FinishReason.fromWire(text(choices.get(0).get("finish_reason")));
        if (finishReason == ModelResponse.FinishReason.UNKNOWN) {
            throw new ModelClientException(ModelFailureKind.INCOMPLETE, "模型响应缺少有效 finish_reason，未提交本次输出");
        }
        List<ToolCall> calls = usable(finishReason) ? toolCalls(message.get("tool_calls")) : List.of();
        return new ModelResponse(content, calls, usage(root.get("usage"), messages, content, calls), finishReason);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta) {
        return chat(messages, tools, textDelta, null);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta,
        Consumer<String> reasoningDelta) {
        Map<String, Object> body = requestBody(messages, tools, true);
        HttpRequest request = HttpRequest
            .newBuilder(endpoint).header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
            .header("Accept", "text/event-stream").timeout(requestTimeout).POST(HttpRequest.BodyPublishers
                .ofString(objectMapper.writeValueAsString(CanonicalJson.ordered(body)), StandardCharsets.UTF_8))
            .build();
        long startedAt = System.nanoTime();
        try (var response = BoundedHttp.open(httpClient, request, idleTimeout, BoundedHttp.STREAM_BYTES)) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream stream = response.body()) {
                    String errorBody =
                        snippet(new String(stream.readNBytes(BoundedHttp.ERROR_BYTES), StandardCharsets.UTF_8));
                    if ((response.statusCode() == 404 || response.statusCode() == 405)
                        && NativeToolRejectedException.rejected(response.statusCode(), errorBody)) {
                        throw new NativeToolRejectedException("端点未提供当前聊天协议路由", true);
                    }
                    throw new ModelClientException(ModelFailureSignals.statusKind(response.statusCode(), errorBody),
                        "模型接口返回 HTTP " + response.statusCode() + "：" + errorBody);
                }
            }
            StringBuilder content = new StringBuilder();
            ThinkSplit thoughts = new ThinkSplit();
            LinkedHashMap<Integer, Map<String, Object>> toolParts = new LinkedHashMap<Integer, Map<String, Object>>();
            ModelUsage reportedUsage = null;
            ModelResponse.FinishReason finishReason = ModelResponse.FinishReason.UNKNOWN;
            try (BufferedReader reader =
                new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = BoundedHttp.readLine(reader, BoundedHttp.SSE_LINE_CHARS)) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).strip();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    JsonNode root = objectMapper.readTree(data);
                    ModelUsage chunkUsage = reportedUsage(root.get("usage"));
                    if (chunkUsage != null) {
                        reportedUsage = chunkUsage;
                    }
                    JsonNode choices = root.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    String finish = text(choices.get(0).get("finish_reason"));
                    if (!finish.isBlank()) {
                        finishReason = ModelResponse.FinishReason.fromWire(finish);
                    }
                    JsonNode delta = choices.get(0).path("delta");
                    String reasoning = reasoningText(delta);
                    if (!reasoning.isEmpty()) {
                        emit(reasoningDelta, reasoning);
                    }
                    JsonNode text = delta.get("content");
                    if (text != null && text.isString()) {
                        thoughts.accept(text.asString(), piece -> {
                            content.append(piece);
                            emit(textDelta, piece);
                        }, piece -> emit(reasoningDelta, piece));
                    }
                    JsonNode calls = delta.path("tool_calls");
                    if (calls.isArray()) {
                        for (JsonNode call : calls) {
                            int index = call.path("index").asInt();
                            @SuppressWarnings("unchecked")
                            Map<String, Object> part =
                                toolParts.computeIfAbsent(index, ignored -> new LinkedHashMap<>());
                            JsonNode id = call.get("id");
                            if (id != null) {
                                part.put("id", part.getOrDefault("id", "") + id.asString());
                            }
                            JsonNode function = call.path("function");
                            if (function.has("name")) {
                                part.put("name", part.getOrDefault("name", "") + function.path("name").asString());
                            }
                            if (function.has("arguments")) {
                                part.put("arguments",
                                    part.getOrDefault("arguments", "") + function.path("arguments").asString());
                            }
                        }
                    }
                }
            }
            if (finishReason == ModelResponse.FinishReason.UNKNOWN) {
                throw new ModelClientException(ModelFailureKind.INCOMPLETE, "模型流在有效 finish_reason 到达前结束，未提交本次输出或执行工具");
            }
            thoughts.finish(piece -> {
                content.append(piece);
                emit(textDelta, piece);
            }, piece -> emit(reasoningDelta, piece));
            List<ToolCall> calls = !usable(finishReason) ? List.of() : toolParts.values().stream().map(part -> {
                try {
                    String id = (String)part.getOrDefault("id", "");
                    String name = (String)part.getOrDefault("name", "");
                    if (id.isBlank() || name.isBlank()) {
                        throw new ModelClientException(ModelFailureKind.PERMANENT,
                            "流式 tool_call 缺少 id 或 function.name");
                    }
                    String args = (String)part.getOrDefault("arguments", "{}");
                    JsonNode parsed = objectMapper.readTree(args);
                    if (parsed == null || !parsed.isObject()) {
                        throw new ModelClientException(ModelFailureKind.PERMANENT, "流式工具参数必须为 JSON 对象");
                    }
                    return new ToolCall(id, name, objectMapper.convertValue(parsed, Map.class));
                } catch (Exception e) {
                    throw new ModelClientException(ModelFailureKind.PERMANENT, "流式工具参数不是合法 JSON", e);
                }
            }).toList();
            ModelUsage finalUsage =
                reportedUsage != null ? reportedUsage : estimatedUsage(messages, content.toString(), calls);
            return new ModelResponse(content.toString(), calls, finalUsage, finishReason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelClientException(ModelFailureKind.INTERRUPTED, "模型请求被中断", e);
        } catch (IOException e) {
            // 流式响应读到一半断开（服务端重启、代理断连、本地网络抖动）也归网络类：
            // 上层会退避重连，而不是把这个任务判死。
            throw new ModelClientException(httpFailure(e),
                "流式模型请求失败（" + elapsedMs(startedAt) + " ms）：" + e.getMessage(), e);
        }
    }

    private static boolean usable(ModelResponse.FinishReason reason) {
        return reason == ModelResponse.FinishReason.STOP || reason == ModelResponse.FinishReason.TOOL_CALLS;
    }

    private Map<String, Object> messagePayload(Message message) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("role", message.role());
        if (message.hasToolCalls()) {
            payload.put("content", message.content().isBlank() ? null : message.content());
            payload.put("tool_calls",
                message.toolCalls().stream()
                    .map(call -> Map.of("id", call.callId(), "type", "function", "function",
                        Map.of("name", call.name(), "arguments", objectMapper
                            .writeValueAsString(CanonicalJson.ordered(call.args() == null ? Map.of() : call.args())))))
                    .toList());
        } else {
            payload.put("content", contentFor(message));
        }
        if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
            payload.put("tool_call_id", message.toolCallId());
        }
        return payload;
    }

    /**
     * 带图片附件的 user 消息按多模态数组发送；其余情况保持纯文本，
     * 避免给不支持多模态的兼容端点发送数组。
     */
    static Object contentFor(Message message) {
        List<Attachment> images = message.attachments().stream().filter(Attachment::image).toList();
        if (images.isEmpty()) {
            return message.content();
        }
        ArrayList<Map<String, Object>> parts = new ArrayList<Map<String, Object>>();
        List<Integer> positions = message.imagePositions();
        if (positions.isEmpty()) {
            if (!message.content().isBlank()) {
                parts.add(Map.of("type", "text", "text", message.content()));
            }
            for (Attachment image : images) {
                parts.add(imagePart(message, image));
            }
            return parts;
        }
        int cursor = 0;
        for (int i = 0; i < images.size(); i++) {
            int position = Math.max(cursor, Math.min(message.content().length(), positions.get(i)));
            if (position > cursor) {
                parts.add(Map.of("type", "text", "text", message.content().substring(cursor, position)));
            }
            parts.add(imagePart(message, images.get(i)));
            cursor = position;
        }
        if (cursor < message.content().length()) {
            parts.add(Map.of("type", "text", "text", message.content().substring(cursor)));
        }
        return parts;
    }

    /**
     * 图片文件丢失时降级为文字占位：工具产出的截图是一次性观察，用户上传的图片也可能
     * 被附件保留策略清理，都不能让整段历史再也发不出去。
     */
    private static Map<String, Object> imagePart(Message message, Attachment image) {
        if (!Files.isRegularFile(Path.of(image.path()))) {
            return Map.of("type", "text", "text", "[图片已不存在：" + image.name() + "]");
        }
        return Map.of("type", "image_url", "image_url", Map.of("url", dataUrl(image)));
    }

    private static String dataUrl(Attachment image) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(image.path()));
            return "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "读取图片附件失败：" + image.name(), e);
        }
    }

    private Map<String, Object> toolPayload(ToolDefinition tool) {
        return Map.of("type", "function", "function",
            Map.of("name", tool.name(), "description", tool.description(), "parameters", tool.parameters()));
    }

    private List<ToolCall> toolCalls(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        ArrayList<ToolCall> calls = new ArrayList<ToolCall>();
        for (JsonNode item : node) {
            String callId = text(item.get("id"));
            JsonNode function = item.get("function");
            String name = function == null ? "" : text(function.get("name"));
            if (callId.isBlank() || name.isBlank()) {
                throw new ModelClientException(ModelFailureKind.PERMANENT, "模型返回的 tool_call 缺少 id 或 function.name");
            }
            calls.add(new ToolCall(callId, name, arguments(function.get("arguments"))));
        }
        return List.copyOf(calls);
    }

    private ModelUsage usage(JsonNode node, List<Message> messages, String content, List<ToolCall> calls) {
        ModelUsage reported = reportedUsage(node);
        return reported == null ? estimatedUsage(messages, content, calls) : reported;
    }

    private static ModelUsage reportedUsage(JsonNode node) {
        return ModelUsageParser.parse(node);
    }

    private ModelUsage estimatedUsage(List<Message> messages, String content, List<ToolCall> calls) {
        long output = ContextCompactor.estimateTokens(content);
        for (ToolCall call : calls == null ? List.<ToolCall>of() : calls) {
            output += ContextCompactor.estimateTokens(call.name());
            output += ContextCompactor.estimateTokens(
                objectMapper.writeValueAsString(CanonicalJson.ordered(call.args() == null ? Map.of() : call.args())));
        }
        return new ModelUsage(ContextCompactor.estimateTokens(messages), output, 0, false);
    }

    private Map<String, Object> arguments(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        String raw = node.isString() ? node.asString() : node.toString();
        if (raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (RuntimeException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "工具调用参数不是合法 JSON：" + snippet(raw), e);
        }
    }

    private static ModelFailureKind httpFailure(IOException error) {
        if (Thread.currentThread().isInterrupted()) {
            return ModelFailureKind.INTERRUPTED;
        }
        return error instanceof BoundedHttp.ResponseLimitException ? ModelFailureKind.PERMANENT
            : ModelFailureKind.NETWORK;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    /** 兼容端点把推理放在 reasoning_content 或 reasoning 上，不进正文。 */
    private static String reasoningText(JsonNode delta) {
        if (delta == null) {
            return "";
        }
        String direct = text(delta.get("reasoning_content"));
        if (!direct.isEmpty()) {
            return direct;
        }
        JsonNode reasoning = delta.get("reasoning");
        if (reasoning == null || reasoning.isNull()) {
            return "";
        }
        if (reasoning.isTextual()) {
            return reasoning.asString();
        }
        String nested = text(reasoning.get("content"));
        return nested.isEmpty() ? text(reasoning.get("text")) : nested;
    }

    private static void emit(Consumer<String> sink, String text) {
        if (sink == null || text == null || text.isEmpty()) {
            return;
        }
        sink.accept(text);
    }

    /**
     * 正文里的 {@code <think>} 块改道到推理通道，避免和回答混在一起。
     * 标签可能被拆在相邻的流式分片里，所以要留住末尾可能的半截标签。
     */
    private static final class ThinkSplit {
        private static final String OPEN = "<think>";
        private static final String CLOSE = "</think>";
        private boolean inside;
        private String held = "";

        private void accept(String chunk, Consumer<String> answer, Consumer<String> reasoning) {
            held += chunk == null ? "" : chunk;
            while (!held.isEmpty()) {
                String tag = inside ? CLOSE : OPEN;
                int at = held.indexOf(tag);
                if (at < 0) {
                    int keep = unfinished(held, tag);
                    emit(inside ? reasoning : answer, held.substring(0, held.length() - keep));
                    held = held.substring(held.length() - keep);
                    return;
                }
                emit(inside ? reasoning : answer, held.substring(0, at));
                held = held.substring(at + tag.length());
                inside = !inside;
            }
        }

        private void finish(Consumer<String> answer, Consumer<String> reasoning) {
            emit(inside ? reasoning : answer, held);
            held = "";
        }

        private static int unfinished(String text, String tag) {
            int max = Math.min(text.length(), tag.length() - 1);
            for (int length = max; length > 0; length--) {
                if (tag.startsWith(text.substring(text.length() - length))) {
                    return length;
                }
            }
            return 0;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString();
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= ERROR_SNIPPET_CHARS ? single : single.substring(0, ERROR_SNIPPET_CHARS) + "…";
    }
}
