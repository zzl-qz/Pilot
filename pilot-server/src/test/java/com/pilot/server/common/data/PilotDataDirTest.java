package com.pilot.server.common.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.pilot.server.browser.BrowserHome;

/**
 * 数据目录的取值与「是否显式配置」标记。
 *
 * <p>这条标记直接决定 {@link BrowserHome} 会不会在 worktree 里继续用当前源码树下的
 * {@code .pilot/browser}，所以 application.yaml 的默认值也一起锁定：默认必须留空，
 * 不能再写成 {@code ${user.dir}/.pilot}，否则每个启动都算「用户显式配置」。
 */
class PilotDataDirTest {
    @TempDir
    Path root;

    @Test
    void blankConfigurationFallsBackToProjectDotPilotWithoutBeingExplicit() {
        PilotDataDir data = new PilotDataDir(" ");

        assertEquals(projectDotPilot(), data.root());
        assertFalse(data.explicitlyConfigured());
    }

    @Test
    void configuredDirectoryIsNormalizedAndMarkedExplicit() {
        PilotDataDir data = new PilotDataDir(root.resolve("共享 数据").resolve(".pilot").toString());

        assertEquals(root.resolve("共享 数据").resolve(".pilot").toAbsolutePath().normalize(), data.root());
        assertTrue(data.explicitlyConfigured());
    }

    @Test
    void pilotDataDirEnvironmentVariableDrivesBrowserHomeThroughApplicationDefaults() {
        Path shared = root.resolve("共享 数据").resolve(".pilot");

        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Map.of("PILOT_DATA_DIR", shared.toString()))))
            .withUserConfiguration(PilotDataDir.class, BrowserHome.class).run(context -> {
                PilotDataDir data = context.getBean(PilotDataDir.class);
                assertTrue(data.explicitlyConfigured());
                assertEquals(shared.toAbsolutePath().normalize(), data.root());
                assertEquals(shared.toAbsolutePath().normalize().resolve("browser"),
                    context.getBean(BrowserHome.class).directory());
            });
    }

    @Test
    void applicationDefaultsStayImplicitSoRepositoryLayoutStillApplies() {
        new ApplicationContextRunner()
            .withInitializer(
                new ConfigDataApplicationContextInitializer())
            .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of())))
            .withUserConfiguration(PilotDataDir.class).run(context -> {
                PilotDataDir data = context.getBean(PilotDataDir.class);
                assertFalse(data.explicitlyConfigured());
                assertEquals(projectDotPilot(), data.root());
            });
    }

    private static Path projectDotPilot() {
        return Path.of(System.getProperty("user.dir", "."), ".pilot").toAbsolutePath().normalize();
    }
}
