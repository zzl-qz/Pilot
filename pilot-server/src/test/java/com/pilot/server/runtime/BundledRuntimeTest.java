package com.pilot.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.pilot.server.common.data.PilotDataDir;

/**
 * 内置 Python 运行时根目录的解析。
 *
 * <p>核心回归：在独立 worktree 里显式传了 {@code PILOT_DATA_DIR} 时，不能再落到当前 worktree 的
 * {@code .pilot/runtime/python}，否则每次启动都会重复下载一套 Python 3.12；没显式配置时保持老布局。
 * 这些用例只算路径，不会触发下载。
 */
class BundledRuntimeTest {
    @TempDir
    Path root;

    /** 显式数据目录优先于源码树里的 {@code .pilot/runtime}。 */
    @Test
    void explicitDataDirectoryWinsOverRepositoryDotPilot() throws Exception {
        Path module = module();
        Path shared = root.resolve("共享 数据").resolve("runtime");

        assertEquals(shared, BundledRuntime.resolveRuntimeRoot(module, shared, true));
        assertNotEquals(SourceLayout.localData(root.resolve("repo")).resolve("runtime"),
            BundledRuntime.resolveRuntimeRoot(module, shared, true));
    }

    /** 没显式配置时保持兼容行为：源码树落到仓库根 {@code .pilot/runtime}，否则当前目录。 */
    @Test
    void nothingConfiguredKeepsRepositoryLayoutThenWorkingDirectory() throws Exception {
        Path module = module();
        Path fallback = root.resolve("data").resolve("runtime");

        assertEquals(root.resolve("repo").resolve(".pilot").resolve("runtime"),
            BundledRuntime.resolveRuntimeRoot(module, fallback, false));
        Path outside = Files.createDirectory(root.resolve("安装包"));
        assertEquals(outside.resolve(".pilot").resolve("runtime"),
            BundledRuntime.resolveRuntimeRoot(outside, fallback, false));
    }

    /** 构造函数走真实装配路径：显式数据目录 + 未配其他开关 → 数据目录下的 runtime。 */
    @Test
    void constructorUsesConfiguredDataDirectory() {
        Path dataDir = root.resolve("共享 数据").resolve(".pilot");
        BundledRuntime runtime = new BundledRuntime(new PilotDataDir(dataDir.toString()), false);

        assertEquals(dataDir.toAbsolutePath().normalize().resolve("runtime"), runtime.runtimeRoot());
    }

    /** 没显式配数据目录时，构造函数仍然使用仓库根的 {@code .pilot/runtime}。 */
    @Test
    void constructorWithoutAnyConfigurationStillUsesTheRepositoryDotPilot() {
        BundledRuntime runtime = new BundledRuntime(new PilotDataDir(null), false);
        Path repoRoot = SourceLayout.serverRoot(SourceLayout.workingDirectory());

        Assumptions.assumeTrue(repoRoot != null, "当前不是从源码仓库运行，跳过仓库布局断言");
        assertEquals(SourceLayout.bundledRuntime(repoRoot), runtime.runtimeRoot());
    }

    /**
     * 真实装配链路：{@code PILOT_DATA_DIR} 经 application.yaml 默认值进入 {@link PilotDataDir}，
     * 再驱动 {@link BundledRuntime} 的运行时根目录。{@code provision=false} 保证不会联网下载 Python。
     */
    @Test
    void pilotDataDirEnvironmentVariableDrivesRuntimeRoot() {
        Path shared = root.resolve("共享 数据").resolve(".pilot");
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Map.of("PILOT_DATA_DIR", shared.toString()))))
            .withPropertyValues("pilot.runtime.provision=false")
            .withUserConfiguration(PilotDataDir.class, BundledRuntime.class).run(context -> {
                PilotDataDir data = context.getBean(PilotDataDir.class);
                assertTrue(data.explicitlyConfigured());
                assertEquals(shared.toAbsolutePath().normalize().resolve("runtime"),
                    context.getBean(BundledRuntime.class).runtimeRoot());
            });
    }

    /** 只有源码仓库、没有 pilot-server 模块的目录不算源码树。 */
    @Test
    void repositoryWithoutServerModuleIsNotTreatedAsSourceTree() throws Exception {
        Path plain = Files.createDirectories(root.resolve("普通目录"));
        Files.createFile(plain.resolve("pom.xml"));

        assertEquals(plain.resolve(".pilot").resolve("runtime"),
            BundledRuntime.resolveRuntimeRoot(plain, root.resolve("data").resolve("runtime"), false));
    }

    private Path module() throws Exception {
        Path repo = Files.createDirectory(root.resolve("repo"));
        Files.createFile(repo.resolve("pom.xml"));
        Path module = Files.createDirectory(repo.resolve("pilot-server"));
        Files.createFile(module.resolve("pom.xml"));
        return module;
    }
}
