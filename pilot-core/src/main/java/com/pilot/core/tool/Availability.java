package com.pilot.core.tool;

/**
 * 工具当前是否真的可用。
 *
 * <p>只有依赖外部运行时的工具（浏览器、MCP 服务）需要覆盖 {@link Tool#availability()}：
 * 运行时没有启动或握手失败时，工具不进入模型请求，也不进入系统提示词，
 * 模型不会被告知一个调用不了的能力；若模型仍按旧上下文调用，执行层返回明确原因，避免反复重试。
 *
 * @param available 是否可用
 * @param reason    不可用时给人和模型看的原因，包含可行的修复办法
 */
public record Availability(boolean available, String reason) {
    private static final Availability READY = new Availability(true, "");

    public Availability {
        reason = reason == null ? "" : reason.strip();
        if (!available && reason.isEmpty()) {
            reason = "运行时未就绪";
        }
    }

    public static Availability ready() {
        return READY;
    }

    public static Availability unavailable(String reason) {
        return new Availability(false, reason);
    }
}
