package com.pilot.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class RuntimeMirrorsTest {
    @Test
    void bindsEnvironmentOverridesAndUsesDefaultRegistryOtherwise() {
        assertEquals("https://registry.npmmirror.com",
            RuntimeMirrors.defaults().npmAttempts(Map.of()).getLast().get("npm_config_registry"));
        Map<String,
            Object> variables = Map.of("PILOT_RUNTIME_NPM_FALLBACK_REGISTRY", "https://internal.test/npm",
                "PILOT_RUNTIME_CHROMIUM_FALLBACK_MIRROR", "http://internal.test:8080/chromium/",
                "PILOT_RUNTIME_ELECTRON_FALLBACK_MIRROR", "https://internal.test/electron");
        new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().getPropertySources()
            .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)))
            .withUserConfiguration(RuntimeMirrors.class).run(context -> {
                var mirrors = context.getBean(RuntimeMirrors.class);
                assertEquals(List.of(Map.of(), Map.of("npm_config_registry", "https://internal.test/npm",
                    "ELECTRON_MIRROR", "https://internal.test/electron/")),
                    mirrors.npmAttempts(mirrors.electronEnvironment()));
                assertEquals("http://internal.test:8080/chromium",
                    mirrors.chromiumEnvironment().get("PLAYWRIGHT_CHROMIUM_DOWNLOAD_HOST"));
            });
    }

    @Test
    void overridesAliasesOnlyForTheFallbackProcess() {
        var env = new LinkedHashMap<>(Map.of("NPM_CONFIG_REGISTRY", "primary", "npm_config_electron_mirror",
            "electron-primary", "HTTPS_PROXY", "proxy"));
        var mirrors = RuntimeMirrors.defaults();
        var attempts = mirrors.npmAttempts(mirrors.electronEnvironment());
        RuntimeMirrors.applyEnvironment(env, attempts.getFirst());
        assertEquals("primary", env.get("NPM_CONFIG_REGISTRY"));
        RuntimeMirrors.applyEnvironment(env, attempts.getLast());
        assertFalse(env.containsKey("NPM_CONFIG_REGISTRY"));
        assertFalse(env.containsKey("npm_config_electron_mirror"));
        assertEquals("proxy", env.get("HTTPS_PROXY"));
        assertEquals(attempts.getLast().get("npm_config_registry"), env.get("npm_config_registry"));
    }

    @Test
    void rejectsUnsupportedOrSensitiveMirrorAddresses() {
        for (String bad : List.of(" ", "file:///tmp/npm", "https://token@mirror.test", "https://mirror.test/?key=token",
            "https://mirror.test/#token", "http://mirror.test:65536")) {
            var error = assertThrows(IllegalArgumentException.class,
                () -> new RuntimeMirrors(bad, "https://mirror.test/chromium", "https://mirror.test/electron"));
            assertTrue(error.getMessage().contains("pilot.runtime.npm-fallback-registry"));
            assertFalse(error.getMessage().contains("token"));
        }
    }

    @Test
    void windowsNpmUsesNodeCliOrFallsBackToFixedBatchArguments(@TempDir Path root) throws Exception {
        Path bin = Files.createDirectory(root.resolve("中文 with spaces"));
        Path npm = Files.createFile(bin.resolve("npm.cmd"));
        assertEquals(List.of("cmd.exe", "/d", "/c", "\"" + npm + "\" install --no-audit --no-fund --omit=dev"),
            NpmInstall.command(npm, List.of("--omit=dev"), true));
        Path node = Files.createFile(bin.resolve("node.exe"));
        Path cli = bin.resolve("node_modules/npm/bin/npm-cli.js");
        Files.createDirectories(cli.getParent());
        Files.createFile(cli);
        assertEquals(List.of(node.toString(), cli.toString(), "install", "--no-audit", "--no-fund", "--omit=dev"),
            NpmInstall.command(npm, List.of("--omit=dev"), true));
    }
}
