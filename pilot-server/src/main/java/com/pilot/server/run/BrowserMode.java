package com.pilot.server.run;

/**
 * {@code /browser} 浏览器任务模式的识别与提示词提取。
 *
 * <p>消息以 {@code /browser} 开头（词边界，忽略大小写；{@code /browserx} 不算）即进入浏览器模式：
 * 默认为本轮 run 打开 {@code browser} 与 {@code browser_agent} 工具（只读规划/审查模式除外），
 * 并追加浏览器任务协议提示词，确保优先委派隔离的浏览器子代理并依据真实宿主机证据交付。
 */
public record BrowserMode(boolean active, String prompt) {

    private static final String PREFIX = "/browser";

    /** @param input 用户消息原文；null 安全 */
    public static BrowserMode parse(String input) {
        String text = input == null ? "" : input.strip();
        if (text.length() < PREFIX.length() || !text.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return new BrowserMode(false, "");
        }
        String rest = text.substring(PREFIX.length());
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
            return new BrowserMode(false, "");
        }
        return new BrowserMode(true, rest.strip());
    }
}
