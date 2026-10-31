package com.pilot.server.session;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.session.SessionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 用量聚合的关键语义：run.usage 是累计快照，每 run 只取末值，跨 run / 跨会话求和。 */
class UsageServiceTest {

    @Test
    void takesLastSnapshotPerRunAndSumsAcrossRuns() {
        Instant day = LocalDate.parse("2026-10-31").atStartOfDay(ZoneId.systemDefault()).toInstant();
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.eventsOfType(eq("run.usage"), any(Instant.class))).thenReturn(List.of(
                // run_a 三条中间快照：只能计末值（3 请求 / 300 输入 / 30 输出）
                envelope("s1", "run_a", day.plusSeconds(10), 1, 100, 10),
                envelope("s1", "run_a", day.plusSeconds(20), 2, 200, 20),
                envelope("s1", "run_a", day.plusSeconds(30), 3, 300, 30),
                // run_b 一条（含子代理归集）
                envelope("s1", "run_b", day.plusSeconds(40), 5, 500, 50),
                // 另一会话的 run_c
                envelope("s2", "run_c", day.plusSeconds(50), 2, 40, 4)));

        SessionService sessions = mock(SessionService.class);
        when(sessions.find("s1")).thenReturn(Optional.of(session("s1", "登录修复")));
        when(sessions.find("s2")).thenReturn(Optional.of(session("s2", "浏览器验证")));

        UsageService.UsageReport report = new UsageService(repository, sessions).report(7);

        // 合计 = 末值之和，不是全部快照之和（否则是 1300 输入）
        assertEquals(10, report.totalRequests());
        assertEquals(840, report.totalInputTokens());
        assertEquals(84, report.totalOutputTokens());
        assertEquals(1, report.daily().size(), "同一天的三次运行归入同一日");
        assertEquals(10, report.daily().getFirst().requests());

        // 会话 Top 按 token 总量降序：s1（830）在 s2（44）前
        assertEquals(2, report.sessions().size());
        assertEquals("登录修复", report.sessions().getFirst().title());
        assertEquals("浏览器验证", report.sessions().get(1).title());
        assertEquals(8, report.sessions().getFirst().requests());
        assertEquals(40, report.sessions().get(1).inputTokens());
    }

    @Test
    void missingSessionsFallBackToPlaceholderTitle() {
        SessionRepository repository = mock(SessionRepository.class);
        when(repository.eventsOfType(anyString(), any(Instant.class))).thenReturn(List.of(
                envelope("gone", "run_x", Instant.now(), 1, 10, 1)));
        SessionService sessions = mock(SessionService.class);
        when(sessions.find(anyString())).thenReturn(Optional.empty());

        UsageService.UsageReport report = new UsageService(repository, sessions).report(30);

        assertEquals("已删除的会话", report.sessions().getFirst().title());
    }

    private static EventEnvelope envelope(String sessionId, String runId, Instant timestamp,
                                          int requests, long input, long output) {
        return new EventEnvelope(1, timestamp, "run.usage", sessionId, runId,
                new RunUsageUpdated(requests, input, output, 100, 120_000, true));
    }

    private static Session session(String id, String title) {
        return new Session(id, title, Instant.now(), SessionStatus.COMPLETED, "/ws");
    }
}
