package com.pilot.core.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工具运行中产生的二进制产物（截图、PDF 渲染页）的落盘位置。
 *
 * <p>服务启动时把数据目录写入系统属性 {@value #PROPERTY}，产物随会话数据一起保留与清理；
 * 没有配置时（单元测试、独立使用）退回系统临时目录，不会写进用户工作区。
 */
public final class ToolArtifacts {
    public static final String PROPERTY = "pilot.artifacts.dir";

    private ToolArtifacts() {
    }

    /** 返回（并创建）某类产物的目录，例如 {@code directory("browser/session-1")}。 */
    public static Path directory(String bucket) {
        String configured = System.getProperty(PROPERTY);
        Path root = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "pilot-artifacts")
                : Path.of(configured);
        Path dir = root.resolve(bucket).toAbsolutePath().normalize();
        if (!dir.startsWith(root.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("产物目录越界: " + bucket);
        }
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建产物目录: " + dir, e);
        }
    }
}
