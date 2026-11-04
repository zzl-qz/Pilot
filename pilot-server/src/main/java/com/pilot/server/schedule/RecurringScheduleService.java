package com.pilot.server.schedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import com.pilot.core.session.SessionService;
import com.pilot.server.store.AutomationStore;

import tools.jackson.databind.ObjectMapper;

/** 周期只产生一次性任务；隔离、唤醒、预算和核验仍由 ScheduleService 负责。 */
@Service
public class RecurringScheduleService {
    public record Series(String id, String name, ScheduleService.Create task, String cron, String timezone,
        String misfire, String state, Instant nextAt, String activeId, int occurrences, String message, long version,
        String sourceWorkspace) {
    }

    public record Create(ScheduleService.Create task, String cron, String misfire) {
    }

    public record Template(String id, String name, long version, ScheduleService.Create task, Instant updatedAt) {
    }

    public record Times(Instant runAt, List<Instant> next, String timezone) {
    }

    private final AutomationStore store;
    private final ObjectMapper mapper;
    private final ScheduleService schedules;
    private final SessionService sessions;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RecurringScheduleService(AutomationStore store, ObjectMapper mapper, ScheduleService schedules,
        SessionService sessions) {
        this(store, mapper, schedules, sessions, Clock.systemUTC());
    }

    public RecurringScheduleService(AutomationStore store, ObjectMapper mapper, ScheduleService schedules,
        SessionService sessions, Clock clock) {
        this.store = store;
        this.mapper = mapper;
        this.schedules = schedules;
        this.sessions = sessions;
        this.clock = clock;
    }

    public List<Series> list() {
        return store.list("series").stream().map(d -> mapper.readValue(d.payload(), Series.class)).toList();
    }

    public Series get(String id) {
        return store.get("series", id).map(d -> mapper.readValue(d.payload(), Series.class))
            .orElseThrow(() -> new IllegalArgumentException("周期计划不存在"));
    }

    public synchronized Series create(Create input) {
        if (input == null) {
            throw new IllegalArgumentException("缺少周期配置");
        }
        validateTask(input.task());
        if (input.task().runAt() == null || !input.task().runAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("首次时间必须在未来");
        }
        ZoneId zone = ZoneId.of(input.task().timezone() == null ? "UTC" : input.task().timezone());
        String cron = validateCron(input.cron(), input.task().runAt(), zone);
        if (!Set.of("ONCE", "SKIP").contains(input.misfire())) {
            throw new IllegalArgumentException("请选择错过执行策略");
        }
        Series row = new Series("series_" + UUID.randomUUID(), input.task().name(), input.task(), cron, zone.getId(),
            input.misfire(), "ACTIVE", input.task().runAt(), null, 0, "等待首次执行", 1,
            sessions.find(input.task().sourceSessionId()).orElseThrow().workspace());
        if (!store.put("series", row.id(), 0, mapper.writeValueAsString(row))) {
            throw new IllegalStateException("周期计划已存在");
        }
        return row;
    }

    private void validateTask(ScheduleService.Create task) {
        if (task == null || task.content() == null || task.content().isBlank() || task.content().length() > 100000) {
            throw new IllegalArgumentException("请填写有效任务目标");
        }
        if (task.name() == null || task.name().isBlank() || task.name().length() > 120) {
            throw new IllegalArgumentException("任务名称为 1–120 字");
        }
        var source = sessions.find(Objects.toString(task.sourceSessionId(), ""))
            .orElseThrow(() -> new IllegalArgumentException("来源对话不存在"));
        if (source.isSide()) {
            throw new IllegalArgumentException("请从主对话创建计划");
        }
        ZoneId.of(task.timezone() == null ? "UTC" : task.timezone());
    }

    static String validateCron(String cron, Instant start, ZoneId zone) {
        if (cron == null || cron.length() > 200) {
            throw new IllegalArgumentException("请填写六段 cron 表达式");
        }
        var expression = CronExpression.parse(cron);
        var first = expression.next(start.atZone(zone));
        var second = first == null ? null : expression.next(first);
        if (first == null || second == null) {
            throw new IllegalArgumentException("表达式没有后续执行时间");
        }
        if (Duration.between(first, second).getSeconds() < 60) {
            throw new IllegalArgumentException("周期不能短于一分钟");
        }
        // 限制秒字段，避免复杂表达式局部出现每秒触发。
        if (!cron.trim().split("\\s+")[0].matches("[0-5]?[0-9]")) {
            throw new IllegalArgumentException("秒字段须为一个固定值（0–59）");
        }
        return cron.trim();
    }

