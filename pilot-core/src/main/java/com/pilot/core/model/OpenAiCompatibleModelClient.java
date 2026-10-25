package com.pilot.core.model;

import com.pilot.core.loop.Message;
import com.pilot.core.tool.ToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenAiCompatibleModelClient implements ModelClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleModelClient(HttpClient httpClient, ObjectMapper objectMapper, URI endpoint, String apiKey, String model) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        try {
            var body = new HashMap<String, Object>();
            body.put("model", model);
            body.put("messages", messages.stream().map(message -> Map.of("role", message.role(), "content", message.content())).toList());
            if (!tools.isEmpty()) {
                body.put("tools", tools.stream().map(tool -> Map.of("type", "function", "function", Map.of(
                        "name", tool.name(), "description", tool.description(), "parameters", tool.parameters()))).toList());
            }

            var request = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Model API returned HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode message = objectMapper.readTree(response.body()).get("choices").get(0).get("message");
            var toolCalls = new ArrayList<ToolCall>();
            JsonNode calls = message.get("tool_calls");
            if (calls != null && calls.isArray()) {
                for (JsonNode call : calls) {
                    JsonNode function = call.get("function");
                    String arguments = function.get("arguments").asText();
                    Map<String, Object> args = arguments.isBlank() ? Map.of()
                            : objectMapper.readValue(arguments, objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
                    toolCalls.add(new ToolCall(call.get("id").asText(), function.get("name").asText(), args));
                }
            }
            JsonNode content = message.get("content");
            return new ModelResponse(content == null || content.isNull() ? "" : content.asText(), toolCalls);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model API request was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Model API request failed", e);
        }
    }
}
