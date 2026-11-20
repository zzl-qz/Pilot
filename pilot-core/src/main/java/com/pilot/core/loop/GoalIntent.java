package com.pilot.core.loop;

import java.util.List;
import java.util.Locale;

/**
 * 基于用户目标文本的轻量意图判定，供 {@link CompletionGate} 与执行策略共享同一口径。
 *
 * <p>调用方应优先使用结构性信号（只读会话标志、可用工具能力集等）；只有在写工具可用且
 * 无显式会话模式时，才回退到这里区分「显式全局只读」「纯调研提问」与「实现类动作」。
 */
public final class GoalIntent {
    /** 仅匹配明确禁止本轮修改任何仓库文件的全局范围声明，不匹配窄范围文件限制或业务名词「只读」。 */
    private static final List<String> EXPLICIT_READ_ONLY_HINTS =
        List.of("本轮只读", "本次只读", "本任务只读", "仅做只读", "只分析不修改", "不要直接动手", "先不要落地", "不要动手", "不改代码", "不要改代码", "不要修改任何仓库文件",
            "不得修改任何仓库文件", "不要修改任何文件", "不得修改任何文件", "do not modify any files", "do not change any files",
            "do not edit any files", "this task is read-only", "read-only task");
    private static final List<String> ACTION_HINTS =
        List.of("修复", "修改", "实现", "添加", "增加", "新增", "重构", "改造", "优化", "改成", "改为", "开发", "落地");
    private static final List<String> ACTION_HINTS_EN =
        List.of("fix", "implement", "add ", "change", "refactor", "update", "build");
    private static final List<String> CODE_HINTS =
        List.of("代码", "函数", "方法", "接口", "模块", "文件", "仓库", "页面", "组件", "测试", "脚本", "配置", "依赖", "构建", "部署", "报错", "异常",
            "性能", "数据库", "sql", "前端", "后端", "服务", "日志", "样式", "工程", "分支", "提交", "路由", "bug");
    private static final List<String> CODE_HINTS_EN =
        List.of("code", "function", "method", "api", "module", "file", "repo", "page", "component", "test", "script",
            "config", "build", "deploy", "error", "database", "service", "log", "style", "bug");
    private static final List<String> CONSULT_HINTS =
        List.of("建议", "看看", "评估", "分析", "意见", "思路", "方案", "对比", "解释", "为什么", "是否", "值不值", "有什么", "怎么看", "判断一下", "调研");
    private static final List<String> CONSULT_HINTS_EN =
        List.of("suggest", "advice", "opinion", "evaluate", "compare", "explain", "review");
    private static final List<String> RESEARCH_HINTS = List.of("分析", "梳理", "说明", "解释", "为什么", "怎么实现", "如何实现", "调用链",
        "流程", "审查", "评估", "调研", "研究", "看一下", "读一下", "哪些", "对比", "定位");
    private static final List<String> RESEARCH_HINTS_EN =
        List.of("explain", "analyze", "analyse", "research", "review", "how does", "why", "walk through");

    private GoalIntent() {
    }

    public static boolean isExplicitReadOnly(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        String lower = goal.toLowerCase(Locale.ROOT);
        return EXPLICIT_READ_ONLY_HINTS.stream().anyMatch(lower::contains);
    }

    public static boolean looksLikeResearch(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        String lower = goal.toLowerCase(Locale.ROOT);
        return RESEARCH_HINTS.stream().anyMatch(goal::contains) || RESEARCH_HINTS_EN.stream().anyMatch(lower::contains);
    }

    public static boolean hasActionVerb(String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        String lower = goal.toLowerCase(Locale.ROOT);
        return ACTION_HINTS.stream().anyMatch(goal::contains) || ACTION_HINTS_EN.stream().anyMatch(lower::contains);
    }

    public static boolean looksLikeAction(String goal) {
        if (!hasActionVerb(goal)) {
            return false;
        }
        String lower = goal.toLowerCase(Locale.ROOT);
        boolean codeTarget =
            CODE_HINTS.stream().anyMatch(lower::contains) || CODE_HINTS_EN.stream().anyMatch(lower::contains);
        if (codeTarget) {
            return true;
        }
        boolean consult =
            CONSULT_HINTS.stream().anyMatch(goal::contains) || CONSULT_HINTS_EN.stream().anyMatch(lower::contains);
        return !consult;
    }

    public static boolean isResearchOnly(String goal) {
        if (isExplicitReadOnly(goal)) {
            return true;
        }
        return looksLikeResearch(goal) && !hasActionVerb(goal);
    }
}
