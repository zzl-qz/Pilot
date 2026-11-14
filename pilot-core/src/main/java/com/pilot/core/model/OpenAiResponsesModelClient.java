package com.pilot.core.model;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;

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
    private Duration idleTimeout;
    private ModelProfile profile;
    private final Set<NativeCapability> capabilities;

    public OpenAiResponsesModelClient(HttpClient httpClient, ObjectMapper mapper, URI endpoint, String apiKey,
        String model, Duration timeout, ModelProfile profile) {
        this(httpClient, mapper, endpoint, apiKey, model, timeout, profile, Set.of(NativeCapability.WEB_SEARCH));
    }

    public OpenAiResponsesModelClient(HttpClient httpClient, ObjectMapper mapper, URI endpoint, String apiKey,
        String model, Duration timeout, ModelProfile profile, Set<NativeCapability> capabilities) {
        this.capabilities = Set.copyOf(capabilities);
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.endpoint = resolveEndpoint(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.idleTimeout = timeout;
        this.profile = profile == null ? ModelProfile.generic() : profile;
    }

    static URI resolveEndpoint(URI endpoint) {
        return ModelHttp.nativeEndpoint(endpoint, "/responses", false);
    }

    public OpenAiResponsesModelClient withIdleTimeout(Duration value) {
        this.idleTimeout = java.util.Objects.requireNonNull(value);
        return this;
    }

    @Override
    public ModelProfile profile() {
        return profile;
    }

    @Override
    public Set<NativeCapability> nativeCapabilities() {
        return capabilities;
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        var request = ModelHttp.jsonPost(endpoint, timeout, mapper, requestBody(messages, tools))
            .header("Authorization", "Bearer " + apiKey).build();
        var response = ModelHttp.sendNative(httpClient, request, idleTimeout);
        JsonNode root = ModelHttp.parseJson(mapper, response.body());
        if (!root.path("output").isArray() && root.path("choices").isArray()) {
            throw new NativeToolRejectedException("端点忽略了 Responses 协议，已返回聊天协议响应", true);
        }
        if ("failed".equals(root.path("status").asString()) || root.hasNonNull("error")) {
            if (NativeToolRejectedException.rejected(400, root.path("error").toString())) {
                throw new NativeToolRejectedException("服务端执行时拒绝原生工具");
            }
            throw new ModelClientException(ModelFailureKind.PERMANENT, "服务端未完成模型请求");
        }
        if (!root.path("output").isArray()) {
            throw new ModelClientException(ModelFailureKind.PERMANENT,
                "模型响应缺少 output：" + ModelHttp.snippet(response.body()));
        }
        ModelResponse.FinishReason reason = switch (string(root.get("status"))) {
            case "completed" -> ModelResponse.FinishReason.STOP;
            case "incomplete" -> switch (string(root.path("incomplete_details").get("reason"))) {
                case "max_output_tokens" -> ModelResponse.FinishReason.LENGTH;
                case "content_filter" -> ModelResponse.FinishReason.CONTENT_FILTER;
                default -> ModelResponse.FinishReason.ERROR;
            };
            case "failed", "cancelled" -> ModelResponse.FinishReason.ERROR;
            default -> throw new ModelClientException(ModelFailureKind.INCOMPLETE, "模型响应尚未完成或缺少有效 status，未提交本次输出或执行工具");
        };
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
            } else if ("function_call".equals(type) && reason == ModelResponse.FinishReason.STOP) {
                String id = string(item.get("call_id"));
                String name = string(item.get("name"));
                if (id.isBlank() || name.isBlank()) {
                    throw new ModelClientException(ModelFailureKind.PERMANENT, "模型返回的 function_call 缺少 call_id 或 name");
                }
                calls.add(new ToolCall(id, name, arguments(item.get("arguments"))));
            }
        }
        NativeModelCitations.append(text, citations, UnaryOperator.identity());
        ModelUsage usage = ModelUsageParser.parse(root.get("usage"));
        return new ModelResponse(text.toString(), calls, usage,
            reason == ModelResponse.FinishReason.STOP && !calls.isEmpty() ? ModelResponse.FinishReason.TOOL_CALLS
                : reason);
    }

    Map<String, Object> requestBody(List<Message> messages, List<ToolDefinition> definitions) {
        ModelCapacity capacity = ModelCapacity.configured();
        RequestBudget.enforce(messages, definitions, capacity);
        List<Map<String, Object>> input = new ArrayList<>();
        for (Message message : messages) {
            if ("tool".equals(message.role())) {
                input.add(Map.of("type", "function_call_output", "call_id", message.toolCallId(), "output",
                    message.content()));
            } else if (message.hasToolCalls()) {
                if (!message.content().isBlank()) {
                    input.add(Map.of("role", "assistant", "content", message.content()));
                }
                for (ToolCall call : message.toolCalls()) {
                    input.add(Map.of("type", "function_call", "call_id", call.callId(), "name", call.name(),
                        "arguments", mapper
                            .writeValueAsString(CanonicalJson.ordered(call.args() == null ? Map.of() : call.args()))));
                }
            } else {
                input.add(Map.of("role", message.role(), "content", content(message)));
            }
        }
        List<Map<String, Object>> requestTools = new ArrayList<>();
        if (definitions != null) {
            definitions.stream()
                .filter(definition -> !NativeToolNames.isHidden(definition.name(), nativeCapabilities()))
                .forEach(definition -> requestTools.add(Map.of("type", "function", "name", definition.name(),
                    "description", definition.description(), "parameters", definition.parameters())));
        }
        if (capabilities.contains(NativeCapability.WEB_SEARCH)) {
            requestTools.add(Map.of("type", "web_search"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_output_tokens", capacity.outputTokens());
        body.put("store", false);
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
            NativeModelCitations.add(string(annotation.get("url")), string(annotation.get("title")), citations);
        }
    }

    private static String string(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asString();
    }

}
