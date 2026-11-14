package com.pilot.server.browser;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.server.config.PilotDataDir;

/** 浏览器进程、桌面端点和安装日志共用的运行目录。 */
@Component
public final class BrowserHome {
    private final Path directory;

    public BrowserHome(PilotDataDir dataDir, @Value("${pilot.browser.home:}") String configured) {
        directory = resolve(Path.of(System.getProperty("user.dir", ".")), dataDir.file("browser"), configured);
    }

    static Path resolve(Path workingDir, Path fallback, String configured) {
        Path start = workingDir.toAbsolutePath().normalize();
        if (configured != null && !configured.isBlank()) {
            return start.resolve(configured).normalize();
        }
        Path dir = start;
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isRegularFile(dir.resolve("pilot-server").resolve("pom.xml"))) {
                return dir.resolve(".pilot").resolve("browser");
            }
        }
        return fallback;
    }

    public Path directory() {
        return directory;
    }

    public Path desktopEndpoint() {
        return directory.resolve("desktop-browser.json");
    }
}
