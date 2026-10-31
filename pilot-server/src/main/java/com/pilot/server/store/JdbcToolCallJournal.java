package com.pilot.server.store;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolCallJournal;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 基于 SQLite 的幂等账本，按工作区核对文件现状后才重放。
 *
 * <p>「同一个调用」由工具名 + 规范化参数的哈希定义，和模型给的 callId 无关：
 * 重启后模型重新发起同样的写文件调用时 callId 一定不同，但参数相同。
 * 重放前重新计算相关文件的现状指纹，只要有一个文件被用户改过就放弃重放、正常执行，
 * 保证幂等不会变成「覆盖别人的修改」。
 */
public final class JdbcToolCallJournal implements ToolCallJournal {
    private final SqliteStore store;
    private final ObjectMapper mapper;
    private final String sessionId;
    private final Path workspace;

    public JdbcToolCallJournal(SqliteStore store, ObjectMapper mapper, String sessionId, Path workspace) {
        this.store = store;
        this.mapper = mapper;
        this.sessionId = sessionId;
        this.workspace = workspace == null ? null : workspace.toAbsolutePath().normalize();
    }

    @Override
    public Optional<Recorded> replay(String runId, ToolCall call) {
        String fingerprint = ToolCallJournal.fingerprint(call);
        Optional<Row> row = JdbcSupport.queryOne(store,
                "SELECT * FROM tool_call_journal WHERE run_id = ? AND fingerprint = ?",
                statement -> {
                    statement.setString(1, runId);
                    statement.setString(2, fingerprint);
                }, rows -> new Row(rows.getString("result"), rows.getString("changed_paths"),
                        rows.getString("checks"), rows.getString("post_hash")),
                "读取工具调用账本失败");
        if (row.isEmpty() || !row.get().postHash().equals(postHash(paths(row.get())))) {
            return Optional.empty();
        }
        return Optional.of(new Recorded(row.get().result(), paths(row.get()), checks(row.get()),
                fingerprint, row.get().postHash(), true));
    }

    @Override
    public void record(String runId, ToolCall call, String result, List<String> changedPaths,
                       List<String> checks, boolean replayable) {
        if (!replayable || changedPaths == null || changedPaths.isEmpty()) {
            return;
        }
        String fingerprint = ToolCallJournal.fingerprint(call);
        JdbcSupport.update(store, """
                INSERT INTO tool_call_journal
                    (run_id, session_id, fingerprint, tool, result, changed_paths, checks, post_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(run_id, fingerprint) DO UPDATE SET
                    result = excluded.result, changed_paths = excluded.changed_paths,
                    checks = excluded.checks, post_hash = excluded.post_hash
                """, statement -> {
            statement.setString(1, runId);
            statement.setString(2, sessionId);
            statement.setString(3, fingerprint);
            statement.setString(4, call.name());
            statement.setString(5, result);
            statement.setString(6, write(changedPaths));
            statement.setString(7, write(checks));
            statement.setString(8, postHash(changedPaths));
            statement.setString(9, Instant.now().toString());
        }, "写入工具调用账本失败");
    }

    @Override
    public int deleteBySession(String sessionId) {
        return JdbcSupport.update(store, "DELETE FROM tool_call_journal WHERE session_id = ?",
                statement -> statement.setString(1, sessionId), "删除工具调用账本失败: " + sessionId);
    }

    /** 现状指纹：{@code path + 内容哈希}，文件消失记为 DELETED。 */
    private String postHash(List<String> paths) {
        List<String> lines = new ArrayList<>();
        for (String path : paths) {
            lines.add(path + " " + currentHash(path));
        }
        lines.sort(String::compareTo);
        return DiffSandbox.fingerprint(String.join("\n", lines));
    }

    private String currentHash(String relative) {
        if (workspace == null) {
            return "UNKNOWN";
        }
        Path file;
        try {
            file = workspace.resolve(relative).normalize();
        } catch (RuntimeException e) {
            return "INVALID";
        }
        if (!file.startsWith(workspace)) {
            return "OUTSIDE";
        }
        if (!Files.isRegularFile(file)) {
            return "DELETED";
        }
        try {
            return DiffSandbox.fingerprint(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "UNREADABLE";
        }
    }

    private String write(List<String> values) {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        } catch (RuntimeException e) {
            return "[]";
        }
    }

    private List<String> paths(Row row) {
        return read(row.changedPaths());
    }

    private List<String> checks(Row row) {
        return read(row.checks());
    }

    private List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            String[] values = mapper.readValue(json, String[].class);
            return values == null ? List.of() : Arrays.stream(values)
                    .filter(value -> value != null && !value.isBlank()).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private record Row(String result, String changedPaths, String checks, String postHash) {
    }
}
