package com.pilot.core.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.pilot.core.http.BoundedHttp;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.model.diagnostics.ModelRequestScope;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Anthropic Messages adapter with optional provider-managed web tools. */
public final class AnthropicMessagesModelClient implements ModelClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private Duration idleTimeout;
    private final Set<NativeCapability> nativeCapabilities;
    private final ModelProfile profile;
    private volatile boolean cacheParametersDisabled;

    public AnthropicMessagesModelClient(HttpClient httpClient, ObjectMapper mapper, URI endpoint, String apiKey,
        String model, Duration timeout, ModelProfile profile, Set<NativeCapability> nativeCapabilities) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.endpoint = resolveEndpoint(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.idleTimeout = timeout;
        this.profile = profile == null ? ModelProfile.generic() : profile;
        this.nativeCapabilities = nativeCapabilities == null ? Set.of() : Set.copyOf(nativeCapabilities);
    }

    static URI resolveEndpoint(URI endpoint) {
        return ModelHttp.nativeEndpoint(endpoint, "/messages", true);
    }

    public AnthropicMessagesModelClient withIdleTimeout(Duration value) {
        this.idleTimeout = java.util.Objects.requireNonNull(value);
        return this;
    }

    @Override
    public ModelProfile profile() {
        ModelProfile p = profile;
        if (!cacheParametersDisabled) {
            return p;
        }
        return new ModelProfile(p.provider(), p.model(), ModelProfile.CACHE_NONE, p.cacheTtl(), p.instructionMessages(),
            p.streamUsage(), p.cacheKey(), p.compactionMode(), p.pricing());
    }

    @Override
    public synchronized boolean disableRejectedCache(Throwable failure) {
        if (!(failure instanceof ModelClientException) || failure.getMessage() == null) {
            return false;
        }
        String message = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (!message.contains("http 400") || !message.matches(
            "(?s).*(unknown|unsupported|unrecognized|unexpected|not allowed|not permitted|extra inputs|invalid|不支持).*")) {
            return false;
        }
        if (!cacheParametersDisabled && (message.contains("cache_control") || message.contains("ttl"))) {
            cacheParametersDisabled = true;
            return true;
        }
        return false;
    }

    @Override
    public Set<NativeCapability> nativeCapabilities() {
        return nativeCapabilities;
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        var request = messagesRequest(messages, tools, false).build();
        var response = ModelHttp.sendNative(httpClient, request, idleTimeout);
        return parseJsonResponse(ModelHttp.parseJson(mapper, response.body()));
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta,
        Consumer<String> reasoningDelta) {
        HttpRequest request = messagesRequest(messages, tools, true).header("Accept", "text/event-stream").build();
        try (var response = BoundedHttp.open(httpClient, request, idleTimeout, BoundedHttp.STREAM_BYTES)) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throwHttpError(response);
            }
            if (isJsonContentType(response.headers())) {
                return emitBufferedJson(readLimitedJson(response.body()), textDelta, reasoningDelta);
            }
            return readStream(response.body(), textDelta, reasoningDelta);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelClientException(ModelFailureKind.INTERRUPTED, "模型请求被中断", e);
        } catch (IOException e) {
            ModelFailureKind kind = Thread.currentThread().isInterrupted() ? ModelFailureKind.INTERRUPTED
                : e instanceof BoundedHttp.ResponseLimitException ? ModelFailureKind.PERMANENT
                    : ModelFailureKind.NETWORK;
            throw new ModelClientException(kind, "模型请求失败：" + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder messagesRequest(List<Message> messages, List<ToolDefinition> tools, boolean stream) {
        return ModelHttp.jsonPost(endpoint, timeout, mapper, requestBody(messages, tools, stream))
            .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
    }

    private ModelResponse readStream(InputStream body, Consumer<String> textDelta, Consumer<String> reasoningDelta)
        throws IOException {
        StreamAccumulator accumulator = new StreamAccumulator(textDelta, reasoningDelta);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String eventName = "";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = BoundedHttp.readLine(reader, BoundedHttp.SSE_LINE_CHARS)) != null) {
                if (data.isEmpty() && !accumulator.started() && line.stripLeading().startsWith("{")) {
                    String buffered = line + "\n" + readLimitedText(reader, BoundedHttp.JSON_BYTES - line.length() - 1);
                    return emitBufferedJson(buffered, textDelta, reasoningDelta);
                }
                if (line.isBlank()) {
                    accumulator.dispatch(eventName, data.toString());
                    eventName = "";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
            accumulator.dispatch(eventName, data.toString());
        }
        return accumulator.finish();
    }

    private ModelResponse emitBufferedJson(String raw, Consumer<String> textDelta, Consumer<String> reasoningDelta) {
        JsonNode root = ModelHttp.parseJson(mapper, raw);
        ModelResponse parsed = parseJsonResponse(root);
        String reasoning = parsed.reasoningContent();
        if (!reasoning.isEmpty()) {
            int split = reasoning.indexOf(THINKING_SPLIT);
            emit(reasoningDelta, split > 0 ? reasoning.substring(0, split) : reasoning);
        } else {
            StringBuilder rawThinking = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("thinking".equals(string(block.get("type")))) {
                    rawThinking.append(string(block.get("thinking")));
                }
            }
            if (!rawThinking.isEmpty()) {
                emit(reasoningDelta, rawThinking.toString());
            }
        }
        emit(textDelta, parsed.content());
        return parsed;
    }

    private void throwHttpError(BoundedHttp.Exchange response) throws IOException {
        String detail =
            ModelHttp.snippet(new String(response.body().readNBytes(BoundedHttp.ERROR_BYTES), StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (NativeToolRejectedException.rejected(status, detail)) {
            throw new NativeToolRejectedException("服务端未接受原生工具或协议", status == 404 || status == 405);
        }
        throw ModelClientException.httpError(ModelFailureSignals.statusKind(status, detail), status,
            ModelHttp.retryAfterMs(response.headers()), "模型接口返回 HTTP " + status + "：" + detail);
    }

    private static boolean isJsonContentType(HttpHeaders headers) {
        if (headers == null) {
            return false;
        }
        String type = headers.firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        return type.contains("application/json") && !type.contains("text/event-stream");
    }

    private static String readLimitedJson(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(BoundedHttp.JSON_BYTES + 1);
        if (bytes.length > BoundedHttp.JSON_BYTES) {
            throw new BoundedHttp.ResponseLimitException("响应正文超过 " + BoundedHttp.JSON_BYTES + " 字节上限");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readLimitedText(BufferedReader reader, int remainingChars) throws IOException {
        StringBuilder out = new StringBuilder();
        int cap = Math.max(0, remainingChars);
        String line;
        while ((line = BoundedHttp.readLine(reader, BoundedHttp.SSE_LINE_CHARS)) != null) {
            if (out.length() + line.length() + 1 > cap) {
                throw new BoundedHttp.ResponseLimitException("JSON 响应超过上限");
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }

    private ModelResponse parseJsonResponse(JsonNode root) {
        if (!root.path("content").isArray()) {
            throw new ModelClientException(ModelFailureKind.PERMANENT,
                "模型响应缺少 content：" + ModelHttp.snippet(root.toString()));
        }
        if ("pause_turn".equals(root.path("stop_reason").asString())) {
            throw new NativeToolRejectedException("原生工具需要分段续传，当前自动切回 Pilot 工具");
        }
        ModelResponse.FinishReason reason = finishReason(string(root.get("stop_reason")));
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        String signature = "";
        List<ToolCall> calls = new ArrayList<>();
        LinkedHashMap<String, String> citations = new LinkedHashMap<>();
        for (JsonNode block : root.path("content")) {
            String type = string(block.get("type"));
            if ("thinking".equals(type)) {
                thinking.append(string(block.get("thinking")));
                String signed = string(block.get("signature"));
                if (!signed.isBlank()) {
                    signature = signed;
                }
            } else if ("text".equals(type)) {
                text.append(string(block.get("text")));
                collectCitations(block.get("citations"), citations);
            } else if ("tool_use".equals(type)
                && (reason == ModelResponse.FinishReason.STOP || reason == ModelResponse.FinishReason.TOOL_CALLS)) {
                String id = string(block.get("id"));
                String name = string(block.get("name"));
                if (id.isBlank() || name.isBlank()) {
                    throw new ModelClientException(ModelFailureKind.PERMANENT, "模型返回的 tool_use 缺少 id 或 name");
                }
                calls.add(new ToolCall(id, name, objectMap(block.get("input"))));
            } else if (type.startsWith("web_search") || type.startsWith("web_fetch")) {
                handleServerToolBlock(block, text, citations);
            }
        }
        NativeModelCitations.append(text, citations, url -> url.replace("(", "%28").replace(")", "%29"));
        String reasoning = signature.isBlank() ? "" : thinking + THINKING_SPLIT + signature;
        return new ModelResponse(text.toString(), calls, ModelUsageParser.parse(root.get("usage")), reason, reasoning);
    }

    private static ModelResponse.FinishReason finishReason(String stopReason) {
        return switch (stopReason == null ? "" : stopReason) {
            case "max_tokens", "model_context_window_exceeded" -> ModelResponse.FinishReason.LENGTH;
            case "tool_use" -> ModelResponse.FinishReason.TOOL_CALLS;
            case "end_turn", "stop_sequence" -> ModelResponse.FinishReason.STOP;
            case "refusal" -> ModelResponse.FinishReason.CONTENT_FILTER;
            default -> throw new ModelClientException(ModelFailureKind.INCOMPLETE, "模型响应缺少有效 stop_reason，未提交本次输出或执行工具");
        };
    }

    private static void handleServerToolBlock(JsonNode block, StringBuilder text, Map<String, String> citations) {
        JsonNode result = block.path("content");
        if (result.path("type").asString().endsWith("_error")) {
            String code = result.path("error_code").asString();
            if (NativeToolRejectedException.rejected(400, block.toString())) {
                throw new NativeToolRejectedException("服务端执行时拒绝原生工具");
            }
            text.append("\n原生工具未成功完成：").append(code).append('\n');
        }
        collectCitations(block, citations);
    }

    private static void emit(Consumer<String> sink, String delta) {
        if (sink != null && delta != null && !delta.isEmpty()) {
            sink.accept(delta);
        }
    }

    Map<String, Object> requestBody(List<Message> messages, List<ToolDefinition> definitions) {
        return requestBody(messages, definitions, false);
    }

    Map<String, Object> requestBody(List<Message> messages, List<ToolDefinition> definitions, boolean stream) {
        ModelCapacity capacity = ModelCapacity.active();
        messages = RequestBudget.enforce(messages, definitions, capacity);
        String topSystem =
            messages.isEmpty() || !"system".equals(messages.getFirst().role()) ? "" : messages.getFirst().content();
        List<String> tailGuidance = new ArrayList<>();
        List<Map<String, Object>> conversation = encodeConversation(messages, tailGuidance);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", capacity.outputTokens());
        Map<String, Object> cacheMarker = explicitCacheMarker();
        if (!topSystem.isBlank()) {
            body.put("system", systemPayload(topSystem, cacheMarker));
        }

        ModelRequestScope.State scope = ModelRequestScope.current();
        boolean toolsDisabled = scope.toolsDisabled();
        boolean hasDefinitions = definitions != null && !definitions.isEmpty();
        List<Map<String, Object>> requestTools = buildRequestTools(definitions, !toolsDisabled || hasDefinitions);
        int customToolsEnd = countCustomTools(definitions);
        boolean sendTools = !requestTools.isEmpty() && (!toolsDisabled || hasDefinitions);
        applyCacheMarkers(sendTools ? requestTools : List.of(), customToolsEnd, conversation, cacheMarker,
            "compaction".equals(scope.purpose()));
        // 临时引导（phaseHint、作用域规则等）跨轮易变，必须在对话尾部缓存断点确定之后再追加。
        appendTailGuidance(conversation, tailGuidance);
        body.put("messages", conversation);
        if (sendTools) {
            body.put("tools", requestTools);
            if (toolsDisabled && !nativeCapabilities.isEmpty()) {
                body.put("tool_choice", Map.of("type", "none"));
            }
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }

    private List<Map<String, Object>> encodeConversation(List<Message> messages, List<String> tailGuidance) {
        List<Map<String, Object>> conversation = new ArrayList<>();
        int lastUser = Message.lastRealUserTurnIndex(messages);
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if ("system".equals(message.role())) {
                // 只把主 system 写进顶层字段：尾部临时引导（phaseHint、运行时引导、路径规则）进 messages，
                // 否则它一出现/消失就会改写顶层 system 的字节，打断供应商的 prompt cache 前缀。
                if (index > 0 && !message.content().isBlank()) {
                    tailGuidance.add(message.content());
                }
            } else if ("tool".equals(message.role())) {
                List<Map<String, Object>> results = new ArrayList<>();
                while (index < messages.size() && "tool".equals(messages.get(index).role())) {
                    Message result = messages.get(index++);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "tool_result");
                    item.put("tool_use_id", result.toolCallId());
                    item.put("content", result.content());
                    results.add(item);
                }
                index--;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("role", "user");
                entry.put("content", results);
                conversation.add(entry);
            } else if (message.hasToolCalls()) {
                conversation.add(assistantToolUseMessage(message, index > lastUser));
            } else {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("role", message.role());
                entry.put("content", content(message));
                conversation.add(entry);
            }
        }
        return conversation;
    }

    private static Map<String, Object> assistantToolUseMessage(Message message, boolean replayThinking) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (replayThinking) {
            Map<String, Object> replay = thinkingBlock(message.reasoningContent());
            if (replay != null) {
                blocks.add(replay);
            }
        }
        if (!message.content().isBlank()) {
            blocks.add(textBlock(message.content()));
        }
        for (ToolCall call : message.toolCalls()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_use");
            block.put("id", call.callId());
            block.put("name", call.name());
            block.put("input", call.args() == null ? Map.of() : call.args());
            blocks.add(block);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", "assistant");
        entry.put("content", blocks);
        return entry;
    }

    private int countCustomTools(List<ToolDefinition> definitions) {
        if (definitions == null) {
            return 0;
        }
        return (int)definitions.stream()
            .filter(definition -> !NativeToolNames.isHidden(definition.name(), nativeCapabilities)).count();
    }

    private List<Map<String, Object>> buildRequestTools(List<ToolDefinition> definitions, boolean includeNative) {
        List<Map<String, Object>> requestTools = new ArrayList<>();
        if (definitions != null) {
            definitions.stream().filter(definition -> !NativeToolNames.isHidden(definition.name(), nativeCapabilities))
                .forEach(definition -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", definition.name());
                    item.put("description", definition.description());
                    item.put("input_schema", definition.parameters());
                    requestTools.add(item);
                });
        }
        if (includeNative && nativeCapabilities.contains(NativeCapability.WEB_SEARCH)) {
            // 与后台实际验证使用同一基础工具版本，避免新版本依赖造成假阳性。
            requestTools.add(Map.of("type", "web_search_20250305", "name", "web_search", "max_uses", 1));
        }
        if (includeNative && nativeCapabilities.contains(NativeCapability.WEB_FETCH)) {
            requestTools.add(Map.of("type", "web_fetch_20250910", "name", "web_fetch", "max_uses", 1));
        }
        return requestTools;
    }

    private Map<String, Object> explicitCacheMarker() {
        if (cacheParametersDisabled || !ModelProfile.CACHE_EXPLICIT.equals(profile.cacheMode())) {
            return null;
        }
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("type", "ephemeral");
        if (profile.cacheTtl() != null && !profile.cacheTtl().isBlank()) {
            marker.put("ttl", profile.cacheTtl());
        }
        return Map.copyOf(marker);
    }

    private static Object systemPayload(String topSystem, Map<String, Object> marker) {
        if (marker == null) {
            return topSystem;
        }
        int boundary = topSystem.indexOf("\n\n[Runtime task context]");
        if (boundary > 0) {
            return List.of(cacheTextBlock(topSystem.substring(0, boundary), marker),
                textBlock(topSystem.substring(boundary)));
        }
        return List.of(cacheTextBlock(topSystem, marker));
    }

    private static void applyCacheMarkers(List<Map<String, Object>> requestTools, int customToolsEnd,
        List<Map<String, Object>> conversation, Map<String, Object> marker, boolean compaction) {
        if (marker == null) {
            return;
        }
        if (customToolsEnd > 0 && customToolsEnd <= requestTools.size()) {
            requestTools.get(customToolsEnd - 1).put("cache_control", marker);
        }
        if (conversation.isEmpty()) {
            return;
        }
        // 压缩请求尾部是一次性摘要指令：把对话断点留在上一条历史消息上直接命中主对话缓存，避免对临时指令计写缓存加价。
        int start = compaction && conversation.size() >= 2 ? conversation.size() - 2 : conversation.size() - 1;
        for (int index = start; index >= 0; index--) {
            if (markConversationMessage(conversation.get(index), marker)) {
                return;
            }
        }
        if (start < conversation.size() - 1) {
            markConversationMessage(conversation.getLast(), marker);
        }
    }

    private static boolean markConversationMessage(Map<String, Object> message, Map<String, Object> marker) {
        Object content = message.get("content");
        if (content instanceof String text && !text.isBlank()) {
            message.put("content", List.of(cacheTextBlock(text, marker)));
            return true;
        }
        if (content instanceof List<?> blocks) {
            List<Object> changed = new ArrayList<>(blocks);
            for (int i = changed.size() - 1; i >= 0; i--) {
                if (!(changed.get(i) instanceof Map<?, ?> part)) {
                    continue;
                }
                Object type = part.get("type");
                boolean markable = "tool_result".equals(type)
                    || ("text".equals(type) && part.get("text") instanceof String text && !text.isBlank());
                if (markable) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    part.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    copy.put("cache_control", marker);
                    changed.set(i, copy);
                    message.put("content", changed);
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static Map<String, Object> cacheTextBlock(String text, Map<String, Object> marker) {
        Map<String, Object> block = textBlock(text);
        block.put("cache_control", marker);
        return block;
    }

    /** 思考正文和签名用这个分隔，只在当前用户轮次回传。 */
    static final String THINKING_SPLIT = "\u001e";

    /**
     * 把尾部临时 system 引导并进 messages 末尾，并遵守 user/assistant 交替：
     * 上一条已经是 user（含 tool_result）时并入它，否则单独追加一条 user 消息。
     */
    private static void appendTailGuidance(List<Map<String, Object>> conversation, List<String> guidance) {
        if (guidance.isEmpty()) {
            return;
        }
        List<Object> blocks = new ArrayList<>(List.of(textBlock(String.join("\n\n", guidance))));
        if (!conversation.isEmpty() && "user".equals(conversation.getLast().get("role"))) {
            Map<String, Object> previous = conversation.removeLast();
            List<Object> merged = new ArrayList<>(blocksOf(previous.get("content")));
            merged.addAll(blocks);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", "user");
            entry.put("content", merged);
            conversation.add(entry);
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", "user");
        entry.put("content", blocks);
        conversation.add(entry);
    }

    /** 把任意 content（纯文本或块数组）展开成可合并的块列表。 */
    private static List<Object> blocksOf(Object content) {
        if (content instanceof List<?> parts) {
            return new ArrayList<>(parts);
        }
        if (content == null || content.toString().isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(textBlock(content.toString())));
    }

    private static Map<String, Object> thinkingBlock(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        int split = stored.indexOf(THINKING_SPLIT);
        if (split <= 0 || split == stored.length() - 1) {
            return null;
        }
        return Map.of("type", "thinking", "thinking", stored.substring(0, split), "signature",
            stored.substring(split + THINKING_SPLIT.length()));
    }

    private Object content(Message message) {
        Object compatible = OpenAiCompatibleModelClient.contentFor(message);
        if (!(compatible instanceof List<?> parts)) {
            return compatible;
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Object value : parts) {
            if (!(value instanceof Map<?, ?> part)) {
                continue;
            }
            if ("text".equals(part.get("type"))) {
                blocks.add(Map.of("type", "text", "text", part.get("text")));
            } else if ("image_url".equals(part.get("type")) && part.get("image_url") instanceof Map<?, ?> image) {
                String dataUrl = String.valueOf(image.get("url"));
                int separator = dataUrl.indexOf(";base64,");
                if (dataUrl.startsWith("data:") && separator > 5) {
                    String mediaType = dataUrl.substring(5, separator);
                    String data = dataUrl.substring(separator + ";base64,".length());
                    blocks.add(Map.of("type", "image", "source",
                        Map.of("type", "base64", "media_type", mediaType, "data", data)));
                }
            }
        }
        return blocks;
    }

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node,
            mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    private static void collectCitations(JsonNode value, Map<String, String> citations) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                collectCitations(item, citations);
            }
            return;
        }
        if (!value.isObject()) {
            return;
        }
        NativeModelCitations.add(string(value.get("url")), string(value.get("title")), citations);
        value.properties().forEach(entry -> collectCitations(entry.getValue(), citations));
    }

    private static String string(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString();
    }

    private static final class StreamBlock {
        final String type;
        final String id;
        final String name;
        final JsonNode initialInput;
        final StringBuilder inputJson = new StringBuilder();

        StreamBlock(String type, String id, String name, JsonNode initialInput) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.initialInput = initialInput;
        }
    }

    private final class StreamAccumulator {
        private final Consumer<String> textDelta;
        private final Consumer<String> reasoningDelta;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();
        private final Map<Integer, StreamBlock> blocks = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> citations = new LinkedHashMap<>();
        private final LinkedHashMap<String, Object> mergedUsage = new LinkedHashMap<>();
        private String stopReason = "";
        private boolean anyEvent;

        StreamAccumulator(Consumer<String> textDelta, Consumer<String> reasoningDelta) {
            this.textDelta = textDelta;
            this.reasoningDelta = reasoningDelta;
        }

        boolean started() {
            return anyEvent;
        }

        void dispatch(String eventName, String data) {
            String payload = data == null ? "" : data.strip();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                return;
            }
            anyEvent = true;
            JsonNode root = ModelHttp.parseJson(mapper, payload);
            String type = string(root.get("type"));
            if (type.isBlank()) {
                type = eventName == null ? "" : eventName;
            }
            switch (type) {
                case "message_start" -> onMessageStart(root);
                case "content_block_start" -> onBlockStart(root);
                case "content_block_delta" -> onBlockDelta(root);
                case "message_delta" -> onMessageDelta(root);
                case "error" -> onErrorEvent(root);
                default -> {
                    // ping、content_block_stop、message_stop 等控制事件无需正文累积
                }
            }
        }

        private void onMessageStart(JsonNode root) {
            JsonNode message = root.path("message");
            String reason = string(message.get("stop_reason"));
            if (!reason.isBlank()) {
                stopReason = reason;
            }
            mergeUsage(message.get("usage"));
        }

        private void onBlockStart(JsonNode root) {
            int index = root.path("index").asInt(blocks.size());
            JsonNode block = root.path("content_block");
            String type = string(block.get("type"));
            StreamBlock entry =
                new StreamBlock(type, string(block.get("id")), string(block.get("name")), block.get("input"));
            blocks.put(index, entry);
            if ("text".equals(type)) {
                String piece = string(block.get("text"));
                text.append(piece);
                emit(textDelta, piece);
                collectCitations(block.get("citations"), citations);
            } else if ("thinking".equals(type)) {
                String piece = string(block.get("thinking"));
                thinking.append(piece);
                emit(reasoningDelta, piece);
                String sig = string(block.get("signature"));
                if (!sig.isBlank()) {
                    signature.append(sig);
                }
            } else if (type.startsWith("web_search") || type.startsWith("web_fetch")) {
                int before = text.length();
                handleServerToolBlock(block, text, citations);
                if (text.length() > before) {
                    emit(textDelta, text.substring(before));
                }
            }
        }

        private void onBlockDelta(JsonNode root) {
            int index = root.path("index").asInt(-1);
            StreamBlock block = blocks.get(index);
            JsonNode delta = root.path("delta");
            String deltaType = string(delta.get("type"));
            if ("text_delta".equals(deltaType) || (deltaType.isBlank() && delta.hasNonNull("text"))) {
                String piece = string(delta.get("text"));
                text.append(piece);
                emit(textDelta, piece);
            } else if ("thinking_delta".equals(deltaType)) {
                String piece = string(delta.get("thinking"));
                thinking.append(piece);
                emit(reasoningDelta, piece);
            } else if ("signature_delta".equals(deltaType)) {
                signature.append(string(delta.get("signature")));
            } else if ("input_json_delta".equals(deltaType) && block != null) {
                block.inputJson.append(string(delta.get("partial_json")));
            } else if ("citations_delta".equals(deltaType)) {
                collectCitations(delta.get("citation"), citations);
            }
        }

        private void onMessageDelta(JsonNode root) {
            String reason = string(root.path("delta").get("stop_reason"));
            if (!reason.isBlank()) {
                stopReason = reason;
            }
            JsonNode usage = root.hasNonNull("usage") ? root.get("usage") : root.path("delta").get("usage");
            mergeUsage(usage);
        }

        private void onErrorEvent(JsonNode root) {
            JsonNode error = root.path("error");
            String errType = string(error.get("type"));
            String message = string(error.get("message"));
            String detail = ModelHttp.snippet(errType.isBlank() ? root.toString() : errType + ": " + message);
            if (NativeToolRejectedException.rejected(400, detail)) {
                throw new NativeToolRejectedException("服务端拒绝原生工具或协议");
            }
            ModelFailureKind kind;
            if ("overloaded_error".equals(errType) || "rate_limit_error".equals(errType)
                || "api_error".equals(errType)) {
                kind = ModelFailureKind.TRANSIENT;
            } else if ("invalid_request_error".equals(errType) || "request_too_large".equals(errType)) {
                kind = ModelFailureSignals.statusKind(400, detail);
            } else {
                kind = ModelFailureSignals.contextOverflow(400, detail) ? ModelFailureKind.CONTEXT_OVERFLOW
                    : ModelFailureKind.PERMANENT;
            }
            throw new ModelClientException(kind, "模型流返回错误：" + detail);
        }

        private void mergeUsage(JsonNode node) {
            if (node == null || !node.isObject()) {
                return;
            }
            node.properties().forEach(entry -> {
                JsonNode val = entry.getValue();
                if (val != null && val.isNumber() && val.asDouble() >= 0) {
                    mergedUsage.put(entry.getKey(), val.numberValue());
                }
            });
        }

        ModelResponse finish() {
            if ("pause_turn".equals(stopReason)) {
                throw new NativeToolRejectedException("原生工具需要分段续传，当前自动切回 Pilot 工具");
            }
            ModelResponse.FinishReason reason = finishReason(stopReason);
            List<ToolCall> calls = parseStreamToolCalls(reason);
            int beforeCitations = text.length();
            NativeModelCitations.append(text, citations, url -> url.replace("(", "%28").replace(")", "%29"));
            if (text.length() > beforeCitations) {
                emit(textDelta, text.substring(beforeCitations));
            }
            ModelUsage usage = mergedUsage.isEmpty() ? null : ModelUsageParser.parse(mapper.valueToTree(mergedUsage));
            String sig = signature.toString();
            String reasoning = sig.isBlank() ? "" : thinking + THINKING_SPLIT + sig;
            return new ModelResponse(text.toString(), calls, usage, reason, reasoning);
        }

        private List<ToolCall> parseStreamToolCalls(ModelResponse.FinishReason reason) {
            if (reason != ModelResponse.FinishReason.STOP && reason != ModelResponse.FinishReason.TOOL_CALLS) {
                return List.of();
            }
            List<ToolCall> calls = new ArrayList<>();
            for (StreamBlock block : blocks.values()) {
                if (!"tool_use".equals(block.type)) {
                    continue;
                }
                if (block.id.isBlank() || block.name.isBlank()) {
                    throw new ModelClientException(ModelFailureKind.PERMANENT, "模型返回的 tool_use 缺少 id 或 name");
                }
                calls.add(new ToolCall(block.id, block.name, parseBlockInput(block)));
            }
            return calls;
        }

        private Map<String, Object> parseBlockInput(StreamBlock block) {
            String raw = block.inputJson.toString();
            if (raw.isBlank()) {
                return block.initialInput != null && block.initialInput.isObject() ? objectMap(block.initialInput)
                    : Map.of();
            }
            try {
                JsonNode parsed = mapper.readTree(raw);
                if (parsed == null || !parsed.isObject()) {
                    throw new ModelClientException(ModelFailureKind.MALFORMED_TOOL_CALL, "流式工具参数必须为 JSON 对象");
                }
                return objectMap(parsed);
            } catch (ModelClientException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ModelClientException(ModelFailureKind.MALFORMED_TOOL_CALL, "流式工具参数不是合法 JSON", e);
            }
        }
    }

}
