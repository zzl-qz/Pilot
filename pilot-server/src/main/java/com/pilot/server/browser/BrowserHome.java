package com.pilot.server.browser;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.server.runtime.SourceLayout;
import com.pilot.server.store.PilotDataDir;

/**
 * 浏览器进程、桌面端点和安装日志共用的运行目录。
 *
 * <p>浏览器二进制和安装日志仍按原有优先级落在 {@code pilot.browser.home} / 显式数据目录 / 仓库 {@code .pilot/browser}；
 * 桌面控制端点文件 {@code desktop-browser.json} 默认迁出工作区，落到用户私有运行目录（{@code ~/.pilot/run/} 或
 * Windows {@code %LOCALAPPDATA%\Pilot\run\}），仅在显式配置隔离目录或环境变量时落到指定位置。
 */
@Component
public final class BrowserHome {
    private final Path directory;
    private final Path desktopEndpoint;

    @Autowired
    public BrowserHome(PilotDataDir dataDir, @Value("${pilot.browser.home:}") String configured) {
        this(dataDir, configured, System.getenv("PILOT_DESKTOP_ENDPOINT"), System.getenv("PILOT_BROWSER_HOME"),
            System.getProperty("os.name", ""), System.getenv("LOCALAPPDATA"), System.getProperty("user.home", "."));
    }

    BrowserHome(PilotDataDir dataDir, String configured, String envEndpoint, String envBrowserHome, String osName,
        String localAppData, String userHome) {
        boolean configuredHome = configured != null && !configured.isBlank();
        boolean envHome = envBrowserHome != null && !envBrowserHome.isBlank();
        String effectiveConfigured = configuredHome ? configured : envHome ? envBrowserHome : "";
        directory = resolve(SourceLayout.workingDirectory(), dataDir.file("browser"), effectiveConfigured,
            dataDir.explicitlyConfigured());
        if (envEndpoint != null && !envEndpoint.isBlank()) {
            desktopEndpoint = Paths.get(envEndpoint).toAbsolutePath().normalize();
        } else if (configuredHome || envHome || dataDir.explicitlyConfigured()) {
            desktopEndpoint = directory.resolve("desktop-browser.json");
        } else {
            desktopEndpoint = privateRunDir(osName, localAppData, userHome).resolve("desktop-browser.json");
        }
    }

    static Path privateRunDir(String osName, String localAppData, String userHome) {
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
        Path base = windows && localAppData != null && !localAppData.isBlank() ? Path.of(localAppData, "Pilot")
            : Path.of(userHome == null || userHome.isBlank() ? "." : userHome, ".pilot");
        return base.toAbsolutePath().normalize().resolve("run");
    }

    static Path resolve(Path workingDir, Path fallback, String configured, boolean dataDirConfigured) {
        Path start = workingDir.toAbsolutePath().normalize();
        if (configured != null && !configured.isBlank()) {
            return start.resolve(configured).normalize();
        }
        if (dataDirConfigured) {
            return fallback;
        }
        Path root = SourceLayout.serverRoot(start);
        return root == null ? fallback : SourceLayout.localData(root).resolve("browser");
    }

    public Path directory() {
        return directory;
    }

    public Path desktopEndpoint() {
        return desktopEndpoint;
    }
}
