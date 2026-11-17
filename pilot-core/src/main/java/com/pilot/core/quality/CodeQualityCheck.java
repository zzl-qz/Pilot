package com.pilot.core.quality;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.quality.CodeAssetIndex.ConstantRef;
import com.pilot.core.quality.CodeTokenizer.ParsedLine;
import com.pilot.core.util.FileNames;

/**
 * 多语言增量代码质量门面。
 * 协同词法分析器、克隆检测器与语境亲和度引擎，检查魔法值、重复字符串、绕过封装、
 * 同构克隆、超长方法、深层嵌套、过长参数列表、瀑布分支以及静默吞异常。
 */
public final class CodeQualityCheck {
    // 阈值全部来自 config/quality/rules.json 生成的规则目录，这里只起命名作用，不再写死数字。
    public static final int LONG_METHOD_LINES = QualityCatalog.limit("long-method");
    public static final int DUPLICATE_BLOCK_LINES = QualityCatalog.limit("duplicate-block");
    private static final int DEEP_NESTING_LEVEL = QualityCatalog.limit("deep-nesting");
    private static final int MAX_METHOD_PARAMS = QualityCatalog.limit("too-many-parameters");
    private static final int ELSE_IF_CHAIN_LIMIT = QualityCatalog.limit("else-if-chain");
    private static final int REPEATED_STRING_THRESHOLD = QualityCatalog.limit("repeated-literal");
    private static final int MAX_FINDINGS = 8;

