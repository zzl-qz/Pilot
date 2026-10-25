package com.pilot.core.session;

/** 会话不存在。接入层应将其映射为 404。 */
public class NoSuchSessionException extends RuntimeException {
    public NoSuchSessionException(String sessionId) {
        super("session not found: " + sessionId);
    }
}
