package com.pilot.core.quality;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pilot.core.quality.CodeQualityCheck.Finding;
import com.pilot.core.quality.CodeTokenizer.ParsedLine;
import com.pilot.core.util.FileNames;

/**
 * 多语言性能陷阱与边界鲁棒性扫描器。
 * 覆盖循环内重分配、循环内 I/O 或 N+1 查询、无界读取、未关闭流资源以及缺少守卫的危险取值。
 */
public final class PerformanceBoundaryScanner {
    public static final String KIND_LOOP_HEAVY_ALLOC = "loop-heavy-alloc";
    public static final String KIND_LOOP_IO_QUERY = "loop-io-or-query";
    public static final String KIND_UNBOUNDED_READ = "unbounded-read";
    public static final String KIND_UNCLOSED_RESOURCE = "unclosed-resource";
    public static final String KIND_UNSAFE_GET = "unsafe-get";

    private static final int GUARD_LOOKBACK = 8;
    private static final int PARSE_LOOKBACK = 6;
    private static final int SIZE_GUARD_LOOKBACK = 15;
    private static final int CLOSE_LOOKAHEAD = 12;
    private static final int TRY_HEADER_LOOKBACK = 3;
    private static final int TAB_SPACES = 4;

    private static final Set<String> SUPPORTED_EXTS =
        Set.of("java", "kt", "kts", "js", "jsx", "ts", "tsx", "mjs", "cjs", "py", "go", "rs");
    private static final Set<String> PERF_KINDS =
        Set.of(KIND_LOOP_HEAVY_ALLOC, KIND_LOOP_IO_QUERY, KIND_UNBOUNDED_READ);
    private static final Set<String> BOUNDARY_KINDS = Set.of(KIND_UNCLOSED_RESOURCE, KIND_UNSAFE_GET);

    private static final Pattern LOOP_HEADER =
        Pattern.compile("\\b(?:for|while)\\s*\\(|\\bdo\\s*\\{|\\bloop\\s*\\{|\\bfor\\s+[^{;]+\\{|\\.forEach\\s*\\(");
    private static final Pattern PY_LOOP_HEADER = Pattern.compile("^(?:async\\s+)?(?:for|while)\\b.*:\\s*$");
    private static final Pattern TRY_KEYWORD = Pattern.compile("\\btry\\b");
    private static final Pattern PY_TRY_HEADER = Pattern.compile("^try\\s*:\\s*$");

    private static final Pattern STRING_VAR_DECL = Pattern.compile("\\bString\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;)");
    private static final Pattern OPTIONAL_VAR_DECL =
        Pattern.compile("\\bOptional(?:\\s*<[^>]+>)?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;)");
    private static final Pattern STRING_PLUS_ASSIGN = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\+=\\s*(.+)");

    private static final Pattern HEAVY_ALLOC = Pattern
        .compile("(?:\\bPattern\\.compile|\\bnew\\s+(?:ObjectMapper|Gson|SimpleDateFormat|RegExp|XmlMapper|YamlMapper)"
            + "|\\bre\\.compile|\\bregexp\\.(?:Must)?Compile|\\bRegex::new)\\s*\\(");
    private static final Pattern DIRECT_IO_CALL =
        Pattern.compile("(?:\\.(?:executeQuery|executeUpdate|selectOne|selectList|queryForObject|queryForList)\\s*\\("
            + "|\\b(?:jdbcTemplate|sqlSession|entityManager)\\s*\\.\\s*(?:query|find|createQuery|persist|merge)\\w*\\s*\\("
            + "|\\b(?:httpClient|restTemplate|webClient|axios|requests)\\s*\\.\\s*"
            + "(?:send|get|post|put|delete|patch|exchange|execute|request)\\w*\\s*\\("
            + "|(?<![A-Za-z0-9_.])fetch\\s*\\("
            + "|\\bFiles\\s*\\.\\s*(?:readString|readAllBytes|readAllLines|writeString|write|lines)\\s*\\("
            + "|\\bos\\.ReadFile\\s*\\(|\\bfs::read_to_string\\s*\\()");
    private static final Pattern REPO_OR_MAPPER_CALL = Pattern.compile(
        "\\b(?:[A-Za-z_][A-Za-z0-9_]*(?:Repository|Repo|Dao)|repository|repo|dao)\\s*\\.\\s*[a-z][A-Za-z0-9_]*\\s*\\("
            + "|\\b(?:[A-Za-z_][A-Za-z0-9_]*Mapper|mapper)\\s*\\.\\s*"
            + "(?:select|find|get|query|load|fetch|list|count|exists|insert|update|delete|save)[A-Za-z0-9_]*\\s*\\("
            + "|\\.findBy[A-Z][A-Za-z0-9_]*\\s*\\(");

