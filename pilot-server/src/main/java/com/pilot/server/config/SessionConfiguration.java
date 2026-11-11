package com.pilot.server.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;

@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {
    @Bean
    SessionService sessionService(SessionRepository repository,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.queue-items:4096}") int items,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.queue-bytes:8388608}") long bytes,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.total-bytes:134217728}") long total,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.connections:256}") int connections,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.session-connections:32}") int perSession,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.page-size:256}") int page,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.queue-wait-ms:30000}") long wait,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.send-timeout-ms:30000}") long send,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.replay-timeout-ms:300000}") long replay,
        @org.springframework.beans.factory.annotation.Value("${pilot.events.reference-bytes:262144}") long reference) {
        return new SessionService(Clock.systemUTC(), repository,
            new com.pilot.core.session.EventStreamLimits(items, bytes, total, connections, perSession, page, wait, send, replay, reference));
    }
}
