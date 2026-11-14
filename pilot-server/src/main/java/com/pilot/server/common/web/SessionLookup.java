package com.pilot.server.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;

/**
 * 接口按路径里的会话 ID 取会话：找不到统一返回 404。
 * 英文和中文两套文案都是已经对外的，这里只把它们收到一处，不改字。
 */
public final class SessionLookup {
    /** 浏览器控制、会话文件、工作区交回、文件工作台、收件箱沿用的中文。 */
    public static final String MISSING = "会话不存在";

    private SessionLookup() {
    }

    public static Session require(SessionService sessions, String id) {
        return sessions.find(id).orElseThrow(() -> notFound(id));
    }

    /** 多数会话接口的 404，文案是 session not found。 */
    public static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id);
    }

    /** 浏览器控制、会话文件的 404，文案带会话 id。 */
    public static ResponseStatusException missing(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, MISSING + ": " + id);
    }

    /** 工作区交回的 404，文案不带 id。 */
    public static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, MISSING);
    }
}
