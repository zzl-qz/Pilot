package com.pilot.core.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量多语言词法切分器。
 * 跨行追踪块注释、多行字符串、枚举体与常量块；超长压缩行自动截断防正则回溯（ReDoS）。
 */
final class CodeTokenizer {
    /** 单行扫描上限：防止 minified JS 或超长生成行触发灾难性回溯。 */
    static final int MAX_LINE_CHARS = 2_000;
    private static final int MAX_FILE_LINES = 15_000;

    private static final Set<String> CONTROL_KEYWORDS =
        Set.of("if", "for", "while", "switch", "try", "match", "select");
    private static final Set<String> SKELETON_KEYWORDS =
        Set.of("if", "else", "for", "while", "do", "switch", "case", "default", "try", "catch", "finally", "return",
            "throw", "new", "break", "continue", "await", "async", "yield", "match", "when", "defer", "go", "select",
            "in", "instanceof", "typeof", "is", "as", "null", "true", "false", "None", "True", "False", "nil");
    private static final Set<String> DECLARATION_STARTERS =
        Set.of("import", "package", "from", "use", "mod", "typedef", "type", "interface", "protocol", "namespace");

    private static final Pattern CONSTANT_DECL =
        Pattern.compile("^\\s*(?:(?:public|protected|private|internal|export|pub)\\s+)*"
            + "(?:static\\s+final\\b|final\\s+static\\b|const\\b|readonly\\b|val\\s+[A-Z][A-Z0-9_]{1,}\\s*=|"
            + "(?:int|long|double|float|String|Duration|Path)\\s+[A-Z][A-Z0-9_]{1,}\\s*=|"
            + "[A-Z][A-Z0-9_]{2,}\\s*(?::[^=]+)?=)");
    private static final Pattern CONSTANT_ENTRY =
        Pattern.compile("^\\s*[\"']?[A-Z][A-Z0-9_]{1,}[\"']?\\s*:\\s*[^;{}]+[,;]?\\s*$");
    private static final Pattern ENUM_ENTRY =
        Pattern.compile("^\\s*[A-Z][A-Z0-9_]{1,}\\s*(?:\\([^;{}]*\\))?\\s*[,;]\\s*$");
    private static final Pattern DIAGNOSTIC_LINE =
        Pattern.compile("^\\s*(?:throw\\s+new\\b|raise\\b|panic!\\s*\\(|assert\\w*\\s*\\(|"
            + "(?:this\\.)?(?:log|logger|LOGGER|LOG)\\s*\\.\\s*(?:trace|debug|info|warn|warning|error|fatal)\\s*\\(|"
            + "console\\s*\\.\\s*(?:warn|error|debug|info)\\s*\\()");
    private static final Pattern COMPARE_SINGLE_DIGIT =
        Pattern.compile("(?:[=!]==?\\s*(-?[3-9])\\b|\\b(-?[3-9])\\s*[=!]==?|\\bcase\\s+(-?[3-9])\\s*:)");
    private static final Pattern NUMBER_LITERAL = Pattern
        .compile("(?<![A-Za-z0-9_.])(-?(?:\\d[\\d_]*\\.\\d[\\d_]*|\\d[\\d_]{1,}))(?:[LFDlfd])?(?![A-Za-z0-9_.])");
    private static final Pattern TOKEN = Pattern.compile(
        "==|!=|<=|>=|&&|\\|\\||->|=>|::|[A-Za-z_$][A-Za-z0-9_$]*|\\d[\\d_]*(?:\\.\\d+)?|[{}()\\[\\].,;:+\\-*/%&|^!~<>?=]");

    private CodeTokenizer() {
    }

    record ParsedLine(int lineNumber, String raw, String codeOnly, String exactNormalized, String structuralSkeleton,
        int skeletonTokens, boolean hasLogicOrCall, boolean declarationOrConstant, boolean diagnosticOrLog,
        List<String> stringLiterals, List<String> numberLiterals, int braceDelta, int controlNestingDepth) {

        boolean isEmptyCode() {
            return exactNormalized.isEmpty();
        }

        boolean meaningfulForClone() {
            return !exactNormalized.isEmpty() && !declarationOrConstant && !exactNormalized.equals("{")
                && !exactNormalized.equals("}") && !exactNormalized.equals("};") && !exactNormalized.equals("});");
        }
    }

