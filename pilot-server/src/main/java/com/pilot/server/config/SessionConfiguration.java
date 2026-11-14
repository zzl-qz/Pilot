package com.pilot.server.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.pilot.core.session.EventStreamLimits;
import com.pilot.core.session.PreviewLimits;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;

@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {
    @Bean
    SessionService sessionService(SessionRepository repository, EventStreamLimits eventLimits,
        PreviewLimits previewLimits) {
        return new SessionService(Clock.systemUTC(), repository, eventLimits, previewLimits);
    }

    @Bean
    EventStreamLimits eventStreamLimits(Environment environment) {
        EventStreamLimits defaults = EventStreamLimits.defaults();
        return new EventStreamLimits(limit(environment, "queue-items", Integer.class, defaults.queueItems()),
            limit(environment, "queue-bytes", Long.class, defaults.queueBytes()),
            limit(environment, "total-bytes", Long.class, defaults.totalBytes()),
            limit(environment, "connections", Integer.class, defaults.subscribers()),
            limit(environment, "session-connections", Integer.class, defaults.subscribersPerSession()),
            limit(environment, "page-size", Integer.class, defaults.pageSize()),
            limit(environment, "queue-wait-ms", Long.class, defaults.queueWaitMillis()),
            limit(environment, "send-timeout-ms", Long.class, defaults.sendMillis()),
            limit(environment, "replay-timeout-ms", Long.class, defaults.replayMillis()),
            limit(environment, "reference-bytes", Long.class, defaults.referenceBytes()));
    }

    @Bean
    PreviewLimits previewLimits(Environment environment) {
        PreviewLimits defaults = PreviewLimits.defaults();
        return new PreviewLimits(limit(environment, "merge-ms", Long.class, defaults.mergeMillis()),
            limit(environment, "merge-bytes", Integer.class, defaults.mergeBytes()),
            limit(environment, "preview-bytes", Integer.class, defaults.snapshotBytes()),
            limit(environment, "total-preview-bytes", Long.class, defaults.totalSnapshotBytes()),
            limit(environment, "preview-sessions", Integer.class, defaults.sessions()));
    }

    private static <T> T limit(Environment environment, String name, Class<T> type, T fallback) {
        return environment.getProperty("pilot.events." + name, type, fallback);
    }
}
