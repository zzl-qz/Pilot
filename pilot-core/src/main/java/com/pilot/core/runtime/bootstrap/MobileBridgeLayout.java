package com.pilot.core.runtime.bootstrap;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** 源码准备与服务端启动共用的手机连接桥布局。 */
public final class MobileBridgeLayout {
    public static final String MAIN_CLASS = "com.pilot.mobile.bridge.MainKt";

    private static final Path PACKAGED_LIBRARIES = Path.of("mobile-bridge", "lib");
    private static final Path SOURCE_BRIDGE = Path.of("pilot-mobile", "bridge");
    private static final Path BUILT_LIBRARIES = SOURCE_BRIDGE.resolve(Path.of("build", "install", "bridge", "lib"));

    private MobileBridgeLayout() {
    }

    public static boolean hasBridge(Path root) {
        return Files.isDirectory(root.resolve(SOURCE_BRIDGE).resolve("src"))
            || Files.isDirectory(root.resolve(PACKAGED_LIBRARIES));
    }

    /** 同一个根目录中优先使用安装包随附的库。 */
    public static Path libraries(Path root) {
        Path packaged = root.resolve(PACKAGED_LIBRARIES);
        return Files.isDirectory(packaged) ? packaged : root.resolve(BUILT_LIBRARIES);
    }

    /** 通配符交给 Java launcher 展开，Windows 的 Path 接口不能表示星号。 */
    public static String classpath(Path libraries) {
        return libraries + File.separator + "*";
    }
}
