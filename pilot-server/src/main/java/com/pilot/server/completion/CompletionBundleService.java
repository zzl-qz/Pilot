package com.pilot.server.completion;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.pilot.core.browser.BrowserEvidenceRecord;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.browser.BrowserEvidenceService;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.run.TaskControlStatus;
import com.pilot.server.session.lookup.SessionLookup;
import com.pilot.server.validation.ValidationEvidence;
import com.pilot.server.validation.ValidationLedger;
import com.pilot.server.validation.ValidationReason;
import com.pilot.server.validation.ValidationSnapshot;
import com.pilot.server.validation.ValidationStatusService;
import com.pilot.server.validation.VerificationPolicyService;
import com.pilot.server.validation.WorkspaceFingerprint;
import com.pilot.server.workspace.ChangeService;
import com.pilot.server.workspace.RunSnapshotService;

import lombok.RequiredArgsConstructor;

/**
 * 可复核完成包：把「这次任务到底做了什么、凭什么算完成、现在还算不算数」收成一份可导出的凭证。
 *
 * <p>它不是运行日志，也不是模型总结：证据、产物指纹、改动清单和策略结论都来自服务端记录。
 * 文件再被改动时，{@link #verify(String)} 会立即给出 stale，而不是继续沿用旧结论。
 */
@Service
@RequiredArgsConstructor
public class CompletionBundleService {
    private final SessionService sessions;
    private final TaskControlService tasks;
    private final ValidationLedger validations;
    private final ValidationStatusService validationStatus;
    private final VerificationPolicyService policies;
    private final RunSnapshotService snapshots;
    private BrowserEvidenceService browserEvidence;

    @Autowired(required = false)
    public void setBrowserEvidence(BrowserEvidenceService browserEvidence) {
        this.browserEvidence = browserEvidence;
    }

    public record CompletionBundle(int version, String generatedAt, String sessionId, String taskId, String goal,
        List<String> constraints, List<TaskControlService.Acceptance> acceptance, List<String> corrections,
        String status, String mode, String phase, String gap, String workspace, ValidationSnapshot verification,
        VerificationPolicyService.PolicyReport policy, List<ValidationEvidence> evidence,
        List<ChangeService.RunFileChange> changes, int added, int removed, boolean changesTruncated,
        String changesError, List<String> nextSteps, List<BrowserEvidenceRecord> browserEvidence) {
        public CompletionBundle {
            version = version <= 0 ? 1 : version;
            generatedAt = generatedAt == null ? "" : generatedAt;
            sessionId = sessionId == null ? "" : sessionId;
            taskId = taskId == null ? "" : taskId;
            goal = goal == null ? "" : goal;
            constraints = copy(constraints);
            acceptance = acceptance == null ? List.of() : List.copyOf(acceptance);
            corrections = copy(corrections);
            status = status == null ? "" : status;
            mode = mode == null ? "" : mode;
            phase = phase == null ? "" : phase;
            gap = gap == null ? "" : gap;
            workspace = workspace == null ? "" : workspace;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            changes = changes == null ? List.of() : List.copyOf(changes);
            changesError = changesError == null ? "" : changesError;
            nextSteps = copy(nextSteps);
            browserEvidence = browserEvidence == null ? List.of() : List.copyOf(browserEvidence);
        }

        public CompletionBundle(int version, String generatedAt, String sessionId, String taskId, String goal,
            List<String> constraints, List<TaskControlService.Acceptance> acceptance, List<String> corrections,
            String status, String mode, String phase, String gap, String workspace, ValidationSnapshot verification,
            VerificationPolicyService.PolicyReport policy, List<ValidationEvidence> evidence,
            List<ChangeService.RunFileChange> changes, int added, int removed, boolean changesTruncated,
            String changesError, List<String> nextSteps) {
            this(version, generatedAt, sessionId, taskId, goal, constraints, acceptance, corrections, status, mode,
                phase, gap, workspace, verification, policy, evidence, changes, added, removed, changesTruncated,
                changesError, nextSteps, List.of());
        }

        private static List<String> copy(List<String> value) {
            return value == null ? List.of() : List.copyOf(value);
        }
    }

