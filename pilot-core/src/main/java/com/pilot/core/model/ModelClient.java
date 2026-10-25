package com.pilot.core.model;

import com.pilot.core.loop.Message;

import java.util.List;

public interface ModelClient {
    ModelResponse chat(List<Message> messages, List<ToolDefinition> tools);
}
