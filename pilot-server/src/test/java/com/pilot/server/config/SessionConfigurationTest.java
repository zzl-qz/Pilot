package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.pilot.core.session.EventStreamLimits;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.PreviewLimits;
import com.pilot.core.session.SessionRepository;

class SessionConfigurationTest {
    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(SessionConfiguration.class)
            .withBean(SessionRepository.class, InMemorySessionRepository::new);

    @Test
    void serverAndCoreUseTheSameDefaults() {
        runner.run(context -> {
            assertEquals(EventStreamLimits.defaults(), context.getBean(EventStreamLimits.class));
            assertEquals(PreviewLimits.defaults(), context.getBean(PreviewLimits.class));
        });
    }

    @Test
    void keepsExistingPropertyAndEnvironmentNamesForBothBudgets() {
        runner.withPropertyValues("pilot.events.page-size=128", "pilot.events.preview-sessions=3")
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Map.of("PILOT_EVENTS_SESSION_CONNECTIONS", "7", "PILOT_EVENTS_MERGE_MS", "123"))))
            .run(context -> {
                var events = context.getBean(EventStreamLimits.class);
                var previews = context.getBean(PreviewLimits.class);
                assertEquals(128, events.pageSize());
                assertEquals(7, events.subscribersPerSession());
                assertEquals(123, previews.mergeMillis());
                assertEquals(3, previews.sessions());
                assertEquals(EventStreamLimits.defaults().queueBytes(), events.queueBytes());
                assertEquals(PreviewLimits.defaults().mergeBytes(), previews.mergeBytes());
            });
    }

    @Test
    void validatesMalformedAndInconsistentBudgets() {
        runner.withPropertyValues("pilot.events.page-size=513")
            .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("pilot.events.total-preview-bytes=100")
            .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("pilot.events.queue-items=unknown")
            .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void rejectsExplicitEmptyValuesForIntegerAndLongLimits() {
        runner.withPropertyValues("pilot.events.page-size=").run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("pilot.events.merge-ms=").run(context -> assertNotNull(context.getStartupFailure()));
    }
}
