package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.message.Message;
import com.pilot.core.model.NativeCapability;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.project.InMemoryProjectRepository;
import com.pilot.core.session.InMemoryPlanRepository;
import com.pilot.core.session.InMemorySessionRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.WritesFiles;
import com.pilot.core.tool.command.CommandTaskManager;
import com.pilot.core.tool.file.EditFileTool;
import com.pilot.core.tool.file.ReadFileTool;
import com.pilot.core.workspace.PathValidator;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.SkillProperties;
import com.pilot.server.multi.SandboxStore;
import com.pilot.server.multi.WorkspaceOverviewService;
import com.pilot.server.run.RunRegistry;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.skill.SkillService;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.tool.WorkspaceToolFactory;
import com.pilot.server.workspace.RunSnapshotService;

import tools.jackson.databind.json.JsonMapper;

class AgentRunPreparationTest {
    @TempDir
    Path workspace;

    @Test
    void preparesFromImmutableInputWithoutStartingRunService() {
        JsonMapper mapper = JsonMapper.builder().build();
        SessionService sessions = new SessionService();
        String sessionId = sessions.createSession("test", workspace.toString()).id();
        AgentProperties properties = new AgentProperties(workspace.toString(), null, 8, 3, 5, 300, 1, 0, 60_000, false,
            false, null, null, null, null, null, null, null, null, null, null, null);
        MultiAgentProperties multiProperties = new MultiAgentProperties(false, 1, 1, 8, 30, List.of());
        WorkspaceToolFactory toolFactory = mock(WorkspaceToolFactory.class);
        when(toolFactory.resolve(anyString())).thenReturn(workspace);
        when(toolFactory.registryFor(anyString(), anyList(), any(PlanSink.class))).thenReturn(new ToolRegistry());
        when(toolFactory.sessionExtras(anyString(), anyString())).thenReturn(List.of());
        TaskControlService taskControls = new TaskControlService(mapper, new PilotDataDir(workspace.toString()));
        var commandTasks = new CommandTaskManager(workspace.resolve("commands"), mapper);
        AgentRunPreparation preparation = new AgentRunPreparation(sessions, toolFactory, properties, multiProperties,
            taskControls, commandTasks, new RunSnapshotService(mapper, new PilotDataDir(workspace.toString())),
            new InMemoryPlanRepository(), mapper,
            new SkillService(new SkillProperties(false, null), properties, new PilotDataDir(workspace.toString())));
        List<Message> history = new ArrayList<>(List.of(Message.user("history")));
        List<String> deferred = new ArrayList<>(List.of("browser"));
        AgentRunPreparation.PreparationInput input = new AgentRunPreparation.PreparationInput(sessionId, "run",
            "request", null, false, history, false, null, false, Set.of(NativeCapability.WEB_SEARCH), deferred);
        history.add(Message.user("later"));
        deferred.add("mcp");

        try {
            AgentRunPreparation.PreparedRun prepared = preparation.prepare(input, (items, explanation) -> {
            }, ignored -> {
            });
            assertEquals(workspace, prepared.resolvedWorkspace());
            assertEquals(List.of("browser"), prepared.inheritedDeferredTools());
            assertEquals(1, input.history().size());
            assertThrows(UnsupportedOperationException.class, () -> input.deferredToolSnapshot().add("write"));
            assertThrows(UnsupportedOperationException.class, () -> prepared.availableTools().add("write"));
        } finally {
            commandTasks.close();
        }
    }

