package com.pilot.core.model;

import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.ContextCompactor;
import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    /** 是否给 system 提示词打显式缓存标记；默认关闭，只在端点支持 cache_control 时启用。 */
    private volatile boolean promptCaching;

    public OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper,
                                       URI endpoint, String apiKey, String model) {
        this(httpClient, objectMapper, endpoint, apiKey, model, Duration.ofSeconds(90));
    }

    public OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper,
                                       URI endpoint, String apiKey, String model, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = resolveEndpoint(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = requestTimeout;
    }

    /**
     * 打开后给 system 提示词加 {@code cache_control: ephemeral}，长系统提示词在多轮里只按缓存价计费。
     * 只应对支持该字段的端点开启；OpenAI 官方端点自动缓存，保持关闭即可。
     */
    public OpenAiCompatibleModelClient withPromptCaching(boolean enabled) {
        this.promptCaching = enabled;
        return this;
    }

    /**
     * 兼容常见的配置写法：服务根地址或 {@code /v1} 结尾时补齐 chat completions 路径。
     * 完整地址原样使用，避免猜错第三方网关的自定义路径。
     */
    static URI resolveEndpoint(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("model endpoint must not be null");
        }
        String path = endpoint.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return appendPath(endpoint, "/v1/chat/completions");
        }
        if ("/v1".equals(path) || "/v1/".equals(path)) {
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
        LinkedHashMap<String,Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages.stream().map(this::messagePayload).toList());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toolPayload).toList());
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelClientException(ModelFailureKind.INTERRUPTED, "模型请求被中断", e);
        } catch (IOException e) {
            throw new ModelClientException(ModelFailureKind.NETWORK,
                    "模型请求失败（" + elapsedMs(startedAt) + " ms, model=" + model + "）："
                            + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelClientException(statusKind(response.statusCode()),
                    "模型接口返回 HTTP " + response.statusCode()
                            + "（" + elapsedMs(startedAt) + " ms, model=" + model + "）：" + snippet(response.body()));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT,
                    "模型响应不是合法 JSON：" + snippet(response.body()), e);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ModelClientException(ModelFailureKind.PERMANENT,
                    "模型响应缺少 choices：" + snippet(response.body()));
        }
        JsonNode message = choices.get(0).path("message");
        if (message.isMissingNode()) {
            throw new ModelClientException(ModelFailureKind.PERMANENT,
                    "模型响应缺少 message：" + snippet(response.body()));
        }
        String content = text(message.get("content"));
        List<ToolCall> calls = toolCalls(message.get("tool_calls"));
        ModelResponse.FinishReason finishReason = ModelResponse.FinishReason.fromWire(
                text(choices.get(0).get("finish_reason")));
        if (finishReason == ModelResponse.FinishReason.UNKNOWN) {
            finishReason = calls.isEmpty() ? ModelResponse.FinishReason.STOP : ModelResponse.FinishReason.TOOL_CALLS;
        }
        return new ModelResponse(content, calls, usage(root.get("usage"), messages, content, calls), finishReason);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta) {
        LinkedHashMap<String,Object> body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages.stream().map(this::messagePayload).toList());
        body.put("stream", true);
        // OpenAI 只有显式请求才在流末尾返回 usage。第三方兼容端点不一定接受这个字段，
        // 因此只对官方域名启用；其他端点若主动返回 usage 仍会被解析。
        if ("api.openai.com".equalsIgnoreCase(endpoint.getHost())) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toolPayload).toList());
            body.put("tool_choice", "auto");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        long startedAt = System.nanoTime();
        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream stream = response.body()) {
                    throw new ModelClientException(statusKind(response.statusCode()),
                            "模型接口返回 HTTP " + response.statusCode() + "："
                                    + snippet(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
            StringBuilder content = new StringBuilder();
            LinkedHashMap<Integer,Map<String,Object>> toolParts = new LinkedHashMap<Integer, Map<String, Object>>();
            ModelUsage reportedUsage = null;
            ModelResponse.FinishReason finishReason = ModelResponse.FinishReason.UNKNOWN;
            try (BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).strip();
                    if (data.equals("[DONE]")) break;
                    JsonNode root = objectMapper.readTree(data);
                    ModelUsage chunkUsage = reportedUsage(root.get("usage"));
                    if (chunkUsage != null) {
                        reportedUsage = chunkUsage;
                    }
                    JsonNode choices = root.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) continue;
                    String finish = text(choices.get(0).get("finish_reason"));
                    if (!finish.isBlank()) finishReason = ModelResponse.FinishReason.fromWire(finish);
                    JsonNode delta = choices.get(0).path("delta");
                    JsonNode text = delta.get("content");
                    if (text != null && text.isString()) {
                        String chunk = text.asString();
                        content.append(chunk);
                        if (textDelta != null && !chunk.isEmpty()) textDelta.accept(chunk);
                    }
                    JsonNode calls = delta.path("tool_calls");
                    if (calls.isArray()) {
                        for (JsonNode call : calls) {
                            int index = call.path("index").asInt();
                            @SuppressWarnings("unchecked")
                            Map<String, Object> part = toolParts.computeIfAbsent(index, ignored -> new LinkedHashMap<>());
                            JsonNode id = call.get("id");
                            if (id != null) part.put("id", part.getOrDefault("id", "") + id.asString());
                            JsonNode function = call.path("function");
                            if (function.has("name")) part.put("name", part.getOrDefault("name", "") + function.path("name").asString());
                            if (function.has("arguments")) part.put("arguments", part.getOrDefault("arguments", "") + function.path("arguments").asString());
                        }
                    }
                }
            }
            List<ToolCall> calls = toolParts.values().stream().map(part -> {
                try {
                    String args = (String) part.getOrDefault("arguments", "{}");
                    JsonNode parsed = objectMapper.readTree(args);
                    return new ToolCall((String) part.get("id"), (String) part.get("name"), objectMapper.convertValue(parsed, Map.class));
                } catch (Exception e) {
                    throw new ModelClientException(ModelFailureKind.PERMANENT, "流式工具参数不是合法 JSON", e);
                }
            }).toList();
            ModelUsage finalUsage = reportedUsage != null
                    ? reportedUsage
                    : estimatedUsage(messages, content.toString(), calls);
            if (finishReason == ModelResponse.FinishReason.UNKNOWN) {
                finishReason = calls.isEmpty() ? ModelResponse.FinishReason.STOP : ModelResponse.FinishReason.TOOL_CALLS;
            }
            return new ModelResponse(content.toString(), calls, finalUsage, finishReason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelClientException(ModelFailureKind.INTERRUPTED, "模型请求被中断", e);
        } catch (IOException e) {
            // 流式响应读到一半断开（服务端重启、代理断连、本地网络抖动）也归网络类：
            // 上层会退避重连，而不是把这个任务判死。
            throw new ModelClientException(ModelFailureKind.NETWORK,
                    "流式模型请求失败（" + elapsedMs(startedAt) + " ms）：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> messagePayload(Message message) {
        LinkedHashMap<String,Object> payload = new LinkedHashMap<String, Object>();
        payload.put("role", message.role());
        // 显式缓存标记只对支持 cache_control 的端点开启（Anthropic 兼容网关）。
        // OpenAI 官方端点会自动缓存长前缀，不需要也不能接受这个字段。
        if (promptCaching && "system".equals(message.role()) && !message.content().isBlank()) {
            payload.put("content", List.of(Map.of(
                    "type", "text",
                    "text", message.content(),
                    "cache_control", Map.of("type", "ephemeral"))));
            return payload;
        }
        if (message.hasToolCalls()) {
            payload.put("content", message.content().isBlank() ? null : message.content());
            payload.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.callId(),
                    "type", "function",
                    "function", Map.of(
                            "name", call.name(),
                            "arguments", objectMapper.writeValueAsString(
                                    call.args() == null ? Map.of() : call.args())))).toList());
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
        ArrayList<Map<String,Object>> parts = new ArrayList<Map<String, Object>>();
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
     * 用户上传的图片丢失时直接报错（用户需要知道）；工具产出的图片只是一次性观察，
     * 文件被清理后改成文字说明，不让整段历史因此再也发不出去。
     */
    private static Map<String, Object> imagePart(Message message, Attachment image) {
        if (message.isToolImages() && !Files.isRegularFile(Path.of(image.path()))) {
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
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", tool.parameters()));
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
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        long input = longValue(node, "prompt_tokens", "input_tokens");
        long output = longValue(node, "completion_tokens", "output_tokens");
        JsonNode details = node.path("completion_tokens_details");
        if (details.isMissingNode() || details.isNull()) {
            details = node.path("output_tokens_details");
        }
        long reasoning = longValue(details, "reasoning_tokens");
        if (input <= 0 && output <= 0 && reasoning <= 0) {
            return null;
        }
        return new ModelUsage(input, output, reasoning, true);
    }

    private static long longValue(JsonNode node, String... names) {
        if (node == null) return 0L;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) {
                return Math.max(0L, value.asLong());
            }
        }
        return 0L;
    }

    private ModelUsage estimatedUsage(List<Message> messages, String content, List<ToolCall> calls) {
        long output = ContextCompactor.estimateTokens(content);
        for (ToolCall call : calls == null ? List.<ToolCall>of() : calls) {
            output += ContextCompactor.estimateTokens(call.name());
            output += ContextCompactor.estimateTokens(objectMapper.writeValueAsString(
                    call.args() == null ? Map.of() : call.args()));
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
            return objectMapper.readValue(raw, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (RuntimeException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT,
                    "工具调用参数不是合法 JSON：" + snippet(raw), e);
        }
    }

    /**
     * HTTP 状态码分类。连着但被拒绝（401/403/404/400/422）重试没有意义；
     * 408、429、5xx 是服务端暂时性故障，值得退避后再试。
     */
    private static ModelFailureKind statusKind(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500
                ? ModelFailureKind.TRANSIENT
                : ModelFailureKind.PERMANENT;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
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
