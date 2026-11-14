package com.pilot.server.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.pilot.core.tool.VerificationCheck;
import com.pilot.core.util.Sha256;
import com.pilot.core.util.WireEnum;

/** 归并目标执行记录中的命令与验证证据。 */
final class GoalEvidence {
    private static final Pattern TASK_ID = Pattern.compile("(?m)^任务ID: ([A-Za-z0-9_-]+)$");

    private GoalEvidence() {
    }

    /** 永久保留改动、有效验证和仍在运行的后台命令；普通观察只保留最近窗口。 */
    static ArrayList<TaskControlService.Evidence> trimEvidence(List<TaskControlService.Evidence> all) {
        if (all.size() <= 160) {
            return new ArrayList<>(all);
        }
        LinkedHashMap<String, TaskControlService.Evidence> retained = new LinkedHashMap<>();
        Set<String> completedTasks = completedTasks(all);
        all.stream().filter(e -> Set.of("edit_file", "create_file", "apply_patch").contains(e.tool())
            || ("record_research".equals(e.tool()) && EvidenceStatus.SUCCESS.is(e.status())) || isVerificationAttempt(e)
            || ("run_command".equals(e.tool()) && isBackgroundLaunch(e) && !completedTasks.contains(taskId(e))))
            .forEach(e -> retained.put(e.id(), e));
        recentHistoricalWaits(all).forEach(e -> retained.put(e.id(), e));
        int ordinaryStart = Math.max(0, all.size() - 120);
        all.subList(ordinaryStart, all.size()).forEach(e -> retained.put(e.id(), e));
        ArrayList<TaskControlService.Evidence> result = new ArrayList<>(retained.values());
        if (result.size() <= 220) {
            return result;
        }
        // 无论普通窗口多大，未结束 task 的启动版本都不能被尾裁掉。
        List<TaskControlService.Evidence> pending = result.stream()
            .filter(e -> "run_command".equals(e.tool()) && isBackgroundLaunch(e) && !completedTasks.contains(taskId(e)))
            .toList();
        LinkedHashMap<String, TaskControlService.Evidence> bounded = new LinkedHashMap<>();
        pending.forEach(e -> bounded.put(e.id(), e));
        latestChecks(all).values().forEach(e -> bounded.put(e.id(), e));
        result.stream().filter(e -> "record_research".equals(e.tool()) && EvidenceStatus.SUCCESS.is(e.status()))
            .reduce((a, b) -> b).ifPresent(e -> bounded.put(e.id(), e));
        recentHistoricalWaits(all).forEach(e -> bounded.put(e.id(), e));
        int remaining = Math.max(0, 220 - bounded.size());
        result.subList(Math.max(0, result.size() - remaining), result.size()).forEach(e -> bounded.put(e.id(), e));
        // 固定保留的检查仍按原始时间顺序输出，不能让旧失败在裁剪后覆盖较新的通过。
        return all.stream().filter(e -> bounded.containsKey(e.id())).collect(Collectors.toCollection(ArrayList::new));
    }

    /** 有界保留最近的等待预约，避免续跑观察挤掉无法重新执行的历史事实。 */
    static List<TaskControlService.Evidence> recentHistoricalWaits(List<TaskControlService.Evidence> all) {
        List<TaskControlService.Evidence> waits =
            all.stream().filter(e -> "wait_until".equals(e.tool()) && successfulEvidence(e)).toList();
        return waits.subList(Math.max(0, waits.size() - 4), waits.size());
    }

    private static boolean isBackgroundLaunch(TaskControlService.Evidence evidence) {
        return EvidenceStatus.RUNNING.is(evidence.status()) && !taskId(evidence).isBlank();
    }

    private static Set<String> completedTasks(List<TaskControlService.Evidence> evidenceList) {
        return evidenceList.stream().filter(e -> "command_status".equals(e.tool()))
            .filter(e -> !WireEnum.in(e.status(), EvidenceStatus.RUNNING, EvidenceStatus.STOPPING))
            .map(GoalEvidence::taskId).filter(value -> !value.isBlank()).collect(Collectors.toSet());
    }

    static String taskId(TaskControlService.Evidence evidence) {
        return taskId(evidence == null ? null : evidence.result());
    }

    static String taskId(String result) {
        Matcher matcher = TASK_ID.matcher(Objects.toString(result, ""));
        return matcher.find() ? matcher.group(1) : "";
    }

    /** 每个命令和工作目录只保留最近结果；不同测试不能相互掩盖失败。 */
    static Map<String, TaskControlService.Evidence> latestChecks(List<TaskControlService.Evidence> evidenceList) {
        Map<String, TaskControlService.Evidence> latest = new LinkedHashMap<>();
        Set<String> completedTasks = completedTasks(evidenceList);
        for (TaskControlService.Evidence evidence : evidenceList) {
            if (!isVerificationAttempt(evidence)) {
                continue;
            }
            if (EvidenceStatus.RUNNING.is(evidence.status()) && completedTasks.contains(taskId(evidence))) {
                continue;
            }
            String command = commandFromCheck(evidence);
            String operation = evidence.operationKey();
            String identity =
                operation.startsWith("command:") ? operation.replaceFirst(":(?:check:[0-9]+|pending-check)$", "")
                    : "command:" + hash(Objects.toString(evidence.target(), "") + "\n" + command);
            latest.put(identity, evidence);
        }
        return latest;
    }

    static boolean keepEvidence(TaskControlService.Evidence evidence) {
        return evidence != null && evidence.result() != null && !evidence.result().isBlank()
            && !EvidenceStatus.SKIPPED.is(evidence.status());
    }

    static boolean successfulEvidence(TaskControlService.Evidence evidence) {
        return keepEvidence(evidence) && EvidenceStatus.SUCCESS.is(evidence.status());
    }

    static boolean isVerificationAttempt(TaskControlService.Evidence evidence) {
        return evidence != null
            && VerificationCheck.classifyCommand(commandFromCheck(evidence).toLowerCase()).qualifies();
    }

    private static String commandFromCheck(TaskControlService.Evidence evidence) {
        String check = Objects.toString(evidence.check(), "");
        int arrow = check.lastIndexOf(" → exit ");
        return (arrow < 0 ? check : check.substring(0, arrow)).strip();
    }

    static boolean isQualifyingVerification(TaskControlService.Evidence evidence) {
        return successfulEvidence(evidence) && VerificationCheck.passingKind(evidence.check()).qualifies();
    }

    static String hash(String text) {
        return Sha256.hex(Objects.toString(text, ""));
    }
}
