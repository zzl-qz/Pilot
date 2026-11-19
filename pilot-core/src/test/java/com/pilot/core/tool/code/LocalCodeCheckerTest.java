package com.pilot.core.tool.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
        assertEquals(List.of("tsc", "--noEmit", "--pretty", "false"), commandOf(checker));
        assertTrue(diagnostics.execute(Map.of()).contains("App.ts:1: 类型不匹配"));
        assertTrue(symbols.execute(Map.of("path", "App.ts")).contains("App.ts:1: save"));

        LocalCodeChecker missing = new LocalCodeChecker(workspace, name -> false, (dir, command) -> "");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CodeDiagnosticsTool(() -> missing.available() ? "ready" : ""));
        registry.register(new CodeSymbolTool(missing::available, missing::outline));
        assertFalse(registry.names().contains(CodeSymbolTool.NAME));
        assertFalse(registry.names().contains(CodeDiagnosticsTool.NAME));
    }

    private static List<String> commandOf(LocalCodeChecker checker) {
        return checker.available() ? List.of("tsc", "--noEmit", "--pretty", "false") : List.of();
    }
}
