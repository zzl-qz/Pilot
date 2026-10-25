package com.pilot.core.loop;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.event.AssistantDelta;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.permission.PermissionGate;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolExecutor;
import com.pilot.core.tool.ToolRegistry;
import com.pilot.core.tool.ToolSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 单 Agent 循环：请求模型 → 解析工具调用 → 执行工具 → 回填结果 → 继续，直到模型给出纯文本答复。
 *
 * <p>循环本身不关心会话：它只把协议事件交给 {@code events} 回调，
 * 由接入层补上 sessionId / runId 并写入 {@code SessionService}。
 *
 * <p>工具权限、审批挂起与执行细节在 {@link ToolExecutor}。
 * 本类有可变状态，一次运行请创建一个实例；运行应放到独立线程。
 */
public final class AgentLoop {
    public static final String DEFAULT_SYSTEM_PROMPT = """
            你是 Pilot，一个在本地工作区中工作的编码代理。
            你可以使用提供的工具查看文件、搜索文本，并在用户授权后修改文件、执行受限命令。
            所有路径都必须使用工作区内的相对路径，命令在工作区根目录执行。
            先获取事实再回答，不要编造未读取过的文件内容。
            修改文件时优先用 edit_file，并保证 old_string 唯一；文件不存在时用 create_file。
            改完代码后尽量用 run_command 运行相关的构建或测试来验证，并在结论中说明执行了什么命令、结果如何。
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

    private static final int DEFAULT_CONTEXT_LIMIT = 120_000;
    private static final int MIN_COMPACTABLE_CHARS = 400;

    private final ModelClient client;
    private final ToolRegistry tools;
    private final ToolExecutor toolExecutor;
    private final Config config;
    private final Cancellation cancellation;

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate) {
        this(client, tools, permissionGate, ApprovalBroker.denyAll(), Config.defaults(), Cancellation.none());
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate, Config config) {
        this(client, tools, permissionGate, ApprovalBroker.denyAll(), config, Cancellation.none());
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                     ApprovalBroker approvals, Config config) {
        this(client, tools, permissionGate, approvals, config, Cancellation.none());
    }

    public AgentLoop(ModelClient client, ToolRegistry tools, PermissionGate permissionGate,
                     ApprovalBroker approvals, Config config, Cancellation cancellation) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.tools = Objects.requireNonNull(tools, "tools must not be null");
        this.toolExecutor = new ToolExecutor(tools,
                Objects.requireNonNull(permissionGate, "permissionGate must not be null"),
                Objects.requireNonNull(approvals, "approvals must not be null"));
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation must not be null");
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
        var changedFiles = new LinkedHashSet<String>();
        var checks = new ArrayList<String>();

        for (int step = 1; step <= config.maxSteps(); step++) {
            if (cancellation.cancelled()) {
                return cancel(messages, events);
            }
            compact(messages, contextLimit());

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
                events.accept(new RunCompleted(summary, List.copyOf(changedFiles), List.copyOf(checks)));
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
                if (cancellation.cancelled()) {
                    return cancel(messages, events);
                }
                ToolExecutor.Outcome outcome = toolExecutor.execute(call, events);
                if (outcome.changedPath() != null) {
                    changedFiles.add(outcome.changedPath());
                }
                if (outcome.check() != null) {
                    checks.add(outcome.check());
                }
                messages.add(Message.tool(call.callId(), outcome.result()));
            }
        }

        events.accept(new RunFailed("MAX_STEPS", "达到最大步数 " + config.maxSteps() + "，任务未完成。", true));
        return new Result(null, "MAX_STEPS", conversation(messages));
    }

    private Result cancel(List<Message> messages, Consumer<SessionEvent> events) {
        events.accept(new RunFailed("CANCELLED", "运行已被用户取消。", false));
        return new Result(null, "CANCELLED", conversation(messages));
    }

    private static int contextLimit() {
        String override = System.getProperty("pilot.context.max-chars");
        if (override != null) {
            try {
                int value = Integer.parseInt(override.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 回落默认预算
            }
        }
        return DEFAULT_CONTEXT_LIMIT;
    }

    /**
     * 上下文预算：把过大的工具输出替换为占位符，保留 system / user / assistant 与全部调用结构，
     * 因此不会破坏模型协议。优先压缩最长的输出，保证少量替换就能回到预算内。
     */
    private static void compact(List<Message> messages, int limit) {
        int total = 0;
        for (Message message : messages) {
            total += message.content().length();
        }
        if (total <= limit) {
            return;
        }

        record Candidate(int index, int length) {
        }
        var candidates = new ArrayList<Candidate>();
        for (int i = 1; i < messages.size(); i++) {
            Message message = messages.get(i);
            if ("tool".equals(message.role()) && message.content().length() >= MIN_COMPACTABLE_CHARS) {
                candidates.add(new Candidate(i, message.content().length()));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::length).reversed());

        for (Candidate candidate : candidates) {
            if (total <= limit) {
                break;
            }
            Message message = messages.get(candidate.index());
            String placeholder = "[工具输出已省略 " + candidate.length() + " 字符，需要时请重新调用工具]";
            messages.set(candidate.index(), Message.tool(message.toolCallId(), placeholder));
            total -= candidate.length() - placeholder.length();
        }
    }

    private static List<Message> conversation(List<Message> messages) {
        return List.copyOf(messages.subList(1, messages.size()));
    }

    private static String signature(List<ToolCall> calls) {
        return calls.stream()
                .map(call -> call.name() + "(" + canonical(call.args()) + ")")
                .collect(Collectors.joining(";"));
    }

    /** 参数规范化：Map 按 key 排序，保证同样的调用得到同样的签名。 */
    private static String canonical(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
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
