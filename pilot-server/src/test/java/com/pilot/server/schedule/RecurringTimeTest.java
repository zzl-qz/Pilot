package com.pilot.server.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.pilot.core.session.SessionService;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.session.RunStarter;
import com.pilot.server.session.TaskControlService;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.json.JsonMapper;

class RecurringTimeTest {
    @TempDir
    Path root;
    final MutableClock clock = new MutableClock();
    final JsonMapper mapper = JsonMapper.builder().build();
    SessionService sessions;
    ScheduleService schedules;
    AutomationStore store;
    RecurringScheduleService recurring;
    String source;

    @BeforeEach
    void setup() {
        sessions = new SessionService(clock);
        source = sessions.createSession("时区测试", root.toString()).id();
        var db = new SqliteStore(root.resolve("schedules.db"));
        store = new AutomationStore(db);
        schedules = new ScheduleService(new ScheduleRepository(db, mapper), sessions, mock(RunStarter.class),
            new TaskControlService(mapper, new PilotDataDir(root.resolve("data").toString())), null, clock);
        recurring = new RecurringScheduleService(store, mapper, schedules, sessions, clock);
    }

    @ParameterizedTest
    @CsvSource({"2026-11-01T01:30,America/New_York,0 30 1 * * *,2026-11-01T05:30:00Z,2026-11-02T06:30:00Z",
        "2026-10-25T02:30,Europe/Berlin,0 30 2 * * *,2026-10-25T00:30:00Z,2026-10-26T01:30:00Z",
        "2026-04-05T01:45,Australia/Lord_Howe,0 45 1 * * *,2026-04-04T14:45:00Z,2026-04-05T15:15:00Z",
        "2026-03-07T02:30,America/New_York,0 30 2 * * *,2026-03-07T07:30:00Z,2026-03-09T06:30:00Z",
        "2026-11-01T09:00,Asia/Shanghai,0 0 9 * * *,2026-11-01T01:00:00Z,2026-11-02T01:00:00Z",
        "2026-11-01T09:00,UTC,0 0 9 * * *,2026-11-01T09:00:00Z,2026-11-02T09:00:00Z",
        "2028-02-29T09:00,UTC,0 0 9 29 2 *,2028-02-29T09:00:00Z,2032-02-29T09:00:00Z"})
    void previewSkipsMissingAndRepeatedLocalTimes(String local, String zone, String cron, String first, String second) {
        var times = recurring.times(local, zone, cron);
        assertEquals(Instant.parse(first), times.runAt());
        assertEquals(Instant.parse(second), times.next().get(1));
        List<String> localTimes =
            times.next().stream().map(at -> at.atZone(ZoneId.of(zone)).toLocalDateTime().toString()).toList();
        assertEquals(localTimes.size(), localTimes.stream().distinct().count());
    }

    @Test
    void minutelyCronDoesNotRepeatTheFallbackHour() {
        var times = recurring.times("2026-11-01T01:58", "America/New_York", "0 * * * * *");
        assertEquals(List.of("2026-11-01T05:58:00Z", "2026-11-01T05:59:00Z", "2026-11-01T07:00:00Z",
            "2026-11-01T07:01:00Z", "2026-11-01T07:02:00Z"), times.next().stream().map(Instant::toString).toList());
        assertThrows(IllegalArgumentException.class,
            () -> recurring.times("2026-03-08T02:30", "America/New_York", "0 30 2 * * *"));
    }

    private RecurringScheduleService.Series create(String at, String misfire) {
        return recurring.create(new RecurringScheduleService.Create(new ScheduleService.Create("每日检查", source, "检查一次",
            Instant.parse(at), "America/New_York", null, null, true, false), "0 30 1 * * *", misfire));
    }

    @Test
    void restartDuringFallbackDoesNotCreateAnotherDailyOccurrence() {
        var plan = create("2026-11-01T05:30:00Z", "ONCE");
        clock.at = plan.nextAt();
        recurring.tick();
        var first = recurring.get(plan.id());
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), first.nextAt());
        schedules.control(first.activeId(), "cancel", null);
        clock.at = Instant.parse("2026-11-01T06:30:00Z");
        var restarted = new RecurringScheduleService(new AutomationStore(new SqliteStore(root.resolve("schedules.db"))),
            mapper, schedules, sessions, clock);
        restarted.tick();
        assertEquals(1, restarted.get(plan.id()).occurrences());
        assertEquals(1, schedules.list().size());
        clock.at = first.nextAt();
        restarted.tick();
        assertEquals(2, restarted.get(plan.id()).occurrences());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ONCE", "SKIP"})
    void offlineRecoveryInRepeatedHourHonorsMisfireWithoutSchedulingSecondOffset(String misfire) {
        var plan = create("2026-11-01T05:30:00Z", misfire);
        clock.at = Instant.parse("2026-11-01T06:15:00Z");
        recurring.tick();
        var recovered = recurring.get(plan.id());
        assertEquals("ONCE".equals(misfire) ? 1 : 0, recovered.occurrences());
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), recovered.nextAt());
        if (recovered.activeId() != null) {
            schedules.control(recovered.activeId(), "cancel", null);
        }
        clock.at = Instant.parse("2026-11-01T06:30:01Z");
        recurring.tick();
        assertEquals(recovered.occurrences(), recurring.get(plan.id()).occurrences());
    }

    @Test
    void rejectsSecondOffsetInNewPlansAndSkipsUndispatchedLegacyDuplicate() {
        assertThrows(IllegalArgumentException.class, () -> create("2026-11-01T06:30:00Z", "ONCE"));
        var old = create("2026-11-01T05:30:00Z", "ONCE");
        var legacy = new RecurringScheduleService.Series(old.id(), old.name(), old.task(), old.cron(), old.timezone(),
            old.misfire(), old.state(), Instant.parse("2026-11-01T06:30:00Z"), null, 1, "旧版待执行", 2,
            old.sourceWorkspace());
        assertTrue(store.put("series", old.id(), 1, mapper.writeValueAsString(legacy)));
        clock.at = legacy.nextAt();
        recurring.tick();
        assertTrue(schedules.list().isEmpty());
        assertEquals(1, recurring.get(old.id()).occurrences());
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), recurring.get(old.id()).nextAt());
    }

    static class MutableClock extends Clock {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        public Instant instant() {
            return at;
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
