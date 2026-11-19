package com.pilot.core.tool.code;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.tool.ToolRegistry;

class CodeDiagnosticsToolTest {
    @Test
    void reportsAtMostFiftyLines() {
        StringBuilder raw = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            raw.append("src/A.java:").append(i).append(": 错误\n");
        }
        String result = new CodeDiagnosticsTool(raw::toString).execute(java.util.Map.of());
        assertTrue(result.contains("src/A.java:1: 错误"), result);
        assertTrue(result.contains("已截断到 50 条"), result);
        assertFalse(result.contains("src/A.java:51:"));
    }

    @Test
    void missingCheckerIsHiddenUntilItExists() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CodeDiagnosticsTool(() -> ""));
        assertTrue(registry.names().isEmpty());
        assertFalse(registry.names().contains(CodeDiagnosticsTool.NAME));
    }

    @Test
    void presentCheckerCanBeEnabled() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CodeDiagnosticsTool(() -> "src/A.java:2: 缺少分号"));
        assertTrue(registry.names().isEmpty());
        registry.enable(List.of(CodeDiagnosticsTool.NAME));
        assertTrue(registry.names().contains(CodeDiagnosticsTool.NAME));
    }
}