    private static final Pattern UNBOUNDED_FILE_READ =
        Pattern.compile("\\bFiles\\s*\\.\\s*(?:readAllBytes|readAllLines)\\s*\\(|\\.readAllBytes\\s*\\(\\s*\\)");
    private static final Pattern SELECT_STAR_SQL = Pattern.compile("(?i)\\bselect\\s+\\*\\s+from\\s+[a-z_][a-z0-9_.]*");
    private static final Pattern SQL_BOUND_CLAUSE =
        Pattern.compile("(?i)\\blimit\\b|\\bfetch\\s+first\\b|\\btop\\s+\\d");
    private static final Pattern JAVA_OPEN_RESOURCE = Pattern.compile(
        "(?:\\bnew\\s+(?:FileInputStream|FileOutputStream|FileReader|FileWriter|BufferedReader|BufferedWriter|RandomAccessFile)"
            + "|\\bFiles\\s*\\.\\s*(?:walk|list|lines|newInputStream|newOutputStream|newBufferedReader|newBufferedWriter))\\s*\\(");
    private static final Pattern CHAINED_OPTIONAL_GET = Pattern.compile(
        "\\.(?:findFirst|findAny|min|max|reduce|ofNullable|findById|findOne)\\s*\\([^;]*?\\)\\s*\\.\\s*get\\s*\\(\\s*\\)");
    private static final Pattern VAR_OPTIONAL_GET =
        Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*get\\s*\\(\\s*\\)");
    private static final Pattern LIST_GET_ZERO =
        Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*get\\s*\\(\\s*0\\s*\\)");
    private static final Pattern UNSAFE_NUMBER_PARSE =
        Pattern.compile("\\b(?:Integer\\.parseInt|Long\\.parseLong|Double\\.parseDouble)\\s*\\(");

    private PerformanceBoundaryScanner() {
    }

    public static boolean isPerformanceKind(String kind) {
        return PERF_KINDS.contains(kind);
    }

    public static boolean isBoundaryKind(String kind) {
        return BOUNDARY_KINDS.contains(kind);
    }

    public static List<Finding> scanFile(String path, String text) {
        if (path == null || text == null || text.isBlank() || !CodeQualityCheck.applies(path)) {
            return List.of();
        }
        String ext = FileNames.extension(path.replace('\\', '/'));
        return scan(ext, CodeTokenizer.tokenize(ext, text), null);
    }

    private static final class ScanState {
        final List<Boolean> loopBraces = new ArrayList<>();
        final List<Boolean> tryBraces = new ArrayList<>();
        final List<Integer> pyLoops = new ArrayList<>();
        final List<Integer> pyTries = new ArrayList<>();
        final Set<String> stringVars = new HashSet<>();
        final Set<String> optionalVars = new HashSet<>();
        boolean pendingLoop;
        boolean pendingTry;
        int parenDepth;
    }

    public static List<Finding> scan(String extension, List<ParsedLine> lines, boolean[] added) {
        if (extension == null || !SUPPORTED_EXTS.contains(extension) || lines == null || lines.isEmpty()) {
            return List.of();
        }
        boolean isPy = "py".equals(extension);
        boolean isJava = "java".equals(extension);
        boolean isJvm = isJava || "kt".equals(extension) || "kts".equals(extension);
        List<Finding> out = new ArrayList<>();
        ScanState st = new ScanState();

        for (int i = 0; i < lines.size(); i++) {
            ParsedLine line = lines.get(i);
            String code = line.codeOnly();
            if (code.isEmpty()) {
                continue;
            }
            int indent = isPy ? leadingSpaces(line.raw()) : 0;
            if (isPy) {
                popClosedIndents(st.pyLoops, indent);
                popClosedIndents(st.pyTries, indent);
            } else if (st.loopBraces.size() <= 1 && code.endsWith("{") && !st.pendingLoop && !st.pendingTry) {
                st.stringVars.clear();
                st.optionalVars.clear();
            }
            recordLocalTypes(isJava, isJvm, code, st);

            boolean loopHdr = isPy ? PY_LOOP_HEADER.matcher(code).find() : LOOP_HEADER.matcher(code).find();
            boolean inLoop = isPy ? !st.pyLoops.isEmpty() : st.loopBraces.contains(Boolean.TRUE)
                || (st.pendingLoop && st.parenDepth == 0 && !code.startsWith("{"));
            String loopTarget = inLoop ? code : inlineLoopBody(code, loopHdr);
            boolean inTry = isPy ? !st.pyTries.isEmpty() : st.tryBraces.contains(Boolean.TRUE) || st.pendingTry;

            if (added == null || (i < added.length && added[i])) {
                inspectLine(lines, i, line, code, loopTarget, isJava, isJvm, inTry, st, out);
            }
            advanceState(isPy, indent, code, loopHdr, st);
        }
        return List.copyOf(out);
    }

