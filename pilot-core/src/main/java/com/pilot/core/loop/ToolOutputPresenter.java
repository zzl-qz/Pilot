package com.pilot.core.loop;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 长命令输出的首次定型器：在 {@code ToolCallJournal} 归档校验成功后，将超过 {@value #COMPACT_TRIGGER_CHARS} 字符的
 * 命令/日志输出在首次写入对话历史前定型为严格 {@code <= 6,500} 字符的可恢复视图（低于
 * {@code ContextCompactor.TRUNCATE_TRIGGER_CHARS = 8,192}），避免后续轮次回头截断改写历史破坏 KV 缓存。
 *
 * <p>统一将 {@code \r\n} / {@code \r} 规范化为 {@code \n}，确保 Windows / macOS / Linux 输出结构一致。
 */
final class ToolOutputPresenter {
    static final int COMPACT_TRIGGER_CHARS = 6_000;
    static final int MAX_PRESENTED_CHARS = 6_500;
    private static final int HEAD_CHARS = 1_800;
    private static final int TAIL_CHARS = 1_800;
    private static final int ERROR_SUMMARY_CHARS = 1_500;
    private static final int MAX_HEADER_CHARS = 700;

    private ToolOutputPresenter() {
    }

    static String compactCommandOutput(String raw, String archiveRef) {
        if (raw == null || raw.length() <= COMPACT_TRIGGER_CHARS) {
            return raw == null ? "" : raw + archiveRef;
        }
        String normalized = normalizeLineEndings(raw);
        String header = "";
        String body = normalized;
        int outputMarker = normalized.indexOf("\n输出:\n");
        if (outputMarker >= 0) {
            header = clip(normalized.substring(0, outputMarker).stripTrailing(), MAX_HEADER_CHARS);
            body = normalized.substring(outputMarker + "\n输出:\n".length());
        }
        boolean failed = looksFailed(header, body);
        String highlights = extractHighlights(body, failed, ERROR_SUMMARY_CHARS);
        int omitted = Math.max(0, body.length() - HEAD_CHARS - TAIL_CHARS);
        String head = body.length() <= HEAD_CHARS ? body : body.substring(0, HEAD_CHARS).stripTrailing();
        String tail = body.length() <= HEAD_CHARS ? ""
            : body.substring(Math.max(HEAD_CHARS, body.length() - TAIL_CHARS)).stripLeading();

        StringBuilder out = new StringBuilder();
        if (!header.isEmpty()) {
            out.append(header).append("\n输出（已按首尾+摘要定型，共 ").append(body.length()).append(" 字符）:\n");
        }
        if (!highlights.isEmpty()) {
            out.append("[关键摘要/错误行]\n").append(highlights).append("\n\n");
        }
        out.append("[输出开头]\n").append(head);
        if (omitted > 0) {
            out.append("\n\n…（中间已省略约 ").append(omitted).append(" 字符，完整原文见下方存档引用）…\n\n");
        } else if (!tail.isEmpty()) {
            out.append('\n');
        }
        if (!tail.isEmpty()) {
            out.append("[输出结尾]\n").append(tail);
        }
        String suffix = archiveRef == null ? "" : archiveRef;
        int bodyBudget = Math.max(200, MAX_PRESENTED_CHARS - suffix.length());
        String result = out.toString();
        if (result.length() > bodyBudget) {
            int keepHead = Math.min(head.length(), bodyBudget / 2);
            int keepTail = Math.max(0, bodyBudget - keepHead - 40);
            String trimmedTail = tail.length() > keepTail ? tail.substring(tail.length() - keepTail) : tail;
            result = result.substring(0, Math.min(result.length(), keepHead)) + "\n…（已收紧至定型上限）…\n" + trimmedTail;
            if (result.length() > bodyBudget) {
                result = result.substring(0, bodyBudget);
            }
        }
        return result + suffix;
    }

    static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean looksFailed(String header, String body) {
        String combined = header + "\n" + body.substring(0, Math.min(body.length(), 1_200));
        return combined.contains("ERROR:") || combined.contains("状态: FAILED") || combined.contains("退出码: 1")
            || combined.contains("BUILD FAILURE")
            || combined.contains("Failures: ") && !combined.contains("Failures: 0");
    }

    private static String extractHighlights(String body, boolean failed, int maxChars) {
        String[] lines = body.split("\n", -1);
        LinkedHashSet<String> picked = new LinkedHashSet<>();
        int used = 0;
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.length() < 4 || !isHighlightLine(line, failed)) {
                continue;
            }
            String clipped = line.length() > 220 ? line.substring(0, 220) + "…" : line;
            if (picked.contains(clipped)) {
                continue;
            }
            int add = clipped.length() + (picked.isEmpty() ? 0 : 1);
            if (used + add > maxChars) {
                break;
            }
            picked.add(clipped);
            used += add;
        }
        if (picked.isEmpty()) {
            return "";
        }
        List<String> list = new ArrayList<>(picked);
        return String.join("\n", list);
    }

    private static boolean isHighlightLine(String line, boolean failed) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (line.contains("Tests run:") || line.contains("BUILD SUCCESS") || line.contains("BUILD FAILURE")
            || line.contains("[quality-gate]") || line.contains("<<< FAILURE") || line.contains("<<< ERROR")
            || lower.startsWith("fail ") || lower.startsWith("pass ") || lower.contains("tests passed")
            || lower.contains("tests failed")) {
            return true;
        }
        if (!failed) {
            return false;
        }
        return line.startsWith("[ERROR]") || lower.startsWith("error:") || lower.contains("exception")
            || lower.contains("assertionfailederror") || lower.contains("assertionerror")
            || lower.contains("compilation error") || lower.contains("violation") || lower.contains("expected:");
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