    private static final Set<String> CODE_EXTS = Set.of("java", "kt", "kts", "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "py", "go", "rs", "cs", "cpp", "cc", "c", "h", "swift");
    private static final Set<String> TEST_TOKENS = Set.of("test", "tests", "spec", "specs", "__tests__", "fixture",
        "fixtures", "target", "build", "dist", "out", "node_modules", ".pilot");
    private static final Set<String> IGNORED_NUMBERS =
        Set.of("0", "1", "2", "-1", "10", "100", "0.0", "0.5", "1.0", "-1.0");
    private static final Set<String> STATUS_WORDS = Set.of("pending", "success", "succeeded", "failed", "failure",
        "error", "active", "inactive", "enabled", "disabled", "approved", "rejected", "paid", "unpaid", "open",
        "closed", "created", "deleted", "running", "stopped", "cancelled", "canceled", "completed", "todo", "doing",
        "done", "timeout", "expired", "processing", "ready", "waiting");
    private static final Set<String> IGNORED_REPEATED_STRINGS = Set.of("type", "string", "object", "array", "number",
        "boolean", "integer", "description", "properties", "items", "required", "value", "result", "error", "ERROR:",
        "SKIPPED:", "message", "status", "utf-8", "UTF-8", "application/json", "text/plain", "localhost");

    private static final Set<String> NON_METHOD_NAMES = Set.of("if", "for", "while", "switch", "catch", "try",
        "synchronized", "return", "throw", "new", "super", "this", "when", "match");
    private static final Pattern METHOD_START = Pattern
        .compile("^\\s*(?:(?:public|protected|private|static|final|synchronized|abstract|override|export|async)\\s+)*"
            + "(?:(?:function|def|fn|func)\\s+|(?:[A-Za-z_][A-Za-z0-9_<>,.?\\[\\]]*\\s+)+)"
            + "([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^()]*)\\)\\s*(?:throws\\s+[^{;]+|->\\s*[^{;]+|:\\s*[^{;]+)?[\\{:]$");
    private static final Pattern JAVA_DEBUG_OUT =
        Pattern.compile("\\bSystem\\.(?:out|err)\\.print(?:ln|f)?\\s*\\(|\\.printStackTrace\\s*\\(\\s*\\)");
    private static final Pattern CATCH_HEADER =
        Pattern.compile("\\bcatch\\s*\\([^)]*?\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*\\{(.*)$");
    private static final Pattern PYTHON_BARE_PASS = Pattern.compile("^\\s*except\\b.*:\\s*$");

    private CodeQualityCheck() {
    }

    public record Finding(int line, String kind, String message) {
    }

    public record TextChange(String before, String after) {
    }

    public static boolean applies(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String norm = path.replace('\\', '/');
        String lower = norm.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".min.js") || lower.endsWith(".bundle.js") || lower.endsWith(".d.ts") || isTestPath(norm)) {
            return false;
        }
        return CODE_EXTS.contains(FileNames.extension(norm));
    }

    public static List<Finding> inspect(String path, String before, String after) {
        return inspectWithContext(CodeAssetIndex.Snapshot.EMPTY, Map.of(), path, before, after);
    }

    public static List<Finding> inspect(Path workspaceRoot, String path, String before, String after) {
        CodeAssetIndex.Snapshot assets =
            workspaceRoot == null ? CodeAssetIndex.Snapshot.EMPTY : CodeAssetIndex.scan(workspaceRoot);
        Map<String, List<ParsedLine>> peers =
            workspaceRoot == null ? Map.of() : CloneDetector.loadSiblings(workspaceRoot, path);
        return inspectWithContext(assets, peers, path, before, after);
    }

    private static List<Finding> inspectWithContext(CodeAssetIndex.Snapshot assets, Map<String, List<ParsedLine>> peers,
        String path, String before, String after) {
        try {
            if (!applies(path) || after == null || after.isBlank()) {
                return List.of();
            }
            String ext = FileNames.extension(path.replace('\\', '/'));
            List<ParsedLine> afterLines = CodeTokenizer.tokenize(ext, after);
            if (afterLines.isEmpty()) {
                return List.of();
            }
            boolean[] added = addedLines(afterLines, before == null ? List.of() : CodeTokenizer.tokenize(ext, before));
            Set<String> impWords = ConstantAffinityMatcher.extractImportedWords(after);
            Set<String> impPaths = ConstantAffinityMatcher.extractImportedPaths(after);

            List<Finding> out = new ArrayList<>();
            out.addAll(bypassAndRobustnessFindings(path, ext, afterLines, added, assets, 2));
            out.addAll(PerformanceBoundaryScanner.scan(ext, afterLines, added));
            out.addAll(literalFindings(path, afterLines, added, assets, impWords, impPaths, MAX_FINDINGS));
            out.addAll(repeatedStringFindings(path, afterLines, added, assets, 2));
            out.addAll(CloneDetector.detect(path, afterLines, added, peers));
            out.addAll(structureFindings(afterLines, added));
            return out.size() <= MAX_FINDINGS ? List.copyOf(out) : List.copyOf(out.subList(0, MAX_FINDINGS));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    static List<Finding> inspectFullFile(CodeAssetIndex.Snapshot assets, Map<String, List<ParsedLine>> peers,
        String path, String ext, List<ParsedLine> lines, String rawText, int categoryLimit) {
        if (lines == null || lines.isEmpty() || categoryLimit <= 0) {
            return List.of();
        }
        Set<String> impWords = ConstantAffinityMatcher.extractImportedWords(rawText);
        Set<String> impPaths = ConstantAffinityMatcher.extractImportedPaths(rawText);
        List<Finding> out = new ArrayList<>();
        out.addAll(CloneDetector.detect(path, lines, null, peers, categoryLimit));
        out.addAll(PerformanceBoundaryScanner.scan(ext, lines, null));
        out.addAll(bypassAndRobustnessFindings(path, ext, lines, null, assets, categoryLimit));
        out.addAll(literalFindings(path, lines, null, assets, impWords, impPaths, categoryLimit));
        out.addAll(repeatedStringFindings(path, lines, null, assets, categoryLimit));
        out.addAll(structureFindings(lines, null));
        return List.copyOf(out);
    }

    public static String summary(String path, String before, String after) {
        return formatSummary(path, inspect(path, before, after));
    }

    public static String summary(Path workspaceRoot, String path, String before, String after) {
        return formatSummary(path, inspect(workspaceRoot, path, before, after));
    }

    private static String formatSummary(String path, List<Finding> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("[代码质量提醒] ").append(display(path)).append(" 的本次新增代码：");
        for (Finding f : findings) {
            out.append("\n- 第 ").append(f.line()).append(" 行：").append(f.message());
        }
        out.append("\n优先复用同链路常量与公共方法；确属合理一次性值或强拆反伤可读性时保持原样并在最终回复说明原因，勿生造过度抽象。");
        return out.toString();
    }

    public static String completionIssue(Map<String, TextChange> changes) {
        return completionIssue(null, changes);
    }

    public static String completionIssue(Path workspaceRoot, Map<String, TextChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return "";
        }
        try {
            CodeAssetIndex.Snapshot assets =
                workspaceRoot == null ? CodeAssetIndex.Snapshot.EMPTY : CodeAssetIndex.scan(workspaceRoot);
            Map<String, List<ParsedLine>> batchParsed = new LinkedHashMap<>();
            changes.forEach((p, c) -> {
                if (applies(p) && c != null && c.after() != null) {
                    batchParsed.put(p.replace('\\', '/'),
                        CodeTokenizer.tokenize(FileNames.extension(p.replace('\\', '/')), c.after()));
                }
            });

            StringBuilder details = new StringBuilder();
            int files = 0;
            int count = 0;
            for (Map.Entry<String, TextChange> entry : changes.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String path = entry.getKey();
                Map<String, List<ParsedLine>> peers = new LinkedHashMap<>(batchParsed);
                peers.remove(path.replace('\\', '/'));
                if (workspaceRoot != null) {
                    CloneDetector.loadSiblings(workspaceRoot, path).forEach(peers::putIfAbsent);
                }
                List<Finding> list =
                    inspectWithContext(assets, peers, path, entry.getValue().before(), entry.getValue().after());
                if (list.isEmpty()) {
                    continue;
                }
                files++;
                for (Finding f : list) {
                    if (count < MAX_FINDINGS) {
                        count++;
                        details.append("\n- ").append(display(path)).append(':').append(f.line()).append(' ')
                            .append(f.message());
                    }
                }
            }
            return count == 0 ? "" : files + " 个代码文件的本次新增内容有 " + count + " 处待核对的魔法值、重复逻辑或结构隐患：" + details;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static int countAddedCodeLines(Map<String, TextChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Map.Entry<String, TextChange> e : changes.entrySet()) {
            if (applies(e.getKey()) && e.getValue() != null && e.getValue().after() != null) {
                String ext = FileNames.extension(e.getKey().replace('\\', '/'));
                boolean[] added = addedLines(CodeTokenizer.tokenize(ext, e.getValue().after()),
                    e.getValue().before() == null ? List.of() : CodeTokenizer.tokenize(ext, e.getValue().before()));
                total += countAdded(added, 0, added.length - 1);
            }
        }
        return total;
    }

    private static List<Finding> bypassAndRobustnessFindings(String path, String ext, List<ParsedLine> lines,
        boolean[] added, CodeAssetIndex.Snapshot assets, int limit) {
        List<Finding> out = new ArrayList<>();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size() && out.size() < limit; i++) {
            if (!included(added, i)) {
                continue;
            }
            ParsedLine line = lines.get(i);
            String bypass = assets.detectBypass(path, line.codeOnly(), line.raw());
            if (bypass != null) {
                out.add(new Finding(line.lineNumber(), "bypass-helper", bypass));
                continue;
            }
            if (("java".equals(ext) || "kt".equals(ext)) && JAVA_DEBUG_OUT.matcher(line.codeOnly()).find()
                && !lowerPath.contains("cli") && !lowerPath.contains("main")) {
                out.add(
                    new Finding(line.lineNumber(), "debug-output", "生产代码调用了控制台打印或 printStackTrace；请改用项目日志组件或向上传播异常"));
                continue;
            }
            if (isSilentSwallowedCatch(ext, lines, i)) {
                out.add(new Finding(line.lineNumber(), "empty-catch",
                    "捕获异常后静默吞掉且未命名 ignored 或说明降级原因；请妥善处理异常、记录原因或显式声明恢复值"));
            }
        }
        return out;
    }

    private static boolean isSilentSwallowedCatch(String ext, List<ParsedLine> lines, int idx) {
        ParsedLine cur = lines.get(idx);
        if ("py".equals(ext) && PYTHON_BARE_PASS.matcher(cur.codeOnly()).matches() && idx + 1 < lines.size()) {
            return "pass".equals(lines.get(idx + 1).codeOnly()) && !lines.get(idx + 1).raw().contains("#");
        }
        Matcher m = CATCH_HEADER.matcher(cur.codeOnly());
        if (!m.find()) {
            return false;
        }
        String varName = m.group(1);
        if (varName.startsWith("ignore") || "_".equals(varName) || cur.raw().contains("//")
            || cur.raw().contains("/*")) {
            return false;
        }
        String tail = m.group(2).strip();
        if ("}".equals(tail)) {
            return true;
        }
        if (!tail.isEmpty()) {
            return false;
        }
        for (int j = idx + 1; j < Math.min(lines.size(), idx + 4); j++) {
            String raw = lines.get(j).raw().strip();
            if (raw.startsWith("//") || raw.startsWith("/*") || raw.startsWith("*")) {
                return false;
            }
            if (!lines.get(j).codeOnly().isEmpty()) {
                return "}".equals(lines.get(j).codeOnly());
            }
        }
        return false;
    }

    private static List<Finding> literalFindings(String path, List<ParsedLine> lines, boolean[] added,
        CodeAssetIndex.Snapshot assets, Set<String> impWords, Set<String> impPaths, int limit) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < lines.size() && out.size() < limit; i++) {
            if (!included(added, i) || lines.get(i).declarationOrConstant()) {
                continue;
            }
            ParsedLine line = lines.get(i);
            String num = line.numberLiterals().stream()
                .filter(n -> !IGNORED_NUMBERS.contains(n) && !IGNORED_NUMBERS.contains(n.replace("-", ""))).findFirst()
                .orElse(null);
            if (num != null) {
                out.add(new Finding(line.lineNumber(), "magic-number",
                    "裸数字 " + num + "；" + assets.adviseConstant(num, true, path, line.raw(), impWords, impPaths)));
                continue;
            }
            if (!line.diagnosticOrLog()) {
                String status = line
                    .stringLiterals().stream().map(String::strip).filter(s -> s.length() >= 3 && s.length() <= 32
                        && s.matches("[A-Za-z][A-Za-z0-9_]*") && STATUS_WORDS.contains(s.toLowerCase(Locale.ROOT)))
                    .findFirst().orElse(null);
                if (status != null) {
                    out.add(new Finding(line.lineNumber(), "magic-status", "硬编码状态 \"" + status + "\"；"
                        + assets.adviseConstant(status, false, path, line.raw(), impWords, impPaths)));
                }
            }
        }
        return out;
    }

    private static List<Finding> repeatedStringFindings(String path, List<ParsedLine> lines, boolean[] added,
        CodeAssetIndex.Snapshot assets, int limit) {
        Map<String, List<Integer>> hits = new LinkedHashMap<>();
        Map<String, String> sampleLine = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            ParsedLine l = lines.get(i);
            if (!included(added, i) || l.declarationOrConstant() || l.diagnosticOrLog()) {
                continue;
            }
            for (String s : l.stringLiterals()) {
                String t = s.strip();
                if (t.length() >= 5 && t.length() <= 80 && !IGNORED_REPEATED_STRINGS.contains(t)
                    && t.chars().anyMatch(Character::isLetter)) {
                    hits.computeIfAbsent(t, ignored -> new ArrayList<>()).add(l.lineNumber());
                    sampleLine.putIfAbsent(t, l.raw());
                }
            }
        }
        List<Finding> out = new ArrayList<>();
        for (var e : hits.entrySet()) {
            if (e.getValue().size() >= REPEATED_STRING_THRESHOLD && out.size() < limit) {
                ConstantRef ref = assets.recommendConstant(e.getKey(), path, sampleLine.getOrDefault(e.getKey(), ""));
                String advice =
                    ref != null ? "同链路已有 " + ref.symbol() + "（" + ref.path() + "），核对一致后可引用" : "若属同一概念请在本模块提炼具名常量避免失步";
                String shown = e.getKey().length() <= 36 ? e.getKey() : e.getKey().substring(0, 36) + "…";
                String scopeWord = added == null ? "文件中" : "新增代码中";
                out.add(new Finding(e.getValue().getFirst(), "repeated-literal",
                    "字符串 \"" + shown + "\" 在" + scopeWord + "重复出现 " + e.getValue().size() + " 次；" + advice));
            }
        }
        return out;
    }

    private static List<Finding> structureFindings(List<ParsedLine> lines, boolean[] added) {
        List<Finding> out = new ArrayList<>();
        boolean reportedNesting = false;
        int braceDepth = 0;
        int elseIfChain = 0;
        int chainStartLine = 0;

        for (int i = 0; i < lines.size(); i++) {
            ParsedLine line = lines.get(i);
            if (braceDepth <= 1 && elseIfChain != 0) {
                elseIfChain = 0;
            }
            if (included(added, i) && !reportedNesting && line.controlNestingDepth() >= DEEP_NESTING_LEVEL) {
                out.add(new Finding(line.lineNumber(), "deep-nesting",
                    "控制流嵌套已达 " + line.controlNestingDepth() + " 层；建议用卫语句（提前 return / continue）拍平分支"));
                reportedNesting = true;
            }
            boolean isRecordHeader =
                line.raw().stripLeading().startsWith("record ") || line.codeOnly().contains(" record ");
            Matcher m = isRecordHeader ? null : METHOD_START.matcher(line.codeOnly());
            if (m != null && m.find() && !NON_METHOD_NAMES.contains(m.group(1))) {
                elseIfChain = 0;
                if (added == null) {
                    reportedNesting = false;
                }
                if (included(added, i)) {
                    int params = countParameters(m.group(2));
                    if (params >= MAX_METHOD_PARAMS) {
                        out.add(new Finding(line.lineNumber(), "long-params",
                            "方法参数列表达 " + params + " 个（数据泥团）；建议聚合为参数 Record/对象以保持签名精悍"));
                    }
                }
                int end = methodEnd(lines, i);
                int linesInMethod = added == null ? countNonEmpty(lines, i, end) : countAdded(added, i, end);
                if (linesInMethod >= LONG_METHOD_LINES) {
                    String msg = added == null ? "单个方法有效代码达 " + linesInMethod + " 行；请把职责独立的子步骤拆为小而精的私有方法"
                        : "本次在单个方法内新增约 " + linesInMethod + " 行代码；请把职责独立的子步骤拆为小而精的私有方法";
                    out.add(new Finding(line.lineNumber(), "long-method", msg));
                }
            }
            if (line.codeOnly().contains("else if") || line.codeOnly().startsWith("elif ")) {
                if (elseIfChain == 0) {
                    chainStartLine = line.lineNumber();
                }
                if (included(added, i)) {
                    elseIfChain++;
                }
            } else if (line.codeOnly().startsWith("if ") || line.codeOnly().startsWith("if(")) {
                if (elseIfChain != 0) {
                    elseIfChain = 0;
                }
            }
            if (elseIfChain >= ELSE_IF_CHAIN_LIMIT) {
                out.add(new Finding(chainStartLine, "sprawling-branch",
                    "同一条件链连续 else-if 达 " + elseIfChain + " 处；若属同类规则分派，优先考虑 Map/Enum 表驱动"));
                elseIfChain = -100;
            }
            braceDepth = Math.max(0, braceDepth + line.braceDelta());
        }
        return out;
    }

    private static int countParameters(String paramsText) {
        if (paramsText == null || paramsText.isBlank()) {
            return 0;
        }
        int depth = 0;
        int commas = 0;
        for (int i = 0; i < paramsText.length(); i++) {
            char c = paramsText.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                commas++;
            }
        }
        return commas + 1;
    }

    private static int methodEnd(List<ParsedLine> lines, int start) {
        int depth = 0;
        boolean seen = false;
        int end = Math.min(lines.size() - 1, start + LONG_METHOD_LINES * 3);
        for (int i = start; i <= end; i++) {
            if (lines.get(i).codeOnly().indexOf('{') >= 0) {
                seen = true;
            }
            depth += lines.get(i).braceDelta();
            if (seen && depth <= 0) {
                return i;
            }
        }
        return end;
    }

    private static boolean[] addedLines(List<ParsedLine> after, List<ParsedLine> before) {
        Map<String, Integer> rem = new HashMap<>();
        for (ParsedLine l : before) {
            if (!l.isEmptyCode()) {
                rem.merge(l.exactNormalized(), 1, Integer::sum);
            }
        }
        boolean[] added = new boolean[after.size()];
        for (int i = 0; i < after.size(); i++) {
            ParsedLine l = after.get(i);
            if (!l.isEmptyCode()) {
                int avail = rem.getOrDefault(l.exactNormalized(), 0);
                if (avail > 0) {
                    rem.put(l.exactNormalized(), avail - 1);
                } else {
                    added[i] = true;
                }
            }
        }
        return added;
    }

    private static boolean included(boolean[] added, int idx) {
        return added == null || (idx < added.length && added[idx]);
    }

    private static int countNonEmpty(List<ParsedLine> lines, int start, int end) {
        int n = 0;
        for (int i = Math.max(0, start); i <= end && i < lines.size(); i++) {
            n += lines.get(i).isEmptyCode() ? 0 : 1;
        }
        return n;
    }

    private static int countAdded(boolean[] added, int start, int end) {
        int n = 0;
        for (int i = Math.max(0, start); i <= end && i < added.length; i++) {
            n += added[i] ? 1 : 0;
        }
        return n;
    }

    static boolean isTestPath(String path) {
        String[] parts = path.replace('\\', '/').split("/");
        for (int i = 0; i < parts.length; i++) {
            if (TEST_TOKENS.contains(parts[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (i == parts.length - 1 && isTestFile(parts[i])) {
                return true;
            }
        }
        return false;
    }

    static boolean isTestFile(String filename) {
        int dot = filename.lastIndexOf('.');
        String stem = dot < 0 ? filename : filename.substring(0, dot);
        String lower = stem.toLowerCase(Locale.ROOT);
        return stem.endsWith("Test") || stem.endsWith("Tests") || stem.endsWith("IT") || stem.endsWith("Spec")
            || stem.endsWith("Specs") || lower.endsWith(".test") || lower.endsWith(".spec") || lower.endsWith("_test")
            || lower.startsWith("test_");
    }

    private static String display(String path) {
        String norm = path.replace('\\', '/');
        return norm.length() <= 160 ? norm : "…" + norm.substring(norm.length() - 159);
    }
}
