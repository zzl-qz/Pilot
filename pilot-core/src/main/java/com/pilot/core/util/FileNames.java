package com.pilot.core.util;

import java.nio.file.Path;
import java.util.Locale;

/** 从文件名或路径里提取扩展名与主干名。扩展名统一小写，没有扩展名时返回空串。 */
public final class FileNames {
    private FileNames() {
    }

    /** 不带点的扩展名，例如 {@code report.PDF} 得到 {@code pdf}；忽略目录名里的点。 */
    public static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = extensionDot(filename);
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** {@link #extension(String)} 的 {@link Path} 重载。 */
    public static String extension(Path file) {
        if (file == null || file.getFileName() == null) {
            return "";
        }
        return extension(file.getFileName().toString());
    }

    /** 带点的扩展名，例如 {@code report.XLSX} 得到 {@code .xlsx}；忽略目录名里的点。 */
    public static String dotExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = extensionDot(filename);
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** {@link #dotExtension(String)} 的 {@link Path} 重载。 */
    public static String dotExtension(Path file) {
        if (file == null || file.getFileName() == null) {
            return "";
        }
        return dotExtension(file.getFileName().toString());
    }

    /** 去掉目录前缀与最后一段扩展名后的文件名主干，例如 {@code dir.v1/App.java} 得到 {@code App}。 */
    public static String stem(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String base = slash < 0 ? path : path.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static int extensionDot(String filename) {
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        return dot > slash ? dot : -1;
    }
}
