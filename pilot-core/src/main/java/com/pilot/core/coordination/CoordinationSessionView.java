package com.pilot.core.coordination;

/**
 * 协调范围内的一条会话状态。
 *
 * @param online 运行时在线状态：来自当前进程的订阅注册，重启后一律为 {@code false}
 */
public record CoordinationSessionView(String sessionId, String title, String status, String workspace, boolean online,
    boolean archived) {
}
