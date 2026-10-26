package com.pilot.core.trace;

import com.pilot.core.tool.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlTraceRecorderTest {
    @TempDir
    Path tempDir;

    @Test
    void appendsOneLinePerToolCall() throws IOException {
        Path file = tempDir.resolve("nested").resolve("trace.jsonl");
        var recorder = new JsonlTraceRecorder(file, JsonMapper.builder().build());

        recorder.record(new ToolCall("c1", "read_file", Map.of("path", "a.txt")), "读到 12 行", 5L);
        recorder.record(new ToolCall("c2", "run_command", Map.of("command", "mvnw test")),
                "ERROR: 权限拒绝：READ_ONLY", 9L);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        // 每次调用独立一行，字段可被下游解析
        assertTrue(lines.get(0).contains("read_file"));
        assertTrue(lines.get(0).contains("EXECUTED"));
        assertTrue(lines.get(1).contains("DENIED"));
    }

    @Test
    void truncatesLongPayloadsToKeepLinesReadable() throws IOException {
        Path file = tempDir.resolve("trace.jsonl");
        var recorder = new JsonlTraceRecorder(file, JsonMapper.builder().build());

        recorder.record(new ToolCall("c1", "read_file", Map.of("path", "a.txt")), "x".repeat(5_000), 1L);

        String line = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(line.length() < 1_500, "单行 trace 应截断，实际长度 " + line.length());
        assertTrue(line.contains("…"), "被截断的内容应带省略标记");
    }
}
