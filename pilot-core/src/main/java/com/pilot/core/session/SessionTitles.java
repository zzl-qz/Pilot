package com.pilot.core.session;

/** 从首条用户消息提取可读标题，不依赖浏览器或额外模型调用。 */
public final class SessionTitles {
    private SessionTitles() {
    }

    public static String from(String input) {
        String text = input == null ? "" : input.strip();
        text = text.lines().map(String::strip).filter(line -> !line.isBlank()).filter(line -> !line.startsWith("```"))
            .map(line -> line.replaceFirst("^(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)", "")).findFirst().orElse("")
            .replaceAll("\\s+", " ").strip();
        if (text.isBlank()) {
            return "附件任务";
        }
        int length = text.codePointCount(0, text.length());
        return length <= 32 ? text : text.substring(0, text.offsetByCodePoints(0, 32)) + "…";
    }

    public static boolean placeholder(String title) {
        return "新对话".equals(title) || "新任务".equals(title) || "未命名会话".equals(title) || "New conversation".equals(title);
    }
}
