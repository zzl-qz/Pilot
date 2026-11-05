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

/** OpenAI Responses API adapter with provider-hosted web search. */
public final class OpenAiResponsesModelClient implements ModelClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private ModelProfile profile;

    public OpenAiResponsesModelClient(HttpClient httpClient, ObjectMapper mapper, URI endpoint, String apiKey,
        String model, Duration timeout, ModelProfile profile) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.endpoint = resolveEndpoint(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.profile = profile == null ? ModelProfile.generic() : profile;
    }

    static URI resolveEndpoint(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("model endpoint must not be null");
        }
        String raw = endpoint.toString();
        int queryIndex = raw.indexOf('?');
        String base = queryIndex >= 0 ? raw.substring(0, queryIndex) : raw;
        String query = queryIndex >= 0 ? raw.substring(queryIndex) : "";
        if (base.endsWith("/v1/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        } else if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        } else if (base.endsWith("/v1/")) {
            base = base.substring(0, base.length() - 1);
        } else if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.endsWith("/responses")) {
            base += base.endsWith("/v1") ? "/responses" : "/v1/responses";
        }
        return URI.create(base + query);
    }

    @Override
    public ModelProfile profile() {
        return profile;
    }

    @Override
    public Set<NativeCapability> nativeCapabilities() {
        return Set.of(NativeCapability.WEB_SEARCH);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        Map<String, Object> body = requestBody(messages, tools);
        HttpRequest request = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json").timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(CanonicalJson.ordered(body)),
                StandardCharsets.UTF_8))
            .build();
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
        if (!root.path("output").isArray()) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "模型响应缺少 output：" + snippet(response.body()));
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        LinkedHashMap<String, String> citations = new LinkedHashMap<>();
        for (JsonNode item : root.path("output")) {
            String type = string(item.get("type"));
            if ("message".equals(type)) {
                for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(string(part.get("type")))) {
                        text.append(string(part.get("text")));
                        collectCitations(part.path("annotations"), citations);
                    }
                }
            } else if ("function_call".equals(type)) {
                String id = string(item.get("call_id"));
                String name = string(item.get("name"));
                if (id.isBlank() || name.isBlank()) {
                    throw new ModelClientException(ModelFailureKind.PERMANENT,
                        "模型返回的 function_call 缺少 call_id 或 name");
                }
                calls.add(new ToolCall(id, name, arguments(item.get("arguments"))));
            }
        }
        appendCitations(text, citations);
        ModelResponse.FinishReason reason = "incomplete".equals(string(root.get("status")))
            ? ModelResponse.FinishReason.LENGTH
            : calls.isEmpty() ? ModelResponse.FinishReason.STOP : ModelResponse.FinishReason.TOOL_CALLS;
        ModelUsage usage = ModelUsageParser.parse(root.get("usage"));
        return new ModelResponse(text.toString(), calls, usage, reason);
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
        List<Map<String, Object>> input = new ArrayList<>();
        for (Message message : messages) {
            if ("tool".equals(message.role())) {
                input.add(Map.of("type", "function_call_output", "call_id", message.toolCallId(),
                    "output", message.content()));
            } else if (message.hasToolCalls()) {
                if (!message.content().isBlank()) {
                    input.add(Map.of("role", "assistant", "content", message.content()));
                }
                for (ToolCall call : message.toolCalls()) {
                    input.add(Map.of("type", "function_call", "call_id", call.callId(), "name", call.name(),
                        "arguments", mapper.writeValueAsString(CanonicalJson.ordered(call.args() == null ? Map.of() : call.args()))));
                }
            } else {
                input.add(Map.of("role", message.role(), "content", content(message)));
            }
        }
        List<Map<String, Object>> requestTools = new ArrayList<>();
        if (definitions != null) {
            definitions.stream().filter(definition -> !NativeToolNames.isHidden(definition.name(), nativeCapabilities()))
                .forEach(definition -> requestTools.add(Map.of("type", "function", "name", definition.name(),
                    "description", definition.description(), "parameters", definition.parameters())));
        }
        requestTools.add(Map.of("type", "web_search"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("tools", requestTools);
        if (ModelRequestScope.current().toolsDisabled()) {
            body.put("tool_choice", "none");
            body.remove("tools");
        }
        return body;
    }

    private Object content(Message message) {
        Object compatible = OpenAiCompatibleModelClient.contentFor(message);
        if (!(compatible instanceof List<?> parts)) {
            return compatible;
        }
        List<Map<String, Object>> input = new ArrayList<>();
        for (Object value : parts) {
            if (!(value instanceof Map<?, ?> part)) {
                continue;
            }
            if ("text".equals(part.get("type"))) {
                input.add(Map.of("type", "input_text", "text", part.get("text")));
            } else if ("image_url".equals(part.get("type")) && part.get("image_url") instanceof Map<?, ?> image) {
                input.add(Map.of("type", "input_image", "image_url", image.get("url")));
            }
        }
        return input;
    }

    private Map<String, Object> arguments(JsonNode value) {
        String json = value == null || value.isNull() ? "{}" : value.isString() ? value.asString() : value.toString();
        try {
            return mapper.readValue(json,
                mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (RuntimeException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "工具调用参数不是合法 JSON", e);
        }
    }

    private static void collectCitations(JsonNode annotations, Map<String, String> citations) {
        if (annotations == null || !annotations.isArray()) {
            return;
        }
        for (JsonNode annotation : annotations) {
            if (!"url_citation".equals(string(annotation.get("type")))) {
                continue;
            }
            String url = string(annotation.get("url"));
            String title = string(annotation.get("title"));
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
        }
    }

    private static void appendCitations(StringBuilder text, Map<String, String> citations) {
        if (citations.isEmpty()) {
            return;
        }
        text.append("\n\n来源：\n");
        citations.forEach((url, title) -> text.append("- [").append(markdown(title)).append("](").append(url)
            .append(")\n"));
    }

    private static String markdown(String value) {
        return value.replace("[", "\\[").replace("]", "\\]").replace("\n", " ");
    }

    private static String string(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString();
    }

    private static String snippet(String body) {
        String value = body == null ? "" : body.replaceAll("\\s+", " ").strip();
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
