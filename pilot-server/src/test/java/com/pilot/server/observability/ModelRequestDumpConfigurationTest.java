package com.pilot.server.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.pilot.core.model.ModelRequestDumper;
import com.pilot.server.common.data.PilotDataDir;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ModelRequestDumpConfigurationTest {
    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PilotDataDir.class, ModelRequestDumpConfiguration.class);

    @AfterEach
    void resetDumper() {
        ModelRequestDumper.configure(false, null);
    }

    @Test
    void dumpSwitchDefaultsToDisabled(@TempDir Path tempDir) {
        runner.withPropertyValues("pilot.data.dir=" + tempDir).run(context -> {
            var config = context.getBean(ModelRequestDumpConfiguration.class);
            assertFalse(config.isEnabled());
            assertFalse(ModelRequestDumper.isEnabled());
        });
    }

    @Test
    void environmentVariableEnablesDumpSwitch(@TempDir Path tempDir) {
        var variables = Map.<String, Object>of("PILOT_DEBUG_DUMP_MODEL_REQUESTS", "true");
        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
            .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
            .withPropertyValues("pilot.data.dir=" + tempDir).run(context -> {
                var config = context.getBean(ModelRequestDumpConfiguration.class);
                assertTrue(config.isEnabled());
                assertTrue(ModelRequestDumper.isEnabled());
            });
    }
}
