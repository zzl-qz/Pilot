package com.pilot.core.trace;

import tools.jackson.databind.ObjectMapper;
import com.pilot.core.tool.ToolCall;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** 每次工具执行一行 JSONL；每行独立追加，摘要不落入完整工具输出。 */
public final class JsonlTraceRecorder implements TraceRecorder {
    private final Path file;
    private final ObjectMapper mapper;

    public JsonlTraceRecorder(Path file, ObjectMapper mapper) {
        this.file = file.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override
    public synchronized void record(ToolCall call, String result, long latencyMs) {
        try {
            Files.createDirectories(file.getParent());
            String args = mapper.writeValueAsString(call.args() == null ? Map.of() : call.args());
            String line = mapper.writeValueAsString(Map.of(
                    "callId", call.callId(), "name", call.name(), "argsSummary", oneLine(args, 300),
                    "resultSummary", oneLine(result, 300), "latencyMs", latencyMs,
                    "decision", decision(result)));
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write tool trace", e);
        }
    }

    private static String decision(String result) {
        if (result == null) return "EXECUTED";
        if (result.startsWith("ERROR: 权限拒绝") || result.startsWith("ERROR: 用户拒绝")) return "DENIED";
        if (result.startsWith("ERROR")) return "FAILED";
        return "EXECUTED";
    }

    private static String oneLine(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }
}
