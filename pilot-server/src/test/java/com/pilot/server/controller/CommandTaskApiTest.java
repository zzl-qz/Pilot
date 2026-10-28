package com.pilot.server.controller;

import com.pilot.core.session.SessionService;
import com.pilot.core.tool.CommandTaskManager;
import com.pilot.server.session.RunStarter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"pilot.store=memory", "pilot.runtime.provision=false", "pilot.web-search.local=false"})
@AutoConfigureMockMvc
class CommandTaskApiTest {
    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "pilot-command-api-" + UUID.randomUUID());
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("pilot.data.dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired CommandTaskManager commands;
    @Autowired RunStarter runs;
    @TempDir Path workspace;

    @Test
    void liveLogsAndStopAreScopedToSession() throws Exception {
        String owner = session(), other = session();
        var task = start(owner, "wait");
        try {
            await().atMost(Duration.ofSeconds(8)).until(() -> commands.logs(owner, task.taskId(), 0, 1024).text().contains("中文😀"));
            mvc.perform(get("/api/sessions/{id}/commands", owner)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.tasks[0].taskId").value(task.taskId()));
            mvc.perform(get("/api/sessions/{id}/commands/{task}/logs", owner, task.taskId()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.eof").value(false))
                    .andExpect(jsonPath("$.text").value("ready 中文😀"));
            mvc.perform(get("/api/sessions/{id}/commands/{task}", other, task.taskId())).andExpect(status().isNotFound());
            mvc.perform(get("/api/sessions/{id}/commands/{task}/logs", other, task.taskId())).andExpect(status().isNotFound());
            mvc.perform(post("/api/sessions/{id}/commands/{task}/stop", other, task.taskId())).andExpect(status().isNotFound());
            assertEquals("RUNNING", commands.get(owner, task.taskId()).status());
            mvc.perform(post("/api/sessions/{id}/commands/{task}/stop", owner, task.taskId())).andExpect(status().isOk());
            await().atMost(Duration.ofSeconds(8)).until(() -> "CANCELLED".equals(commands.get(owner, task.taskId()).status()));
            mvc.perform(post("/api/sessions/{id}/commands/{task}/stop", owner, task.taskId()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        } finally { commands.stopSession(owner); }
    }

    @Test
    void terminalExitAndLogCursorRemainReadableAndInputIsBounded() throws Exception {
        String owner = session();
        var task = start(owner, "fail");
        await().atMost(Duration.ofSeconds(8)).until(() -> "FAILED".equals(commands.get(owner, task.taskId()).status()));
        mvc.perform(get("/api/sessions/{id}/commands/{task}", owner, task.taskId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exitCode").value(7));
        mvc.perform(get("/api/sessions/{id}/commands/{task}/logs", owner, task.taskId()).param("offset", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/{id}/commands/{task}/logs", owner, task.taskId()).param("limit", "65537"))
                .andExpect(status().isBadRequest());
        var first = commands.logs(owner, task.taskId(), 0, 3);
        var rest = commands.logs(owner, task.taskId(), first.nextOffset(), 1024);
        assertEquals("ready 中文😀", first.text() + rest.text());
        assertTrue(rest.eof());
        mvc.perform(get("/api/sessions/missing/commands")).andExpect(status().isNotFound());
    }

    @Test
    void backgroundProcessBlocksWorkspaceRestoreAndSessionDeletionStopsIt() throws Exception {
        String owner = session();
        var task = start(owner, "wait");
        assertFalse(runs.lockWorkspace("test-restore", workspace), "后台进程仍在运行时不能回滚文件或切分支");
        mvc.perform(delete("/api/sessions/{id}", owner)).andExpect(status().isNoContent());
        await().atMost(Duration.ofSeconds(8)).until(() -> !commands.hasRunningInWorkspace(workspace));
        assertFalse(ProcessHandle.of(task.pid()).map(ProcessHandle::isAlive).orElse(false));
        mvc.perform(get("/api/sessions/{id}/commands", owner)).andExpect(status().isNotFound());
        assertThrows(IllegalStateException.class, () -> start(owner, "wait"), "删除后不得再次启动迟到的命令");
        assertTrue(runs.lockWorkspace("test-restore-after-stop", workspace));
        runs.abandon("test-restore-after-stop");
    }

    private String session() { return sessions.createSession("后台命令测试", workspace.toString()).id(); }

    private CommandTaskManager.Task start(String owner, String mode) {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), mode).directory(workspace.toFile());
        return commands.start(owner, "java fixture " + mode, builder, true, 0);
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            System.out.print("ready 中文😀");
            System.out.flush();
            if ("fail".equals(args[0])) System.exit(7);
            Thread.sleep(60_000);
        }
    }
}
