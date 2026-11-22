package com.pilot.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.message.Message;
import com.pilot.core.model.NativeCapability;
import com.pilot.core.plan.PlanSink;
import com.pilot.core.session.InMemoryPlanRepository;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.server.common.data.PilotDataDir;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.SkillProperties;
import com.pilot.server.run.TaskControlService;
import com.pilot.server.skill.SkillService;
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
        AgentProperties properties = new AgentProperties(workspace.toString(), null, 8, 3, 5, 300, 1, 0,
            60_000, false, false, null, null, null, null, null, null, null, null, null, null, null);
        MultiAgentProperties multiProperties = new MultiAgentProperties(false, 1, 1, 8, 30, List.of());
        WorkspaceToolFactory toolFactory = mock(WorkspaceToolFactory.class);
        when(toolFactory.resolve(anyString())).thenReturn(workspace);
        when(toolFactory.registryFor(anyString(), any(PlanSink.class))).thenReturn(new ToolRegistry());
        when(toolFactory.sessionExtras(anyString(), anyString())).thenReturn(List.of());
        TaskControlService taskControls = new TaskControlService(mapper, new PilotDataDir(workspace.toString()));
        var commandTasks = new com.pilot.core.tool.command.CommandTaskManager(workspace.resolve("commands"), mapper);
        AgentRunPreparation preparation = new AgentRunPreparation(sessions, toolFactory, properties, multiProperties,
            taskControls, commandTasks,
            new RunSnapshotService(mapper, new PilotDataDir(workspace.toString())), new InMemoryPlanRepository(),
            mapper, new SkillService(new SkillProperties(false, null), properties,
                new PilotDataDir(workspace.toString())));
        List<Message> history = new ArrayList<>(List.of(Message.user("history")));
        List<String> deferred = new ArrayList<>(List.of("browser"));
        AgentRunPreparation.PreparationInput input = new AgentRunPreparation.PreparationInput(sessionId, "run",
            "request", null, false, history, false, null, false, Set.of(NativeCapability.WEB_SEARCH), deferred);
        history.add(Message.user("later"));
        deferred.add("mcp");

        try {
            AgentRunPreparation.PreparedRun prepared = preparation.prepare(input, (items, explanation) -> {},
                ignored -> {});
            assertEquals(workspace, prepared.resolvedWorkspace());
            assertEquals(List.of("browser"), prepared.inheritedDeferredTools());
            assertEquals(1, input.history().size());
            assertThrows(UnsupportedOperationException.class, () -> input.deferredToolSnapshot().add("write"));
            assertThrows(UnsupportedOperationException.class, () -> prepared.availableTools().add("write"));
        } finally {
            commandTasks.close();
        }
    }
}
