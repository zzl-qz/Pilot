package com.pilot.server.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.VerificationCheck;
import com.pilot.core.util.Sha256;
import com.pilot.core.util.WireEnum;
import com.pilot.server.run.EvidenceStatus;
import com.pilot.server.run.TaskControlService;

/** 归并目标执行记录中的命令与验证证据。 */
final class GoalEvidence {
    private static final Pattern TASK_ID = Pattern.compile("(?m)^任务ID: ([A-Za-z0-9_-]+)$");
    /** 真正决定「跑哪些用例」的参数：同一批用例换开关重跑才能算覆盖，换用例不算。 */
    private static final Pattern TEST_SELECTOR =
        Pattern.compile("(?i)(?:-dtest[= ]|--tests[= ]|-dgroups[= ])([^\\s,]+(?:,[^\\s,]+)*)");

    private GoalEvidence() {
    }

    /** 永久保留改动、有效验证和仍在运行的后台命令；普通观察只保留最近窗口。 */
    static ArrayList<TaskControlService.Evidence> trimEvidence(List<TaskControlService.Evidence> all) {
        if (all.size() <= 160) {
            return new ArrayList<>(all);
        }
        LinkedHashMap<String, TaskControlService.Evidence> retained = new LinkedHashMap<>();
        Set<String> completedTasks = completedTasks(all);
        all.stream().filter(e -> ToolNames.FILE_EDITS.contains(e.tool())
            || (ToolNames.RECORD_RESEARCH.equals(e.tool()) && EvidenceStatus.SUCCESS.is(e.status()))
            || isVerificationAttempt(e)
            || (ToolNames.RUN_COMMAND.equals(e.tool()) && isBackgroundLaunch(e) && !completedTasks.contains(taskId(e))))
            .forEach(e -> retained.put(e.id(), e));
        recentHistoricalWaits(all).forEach(e -> retained.put(e.id(), e));
        int ordinaryStart = Math.max(0, all.size() - 120);
        all.subList(ordinaryStart, all.size()).forEach(e -> retained.put(e.id(), e));
        ArrayList<TaskControlService.Evidence> result = new ArrayList<>(retained.values());
        if (result.size() <= 220) {
            return result;
        }
        // 无论普通窗口多大，未结束 task 的启动版本都不能被尾裁掉。
        List<TaskControlService.Evidence> pending = result.stream().filter(
            e -> ToolNames.RUN_COMMAND.equals(e.tool()) && isBackgroundLaunch(e) && !completedTasks.contains(taskId(e)))
            .toList();
        LinkedHashMap<String, TaskControlService.Evidence> bounded = new LinkedHashMap<>();
        pending.forEach(e -> bounded.put(e.id(), e));
        latestChecks(all).values().forEach(e -> bounded.put(e.id(), e));
        result.stream().filter(e -> ToolNames.RECORD_RESEARCH.equals(e.tool()) && EvidenceStatus.SUCCESS.is(e.status()))
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
            all.stream().filter(e -> ToolNames.WAIT_UNTIL.equals(e.tool()) && successfulEvidence(e)).toList();
        return waits.subList(Math.max(0, waits.size() - 4), waits.size());
    }

    private static boolean isBackgroundLaunch(TaskControlService.Evidence evidence) {
        return EvidenceStatus.RUNNING.is(evidence.status()) && !taskId(evidence).isBlank();
    }

    private static Set<String> completedTasks(List<TaskControlService.Evidence> evidenceList) {
        return evidenceList.stream().filter(e -> ToolNames.COMMAND_STATUS.equals(e.tool()))
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

    /** 按（规范化工作目录 + 检查类别）生成验证覆盖域，用于判断当前版本新检查是否已覆盖历史旧检查。 */
    static String verificationScope(TaskControlService.Evidence evidence) {
        String target = Objects.toString(evidence == null ? "" : evidence.target(), "").replace('\\', '/').strip();
        while (target.startsWith("./")) {
            target = target.substring(2);
        }
        if (target.isEmpty()) {
            target = ".";
        }
        VerificationCheck.Kind kind =
            VerificationCheck.classifyCommand(commandFromCheck(evidence).toLowerCase(Locale.ROOT));
        return target + "|" + kind.name();
    }

    /**
     * 同一验证目标的判定键：覆盖域 + 测试选择器。同一批用例换无关开关重跑仍算同一目标，
     * 换测试子集则是另一个目标，不能互相掩盖。
     */
    static String verificationSubject(TaskControlService.Evidence evidence) {
        String command = commandFromCheck(evidence);
        if (command.isEmpty() || command.contains("…") || command.contains("...")) {
            // 显示层截断过的命令看不出是不是同一批用例，只按这条证据自己算，禁止跨证据覆盖。
            return "truncated|" + Objects.toString(evidence.id(), "");
        }
        String selectors = selectorSignature(command);
        // 没有测试选择器时无法判断两条命令跑的是不是同一批用例，只能按命令原文算另一个目标。
        return verificationScope(evidence) + "|" + (selectors.isEmpty() ? command : selectors);
    }

    /** 同一验证目标之后有更新的合格通过时，先前那条失败不再代表当前结论。 */
    static boolean recovered(TaskControlService.Evidence failure, Iterable<TaskControlService.Evidence> latest) {
        String subject = verificationSubject(failure);
        for (TaskControlService.Evidence candidate : latest) {
            if (isQualifyingVerification(candidate)
                && candidate.observedAtEpochMillis() > failure.observedAtEpochMillis()
                && subject.equals(verificationSubject(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static String selectorSignature(String command) {
        Set<String> selectors = new TreeSet<>();
        Matcher matcher = TEST_SELECTOR.matcher(Objects.toString(command, ""));
        while (matcher.find()) {
            for (String part : matcher.group(1).split(",")) {
                String selector = part.replace("\"", "").replace("'", "").strip();
                if (!selector.isEmpty()) {
                    selectors.add(selector);
                }
            }
        }
        return String.join(",", selectors);
    }

    static boolean isQualifyingVerification(TaskControlService.Evidence evidence) {
        return successfulEvidence(evidence) && VerificationCheck.passingKind(evidence.check()).qualifies();
    }

    static String hash(String text) {
        return Sha256.hex(Objects.toString(text, ""));
    }
}