    public Times times(String localTime, String timezone, String cron) {
        if (localTime == null || localTime.isBlank() || timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("请填写执行时间和时区");
        }
        ZoneId zone = ZoneId.of(timezone);
        LocalDateTime local = LocalDateTime.parse(localTime);
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            throw new IllegalArgumentException("该时区在夏令时切换中不存在此时间，请选择其他时间");
        }
        Instant first = local.atOffset(offsets.getFirst()).toInstant();
        List<Instant> next = new ArrayList<>();
        next.add(first);
        if (cron != null && !cron.isBlank()) {
            var expr = CronExpression.parse(validateCron(cron, first, zone));
            var at = first.atZone(zone);
            for (int i = 0; i < 4; i++) {
                at = expr.next(at);
                if (at == null) {
                    break;
                }
                next.add(at.toInstant());
            }
        }
        return new Times(first, List.copyOf(next), timezone);
    }

    public synchronized Series control(String id, long version, String action) {
        var old = get(id);
        if (old.version() != version) {
            throw new IllegalStateException("周期计划已变化，请刷新");
        }
        if ("CANCELLED".equals(old.state())) {
            throw new IllegalStateException("计划已取消");
        }
        if (action == null) {
            throw new IllegalArgumentException("缺少操作");
        }
        String state = switch (action) {
            case "pause" -> "PAUSED";
            case "resume" -> "ACTIVE";
            case "cancel" -> "CANCELLED";
            default -> throw new IllegalArgumentException("不支持的操作");
        };
        // 控制未来轮次，不中止当前任务；当前轮次在定时任务列表单独暂停/取消。
        return replace(old, state, old.nextAt(), old.activeId(), old.occurrences(),
            "resume".equals(action) ? "已恢复；按错过策略继续" : "未来轮次已" + (state.equals("PAUSED") ? "暂停" : "取消"));
    }

    private Series replace(Series old, String state, Instant next, String active, int count, String message) {
        var row = new Series(old.id(), old.name(), old.task(), old.cron(), old.timezone(), old.misfire(), state, next,
            active, count, message, old.version() + 1, old.sourceWorkspace());
        if (!store.put("series", row.id(), old.version(), mapper.writeValueAsString(row))) {
            throw new IllegalStateException("周期计划已变化，请刷新");
        }
        return row;
    }

    @Scheduled(fixedDelayString = "${pilot.schedules.poll-ms:2000}")
    public synchronized void tick() {
        for (var row : list()) {
            try {
                advance(row);
            } catch (RuntimeException e) {
                try {
                    var latest = get(row.id());
                    if (latest.version() == row.version()) {
                        replace(latest, "ERROR", latest.nextAt(), latest.activeId(), latest.occurrences(),
                            "创建轮次失败：" + e.getMessage());
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void advance(Series row) {
        if (!"ACTIVE".equals(row.state())) {
            return;
        }
        Instant now = clock.instant();
        if (row.nextAt().isAfter(now)) {
            return;
        }
        if (row.activeId() != null) {
            var current = schedules.get(row.activeId());
            if (!Set.of("COMPLETED", "CANCELLED", "NEEDS_ATTENTION").contains(current.state())) {
                return;
            }
        }
        var next = CronExpression.parse(row.cron()).next(now.atZone(ZoneId.of(row.timezone())));
        if (next == null) {
            replace(row, "ERROR", row.nextAt(), row.activeId(), row.occurrences(), "没有后续执行时间");
            return;
        }
        String key = row.id() + "_" + row.nextAt().toEpochMilli();
        var existing = schedules.findOccurrence(key);
        if (existing.isPresent()) {
            replace(row, "ACTIVE", next.toInstant(), key, row.occurrences() + 1, "已恢复已创建的轮次，未重复投递");
            return;
        }
        if ("SKIP".equals(row.misfire()) && Duration.between(row.nextAt(), now).getSeconds() > 60) {
            replace(row, "ACTIVE", next.toInstant(), row.activeId(), row.occurrences(), "已跳过错过的轮次");
            return;
        }
        var task = row.task();
        var source = sessions.find(task.sourceSessionId()).orElseThrow(() -> new IllegalArgumentException("来源对话已删除"));
        if (!Objects.equals(source.workspace(), row.sourceWorkspace())) {
            throw new IllegalArgumentException("来源工作区已改变，请核对后重建周期计划");
        }
        var job = schedules.createOccurrence(
            new ScheduleService.Create(task.name(), task.sourceSessionId(), task.content(), now.plusSeconds(1),
                row.timezone(), task.requirements(), task.skill(), task.fullAccess(), task.isolated()),
            key);
        replace(row, "ACTIVE", next.toInstant(), job.id(), row.occurrences() + 1,
            "已创建第 " + (row.occurrences() + 1) + " 轮；同一计划不重叠执行");
    }

    public List<Template> templates() {
        return store.list("template").stream().map(d -> mapper.readValue(d.payload(), Template.class)).toList();
    }

    public synchronized Template saveTemplate(String id, long version, String name, ScheduleService.Create task) {
        if (task == null || task.content() == null || task.content().isBlank() || task.content().length() > 100000
            || name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("请填写模板名称和目标");
        }
        if (id == null) {
            if (version != 0) {
                throw new IllegalArgumentException("新模板版本应为 0");
            }
            id = "template_" + UUID.randomUUID();
        }
        // 模板只保存内容和策略，不绑定来源会话或旧执行时间。
        var payload = new ScheduleService.Create(task.name(), null, task.content(), null, task.timezone(),
            task.requirements(), task.skill(), task.fullAccess(), task.isolated());
        var row = new Template(id, name, version + 1, payload, clock.instant());
        if (!store.put("template", id, version, mapper.writeValueAsString(row))) {
            throw new IllegalStateException("模板已更新，请刷新后重试");
        }
        return row;
    }

    public void deleteTemplate(String id, long version) {
        if (!store.delete("template", id, version)) {
            throw new IllegalStateException("模板已变化，请刷新");
        }
    }
}
