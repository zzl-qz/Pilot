package com.pilot.core.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pilot.core.message.Message;
import com.pilot.core.message.ToolCall;

/** Conservative admission check for the complete visible model request. */
public final class RequestBudget {
    /**
     * 与 {@code ToolResults.SKIPPED} 同一开头，工具层仍认得出这是跳过而不是执行失败。
     * 模型包不能引用工具包，否则会和 tool → model 接成环。
     */
    private static final String INTERRUPTED_TOOL_RESULT = "SKIPPED: 上次运行在写回工具结果前中断，这次调用没有执行。";

    private RequestBudget() {
    }

    public record Assessment(long estimatedInputTokens, long inputLimit, int attachments, boolean fits) {
    }

    public static Assessment assess(List<Message> messages, List<ToolDefinition> tools, int configuredLimit) {
        int limit = Math.max(1, configuredLimit);
        ContextComposition composition = ContextComposition.estimateRequest(messages, tools, List.of());
        long inputLimit = Math.max(1L, (long)(limit * 0.80));
        return new Assessment(composition.totalTokens(), inputLimit, composition.attachments(),
            composition.totalTokens() <= inputLimit);
    }

    /** 同时尊重本地上下文水位和端点容量；后者包含输出预留、协议和图片开销。 */
    public static Assessment assessRequest(List<Message> messages, List<ToolDefinition> tools, int localLimit) {
        Assessment local = assess(messages, tools, localLimit);
        Assessment wire = assess(messages, tools, ModelCapacity.configured());
        long inputLimit = Math.min(local.inputLimit(), wire.inputLimit());
        return new Assessment(wire.estimatedInputTokens(), inputLimit, wire.attachments(),
            wire.estimatedInputTokens() <= inputLimit);
    }

    public static final class BudgetExceeded extends IllegalArgumentException {
        public BudgetExceeded(String message) {
            super(message);
        }
    }

    public static Assessment assess(List<Message> messages, List<ToolDefinition> tools, ModelCapacity capacity) {
        ContextComposition composition = ContextComposition.estimateRequest(messages, tools, List.of());
        long overhead = 16L * messages.size() + 32L * (tools == null ? 0 : tools.size()) + 256L;
        long input = composition.totalTokens() + overhead
            + composition.attachments() * (long)Math.max(0, capacity.imageTokens() - 1024);
        return new Assessment(input, capacity.inputBudget(), composition.attachments(),
            input <= capacity.inputBudget());
    }

    /**
     * 补上没写回的工具结果，并丢掉对不上任何调用的结果。
     * 中断或续跑时，助手已经说出要调用工具，结果却没落盘。这种记录不能再发给模型。
     */
    public static void closeDanglingToolCalls(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            validateProtocol(messages);
            return;
        } catch (IllegalArgumentException ignored) {
            // 下面按调用把结果重新排好。已经合法的记录不会进到这里。
        }
        List<Message> rebuilt = new ArrayList<>(messages.size());
        Map<String, Message> results = new LinkedHashMap<>();
        for (Message message : messages) {
            if ("tool".equals(message.role()) && message.toolCallId() != null && !message.toolCallId().isBlank()) {
                results.putIfAbsent(message.toolCallId(), message);
            }
        }
        Set<String> seen = new HashSet<>();
        for (Message message : messages) {
            if ("tool".equals(message.role())) {
                continue;
            }
            Message sanitized = withoutBrokenCalls(message, seen);
            rebuilt.add(sanitized);
            for (ToolCall call : sanitized.toolCalls()) {
                Message existing = results.get(call.callId());
                rebuilt.add(existing != null ? existing : Message.tool(call.callId(), INTERRUPTED_TOOL_RESULT));
            }
        }
        messages.clear();
        messages.addAll(rebuilt);
    }

    /** 空的、重复的调用编号不能发给模型。保留第一次出现的那个。 */
    private static Message withoutBrokenCalls(Message message, Set<String> seen) {
        if (message.toolCalls().isEmpty()) {
            return message;
        }
        List<ToolCall> kept = new ArrayList<>();
        for (ToolCall call : message.toolCalls()) {
            if (call.callId() == null || call.callId().isBlank() || !seen.add(call.callId())) {
                continue;
            }
            kept.add(call);
        }
        if (kept.size() == message.toolCalls().size()) {
            return message;
        }
        return new Message(message.role(), message.content(), kept, message.toolCallId(), message.attachments(),
            message.imagePositions());
    }

    /** 由所有端点适配器在序列化并发送之前调用，包括摘要、审查、收尾和子代理。 */
    public static void enforce(List<Message> messages, List<ToolDefinition> tools, ModelCapacity capacity) {
        closeDanglingToolCalls(messages);
        validateProtocol(messages);
        Assessment budget = assess(messages, tools, capacity);
        if (!budget.fits()) {
            throw new BudgetExceeded("CONTEXT_TOO_LARGE: 完整请求估算 " + budget.estimatedInputTokens() + " token，输入预算 "
                + budget.inputLimit() + "；未发送请求");
        }
    }

    public static void validateProtocol(List<Message> messages) {
        Set<String> pending = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (Message message : messages) {
            if ("tool".equals(message.role())) {
                if (!pending.remove(message.toolCallId())) {
                    throw new IllegalArgumentException("INVALID_TOOL_PROTOCOL: 孤立或重复工具结果 " + message.toolCallId());
                }
            } else {
                if (!pending.isEmpty()) {
                    throw new IllegalArgumentException("INVALID_TOOL_PROTOCOL: 工具结果缺失 " + pending);
                }
                for (var call : message.toolCalls()) {
                    if (call.callId() == null || call.callId().isBlank() || !seen.add(call.callId())) {
                        throw new IllegalArgumentException("INVALID_TOOL_PROTOCOL: 重复或空调用 ID");
                    }
                    pending.add(call.callId());
                }
            }
        }
        if (!pending.isEmpty()) {
            throw new IllegalArgumentException("INVALID_TOOL_PROTOCOL: 工具结果缺失 " + pending);
        }
    }
}
