package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolCallJournal;

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
