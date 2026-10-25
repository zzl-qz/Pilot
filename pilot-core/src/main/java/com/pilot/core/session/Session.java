package com.pilot.core.session;

import java.time.Instant;

/**
 * 会话快照。事件列表通过 {@link SessionService#events(String)} 读取。
 *
 * @param workspace 会话绑定的本地工作区绝对路径；为 {@code null} 表示使用服务端默认工作区
 */
public record Session(String id, String title, Instant createdAt, SessionStatus status, String workspace) {
}
