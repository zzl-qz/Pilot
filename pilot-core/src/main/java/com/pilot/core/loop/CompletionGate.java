package com.pilot.core.loop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.pilot.core.lint.LintGate;
import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;
import com.pilot.core.plan.PlanItem;
import com.pilot.core.quality.IndependentCodeReviewer;
import com.pilot.core.quality.RefactorEvidenceGate;
import com.pilot.core.quality.TextChange;
import com.pilot.core.tool.ToolResults;
import com.pilot.core.tool.VerificationCheck;
import com.pilot.core.util.Texts;

/**
 * 收尾门：模型说「做完了」时，先检查它是不是真的做完了。
 *
 * <p>两道门，各最多催几次：
 * <ol>
 *   <li>验证：改了代码，却没有任何退出码为 0 的构建 / 测试记录；</li>
 *   <li>自审：没有独立审查时，有文件改动（或只读任务读得太少），让模型核对原始目标。</li>
 * </ol>
 *
 * <p>抽成独立类是因为它有自己的状态（催了几次、看过哪些路径、调了几次工具），
 * 和 AgentLoop 的主循环没有耦合；单独拿出来也能直接测「什么时候该催、什么时候该放行」。
 */
final class CompletionGate {
    /** 改了代码却没有验证时，最多催几次。 */
    private static final int MAX_VERIFY_PROMPTS = 2;
    /** 收尾自审只做一次，再多就变成来回客套。 */
    private static final int MAX_REVIEW_PROMPTS = 1;
    /** 实现类目标却没有任何改动就想收尾时，只推一次让它直接动手。 */
    private static final int MAX_ACTION_PROMPTS = 1;
    private static final int MAX_EVIDENCE_PROMPTS = 1;
    /** 本轮产生的临时/备份文件残留最多提醒清理一次，避免误拦用户明确要求的产物。 */
    private static final int MAX_SCRATCH_PROMPTS = 1;
    private static final List<String> SCRATCH_SUFFIXES = List.of(".tmp", ".bak", ".orig", ".rej");
    /** 只改这些后缀的文档不算「改了代码」，不强制跑构建。 */
    private static final Set<String> DOC_SUFFIXES = Set.of(".md", ".txt", ".rst", ".adoc");
    /** 这些后缀是图片/字体/锁文件等非代码资源，不触发构建或测试门。 */
    private static final Set<String> ASSET_SUFFIXES = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".ico",
        ".svg", ".woff", ".woff2", ".ttf", ".otf", ".map", ".lock", ".snap");

    /**
     * @param attempt 第几次提醒
     * @param max     这类提醒的上限
     * @param halt    为 true 时不再把问题交回模型打补丁，运行标成未完成
     */
    record Nudge(String prompt, String reason, int attempt, int max, boolean halt) {
        Nudge(String prompt, String reason, int attempt, int max) {
            this(prompt, reason, attempt, max, false);
        }
    }

    private final boolean enabled;
    private final String goal;
    private final boolean readOnlySession;
    private final RefactorEvidenceGate evidenceGate;
    /** 本轮开始前就已经失败、且之后没有变化的检查。 */
    private Set<String> baselineChecks = Set.of();
    private final LintGate lintGate;
    private String projectConstraints = "";
    private int planPrompts;
    private int verifyPrompts;
    private int reviewPrompts;
    private int actionPrompts;
    private int evidencePrompts;
    private int scratchPrompts;
    private boolean independentCodeReviewed;
    /** 记录最近一次通过的构建/测试之后是否又改了代码，防止收尾重构改坏原有功能却漏跑验证。 */
    private boolean codeDirtySincePassingCheck;
    private int toolCalls;
    private final Set<String> inspected = new LinkedHashSet<>();

    /**
     * @param goal    本轮原始任务，自审时回灌给模型对照；续跑没有原始输入时为 {@code null}
     * @param enabled 关闭后直接放行，不做任何催办
     */
    CompletionGate(boolean enabled, String goal) {
        this(enabled, goal, LintGate.enabled());
    }

    CompletionGate(boolean enabled, String goal, LintGate lintGate) {
        this(enabled, goal, lintGate, false);
    }

    CompletionGate(boolean enabled, String goal, LintGate lintGate, boolean readOnlySession) {
        this.enabled = enabled;
        this.goal = goal;
        this.readOnlySession = readOnlySession;
        this.evidenceGate = new RefactorEvidenceGate(goal);
        this.lintGate = lintGate == null ? LintGate.disabled() : lintGate;
    }

    /** 用户在项目里写下的约束。收尾自审会再对一遍。 */
    void useProjectConstraints(String text) {
        projectConstraints = text == null ? "" : text.strip();
    }

    void countToolCall() {
        toolCalls++;
    }

    /** 追踪代码改动与验证的先后顺序：重构或修整代码后，此前旧版本的测试通过记录不能继续放行。 */
    void recordOutcome(Collection<String> changedPaths, List<String> outcomeChecks) {
        boolean codeChanged = changedPaths != null && touchesCode(changedPaths);
        if (codeChanged) {
            codeDirtySincePassingCheck = true;
        }
        boolean passedNow = outcomeChecks != null && hasPassingCheck(outcomeChecks);
        // 同一工具结果无法证明检查发生在变更之后；要求下一次独立检查再清除 dirty。
        if (passedNow && !codeChanged) {
            codeDirtySincePassingCheck = false;
        }
    }

    /** 记下这次调用碰过的路径，只读任务自审时说明「已经看过哪些」。 */
    void recordTarget(ToolCall call) {
        Object path = call.args() == null ? null : call.args().get("path");
        if (path == null) {
            return;
        }
        String text = String.valueOf(path).strip();
        if (!text.isEmpty()) {
            inspected.add(text);
        }
    }

    /**
     * 续跑时从已持久化的对话恢复只读覆盖事实。工具调用本身已经发生过，不能因为
     * 新建了一个 AgentLoop 就把调研任务重新当成“没有检查过任何东西”。跳过调用不计入。
     */
    void restoreFrom(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        Map<String, String> results = new HashMap<>();
        for (Message message : history) {
            if ("tool".equals(message.role()) && message.toolCallId() != null) {
                results.put(message.toolCallId(), message.content());
            }
        }
        for (Message message : history) {
            if (!"assistant".equals(message.role())) {
                continue;
            }
            for (ToolCall call : message.toolCalls()) {
                String result = results.get(call.callId());
                if (ToolResults.isSkipped(result)) {
                    continue;
                }
                countToolCall();
                recordTarget(call);
            }
        }
    }

    /**
     * 判断是否该在收尾前再催一轮。
     *
     * @param changedFiles 本轮改过的文件
     * @param checks       本轮跑过的验证命令摘要
     * @param plan         当前计划
     * @param openSteps    计划里还没完成的步骤
     * @return {@code null} 表示可以收尾
     */
    Nudge evaluate(Set<String> changedFiles, List<String> checks, List<PlanItem> plan, List<String> openSteps) {
        return evaluate(changedFiles, checks, plan, openSteps, false);
    }

    Nudge evaluate(Set<String> changedFiles, List<String> checks, List<PlanItem> plan, List<String> openSteps,
        boolean independentReview) {
        return evaluate(changedFiles, checks, plan, openSteps, independentReview, Map.of());
    }

    Nudge evaluate(Set<String> changedFiles, List<String> checks, List<PlanItem> plan, List<String> openSteps,
        boolean independentReview, Map<String, TextChange> qualityChanges) {
        return evaluate(changedFiles, checks, plan, openSteps, independentReview, qualityChanges, null, null);
    }

    /**
     * @param qualityChanges 本轮写前文本与当前文本；为空时跳过质量门，保持旧调用方行为
     * @param workspaceRoot  工作区根目录；非空时支持跨文件克隆查重与已有常量关联推荐
     * @param codeReviewer   轻量独立语义代码审查器；仅在验证通过且改动达标时最多运行一次
     */
    Nudge evaluate(Set<String> changedFiles, List<String> checks, List<PlanItem> plan, List<String> openSteps,
        boolean independentReview, Map<String, TextChange> qualityChanges, Path workspaceRoot,
        IndependentCodeReviewer codeReviewer) {
        return evaluate(changedFiles, checks, plan, openSteps, independentReview, qualityChanges, workspaceRoot,
            codeReviewer, "");
    }

    private record GateContext(Set<String> changedFiles, List<String> checks, List<PlanItem> plan,
        List<String> openSteps, Path workspace, ReviewScope review) {
        boolean changed() {
            return !changedFiles.isEmpty();
        }
    }

    private record ReviewScope(boolean independentReview, Map<String, TextChange> qualityChanges,
        IndependentCodeReviewer codeReviewer, String candidateAnswer) {
    }

    Nudge evaluate(Set<String> changedFiles, List<String> checks, List<PlanItem> plan, List<String> openSteps,
        boolean independentReview, Map<String, TextChange> qualityChanges, Path workspaceRoot,
        IndependentCodeReviewer codeReviewer, String candidateAnswer) {
        if (!enabled) {
            return null;
        }
        Path workspace =
            workspaceRoot != null ? workspaceRoot : codeReviewer == null ? null : codeReviewer.workspaceRoot();
        return evaluate(new GateContext(changedFiles, checks, plan, openSteps, workspace,
            new ReviewScope(independentReview, qualityChanges, codeReviewer, candidateAnswer)));
    }

    private Nudge evaluate(GateContext ctx) {
        boolean readOnlyTask = readOnlySession || GoalIntent.isExplicitReadOnly(goal);
        Nudge openPlan = checkOpenPlan(ctx, readOnlyTask);
        if (openPlan != null) {
            return openPlan;
        }
        // 只读任务没有改动，但「目标像调研、工具却只用了两三次」同样要核对覆盖范围
        boolean researching = !ctx.changed() && toolCalls >= 2 && (readOnlyTask || GoalIntent.looksLikeResearch(goal));
        Nudge noAction = checkNoAction(ctx, readOnlyTask, researching);
        if (noAction != null) {
            return noAction;
        }
        if (!ctx.changed() && !researching) {
            return null;
        }
        boolean needsVerify =
            codeDirtySincePassingCheck || (!hasPassingCheck(ctx.checks()) && !sameAsBaseline(ctx.checks()));
        return firstNudge(List.of(() -> checkScratchFiles(ctx), () -> checkLint(ctx),
            () -> checkVerification(ctx, needsVerify), () -> checkEvidence(ctx, readOnlyTask, needsVerify),
            () -> checkIndependentReview(ctx, needsVerify), () -> checkSelfReview(ctx)));
    }

    private static Nudge firstNudge(List<Supplier<Nudge>> checks) {
        for (Supplier<Nudge> check : checks) {
            Nudge nudge = check.get();
            if (nudge != null) {
                return nudge;
            }
        }
        return null;
    }

    private Nudge checkOpenPlan(GateContext ctx, boolean readOnlyTask) {
        if (readOnlyTask || ctx.openSteps().isEmpty() || planPrompts >= 2) {
            return null;
        }
        planPrompts++;
        return new Nudge("原始用户目标：" + (goal == null ? "见持久任务上下文" : goal) + "\n计划进度："
            + ctx.plan().stream().filter(PlanItem::done).count() + "/" + ctx.plan().size() + "\n仍未完成的计划：\n"
            + bullets(ctx.openSteps())
            + "\n若上述步骤已实际完成且具备验证证据，请直接调用 update_plan 将对应项标记为 completed（切勿仅在正文中口头总结，否则系统仍判定为 TASK_INCOMPLETE）；"
            + "若尚有可执行工作，请继续完成后再调用 update_plan 更新。不要删除或缩小要求来通过完成检查；"
            + "确实无法执行的步骤标 blocked 并写明原因，用户同意跳过的标 skipped；不要假装完成。", "计划仍有未完成项，继续执行并核对原始目标", planPrompts, 2);
    }

    private Nudge checkNoAction(GateContext ctx, boolean readOnlyTask, boolean researching) {
        // 实现类目标却一个文件都没改就想收尾，多半是只给了方案或反问了一句，而不是真的做完
        if (readOnlyTask || ctx.changed() || researching || !GoalIntent.looksLikeAction(goal)
            || GoalIntent.looksLikeResearch(goal)) {
            return null;
        }
        if (actionPrompts < MAX_ACTION_PROMPTS) {
            actionPrompts++;
            String prompt =
                LoopPromptCatalog.render("completion/act", Map.of("goal", Texts.oneLine(goal, 500))).strip();
            return new Nudge(prompt, "实现类目标尚无任何改动，已要求直接动手", actionPrompts, MAX_ACTION_PROMPTS);
        }
        return new Nudge("目标要求实际改动，但本轮仍没有文件改动。停止并如实报告未完成原因。", "实现类目标提醒后仍无改动，已停止", MAX_ACTION_PROMPTS,
            MAX_ACTION_PROMPTS, true);
    }

    private Nudge checkScratchFiles(GateContext ctx) {
        if (!ctx.changed() || scratchPrompts >= MAX_SCRATCH_PROMPTS) {
            return null;
        }
        List<String> scratchFiles = remainingScratchFiles(ctx.changedFiles(), ctx.workspace());
        if (scratchFiles.isEmpty()) {
            return null;
        }
        scratchPrompts++;
        String prompt =
            "本轮改动中仍残留以下临时/备份文件：\n" + bullets(scratchFiles) + "\n若它们仅用于中途暂存或验证，请先删除清理并确认工作区干净后再交付；若确为任务要求的产物，请说明用途。";
        return new Nudge(prompt, "工作区残留本轮产生的临时文件，已要求清理", scratchPrompts, MAX_SCRATCH_PROMPTS);
    }

    private Nudge checkLint(GateContext ctx) {
        if (!ctx.changed()) {
            return null;
        }
        boolean verified = hasPassingCheck(ctx.checks()) && !codeDirtySincePassingCheck;
        boolean countStyleRound = !lintGate.retriesOnFailure() || verified;
        LintGate.LintAssessment lint = lintGate.assess(ctx.workspace(), ctx.changedFiles(), countStyleRound);
        LintGate.LintAssessment.Kind kind = lint.kind();
        if (kind == LintGate.LintAssessment.Kind.BLOCKED) {
            return new Nudge(lint.prompt(), lint.reason(), 1, 1, true);
        }
        if (kind != LintGate.LintAssessment.Kind.FAILED && kind != LintGate.LintAssessment.Kind.FINDINGS) {
            return null;
        }
        if (lintGate.stopsOnFailure()) {
            return new Nudge(lint.prompt(), lint.reason(), 1, 1, true);
        }
        if (!lintGate.retriesOnFailure()) {
            return null;
        }
        if (verified) {
            if (kind == LintGate.LintAssessment.Kind.FINDINGS && !lint.exhausted()) {
                return new Nudge(lint.prompt(), lint.reason(), 1, LintGate.SAME_FAILURE_LIMIT, false);
            }
            return null;
        }
        if (!touchesCode(ctx.changedFiles()) || verifyPrompts >= MAX_VERIFY_PROMPTS) {
            return new Nudge(lint.prompt() + "\n功能还没验证通过，不能放行。", "规范未过且功能未验证，不能放行", 1, 1, true);
        }
        return null;
    }

    private Nudge checkVerification(GateContext ctx, boolean needsVerify) {
        if (!ctx.changed() || !touchesCode(ctx.changedFiles()) || !needsVerify) {
            return null;
        }
        if (verifyPrompts < MAX_VERIFY_PROMPTS) {
            verifyPrompts++;
            String checkDesc = describeChecksForVerify(ctx.checks());
            String prompt = LoopPromptCatalog
                .render("completion/verify", Map.of("changedFiles", bullets(ctx.changedFiles()), "checks", checkDesc))
                .strip();
            String reason = codeDirtySincePassingCheck && hasPassingCheck(ctx.checks()) ? "最近一次代码整理后尚未重新验证，已要求重跑测试"
                : "改动未验证，已要求先跑构建或测试";
            return new Nudge(prompt, reason, verifyPrompts, MAX_VERIFY_PROMPTS);
        }
        return new Nudge("代码改动仍没有当前版本的通过验证。停止并如实报告未完成项及验证阻碍。", "达到验证提醒上限仍未验证，已停止", MAX_VERIFY_PROMPTS,
            MAX_VERIFY_PROMPTS, true);
    }

    private String describeChecksForVerify(List<String> checks) {
        if (checks.isEmpty()) {
            return "（本轮没有跑过构建或测试）";
        }
        if (codeDirtySincePassingCheck) {
            return bullets(checks) + "\n（注意：上述验证早于最近一次代码修改，请对最新代码重跑验证）";
        }
        return bullets(checks);
    }

    private Nudge checkEvidence(GateContext ctx, boolean readOnlyTask, boolean needsVerify) {
        if (readOnlyTask || evidencePrompts >= MAX_EVIDENCE_PROMPTS) {
            return null;
        }
        String evidenceIssue = evidenceGate.evaluate(ctx.workspace(), ctx.changedFiles(), ctx.review().qualityChanges(),
            !needsVerify, ctx.review().candidateAnswer());
        if (evidenceIssue == null || evidenceIssue.isBlank()) {
            return null;
        }
        evidencePrompts++;
        return new Nudge(evidenceIssue, "重构/性能/边界硬证据不足，已要求补齐可核验证据", evidencePrompts, MAX_EVIDENCE_PROMPTS);
    }

    private Nudge checkIndependentReview(GateContext ctx, boolean needsVerify) {
        ReviewScope review = ctx.review();
        if (review.independentReview() || !ctx.changed() || review.codeReviewer() == null || independentCodeReviewed) {
            return null;
        }
        independentCodeReviewed = true;
        boolean reviewedDiff = IndependentCodeReviewer.shouldReview(review.qualityChanges());
        String refactorGuidance = review.codeReviewer().review(goal, review.qualityChanges());
        if (refactorGuidance != null && !refactorGuidance.isBlank()) {
            reviewPrompts = MAX_REVIEW_PROMPTS;
            return new Nudge(refactorGuidance, "独立代码精简审查发现可安全收敛的重复逻辑或魔法值", 1, 1);
        }
        // 独立代码审查已审阅本次变更并放行，且测试已通过、计划无剩余步骤时，不再追加冗余自审轮次打断最终答复。
        if (reviewedDiff && !needsVerify && ctx.openSteps().isEmpty()) {
            reviewPrompts = MAX_REVIEW_PROMPTS;
        }
        return null;
    }

    private Nudge checkSelfReview(GateContext ctx) {
        if (ctx.review().independentReview() || reviewPrompts >= MAX_REVIEW_PROMPTS) {
            return null;
        }
        reviewPrompts++;
        String goalText = goal == null ? "（续跑，无原始输入）" : Texts.oneLine(goal, 500);
        String prompt;
        if (ctx.changed()) {
            int done = (int)ctx.plan().stream().filter(PlanItem::done).count();
            prompt = LoopPromptCatalog.render("completion/review",
                Map.of("goal", goalText, "toolCalls", String.valueOf(toolCalls), "changedFiles",
                    bullets(ctx.changedFiles()), "planProgress",
                    ctx.plan().isEmpty() ? "（本轮没有维护计划）" : done + "/" + ctx.plan().size() + " 已完成", "openSteps",
                    ctx.openSteps().isEmpty() ? "（无）" : bullets(ctx.openSteps()), "checks",
                    ctx.checks().isEmpty() ? "（未执行验证）" : bullets(ctx.checks()), "constraints", constraintBlock()));
        } else {
            prompt = LoopPromptCatalog.render("completion/review-readonly", Map.of("goal", goalText, "toolCalls",
                String.valueOf(toolCalls), "inspected", inspected.isEmpty() ? "（未记录具体路径）" : bullets(inspected)));
            if (!projectConstraints.isBlank()) {
                prompt = prompt + constraintBlock();
            }
        }
        return new Nudge(prompt.strip(), "收尾前自审：核对目标与结论是否真的覆盖完整", reviewPrompts, MAX_REVIEW_PROMPTS);
    }

    private String constraintBlock() {
        if (projectConstraints.isBlank()) {
            return "";
        }
        return "\n本项目约束（必须遵守，做不到就继续改，不要宣称完成）：\n" + projectConstraints;
    }

    private static String bullets(Collection<String> values) {
        return values.stream().map(value -> "- " + value).collect(Collectors.joining("\n"));
    }

    /** 改动里是否包含代码文件；纯文档、非代码资源与临时暂存文件不强制跑构建。 */
    private static boolean touchesCode(Collection<String> changedFiles) {
        return changedFiles.stream().anyMatch(path -> {
            if (scratchToken(path) != null) {
                return false;
            }
            String lower = path.toLowerCase(Locale.ROOT);
            return DOC_SUFFIXES.stream().noneMatch(lower::endsWith)
                && ASSET_SUFFIXES.stream().noneMatch(lower::endsWith);
        });
    }

    private List<String> remainingScratchFiles(Set<String> changedFiles, Path workspace) {
        String lowerGoal = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
        Path root = workspace == null ? null : workspace.toAbsolutePath().normalize();
        return changedFiles.stream().filter(path -> {
            String token = scratchToken(path);
            if (token == null) {
                return false;
            }
            if (!lowerGoal.isEmpty() && lowerGoal.contains(token)) {
                return false;
            }
            if (root == null) {
                return true;
            }
            Path resolved = root.resolve(path).normalize();
            return resolved.startsWith(root) && Files.exists(resolved);
        }).sorted().toList();
    }

    /** 命中临时目录段（.tmp、.tmp- 前缀或 .tmp_ 前缀）或备份后缀（.tmp/.bak/.orig/.rej）时返回对应标识词。 */
    static String scratchToken(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String normalized = rawPath.strip().replace('\\', '/');
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (lower.equals(".tmp") || lower.startsWith(".tmp-") || lower.startsWith(".tmp_")) {
                return lower;
            }
        }
        String lowerLeaf = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        for (String suffix : SCRATCH_SUFFIXES) {
            if (lowerLeaf.endsWith(suffix)) {
                return suffix;
            }
        }
        return null;
    }

    void baseline(List<String> checks) {
        baselineChecks = Set.copyOf(checks == null ? List.of() : checks);
    }

    private boolean sameAsBaseline(List<String> checks) {
        return !baselineChecks.isEmpty() && baselineChecks.equals(Set.copyOf(checks));
    }

    private static boolean hasPassingCheck(List<String> checks) {
        return checks.stream().map(VerificationCheck::passingKind).anyMatch(VerificationCheck.Kind::qualifies);
    }
}
