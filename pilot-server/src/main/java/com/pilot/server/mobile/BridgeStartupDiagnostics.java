package com.pilot.server.mobile;

import java.util.Locale;

/** 只保留已知故障类别，不保存可能包含本机路径、令牌或配对凭证的子进程输出。 */
final class BridgeStartupDiagnostics {
    private enum Failure {
        UNKNOWN, NATIVE_LIBRARY, JAVA_VERSION, MISSING_COMPONENT, PERMISSION
    }

    private final boolean windows;
    private Failure failure = Failure.UNKNOWN;

    BridgeStartupDiagnostics(String os) {
        windows = os.toLowerCase(Locale.ROOT).contains("win");
    }

    synchronized void accept(String line) {
        String text = line.substring(0, Math.min(line.length(), 4096)).toLowerCase(Locale.ROOT);
        if (text.contains("unsupportedclassversionerror")) {
            failure = Failure.JAVA_VERSION;
        } else if (text.contains("could not find or load main class") || text.contains("noclassdeffounderror")
            || text.contains("classnotfoundexception")) {
            if (failure == Failure.UNKNOWN) {
                failure = Failure.MISSING_COMPONENT;
            }
        } else if (text.contains("unsatisfiedlinkerror") || text.contains("unable to load library")
            || text.contains("jna is already loaded")) {
            if (failure != Failure.JAVA_VERSION && failure != Failure.PERMISSION) {
                failure = Failure.NATIVE_LIBRARY;
            }
        } else if (text.contains("accessdeniedexception") || text.contains("permission denied")
            || text.contains("read-only file system")) {
            if (failure != Failure.JAVA_VERSION) {
                failure = Failure.PERMISSION;
            }
        }
    }

    synchronized String message() {
        return switch (failure) {
            case JAVA_VERSION -> "手机连接组件与 Java 版本不兼容，请使用 Java 25 或更新完整安装包";
            case MISSING_COMPONENT -> "手机连接组件缺少依赖，源码运行请先执行 node scripts/mobile.mjs build-bridge；安装版请更新完整安装包";
            case NATIVE_LIBRARY -> windows ? "Windows x64 原生连接库加载失败，请检查 Microsoft Visual C++ x64 运行库、临时目录权限或安全软件拦截"
                : "原生连接库加载失败，请检查当前系统架构、临时目录权限及完整安装包";
            case PERMISSION -> "手机连接组件无法读写运行目录或加载临时文件，请检查目录权限与安全软件拦截";
            case UNKNOWN -> "手机连接组件未能启动，请检查安装包或稍后重试";
        };
    }
}
