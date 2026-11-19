package com.pilot.server.multi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.loop.AgentLoop;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolRegistry;

class SpawnCheckoutTest {
    @TempDir
    Path root;

    @Test
    void readOnlySpawnDoesNotCreateACheckout() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        SpawnAgentTool tool = tool(opened);
        String result = tool.execute(Map.of("task", "只看代码", "isolation", "worktree", "mode", "read"));
        assertTrue(result.contains("只看代码") || result.contains("结论") || !result.startsWith("ERROR: 无法创建"), result);
        assertEqualsZero(opened);
        assertFalse(Files.exists(root.resolve("child")));
    }

    @Test
    void writeSpawnUsesTheCheckoutAndStopsWhenCreationFails() {
        SpawnAgentTool missing = tool(new AtomicInteger()).withCheckouts(null, null);
        String failed = missing.execute(Map.of("task", "改代码", "isolation", "worktree", "mode", "write"));
        assertTrue(failed.contains("未修改父工作区"), failed);

        AtomicInteger opened = new AtomicInteger();
        SubagentCheckouts broken = new SubagentCheckouts(workspace -> new SubagentCheckouts.Opened(null, "", "创建失败"));
        SpawnAgentTool tool = tool(opened).withCheckouts(root, broken);
        String result = tool.execute(Map.of("task", "改代码", "isolation", "worktree", "mode", "write"));
        assertTrue(result.contains("未修改父工作区"), result);
        assertEqualsZero(opened);
    }

    @Test
    void writeSpawnReportsTheCheckout() throws Exception {
        Path checkout = Files.createDirectories(root.resolve("child"));
        Files.writeString(checkout.resolve("New.java"), "class New {}\n");
        SubagentCheckouts checkouts =
            new SubagentCheckouts(workspace -> new SubagentCheckouts.Opened(checkout, "New.java", ""));
        SpawnAgentTool tool = tool(new AtomicInteger()).withCheckouts(root.resolve("parent"), checkouts);
        String result = tool.execute(Map.of("task", "改代码", "isolation", "worktree", "mode", "write"));
        assertTrue(result.contains(checkout.toString()), result);
        assertTrue(result.contains("New.java"), result);
        assertFalse(Files.exists(root.resolve("parent").resolve("New.java")));
    }

    @Test
    void writeSpawnsKeepSeparateRootsUntilHandoff() throws Exception {
        Path parent = Files.createDirectories(root.resolve("parent"));
        Path firstRoot = Files.createDirectories(root.resolve("wt-a"));
        Path secondRoot = Files.createDirectories(root.resolve("wt-b"));
        AtomicInteger opened = new AtomicInteger();
        List<String> roots = new ArrayList<>();
        SubagentCheckouts checkouts = new SubagentCheckouts(workspace -> {
            Path path = opened.getAndIncrement() == 0 ? firstRoot : secondRoot;
            return new SubagentCheckouts.Opened(path, "", "");
        });
        SpawnAgentTool tool = new SpawnAgentTool((messages, definitions) -> {
            String name = definitions.stream().map(ToolDefinition::name).findFirst().orElse("");
            roots.add(name);
            assertFalse(Files.exists(parent.resolve(name + ".txt")));
            return new ModelResponse("结论", List.of());
        }, new ToolRegistry(), new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
            new AgentLoop.Config(4, 3, "sys"), List::of, event -> {
            }, () -> false).withCheckouts(parent, checkouts).withWorkspaceTools(path -> {
                ToolRegistry registry = new ToolRegistry();
                registry.register(marker(path.getFileName().toString()));
                return registry;
            }, path -> {
                try {
                    Files.writeString(parent.resolve(path.getFileName() + ".txt"), "交回");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return "改动已交回原目录";
            });

        String first = tool.execute(Map.of("task", "改前端", "isolation", "worktree", "mode", "write"));
        assertTrue(first.contains("改动已交回原目录"), first);
        String second = tool.execute(Map.of("task", "改后端", "isolation", "worktree", "mode", "write"));
        assertTrue(second.contains(secondRoot.toString()), second);
        assertTrue(roots.contains("wt-a") && roots.contains("wt-b"), roots.toString());
        assertTrue(Files.exists(parent.resolve("wt-a.txt")));
        assertTrue(Files.exists(parent.resolve("wt-b.txt")));
    }

    private static Tool marker(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public boolean readOnly() {
                return false;
            }

            @Override
            public String execute(Map<String, Object> args) {
                return name;
            }
        };
    }

    private static void assertEqualsZero(AtomicInteger opened) {
        Assertions.assertEquals(0, opened.get());
    }

    private SpawnAgentTool tool(AtomicInteger opened) {
        List<SessionEvent> events = new ArrayList<>();
        return new SpawnAgentTool((request, tools) -> new ModelResponse("结论", List.of()), new ToolRegistry(),
            new PolicyPermissionGate(PermissionMode.READ_ONLY), ApprovalBroker.denyAll(),
            new AgentLoop.Config(4, 3, "sys"), List::of, events::add, () -> false);
    }
}
