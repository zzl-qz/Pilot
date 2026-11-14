package com.pilot.core.util;

import java.util.Locale;

/**
 * 当前机器是哪种操作系统。
 * 全项目统一在这里判断，别处不要再各自读 os.name 写一遍 contains("win")。
 * 带参数的版本是纯函数，测试里可以直接传系统名。
 */
public final class HostOs {
    private HostOs() {
    }

    public static boolean isWindows() {
        return isWindows(name());
    }

    public static boolean isMac() {
        return isMac(name());
    }

    public static boolean isLinux() {
        return isLinux(name());
    }

    /** Windows 的 os.name 都以 Windows 开头；不用 contains("win")，否则 Darwin 会被误判。 */
    public static boolean isWindows(String osName) {
        return lower(osName).startsWith("windows");
    }

    public static boolean isMac(String osName) {
        String name = lower(osName);
        return name.contains("mac") || name.contains("darwin");
    }

    public static boolean isLinux(String osName) {
        return lower(osName).contains("linux");
    }

    /** os.name 原文，读不到时是空串。每次现读，不缓存。 */
    public static String name() {
        return System.getProperty("os.name", "");
    }

    private static String lower(String osName) {
        return osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    }
}
