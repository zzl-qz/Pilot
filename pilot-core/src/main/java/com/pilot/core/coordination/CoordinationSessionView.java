package com.pilot.core.coordination;

import java.time.Instant;

/**
 * 协调范围内的一条会话状态。
 *
 * @param online         运行时在线状态：来自当前进程的订阅注册，重启后一律为 {@code false}
 * @param lastActivityAt 最近一次会话活动时间；用于判断运行中会话是否疑似停滞
 * @param stalled        处于运行中但超过看门狗静默阈值仍无新活动
 */
public record CoordinationSessionView(String sessionId, String title, String status, String workspace, boolean online,
    boolean archived, Instant lastActivityAt, boolean stalled) {

    public CoordinationSessionView(String sessionId, String title, String status, String workspace, boolean online,
        boolean archived) {
        this(sessionId, title, status, workspace, online, archived, null, false);
    }
}
