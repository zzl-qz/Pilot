package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MultiAgentPropertiesBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    void bindsCanonicalRecordConstructorAndCrossSessionSettings() {
        runner.withPropertyValues("pilot.multi.enabled=false", "pilot.multi.cross-session-conflict=block",
            "pilot.multi.cross-session-window-minutes=75").run(context -> {
                var properties = context.getBean(MultiAgentProperties.class);
                assertFalse(properties.enabled());
                assertEquals(MultiAgentProperties.CROSS_SESSION_BLOCK, properties.crossSessionConflict());
                assertEquals(75, properties.crossSessionWindowMinutes());
            });
    }

    @Test
    void appliesDefaultsWhenPropertiesAreAbsent() {
        runner.run(context -> {
            var properties = context.getBean(MultiAgentProperties.class);
            assertEquals(MultiAgentProperties.CROSS_SESSION_WARN, properties.crossSessionConflict());
            assertEquals(MultiAgentProperties.DEFAULT_CROSS_SESSION_WINDOW_MINUTES,
                properties.crossSessionWindowMinutes());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MultiAgentProperties.class)
    static class Binding {}
}
