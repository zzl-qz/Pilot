package com.pilot.core.coordination;

import java.time.Clock;
import java.time.Duration;

/**
 * 协调服务的运行时设置：租约时长、时钟与在线状态来源。
 *
 * <p>收成一个参数对象是为了让装配处只描述「按什么规则协调」，而不是排一长串构造参数。
 *
 * @param claimTtl   占用租约；到期自动释放
 * @param handoffTtl 交接待办期限；到期变成 {@code EXPIRED}
 */
public record CoordinationSettings(SessionPresence presence, Clock clock, Duration claimTtl, Duration handoffTtl) {

    public CoordinationSettings {
        presence = presence == null ? SessionPresence.NONE : presence;
        clock = clock == null ? Clock.systemUTC() : clock;
        claimTtl = claimTtl == null ? SessionCoordinationService.DEFAULT_CLAIM_TTL : claimTtl;
        handoffTtl = handoffTtl == null ? SessionCoordinationService.DEFAULT_HANDOFF_TTL : handoffTtl;
    }

    public static CoordinationSettings defaults() {
        return new CoordinationSettings(SessionPresence.NONE, Clock.systemUTC(),
            SessionCoordinationService.DEFAULT_CLAIM_TTL, SessionCoordinationService.DEFAULT_HANDOFF_TTL);
    }

    public static CoordinationSettings withPresence(SessionPresence presence) {
        return new CoordinationSettings(presence, Clock.systemUTC(), SessionCoordinationService.DEFAULT_CLAIM_TTL,
            SessionCoordinationService.DEFAULT_HANDOFF_TTL);
    }

    public CoordinationSettings withClock(Clock clock) {
        return new CoordinationSettings(presence, clock, claimTtl, handoffTtl);
    }

    public CoordinationSettings withTtls(Duration claims, Duration handoffs) {
        return new CoordinationSettings(presence, clock, claims, handoffs);
    }
}
