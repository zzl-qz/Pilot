package com.pilot.server.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.frontend.FrontendStartup;
import com.pilot.server.model.ModelSettings;
import com.pilot.server.permission.PermissionSettings;
import com.pilot.server.runtime.SourceLayout;

import lombok.RequiredArgsConstructor;

/** 运行状态：当前模型、权限档位和可用工具。 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {
    private static volatile Boolean frontendStale;

    private final AgentProperties agentProperties;
    private final ModelSettings modelSettings;
    private final PermissionSettings permissionSettings;
    private final ObjectProvider<ToolRegistry> toolRegistry;

    @GetMapping
    public HealthResponse health(HttpServletRequest request) {
        ToolRegistry registry = toolRegistry.getIfAvailable();
        List<String> tools = registry == null ? List.of() : registry.tools().stream().map(Tool::name).toList();
        String workspace = agentProperties.workspace();
        if (request != null && !isLoopback(request.getRemoteAddr())) {
            workspace = "";
        }
        return new HealthResponse("ok", modelSettings.snapshot().model(), tools, permissionSettings.mode().name(),
            workspace, frontendStale());
    }

    public HealthResponse health() {
        return health(null);
    }

    private static boolean isLoopback(String address) {
        return address != null
            && (address.startsWith("127.") || "::1".equals(address) || "0:0:0:0:0:0:0:1".equalsIgnoreCase(address));
    }

    /**
     * 源码明显新于已构建的 static 资源时提示界面。
     *
     * <p>这只在仓库内运行（能找到 frontend/src）时有意义；打包后的桌面端没有源码，直接返回 false。
     * 结果缓存一次，健康检查不应每次遍历整棵源码树。
     */
    private static boolean frontendStale() {
        Boolean cached = frontendStale;
        if (cached != null) {
            return cached;
        }
        boolean stale = computeFrontendStale();
        frontendStale = stale;
        return stale;
    }

    private static boolean computeFrontendStale() {
        if (!FrontendStartup.runningFromClasses()) {
            return false;
        }
        Path workingDirectory = SourceLayout.workingDirectory();
        Path root = SourceLayout.frontendRoot(workingDirectory);
        Path staticSource = SourceLayout.staticSource(workingDirectory);
        if (root == null || staticSource == null) {
            return false;
        }
        Path source = SourceLayout.frontendDirectory(root).resolve("src");
        Path assets = staticSource.resolve("assets");
        try {
            return newest(source) > newest(assets) + 2_000L;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static long newest(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> path.toFile().lastModified()).max().orElse(0L);
        }
    }

    public record HealthResponse(String status, String model, List<String> tools, String permissionMode,
        String workspace, boolean frontendStale) {
    }
}
