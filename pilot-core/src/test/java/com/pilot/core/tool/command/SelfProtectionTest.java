package com.pilot.core.tool.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class SelfProtectionTest {
    @Test
    void protectsWindowsExecutableNamesWithPathAndExtension() {
        for (String command : List.of("taskkill.exe", "C:\\Windows\\System32\\TASKKILL.EXE", "kill.cmd",
            "stop-process.bat")) {
            assertNotNull(SelfProtection.check(List.of(command)), command);
        }
    }
}
