package com.pilot.core.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyCachesTest {
    @TempDir
    Path project;

    @Test
    void missingCachesStayWithinTheProjectWithoutChangingTheParentEnvironment() {
        var inherited = Map.of("PATH", "system-bin");
        var env = DependencyCaches.environment(project, inherited);
        assertFalse(env.containsKey("GRADLE_USER_HOME"));
        assertEquals(project.resolve(".pilot/cache/npm").toString(), env.get("npm_config_cache"));
        assertEquals(project.resolve(".pilot/cache/electron").toString(), env.get("electron_config_cache"));
        assertEquals(project.resolve(".pilot/cache/maven").toString(), env.get("PILOT_MAVEN_REPOSITORY"));
        assertFalse(inherited.containsKey("GRADLE_USER_HOME"));
    }

    @Test
    void explicitLocationsAndCaseInsensitiveToolVariablesArePreserved() {
        var env = DependencyCaches.environment(project,
            Map.of("PILOT_DEPENDENCY_CACHE", "中文 cache & 100%", "NPM_CONFIG_CACHE", "custom-npm", "GRADLE_USER_HOME",
                "custom-gradle", "electron_config_cache", "custom-electron", "PILOT_MAVEN_REPOSITORY", "custom-maven"));
        assertEquals("custom-npm", env.get("NPM_CONFIG_CACHE"));
        assertFalse(env.containsKey("npm_config_cache"));
        assertEquals("custom-gradle", env.get("GRADLE_USER_HOME"));
        assertEquals("custom-electron", env.get("electron_config_cache"));
        assertEquals("custom-maven", env.get("PILOT_MAVEN_REPOSITORY"));
        var defaults = DependencyCaches.environment(project, Map.of("PILOT_DEPENDENCY_CACHE", "中文 cache & 100%"));
        assertEquals(project.resolve("中文 cache & 100%/maven").toString(), defaults.get("PILOT_MAVEN_REPOSITORY"));
    }
}
