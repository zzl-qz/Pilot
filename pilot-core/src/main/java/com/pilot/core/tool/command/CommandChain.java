package com.pilot.core.tool.command;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条命令行解析成「命令段 + 连接符」，只认 {@code && || ; |} 四种连接符。
 *
 * <p>解析是 run_command 安全模型的第一道门：单引号内的任何字符都是字面量；双引号内除
 * {@code \"} 与 {@code \\} 转义外均为字面量（保留正则 {@code \|} 与 Windows 路径反斜杠）；引号外的
 * 命令替换、后台符、换行一律拒绝（括号只是普通字符，不会形成子 shell）。输出重定向只记下目标，
 * 由调用方确认落在工作区内。解析结果不再交给 shell 做二次解释——
 * 单条命令直接以参数数组启动，多条命令由调用方把每个词重新加单引号后拼成脚本，
 * 所以变量、通配符、波浪号都不会被展开。
 */
final class CommandChain {
    static final int MAX_SEGMENTS = 8;

    /**
     * @param mergeStderr 该段带 {@code 2>&1}；受管命令本来就合并输出，多段脚本里需要原样保留
     * @param redirectTo 引号外的 {@code >} 或 {@code >>} 目标，尚未校验是否在工作区内
     * @param append {@code true} 表示 {@code >>}
     */
    record Segment(List<String> tokens, boolean mergeStderr, String redirectTo, boolean append) {
        Segment(List<String> tokens, boolean mergeStderr) {
            this(tokens, mergeStderr, null, false);
        }
    }

    record Parsed(List<Segment> segments, List<String> operators, String error) {
        static Parsed error(String message) {
            return new Parsed(List.of(), List.of(), message);
        }

        boolean chained() {
            return segments.size() > 1;
        }
    }

    private CommandChain() {
    }

    static Parsed parse(String raw) {
        List<Segment> segments = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean hasToken = false;
        boolean mergeStderr = false;
        String redirectTo = null;
        boolean append = false;
        char quote = 0;
        int length = raw.length();
        for (int i = 0; i < length; i++) {
            char ch = raw.charAt(i);
            if (quote != 0) {
                if (isDoubleQuoteEscape(quote, raw, i)) {
                    current.append(raw.charAt(++i));
                } else if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"', '\'' -> {
                    quote = ch;
                    hasToken = true;
                }
                case ' ', '\t' -> {
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                }
                case '\n', '\r' -> {
                    return Parsed.error("命令不能包含换行；多条命令用 && 连接，或分次调用");
                }
                case '`' -> {
                    return Parsed.error("不支持反引号命令替换");
                }
                case '^' -> {
                    return Parsed.error("命令包含不允许的字符 ^；需要时请加引号");
                }
                case '$' -> {
                    if (i + 1 < length && raw.charAt(i + 1) == '(') {
                        return Parsed.error("不支持 $( ) 命令替换");
                    }
                    current.append(ch);
                    hasToken = true;
                }
                case '<' -> {
                    return Parsed.error("不支持输入重定向 <；需要读文件请把路径作为参数");
                }
                case '>' -> {
                    // 2>&1 是合并输出，不是把内容写进文件。
                    boolean merge =
                        hasToken && current.toString().equals("2") && raw.startsWith(">&1", i) && (i + 3 >= length
                            || Character.isWhitespace(raw.charAt(i + 3)) || "&|;".indexOf(raw.charAt(i + 3)) >= 0);
                    if (merge) {
                        current.setLength(0);
                        hasToken = false;
                        mergeStderr = true;
                        i += 2;
                        break;
                    }
                    if (hasToken && current.toString().equals("2")) {
                        return Parsed.error("不支持把标准错误重定向到文件；只放行 2>&1");
                    }
                    if (redirectTo != null) {
                        return Parsed.error("一条命令只支持一个输出重定向");
                    }
                    boolean appending = i + 1 < length && raw.charAt(i + 1) == '>';
                    int cursor = i + (appending ? 2 : 1);
                    while (cursor < length && (raw.charAt(cursor) == ' ' || raw.charAt(cursor) == '\t')) {
                        cursor++;
                    }
                    if (cursor >= length) {
                        return Parsed.error("输出重定向缺少目标文件");
                    }
                    String target;
                    if (raw.charAt(cursor) == '"' || raw.charAt(cursor) == '\'') {
                        char mark = raw.charAt(cursor);
                        StringBuilder targetBuf = new StringBuilder();
                        int end = -1;
                        for (int p = cursor + 1; p < length; p++) {
                            char tc = raw.charAt(p);
                            if (isDoubleQuoteEscape(mark, raw, p)) {
                                targetBuf.append(raw.charAt(++p));
                            } else if (tc == mark) {
                                end = p;
                                break;
                            } else {
                                targetBuf.append(tc);
                            }
                        }
                        if (end < 0) {
                            return Parsed.error("引号没有闭合");
                        }
                        target = targetBuf.toString();
                        cursor = end + 1;
                    } else {
                        int end = cursor;
                        while (end < length && !Character.isWhitespace(raw.charAt(end))
                            && "&|;<>".indexOf(raw.charAt(end)) < 0) {
                            end++;
                        }
                        target = raw.substring(cursor, end);
                        cursor = end;
                    }
                    if (target.isBlank()) {
                        return Parsed.error("输出重定向缺少目标文件");
                    }
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                    redirectTo = target;
                    append = appending;
                    i = cursor - 1;
                }
                case '&', '|', ';' -> {
                    String operator;
                    if (ch == ';') {
                        operator = ";";
                    } else if (i + 1 < length && raw.charAt(i + 1) == ch) {
                        operator = String.valueOf(ch) + ch;
                        i++;
                    } else if (ch == '|') {
                        operator = "|";
                    } else {
                        return Parsed.error("不支持单个 &（后台运行）；后台运行请用 background=true");
                    }
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                    if (tokens.isEmpty()) {
                        return Parsed.error("连接符 " + operator + " 前缺少命令");
                    }
                    segments.add(new Segment(List.copyOf(tokens), mergeStderr, redirectTo, append));
                    operators.add(operator);
                    tokens = new ArrayList<>();
                    mergeStderr = false;
                    redirectTo = null;
                    append = false;
                }
                default -> {
                    current.append(ch);
                    hasToken = true;
                }
            }
        }
        if (quote != 0) {
            return Parsed.error("引号没有闭合");
        }
        if (hasToken) {
            tokens.add(current.toString());
        }
        if (tokens.isEmpty()) {
            // 末尾的 ; 允许（"cmd ;"），其余连接符后必须还有命令
            if (segments.isEmpty()) {
                return Parsed.error("命令为空");
            }
            if (!operators.getLast().equals(";")) {
                return Parsed.error("连接符 " + operators.getLast() + " 后缺少命令");
            }
            operators.removeLast();
        } else {
            segments.add(new Segment(List.copyOf(tokens), mergeStderr, redirectTo, append));
        }
        if (segments.size() > MAX_SEGMENTS) {
            return Parsed.error("单次最多 " + MAX_SEGMENTS + " 条命令串联，请拆开执行");
        }
        return new Parsed(List.copyOf(segments), List.copyOf(operators), null);
    }

    /** 双引号内仅将 {@code \"} 与 {@code \\} 视为转义，其余反斜杠按字面保留以兼容正则与 Windows 路径。 */
    static boolean isDoubleQuoteEscape(char quote, String raw, int index) {
        if (quote != '"' || raw.charAt(index) != '\\' || index + 1 >= raw.length()) {
            return false;
        }
        char next = raw.charAt(index + 1);
        return next == '"' || next == '\\';
    }

    /** POSIX 单引号转义：词内的单引号写成 {@code '\''}。 */
    static String quote(String word) {
        return "'" + word.replace("'", "'\\''") + "'";
    }
}
