package com.pilot.core.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;

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
    private final Set<NativeCapability> nativeCapabilities;
    private final ModelProfile profile;

    public AnthropicMessagesModelClient(HttpClient httpClient, ObjectMapper mapper, URI endpoint, String apiKey,
        String model, Duration timeout, ModelProfile profile, Set<NativeCapability> nativeCapabilities) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.endpoint = resolveEndpoint(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.profile = profile == null ? ModelProfile.generic() : profile;
        this.nativeCapabilities = nativeCapabilities == null ? Set.of() : Set.copyOf(nativeCapabilities);
    }

    static URI resolveEndpoint(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("model endpoint must not be null");
        }
        String raw = endpoint.toString();
        int queryIndex = raw.indexOf('?');
        String base = queryIndex >= 0 ? raw.substring(0, queryIndex) : raw;
        String query = queryIndex >= 0 ? raw.substring(queryIndex) : "";
        if (base.endsWith("/v1/messages")) {
            return endpoint;
        }
        if (base.endsWith("/v1/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        } else if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        } else if (base.endsWith("/v1/")) {
            base = base.substring(0, base.length() - 1);
        } else if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create((base.endsWith("/v1") ? base : base + "/v1") + "/messages" + query);
    }

    @Override
    public ModelProfile profile() {
        return profile;
    }

    @Override
    public Set<NativeCapability> nativeCapabilities() {
        return nativeCapabilities;
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        Map<String, Object> body = requestBody(messages, tools);
        HttpRequest request = HttpRequest.newBuilder(endpoint).header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01").header("Content-Type", "application/json")
            .timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(CanonicalJson.ordered(body)), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelClientException(ModelFailureKind.INTERRUPTED, "模型请求被中断", e);
        } catch (IOException e) {
            throw new ModelClientException(ModelFailureKind.NETWORK, "模型请求失败：" + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = snippet(response.body());
            throw new ModelClientException(ModelFailureSignals.statusKind(response.statusCode(), detail),
                "模型接口返回 HTTP " + response.statusCode() + "：" + detail);
        }
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "模型响应不是合法 JSON：" + snippet(response.body()), e);
        }
        if (!root.path("content").isArray()) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "模型响应缺少 content：" + snippet(response.body()));
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        LinkedHashMap<String, String> citations = new LinkedHashMap<>();
        for (JsonNode block : root.path("content")) {
            String type = string(block.get("type"));
            if ("text".equals(type)) {
                text.append(string(block.get("text")));
                collectCitations(block.get("citations"), citations);
            } else if ("tool_use".equals(type)) {
                String id = string(block.get("id"));
                String name = string(block.get("name"));
                if (id.isBlank() || name.isBlank()) {
                    throw new ModelClientException(ModelFailureKind.PERMANENT,
                        "模型返回的 tool_use 缺少 id 或 name");
                }
                calls.add(new ToolCall(id, name, objectMap(block.get("input"))));
            } else if (type.startsWith("web_search") || type.startsWith("web_fetch")) {
                collectCitations(block, citations);
            }
        }
        appendCitations(text, citations);
        ModelResponse.FinishReason reason = switch (string(root.get("stop_reason"))) {
            case "max_tokens" -> ModelResponse.FinishReason.LENGTH;
            case "tool_use" -> ModelResponse.FinishReason.TOOL_CALLS;
            case "end_turn", "stop_sequence" -> ModelResponse.FinishReason.STOP;
            default -> calls.isEmpty() ? ModelResponse.FinishReason.STOP : ModelResponse.FinishReason.TOOL_CALLS;
        };
        return new ModelResponse(text.toString(), calls, ModelUsageParser.parse(root.get("usage")), reason);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta,
        Consumer<String> reasoningDelta) {
        ModelResponse response = chat(messages, tools);
        if (textDelta != null && response.content() != null && !response.content().isEmpty()) {
            textDelta.accept(response.content());
        }
        return response;
    }

    Map<String, Object> requestBody(List<Message> messages, List<ToolDefinition> definitions) {
        List<String> system = new ArrayList<>();
        List<Map<String, Object>> conversation = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if ("system".equals(message.role())) {
                if (!message.content().isBlank()) {
                    system.add(message.content());
                }
            } else if ("tool".equals(message.role())) {
                List<Map<String, Object>> results = new ArrayList<>();
                while (index < messages.size() && "tool".equals(messages.get(index).role())) {
                    Message result = messages.get(index++);
                    results.add(Map.of("type", "tool_result", "tool_use_id", result.toolCallId(),
                        "content", result.content()));
                }
                index--;
                conversation.add(Map.of("role", "user", "content", results));
            } else if (message.hasToolCalls()) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                if (!message.content().isBlank()) {
                    blocks.add(Map.of("type", "text", "text", message.content()));
                }
                for (ToolCall call : message.toolCalls()) {
                    blocks.add(Map.of("type", "tool_use", "id", call.callId(), "name", call.name(),
                        "input", call.args() == null ? Map.of() : call.args()));
                }
                conversation.add(Map.of("role", "assistant", "content", blocks));
            } else {
                conversation.add(Map.of("role", message.role(), "content", content(message)));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 16_000);
        if (!system.isEmpty()) {
            body.put("system", String.join("\n\n", system));
        }
        body.put("messages", conversation);
        List<Map<String, Object>> requestTools = new ArrayList<>();
        if (definitions != null) {
            definitions.stream().filter(definition -> !NativeToolNames.isHidden(definition.name(), nativeCapabilities))
                .forEach(definition -> requestTools.add(Map.of("name", definition.name(),
                    "description", definition.description(), "input_schema", definition.parameters())));
        }
        if (nativeCapabilities.contains(NativeCapability.WEB_SEARCH)) {
            // _20260209 及之后的版本默认只从 code execution 调用；不支持程序化工具调用的模型
            // 必须显式声明 allowed_callers，否则官方端点会返回 400。
            requestTools.add(Map.of("type", "web_search_20260209", "name", "web_search",
                "allowed_callers", List.of("direct")));
        }
        if (nativeCapabilities.contains(NativeCapability.WEB_FETCH)) {
            requestTools.add(Map.of("type", "web_fetch_20260209", "name", "web_fetch",
                "allowed_callers", List.of("direct")));
        }
        if (!ModelRequestScope.current().toolsDisabled() && !requestTools.isEmpty()) {
            body.put("tools", requestTools);
        }
        return body;
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
        String url = string(value.get("url"));
        String title = string(value.get("title"));
        try {
            URI citation = URI.create(url);
            String scheme = citation.getScheme();
            if (citation.getHost() != null && citation.getUserInfo() == null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                citations.putIfAbsent(citation.toASCIIString(), title.isBlank() ? citation.toASCIIString() : title);
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore malformed provider citation metadata.
        }
        value.properties().forEach(entry -> collectCitations(entry.getValue(), citations));
    }

    private static void appendCitations(StringBuilder text, Map<String, String> citations) {
        if (citations.isEmpty()) {
            return;
        }
        text.append("\n\n来源：\n");
        citations.forEach((url, title) -> text.append("- [").append(title.replace("[", "\\[").replace("]", "\\]")
            .replace("\n", " ")).append("](").append(url.replace("(", "%28").replace(")", "%29"))
            .append(")\n"));
    }

    private static String string(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString();
    }

    private static String snippet(String body) {
        String value = body == null ? "" : body.replaceAll("\\s+", " ").strip();
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
