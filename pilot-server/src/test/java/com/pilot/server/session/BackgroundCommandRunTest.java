package com.pilot.server.session;

import com.pilot.core.event.RunCompleted;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.session.SessionService;
import com.pilot.core.tool.CommandTaskManager;
import com.pilot.core.tool.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"pilot.store=memory", "pilot.runtime.provision=false", "pilot.web-search.local=false",
        "pilot.agent.permission-mode=AUTO", "pilot.agent.completion-checks=false"})
@AutoConfigureMockMvc
class BackgroundCommandRunTest {
    private static final Path DATA = Path.of(System.getProperty("java.io.tmpdir"), "pilot-command-run-" + UUID.randomUUID());
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("pilot.data.dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired RunStarter runs;
    @Autowired CommandTaskManager commands;
    @TempDir Path workspace;

    @TestConfiguration
    static class ModelConfiguration {
        @Bean @Primary
        ModelClient backgroundTestModel() throws Exception {
            String classes = Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            String command = "java -cp \"" + classes + "\" " + Fixture.class.getName();
            return (messages, tools) -> {
                if ("user".equals(messages.getLast().role())) {
                    assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("command_status")));
                    return new ModelResponse("启动后台命令", List.of(new ToolCall("background-start", "run_command",
                            Map.of("command", command, "background", true))));
                }
                assertTrue(messages.getFirst().content().contains("本会话最近的命令任务"));
                return new ModelResponse("后台服务已启动，可在命令面板查看日志和停止。", List.of());
            };
        }
    }

    @Test
    void backgroundCommandSurvivesAgentTurnAndIsControllableThroughApi() throws Exception {
        String id = sessions.createSession("后台服务", workspace.toString()).id();
        try {
            mvc.perform(post("/api/sessions/{id}/messages", id).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"启动开发服务器\"}")).andExpect(status().isOk());
            await().atMost(Duration.ofSeconds(10)).until(() -> !commands.list(id).isEmpty() && runs.activeRun(id).isEmpty());
            var task = commands.list(id).getFirst();
            assertTrue(task.background());
            assertEquals("RUNNING", task.status(), "模型回合结束不能结束后台进程");
            var completed = sessions.events(id).stream().map(event -> event.payload())
                    .filter(RunCompleted.class::isInstance).map(RunCompleted.class::cast).findFirst().orElseThrow();
            assertTrue(completed.checks().isEmpty(), "后台进程已启动不能记成测试通过");
            await().atMost(Duration.ofSeconds(8)).until(() -> commands.logs(id, task.taskId(), 0, 1024).text().contains("development server ready"));
            mvc.perform(post("/api/sessions/{id}/commands/{taskId}/stop", id, task.taskId())).andExpect(status().isOk());
            await().atMost(Duration.ofSeconds(8)).until(() -> "CANCELLED".equals(commands.get(id, task.taskId()).status()));
        } finally { commands.stopSession(id); }
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            System.out.print("development server ready");
            System.out.flush();
            Thread.sleep(60_000);
        }
    }
}
