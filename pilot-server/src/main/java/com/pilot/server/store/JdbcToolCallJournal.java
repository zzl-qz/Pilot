package com.pilot.server.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolCallJournal;
import com.pilot.core.util.Sha256;
import com.pilot.server.maintenance.StorageHealth;

import tools.jackson.databind.ObjectMapper;

/**
 * 基于 SQLite 的幂等账本，按工作区核对文件现状后才重放。
 *
 * <p>「同一个调用」由工具名 + 规范化参数的哈希定义，和模型给的 callId 无关：
 * 重启后模型重新发起同样的写文件调用时 callId 一定不同，但参数相同。
 * 重放前重新计算相关文件的现状指纹，只要有一个文件被用户改过就放弃重放、正常执行，
 * 保证幂等不会变成「覆盖别人的修改」。
 */
public final class JdbcToolCallJournal implements ToolCallJournal {
    private static final int MAX_OBSERVATION_CHARS = 200_000;
    /** 现状指纹里代替内容哈希的标记：文件已经不在了。 */
    private static final String DELETED = "DELETED";
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
        Optional<Row> row = queryOne(
            "SELECT * FROM tool_call_journal WHERE run_id = ? AND fingerprint = ? AND session_id = ?", statement -> {
                statement.setString(1, runId);
                statement.setString(2, fingerprint);
                statement.setString(3, sessionId);
            }, rows -> new Row(rows.getString("result"), rows.getString("changed_paths"), rows.getString("checks"),
                rows.getString("post_hash")),
            "读取工具调用账本失败");
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row saved = row.get();
        List<String> paths = read(saved.changedPaths());
        if (paths.isEmpty() || saved.postHash() == null) {
            throw unavailable("工具调用账本缺少文件核对依据", null);
        }
        if (!Objects.equals(saved.postHash(), postHash(paths))) {
            return Optional.empty();
        }
        return Optional
            .of(new Recorded(saved.result(), paths, read(saved.checks()), fingerprint, saved.postHash(), true));
    }

    @Override
    public void record(String runId, ToolCall call, String result, List<String> changedPaths, List<String> checks,
        boolean replayable) {
        if (!replayable || changedPaths == null || changedPaths.isEmpty()) {
            return;
        }
        String fingerprint = ToolCallJournal.fingerprint(call);
        update("""
            INSERT INTO tool_call_journal
                (run_id, session_id, fingerprint, tool, result, changed_paths, checks, post_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(run_id, fingerprint) DO UPDATE SET
                result = excluded.result, changed_paths = excluded.changed_paths,
                checks = excluded.checks, post_hash = excluded.post_hash
            WHERE tool_call_journal.session_id = excluded.session_id
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
    public void recordObservation(String runId, ToolCall call, String result) {
        if (runId == null || runId.isBlank() || call == null || result == null
            || result.length() > MAX_OBSERVATION_CHARS) {
            return;
        }
        update("""
            INSERT INTO tool_observations(run_id, session_id, call_id, fingerprint, tool, result, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(run_id, call_id) DO NOTHING
            """, statement -> {
            statement.setString(1, runId);
            statement.setString(2, sessionId);
            statement.setString(3, call.callId());
            statement.setString(4, ToolCallJournal.fingerprint(call));
            statement.setString(5, call.name());
            statement.setString(6, result);
            statement.setString(7, Instant.now().toString());
        }, "保存工具观察记录失败");
    }

    @Override
    public Optional<String> observation(String runId, String callId) {
        return queryOne("SELECT result FROM tool_observations WHERE run_id = ? AND call_id = ? AND session_id = ?",
            statement -> {
                statement.setString(1, runId);
                statement.setString(2, callId);
                statement.setString(3, sessionId);
            }, rows -> rows.getString("result"), "读取工具观察记录失败");
    }

    @Override
    public int deleteBySession(String sessionId) {
        int observations = update("DELETE FROM tool_observations WHERE session_id = ?",
            statement -> statement.setString(1, sessionId), "删除工具观察记录失败: " + sessionId);
        return observations + update("DELETE FROM tool_call_journal WHERE session_id = ?",
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
            throw unavailable("工具调用账本缺少工作区", null);
        }
        try {
            Path file = workspace.resolve(relative).normalize();
            if (!file.startsWith(workspace)) {
                throw new IOException("文件路径越出工作区");
            }
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                return DELETED;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().startsWith(workspace.toRealPath())) {
                throw new IOException("文件无法可靠读取");
            }
            return Sha256.hex(file);
        } catch (IOException | RuntimeException e) {
            throw unavailable("无法核对工具调用涉及的文件", e);
        }
    }

    private String write(List<String> values) {
        return mapper.writeValueAsString(values == null ? List.of() : values);
    }

    private List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            String[] values = mapper.readValue(json, String[].class);
            return values == null ? List.of()
                : Arrays.stream(values).filter(value -> value != null && !value.isBlank()).toList();
        } catch (RuntimeException e) {
            throw unavailable("工具调用账本内容损坏", e);
        }
    }

    private int update(String sql, JdbcSupport.Binder binder, String failure) {
        try {
            return JdbcSupport.update(store, sql, binder, failure);
        } catch (RuntimeException e) {
            throw unavailable(failure, e);
        }
    }

    private <T> Optional<T> queryOne(String sql, JdbcSupport.Binder binder, JdbcSupport.RowMapper<T> mapper,
        String failure) {
        try {
            return JdbcSupport.queryOne(store, sql, binder, mapper, failure);
        } catch (RuntimeException e) {
            throw unavailable(failure, e);
        }
    }

    private IllegalStateException unavailable(String message, Exception cause) {
        StorageHealth.markDegraded("tool-journal", message + "（" + sessionId + "）");
        return new IllegalStateException(message, cause);
    }

    private record Row(String result, String changedPaths, String checks, String postHash) {
    }
}
