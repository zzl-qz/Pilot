package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgentPropertiesBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Binding.class);

    @Test
    void applicationConfigurationLeavesLegacyFallbackAvailable() {
        runner.withSystemProperties("pilot.completion.checks=false").run(context -> {
            var properties = context.getBean(AgentProperties.class);
            assertFalse(properties.completionChecksEnabled());
            assertEquals(1_000, properties.directMaxRequests());
            assertEquals(300, properties.approvalTimeoutSeconds());
        });
    }

    @Test
    void environmentOverridesDefaultsAndExplicitCompletionSettingWins() {
        var variables = Map.<String, Object>of("PILOT_AGENT_MAX_STEPS", "1500", "PILOT_AGENT_DIRECT_MAX_REQUESTS",
            "1250", "PILOT_AGENT_APPROVAL_TIMEOUT_SECONDS", "90", "PILOT_AGENT_REPEAT_REMIND_AT", "0",
            "PILOT_AGENT_COMPLETION_CHECKS", "true");
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
            .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
            .withSystemProperties("pilot.completion.checks=false").run(context -> {
                var properties = context.getBean(AgentProperties.class);
                assertEquals(1_500, properties.maxSteps());
                assertEquals(1_250, properties.directMaxRequests());
                assertEquals(90, properties.approvalTimeoutSeconds());
                assertEquals(0, properties.repeatRemindAt());
                assertTrue(properties.completionChecksEnabled());
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    static class Binding {}
}
