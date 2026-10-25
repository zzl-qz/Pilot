package com.pilot.core.session;

import java.time.Instant;

/** 会话快照。事件列表通过 {@link SessionService#events(String)} 读取。 */
public record Session(String id, String title, Instant createdAt, SessionStatus status) {
}
