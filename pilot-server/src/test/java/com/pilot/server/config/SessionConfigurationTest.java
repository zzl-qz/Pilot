package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import com.pilot.core.session.EventStreamLimits;
import com.pilot.core.session.PreviewLimits;

class SessionConfigurationTest {
    private final SessionConfiguration configuration = new SessionConfiguration();

    @Test
    void serverAndCoreUseTheSameDefaults() {
        var environment = new MockEnvironment();
        assertEquals(EventStreamLimits.defaults(), configuration.eventStreamLimits(environment));
        assertEquals(PreviewLimits.defaults(), configuration.previewLimits(environment));
    }

    @Test
    void keepsExistingPropertyAndEnvironmentNamesForBothBudgets() {
        var environment = new MockEnvironment().withProperty("pilot.events.page-size", "128")
            .withProperty("pilot.events.preview-sessions", "3");
        environment.getPropertySources()
            .addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("PILOT_EVENTS_SESSION_CONNECTIONS", "7", "PILOT_EVENTS_MERGE_MS", "123")));
        var events = configuration.eventStreamLimits(environment);
        var previews = configuration.previewLimits(environment);
        assertEquals(128, events.pageSize());
        assertEquals(7, events.subscribersPerSession());
        assertEquals(123, previews.mergeMillis());
        assertEquals(3, previews.sessions());
        assertEquals(EventStreamLimits.defaults().queueBytes(), events.queueBytes());
        assertEquals(PreviewLimits.defaults().mergeBytes(), previews.mergeBytes());
    }

    @Test
    void validatesMalformedAndInconsistentBudgets() {
        assertThrows(IllegalArgumentException.class,
            () -> configuration.eventStreamLimits(new MockEnvironment().withProperty("pilot.events.page-size", "513")));
        assertThrows(IllegalArgumentException.class, () -> configuration
            .previewLimits(new MockEnvironment().withProperty("pilot.events.total-preview-bytes", "100")));
        assertThrows(RuntimeException.class, () -> configuration
            .eventStreamLimits(new MockEnvironment().withProperty("pilot.events.queue-items", "unknown")));
    }
}
