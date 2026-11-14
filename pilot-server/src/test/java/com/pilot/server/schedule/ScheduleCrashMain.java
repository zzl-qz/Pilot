package com.pilot.server.schedule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.pilot.core.event.RunCompleted;
import com.pilot.core.loop.Attachment;
import com.pilot.core.session.SessionService;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.notifications.DeliveryChannel;
import com.pilot.server.notifications.DeliveryService;
import com.pilot.server.results.ResultInboxRepository;
import com.pilot.server.results.ResultInboxService;
import com.pilot.server.run.RunStarter;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.store.EventCodec;
import com.pilot.server.store.JdbcSessionRepository;
import com.pilot.server.store.SqliteStore;

import tools.jackson.databind.json.JsonMapper;

/** 独立 JVM 在真实 SQLite 提交和本地 HTTP 投递边界直接退出，不运行外部模型。 */
public class ScheduleCrashMain {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String phase = args[1];
        var clock = new MutableClock(Instant.parse(args[2]));
        var mapper = JsonMapper.builder().build();
        var db = new SqliteStore(root.resolve("automation.db"));
        var codec = new EventCodec(mapper);
        var sessions = new SessionService(clock, new JdbcSessionRepository(db, codec));
        var tasks = new TaskControlService(mapper, new PilotDataDir(root.toString()));
        var store = new AutomationStore(db) {
            @Override
            public synchronized boolean put(String kind, String id, long version, String payload) {
                if ("series".equals(kind) && version > 0) {
                    crash(phase, "occurrence");
                }
                if ("delivery".equals(kind) && "DELIVERED".equals(mapper.readTree(payload).path("state").asText())) {
                    crash(phase, "receipt");
                }
                return super.put(kind, id, version, payload);
            }
        };
        var repository = new ScheduleRepository(db, mapper) {
            @Override
            public synchronized boolean replace(ScheduledTask old, ScheduledTask next) {
                boolean replaced = super.replace(old, next);
                if (replaced && "DISPATCHING".equals(next.state())) {
                    crash(phase, "dispatch");
                }
                return replaced;
            }
        };
        var runner = new CompletingRunner(root, phase, sessions, tasks);
        var schedules = new ScheduleService(repository, sessions, runner, tasks, null, clock);
        var recurring = new RecurringScheduleService(store, mapper, schedules, sessions, clock);
        var results =
            new ResultInboxService(new ResultInboxRepository(db, codec, sessions), sessions, schedules, tasks, mapper);
        var delivery = new DeliveryService(store, mapper, results, clock);
        try {
            if ("seed".equals(phase)) {
                String source = sessions.createSession("跨进程周期", root.toString()).id();
                recurring
                    .create(
                        new RecurringScheduleService.Create(
                            new ScheduleService.Create("每日维护", source, "只执行一次副作用",
                                Instant.parse("2026-11-01T05:30:00Z"), "America/New_York", null, null, true, false),
                            "0 30 1 * * *", args[4]));
                delivery.configure(0, false, args[3]);
            } else {
                recurring.tick();
                clock.at = clock.at.plusSeconds(2);
                for (int i = 0; i < 3; i++) {
                    schedules.tick();
                }
                delivery.collect();
                var pending = delivery.claim(DeliveryChannel.WEBHOOK);
                if (pending != null) {
                    crash(phase, "claim");
                    delivery.send(pending);
                }
            }
            if (!List.of("seed", "recover").contains(phase)) {
                throw new AssertionError("未到达故障注入点：" + phase);
            }
            long starts = sessions.list().stream().flatMap(s -> sessions.events(s.id()).stream())
                .filter(e -> "run.started".equals(e.type())).count();
            long executions = Files.exists(root.resolve("executions.log"))
                ? Files.readAllLines(root.resolve("executions.log")).size() : 0;
            Files.writeString(root.resolve("report.json"), mapper.writeValueAsString(Map.of("series", recurring.list(),
                "jobs", schedules.list(), "deliveries", delivery.list(), "starts", starts, "executions", executions)));
        } finally {
            delivery.close();
        }
    }

    private static void crash(String phase, String point) {
        if (phase.equals(point)) {
            Runtime.getRuntime().halt(23);
        }
    }

    private record CompletingRunner(Path root, String phase, SessionService sessions,
        TaskControlService tasks) implements RunStarter {
        public Optional<String> claimRun(String session) {
            return Optional.of("run_" + UUID.randomUUID());
        }

        public void start(String session, String run, String input) {
            throw new AssertionError("计划必须携带任务要求");
        }

        public void start(String session, String run, String input, List<Attachment> attachments, List<Integer> images,
            TaskControlService.Requirements requirements, String skill) {
            crash(phase, "unbound");
            tasks.start(session, input, requirements.constraints(), requirements.acceptance());
            tasks.configureAutomation(session, requirements.automation());
            try {
                Files.writeString(root.resolve("executions.log"), run + "\n", StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            tasks.status(session, "COMPLETED");
            sessions.publish(session, run, new RunCompleted("已完成", List.of(), List.of("本地测试")));
            crash(phase, "completed");
        }
    }

    private static class MutableClock extends Clock {
        Instant at;

        MutableClock(Instant at) {
            this.at = at;
        }

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
