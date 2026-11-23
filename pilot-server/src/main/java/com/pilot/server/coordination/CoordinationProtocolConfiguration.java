package com.pilot.server.coordination;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.coordination.CoordinationScopeResolver;
import com.pilot.core.coordination.CoordinationSettings;
import com.pilot.core.coordination.CoordinationSnapshotStore;
import com.pilot.core.coordination.SessionCoordinationService;
import com.pilot.core.coordination.WorkspaceEventPublisher;
import com.pilot.core.session.SessionRepository;

/**
 * 协调协议的装配：协议服务只依赖显式注入的端口，租约与在线状态通过 {@link CoordinationSettings} 收口。
 */
@Configuration
public class CoordinationProtocolConfiguration {

    @Bean
    public SessionCoordinationService sessionCoordinationService(SessionRepository sessions,
        CoordinationScopeResolver scopes, WorkspaceEventPublisher events, CoordinationSnapshotStore store,
        SessionPresenceRegistry presence, @Value("${pilot.coordination.claim-ttl-minutes:30}") long claimTtlMinutes,
        @Value("${pilot.coordination.handoff-ttl-hours:12}") long handoffTtlHours) {
        CoordinationSettings settings = new CoordinationSettings(presence, Clock.systemUTC(),
            Duration.ofMinutes(Math.max(1, claimTtlMinutes)), Duration.ofHours(Math.max(1, handoffTtlHours)));
        return new SessionCoordinationService(sessions, scopes, events, store, settings);
    }
}
