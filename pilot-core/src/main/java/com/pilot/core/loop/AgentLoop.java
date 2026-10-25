package com.pilot.core.loop;

import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PermissionResult;
import com.pilot.core.tool.Tool;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 单 Agent 循环：请求模型 → 解析工具调用 → 执行工具 → 回填结果 → 继续，直到模型给出纯文本答复。
 *
 * <p>循环本身不关心会话：它只把协议事件交给 {@code events} 回调，
 * 由接入层补上 sessionId / runId 并写入 {@code SessionService}。
 *
 * <p>本类有可变状态，一次运行请创建一个实例；运行应放到独立线程。
 */
public final class AgentLoop {
    public static final String DEFAULT_SYSTEM_PROMPT = """
            你是 Pilot，一个在本地工作区中工作的编码代理。
            你可以使用提供的只读工具查看文件与搜索文本，所有路径都必须使用工作区内的相对路径。
            先获取事实再回答，不要编造未读取过的文件内容。
            完成任务后用简洁的中文给出结论，必要处引用文件路径与行号。
            """;

    /** @param repeatedToolCallLimit 连续相同工具调用达到该次数即中断（含第一次） */
    public record Config(int maxSteps, int repeatedToolCallLimit, String systemPrompt) {
        public Config {
            if (maxSteps < 1) {
                throw new IllegalArgumentException("maxSteps must be >= 1");
            }
            if (repeatedToolCallLimit < 1) {
                throw new IllegalArgumentException("repeatedToolCallLimit must be >= 1");
            }
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalArgumentException("systemPrompt must not be blank");
            }
        }

        public static Config defaults() {
            return new Config(8, 3, DEFAULT_SYSTEM_PROMPT);
        }
    }

    /**
     * @param finalText    成功时的最终文本
     * @param failureCode  失败码；成功为 {@code null}
     * @param conversation 本次运行后的对话历史（不含 system 消息），可用于同一会话的下一轮
     */
    public record Result(String finalText, String failureCode, List<Message> conversation) {
        public boolean failed() {
            return failureCode != null;
        }
    }

    private final ModelClient client;
    private final ToolRegistry tools;
    private final PermissionGate permissionGate;
    private final Config config;

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate) {
        this(client, tools, permissionGate, Config.defaults());
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, Config config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public Result run(String userInput, List<Message> history, Consumer<SessionEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("userInput must not be blank");
        }
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(config.systemPrompt()));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Message.user(userInput.strip()));

        String lastSignature = null;
        int repeated = 0;

        for (int step = 1; step <= config.maxSteps(); step++) {
            ModelResponse response;
            try {
                response = client.chat(List.copyOf(messages), tools.definitions());
            } catch (RuntimeException e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                events.accept(new RunFailed("MODEL_ERROR", "模型调用失败：" + ToolSupport.oneLine(reason, 300), true));
                return new Result(null, "MODEL_ERROR", conversation(messages));
            }

            String content = response.content() == null ? "" : response.content();
            if (!content.isBlank()) {
                events.accept(new AssistantDelta(content));
            }
            List<ToolCall> calls = response.toolCalls() == null ? List.of() : response.toolCalls();

            if (calls.isEmpty()) {
                messages.add(Message.assistant(content, List.of()));
                String summary = content.isBlank() ? "任务完成（模型未返回文本）" : content;
                events.accept(new RunCompleted(summary, List.of(), List.of()));
                return new Result(content, null, conversation(messages));
            }

            messages.add(Message.assistant(content, calls));

            String signature = signature(calls);
            if (signature.equals(lastSignature)) {
                repeated++;
            } else {
                lastSignature = signature;
                repeated = 1;
            }
            if (repeated >= config.repeatedToolCallLimit()) {
                String detail = "模型连续 " + repeated + " 次请求相同的工具调用，已中断以避免死循环。";
                events.accept(new RunFailed("REPEATED_TOOL_CALL", detail, false));
                return new Result(null, "REPEATED_TOOL_CALL", conversation(messages));
            }

            for (ToolCall call : calls) {
                messages.add(Message.tool(call.callId(), execute(call, events)));
            }
        }

        events.accept(new RunFailed("MAX_STEPS", "达到最大步数 " + config.maxSteps() + "，任务未完成。", true));
        return new Result(null, "MAX_STEPS", conversation(messages));
    }

    private String execute(ToolCall call, Consumer<SessionEvent> events) {
        events.accept(new ToolStarted(call.callId(), call.name(), summarizeArgs(call)));
        long startedAt = System.nanoTime();

        String outcome;
        Optional<Tool> maybeTool = tools.find(call.name());
        if (maybeTool.isEmpty()) {
            outcome = "ERROR: 未知工具 " + call.name() + "，可用工具: " + availableNames();
        } else {
            Tool tool = maybeTool.get();
            PermissionResult permission = permissionGate.check(call, tool);
            outcome = switch (permission.decision()) {
                case ALLOW -> runTool(tool, call);
                case DENY -> "ERROR: 权限拒绝：" + permission.reason();
                case ASK -> "ERROR: 审批流程尚未接入，已拒绝执行 " + tool.name() + "。";
            };
        }

        long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        events.accept(new ToolCompleted(call.callId(), ToolSupport.oneLine(outcome, 120), latencyMs));
        return outcome;
    }

    private static String runTool(Tool tool, ToolCall call) {
        try {
            Map<String, Object> args = call.args() == null ? Map.of() : call.args();
            String result = tool.execute(args);
            return result == null ? "" : result;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "ERROR: 工具执行失败：" + reason;
        }
    }

    private String availableNames() {
        List<String> names = tools.tools().stream().map(Tool::name).toList();
        return names.isEmpty() ? "(无)" : String.join(", ", names);
    }

    private static List<Message> conversation(List<Message> messages) {
        return List.copyOf(messages.subList(1, messages.size()));
    }

    private static String summarizeArgs(ToolCall call) {
        Map<String, Object> args = call.args() == null ? Map.of() : call.args();
        for (String key : List.of("path", "pattern")) {
            Object value = args.get(key);
            if (value != null) {
                return ToolSupport.oneLine(key + "=" + value, 120);
            }
        }
        return ToolSupport.oneLine(canonical(args), 120);
    }

    private static String signature(List<ToolCall> calls) {
        return calls.stream()
                .map(call -> call.name() + "(" + canonical(call.args()) + ")")
                .collect(Collectors.joining(";"));
    }

    /** 参数规范化：Map 按 key 排序，保证同样的调用得到同样的签名。 */
    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> entry.getKey() + "=" + canonical(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentLoop::canonical).collect(Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
    }
}
