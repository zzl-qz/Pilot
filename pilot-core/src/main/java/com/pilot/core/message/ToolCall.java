package com.pilot.core.message;

import java.util.Map;

public record ToolCall(String callId, String name, Map<String, Object> args) {
}