    @Test
    void addsProjectFoldersAsExtraRoots() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        SessionService sessions = new SessionService();
        Path shared = Files.createDirectory(workspace.resolveSibling("shared-" + UUID.randomUUID()));
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        projects.create("prj_1", "多目录项目", List.of(workspace.toString(), shared.toString()), Instant.now());
        String sessionId = sessions.createSession("test", workspace.toString(), "prj_1").id();
        AgentProperties properties = new AgentProperties(workspace.toString(), null, 8, 3, 5, 300, 1, 0, 60_000, false,
            false, null, null, null, null, null, null, null, null, null, null, null);
        MultiAgentProperties multiProperties = new MultiAgentProperties(false, 1, 1, 8, 30, List.of());
        WorkspaceToolFactory toolFactory = mock(WorkspaceToolFactory.class);
        when(toolFactory.resolve(anyString())).thenReturn(workspace);
        when(toolFactory.registryFor(anyString(), anyList(), any(PlanSink.class))).thenReturn(new ToolRegistry());
        when(toolFactory.sessionExtras(anyString(), anyString())).thenReturn(List.of());
        TaskControlService taskControls = new TaskControlService(mapper, new PilotDataDir(workspace.toString()));
        var commandTasks = new CommandTaskManager(workspace.resolve("commands"), mapper);
        AgentRunPreparation preparation = new AgentRunPreparation(sessions, toolFactory, properties, multiProperties,
            taskControls, commandTasks, new RunSnapshotService(mapper, new PilotDataDir(workspace.toString())),
            new InMemoryPlanRepository(), mapper,
            new SkillService(new SkillProperties(false, null), properties, new PilotDataDir(workspace.toString())));
        preparation.setProjects(projects);
        AgentRunPreparation.PreparationInput input = new AgentRunPreparation.PreparationInput(sessionId, "run",
            "request", null, false, List.of(), false, null, false, Set.of(), List.of());

