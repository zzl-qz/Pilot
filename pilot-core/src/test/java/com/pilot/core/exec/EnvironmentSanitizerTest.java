package com.pilot.core.exec;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentSanitizerTest {

    private static Map<String, String> env() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("PATH", "/usr/bin");
        values.put("HOME", "/home/dev");
        values.put("OPENAI_API_KEY", "sk-secret-value");
        values.put("AWS_SECRET_ACCESS_KEY", "aws-secret");
        values.put("GITHUB_TOKEN", "ghp_xxx");
        values.put("DB_PASSWORD", "hunter2");
        values.put("JAVA_HOME", "/opt/jdk");
        return values;
    }

    @Test
    void inheritsOnlyNonSecretNames() {
        Map<String, String> inherited = EnvironmentSanitizer.defaults().inherit(env());

        assertTrue(inherited.containsKey("PATH"));
        assertTrue(inherited.containsKey("HOME"));
        assertTrue(inherited.containsKey("JAVA_HOME"));
        assertFalse(inherited.containsKey("OPENAI_API_KEY"));
        assertFalse(inherited.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(inherited.containsKey("GITHUB_TOKEN"));
        assertFalse(inherited.containsKey("DB_PASSWORD"));
    }

    @Test
    void explicitValuesStillGoThroughTheFilter() {
        Map<String, String> merged = EnvironmentSanitizer.defaults().merge(env(),
                Map.of("MY_SECRET", "x", "PILOT_SAFE_ENV", "ok"));

        assertEquals("ok", merged.get("PILOT_SAFE_ENV"));
        assertFalse(merged.containsKey("MY_SECRET"), "显式传入不能绕过凭据过滤");
    }

    @Test
    void canExplicitlyAllowExtraNames() {
        EnvironmentSanitizer sanitizer = new EnvironmentSanitizer(java.util.Set.of("CUSTOM_FLAG"));
        assertTrue(sanitizer.allowed("CUSTOM_FLAG"));
        assertFalse(sanitizer.allowed("CUSTOM_TOKEN"));
    }

    @Test
    void reportsRejectedNamesWithoutValues() {
        assertEquals(java.util.List.of("AWS_SECRET_ACCESS_KEY", "DB_PASSWORD", "GITHUB_TOKEN", "OPENAI_API_KEY"),
                EnvironmentSanitizer.defaults().rejectedNames(env()).stream().sorted().toList());
    }
}
