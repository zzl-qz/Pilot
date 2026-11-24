package com.pilot.core.tool.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.tool.ToolRegistry;

class LocalCodeCheckerTest {
    @TempDir
    Path workspace;

    @Test
    void runsTheInstalledCheckerAndHidesSymbolsWithoutOne() throws Exception {
        Files.writeString(workspace.resolve("tsconfig.json"), "{}");
        Files.writeString(workspace.resolve("App.ts"), "function save() {}\n");
        LocalCodeChecker checker =
            new LocalCodeChecker(workspace, "tsc"::equals, (dir, command) -> "App.ts:1: 类型不匹配\nnoise\n");
        CodeDiagnosticsTool diagnostics =
            new CodeDiagnosticsTool(() -> checker.available() ? "ready" : "", checker::diagnose);
        CodeSymbolTool symbols = new CodeSymbolTool(checker::available, checker::outline);

        assertTrue(checker.available());
        assertEquals(List.of("tsc", "--noEmit", "--pretty", "false"), checker.resolvePlanForTest("").command());
        assertTrue(diagnostics.execute(Map.of()).contains("App.ts:1: 类型不匹配"));
        assertTrue(symbols.execute(Map.of("path", "App.ts")).contains("App.ts:1: save"));

        LocalCodeChecker missing = new LocalCodeChecker(workspace, name -> false, (dir, command) -> "");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CodeDiagnosticsTool(() -> missing.available() ? "ready" : ""));
        registry.register(new CodeSymbolTool(missing::available, missing::outline));
        assertFalse(registry.names().contains(CodeSymbolTool.NAME));
        assertFalse(registry.names().contains(CodeDiagnosticsTool.NAME));
    }

    @Test
    void mavenMultiModuleRunsTestCompileAndParsesErrors() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Path moduleFile = workspace.resolve("pilot-core/src/main/java/Foo.java");
        Files.createDirectories(moduleFile.getParent());
        Files.writeString(workspace.resolve("pilot-core/pom.xml"), "<project/>");
        Files.writeString(moduleFile, "class Foo {}\n");

        List<String> captured = new ArrayList<>();
        String mavenOut = "[INFO] Compiling 1 source file\n"
            + "[ERROR] " + moduleFile.toAbsolutePath() + ":[12,9] cannot find symbol\n"
            + "  symbol:   variable bar\n"
            + "[ERROR] BUILD FAILURE\n";
        LocalCodeChecker checker = new LocalCodeChecker(workspace, "mvn"::equals, (dir, cmd) -> {
            captured.addAll(cmd);
            return mavenOut;
        });

        CodeDiagnosticsTool tool = new CodeDiagnosticsTool(() -> checker.available() ? "ready" : "", checker::diagnose);
        String result = tool.execute(Map.of("path", "pilot-core/src/main/java/Foo.java"));

        assertEquals(List.of("mvn", "test-compile", "-B", "-pl", "pilot-core", "-am"), captured);
        assertTrue(result.contains("pilot-core/src/main/java/Foo.java:12: cannot find symbol (symbol:   variable bar)"),
            result);
    }

    @Test
    void monorepoTypeScriptSubdirUsesLocalTscAndNormalizesParenDiagnostics() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        Path frontend = workspace.resolve("pilot-web/frontend");
        Files.createDirectories(frontend.resolve("src"));
        Files.createDirectories(frontend.resolve("node_modules/.bin"));
        Files.writeString(frontend.resolve("tsconfig.json"), "{}");
        Files.writeString(frontend.resolve("node_modules/.bin/tsc"), "#!/bin/sh\n");
        Files.writeString(frontend.resolve("src/App.tsx"), "export const x = 1;\n");
        Files.writeString(frontend.resolve("src/Other.tsx"), "export const y = 2;\n");

        List<Path> dirs = new ArrayList<>();
        LocalCodeChecker checker = new LocalCodeChecker(workspace, name -> false, (dir, cmd) -> {
            dirs.add(dir);
            return "src/App.tsx(8,3): error TS2322: Type 'string' is not assignable to type 'number'.\n"
                + "src/Other.tsx(2,1): error TS2304: Cannot find name 'foo'.\n";
        });

        assertTrue(checker.available());
        String appOnly = checker.diagnose("pilot-web/frontend/src/App.tsx");
        assertEquals(List.of(frontend.toAbsolutePath().normalize()), dirs);
        assertTrue(appOnly.contains("pilot-web/frontend/src/App.tsx:8: error TS2322"), appOnly);
        assertFalse(appOnly.contains("Other.tsx"), appOnly);

        Files.writeString(frontend.resolve("src/Clean.tsx"), "export const ok = true;\n");
        String cleanNote = checker.diagnose("pilot-web/frontend/src/Clean.tsx");
        assertTrue(cleanNote.startsWith("当前文件无诊断错误"), cleanNote);
        assertTrue(cleanNote.contains("pilot-web/frontend/src/App.tsx:8:"), cleanNote);
    }

    @Test
    void supportsRustGoPythonGradleAndRejectsEscapingPath() throws Exception {
        Files.writeString(workspace.resolve("Cargo.toml"), "[package]\nname='demo'\n");
        LocalCodeChecker rust = new LocalCodeChecker(workspace, "cargo"::equals, (dir, cmd) -> "");
        LocalCodeChecker.CheckPlan rustPlan = rust.resolvePlanForTest("");
        assertNotNull(rustPlan);
        assertEquals(List.of("cargo", "check", "--message-format=short"), rustPlan.command());
        assertEquals("", rust.diagnose(""));

        assertTrue(rust.diagnose("../outside.rs").startsWith("ERROR:"));
    }

    @Test
    void zeroErrorRunReportsCleanViaDiagnosticsTool() throws Exception {
        Files.writeString(workspace.resolve("go.mod"), "module example.com/demo\n");
        Files.writeString(workspace.resolve("main.go"), "package main\n");
        LocalCodeChecker goChecker = new LocalCodeChecker(workspace, "go"::equals, (dir, cmd) -> "");
        CodeDiagnosticsTool tool =
            new CodeDiagnosticsTool(() -> goChecker.available() ? "ready" : "", goChecker::diagnose);

        assertEquals(CodeDiagnosticsTool.NO_DIAGNOSTICS, tool.execute(Map.of("path", "main.go")));
    }

    @Test
    void parsesPyrightAndSupportsGradleWrapper() throws Exception {
        Files.writeString(workspace.resolve("pyproject.toml"), "[project]\nname='demo'\n");
        Files.writeString(workspace.resolve("app.py"), "x: int = 'bad'\n");
        LocalCodeChecker py = new LocalCodeChecker(workspace, "pyright"::equals,
            (dir, cmd) -> workspace.resolve("app.py") + ":1:10 - error: Type mismatch\n");
        String diag = py.diagnose("app.py");
        assertEquals("app.py:1: error: Type mismatch", diag);

        Path gradleDir = workspace.resolve("gradle-sub");
        Files.createDirectories(gradleDir);
        Files.writeString(gradleDir.resolve("build.gradle.kts"), "");
        LocalCodeChecker gradle = new LocalCodeChecker(gradleDir, "gradle"::equals, (dir, cmd) -> "");
        LocalCodeChecker.CheckPlan plan = gradle.resolvePlanForTest("");
        assertNotNull(plan);
        assertEquals(List.of("gradle", "compileTestJava", "--console=plain"), plan.command());
    }

    @Test
    void mavenNonLineBuildFailureReportsErrorInsteadOfCleanPass() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        LocalCodeChecker maven = new LocalCodeChecker(workspace, "mvn"::equals,
            (dir, cmd) -> "[ERROR] Failed to execute goal: dependency missing\n");
        String diag = maven.diagnose("");
        assertTrue(diag.startsWith("ERROR: Maven 检查失败"), diag);
        assertTrue(diag.contains("dependency missing"), diag);
    }
}