    static List<ParsedLine> tokenize(String extension, String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        boolean hashComments = "py".equals(extension) || "rb".equals(extension) || "sh".equals(extension);
        List<String> rawLines = splitLines(text);
        int limit = Math.min(rawLines.size(), MAX_FILE_LINES);
        List<ParsedLine> parsed = new ArrayList<>(limit);
        LexState state = new LexState();
        List<Boolean> controlStack = new ArrayList<>();
        boolean pendingControl = false;

        for (int idx = 0; idx < limit; idx++) {
            String raw = rawLines.get(idx);
            if (raw.length() > MAX_LINE_CHARS) {
                // 超长压缩行跳过深度正则匹配，仅做安全占位，防止卡死
                parsed.add(new ParsedLine(idx + 1, raw.substring(0, MAX_LINE_CHARS), "", "", "", 0, false, true, false,
                    List.of(), List.of(), 0, countTrue(controlStack)));
                continue;
            }
            LineScan scan = scanLine(raw, hashComments, state);
            String codeOnly = scan.codeOnly().strip();
            String exact = scan.withLiterals().strip().replaceAll("\\s+", " ");
            if (codeOnly.matches(".*\\benum\\s+[A-Za-z_][A-Za-z0-9_]*\\b.*")) {
                state.inEnumBody = true;
            }
            if (CONSTANT_DECL.matcher(raw).find() && (codeOnly.endsWith("{") || codeOnly.endsWith("["))) {
                state.inConstBlock = true;
            }
            boolean declOrConst = isDeclarationOrConstant(raw, codeOnly, state);
            boolean diagnostic = DIAGNOSTIC_LINE.matcher(raw).find();
            Skeleton sk = buildSkeleton(codeOnly);
            if (sk.hasControl) {
                pendingControl = true;
            }

            int depthBefore = countTrue(controlStack);
            int braceDelta = 0;
            int parenDepth = 0;
            for (int c = 0; c < codeOnly.length(); c++) {
                char ch = codeOnly.charAt(c);
                if (ch == '(') {
                    parenDepth++;
                } else if (ch == ')' && parenDepth > 0) {
                    parenDepth--;
                } else if (ch == '{') {
                    braceDelta++;
                    controlStack.add(pendingControl);
                    pendingControl = false;
                } else if (ch == '}') {
                    braceDelta--;
                    if (!controlStack.isEmpty()) {
                        controlStack.removeLast();
                    }
                    if (controlStack.isEmpty()) {
                        state.inEnumBody = false;
                        state.inConstBlock = false;
                    }
                } else if (ch == ']' && controlStack.isEmpty()) {
                    state.inConstBlock = false;
                } else if (ch == ';' && parenDepth == 0) {
                    pendingControl = false;
                }
            }
            int controlDepth = Math.max(depthBefore, countTrue(controlStack));
            List<String> numbers = declOrConst ? List.of() : extractNumbers(codeOnly);
            parsed.add(new ParsedLine(idx + 1, raw, codeOnly, exact, sk.text, sk.tokens, sk.hasLogic, declOrConst,
                diagnostic, List.copyOf(scan.strings()), numbers, braceDelta, controlDepth));
        }
        return List.copyOf(parsed);
    }

    private static final class LexState {
        boolean inBlockComment;
        String multilineQuote;
        boolean inEnumBody;
        boolean inConstBlock;
    }

    private record LineScan(String codeOnly, String withLiterals, List<String> strings) {
    }

