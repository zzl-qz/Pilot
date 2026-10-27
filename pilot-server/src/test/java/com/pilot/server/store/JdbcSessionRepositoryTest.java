package com.pilot.server.store;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunStarted;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.session.NoSuchSessionException;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSessionRepositoryTest {
    @TempDir
    Path tempDir;

    private Path database;
    private JdbcSessionRepository repository;

    @BeforeEach
    void setUp() {
        database = tempDir.resolve("pilot.db");
        repository = new JdbcSessionRepository(new SqliteStore(database),
                new EventCodec(JsonMapper.builder().build()));
    }

    @Test
    void persistsSessionAndEventsAcrossReopen() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        repository.create("ses_1", "第一个会话", "E:\\work", now);

        repository.append("ses_1", "run_1", now, new RunStarted("你好"), SessionStatus.RUNNING);
        repository.append("ses_1", "run_1", now.plusSeconds(1), new ToolCompleted("call_1", "读取 12 行", 42L),
                SessionStatus.RUNNING);
        repository.append("ses_1", "run_1", now.plusSeconds(2),
                new RunCompleted("完成", List.of("a.txt"), List.of("mvn -q test → exit 0")), SessionStatus.COMPLETED);

        // 重新打开同一个库，验证真的落盘而不是内存
        var reopened = new JdbcSessionRepository(new SqliteStore(database),
                new EventCodec(JsonMapper.builder().build()));
        List<EventEnvelope> events = reopened.events("ses_1");

        assertEquals(3, events.size());
        assertEquals(1, events.get(0).sequence());
        assertTrue(events.get(0).payload() instanceof RunStarted started && "你好".equals(started.input()));
        assertTrue(events.get(1).payload() instanceof ToolCompleted completed && completed.latencyMs() == 42L);
        assertTrue(events.get(2).payload() instanceof RunCompleted done && done.changedFiles().equals(List.of("a.txt")));

        Session session = reopened.find("ses_1").orElseThrow();
        assertEquals("第一个会话", session.title());
        assertEquals("E:\\work", session.workspace());
        assertEquals(SessionStatus.COMPLETED, session.status());
    }

    @Test
    void assignsMonotonicSequencePerSession() {
        repository.create("ses_1", "a", null, Instant.now());
        repository.create("ses_2", "b", null, Instant.now());

        repository.append("ses_1", "run_1", Instant.now(), new RunStarted("1"), SessionStatus.RUNNING);
        repository.append("ses_2", "run_2", Instant.now(), new RunStarted("2"), SessionStatus.RUNNING);
        repository.append("ses_1", "run_3", Instant.now(), new RunStarted("3"), SessionStatus.RUNNING);

        // 序号按会话独立自增：交叉写入不会串号
        assertEquals(List.of(1L, 2L), repository.events("ses_1").stream().map(EventEnvelope::sequence).toList());
        assertEquals(List.of(2L), repository.eventsAfter("ses_1", 1L).stream().map(EventEnvelope::sequence).toList());
        assertEquals(List.of(1L), repository.events("ses_2").stream().map(EventEnvelope::sequence).toList());
    }

    @Test
    void listsRenameAndDeleteSessions() {
        repository.create("ses_1", "旧标题", null, Instant.parse("2026-01-01T00:00:00Z"));
        repository.create("ses_2", "第二个", null, Instant.parse("2026-01-02T00:00:00Z"));

        assertEquals(List.of("ses_2", "ses_1"), repository.list().stream().map(Session::id).toList());
        assertTrue(repository.rename("ses_1", "新标题"));
        assertEquals("新标题", repository.find("ses_1").orElseThrow().title());

        repository.append("ses_1", "run_1", Instant.now(), new RunStarted("x"), SessionStatus.RUNNING);
        assertTrue(repository.delete("ses_1"));
        assertFalse(repository.delete("ses_1"));
        assertThrows(NoSuchSessionException.class, () -> repository.events("ses_1"));
        assertEquals(1, repository.list().size());
    }

    @Test
    void pagesEventsFromTheTailWithoutLoadingEverything() {
        repository.create("ses_1", "分页", null, Instant.now());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 10; i++) {
            repository.append("ses_1", "run_1", now.plusSeconds(i), new RunStarted("第 " + i + " 条"),
                    SessionStatus.RUNNING);
        }

        assertEquals(10, repository.eventCount("ses_1"));

        // 最新一页：序号 6..10，升序返回
        List<EventEnvelope> latest = repository.eventsPage("ses_1", null, 5);
        assertEquals(List.of(6L, 7L, 8L, 9L, 10L), latest.stream().map(EventEnvelope::sequence).toList());

        // 往前翻：序号 1..5
        List<EventEnvelope> earlier = repository.eventsPage("ses_1", 6L, 5);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), earlier.stream().map(EventEnvelope::sequence).toList());

        // 已经到最早一条，前面没有更多
        assertTrue(repository.eventsPage("ses_1", 1L, 5).isEmpty());
        assertEquals(0, repository.eventCount("ses_missing"));
    }

    @Test
    void deletingExpiredEventsKeepsSessions() {
        SqliteStore store = new SqliteStore(database);
        var repo = new JdbcSessionRepository(store, new EventCodec(JsonMapper.builder().build()));
        repo.create("ses_1", "保留策略", null, Instant.parse("2020-01-01T00:00:00Z"));
        repo.append("ses_1", "run_1", Instant.parse("2020-01-02T00:00:00Z"), new RunStarted("旧"),
                SessionStatus.RUNNING);

        assertEquals(1, store.deleteEventsBefore(Instant.parse("2021-01-01T00:00:00Z")));
        assertEquals(0, repo.eventCount("ses_1"));
        // 会话本身不能被清理掉，否则界面上的记录会凭空消失
        assertTrue(repo.find("ses_1").isPresent());
    }

    @Test
    void appendingToUnknownSessionFails() {
        assertThrows(NoSuchSessionException.class, () -> repository.append("ses_missing", "run_1", Instant.now(),
                new RunStarted("x"), SessionStatus.RUNNING));
    }
}
