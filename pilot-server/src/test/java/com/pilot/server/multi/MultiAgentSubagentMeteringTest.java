package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.event.MultiAgentBudgetUpdated;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.diagnostics.ModelRequestScope;
import com.pilot.core.multi.TaskSpec;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.tool.SnapshotSink;
import com.pilot.server.config.AgentProperties;
import com.pilot.server.config.MultiAgentBudget;
import com.pilot.server.config.MultiAgentProperties;
import com.pilot.server.config.WebSearchProperties;
import com.pilot.server.permission.PermissionSettings;
import com.pilot.server.store.PilotDataDir;
import com.pilot.server.store.SettingsStore;
import com.pilot.server.tool.WorkspaceToolFactory;

import tools.jackson.databind.json.JsonMapper;

/**
 * dispatch 子代理的记账边界：不传执行客户端时也不能落回裸客户端。
 *
 * <p>单独成类而不放进 {@code MultiAgentServiceTest}：那个类整体标注了
 * {@code WindowsJdkLoopbackSensitive}，在 Windows JDK 24/25 上会被整体跳过。
 */
class MultiAgentSubagentMeteringTest {

    private static final String REPORT = """
        【结论】
        src/A.java 里只有一个空类，没有需要改动的地方。

        【证据清单】
        - src/A.java:1 class A 定义为空

        【风险评估】
        - 无
        """;

    @TempDir
    Path workspace;

    @TempDir
    Path dataDir;

    private List<SessionEvent> events;

    @Test
    void dispatchWithoutExplicitClientStillMetersAsSubagent() throws IOException {
        List<String> purposes = new CopyOnWriteArrayList<>();
        List<String> groups = new CopyOnWriteArrayList<>();
        MultiAgentService service = service((messages, tools) -> {
            purposes.add(ModelRequestScope.current().purpose());
            groups.add(ModelRequestScope.current().cacheGroup());
            return new ModelResponse(REPORT, List.of(), new ModelUsage(120, 40, 0, true));
        });
        service.setBudgetLimits(new MultiAgentBudget(600, 100_000));

        MultiAgentService.Result result =
            service.dispatch(dispatch(List.of(TaskSpec.readOnly("T1", "记账", "读取代码", List.of(), List.of("src/**")))));

        assertTrue(result.completed(), result.report());
        assertFalse(groups.isEmpty(), "子代理请求必须真的打过模型");
        assertEquals(List.of("ses_test:subagent"), groups.stream().distinct().toList(),
            "dispatch 子代理不能混用主会话缓存组，也不能落回裸客户端");
        assertTrue(purposes.stream().allMatch("subagent"::equals), purposes.toString());
        long usedTokens = events.stream().filter(MultiAgentBudgetUpdated.class::isInstance)
            .map(MultiAgentBudgetUpdated.class::cast).mapToLong(MultiAgentBudgetUpdated::usedTokens).max().orElse(0L);
        assertTrue(usedTokens > 0, "子代理用量仍要计入 dispatch 预算，实际 " + usedTokens);
    }

    private MultiAgentService service(ModelClient client) throws IOException {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/A.java"), "class A {}\n");
        AgentProperties agent =
            new AgentProperties(workspace.toString(), PermissionMode.AUTO, 8, 3, 5, 300, 2, 1, 60_000, true);
        WorkspaceToolFactory factory =
            new WorkspaceToolFactory(agent, new WebSearchProperties(false, "http://127.0.0.1:9", "", ""),
                SnapshotSink.none(), JsonMapper.builder().build());
        SandboxStore store = new SandboxStore(JsonMapper.builder().build(), new PilotDataDir(dataDir.toString()));
        MultiAgentProperties properties = new MultiAgentProperties(true, 2, 2, 8, 30, List.of(), null);
        VerificationGate gate = (root, base, changes) -> VerificationGate.Report.skipped("测试环境跳过编译");
        events = new CopyOnWriteArrayList<>();
        return new MultiAgentService(client, factory, store, gate, properties, new PilotDataDir(dataDir.toString()),
            JsonMapper.builder().build(), new PermissionSettings(agent, permissionStore()), agent, null);
    }

    private MultiAgentService.Dispatch dispatch(List<TaskSpec> specs) {
        return new MultiAgentService.Dispatch("ses_test", "run_test", workspace.toString(), specs, events::add,
            () -> false);
    }

    /** 会话权限档位：默认完全放行，和 {@code MultiAgentServiceTest} 保持一致。 */
    private SettingsStore permissionStore() {
        Map<String, String> values = new ConcurrentHashMap<>();
        return new SettingsStore() {
            @Override
            public Optional<String> find(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public void save(String key, String value) {
                values.put(key, value);
            }

            @Override
            public void saveAll(Map<String, String> all) {
                values.putAll(all);
            }
        };
    }
}
