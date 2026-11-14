package com.pilot.core.util;

import java.util.Locale;

/** 从文件名里取扩展名。统一小写，没有扩展名时返回空串。 */
public final class FileNames {
    private FileNames() {
    }

    /** 不带点的扩展名，例如 report.PDF 得到 pdf。 */
    public static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 带点的扩展名，例如 report.XLSX 得到 .xlsx。 */
    public static String dotExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
