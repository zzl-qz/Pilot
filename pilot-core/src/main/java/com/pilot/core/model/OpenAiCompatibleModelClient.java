package com.pilot.core.model;

import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
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

/**
 * OpenAI 兼容的 chat completions 客户端（非流式）。
 * 只负责协议转换：把 {@link Message} 序列化为请求体，把响应的 content 与 tool_calls 解析回来。
 */
public final class OpenAiCompatibleModelClient implements ModelClient {
    private static final int ERROR_SNIPPET_CHARS = 300;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;

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
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("messages", messages.stream().map(this::messagePayload).toList());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toolPayload).toList());
        }

        var request = HttpRequest.newBuilder(endpoint)
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
            throw new ModelClientException("模型请求被中断", e);
        } catch (IOException e) {
            throw new ModelClientException("模型请求失败（" + elapsedMs(startedAt) + " ms, model=" + model + "）："
                    + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelClientException("模型接口返回 HTTP " + response.statusCode()
                    + "（" + elapsedMs(startedAt) + " ms, model=" + model + "）：" + snippet(response.body()));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new ModelClientException("模型响应不是合法 JSON：" + snippet(response.body()), e);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ModelClientException("模型响应缺少 choices：" + snippet(response.body()));
        }
        JsonNode message = choices.get(0).path("message");
        if (message.isMissingNode()) {
            throw new ModelClientException("模型响应缺少 message：" + snippet(response.body()));
        }
        return new ModelResponse(text(message.get("content")), toolCalls(message.get("tool_calls")));
    }

    private Map<String, Object> messagePayload(Message message) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("role", message.role());
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
        var images = message.attachments().stream().filter(Attachment::image).toList();
        if (images.isEmpty()) {
            return message.content();
        }
        var parts = new ArrayList<Map<String, Object>>();
        if (!message.content().isBlank()) {
            parts.add(Map.of("type", "text", "text", message.content()));
        }
        for (Attachment image : images) {
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl(image))));
        }
        return parts;
    }

    private static String dataUrl(Attachment image) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(image.path()));
            return "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new ModelClientException("读取图片附件失败：" + image.name(), e);
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
        var calls = new ArrayList<ToolCall>();
        for (JsonNode item : node) {
            String callId = text(item.get("id"));
            JsonNode function = item.get("function");
            String name = function == null ? "" : text(function.get("name"));
            if (callId.isBlank() || name.isBlank()) {
                throw new ModelClientException("模型返回的 tool_call 缺少 id 或 function.name");
            }
            calls.add(new ToolCall(callId, name, arguments(function.get("arguments"))));
        }
        return List.copyOf(calls);
    }

    private Map<String, Object> arguments(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        String raw = node.isTextual() ? node.asText() : node.toString();
        if (raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (RuntimeException e) {
            throw new ModelClientException("工具调用参数不是合法 JSON：" + snippet(raw), e);
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= ERROR_SNIPPET_CHARS ? single : single.substring(0, ERROR_SNIPPET_CHARS) + "…";
    }
}
