package com.pilot.server.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;

/**
 * 接口按路径里的会话 ID 取会话：找不到统一返回 404。
 * TODO(待确认)：浏览器控制、会话文件、工作区交回三处接口用的是中文文案
 * （“会话不存在: id”“会话不存在”），文案属于对外契约，这里没有擅自并过来。
 */
public final class SessionLookup {
    private SessionLookup() {
    }

    public static Session require(SessionService sessions, String id) {
        return sessions.find(id).orElseThrow(() -> notFound(id));
    }

    /** 会话不存在时的 404；文案沿用历史接口，客户端可能按它展示。 */
    public static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found: " + id);
    }
}
