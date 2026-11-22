package com.pilot.server.coordination;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.core.coordination.SessionPresence;

/**
 * 运行时在线登记：当前进程里打开看板或工作区事件流的会话才算在线。
 *
 * <p>只存内存，进程重启后自然清空；连接异常消失时靠 {@code ttl-ms} 兜底过期，
 * 不会被持久化成「重启后仍在线的会话」。
 */
@Component
public class SessionPresenceRegistry implements SessionPresence {

    private final ConcurrentMap<String, Long> onlineAt = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SessionPresenceRegistry(@Value("${pilot.coordination.presence-ttl-ms:300000}") long ttlMillis) {
        this.ttlMillis = Math.max(1_000L, ttlMillis);
    }

    /** 打开看板或建立事件流时登记；重复登记只是刷新时间。 */
    public void markOnline(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        onlineAt.put(sessionId, System.currentTimeMillis());
    }

    /** 连接正常关闭时撤销登记。 */
    public void markOffline(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        onlineAt.remove(sessionId);
    }

    @Override
    public boolean isOnline(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Long seen = onlineAt.get(sessionId);
        if (seen == null) {
            return false;
        }
        if (System.currentTimeMillis() - seen > ttlMillis) {
            onlineAt.remove(sessionId, seen);
            return false;
        }
        return true;
    }

    /** 当前仍在有效期内的在线会话，供指标端点观测。 */
    public Set<String> onlineSessions() {
        long now = System.currentTimeMillis();
        onlineAt.entrySet().removeIf(entry -> now - entry.getValue() > ttlMillis);
        return Set.copyOf(onlineAt.keySet());
    }

    /** 在线数与有效期，界面用它解释「为什么刚打开时看不到别人在线」。 */
    public Map<String, Object> metrics() {
        return Map.of("online", onlineSessions().size(), "ttlMillis", ttlMillis);
    }
}
