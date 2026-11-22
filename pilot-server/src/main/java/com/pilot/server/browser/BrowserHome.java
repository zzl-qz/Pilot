package com.pilot.server.browser;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.server.store.PilotDataDir;
import com.pilot.server.runtime.SourceLayout;

/**
 * 浏览器进程、桌面端点和安装日志共用的运行目录。
 *
 * <p>优先级：{@code pilot.browser.home} →（显式配了 {@code pilot.data.dir}/{@code PILOT_DATA_DIR} 时）
 * 数据目录下的 {@code browser} → 源码仓库根 {@code .pilot/browser} → 数据目录下的 {@code browser}。
 * 中间两级保证在 worktree 里指向共享数据目录启动时不会重新下载 Chromium、桌面端点也跟着共享目录走。
 */
@Component
public final class BrowserHome {
    private final Path directory;

    public BrowserHome(PilotDataDir dataDir, @Value("${pilot.browser.home:}") String configured) {
        directory = resolve(SourceLayout.workingDirectory(), dataDir.file("browser"), configured,
            dataDir.explicitlyConfigured());
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
        return directory.resolve("desktop-browser.json");
    }
}
