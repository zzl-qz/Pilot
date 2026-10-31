package com.pilot.core.browser;

import tools.jackson.databind.JsonNode;

/**
 * 浏览器运行时主动事件的监听者（sidecar 的事件通道）。
 *
 * <p>sidecar 除请求-响应外还会主动推送事件：镜像帧（{@code mirror-frame}）、
 * 智能体动作坐标（{@code agent-action}）、主框架导航（{@code nav}）、
 * 浏览器断开（{@code browser-closed}，进程级）。
 *
 * <p>监听者始终在同一个单线程事件流里被调用（顺序即到达顺序），实现应尽快返回——
 * 耗时或可能阻塞的操作（如 SSE 写入）自行做「只保留最新一帧」的丢弃式处理，
 * 不要在监听线程里排队积压。监听者抛出的异常会被吞掉并记日志，不影响协议读取。
 */
@FunctionalInterface
public interface BrowserEventListener {

    /**
     * @param sessionId 事件所属会话；进程级事件（{@code browser-closed}）为空串
     * @param kind      事件类型：{@code mirror-frame} / {@code agent-action} / {@code nav} / {@code browser-closed}
     * @param payload   完整事件消息（含 {@code frame} / {@code x,y} / {@code url} 等载荷字段）
     */
    void onEvent(String sessionId, String kind, JsonNode payload);
}
