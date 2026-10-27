package com.pilot.server.multi;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.server.config.PilotDataDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxStoreTest {

    private static final String ORIGINAL = "a\nb\nc\n";

    @TempDir
    Path workspace;

    @TempDir
    Path dataDir;

    private SandboxStore store() {
        return new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(dataDir.toString()));
    }

    private DiffSandbox proposal() throws IOException {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/A.java"), ORIGINAL);
        var sandbox = new DiffSandbox("sbx_1", "agent_1", "T1", "");
        sandbox.add("src/A.java", ORIGINAL, "a\nCHANGED\nc\n", true, RiskLevel.MEDIUM);
        return sandbox;
    }

    @Test
    void acceptWritesSandboxContentToWorkspace() throws IOException {
        var store = store();
        store.add("ses_1", proposal());
        assertEquals(ORIGINAL, Files.readString(workspace.resolve("src/A.java")), "接受之前不能落盘");

        SandboxStore.ApplyReport report = store.accept("ses_1", "sbx_1", workspace);

        assertTrue(report.ok(), report.message());
        assertEquals("a\nCHANGED\nc\n", Files.readString(workspace.resolve("src/A.java")));
        assertEquals(1, store.diffs("ses_1").size());
    }

    @Test
    void acceptRefusesToOverwriteAFileThatChangedMeanwhile() throws IOException {
        var store = store();
        store.add("ses_1", proposal());
        Files.writeString(workspace.resolve("src/A.java"), "a\n用户改过了\nc\n");

        SandboxStore.ApplyReport report = store.accept("ses_1", "sbx_1", workspace);

        assertFalse(report.ok());
        assertTrue(report.failed().get(0).contains("已被修改"), report.failed().toString());
        assertEquals("a\n用户改过了\nc\n", Files.readString(workspace.resolve("src/A.java")));
    }

    @Test
    void rejectLeavesWorkspaceUntouched() throws IOException {
        var store = store();
        store.add("ses_1", proposal());

        assertTrue(store.reject("ses_1", "sbx_1"));
        store.accept("ses_1", "sbx_1", workspace);

        assertEquals(ORIGINAL, Files.readString(workspace.resolve("src/A.java")));
        assertTrue(store.diffs("ses_1").get(0).status().equals("rejected"));
    }

    @Test
    void refusesToWriteOutsideWorkspace() throws IOException {
        var store = store();
        var sandbox = new DiffSandbox("sbx_2", "agent_2", "T2", "");
        sandbox.add("../../escape.txt", null, "boom", false, RiskLevel.MEDIUM);
        store.add("ses_1", sandbox);

        SandboxStore.ApplyReport report = store.accept("ses_1", "sbx_2", workspace);

        assertFalse(report.ok());
        assertFalse(Files.exists(workspace.getParent().resolve("escape.txt")));
    }
}
