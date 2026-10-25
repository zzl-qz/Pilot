package com.pilot.core.tool;

import java.util.Map;

public record ToolCall(String callId, String name, Map<String, Object> args) {
}
