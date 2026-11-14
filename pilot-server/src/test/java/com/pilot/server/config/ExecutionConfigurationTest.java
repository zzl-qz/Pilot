package com.pilot.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.pilot.core.exec.ExecutionLimits;
import com.pilot.core.exec.ExecutionSettings;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ExecutionConfigurationTest {
    private static final String COMMAND_NETWORK = "pilot.execution.command-network";

    private final String originalCommandNetwork = System.getProperty(COMMAND_NETWORK);
    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class, PilotDataDir.class);

    @AfterEach
    void restoreCommandNetwork() {
        if (originalCommandNetwork == null) {
            System.clearProperty(COMMAND_NETWORK);
        } else {
            System.setProperty(COMMAND_NETWORK, originalCommandNetwork);
        }
    }

    @Test
    void usesCoreDefaultsWhenSettingsAreAbsent() {
        runner.run(context -> {
            var settings = context.getBean(ExecutionSettings.class);
            assertEquals(ExecutionSettings.DEFAULT_CONTAINER_IMAGE, settings.image());
            assertEquals(ExecutionLimits.defaults(), settings.limits());
        });
    }

    @Test
    void preservesEnvironmentAliasesAndExistingLimitHandling() {
        runner
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Map.of("PILOT_EXECUTION_IMAGE", "example.test/build:1", "PILOT_EXECUTION_MEMORY_MB", "32",
                        "PILOT_EXECUTION_CPUS", "1.5", "PILOT_EXECUTION_PIDS_LIMIT", "3"))))
            .withPropertyValues("pilot.multi.verify-timeout-seconds=0").run(context -> {
                var settings = context.getBean(ExecutionSettings.class);
                assertEquals("example.test/build:1", settings.image());
                assertEquals(new ExecutionLimits(0, ExecutionLimits.defaults().maxOutputBytes(),
                    ExecutionLimits.MIN_MEMORY_MB, 1.5, ExecutionLimits.MIN_PIDS), settings.limits());
                assertEquals(ExecutionLimits.DEFAULT_TIMEOUT_SECONDS,
                    new MultiAgentProperties(null, null, null, null, 0, null).verifyTimeoutSeconds());
            });
    }

    @Test
    void rejectsMalformedNumericOverrides() {
        runner.withPropertyValues("pilot.execution.cpus=unknown")
            .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("pilot.execution.cpus=").run(context -> assertNotNull(context.getStartupFailure()));
    }
}