    public record VerifyReport(String status, String code, String reason, String currentCandidateHash,
        String evidenceCandidateHash, String evidenceRunId, VerificationPolicyService.PolicyReport policy,
        String note) {
        public VerifyReport {
            status = status == null ? ValidationSnapshot.UNVERIFIED : status;
            code = code == null ? "" : code;
            reason = reason == null ? "" : reason;
            currentCandidateHash = currentCandidateHash == null ? "" : currentCandidateHash;
            evidenceCandidateHash = evidenceCandidateHash == null ? "" : evidenceCandidateHash;
            evidenceRunId = evidenceRunId == null ? "" : evidenceRunId;
            note = note == null ? "" : note;
        }
    }

    /** 生成当前会话的完成包；不重新执行命令，只汇总服务端已有记录。 */
    public CompletionBundle build(String sessionId) {
        Session session = SessionLookup.require(sessions, sessionId);
        Path workspace = SessionLookup.workspaceOf(session);
        TaskControlService.TaskContract task = tasks.load(sessionId);
        List<ValidationEvidence> evidence = validations.recent(sessionId);
        ValidationEvidence latest = evidence.isEmpty() ? null : evidence.getLast();
        List<String> files = latest == null ? fallbackFiles(task) : latest.files();
        List<String> checks = latest == null ? List.of() : latest.checks();
        ValidationSnapshot verification = validationStatus.snapshot(sessionId, workspace, null);
        VerificationPolicyService.PolicyReport policy = policies.evaluate(workspace, files, checks);
        ChangeService.RunChangeReport report = latest == null ? null : changes(latest.runId());
        List<ChangeService.RunFileChange> changes = report == null ? List.of() : report.files();
        List<String> nextSteps = nextSteps(task, verification, policy);
        List<BrowserEvidenceRecord> browserRecords =
            browserEvidence == null ? List.of() : browserEvidence.list(sessionId, "", 40);
        return new CompletionBundle(1, Instant.now().toString(), sessionId, task == null ? "" : task.taskId(),
            task == null ? "" : task.originalGoal(), task == null ? List.of() : task.constraints(),
            task == null ? List.of() : task.acceptance(), task == null ? List.of() : task.corrections(),
            task == null ? "" : task.status(), executionValue(task, TaskControlService.Execution::mode),
            executionValue(task, TaskControlService.Execution::phase),
            executionValue(task, TaskControlService.Execution::gap), workspace.toString(), verification, policy,
            evidence, changes, report == null ? 0 : report.added(), report == null ? 0 : report.removed(),
            report != null && report.truncated(), report == null ? "" : report.error(), nextSteps, browserRecords);
    }

    /** 只核对证据是否仍覆盖当前文件；不会自动重跑测试。 */
    public VerifyReport verify(String sessionId) {
        Session session = SessionLookup.require(sessions, sessionId);
        Path workspace = SessionLookup.workspaceOf(session);
        List<ValidationEvidence> evidence = validations.recent(sessionId);
        ValidationEvidence latest = evidence.isEmpty() ? null : evidence.getLast();
        String current = latest == null ? "" : WorkspaceFingerprint.of(workspace, latest.files());
        ValidationLedger.Report report = validations.report(sessionId, current);
        VerificationPolicyService.PolicyReport policy = policies.evaluate(workspace,
            latest == null ? List.of() : latest.files(), latest == null ? List.of() : latest.checks());
        return new VerifyReport(
            report.current() ? ValidationSnapshot.VERIFIED
                : report.code().equals(ValidationReason.STALE_CANDIDATE) ? ValidationSnapshot.STALE
                    : ValidationSnapshot.UNVERIFIED,
            report.code(), report.reason(), current, latest == null ? "" : latest.candidateHash(),
            latest == null ? "" : latest.runId(), policy, "仅核对现有证据与当前产物的指纹；要重新验证请让 Agent 重新执行策略里的命令。");
    }

