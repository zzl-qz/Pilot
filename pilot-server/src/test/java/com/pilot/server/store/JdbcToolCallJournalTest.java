package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.ReadToolOutputTool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolCallJournal;
import com.pilot.server.common.data.StorageHealth;

import tools.jackson.databind.json.JsonMapper;

/**
 * 幂等重放的安全性：只有文件现状仍与写入后一致时才复用结果。
 * 否则「幂等」会变成「覆盖用户后来的修改」。
 */
class JdbcToolCallJournalTest {

    @TempDir
    Path dir;

    private JdbcToolCallJournal journal(Path workspace) {
        return new JdbcToolCallJournal(new SqliteStore(dir.resolve("pilot.db")), JsonMapper.builder().build(), "ses_1",
            workspace);
    }

    private static ToolCall call(String content) {
        return new ToolCall("call_1", "write_file", Map.of("path", "a.txt", "content", content));
    }

    @Test
    void replaysWhenTheFileStillMatches() throws IOException {
        Path workspace = dir.resolve("work");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("a.txt"), "hello");
        JdbcToolCallJournal journal = journal(workspace);
        journal.record("run_1", call("hello"), "已写入 a.txt", List.of("a.txt"), List.of(), true);

        ToolCallJournal.Recorded recorded = journal.replay("run_1", call("hello")).orElseThrow();
        assertTrue(recorded.replayable());
        assertEquals("已写入 a.txt", recorded.result());
        assertEquals(List.of("a.txt"), recorded.changedPaths());
    }

    @Test
    void verifiesBinaryWritesAndReportsCorruptJournalMetadata() throws Exception {
        Path file = Files.write(dir.resolve("a.txt"), new byte[] {(byte)0xff, 0});
        JdbcToolCallJournal journal = journal(dir);
        ToolCall call = call("binary");
        journal.record("run_1", call, "已写入", List.of("a.txt"), List.of(), true);
        assertTrue(journal.replay("run_1", call).isPresent());
        Files.write(file, new byte[] {(byte)0xfe, 0});
        assertTrue(journal.replay("run_1", call).isEmpty());
        try (var connection = new SqliteStore(dir.resolve("pilot.db")).open(); var sql = connection.createStatement()) {
            sql.executeUpdate("UPDATE tool_call_journal SET changed_paths = 'invalid json'");
        }
        try {
            assertThrows(IllegalStateException.class, () -> journal.replay("run_1", call));
            assertTrue(
                StorageHealth.snapshot().issues().stream().anyMatch(issue -> issue.area().equals("tool-journal")));
        } finally {
            StorageHealth.reset();
        }
    }

    @Test
    void retrievesRawObservationWithoutReplayingTheTool() {
        JdbcToolCallJournal journal = journal(dir);
        ToolCall call = new ToolCall("call_observe", "read_file", Map.of("path", "large.txt"));
        String raw = "原始工具输出".repeat(100);

        journal.recordObservation("run_observe", call, raw);

        assertEquals(raw, journal.observation("run_observe", "call_observe").orElseThrow());
        assertTrue(journal.observation("other_run", "call_observe").isEmpty());
        var other = new JdbcToolCallJournal(new SqliteStore(dir.resolve("pilot.db")), JsonMapper.builder().build(),
            "ses_other", dir);
        assertTrue(other.observation("run_observe", "call_observe").isEmpty());
        other.recordObservation("run_observe", call, "other session cannot overwrite");
        journal.recordObservation("run_observe", call, "duplicate call cannot overwrite");
        assertEquals(raw, journal.observation("run_observe", "call_observe").orElseThrow());
        var reader = new ReadToolOutputTool(journal);
        String page =
            reader.execute(Map.of("run_id", "run_observe", "call_id", "call_observe", "offset", 3, "limit", 10));
        assertTrue(page.endsWith(raw.substring(3, 13)));
        assertTrue(page.contains("next_offset=13"));
        assertTrue(reader.execute(Map.of("run_id", "run_observe", "call_id", "call_observe", "offset", -1))
            .startsWith("ERROR:"));
    }

    @Test
    void skipsObservationsAboveTheConfiguredBound() {
        JdbcToolCallJournal journal = journal(dir);
        ToolCall call = new ToolCall("call_big", "read_file", Map.of("path", "huge.txt"));

        journal.recordObservation("run_big", call, "x".repeat(200_001));

        assertTrue(journal.observation("run_big", "call_big").isEmpty());
    }

    @Test
    void refusesToReplayAfterTheFileWasEdited() throws IOException {
        Path workspace = dir.resolve("work");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("a.txt"), "hello");
        JdbcToolCallJournal journal = journal(workspace);
        journal.record("run_1", call("hello"), "已写入 a.txt", List.of("a.txt"), List.of(), true);

        Files.writeString(workspace.resolve("a.txt"), "manual edit");

        assertTrue(journal.replay("run_1", call("hello")).isEmpty());
    }

    @Test
    void refusesToReplayAfterTheFileWasDeleted() throws IOException {
        Path workspace = dir.resolve("work");
        Files.createDirectories(workspace);
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "hello");
        JdbcToolCallJournal journal = journal(workspace);
        journal.record("run_1", call("hello"), "已写入 a.txt", List.of("a.txt"), List.of(), true);

        Files.delete(file);

        assertTrue(journal.replay("run_1", call("hello")).isEmpty());
    }

    @Test
    void scopesEntriesToTheRunAndTheExactArguments() throws IOException {
        Path workspace = dir.resolve("work");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("a.txt"), "hello");
        JdbcToolCallJournal journal = journal(workspace);
        journal.record("run_1", call("hello"), "已写入 a.txt", List.of("a.txt"), List.of(), true);

        assertTrue(journal.replay("run_2", call("hello")).isEmpty(), "换一次运行不应复用上一次的结果");
        assertTrue(journal.replay("run_1", call("other")).isEmpty(), "参数不同就是不同的调用");
    }

    @Test
    void neverReplaysWhenTheRecorderMarksItUnsafe() throws IOException {
        Path workspace = dir.resolve("work");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("a.txt"), "hello");
        JdbcToolCallJournal journal = journal(workspace);

        journal.record("run_1", call("hello"), "退出码: 0", List.of("a.txt"), List.of(), false);

        assertTrue(journal.replay("run_1", call("hello")).isEmpty());
    }
}
