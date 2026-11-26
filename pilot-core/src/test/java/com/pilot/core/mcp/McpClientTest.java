package com.pilot.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpClientTest {
    @TempDir
    Path directory;

    @Test
    void resolvesUvxExeBeforeCmdOnWindowsPath() throws Exception {
        Path executable = Files.createFile(directory.resolve("uvx.exe"));
        Files.createFile(directory.resolve("uvx.cmd"));
        assertEquals(executable.toString(), McpClient.resolveCommand("uvx", true, directory.toString()));
        assertEquals("uvx", McpClient.resolveCommand("uvx", false, directory.toString()));
    }

    @Test
    void fallsBackToUvxCmdThenBareCommandOnWindows() throws Exception {
        Path cmd = Files.createFile(directory.resolve("uvx.cmd"));
        assertEquals(cmd.toString(), McpClient.resolveCommand("uvx", true, directory.toString()));
        Files.delete(cmd);
        assertEquals("uvx", McpClient.resolveCommand("uvx", true, directory.toString()));
        assertEquals("npx.cmd", McpClient.resolveCommand("npx", true, directory.toString()));
    }
}
