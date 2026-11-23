package com.pilot.core.coordination;

import java.time.Clock;
import java.time.Duration;

/**
 * 协调服务的运行时设置：租约时长、静默停滞阈值、时钟与在线状态来源。
 *
 * <p>收成一个参数对象是为了让装配处只描述「按什么规则协调」，而不是排一长串构造参数。
 *
 * @param claimTtl     占用租约；到期自动释放
 * @param handoffTtl   交接待办期限；到期变成 {@code EXPIRED}
 * @param requestTtl   协作请求期限；到期变成 {@code EXPIRED}
 * @param stallTimeout 运行中会话连续无新活动的停滞判定阈值
 */
public record CoordinationSettings(SessionPresence presence, Clock clock, Duration claimTtl, Duration handoffTtl,
    Duration requestTtl, Duration stallTimeout) {

    public CoordinationSettings(SessionPresence presence, Clock clock, Duration claimTtl, Duration handoffTtl) {
        this(presence, clock, claimTtl, handoffTtl, SessionCoordinationService.DEFAULT_REQUEST_TTL,
            SessionCoordinationService.DEFAULT_STALL_TIMEOUT);
    }

    public CoordinationSettings {
        presence = presence == null ? SessionPresence.NONE : presence;
        clock = clock == null ? Clock.systemUTC() : clock;
        claimTtl = claimTtl == null ? SessionCoordinationService.DEFAULT_CLAIM_TTL : claimTtl;
        handoffTtl = handoffTtl == null ? SessionCoordinationService.DEFAULT_HANDOFF_TTL : handoffTtl;
        requestTtl = requestTtl == null ? SessionCoordinationService.DEFAULT_REQUEST_TTL : requestTtl;
        stallTimeout = stallTimeout == null ? SessionCoordinationService.DEFAULT_STALL_TIMEOUT : stallTimeout;
    }

    public static CoordinationSettings defaults() {
        return new CoordinationSettings(SessionPresence.NONE, Clock.systemUTC(),
            SessionCoordinationService.DEFAULT_CLAIM_TTL, SessionCoordinationService.DEFAULT_HANDOFF_TTL,
            SessionCoordinationService.DEFAULT_REQUEST_TTL, SessionCoordinationService.DEFAULT_STALL_TIMEOUT);
    }

    public static CoordinationSettings withPresence(SessionPresence presence) {
        return new CoordinationSettings(presence, Clock.systemUTC(), SessionCoordinationService.DEFAULT_CLAIM_TTL,
            SessionCoordinationService.DEFAULT_HANDOFF_TTL, SessionCoordinationService.DEFAULT_REQUEST_TTL,
            SessionCoordinationService.DEFAULT_STALL_TIMEOUT);
    }

    public CoordinationSettings withClock(Clock clock) {
        return new CoordinationSettings(presence, clock, claimTtl, handoffTtl, requestTtl, stallTimeout);
    }

    public CoordinationSettings withTtls(Duration claims, Duration handoffs) {
        return new CoordinationSettings(presence, clock, claims, handoffs, requestTtl, stallTimeout);
    }

    public CoordinationSettings withRequestTtl(Duration requests) {
        return new CoordinationSettings(presence, clock, claimTtl, handoffTtl, requests, stallTimeout);
    }

    public CoordinationSettings withStallTimeout(Duration timeout) {
        return new CoordinationSettings(presence, clock, claimTtl, handoffTtl, requestTtl, timeout);
    }
}
