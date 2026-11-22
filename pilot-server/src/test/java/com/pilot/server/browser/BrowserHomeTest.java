package com.pilot.server.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.server.store.PilotDataDir;
import com.pilot.server.runtime.SourceLayout;

class BrowserHomeTest {
    @TempDir
    Path root;

    @Test
    void keepsRepositoryDefaultAndUsesDataDirectoryOutsideSourceTree() throws Exception {
        Path module = module();
        Path fallback = root.resolve("data").resolve("browser");

        assertEquals(root.resolve("repo").resolve(".pilot").resolve("browser"),
            BrowserHome.resolve(module, fallback, " ", false));
        assertEquals(fallback, BrowserHome.resolve(root, fallback, null, false));
    }

    @Test
    void resolvesCustomAbsoluteAndRelativePathsWithSpacesAndUnicode() throws Exception {
        Path workingDir = Files.createDirectory(root.resolve("workspace"));
        Path custom = root.resolve("浏览器 runtime");
        Path fallback = root.resolve("fallback");

        assertEquals(custom, BrowserHome.resolve(workingDir, fallback, custom.toString(), false));
        assertEquals(custom, BrowserHome.resolve(workingDir, fallback, Path.of("..", "浏览器 runtime").toString(), false));
        assertEquals(custom, BrowserHome.resolve(workingDir, fallback, Path.of("..", "浏览器 runtime").toString(), true));
    }

    /** 显式配了浏览器目录时，即使数据目录也显式配了，仍然以 pilot.browser.home 为准。 */
    @Test
    void configuredBrowserHomeWinsOverExplicitDataDirectoryInsideSourceTree() throws Exception {
        Path module = module();
        Path fallback = root.resolve("shared data").resolve("browser");

        Path custom = module.resolve(Path.of("..", "浏览器 runtime")).normalize();

        assertEquals(custom, BrowserHome.resolve(module, fallback, Path.of("..", "浏览器 runtime").toString(), true));
        assertNotEquals(SourceLayout.localData(root.resolve("repo")).resolve("browser"),
            BrowserHome.resolve(module, fallback, Path.of("..", "浏览器 runtime").toString(), true));
    }

    /**
     * 核心回归：worktree 里显式传了数据目录时，不能再落到当前 worktree 的 .pilot/browser，
     * 否则每次启动都会重新下载 Chromium，桌面端点也和共享数据目录对不上。
     */
    @Test
    void explicitDataDirectoryWinsOverRepositoryDotPilot() throws Exception {
        Path module = module();
        Path worktree = Files.createDirectory(root.resolve("repo").resolve("worktree 副本"));
        Path fallback = root.resolve("shared data").resolve("browser");

        assertEquals(fallback, BrowserHome.resolve(module, fallback, "", true));
        assertEquals(fallback, BrowserHome.resolve(module, fallback, null, true));
        assertEquals(fallback, BrowserHome.resolve(worktree, fallback, " ", true));
    }

    @Test
    void nothingConfiguredKeepsRepositoryDefaultAndFallsBackOutsideSourceTree() throws Exception {
        Path module = module();
        Path fallback = root.resolve("data").resolve("browser");

        assertEquals(root.resolve("repo").resolve(".pilot").resolve("browser"),
            BrowserHome.resolve(module, fallback, null, false));
        assertEquals(fallback, BrowserHome.resolve(root.resolve("seed"), fallback, null, false));
    }

    /** 构造函数走的是真实装配路径：显式数据目录 + 未配浏览器目录 → 数据目录下的 browser。 */
    @Test
    void constructorUsesConfiguredDataDirectoryInsteadOfSourceTree() {
        Path dataDir = root.resolve("共享 数据").resolve(".pilot");
        BrowserHome home = new BrowserHome(new PilotDataDir(dataDir.toString()), "");

        assertEquals(dataDir.toAbsolutePath().normalize().resolve("browser"), home.directory());
        assertEquals(home.directory().resolve("desktop-browser.json"), home.desktopEndpoint());
    }

    /** 没显式配数据目录时保持老行为：源码仓库里落到仓库根 .pilot/browser。 */
    @Test
    void constructorWithoutAnyConfigurationStillUsesTheRepositoryDotPilot() {
        BrowserHome home = new BrowserHome(new PilotDataDir(null), null);
        Path repoRoot = SourceLayout.serverRoot(SourceLayout.workingDirectory());

        Assumptions.assumeTrue(repoRoot != null, "当前不是从源码仓库运行，跳过仓库布局断言");
        assertEquals(SourceLayout.localData(repoRoot).resolve("browser"), home.directory());
    }

    @Test
    void constructorKeepsConfiguredBrowserHomeRelativeToWorkingDirectory() {
        BrowserHome home = new BrowserHome(new PilotDataDir(root.resolve("data").toString()), "浏览器 runtime");

        assertEquals(SourceLayout.workingDirectory().resolve("浏览器 runtime"), home.directory());
    }

    /** 绝对路径的浏览器目录不能被拼到工作目录后面，也不能被数据目录顶掉；Windows 盘符同理。 */
    @Test
    void absoluteBrowserHomeIsNeverRebasedOrOverridden() throws Exception {
        Path module = module();
        Path fallback = root.resolve("shared data").resolve("browser");
        Path custom = root.resolve("安装 目录").resolve("浏览器 缓存").toAbsolutePath().normalize();

        assertEquals(custom, BrowserHome.resolve(module, fallback, custom.toString(), true));
        assertEquals(custom, BrowserHome.resolve(module, fallback, custom.toString(), false));
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Path drive = Path.of("C:\\Program Files\\Pilot\\浏览器 缓存");
            assertEquals(drive, BrowserHome.resolve(module, fallback, drive.toString(), true));
        }
    }

    /** 配置值里的相对路径按平台原生分隔符拼到工作目录下（Windows 用反斜杠，类 Unix 用斜杠）。 */
    @Test
    void relativeBrowserHomeUsesNativeSeparatorsOnEveryPlatform() {
        Path relative = Path.of("共享 数据", "浏览器 缓存");
        Path fallback = root.resolve("fallback");
        Path expected = SourceLayout.workingDirectory().resolve(relative).normalize();

        assertEquals(expected,
            BrowserHome.resolve(SourceLayout.workingDirectory(), fallback, relative.toString(), true));
        assertEquals(expected,
            BrowserHome.resolve(SourceLayout.workingDirectory(), fallback, relative.toString(), false));
    }

    private Path module() throws Exception {
        Path repo = Files.createDirectory(root.resolve("repo"));
        Files.createFile(repo.resolve("pom.xml"));
        Path module = Files.createDirectory(repo.resolve("pilot-server"));
        Files.createFile(module.resolve("pom.xml"));
        return module;
    }
}
