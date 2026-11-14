package com.pilot.core.tool.command;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条命令行解析成「命令段 + 连接符」，只认 {@code && || ; |} 四种连接符。
 *
 * <p>解析是 run_command 安全模型的第一道门：引号内的任何字符都是字面量；引号外的
 * 重定向、命令替换、后台符、换行一律拒绝（括号只是普通字符，不会形成子 shell）。解析结果不再交给 shell 做二次解释——
 * 单条命令直接以参数数组启动，多条命令由调用方把每个词重新加单引号后拼成脚本，
 * 所以变量、通配符、波浪号都不会被展开。
 */
final class CommandChain {
    static final int MAX_SEGMENTS = 8;

    /** @param mergeStderr 该段带 {@code 2>&1}；受管命令本来就合并输出，多段脚本里需要原样保留 */
    record Segment(List<String> tokens, boolean mergeStderr) {
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
        char quote = 0;
        int length = raw.length();
        for (int i = 0; i < length; i++) {
            char ch = raw.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
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
                    // 只放行 2>&1：受管命令本来就合并 stderr，其余重定向会绕开文件变更追踪
                    boolean merge =
                        hasToken && current.toString().equals("2") && raw.startsWith(">&1", i) && (i + 3 >= length
                            || Character.isWhitespace(raw.charAt(i + 3)) || "&|;".indexOf(raw.charAt(i + 3)) >= 0);
                    if (!merge) {
                        return Parsed.error("不支持输出重定向 >；需要写文件请用 create_file / edit_file / apply_patch，" + "只放行 2>&1");
                    }
                    current.setLength(0);
                    hasToken = false;
                    mergeStderr = true;
                    i += 2;
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
                    segments.add(new Segment(List.copyOf(tokens), mergeStderr));
                    operators.add(operator);
                    tokens = new ArrayList<>();
                    mergeStderr = false;
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
            segments.add(new Segment(List.copyOf(tokens), mergeStderr));
        }
        if (segments.size() > MAX_SEGMENTS) {
            return Parsed.error("单次最多 " + MAX_SEGMENTS + " 条命令串联，请拆开执行");
        }
        return new Parsed(List.copyOf(segments), List.copyOf(operators), null);
    }

    /** POSIX 单引号转义：词内的单引号写成 {@code '\''}。 */
    static String quote(String word) {
        return "'" + word.replace("'", "'\\''") + "'";
    }
}
