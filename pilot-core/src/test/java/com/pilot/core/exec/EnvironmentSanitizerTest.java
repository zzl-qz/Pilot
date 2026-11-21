package com.pilot.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

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
        Map<String, String> merged =
            EnvironmentSanitizer.defaults().merge(env(), Map.of("MY_SECRET", "x", "PILOT_SAFE_ENV", "ok"));

        assertEquals("ok", merged.get("PILOT_SAFE_ENV"));
        assertFalse(merged.containsKey("MY_SECRET"), "显式传入不能绕过凭据过滤");
    }

    @Test
    void canExplicitlyAllowExtraNames() {
        EnvironmentSanitizer sanitizer = new EnvironmentSanitizer(Set.of("CUSTOM_FLAG"));
        assertTrue(sanitizer.allowed("CUSTOM_FLAG"));
        assertFalse(sanitizer.allowed("CUSTOM_TOKEN"));
    }

    @Test
    void reportsRejectedNamesWithoutValues() {
        assertEquals(List.of("AWS_SECRET_ACCESS_KEY", "DB_PASSWORD", "GITHUB_TOKEN", "OPENAI_API_KEY"),
            EnvironmentSanitizer.defaults().rejectedNames(env()).stream().sorted().toList());
    }

    @Test
    void keepsWindowsDirectoryVariablesNeededToLocateInstalledBrowsers() {
        Map<String, String> host = new LinkedHashMap<>();
        host.put("ProgramFiles", "C:\\Program Files");
        host.put("ProgramFiles(x86)", "C:\\Program Files (x86)");
        host.put("ProgramW6432", "C:\\Program Files");
        host.put("ProgramData", "C:\\ProgramData");
        host.put("LOCALAPPDATA", "C:\\Users\\dev\\AppData\\Local");
        host.put("APPDATA", "C:\\Users\\dev\\AppData\\Roaming");
        host.put("SystemDrive", "C:");
        host.put("PROCESSOR_ARCHITECTURE", "AMD64");

        Map<String, String> inherited = EnvironmentSanitizer.defaults().inherit(host);

        // %ProgramFiles% 缺失会让 Playwright 拼出 undefined\Program Files\... 并误报浏览器未安装。
        assertEquals("C:\\Program Files", inherited.get("ProgramFiles"));
        assertEquals("C:\\Program Files (x86)", inherited.get("ProgramFiles(x86)"));
        assertEquals("C:\\Users\\dev\\AppData\\Local", inherited.get("LOCALAPPDATA"));
        assertEquals("C:", inherited.get("SystemDrive"));
    }

    @Test
    void directoryAllowListDoesNotLetCredentialNamesThrough() {
        Map<String, String> host = new LinkedHashMap<>();
        host.put("LOCALAPPDATA", "C:\\Users\\dev\\AppData\\Local");
        host.put("APPDATA_TOKEN", "secret");
        host.put("PROGRAMDATA_PASSWORD", "secret");
        host.put("LOCALAPPDATA_CREDENTIAL", "secret");

        Map<String, String> inherited = EnvironmentSanitizer.defaults().inherit(host);

        assertTrue(inherited.containsKey("LOCALAPPDATA"));
        assertFalse(inherited.containsKey("APPDATA_TOKEN"));
        assertFalse(inherited.containsKey("PROGRAMDATA_PASSWORD"));
        assertFalse(inherited.containsKey("LOCALAPPDATA_CREDENTIAL"));
    }
}
