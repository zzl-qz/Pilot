package com.pilot.server.config;

import java.time.Clock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.session.EventStreamLimits;
import com.pilot.core.session.PreviewLimits;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionConfiguration.EventStreamProperties.class)
public class SessionConfiguration {
    @Bean
    SessionService sessionService(SessionRepository repository, EventStreamLimits eventLimits,
        PreviewLimits previewLimits) {
        return new SessionService(Clock.systemUTC(), repository, eventLimits, previewLimits);
    }

    @Bean
    EventStreamLimits eventStreamLimits(EventStreamProperties properties) {
        return properties.toEventStreamLimits();
    }

    @Bean
    PreviewLimits previewLimits(EventStreamProperties properties) {
        return properties.toPreviewLimits();
    }

    @ConfigurationProperties(prefix = "pilot.events")
    public record EventStreamProperties(@DefaultValue("4096") int queueItems, @DefaultValue("8388608") long queueBytes,
        @DefaultValue("134217728") long totalBytes, @DefaultValue("256") int connections,
        @DefaultValue("32") int sessionConnections, @DefaultValue("256") int pageSize,
        @DefaultValue("30000") long queueWaitMs, @DefaultValue("30000") long sendTimeoutMs,
        @DefaultValue("300000") long replayTimeoutMs, @DefaultValue("262144") long referenceBytes,
        @DefaultValue("50") long mergeMs, @DefaultValue("8192") int mergeBytes,
        @DefaultValue("131072") int previewBytes, @DefaultValue("16777216") long totalPreviewBytes,
        @DefaultValue("256") int previewSessions) {
        EventStreamLimits toEventStreamLimits() {
            return new EventStreamLimits(queueItems, queueBytes, totalBytes, connections, sessionConnections, pageSize,
                queueWaitMs, sendTimeoutMs, replayTimeoutMs, referenceBytes);
        }

        PreviewLimits toPreviewLimits() {
            return new PreviewLimits(mergeMs, mergeBytes, previewBytes, totalPreviewBytes, previewSessions);
        }
    }
}
