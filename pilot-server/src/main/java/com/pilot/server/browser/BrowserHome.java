package com.pilot.server.browser;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.server.config.PilotDataDir;
import com.pilot.server.runtime.SourceLayout;

/** 浏览器进程、桌面端点和安装日志共用的运行目录。 */
@Component
public final class BrowserHome {
    private final Path directory;

    public BrowserHome(PilotDataDir dataDir, @Value("${pilot.browser.home:}") String configured) {
        directory = resolve(SourceLayout.workingDirectory(), dataDir.file("browser"), configured);
    }

    static Path resolve(Path workingDir, Path fallback, String configured) {
        Path start = workingDir.toAbsolutePath().normalize();
        if (configured != null && !configured.isBlank()) {
            return start.resolve(configured).normalize();
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
