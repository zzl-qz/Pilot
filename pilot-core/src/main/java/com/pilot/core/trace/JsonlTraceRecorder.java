package com.pilot.core.trace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import com.pilot.core.message.ToolCall;
import com.pilot.core.tool.ToolResults;
import com.pilot.core.util.Texts;

import tools.jackson.databind.ObjectMapper;

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
            String line = mapper.writeValueAsString(Map.of("callId", call.callId(), "name", call.name(), "argsSummary",
                Texts.oneLine(args, 300), "resultSummary", Texts.oneLine(result, 300), "latencyMs", latencyMs,
                "decision", decision(result).name()));
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write tool trace", e);
        }
    }

    /** 写进 trace 的 decision 字段；名字就是落盘的字符串。 */
    private enum Decision {
        EXECUTED, DENIED, FAILED
    }

    private static Decision decision(String result) {
        if (result == null) {
            return Decision.EXECUTED;
        }
        if (ToolResults.isDenied(result)) {
            return Decision.DENIED;
        }
        if (ToolResults.isError(result)) {
            return Decision.FAILED;
        }
        return Decision.EXECUTED;
    }

}
