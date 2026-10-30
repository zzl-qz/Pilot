package com.pilot.core.loop;

import com.pilot.core.event.SessionEvent;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.permission.PermissionMode;
import com.pilot.core.permission.PolicyPermissionGate;
import com.pilot.core.tool.Availability;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolImageLoopTest {
    private final List<SessionEvent> events = new ArrayList<>();

    @Test
    void toolImagesArriveAsOneUserMessageAfterAllToolResults() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ImageTool("screenshot_a", "/tmp/a.png"));
        registry.register(new ImageTool("screenshot_b", "/tmp/b.png"));
        Scripted client = new Scripted(
                new ModelResponse("", List.of(
                        new ToolCall("c1", "screenshot_a", Map.of()),
                        new ToolCall("c2", "screenshot_b", Map.of()))),
                new ModelResponse("看到了。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry, new PolicyPermissionGate(PermissionMode.READ_ONLY),
                new AgentLoop.Config(8, 3, "测试"));

        loop.run("截图", List.of(), events::add);

        List<Message> second = client.requests.get(1);
        int size = second.size();
        Message images = second.get(size - 1);
        assertTrue(images.isToolImages());
        assertEquals("user", images.role());
        assertEquals(2, images.attachments().size());
        assertEquals("tool", second.get(size - 2).role());
        assertEquals("tool", second.get(size - 3).role());
        assertTrue(second.get(size - 4).hasToolCalls());
        // tool 消息本身只有文本：图片绝不夹进 tool 角色里。
        assertTrue(second.get(size - 2).attachments().isEmpty());
        assertTrue(images.content().contains("screenshot_a(c1)") && images.content().contains("screenshot_b(c2)"));
    }

    @Test
    void staleToolImagesAreReplacedByPlaceholders() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("sys"));
        for (int i = 0; i < 5; i++) {
            messages.add(Message.toolImages("shot " + i,
                    List.of(new Attachment("s" + i + ".png", "image/png", "/tmp/s" + i + ".png"))));
            messages.add(Message.assistant("ok " + i, List.of()));
        }

        int dropped = ContextCompactor.dropStaleToolImages(messages, 2);

        assertEquals(3, dropped);
        List<Message> remaining = messages.stream().filter(m -> !m.attachments().isEmpty()).toList();
        assertEquals(2, remaining.size());
        assertEquals("s3.png", remaining.get(0).attachments().getFirst().name());
        assertEquals("s4.png", remaining.get(1).attachments().getFirst().name());
        Message oldest = messages.get(1);
        assertTrue(oldest.content().startsWith(Message.TOOL_IMAGE_MARKER), oldest.content());
        assertTrue(oldest.attachments().isEmpty());
        // 再次执行是幂等的。
        assertEquals(0, ContextCompactor.dropStaleToolImages(messages, 2));
    }

    @Test
    void unavailableToolIsHiddenFromTheModelAndRefusedWithAReason() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ImageTool("screenshot_a", "/tmp/a.png"));
        registry.register(new Tool() {
            @Override public String name() { return "browser"; }
            @Override public String description() { return "浏览器"; }
            @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
            @Override public boolean readOnly() { return true; }
            @Override public Availability availability() { return Availability.unavailable("未检测到 Chrome"); }
            @Override public String execute(Map<String, Object> args) { throw new AssertionError("不应被执行"); }
        });
        Scripted client = new Scripted(
                new ModelResponse("", List.of(new ToolCall("c1", "browser", Map.of("action", "snapshot")))),
                new ModelResponse("好吧。", List.of()));
        AgentLoop loop = new AgentLoop(client, registry, new PolicyPermissionGate(PermissionMode.READ_ONLY),
                new AgentLoop.Config(8, 3, "测试"));

        loop.run("看看页面", List.of(), events::add);

        assertEquals(List.of("screenshot_a"),
                client.toolDefinitions.get(0).stream().map(ToolDefinition::name).toList());
        Message toolMessage = client.requests.get(1).getLast();
        assertEquals("tool", toolMessage.role());
        assertTrue(toolMessage.content().startsWith("ERROR: 工具 browser 当前不可用：未检测到 Chrome"),
                toolMessage.content());
        assertFalse(client.requests.get(1).stream().anyMatch(Message::isToolImages));
    }

    private static final class ImageTool implements Tool {
        private final String name;
        private final String path;

        private ImageTool(String name, String path) {
            this.name = name;
            this.path = path;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "产出图片"; }
        @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
        @Override public boolean readOnly() { return true; }
        @Override public String execute(Map<String, Object> args) { return "ok"; }

        @Override
        public ToolResult executeResult(Map<String, Object> args) {
            return ToolResult.withImages("已截图 " + name, List.of(new Attachment(name + ".png", "image/png", path)));
        }
    }

    private static final class Scripted implements ModelClient {
        private final Deque<ModelResponse> script = new ArrayDeque<>();
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<ToolDefinition>> toolDefinitions = new ArrayList<>();

        private Scripted(ModelResponse... responses) {
            script.addAll(List.of(responses));
        }

        @Override
        public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            requests.add(List.copyOf(messages));
            toolDefinitions.add(List.copyOf(tools));
            ModelResponse next = script.poll();
            if (next == null) throw new IllegalStateException("脚本已用完");
            return next;
        }
    }
}
