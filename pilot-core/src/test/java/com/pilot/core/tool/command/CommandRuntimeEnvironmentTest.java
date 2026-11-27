package com.pilot.core.tool.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandRuntimeEnvironmentTest {
    @TempDir
    Path workspace;

    @Test
    void filtersCommandEnvironmentAndPrioritizesBundledRuntime() throws Exception {
        Path bin = Files.createDirectory(workspace.resolve("runtime-bin"));
        Path python = Files.createFile(bin.resolve(isWindows() ? "python3.cmd" : "python3"));
        String previous = System.getProperty("pilot.runtime.bin");
        System.setProperty("pilot.runtime.bin", bin.toString());
        try {
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("PATH", workspace.toString());
            environment.put("HOME", "/Users/dev");
            environment.put("USER", "dev");
            environment.put("OPENAI_API_KEY", "sk-test");
            environment.put("GITHUB_TOKEN", "ghp_xxx");

            CommandRuntimeEnvironment.prepareEnvironment(environment);

            assertFalse(environment.containsKey("OPENAI_API_KEY"));
            assertFalse(environment.containsKey("GITHUB_TOKEN"));
            assertEquals("/Users/dev", environment.get("HOME"));
            assertEquals("dev", environment.get("USER"));
            assertTrue(environment.get("PATH").startsWith(bin.toString()), environment.get("PATH"));
            assertFalse(environment.get("JAVA_HOME").isBlank());
            assertEquals("cat", environment.get("PAGER"));
            assertEquals(python.toAbsolutePath(),
                CommandRuntimeEnvironment.resolveOnPath("python3", workspace.toString(), isWindows()).toAbsolutePath());
        } finally {
            if (previous == null) {
                System.clearProperty("pilot.runtime.bin");
            } else {
                System.setProperty("pilot.runtime.bin", previous);
            }
        }
    }

    @Test
    void resolvesPathEntriesAndWindowsSuffixesInPriorityOrder() throws Exception {
        Path first = Files.createDirectory(workspace.resolve("中文 tools"));
        Path second = Files.createDirectory(workspace.resolve("fallback"));
        Path unix = Files.createFile(first.resolve("tool"));
        Path batch = Files.createFile(first.resolve("tool.bat"));
        Path laterExe = Files.createFile(second.resolve("tool.exe"));
        String path = String.join(File.pathSeparator, "bad\0path", " " + first + " ", second.toString());

        assertEquals(unix, CommandRuntimeEnvironment.resolveOnPath("tool", path, false));
        assertEquals(batch, CommandRuntimeEnvironment.resolveOnPath("tool", path, true));
        Path cmd = Files.createFile(first.resolve("tool.cmd"));
        assertEquals(cmd, CommandRuntimeEnvironment.resolveOnPath("tool", path, true));
        Files.delete(cmd);
        Files.delete(batch);
        assertEquals(laterExe, CommandRuntimeEnvironment.resolveOnPath("tool", path, true));
        assertNull(CommandRuntimeEnvironment.resolveOnPath("absent-command", workspace.toString(), isWindows()));
    }

    @Test
    void findsOnlyPlatformAppropriateWorkspaceWrappers() throws Exception {
        Path unix = Files.createFile(workspace.resolve("mvnw"));
        Path cmd = Files.createFile(workspace.resolve("mvnw.cmd"));
        Path bat = Files.createFile(workspace.resolve("mvnw.bat"));

        assertEquals(unix, CommandRuntimeEnvironment.findWrapper(workspace, "mvnw", false));
        assertEquals(cmd, CommandRuntimeEnvironment.findWrapper(workspace, "mvnw", true));
        Files.delete(cmd);
        assertEquals(bat, CommandRuntimeEnvironment.findWrapper(workspace, "mvnw", true));
        assertNull(CommandRuntimeEnvironment.findWrapper(workspace, "gradlew", true));
    }

    @Test
    void preservesExistingValidJavaHomeAndSkipsJreOnlyFallback() throws Exception {
        boolean win = isWindows();
        String javaBin = win ? "java.exe" : "java";
        String javacBin = win ? "javac.exe" : "javac";

        Path userJdk = workspace.resolve("jdk-17");
        Files.createDirectories(userJdk.resolve("bin"));
        Files.createFile(userJdk.resolve("bin").resolve(javaBin));

        Path pilotJdk = workspace.resolve("pilot-jdk-25");
        Files.createDirectories(pilotJdk.resolve("bin"));
        Files.createFile(pilotJdk.resolve("bin").resolve(javaBin));
        Files.createFile(pilotJdk.resolve("bin").resolve(javacBin));

        Path pilotJreOnly = workspace.resolve("pilot-jre-25");
        Files.createDirectories(pilotJreOnly.resolve("bin"));
        Files.createFile(pilotJreOnly.resolve("bin").resolve(javaBin));

        // 1. 用户已配置有效 JAVA_HOME 时必须保留，不被 Pilot 的 JDK 25 覆盖或抢占 PATH
        Map<String, String> withUserJdk = new LinkedHashMap<>();
        withUserJdk.put("JAVA_HOME", userJdk.toString());
        withUserJdk.put("PATH", userJdk.resolve("bin").toString());
        CommandRuntimeEnvironment.prepareEnvironment(withUserJdk, pilotJdk.toString(), win);
        assertEquals(userJdk.toString(), withUserJdk.get("JAVA_HOME"));
        assertFalse(withUserJdk.get("PATH").contains(pilotJdk.toString()), withUserJdk.get("PATH"));

        // 2. 无 JAVA_HOME 且 Pilot 自身仅运行在无 javac 的 jlink JRE 上时，不得注入残缺 JAVA_HOME
        Map<String, String> withJreOnly = new LinkedHashMap<>();
        withJreOnly.put("PATH", workspace.toString());
        CommandRuntimeEnvironment.prepareEnvironment(withJreOnly, pilotJreOnly.toString(), win);
        assertNull(withJreOnly.get("JAVA_HOME"));

        // 3. 既有 JAVA_HOME 指向不存在目录时，回退到完整的 Pilot JDK
        Map<String, String> withBrokenHome = new LinkedHashMap<>();
        withBrokenHome.put("JAVA_HOME", workspace.resolve("missing-jdk").toString());
        withBrokenHome.put("PATH", workspace.toString());
        CommandRuntimeEnvironment.prepareEnvironment(withBrokenHome, pilotJdk.toString(), win);
        assertEquals(pilotJdk.toString(), withBrokenHome.get("JAVA_HOME"));
        assertTrue(withBrokenHome.get("PATH").contains(pilotJdk.resolve("bin").toString()), withBrokenHome.get("PATH"));

        // 4. 跨平台模拟 Windows 大小写不敏感环境变量名（Java_Home / Path）与 .exe 可执行文件校验
        Path winJdk = workspace.resolve("win-jdk-17");
        Files.createDirectories(winJdk.resolve("bin"));
        Files.createFile(winJdk.resolve("bin").resolve("java.exe"));
        Path winPilotJdk = workspace.resolve("win-pilot-jdk-25");
        Files.createDirectories(winPilotJdk.resolve("bin"));
        Files.createFile(winPilotJdk.resolve("bin").resolve("java.exe"));
        Files.createFile(winPilotJdk.resolve("bin").resolve("javac.exe"));
        Map<String, String> winEnv = new LinkedHashMap<>();
        winEnv.put("Java_Home", winJdk.toString());
        winEnv.put("Path", winJdk.resolve("bin").toString());
        CommandRuntimeEnvironment.prepareEnvironment(winEnv, winPilotJdk.toString(), true);
        assertEquals(winJdk.toString(), winEnv.get("Java_Home"));
        assertFalse(winEnv.containsKey("JAVA_HOME"));
        assertFalse(winEnv.get("Path").contains(winPilotJdk.toString()), winEnv.get("Path"));
    }

    @Test
    void detectsMissingWrapperJarOnlyForJarBasedWrappers() throws Exception {
        Path mvnw = Files.writeString(workspace.resolve("mvnw"),
            "#!/bin/sh\nexec java -cp .mvn/wrapper/maven-wrapper.jar org.apache.maven.wrapper.MavenWrapperMain \"$@\"\n");
        assertTrue(CommandRuntimeEnvironment.isWrapperMissingJar(workspace, mvnw, "mvnw"));

        Files.createDirectories(workspace.resolve(".mvn/wrapper"));
        Files.writeString(workspace.resolve(".mvn/wrapper/maven-wrapper.jar"), "jar");
        assertFalse(CommandRuntimeEnvironment.isWrapperMissingJar(workspace, mvnw, "mvnw"));

        Path customMvnw = Files.writeString(workspace.resolve("mvnw"), "#!/bin/sh\necho only-script\n");
        Files.delete(workspace.resolve(".mvn/wrapper/maven-wrapper.jar"));
        assertFalse(CommandRuntimeEnvironment.isWrapperMissingJar(workspace, customMvnw, "mvnw"));

        Path gradlew = Files.writeString(workspace.resolve("gradlew"),
            "#!/bin/sh\nCLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar\n");
        assertTrue(CommandRuntimeEnvironment.isWrapperMissingJar(workspace, gradlew, "gradlew"));
        Files.createDirectories(workspace.resolve("gradle/wrapper"));
        Files.writeString(workspace.resolve("gradle/wrapper/gradle-wrapper.jar"), "jar");
        assertFalse(CommandRuntimeEnvironment.isWrapperMissingJar(workspace, gradlew, "gradlew"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
