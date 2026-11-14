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
        return new EventStreamLimits(limit(environment, "queue-items", int.class, defaults.queueItems()),
            limit(environment, "queue-bytes", long.class, defaults.queueBytes()),
            limit(environment, "total-bytes", long.class, defaults.totalBytes()),
            limit(environment, "connections", int.class, defaults.subscribers()),
            limit(environment, "session-connections", int.class, defaults.subscribersPerSession()),
            limit(environment, "page-size", int.class, defaults.pageSize()),
            limit(environment, "queue-wait-ms", long.class, defaults.queueWaitMillis()),
            limit(environment, "send-timeout-ms", long.class, defaults.sendMillis()),
            limit(environment, "replay-timeout-ms", long.class, defaults.replayMillis()),
            limit(environment, "reference-bytes", long.class, defaults.referenceBytes()));
    }

    @Bean
    PreviewLimits previewLimits(Environment environment) {
        PreviewLimits defaults = PreviewLimits.defaults();
        return new PreviewLimits(limit(environment, "merge-ms", long.class, defaults.mergeMillis()),
            limit(environment, "merge-bytes", int.class, defaults.mergeBytes()),
            limit(environment, "preview-bytes", int.class, defaults.snapshotBytes()),
            limit(environment, "total-preview-bytes", long.class, defaults.totalSnapshotBytes()),
            limit(environment, "preview-sessions", int.class, defaults.sessions()));
    }

    private static <T> T limit(Environment environment, String name, Class<T> type, T fallback) {
        // 使用基本类型转换，让显式空值与原来的 @Value 参数一样报错。
        return environment.getProperty("pilot.events." + name, type, fallback);
    }
}