    private static void advanceState(boolean isPy, int indent, String code, boolean loopHdr, ScanState st) {
        if (isPy) {
            if (loopHdr) {
                st.pyLoops.add(indent);
            }
            if (PY_TRY_HEADER.matcher(code).find()) {
                st.pyTries.add(indent);
            }
            return;
        }
        st.pendingLoop |= loopHdr;
        st.pendingTry |= TRY_KEYWORD.matcher(code).find();
        for (int c = 0; c < code.length(); c++) {
            char ch = code.charAt(c);
            if (ch == '(') {
                st.parenDepth++;
            } else if (ch == ')' && st.parenDepth > 0) {
                st.parenDepth--;
            } else if (ch == '{') {
                st.loopBraces.add(st.pendingLoop);
                st.tryBraces.add(st.pendingTry);
                st.pendingLoop = false;
                st.pendingTry = false;
            } else if (ch == '}') {
                if (!st.loopBraces.isEmpty()) {
                    st.loopBraces.removeLast();
                }
                if (!st.tryBraces.isEmpty()) {
                    st.tryBraces.removeLast();
                }
            } else if (ch == ';' && st.parenDepth == 0) {
                st.pendingLoop = false;
                st.pendingTry = false;
            }
        }
    }

    private static void inspectLine(List<ParsedLine> lines, int idx, ParsedLine line, String code, String loopTarget,
        boolean isJava, boolean isJvm, boolean inTry, ScanState st, List<Finding> out) {
        if (!loopTarget.isEmpty()) {
            if (HEAVY_ALLOC.matcher(loopTarget).find()) {
                out.add(new Finding(line.lineNumber(), KIND_LOOP_HEAVY_ALLOC,
                    "循环内重复创建正则/序列化/日期格式化重对象；请提升为 static final 常量或在循环外复用"));
            } else if (isJava && isLoopStringConcat(loopTarget, st.stringVars)) {
                out.add(new Finding(line.lineNumber(), KIND_LOOP_HEAVY_ALLOC,
                    "Java 循环内使用 += 拼接字符串会产生 O(N^2) 临时对象分配；请改用 StringBuilder"));
            }
            if (DIRECT_IO_CALL.matcher(loopTarget).find() || REPO_OR_MAPPER_CALL.matcher(loopTarget).find()) {
                out.add(new Finding(line.lineNumber(), KIND_LOOP_IO_QUERY,
                    "循环内逐次发起查询、远程请求或文件 I/O（疑似 N+1 性能陷阱）；建议改为批量查询/预加载或聚合读写"));
            }
        }
        if (UNBOUNDED_FILE_READ.matcher(code).find()
            && !windowContainsAny(lines, idx - SIZE_GUARD_LOOKBACK, idx, "Files.size(", ".size(", ".length(",
                "maxBytes", "MAX_", "limit", "getResourceAsStream(", "ByteArrayInputStream")) {
            out.add(new Finding(line.lineNumber(), KIND_UNBOUNDED_READ,
                "未做大小上界校验直接全量读取字节或全部行，大输入下易触发 OOM；请先校验文件大小或使用有界流读取"));
        } else if (hasUnboundedSelectStar(lines, idx)) {
            out.add(new Finding(line.lineNumber(), KIND_UNBOUNDED_READ,
                "SQL 使用 SELECT * 且缺少 LIMIT 分页上界；请显式指定所需列并补充 LIMIT 保护"));
        }
        if (isJava && JAVA_OPEN_RESOURCE.matcher(code).find() && !code.startsWith("return ")
            && !windowContainsAny(lines, idx - TRY_HEADER_LOOKBACK, idx, "try (", "try(")
            && !windowContainsAny(lines, idx, idx + CLOSE_LOOKAHEAD, ".close()")) {
            out.add(new Finding(line.lineNumber(), KIND_UNCLOSED_RESOURCE,
                "打开 I/O 流或目录遍历 Stream 未置于 try-with-resources 中，异常路径会导致句柄泄漏"));
        }
        if (isJvm) {
            String unsafeMsg = detectUnsafeGetOrParse(lines, idx, code, isJava, inTry, st.optionalVars);
            if (unsafeMsg != null) {
                out.add(new Finding(line.lineNumber(), KIND_UNSAFE_GET, unsafeMsg));
            }
        }
    }

