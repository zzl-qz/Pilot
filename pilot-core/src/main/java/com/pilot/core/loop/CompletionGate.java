package com.pilot.core.loop;

import com.pilot.core.plan.PlanItem;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolSupport;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 收尾门：模型说「做完了」时，先检查它是不是真的做完了。
 *
 * <p>两道门，各最多催几次：
 * <ol>
 *   <li>验证：改了代码，却没有任何退出码为 0 的构建 / 测试记录；</li>
 *   <li>自审：有文件改动（或只读任务读得太少），让模型对照原始目标和计划再核对一遍有没有缩水。</li>
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
    /** 只改这些后缀的文档不算「改了代码」，不强制跑构建。 */
    private static final Set<String> DOC_SUFFIXES = Set.of(".md", ".txt", ".rst", ".adoc");
    /** 目标像「调研 / 解释 / 定位」时，只读任务也要核对覆盖范围，不能读两个文件就下结论。 */
    private static final List<String> RESEARCH_HINTS = List.of(
            "分析", "梳理", "说明", "解释", "为什么", "怎么实现", "如何实现", "调用链", "流程",
            "审查", "评估", "调研", "看一下", "读一下", "哪些", "对比", "定位");
    private static final List<String> RESEARCH_HINTS_EN = List.of(
            "explain", "analyze", "analyse", "review", "how does", "why", "walk through");
    /** 从验证摘要里读退出码，例如 {@code mvn -q test → exit 0}。 */
    private static final Pattern EXIT_CODE = Pattern.compile("exit (-?\\d+)");

    /** @param attempt 第几次提醒；{@code max} 是这类提醒的上限 */
    record Nudge(String prompt, String reason, int attempt, int max) {
    }

    private final PromptLibrary prompts;
    private final boolean enabled;
    private final String goal;
    private int verifyPrompts;
    private int reviewPrompts;
    private int toolCalls;
    private final Set<String> inspected = new LinkedHashSet<>();

    /**
     * @param goal    本轮原始任务，自审时回灌给模型对照；续跑没有原始输入时为 {@code null}
     * @param enabled 关闭后直接放行，不做任何催办
     */
    CompletionGate(PromptLibrary prompts, boolean enabled, String goal) {
        this.prompts = prompts;
        this.enabled = enabled;
        this.goal = goal;
    }

    void countToolCall() {
        toolCalls++;
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
     * 判断是否该在收尾前再催一轮。
     *
     * @param changedFiles 本轮改过的文件
     * @param checks       本轮跑过的验证命令摘要
     * @param plan         当前计划
     * @param openSteps    计划里还没完成的步骤
     * @return {@code null} 表示可以收尾
     */
    Nudge evaluate(Set<String> changedFiles, List<String> checks, List<PlanItem> plan, List<String> openSteps) {
        if (!enabled) {
            return null;
        }
        boolean changed = !changedFiles.isEmpty();
        // 只读任务没有改动，但「目标像调研、工具却只用了两三次」同样要核对覆盖范围
        boolean researching = !changed && toolCalls >= 2 && looksLikeResearch(goal);
        if (!changed && !researching) {
            return null;
        }
        if (changed && touchesCode(changedFiles) && !hasPassingCheck(checks)
                && verifyPrompts < MAX_VERIFY_PROMPTS) {
            verifyPrompts++;
            String prompt = prompts.render("completion/verify", Map.of(
                    "changedFiles", bullets(changedFiles),
                    "checks", checks.isEmpty() ? "（本轮没有跑过构建或测试）" : bullets(checks))).strip();
            return new Nudge(prompt, "改动未验证，已要求先跑构建或测试", verifyPrompts, MAX_VERIFY_PROMPTS);
        }
        if (reviewPrompts < MAX_REVIEW_PROMPTS) {
            reviewPrompts++;
            String goalText = goal == null ? "（续跑，无原始输入）" : ToolSupport.oneLine(goal, 500);
            String prompt;
            if (changed) {
                int done = (int) plan.stream().filter(PlanItem::done).count();
                prompt = prompts.render("completion/review", Map.of(
                        "goal", goalText,
                        "toolCalls", String.valueOf(toolCalls),
                        "changedFiles", bullets(changedFiles),
                        "planProgress", plan.isEmpty()
                                ? "（本轮没有维护计划）"
                                : done + "/" + plan.size() + " 已完成",
                        "openSteps", openSteps.isEmpty() ? "（无）" : bullets(openSteps),
                        "checks", checks.isEmpty() ? "（未执行验证）" : bullets(checks)));
            } else {
                prompt = prompts.render("completion/review-readonly", Map.of(
                        "goal", goalText,
                        "toolCalls", String.valueOf(toolCalls),
                        "inspected", inspected.isEmpty() ? "（未记录具体路径）" : bullets(inspected)));
            }
            return new Nudge(prompt.strip(), "收尾前自审：核对目标与结论是否真的覆盖完整",
                    reviewPrompts, MAX_REVIEW_PROMPTS);
        }
        return null;
    }

    private static boolean looksLikeResearch(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        String lower = goal.toLowerCase(Locale.ROOT);
        return RESEARCH_HINTS.stream().anyMatch(goal::contains)
                || RESEARCH_HINTS_EN.stream().anyMatch(lower::contains);
    }

    private static String bullets(Collection<String> values) {
        return values.stream().map(value -> "- " + value).collect(Collectors.joining("\n"));
    }

    /** 改动里是否包含代码文件；纯文档改动不强制跑构建。 */
    private static boolean touchesCode(Collection<String> changedFiles) {
        return changedFiles.stream().anyMatch(path -> {
            String lower = path.toLowerCase(Locale.ROOT);
            return DOC_SUFFIXES.stream().noneMatch(lower::endsWith);
        });
    }

    private static boolean hasPassingCheck(List<String> checks) {
        for (String check : checks) {
            Matcher matcher = EXIT_CODE.matcher(check);
            if (matcher.find() && "0".equals(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }
}
