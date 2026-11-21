package com.pilot.server.frontend;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.stream.Stream;

import com.pilot.core.runtime.ExecutablePath;
import com.pilot.core.util.HostOs;
import com.pilot.server.runtime.SourceLayout;

/** IDEA 直接运行 main 时，补上 Maven 原本负责的前端检查和构建。 */
public final class FrontendStartup {
    private FrontendStartup() {
    }

    public static void prepare() {
        if (!runningFromClasses() || !Boolean.parseBoolean(System.getProperty("pilot.frontend.prepare", "true"))) {
            return;
        }
        Path root = SourceLayout.frontendRoot(SourceLayout.workingDirectory());
        if (root == null) {
            return;
        }

        Path frontend = SourceLayout.frontendDirectory(root);
        Path node = findNode(root);
        ProcessBuilder builder = new ProcessBuilder(node.toString(), "scripts/build.mjs", "--if-needed")
            .directory(frontend.toFile()).inheritIO();
        var environment = builder.environment();
        String pathKey = ExecutablePath.key(environment);
        environment.put(pathKey, node.getParent() + File.pathSeparator + ExecutablePath.inherited(environment));
        try {
            Process process = builder.start();
            try {
                int code = process.waitFor();
                if (code != 0) {
                    throw new IllegalStateException("前端准备失败（退出码 " + code + "），请修复上方错误后重试。");
                }
            } catch (InterruptedException e) {
                process.destroy();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("前端准备已中断。", e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法启动前端构建，请检查 Node.js 安装。", e);
        }
    }

    private static Path findNode(Path root) {
        String executable = HostOs.isWindows() ? "node.exe" : "node";
        Path bundled = SourceLayout.bundledRuntime(root).resolve("node");
        var directories = ExecutablePath.directories(ExecutablePath.inherited(System.getenv()));
        return ExecutablePath.candidates(Stream.concat(directories, Stream.of(bundled)), executable)
            .map(path -> path.toAbsolutePath().normalize()).filter(Files::isRegularFile).filter(Files::isExecutable)
            .findFirst().orElseThrow(() -> new IllegalStateException("找不到 Node.js，请先安装 Node.js 或运行源码启动脚本。"));
    }

    public static boolean runningFromClasses() {
        return SourceLayout.runningFromClasses(FrontendStartup.class);
    }

    static boolean runningFromClasses(CodeSource source) {
        return SourceLayout.runningFromClasses(source);
    }

    /** Spring Boot 4 的 jar:nested: 地址也能被解析成目录，只有 file: 目录才是开发态。 */
    static boolean isDevelopmentLocation(String location) {
        return SourceLayout.isDevelopmentLocation(location);
    }
}
