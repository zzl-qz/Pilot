package com.pilot.core.model;

import com.pilot.core.loop.Message;

import java.util.List;
import java.util.function.Consumer;

public interface ModelClient {
    ModelResponse chat(List<Message> messages, List<ToolDefinition> tools);

    default ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta) {
        ModelResponse response = chat(messages, tools);
        if (textDelta != null && response.content() != null && !response.content().isEmpty()) {
            textDelta.accept(response.content());
        }
        return response;
    }
}
