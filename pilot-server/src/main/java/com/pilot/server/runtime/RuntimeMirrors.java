package com.pilot.server.runtime;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 浏览器与桌面运行时的备用下载源。首次安装继续使用进程继承的工具配置。 */
@Component
public final class RuntimeMirrors {
    private static final String DEFAULT_NPM = "https://registry.npmmirror.com";
    private static final String DEFAULT_CHROMIUM = "https://npmmirror.com/mirrors/playwright";
    private static final String DEFAULT_ELECTRON = "https://npmmirror.com/mirrors/electron/";

    private final Map<String, String> npm;
    private final Map<String, String> chromium;
    private final Map<String, String> electron;

    public RuntimeMirrors(@Value("${pilot.runtime.npm-fallback-registry:" + DEFAULT_NPM + "}") String npmRegistry,
        @Value("${pilot.runtime.chromium-fallback-mirror:" + DEFAULT_CHROMIUM + "}") String chromiumMirror,
        @Value("${pilot.runtime.electron-fallback-mirror:" + DEFAULT_ELECTRON + "}") String electronMirror) {
        String registry = endpoint("pilot.runtime.npm-fallback-registry", npmRegistry);
        npm = Map.of("npm_config_registry", registry);
        String chromiumUrl = endpoint("pilot.runtime.chromium-fallback-mirror", chromiumMirror).replaceFirst("/+$", "");
        chromium = Map.of("PLAYWRIGHT_DOWNLOAD_HOST", chromiumUrl, "PLAYWRIGHT_CHROMIUM_DOWNLOAD_HOST", chromiumUrl);
        String electronUrl = endpoint("pilot.runtime.electron-fallback-mirror", electronMirror);
        electron = Map.of("ELECTRON_MIRROR", electronUrl.endsWith("/") ? electronUrl : electronUrl + "/");
    }

    public static RuntimeMirrors defaults() {
        return new RuntimeMirrors(DEFAULT_NPM, DEFAULT_CHROMIUM, DEFAULT_ELECTRON);
    }

    public Map<String, String> chromiumEnvironment() {
        return chromium;
    }

    public Map<String, String> electronEnvironment() {
        return electron;
    }

    public List<Map<String, String>> npmAttempts(Map<String, String> binaryMirror) {
        var fallback = new LinkedHashMap<>(npm);
        fallback.putAll(binaryMirror);
        return List.of(Map.of(), Map.copyOf(fallback));
    }

    /** npm 同时接受两种大小写。覆盖已有别名，避免不同平台或父进程的变量盖过回退源。 */
    public static void applyEnvironment(Map<String, String> inherited, Map<String, String> overrides) {
        if (overrides.containsKey("npm_config_registry")) {
            inherited.keySet().removeIf("npm_config_registry"::equalsIgnoreCase);
        }
        if (overrides.containsKey("ELECTRON_MIRROR")) {
            inherited.keySet().removeIf(name -> "npm_config_electron_mirror".equalsIgnoreCase(name)
                || "npm_package_config_electron_mirror".equalsIgnoreCase(name));
        }
        inherited.putAll(overrides);
    }

    private static String endpoint(String property, String configured) {
        String value = configured.strip();
        try {
            URI uri = URI.create(value);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && uri.getPort() != 0 && uri.getPort() <= 65535) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {
            // 用配置名定位问题，避免把意外填入的凭据写入日志。
        }
        throw new IllegalArgumentException(property + " 必须是无凭据、查询参数和片段的 HTTP(S) 地址");
    }
}
