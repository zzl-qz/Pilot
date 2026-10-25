package com.pilot.core.model;

import com.pilot.core.tool.ToolCall;

import java.util.List;

public record ModelResponse(String content, List<ToolCall> toolCalls) {
}
