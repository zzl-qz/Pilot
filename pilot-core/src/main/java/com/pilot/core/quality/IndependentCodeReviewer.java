package com.pilot.core.quality;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import com.pilot.core.lint.SourceFiles;
import com.pilot.core.message.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.model.diagnostics.ModelRequestScope;
import com.pilot.core.multi.UnifiedDiff;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.ToolSchemas;
import com.pilot.core.util.Texts;

/**
 * 收尾阶段的轻量独立语义代码审查器。
 * 仅在本轮产生较大规模生产代码改动且构建/测试通过后最多触发一次。
 * 使用不带历史对话包袱的独立上下文，只看用户目标、公共资产清单与本次 diff，
 * 专门识别语义级重复造轮子、遗漏复用与过度设计；任何模型异常均安全放行，不阻断主流程。
 */
public final class IndependentCodeReviewer {
    /** 至少改动这么多生产代码文件，或净新增达到行数阈值，才值得发起独立语义审查。 */
    public static final int MIN_CODE_FILES = 2;
    public static final int MIN_ADDED_CODE_LINES = 35;
    private static final int MAX_DIFF_CHARS = 12_000;
    private static final int MAX_ISSUES = 4;
    private static final String TOOL_NAME = "submit_code_review";

    private final ModelClient model;
    private final Path workspaceRoot;
    private final PromptLibrary prompts;
    private final BooleanSupplier cancelled;

    public IndependentCodeReviewer(ModelClient model, Path workspaceRoot, BooleanSupplier cancelled) {
        this(model, workspaceRoot, PromptLibrary.standard(), cancelled);
    }

    public IndependentCodeReviewer(ModelClient model, Path workspaceRoot, PromptLibrary prompts,
        BooleanSupplier cancelled) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.workspaceRoot = workspaceRoot;
        this.prompts = Objects.requireNonNull(prompts, "prompts must not be null");
        this.cancelled = cancelled == null ? () -> false : cancelled;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** 判断本轮代码变更规模是否值得触发独立语义审查，小修小补直接跳过。 */
    public static boolean shouldReview(Map<String, TextChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return false;
        }
        int codeFiles = 0;
        for (Map.Entry<String, TextChange> entry : changes.entrySet()) {
            if (SourceFiles.isProductionSource(entry.getKey()) && entry.getValue() != null
                && entry.getValue().after() != null) {
                codeFiles++;
            }
        }
        if (codeFiles == 0) {
            return false;
        }
        return codeFiles >= MIN_CODE_FILES || addedLines(changes) >= MIN_ADDED_CODE_LINES;
    }

    /**
     * 执行一次独立语义审查。
     *
     * @return 需要主 Agent 收敛改进时返回具体指引文本；通过或无法审查时返回 {@code null}
     */
    public String review(String goal, Map<String, TextChange> changes) {
        if (cancelled.getAsBoolean() || !shouldReview(changes)) {
            return null;
        }
        String diff = buildProductionDiff(changes);
        if (diff.isBlank()) {
            return null;
        }
        try {
            String userPayload =
                "原始用户目标：\n" + (goal == null || goal.isBlank() ? "（按当前代码变更上下文判断）" : Texts.oneLine(goal, 600))
                    + "\n\n本次生产代码变更 Diff（待审数据）：\n" + diff;

            List<Message> messages = List.of(Message.system(prompts.render("completion/code-review", Map.of()).strip()),
                Message.user(userPayload));
            ToolDefinition tool = new ToolDefinition(TOOL_NAME, "提交独立代码质量与复用审查结论", schema());
            ModelResponse response =
                ModelRequestScope.within("code-review", null, false, () -> model.chat(messages, List.of(tool)));
            if (cancelled.getAsBoolean() || response == null || response.toolCalls() == null
                || response.toolCalls().size() != 1) {
                return null;
            }
            var call = response.toolCalls().getFirst();
            if (!TOOL_NAME.equals(call.name()) || call.args() == null) {
                return null;
            }
            return parseRefactorGuidance(call.args());
        } catch (RuntimeException ignored) {
            // 独立代码审查属于质量增强层；模型异常或不支持工具调用时安全放行，不影响正常交付。
            return null;
        }
    }

    private static int addedLines(Map<String, TextChange> changes) {
        int total = 0;
        for (TextChange change : changes.values()) {
            if (change == null || change.after() == null) {
                continue;
            }
            int after = change.after().split("\n", -1).length;
            int before = change.before() == null ? 0 : change.before().split("\n", -1).length;
            total += Math.max(0, after - before);
        }
        return total;
    }

    private static String buildProductionDiff(Map<String, TextChange> changes) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, TextChange> entry : changes.entrySet()) {
            String path = entry.getKey();
            TextChange change = entry.getValue();
            if (!SourceFiles.isProductionSource(path) || change == null || change.after() == null) {
                continue;
            }
            String patch = UnifiedDiff.create(path, change.before(), change.after());
            if (patch.isBlank()) {
                continue;
            }
            if (out.length() + patch.length() > MAX_DIFF_CHARS) {
                int remain = MAX_DIFF_CHARS - out.length();
                if (remain > 200) {
                    out.append(patch, 0, remain).append("\n…（后续 diff 已截断）");
                }
                break;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(patch.strip());
        }
        return out.toString();
    }

    private static String parseRefactorGuidance(Map<String, Object> args) {
        Object rawVerdict = args.get("verdict");
        if (rawVerdict == null || !"REFACTOR".equalsIgnoreCase(String.valueOf(rawVerdict).strip())) {
            return null;
        }
        Object rawIssues = args.get("issues");
        if (!(rawIssues instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> issues = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = Texts.oneLine(String.valueOf(item), 260);
            if (!text.isBlank()) {
                issues.add(text);
            }
            if (issues.size() >= MAX_ISSUES) {
                break;
            }
        }
        if (issues.isEmpty()) {
            return null;
        }
        StringBuilder prompt = new StringBuilder("[独立代码精简审查] 检测到本次新增代码存在可安全收敛的重复造轮子、魔法值或过度抽象：\n");
        for (String issue : issues) {
            prompt.append("- ").append(issue).append('\n');
        }
        prompt.append("请在不改变对外接口契约和原有业务功能的前提下完成精简复用，修改后必须重新运行对应的测试或构建确认零回归；")
            .append("若某条经核实确实不宜改动，保留现有实现并在最终回复简要说明原因。");
        return prompt.toString();
    }

    private static Map<String, Object> schema() {
        Map<String, Object> verdictSchema = Map.of("type", "string", "enum", List.of("PASS", "REFACTOR"), "description",
            "PASS 表示无需重构直接放行；REFACTOR 表示存在明确且改动安全的重复逻辑、遗漏复用或魔法值");
        Map<String, Object> issuesSchema = Map.of("type", "array", "items", ToolSchemas.string("filepath:line 问题与最小改法"),
            "description", "仅 REFACTOR 时填写，最多 4 条；PASS 时传空数组");
        return ToolSchemas.object(Map.of("verdict", verdictSchema, "issues", issuesSchema),
            List.of("verdict", "issues"));
    }
}