    /** 面向人阅读的交付凭证；内容与 JSON 同源。 */
    public String markdown(CompletionBundle bundle) {
        StringBuilder text = new StringBuilder();
        text.append("# 完成凭证\n\n");
        text.append("- 生成时间：").append(bundle.generatedAt()).append('\n');
        text.append("- 会话：`").append(bundle.sessionId()).append("`\n");
        text.append("- 任务：`").append(bundle.taskId()).append("`\n");
        text.append("- 状态：").append(bundle.status()).append('\n');
        text.append("- 工作区：`").append(bundle.workspace()).append("`\n\n");
        text.append("## 目标\n\n").append(bundle.goal().isBlank() ? "（没有任务契约）" : bundle.goal()).append("\n\n");
        if (!bundle.constraints().isEmpty()) {
            text.append("## 约束\n\n");
            bundle.constraints().forEach(item -> text.append("- ").append(item).append('\n'));
            text.append('\n');
        }
        text.append("## 验收\n\n");
        if (bundle.acceptance().isEmpty()) {
            text.append("（还没有人工验收项）\n\n");
        } else {
            for (TaskControlService.Acceptance item : bundle.acceptance()) {
                text.append("- [").append(Boolean.TRUE.equals(item.checked()) ? "x" : " ").append("] ")
                    .append(item.text());
                if (item.evidence() != null && !item.evidence().isBlank()) {
                    text.append(" — ").append(item.evidence());
                }
                text.append('\n');
            }
            text.append('\n');
        }
        text.append("## 证据\n\n");
        text.append("- 当前结论：").append(bundle.verification().status()).append("（").append(bundle.verification().code())
            .append("）");
        if (!bundle.verification().reason().isBlank()) {
            text.append("：").append(bundle.verification().reason());
        }
        text.append('\n');
        if (bundle.evidence().isEmpty()) {
            text.append("\n（还没有校验证据）\n\n");
        } else {
            text.append("\n| 证据 | 状态 | 运行 | 文件 | 检查 |\n| --- | --- | --- | --- | --- |\n");
            for (ValidationEvidence item : bundle.evidence()) {
                text.append("| `").append(item.id()).append("` | ").append(item.status()).append(" | `")
                    .append(item.runId()).append("` | ").append(item.files().size()).append(" 个 | ")
                    .append(item.checks().isEmpty() ? "（无）" : item.checks().stream().map(check -> "`" + check + "`")
                        .reduce((left, right) -> left + "<br>" + right).orElse(""))
                    .append(" |\n");
            }
            text.append('\n');
        }
        text.append("## 验证策略\n\n");
        if (!bundle.policy().configured()) {
            text.append("未配置 `pilot.policy.yaml`。\n\n");
        } else {
            text.append("- 来源：`").append(bundle.policy().source()).append("`\n");
            text.append("- 要求：").append(bundle.policy().requirement()).append('\n');
            text.append("- 结果：").append(bundle.policy().passed() ? "通过" : "未通过").append('\n');
            if (!bundle.policy().required().isEmpty()) {
                text.append("- 必跑：").append(String.join("、", bundle.policy().required())).append('\n');
            }
            if (!bundle.policy().missing().isEmpty()) {
                text.append("- 缺失：").append(String.join("、", bundle.policy().missing())).append('\n');
            }
            if (!bundle.policy().error().isBlank()) {
                text.append("- 策略错误：").append(bundle.policy().error()).append('\n');
            }
            text.append('\n');
        }
        text.append("## 改动\n\n");
        if (bundle.changes().isEmpty()) {
            text.append(bundle.changesError().isBlank() ? "（没有可展示的逐文件差异）" : "（" + bundle.changesError() + "）")
                .append("\n\n");
        } else {
            text.append("| 文件 | 状态 | + | - |\n| --- | --- | --- | --- |\n");
            bundle.changes()
                .forEach(change -> text.append("| `").append(change.path()).append("` | ").append(change.status())
                    .append(" | ").append(lines(change, true)).append(" | ").append(lines(change, false))
                    .append(" |\n"));
            text.append("\n合计：")
                .append(bundle.changes().stream().anyMatch(change -> Boolean.FALSE.equals(change.countsKnown()))
                    ? "部分文件行数未知" : "+" + bundle.added() + " / -" + bundle.removed())
                .append("\n\n");
            String diff = bundle.changes().stream().map(ChangeService.RunFileChange::diff).filter(Objects::nonNull)
                .reduce("", (left, right) -> left + right);
            if (!diff.isBlank()) {
                text.append("```diff\n").append(diff, 0, Math.min(diff.length(), 30_000))
                    .append(diff.length() > 30_000 ? "\n… diff 已截断 …\n" : "\n").append("```\n\n");
            }
        }
        if (!bundle.nextSteps().isEmpty()) {
            text.append("## 下一步\n\n");
            bundle.nextSteps().forEach(item -> text.append("- ").append(item).append('\n'));
            text.append('\n');
        }
        if (!bundle.browserEvidence().isEmpty()) {
            text.append("## 浏览器验证证据\n\n");
            text.append("| 时间 | 动作 | 状态 | URL | 控制台错误 | 失败请求 | 摘要 |\n");
            text.append("| --- | --- | --- | --- | --- | --- | --- |\n");
            for (BrowserEvidenceRecord item : bundle.browserEvidence()) {
                text.append("| ").append(item.timestamp()).append(" | `").append(item.action()).append("` | ")
                    .append(item.status()).append(" | `").append(item.url()).append("` | ").append(item.consoleErrors())
                    .append(" | ").append(item.failedRequests()).append(" | ")
                    .append(item.summary().replace('\n', ' ').replace('|', '/')).append(" |\n");
            }
            text.append('\n');
        }
        text.append("---\n\n");
        text.append("这份凭证只汇总服务端记录，不会自动重跑命令。文件变化后请重新验证。\n");
        return text.toString();
    }