    private static String detectUnsafeGetOrParse(List<ParsedLine> lines, int idx, String code, boolean isJava,
        boolean inTry, Set<String> optionalVars) {
        if (CHAINED_OPTIONAL_GET.matcher(code).find()) {
            return "对 Optional 结果直接链式调用 .get()，空值时将抛 NoSuchElementException；请改用 orElse / orElseThrow 或判空";
        }
        Matcher optGet = VAR_OPTIONAL_GET.matcher(code);
        while (optGet.find()) {
            String name = optGet.group(1);
            if ((optionalVars.contains(name) || name.toLowerCase(Locale.ROOT).startsWith("opt"))
                && !windowContainsAny(lines, idx - GUARD_LOOKBACK, idx, ".isPresent(", ".isEmpty(", ".ifPresent(",
                    ".orElse")) {
                return "未先校验 isPresent/isEmpty 即调用 " + name + ".get()；请改用 orElse / orElseThrow 或显式守卫";
            }
        }
        Matcher zeroGet = LIST_GET_ZERO.matcher(code);
        while (zeroGet.find()) {
            if (!windowContainsAny(lines, idx - GUARD_LOOKBACK, idx, "isEmpty(", ".size(", ".length", "> 0", ">= 1",
                "!= 0", "== 1", "List.of(", "Arrays.asList(")) {
                return "未校验集合非空直接调用 " + zeroGet.group(1) + ".get(0)，空集合时将抛 IndexOutOfBoundsException";
            }
        }
        if (isJava && !inTry && UNSAFE_NUMBER_PARSE.matcher(code).find() && !windowContainsAny(lines,
            idx - PARSE_LOOKBACK, idx, ".matches(", "isDigit(", "NumberFormatException", "throws Exception")) {
            return "解析数值字符串未捕获 NumberFormatException 或做格式校验，非法输入将直接中断流程";
        }
        return null;
    }

    private static void recordLocalTypes(boolean isJava, boolean isJvm, String code, ScanState st) {
        if (isJava) {
            Matcher sm = STRING_VAR_DECL.matcher(code);
            while (sm.find()) {
                st.stringVars.add(sm.group(1));
            }
        }
        if (isJvm) {
            Matcher om = OPTIONAL_VAR_DECL.matcher(code);
            while (om.find()) {
                st.optionalVars.add(om.group(1));
            }
        }
    }

    private static boolean isLoopStringConcat(String code, Set<String> stringVars) {
        Matcher m = STRING_PLUS_ASSIGN.matcher(code);
        return m.find() && (stringVars.contains(m.group(1)) || m.group(2).contains("\"\"")
            || m.group(2).contains("String.valueOf(") || m.group(2).contains(".toString()"));
    }

    private static String inlineLoopBody(String code, boolean loopHdr) {
        if (!loopHdr) {
            return "";
        }
        int arrow = code.indexOf("->");
        int brace = code.indexOf('{');
        int start = arrow >= 0 && (brace < 0 || arrow < brace) ? arrow + 2 : brace >= 0 ? brace + 1 : -1;
        return start >= 0 && start < code.length() ? code.substring(start).strip() : "";
    }

    private static boolean hasUnboundedSelectStar(List<ParsedLine> lines, int idx) {
        return lines.get(idx).stringLiterals().stream().anyMatch(s -> SELECT_STAR_SQL.matcher(s).find())
            && !SQL_BOUND_CLAUSE.matcher(lines.get(idx).raw()).find()
            && (idx + 1 >= lines.size() || !SQL_BOUND_CLAUSE.matcher(lines.get(idx + 1).raw()).find());
    }

    private static boolean windowContainsAny(List<ParsedLine> lines, int from, int to, String... needles) {
        for (int j = Math.max(0, from); j <= Math.min(lines.size() - 1, to); j++) {
            String raw = lines.get(j).raw();
            for (String needle : needles) {
                if (raw.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void popClosedIndents(List<Integer> stack, int currentIndent) {
        while (!stack.isEmpty() && stack.getLast() >= currentIndent) {
            stack.removeLast();
        }
    }

    private static int leadingSpaces(String raw) {
        int n = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch != ' ' && ch != '\t') {
                break;
            }
            n += ch == '\t' ? TAB_SPACES : 1;
        }
        return n;
    }
}