    private static LineScan scanLine(String line, boolean hashComments, LexState state) {
        StringBuilder codeOnly = new StringBuilder(line.length());
        StringBuilder withLiterals = new StringBuilder(line.length());
        List<String> strings = new ArrayList<>();
        int i = 0;
        int len = line.length();
        while (i < len) {
            if (state.inBlockComment) {
                int close = line.indexOf("*/", i);
                if (close < 0) {
                    return new LineScan(codeOnly.toString(), withLiterals.toString(), strings);
                }
                state.inBlockComment = false;
                i = close + 2;
                codeOnly.append(' ');
                withLiterals.append(' ');
                continue;
            }
            if (state.multilineQuote != null) {
                int close = line.indexOf(state.multilineQuote, i);
                if (close < 0) {
                    return new LineScan(codeOnly.toString(), withLiterals.toString(), strings);
                }
                strings.add(line.substring(i, close));
                state.multilineQuote = null;
                i = close + 3;
                codeOnly.append("\"\"");
                withLiterals.append("\"$ML\"");
                continue;
            }
            if ((i + 1 < len && line.charAt(i) == '/' && line.charAt(i + 1) == '/')
                || (hashComments && line.charAt(i) == '#')) {
                break;
            }
            if (i + 1 < len && line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
                state.inBlockComment = true;
                i += 2;
                continue;
            }
            if (i + 2 < len && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i))) {
                String quote = line.substring(i, i + 3);
                int close = line.indexOf(quote, i + 3);
                if (close >= 0) {
                    String content = line.substring(i + 3, close);
                    strings.add(content);
                    codeOnly.append("\"\"");
                    withLiterals.append('"').append(content).append('"');
                    i = close + 3;
                } else {
                    state.multilineQuote = quote;
                    codeOnly.append("\"\"");
                    withLiterals.append("\"$ML\"");
                    return new LineScan(codeOnly.toString(), withLiterals.toString(), strings);
                }
                continue;
            }
            char ch = line.charAt(i);
            if (ch == '"' || ch == '\'' || ch == '`') {
                int end = consumeString(line, i, ch);
                String literal = line.substring(i + 1, Math.min(end, len)).replace("\\\"", "\"").replace("\\'", "'");
                if (ch != '\'' || literal.length() > 1) {
                    strings.add(literal);
                }
                codeOnly.append(ch == '\'' ? "''" : "\"\"");
                withLiterals.append(line, i, Math.min(end + 1, len));
                i = end + 1;
                continue;
            }
            codeOnly.append(ch);
            withLiterals.append(ch);
            i++;
        }
        return new LineScan(codeOnly.toString(), withLiterals.toString(), strings);
    }

    private static int consumeString(String line, int start, char quote) {
        for (int i = start + 1; i < line.length(); i++) {
            if (line.charAt(i) == '\\') {
                i++;
            } else if (line.charAt(i) == quote) {
                return i;
            }
        }
        return line.length() - 1;
    }

    private static boolean isDeclarationOrConstant(String raw, String codeOnly, LexState state) {
        if (codeOnly.isEmpty()) {
            return true;
        }
        String trimmed = raw.stripLeading();
        if (trimmed.startsWith("@") || trimmed.startsWith("#[")) {
            return true;
        }
        int space = codeOnly.indexOf(' ');
        String first = space < 0 ? codeOnly : codeOnly.substring(0, space);
        if (DECLARATION_STARTERS.contains(first) || codeOnly.contains("serialVersionUID")) {
            return true;
        }
        if (CONSTANT_DECL.matcher(raw).find() || CONSTANT_ENTRY.matcher(trimmed).matches()
            || codeOnly.endsWith("as const;") || codeOnly.endsWith("as const") || state.inConstBlock) {
            return true;
        }
        return state.inEnumBody && ENUM_ENTRY.matcher(trimmed).matches();
    }

    private record Skeleton(String text, int tokens, boolean hasLogic, boolean hasControl) {
    }

    private static Skeleton buildSkeleton(String codeOnly) {
        if (codeOnly.isEmpty()) {
            return new Skeleton("", 0, false, false);
        }
        Matcher m = TOKEN.matcher(codeOnly.replace("\"\"", " $S ").replace("''", " $C "));
        StringBuilder out = new StringBuilder(codeOnly.length());
        int count = 0;
        boolean logic = false;
        boolean control = false;
        String prev = "";
        while (m.find()) {
            String tok = m.group();
            count++;
            if ("$S".equals(tok) || "$C".equals(tok)) {
                out.append(tok);
            } else if (Character.isDigit(tok.charAt(0))) {
                out.append("$N");
            } else if (Character.isLetter(tok.charAt(0)) || tok.charAt(0) == '_' || tok.charAt(0) == '$') {
                if (CONTROL_KEYWORDS.contains(tok)) {
                    control = true;
                    logic = true;
                }
                if (SKELETON_KEYWORDS.contains(tok)) {
                    out.append(tok);
                    logic |= "return".equals(tok) || "throw".equals(tok) || "await".equals(tok);
                } else {
                    out.append("$I");
                }
            } else {
                logic |= ("(".equals(tok) && "$I".equals(prev)) || "==!=>=<=&&||+-?".contains(tok);
                out.append(tok);
            }
            prev = out.length() >= 2 && out.substring(out.length() - 2).equals("$I") ? "$I" : tok;
        }
        return new Skeleton(out.toString(), count, logic, control);
    }

    private static List<String> extractNumbers(String codeOnly) {
        List<String> found = new ArrayList<>();
        String sanitized = codeOnly.replaceAll("0[xX][0-9a-fA-F_]+", "0").replaceAll("0[bB][01_]+", "0");
        Matcher multi = NUMBER_LITERAL.matcher(sanitized);
        while (multi.find()) {
            String normalized = multi.group(1).replace("_", "");
            if (!found.contains(normalized)) {
                found.add(normalized);
            }
        }
        Matcher single = COMPARE_SINGLE_DIGIT.matcher(sanitized);
        while (single.find()) {
            for (int g = 1; g <= 3; g++) {
                if (single.group(g) != null && !found.contains(single.group(g))) {
                    found.add(single.group(g));
                }
            }
        }
        return found.isEmpty() ? List.of() : List.copyOf(found);
    }

    private static int countTrue(List<Boolean> flags) {
        int total = 0;
        for (Boolean flag : flags) {
            if (Boolean.TRUE.equals(flag)) {
                total++;
            }
        }
        return total;
    }

    static List<String> splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> result = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!result.isEmpty() && result.getLast().isEmpty()) {
            result.removeLast();
        }
        return result;
    }
}
