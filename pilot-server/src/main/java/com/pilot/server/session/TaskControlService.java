package com.pilot.server.session;

import com.pilot.server.config.PilotDataDir;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 独立于压缩后的对话记忆保存任务目标、边界、验收证据和纠正记录。 */
@Component
public class TaskControlService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "PAUSED", "INTERRUPTED",
            "AWAITING_REVIEW", "COMPLETED", "FAILED");
    private final ObjectMapper mapper;
    private final PilotDataDir data;

    public TaskControlService(ObjectMapper mapper, PilotDataDir data) {
        this.mapper = mapper;
        this.data = data;
    }

    public record Acceptance(String text, Boolean checked, String evidence) {
        public Acceptance {
            text = required(text, "验收条件不能为空");
            checked = Boolean.TRUE.equals(checked);
            evidence = evidence == null ? "" : evidence.strip();
            if (checked && evidence.isBlank()) {
                throw new IllegalArgumentException("勾选已验收的条件必须填写验证证据");
            }
        }
    }

    public record Requirements(List<String> constraints, List<Acceptance> acceptance) {
        public Requirements {
            constraints = normalizeConstraints(constraints);
            acceptance = acceptance == null ? List.of() : List.copyOf(acceptance);
        }
    }

    public record TaskContract(String taskId, String originalGoal, List<String> constraints,
                               List<Acceptance> acceptance, List<String> corrections, String status) {
        public TaskContract {
            required(taskId, "taskId must not be blank");
            required(originalGoal, "原始目标不能为空");
            constraints = normalizeConstraints(constraints);
            acceptance = acceptance == null ? List.of() : List.copyOf(acceptance);
            corrections = corrections == null ? List.of() : List.copyOf(corrections);
            if (!STATUSES.contains(status)) throw new IllegalArgumentException("未知任务状态: " + status);
        }
    }

    private record TaskSnapshot(TaskContract task) { }

    /** 新的独立任务才建立新目标；续跑应沿用 load 的记录。 */
    public synchronized TaskContract start(String sessionId, String originalGoal, List<String> constraints,
                                           List<Acceptance> acceptance) {
        TaskContract task = new TaskContract("task_" + UUID.randomUUID(), originalGoal,
                constraints, acceptance, List.of(), "ACTIVE");
        save(sessionId, task);
        return task;
    }

    public synchronized TaskContract load(String sessionId) {
        Path file = currentFile(sessionId);
        if (!Files.isRegularFile(file)) return null;
        try {
            return mapper.readValue(Files.readString(file), TaskContract.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取任务目标失败", e);
        }
    }

    /** 更新边界和验收时，保持原始目标、任务 ID 和纠正记录。 */
    public synchronized TaskContract update(String sessionId, List<String> constraints, List<Acceptance> acceptance) {
        TaskContract task = requireTask(sessionId);
        Requirements requirements = new Requirements(constraints, acceptance);
        String status = task.status();
        if ("AWAITING_REVIEW".equals(status) || "COMPLETED".equals(status)) {
            status = allChecked(requirements.acceptance()) ? "COMPLETED" : "AWAITING_REVIEW";
        }
        TaskContract updated = new TaskContract(task.taskId(), task.originalGoal(), requirements.constraints(),
                requirements.acceptance(), task.corrections(), status);
        save(sessionId, updated);
        return updated;
    }

    public synchronized TaskContract status(String sessionId, String status) {
        TaskContract task = load(sessionId);
        if (task == null) return null;
        TaskContract updated = new TaskContract(task.taskId(), task.originalGoal(), task.constraints(),
                task.acceptance(), task.corrections(), status);
        save(sessionId, updated);
        return updated;
    }

    /** 模型运行完成和用户验收完成分别记录；未勾选的明确验收条件不会自动通过。 */
    public synchronized TaskContract finish(String sessionId) {
        TaskContract task = load(sessionId);
        return task == null ? null : status(sessionId, allChecked(task.acceptance()) ? "COMPLETED" : "AWAITING_REVIEW");
    }

    public synchronized TaskContract correct(String sessionId, String text) {
        TaskContract task = requireTask(sessionId);
        List<String> corrections = new ArrayList<>(task.corrections());
        corrections.add(required(text, "纠正内容不能为空"));
        // 改了任务方向后，之前的验收证据仍保留供查看，但需要重新确认。
        List<Acceptance> acceptance = task.acceptance().stream()
                .map(item -> new Acceptance(item.text(), false, item.evidence())).toList();
        TaskContract updated = new TaskContract(task.taskId(), task.originalGoal(), task.constraints(),
                acceptance, corrections, "ACTIVE");
        save(sessionId, updated);
        return updated;
    }

    /** 每次开始/续跑注入，并可在每个安全步骤刷新，避免压缩或暂停丢失目标。 */
    public synchronized String prompt(String sessionId) {
        TaskContract task = load(sessionId);
        if (task == null) return "";
        StringBuilder text = new StringBuilder("当前任务的持久记录（用户要求）：\n原始目标：\n")
                .append(task.originalGoal()).append("\n\n用户约束：\n");
        appendLines(text, task.constraints());
        text.append("\n验收条件与证据：\n");
        if (task.acceptance().isEmpty()) text.append("（用户未单独列出；按原始目标核对，不得虚构已验证结果）\n");
        for (Acceptance item : task.acceptance()) {
            text.append(item.checked() ? "- [已确认] " : "- [待核对] ").append(item.text()).append('\n');
            if (!item.evidence().isBlank()) text.append("  已记录证据：").append(item.evidence()).append('\n');
        }
        text.append("\n用户对当前任务的纠正（按顺序，后续明确变更优先）：\n");
        appendLines(text, task.corrections());
        text.append("\n继续工作前对照原始目标、约束与纠正；收尾时逐条说明验收结果、实际证据和未完成项。")
                .append("只有执行步骤完成不代表已满足目标；没有证据的验收项不能宣称通过。");
        return text.toString();
    }

    public synchronized void snapshot(String sessionId, String runId) {
        write(snapshotFile(runId), new TaskSnapshot(load(sessionId)));
    }

    /** 检查点属于 runId；从对话分支引用原运行时也可以恢复到该状态。 */
    public synchronized void restoreTo(String sessionId, String runId) {
        Path file = snapshotFile(runId);
        if (!Files.isRegularFile(file)) {
            delete(sessionId);
            return;
        }
        try {
            TaskContract task = mapper.readValue(Files.readString(file), TaskSnapshot.class).task();
            if (task == null) delete(sessionId);
            else save(sessionId, task);
        } catch (IOException e) {
            throw new UncheckedIOException("恢复任务目标失败", e);
        }
    }

    public synchronized void restore(String sessionId, String runId) { restoreTo(sessionId, runId); }

    public synchronized void copy(String sourceSessionId, String targetSessionId) {
        TaskContract task = load(sourceSessionId);
        if (task == null) delete(targetSessionId);
        else save(targetSessionId, task);
    }

    public synchronized void delete(String sessionId) {
        try { Files.deleteIfExists(currentFile(sessionId)); }
        catch (IOException e) { throw new UncheckedIOException("清理任务目标失败", e); }
    }

    private TaskContract requireTask(String sessionId) {
        TaskContract task = load(sessionId);
        if (task == null) throw new IllegalStateException("该会话还没有任务目标，请先发送任务");
        return task;
    }

    private static boolean allChecked(List<Acceptance> acceptance) {
        return acceptance.stream().allMatch(item -> item.checked() && !item.evidence().isBlank());
    }

    private static void appendLines(StringBuilder target, List<String> lines) {
        if (lines.isEmpty()) target.append("（无）\n");
        else lines.forEach(line -> target.append("- ").append(line).append('\n'));
    }

    private static List<String> normalizeConstraints(List<String> constraints) {
        return constraints == null ? List.of() : constraints.stream()
                .map(value -> required(value, "约束内容不能为空")).toList();
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.strip();
    }

    private static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("非法标识符");
        return value;
    }

    private Path currentFile(String sessionId) { return data.file("tasks", id(sessionId), "current.json"); }
    private Path snapshotFile(String runId) { return data.file("snapshots", id(runId), "task.json"); }
    private void save(String sessionId, TaskContract task) { write(currentFile(sessionId), task); }

    private void write(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "task-", ".tmp");
            try {
                Files.writeString(temporary, mapper.writeValueAsString(value));
                try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) { throw new UncheckedIOException("保存任务目标失败", e); }
    }
}
