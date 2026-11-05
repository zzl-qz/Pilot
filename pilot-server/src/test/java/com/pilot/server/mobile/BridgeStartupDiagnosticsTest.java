package com.pilot.server.mobile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BridgeStartupDiagnosticsTest {
    @Test
    void windowsNativeFailureExplainsRuntimeAndDoesNotExposeOutput() {
        var diagnostics = new BridgeStartupDiagnostics("Windows 11");
        diagnostics.accept("java.lang.UnsatisfiedLinkError: Unable to load library 'iroh_ffi' at C:\\private\\token");
        diagnostics.accept("java.lang.NoClassDefFoundError: Could not initialize class computer.iroh.Native");
        assertTrue(diagnostics.message().contains("Microsoft Visual C++ x64"));
        assertFalse(diagnostics.message().contains("private"));
        assertFalse(diagnostics.message().contains("token"));
    }

    @Test
    void nativeGuidanceIsNotWindowsSpecificOnOtherPlatforms() {
        for (String platform : new String[] {"Mac OS X", "Linux"}) {
            var diagnostics = new BridgeStartupDiagnostics(platform);
            diagnostics.accept("java.lang.UnsatisfiedLinkError: Unable to load library");
            assertTrue(diagnostics.message().contains("系统架构"));
            assertFalse(diagnostics.message().contains("Visual C++"));
        }
    }

    @Test
    void missingComponentVersionAndPermissionHaveActionableMessages() {
        var missing = new BridgeStartupDiagnostics("Windows 10");
        missing.accept("Error: Could not find or load main class com.pilot.mobile.bridge.MainKt");
        assertTrue(missing.message().contains("node scripts/mobile.mjs build"));
        var java = new BridgeStartupDiagnostics("Windows 10");
        java.accept("java.lang.UnsupportedClassVersionError: class version 69");
        java.accept("Caused by: java.lang.ClassNotFoundException: MainKt");
        assertTrue(java.message().contains("Java 25"));
        var permission = new BridgeStartupDiagnostics("Windows 11");
        permission.accept("java.lang.UnsatisfiedLinkError");
        permission.accept("java.lang.NoClassDefFoundError: Could not initialize class computer.iroh.Native");
        permission.accept("java.nio.file.AccessDeniedException: C:\\private\\secret");
        assertTrue(permission.message().contains("目录权限"));
        assertFalse(permission.message().contains("secret"));
    }

    @Test
    void unknownOutputKeepsFixedMessageWithoutEchoingCredentials() {
        var diagnostics = new BridgeStartupDiagnostics("Windows 11");
        diagnostics.accept("PILOT_MOBILE_ADMIN http://127.0.0.1:1234/#private-secret");
        assertTrue(diagnostics.message().contains("未能启动"));
        assertFalse(diagnostics.message().contains("private-secret"));
    }
}
