package com.pilot.server.session;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.SessionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 用量聚合的关键语义：run.usage 是累计快照，每 run 只取末值，跨 run / 跨会话求和。 */
class UsageServiceTest {

    @Test
    void takesLastSnapshotPerRunAndSumsAcrossRuns() {
        Instant day = LocalDate.parse("2026-10-31").atStartOfDay(ZoneId.systemDefault()).toInstant();
        UsageRepository repository = new UsageRepository(List.of(
                envelope("s1", "run_a", day.plusSeconds(10), 1, 100, 10),
                envelope("s1", "run_a", day.plusSeconds(20), 2, 200, 20),
                envelope("s1", "run_a", day.plusSeconds(30), 3, 300, 30),
                envelope("s1", "run_b", day.plusSeconds(40), 5, 500, 50),
                envelope("s2", "run_c", day.plusSeconds(50), 2, 40, 4)));
        repository.create("s1", "登录修复", "/ws", Instant.now(), null, false);
        repository.create("s2", "浏览器验证", "/ws", Instant.now(), null, false);

        UsageService.UsageReport report = new UsageService(repository, sessions(repository)).report(7);

        assertEquals(10, report.totalRequests());
        assertEquals(840, report.totalInputTokens());
        assertEquals(84, report.totalOutputTokens());
        assertEquals(1, report.daily().size(), "同一天的三次运行归入同一日");
        assertEquals(10, report.daily().getFirst().requests());

        assertEquals(2, report.sessions().size());
        assertEquals("登录修复", report.sessions().getFirst().title());
        assertEquals("浏览器验证", report.sessions().get(1).title());
        assertEquals(8, report.sessions().getFirst().requests());
        assertEquals(40, report.sessions().get(1).inputTokens());
        assertEquals(0, report.totalCachedInputTokens(), "没有缓存观测的 run 命中数就是 0，不是缺失");
    }

    @Test
    void sumsCachedInputTokensAcrossRuns() {
        Instant day = LocalDate.parse("2026-10-31").atStartOfDay(ZoneId.systemDefault()).toInstant();
        UsageRepository repository = new UsageRepository(List.of(
                envelopeCached("s1", "run_a", day.plusSeconds(10), 1, 1_000, 100, 800),
                envelopeCached("s1", "run_b", day.plusSeconds(20), 2, 500, 50, 100)));
        repository.create("s1", "登录修复", "/ws", Instant.now(), null, false);

        UsageService.UsageReport report = new UsageService(repository, sessions(repository)).report(7);

        assertEquals(1_500, report.totalInputTokens());
        assertEquals(900, report.totalCachedInputTokens(), "缓存命中按与总输入相同的口径累计");
        assertEquals(900, report.daily().getFirst().cachedInputTokens());
        assertEquals(900, report.sessions().getFirst().cachedInputTokens());
    }

    @Test
    void missingSessionsFallBackToPlaceholderTitle() {
        UsageRepository repository = new UsageRepository(List.of(
                envelope("gone", "run_x", Instant.now(), 1, 10, 1)));

        UsageService.UsageReport report = new UsageService(repository, sessions(repository)).report(30);

        assertEquals("已删除的会话", report.sessions().getFirst().title());
    }

    private static SessionService sessions(UsageRepository repository) {
        return new SessionService(Clock.systemUTC(), repository);
    }

    private static EventEnvelope envelope(String sessionId, String runId, Instant timestamp,
                                          int requests, long input, long output) {
        return new EventEnvelope(1, timestamp, "run.usage", sessionId, runId,
                new RunUsageUpdated(requests, input, output, 100, 120_000, true));
    }

    private static EventEnvelope envelopeCached(String sessionId, String runId, Instant timestamp,
                                                int requests, long input, long output, long cached) {
        return new EventEnvelope(1, timestamp, "run.usage", sessionId, runId,
                new RunUsageUpdated(requests, input, output, cached, 100, 120_000, true));
    }

    /** 测试只关心用量事件，不把 Mockito 桩到接口默认方法上。 */
    private static final class UsageRepository extends InMemorySessionRepository {
        private final List<EventEnvelope> usage;

        private UsageRepository(List<EventEnvelope> usage) {
            this.usage = usage;
        }

        @Override
        public List<EventEnvelope> eventsOfType(String type, Instant since) {
            return usage;
        }
    }
}