    /** 表格里的行数：算不出来时写「未知」，不能拿 0 冒充。 */
    private static String lines(ChangeService.RunFileChange change, boolean added) {
        if (Boolean.FALSE.equals(change.countsKnown())) {
            return "未知";
        }
        return String.valueOf(added ? change.added() : change.removed());
    }

    private static List<String> nextSteps(TaskControlService.TaskContract task, ValidationSnapshot verification,
        VerificationPolicyService.PolicyReport policy) {
        Set<String> steps = new LinkedHashSet<>();
        if (task != null && task.execution() != null) {
            if (task.execution().gap() != null && !task.execution().gap().isBlank()) {
                steps.add(task.execution().gap());
            }
            task.execution().unknowns().stream().filter(item -> item != null && !item.isBlank()).forEach(steps::add);
        }
        if (policy.configured() && !policy.passed()) {
            if (!policy.missing().isEmpty()) {
                steps.add("补跑策略要求的检查：" + String.join("、", policy.missing()));
            }
            if (!policy.error().isBlank()) {
                steps.add("修复验证策略文件：" + policy.error());
            }
        }
        if (verification.stale()) {
            steps.add("产物已变化，重新运行验证后再导出凭证。");
        } else if (!verification.verified() && !verification.reason().isBlank()) {
            steps.add("验证结论未通过：" + verification.reason());
        }
        if (task != null && !TaskControlStatus.COMPLETED.is(task.status())) {
            steps.add("任务状态为 " + task.status() + "，完成后重新生成凭证。");
        }
        return List.copyOf(new ArrayList<>(steps));
    }

    private static List<String> fallbackFiles(TaskControlService.TaskContract task) {
        if (task == null || task.execution() == null) {
            return List.of();
        }
        return task.execution().evidence().stream().map(TaskControlService.Evidence::target)
            .filter(target -> target != null && !target.isBlank()).distinct().toList();
    }

    private ChangeService.RunChangeReport changes(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        try {
            return snapshots.changes(runId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String executionValue(TaskControlService.TaskContract task,
        Function<TaskControlService.Execution, String> getter) {
        return task == null || task.execution() == null ? "" : getter.apply(task.execution());
    }
}
