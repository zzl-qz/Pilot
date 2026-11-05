package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionTitles;

import tools.jackson.databind.json.JsonMapper;

class SessionOrganizationTest {
    @TempDir
    Path directory;
    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    private SessionRepository repository(boolean persistent) {
        return persistent ? new JdbcSessionRepository(new SqliteStore(directory.resolve("sessions.db")),
            new EventCodec(JsonMapper.builder().build())) : new InMemorySessionRepository();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstMessageNamesTheSessionAndOldConversationMovesForward(boolean persistent) {
        SessionRepository repository = repository(persistent);
        repository.create("old", "未命名会话", null, START, "project", true);
        repository.create("new", "较新的对话", null, START.plusSeconds(10), "project", false);
        SessionService sessions = new SessionService(Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC), repository);
        assertEquals(List.of("new", "old"), sessions.list().stream().map(Session::id).toList());
        sessions.sendMessage("old", "# 修复模型选择同步\n先检查请求链路，再补回归测试。");
        assertEquals("修复模型选择同步", sessions.find("old").orElseThrow().title());
        assertFalse(sessions.find("old").orElseThrow().isBlank());
        assertEquals(START.plusSeconds(20), sessions.find("old").orElseThrow().updatedAt());
        assertEquals(List.of("old", "new"), sessions.listByProject("project").stream().map(Session::id).toList());
        sessions.updatePreferences("new", true, true);
        assertEquals("new", sessions.list().getFirst().id(), "置顶高于最近活动");
        sessions.rename("old", "我自己的名称");
        sessions.sendMessage("old", "继续检查另外的问题");
        assertEquals("我自己的名称", sessions.find("old").orElseThrow().title());
        assertFalse(repository.autoRename("old", "晚到的自动标题"), "自动命名不能覆盖手动编辑");
        assertEquals("我自己的名称", sessions.find("old").orElseThrow().title());
        sessions.updatePreferences("new", false, false);
        assertEquals("old", sessions.list().getFirst().id());
        assertEquals(START.plusSeconds(10), sessions.find("new").orElseThrow().updatedAt(), "读取和编辑标记不推进活动时间");
        if (persistent) {
            SessionRepository reopened = repository(true);
            assertEquals("old", reopened.list().getFirst().id());
            assertTrue(reopened.find("old").orElseThrow().titleManual());
            sessions.updatePreferences("old", true, true);
            assertTrue(reopened.find("old").orElseThrow().pinned());
            assertTrue(reopened.find("old").orElseThrow().unread());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualNameBeforeTheFirstMessageIsPreservedAndQueuedInputCanTouchActivity(boolean persistent) {
        SessionRepository repository = repository(persistent);
        repository.create("blank", "新对话", null, START, null, true);
        SessionService sessions = new SessionService(Clock.fixed(START.plusSeconds(30), ZoneOffset.UTC), repository);
        sessions.rename("blank", "手工安排的任务");
        sessions.sendMessage("blank", "执行这个操作");
        assertEquals("手工安排的任务", sessions.find("blank").orElseThrow().title());
        repository.create("queued", "队列中的会话", null, START, null, false);
        sessions.touch("queued");
        assertEquals(START.plusSeconds(30), sessions.find("queued").orElseThrow().updatedAt());
        new SessionService(Clock.fixed(START.plusSeconds(10), ZoneOffset.UTC), repository).sendMessage("queued",
            "较早时间戳的事件");
        assertEquals(START.plusSeconds(30), sessions.find("queued").orElseThrow().updatedAt(), "时钟回拨不能让会话活动时间倒退");
    }

    @Test
    void legacyDatabaseBackfillsLatestActivityWithoutChangingExistingTitles() throws Exception {
        Path file = directory.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, created_at TEXT NOT NULL, "
                + "status TEXT NOT NULL, sequence INTEGER NOT NULL DEFAULT 0, workspace TEXT)");
            sql.execute("CREATE TABLE events (session_id TEXT, sequence INTEGER, run_id TEXT, type TEXT, payload TEXT, "
                + "created_at TEXT, PRIMARY KEY(session_id, sequence))");
            sql.execute("INSERT INTO sessions VALUES ('legacy','保留旧名称','2026-11-01T00:00:00Z','CREATED',1,NULL)");
            sql.execute("INSERT INTO events VALUES ('legacy',1,'run','run.started','{}','2026-11-02T00:00:00Z')");
        }
        var repository = new JdbcSessionRepository(new SqliteStore(file), new EventCodec(JsonMapper.builder().build()));
        Session migrated = repository.find("legacy").orElseThrow();
        assertEquals("保留旧名称", migrated.title());
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), migrated.updatedAt());
        assertFalse(migrated.pinned());
        repository.touch("legacy", Instant.parse("2026-11-03T00:00:00Z"));
        new SqliteStore(file);
        assertEquals(Instant.parse("2026-11-03T00:00:00Z"), repository.find("legacy").orElseThrow().updatedAt(),
            "再次启动不能用旧事件覆盖新的活动时间");
    }

    @Test
    void titlesNormalizeMarkdownWhitespaceAndPreserveUnicodeBoundaries() {
        assertEquals("整理项目文档", SessionTitles.from("\n  ## 整理项目文档\n正文"));
        assertEquals("附件任务", SessionTitles.from("\n\t"));
        assertEquals("😀".repeat(32) + "…", SessionTitles.from("😀".repeat(40)));
    }
}
