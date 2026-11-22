package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.multi.DiffSandbox;
import com.pilot.core.multi.RiskLevel;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.server.multi.SandboxStore;
import com.pilot.server.multi.WorkspaceOverviewService;
import com.pilot.server.run.RunRegistry;
import com.pilot.server.store.PilotDataDir;

import tools.jackson.databind.json.JsonMapper;

class WorkspaceOverviewToolTest {
    private static final String ORIGINAL = "a\nb\nc\n";

    @TempDir
    Path workspace;

    @TempDir
    Path dataDir;

    @TempDir
    Path commandStorage;

    @Test
    void summarizesOtherSessionsWithoutTouchingFiles() throws IOException {
        SessionRepository sessions = new InMemorySessionRepository();
        sessions.create("ses_a", "会话 A", workspace.toString(), Instant.now());
        sessions.create("ses_b", "会话 B", workspace.toString(), Instant.now());
        SandboxStore sandboxes = sandboxes();
        sandboxes.add("ses_a", sandbox("sbx_a", "T1", "src/A.java"));
        sandboxes.add("ses_b", sandbox("sbx_b", "T2", "src/A.java"));
        WorkspaceOverviewTool tool = new WorkspaceOverviewTool(overviewService(sessions, sandboxes), workspace);

        String text = tool.execute(Map.of());

        assertTrue(text.contains("ses_a"));
        assertTrue(text.contains("ses_b"));
        assertTrue(text.contains("src/A.java"));
        assertEquals("workspace_overview", tool.name());
        assertTrue(tool.readOnly());
        assertFalse(text.contains(ORIGINAL), "只读汇总不应回传文件正文");
    }

    @Test
    void missingWorkspaceReturnsAnErrorInsteadOfThrowing() throws IOException {
        WorkspaceOverviewTool tool = new WorkspaceOverviewTool(
            overviewService(new InMemorySessionRepository(), sandboxes()), workspace.resolve("missing"));

        assertTrue(tool.execute(Map.of()).startsWith("ERROR: "));
    }

    private WorkspaceOverviewService overviewService(SessionRepository sessions, SandboxStore sandboxes) {
        return new WorkspaceOverviewService(sessions, sandboxes, new RunRegistry(),
            new CommandTaskManager(commandStorage, JsonMapper.builder().build()));
    }

    private SandboxStore sandboxes() {
        return new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(dataDir.toString()));
    }

    private DiffSandbox sandbox(String sandboxId, String taskId, String path) throws IOException {
        Path file = workspace.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ORIGINAL);
        DiffSandbox sandbox = new DiffSandbox(sandboxId, "agent", taskId, "", workspace);
        sandbox.add(path, ORIGINAL, "changed\n", true, RiskLevel.MEDIUM);
        return sandbox;
    }
}
