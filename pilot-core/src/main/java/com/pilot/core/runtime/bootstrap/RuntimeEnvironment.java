package com.pilot.core.runtime.bootstrap;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** 源码启动器、后台和子进程共用的运行时环境约定。 */
public final class RuntimeEnvironment {
    public static final String BOOTSTRAP_DIR = "PILOT_BOOTSTRAP_DIR";
    public static final String BOOTSTRAP_NODE = "PILOT_BOOTSTRAP_NODE";

    private static final String BUNDLED_BIN_PROPERTY = "pilot.runtime.bin";
    private static final String MOBILE_BIN_PROPERTY = "pilot.mobile.runtime.bin";

    private RuntimeEnvironment() {
    }

    public static boolean offline() {
        return "true".equalsIgnoreCase(System.getenv("PILOT_OFFLINE"));
    }

    public static String bundledBinPath() {
        return System.getProperty(BUNDLED_BIN_PROPERTY, "");
    }

    public static String publishBundledBins(List<String> bins) {
        String path = String.join(File.pathSeparator, bins);
        System.setProperty(BUNDLED_BIN_PROPERTY, path);
        return path;
    }

    public static String mobileBinPath() {
        return System.getProperty(MOBILE_BIN_PROPERTY, "");
    }

    public static void publishMobileBin(Path directory) {
        System.setProperty(MOBILE_BIN_PROPERTY, directory.toString());
    }
}
