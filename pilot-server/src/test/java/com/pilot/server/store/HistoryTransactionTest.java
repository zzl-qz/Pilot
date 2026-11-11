package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.event.RunCompleted;
import com.pilot.core.loop.Message;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;

import tools.jackson.databind.json.JsonMapper;

class HistoryTransactionTest {
    @TempDir
    Path directory;

    @Test
    void statusFailureRollsBackEventsMemoryPlanAndRevisionAcrossReopen() throws Exception {
        SqliteStore store = new SqliteStore(directory.resolve("history.db"));
        var mapper = JsonMapper.builder().build();
        var repository = new JdbcSessionRepository(store, new EventCodec(mapper));
        var sessions = new SessionService(Clock.systemUTC(), repository);
        var memory = new JdbcConversationRepository(store, mapper);
        var plans = new JdbcPlanRepository(store);
        String id = sessions.createSession("事务").id();
        var run = sessions.sendMessage(id, "原始消息");
        sessions.publish(id, run.runId(), new RunCompleted("完成", List.of(), List.of()));
        var original = List.of(Message.user("原始消息"));
        var plan = List.of(new PlanItem("原计划", "pending"));
        memory.save(id, original);
        plans.save(id, plan);
        try (var connection = store.open(); var statement = connection.createStatement()) {
            statement.execute(
                "CREATE TRIGGER fail_status BEFORE UPDATE OF status ON sessions BEGIN SELECT RAISE(ABORT, 'injected'); END");
        }
        assertThrows(IllegalStateException.class, () -> sessions.editHistory(id, 2, 0L, () -> {
            memory.save(id, List.of());
            plans.save(id, List.of());
        }));
        var reopened = new SqliteStore(directory.resolve("history.db"));
        assertEquals(2, new JdbcSessionRepository(reopened, new EventCodec(mapper)).events(id).size());
        assertEquals(SessionStatus.COMPLETED, sessions.find(id).orElseThrow().status());
        assertEquals(original, new JdbcConversationRepository(reopened, mapper).load(id));
        assertEquals(plan, new JdbcPlanRepository(reopened).load(id));
        assertEquals(0, sessions.historyRevision(id));
        try (var connection = store.open(); var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER fail_status");
        }
        assertEquals(1, sessions.editHistory(id, 2, 0L, () -> {
            memory.save(id, List.of());
            plans.delete(id);
        }));
        assertEquals(1, sessions.latestSequence(id));
        assertEquals(1, sessions.historyRevision(id));
        assertEquals(1, sessions.find(id).orElseThrow().historyRevision());
        assertEquals(SessionStatus.RUNNING, sessions.find(id).orElseThrow().status());
        assertTrue(memory.load(id).isEmpty());
        assertTrue(plans.load(id).isEmpty());
        sessions.sendMessage(id, "新消息");
        assertEquals(3, sessions.events(id).getLast().sequence());
        AtomicBoolean changed = new AtomicBoolean();
        assertThrows(SessionService.HistoryChangedException.class,
            () -> sessions.editHistory(id, 1, 0L, () -> changed.set(true)));
        assertFalse(changed.get());
        sessions.dropEventsFrom(id, 1);
        assertEquals(0, sessions.latestSequence(id));
        assertTrue(repository.latestEvent(id).isEmpty());
        sessions.sendMessage(id, "清空后再发");
        assertEquals(4, sessions.events(id).getLast().sequence());
    }

    @Test
    void replayBoundaryDoesNotDecodeTheLastPayload() throws Exception {
        var store = new SqliteStore(directory.resolve("metadata.db"));
        var repository = new JdbcSessionRepository(store, new EventCodec(JsonMapper.builder().build()));
        var service = new SessionService(Clock.systemUTC(), repository);
        String id = service.createSession("元数据").id();
        String run = service.sendMessage(id, "开始").runId();
        try (var connection = store.open();
            var statement = connection.prepareStatement("UPDATE events SET payload = ? WHERE session_id = ?")) {
            statement.setString(1, "{\"input\":{}}");
            statement.setString(2, id);
            statement.executeUpdate();
        }
        assertEquals(1, service.latestSequence(id));
        assertEquals(run, repository.latestEvent(id).orElseThrow().runId());
        assertThrows(RuntimeException.class, () -> repository.replayPage(id, 0, 1, 1));
    }

    @Test
    void innerRepositoryCannotCommitEarlyOrHideItsRollback() throws Exception {
        SqliteStore store = new SqliteStore(directory.resolve("nested.db"));
        var mapper = JsonMapper.builder().build();
        var memory = new JdbcConversationRepository(store, mapper);
        memory.save("s", List.of(Message.user("旧")));
        var outside = new JdbcConversationRepository(new SqliteStore(store.path()), mapper);
        assertThrows(IllegalStateException.class, () -> store.transaction(() -> {
            memory.save("s", List.of(Message.user("新")));
            assertEquals("旧", outside.load("s").getFirst().content());
            try (var connection = store.open()) {
                connection.rollback();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return true;
        }));
        assertEquals("旧", outside.load("s").getFirst().content());
        // 事务上下文必须在失败后移除，普通仓储写入仍可提交。
        memory.save("s", List.of(Message.user("随后")));
        assertEquals("随后", outside.load("s").getFirst().content());
    }
}
