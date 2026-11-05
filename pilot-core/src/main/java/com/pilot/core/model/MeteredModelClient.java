package com.pilot.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.pilot.core.loop.Message;

import tools.jackson.databind.ObjectMapper;

/** 覆盖主调用、压缩、子代理和失败尝试的统一记账边界。 */
public final class MeteredModelClient implements ModelClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ModelClient delegate;
    private final String group;
    private final String purpose;
    private final List<ContextComposition.Source> sources;
    private final Consumer<RequestReport> sink;
    private final ConcurrentHashMap<String, Baseline> previous = new ConcurrentHashMap<>();

    private record Baseline(String model, String tools, List<String> messages) {
    }

    public MeteredModelClient(ModelClient delegate, String group, String purpose,
        List<ContextComposition.Source> sources, Consumer<RequestReport> sink) {
        this.delegate = delegate;
        this.group = group;
        this.purpose = purpose;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.sink = sink;
    }

    @Override
    public ModelProfile profile() {
        return delegate.profile();
    }

    @Override
    public java.util.Set<NativeCapability> nativeCapabilities() {
        return delegate.nativeCapabilities();
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return invoke(messages, tools, null, null, false);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> text) {
        return invoke(messages, tools, text, null, true);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> text,
        Consumer<String> reasoning) {
        return invoke(messages, tools, text, reasoning, true);
    }

    private ModelResponse invoke(List<Message> messages, List<ToolDefinition> tools, Consumer<String> text,
        Consumer<String> reasoning, boolean streaming) {
        ModelClient target = delegate.snapshot();
        for (int attempt = 0;; attempt++) {
            try {
                return attempt(target, messages, tools, text, reasoning, streaming);
            } catch (RuntimeException failure) {
                if (attempt != 0 || !target.disableRejectedCache(failure)) {
                    throw failure;
                }
            }
        }
    }

    private ModelResponse attempt(ModelClient target, List<Message> messages, List<ToolDefinition> tools,
        Consumer<String> text, Consumer<String> reasoning, boolean streaming) {
        ModelProfile profile = target.profile();
        long startedAt = System.currentTimeMillis();
        long started = System.nanoTime();
        AtomicLong first = new AtomicLong(-1);
        ContextComposition composition = ContextComposition.estimate(messages, tools, sources);
        String actualPurpose =
            ModelRequestScope.current().purpose().isBlank() ? purpose : ModelRequestScope.current().purpose();
        RequestReport.Prefix prefix = inspect(profile, messages, tools, actualPurpose);
        ModelResponse response = null;
        String status = "failed";
        try {
            Consumer<String> onText = value -> {
                if (value != null && !value.isEmpty()) {
                    first.compareAndSet(-1, elapsed(started));
                }
                if (text != null) {
                    text.accept(value);
                }
            };
            Consumer<String> onReasoning = value -> {
                if (value != null && !value.isEmpty()) {
                    first.compareAndSet(-1, elapsed(started));
                }
                if (reasoning != null) {
                    reasoning.accept(value);
                }
            };
            response = ModelRequestScope.within(null, group, false,
                () -> streaming ? target.chat(messages, tools, onText, onReasoning) : target.chat(messages, tools));
            status = "completed";
            return response;
        } finally {
            ModelUsage usage =
                response == null ? new ModelUsage(composition.totalTokens(), 0, 0, false) : response.usage();
            if (usage == null) {
                usage = new ModelUsage(composition.totalTokens(), 0, 0, false);
            } else if (!usage.exact()) {
                usage = new ModelUsage(Math.max(composition.totalTokens(), usage.inputTokens()), usage.outputTokens(),
                    usage.reasoningTokens(), false);
            }
            RequestReport report = new RequestReport(UUID.randomUUID().toString(), actualPurpose, profile.model(),
                profile.provider(), startedAt, elapsed(started), first.get() < 0 ? null : first.get(), status, usage,
                composition, profile.pricing().estimate(usage), prefix);
            try {
                sink.accept(report);
            } catch (RuntimeException failure) {
                System.getLogger(MeteredModelClient.class.getName()).log(System.Logger.Level.WARNING,
                    "模型请求统计写入失败，未重放已完成的模型请求", failure);
            }
        }
    }

    private RequestReport.Prefix inspect(ModelProfile profile, List<Message> messages, List<ToolDefinition> tools,
        String purpose) {
        String toolHash = hash(JSON.writeValueAsString(CanonicalJson.ordered(tools == null ? List.of()
            : tools.stream().map(
                t -> java.util.Map.of("name", t.name(), "description", t.description(), "parameters", t.parameters()))
                .toList())));
        List<
            String> hashes =
                messages.stream()
                    .map(message -> hash(JSON.writeValueAsString(CanonicalJson.ordered(java.util.Map.of("role",
                        message.role(), "text", message.content(), "calls",
                        message.toolCalls().stream()
                            .map(c -> java.util.Map.of("id", c.callId(), "name", c.name(), "args", c.args())).toList(),
                        "result", message.toolCallId() == null ? "" : message.toolCallId(), "attachments",
                        message.attachments(), "positions", message.imagePositions())))))
                    .toList();
        // 子代理并发不互相比较；每个线程/用途只保留摘要，生命周期限于一个 run。
        String key = purpose + ":" + Thread.currentThread().threadId();
        Baseline old = previous.put(key, new Baseline(profile.provider() + ":" + profile.model(), toolHash, hashes));
        String reason = "initial";
        int common = 0;
        if (old != null) {
            if (!old.model().equals(profile.provider() + ":" + profile.model())) {
                reason = "model_changed";
            } else if (!old.tools().equals(toolHash)) {
                reason = "tools_changed";
            } else {
                while (common < hashes.size() && common < old.messages().size()
                    && hashes.get(common).equals(old.messages().get(common))) {
                    common++;
                }
                reason = common == old.messages().size() ? "append_only"
                    : common == 0 ? "instructions_changed" : "history_changed";
            }
        }
        return new RequestReport.Prefix(profile.cacheMode(), hash(toolHash + String.join("", hashes)), common, reason);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long elapsed(long start) {
        return Math.max(0, (System.nanoTime() - start) / 1_000_000);
    }
}
