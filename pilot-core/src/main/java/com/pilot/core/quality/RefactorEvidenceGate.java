package com.pilot.core.quality;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.pilot.core.quality.CodeQualityCheck.TextChange;
import com.pilot.core.quality.CodebaseAuditor.AuditItem;
import com.pilot.core.quality.CodebaseAuditor.AuditSnapshot;
import com.pilot.core.quality.CodebaseAuditor.Category;
import com.pilot.core.quality.CodebaseAuditor.DeltaReport;
import com.pilot.core.util.FileNames;

/**
 * 存量重构、性能优化与边界加固的防糊弄硬证据门禁。
 * 对比改动前后真实静态审计账本、测试文件变更记录与验证执行记录，拦截无证据口头宣称完成。
 */
public final class RefactorEvidenceGate {
    private static final int MAX_SHOWN_ITEMS = 5;

    private static final Set<String> CODE_EXTS = Set.of("java", "kt", "kts", "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "py", "go", "rs", "cs", "cpp", "cc", "c", "h", "swift");
    private static final Set<String> TEST_DIRS = Set.of("test", "tests", "spec", "specs", "__tests__");

    private static final List<String> READ_ONLY_HINTS = List.of("本轮只读", "本次只读", "本任务只读", "仅做只读", "只分析不修改", "只输出方案",
        "不改代码", "不修改业务代码", "不要修改任何仓库文件", "不得修改任何仓库文件", "不要修改任何文件", "不得修改任何文件", "do not modify any files",
        "do not change any files", "do not edit any files", "this task is read-only", "read-only");

    private static final List<String> STRONG_CONSULT_PHRASES = List.of("重构方案", "重构建议", "重构思路", "优化建议", "优化方案", "优化空间",
        "怎么优化", "如何优化", "怎么重构", "如何重构", "只给建议", "先不改", "先别改", "有哪些问题", "评估一下");
    private static final List<String> CONSULT_HINTS =
        List.of("建议", "评估", "分析", "意见", "思路", "方案", "对比", "解释", "为什么", "是否", "怎么看", "判断一下", "调研", "有哪些", "看看怎么",
            "suggest", "advice", "opinion", "evaluate", "compare", "explain", "analyze", "analyse");
    private static final List<String> DIRECT_EDIT_PHRASES =
        List.of("并重构", "并修复", "并修改", "并清理", "并优化", "并完善", "并补充", "直接重构", "直接修改", "直接修复", "请重构", "请修复", "请清理", "动手");

    private static final List<String> CLEANUP_HINTS = List.of("老项目", "旧项目", "历史代码", "存量", "重构", "精简", "去重", "重复代码",
        "消除重复", "魔法值", "魔法数", "消除魔法值", "清理魔法值", "硬编码", "内聚", "坏味道", "refactor", "legacy", "deduplicate",
        "duplicate code", "magic number", "magic value", "clean up");
    private static final List<String> PERF_HINTS =
        List.of("性能优化", "提升性能", "优化性能", "性能问题", "性能瓶颈", "慢查询", "耗时", "吞吐", "n+1", "内存泄漏", "benchmark",
            "optimize performance", "performance optimization", "bottleneck", "latency", "throughput");
    private static final List<String> BOUNDARY_HINTS = List.of("边界情况", "边界条件", "极端情况", "异常路径", "鲁棒性", "健壮性", "容错",
        "edge case", "edge cases", "boundary condition", "boundary case", "robustness", "corner case");

    private static final List<String> PERF_CLAIM_HINTS = List.of("性能提升", "性能大幅", "耗时降低", "耗时减少", "响应更快", "速度更快", "运行更快",
        "吞吐提升", "提速", "performance improved", "faster execution", "latency reduced");
    private static final Pattern CONCRETE_PERF_PROOF = Pattern.compile(
        "(?i)(?:\\b\\d+(?:\\.\\d+)?\\s*(?:ms|ns|us|s|%|倍|x|ops|qps|tps|kb|mb)\\b|->|→|O\\([1nN]|n\\+1|批量|预编译|复用"
            + "|StringBuilder|有界|缓存|audit_codebase|未跑.*(?:基准|压测)|未做.*(?:基准|压测)|静态消除|not benchmarked)");

    public record Intent(boolean cleanup, boolean performance, boolean boundary) {
        public static final Intent NONE = new Intent(false, false, false);

        public boolean active() {
            return cleanup || performance || boundary;
        }
    }

    private final Intent intent;
    private AuditSnapshot workspaceBaseline;
    private boolean auditToolInvoked;

    public RefactorEvidenceGate(String goal) {
        this.intent = classify(goal);
    }

    public Intent intent() {
        return intent;
    }

    public void recordAuditToolCall() {
        this.auditToolInvoked = true;
    }

    public AuditSnapshot captureBaseline(Path workspaceRoot) {
        if (workspaceBaseline == null && workspaceRoot != null) {
            workspaceBaseline = CodebaseAuditor.audit(workspaceRoot);
        }
        return workspaceBaseline == null ? AuditSnapshot.EMPTY : workspaceBaseline;
    }

    public static Intent classify(String goal) {
        if (goal == null || goal.isBlank()) {
            return Intent.NONE;
        }
        String lower = goal.toLowerCase(Locale.ROOT);
        if (isConsultationOrReadOnly(goal, lower)) {
            return Intent.NONE;
        }
        boolean cleanup = containsAny(lower, CLEANUP_HINTS);
        boolean perf = containsAny(lower, PERF_HINTS);
        boolean boundary = containsAny(lower, BOUNDARY_HINTS);
        return cleanup || perf || boundary ? new Intent(cleanup, perf, boundary) : Intent.NONE;
    }

    public static DeltaReport delta(Path workspaceRoot, Map<String, TextChange> qualityChanges) {
        if (qualityChanges == null || qualityChanges.isEmpty()) {
            return CodebaseAuditor.compare(AuditSnapshot.EMPTY, AuditSnapshot.EMPTY);
        }
        Map<String, String> beforeTexts = new LinkedHashMap<>();
        Map<String, String> afterTexts = new LinkedHashMap<>();
        qualityChanges.forEach((path, change) -> {
            if (change != null && CodeQualityCheck.applies(path)) {
                if (change.before() != null && !change.before().isBlank()) {
                    beforeTexts.put(path, change.before());
                }
                if (change.after() != null && !change.after().isBlank()) {
                    afterTexts.put(path, change.after());
                }
            }
        });
        AuditSnapshot before = CodebaseAuditor.auditTexts(workspaceRoot, beforeTexts);
        AuditSnapshot after = CodebaseAuditor.auditTexts(workspaceRoot, afterTexts);
        return CodebaseAuditor.compare(before, after);
    }

    public String evaluate(Path workspaceRoot, Set<String> changedFiles, Map<String, TextChange> qualityChanges,
        boolean hasPassingVerification, String candidateAnswer) {
        boolean perfClaimed = claimsPerformanceGain(candidateAnswer);
        if (!intent.active() && !perfClaimed) {
            return null;
        }
        Set<String> files = changedFiles == null ? Set.of() : changedFiles;
        boolean prodChanged = files.stream().anyMatch(CodeQualityCheck::applies);
        boolean testChanged = files.stream().anyMatch(RefactorEvidenceGate::isTestCodePath);
        if (!prodChanged && !perfClaimed) {
            return null;
        }

        DeltaReport report = delta(workspaceRoot, qualityChanges);
        List<String> gaps = new ArrayList<>();

        if (prodChanged && report.regressed()) {
            gaps.add(
                "变更后静态审计问题数不降反升（" + report.renderSummary() + "），请先清理引入或遗留的问题：" + formatItems(report.after().items()));
        } else if (intent.cleanup() && prodChanged && report.totalIssuesAfter() > 0 && !report.improved()) {
            gaps.add("目标要求精简存量代码或清理魔法值/重复逻辑，但触达文件问题总数未下降（" + report.renderSummary() + "），仍存在以下待收敛项："
                + formatItems(report.after().items()));
        }

        if (intent.boundary() && prodChanged && !testChanged) {
            gaps.add("目标要求完善边界情况，但本轮只改了业务代码，未新增或更新任何边界单元测试作为证据；请补充覆盖空值/极值/异常路径的测试并跑通");
        } else if (intent.boundary() && prodChanged && report.after().boundaryHazards() > 0
            && report.boundaryDelta() >= 0) {
            gaps.add("触达代码仍残留 " + report.after().boundaryHazards() + " 处未关闭资源、空 catch 或无守卫取值隐患："
                + formatItems(report.after().filterFocus(Category.BOUNDARY.focusKey())));
        }

        if (intent.performance() || perfClaimed) {
            if (prodChanged && report.after().perfHazards() > 0 && report.perfDelta() >= 0) {
                gaps.add("触达代码仍残留 " + report.after().perfHazards() + " 处循环 N+1、循环重分配或无界读取性能隐患："
                    + formatItems(report.after().filterFocus(Category.PERFORMANCE.focusKey())));
            }
            if (!hasPassingVerification) {
                gaps.add("性能优化或性能提升结论缺少已通过的测试/命令执行记录；请实际运行验证或基准命令取得证据");
            } else if (lacksConcretePerfProof(candidateAnswer, report)) {
                gaps.add("候选结论存在无数据支撑的性能提升表述；请补充前后真实耗时/调用次数/复杂度消减证据（可结合 audit_codebase 或测试输出），"
                    + "未做压测的部分必须如实说明为静态隐患消除，严禁夸大或捏造收益");
            }
        }

        if (gaps.isEmpty()) {
            return null;
        }
        StringBuilder nudge = new StringBuilder("[防糊弄硬证据核验] 当前交付尚缺可机器核实的优化证据：\n");
        for (int i = 0; i < gaps.size(); i++) {
            nudge.append(i + 1).append(". ").append(gaps.get(i)).append('\n');
        }
        nudge.append("请补齐上述真实代码收敛或测试证据，并在任何代码/测试修改后重新运行测试确保通过；若某处字面量或结构确需保留，请在最终回复逐处写明客观原因。");
        return nudge.toString();
    }

    public static boolean isTestCodePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String norm = path.replace('\\', '/');
        if (!CODE_EXTS.contains(FileNames.extension(norm))) {
            return false;
        }
        String[] parts = norm.split("/");
        if (CodeQualityCheck.isTestFile(parts[parts.length - 1])) {
            return true;
        }
        for (int i = 0; i < parts.length - 1; i++) {
            if (TEST_DIRS.contains(parts[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean claimsPerformanceGain(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return containsAny(lower, PERF_CLAIM_HINTS);
    }

    private boolean lacksConcretePerfProof(String candidateAnswer, DeltaReport report) {
        if (candidateAnswer == null || candidateAnswer.isBlank()) {
            return false;
        }
        if (CONCRETE_PERF_PROOF.matcher(candidateAnswer).find()) {
            return false;
        }
        return !(auditToolInvoked && report.perfDelta() < 0);
    }

    private static boolean isConsultationOrReadOnly(String goal, String lower) {
        if (containsAny(lower, READ_ONLY_HINTS)) {
            return true;
        }
        boolean hasDirectEdit = containsAny(lower, DIRECT_EDIT_PHRASES);
        if (!hasDirectEdit && containsAny(lower, STRONG_CONSULT_PHRASES)) {
            return true;
        }
        return !hasDirectEdit && containsAny(lower, CONSULT_HINTS);
    }

    private static boolean containsAny(String lowerText, List<String> keywords) {
        for (String kw : keywords) {
            if (lowerText.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String formatItems(List<AuditItem> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_SHOWN_ITEMS, items.size()); i++) {
            AuditItem item = items.get(i);
            sb.append("\n   - ").append(item.path()).append(':').append(item.line()).append(" [").append(item.kind())
                .append("] ").append(item.message());
        }
        return sb.toString();
    }
}
