package com.pilot.core.runtime.bootstrap;

import java.util.Locale;

/** 使用实际 JVM 架构，Windows ARM64 的 x64 兼容进程按 x64 检测和验证。 */
public record BootstrapPlatform(String os, String arch) {
    public static BootstrapPlatform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static BootstrapPlatform detect(String name, String machine) {
        String normalized = name.toLowerCase(Locale.ROOT);
        String os = normalized.contains("mac") || normalized.contains("darwin") ? "mac"
            : normalized.startsWith("windows") ? "windows" : normalized.contains("linux") ? "linux" : "unsupported";
        String arch = switch (machine.toLowerCase(Locale.ROOT)) {
            case "x86_64", "amd64", "x64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default -> "unsupported";
        };
        return new BootstrapPlatform(os, arch);
    }

    public boolean windows() {
        return "windows".equals(os);
    }

    public boolean mobileSupported() {
        return "linux".equals(os) && !"unsupported".equals(arch) || "mac".equals(os) && "aarch64".equals(arch)
            || windows() && "x64".equals(arch);
    }

    /** 只选择可尝试的兼容后端，最终是否可用仍必须由实际子进程探测确认。 */
    public BootstrapPlatform bridgeCandidate(boolean appleSiliconHost) {
        if (windows() && "aarch64".equals(arch)) {
            return new BootstrapPlatform("windows", "x64");
        }
        if ("mac".equals(os) && "x64".equals(arch) && appleSiliconHost) {
            return new BootstrapPlatform("mac", "aarch64");
        }
        return this;
    }

    public String key() {
        return os + "." + arch;
    }

    public String executable(String name) {
        return windows() ? name + ".exe" : name;
    }
}
