package com.pilot.core.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 可见根解析：把目录换成真实路径，并去掉不存在、重复或相互重叠的条目。
 *
 * <p>路径校验和命令沙箱都必须以真实路径比较边界，否则 Windows 8.3 短名和符号链接会让
 * 前缀判断失效；两处共用同一套筛选规则，避免一边放行、另一边拒绝。
 */
public final class RealPaths {
    private RealPaths() {
    }

    /** 不存在或不是目录时返回 {@code null}：项目里的目录可能暂时没挂载，由调用方跳过。 */
    public static Path directory(Path path) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            return Files.isDirectory(absolute) ? absolute.toRealPath() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 主根在前，其余只保留真实存在的目录。
     *
     * @param nested {@code true} 时连与其它额外根重叠的条目也丢掉（路径归属更具体的根），
     *               {@code false} 时只跳过与主根重叠的条目（沙箱里嵌套目录可分别授权）
     */
    public static List<Path> visible(Path primary, List<Path> candidates, boolean nested) {
        List<Path> visible = new ArrayList<>();
        visible.add(primary);
        if (candidates == null) {
            return List.copyOf(visible);
        }
        for (Path candidate : candidates) {
            Path real = candidate == null ? null : directory(candidate);
            if (real == null) {
                continue;
            }
            List<Path> compared = nested ? visible : List.of(primary);
            if (compared.stream().anyMatch(real::startsWith)) {
                continue;
            }
            visible.add(real);
        }
        return List.copyOf(visible);
    }
}