        try {
            AgentRunPreparation.PreparedRun prepared = preparation.prepare(input, (items, explanation) -> {
            }, ignored -> {
            });
            assertEquals(List.of(workspace.toRealPath(), shared.toRealPath()), prepared.workspaceValidator().roots());
            verify(toolFactory).registryFor(eq(workspace.toString()), eq(List.of(workspace, shared)),
                any(PlanSink.class));
        } finally {
            commandTasks.close();
            Files.deleteIfExists(shared);
        }
    }

    @Test
    void registersWorkspaceOverviewWhenTheServiceIsWired() {
        JsonMapper mapper = JsonMapper.builder().build();
        SessionService sessions = new SessionService();
        String sessionId = sessions.createSession("test", workspace.toString()).id();
        AgentProperties properties = new AgentProperties(workspace.toString(), null, 8, 3, 5, 300, 1, 0, 60_000, false,
            false, null, null, null, null, null, null, null, null, null, null, null);
        MultiAgentProperties multiProperties = new MultiAgentProperties(false, 1, 1, 8, 30, List.of());
        WorkspaceToolFactory toolFactory = mock(WorkspaceToolFactory.class);
        when(toolFactory.resolve(anyString())).thenReturn(workspace);
        when(toolFactory.registryFor(anyString(), anyList(), any(PlanSink.class))).thenReturn(new ToolRegistry());
        when(toolFactory.sessionExtras(anyString(), anyString())).thenReturn(List.of());
        TaskControlService taskControls = new TaskControlService(mapper, new PilotDataDir(workspace.toString()));
        var commandTasks = new CommandTaskManager(workspace.resolve("commands"), mapper);
        AgentRunPreparation preparation = new AgentRunPreparation(sessions, toolFactory, properties, multiProperties,
            taskControls, commandTasks, new RunSnapshotService(mapper, new PilotDataDir(workspace.toString())),
            new InMemoryPlanRepository(), mapper,
            new SkillService(new SkillProperties(false, null), properties, new PilotDataDir(workspace.toString())));
        preparation.setWorkspaceOverview(new WorkspaceOverviewService(new InMemorySessionRepository(),
            new SandboxStore(mapper, new PilotDataDir(workspace.resolve("multi").toString())), new RunRegistry(),
            commandTasks, new InMemoryProjectRepository()));
        AgentRunPreparation.PreparationInput input = new AgentRunPreparation.PreparationInput(sessionId, "run",
            "request", null, false, List.of(), false, null, false, Set.of(), List.of());

        try {
            AgentRunPreparation.PreparedRun prepared = preparation.prepare(input, (items, explanation) -> {
            }, ignored -> {
            });
            assertTrue(prepared.availableTools().contains("workspace_overview"));
        } finally {
            commandTasks.close();
        }
    }

    @Test
    void planModeStripsAllWriteToolsAndMarksReadOnlySession() {
        JsonMapper mapper = JsonMapper.builder().build();
        SessionService sessions = new SessionService();
        String sessionId = sessions.createSession("plan-mode", workspace.toString()).id();
        AgentProperties properties = new AgentProperties(workspace.toString(), null, 8, 3, 5, 300, 1, 0, 60_000, false,
            false, null, null, null, null, null, null, null, null, null, null, null);
        MultiAgentProperties multiProperties = new MultiAgentProperties(true, 1, 1, 8, 30, List.of());
        WorkspaceToolFactory toolFactory = mock(WorkspaceToolFactory.class);
        when(toolFactory.resolve(anyString())).thenReturn(workspace);
        when(toolFactory.registryFor(anyString(), anyList(), any(PlanSink.class))).thenAnswer(invocation -> {
            ToolRegistry registry = new ToolRegistry();
            PathValidator validator = new PathValidator(workspace);
            registry.register(new ReadFileTool(validator));
            registry.register(new EditFileTool(validator));
            return registry;
        });
        when(toolFactory.sessionExtras(anyString(), anyString())).thenReturn(List.of());
        TaskControlService taskControls = new TaskControlService(mapper, new PilotDataDir(workspace.toString()));
        var commandTasks = new CommandTaskManager(workspace.resolve("commands"), mapper);
        AgentRunPreparation preparation = new AgentRunPreparation(sessions, toolFactory, properties, multiProperties,
            taskControls, commandTasks, new RunSnapshotService(mapper, new PilotDataDir(workspace.toString())),
            new InMemoryPlanRepository(), mapper,
            new SkillService(new SkillProperties(false, null), properties, new PilotDataDir(workspace.toString())));

        try {
            AgentRunPreparation.PreparationInput planInput =
                new AgentRunPreparation.PreparationInput(sessionId, "run_plan", "请修复并重构认证模块", null, false, List.of(),
                    false, null, false, Set.of(), List.of("browser", "dispatch_agents"));
            AgentRunPreparation.PreparedRun planPrepared = preparation.prepare(planInput, (items, explanation) -> {
            }, ignored -> {
            }, true);
            assertTrue(planPrepared.planMode());
            assertTrue(planPrepared.tools().tools().stream().noneMatch(t -> t instanceof WritesFiles),
                "plan 模式不得保留任何写文件工具");
            assertFalse(planPrepared.tools().find("edit_file").isPresent());
            assertFalse(planPrepared.availableTools().contains("dispatch_agents"));
            assertTrue(planPrepared.tools().find("read_file").isPresent());
            assertTrue(planPrepared.tools().enable(List.of("edit_file", "dispatch_agents", "browser")).isEmpty(),
                "被剥离的工具不得通过 enable_tools 重新开启");
            assertTrue(planPrepared.systemPrompt().contains("只读分析与规划模式（plan）"));

            AgentRunPreparation.PreparationInput actInput = new AgentRunPreparation.PreparationInput(sessionId,
                "run_act", "请修复并重构认证模块", null, false, List.of(), false, null, false, Set.of(), List.of());
            AgentRunPreparation.PreparedRun actPrepared = preparation.prepare(actInput, (items, explanation) -> {
            }, ignored -> {
            }, false);
            assertFalse(actPrepared.planMode());
            assertTrue(actPrepared.tools().find("edit_file").isPresent(), "默认 act 模式应保留写文件工具");
        } finally {
            commandTasks.close();
        }
    }
}
