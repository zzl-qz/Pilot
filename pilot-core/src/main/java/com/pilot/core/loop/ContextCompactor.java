package com.pilot.core.loop;

import com.pilot.core.model.ModelClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩：把较早的对话折叠成一份「交接摘要」，而不是直接丢掉。
 *
 * <p>做法对齐 Codex / Claude Code / OpenCode 的共同点：压缩产物必须让「下一个自己」看得懂。
 * 与协议相关的一个取舍：摘要以 <strong>user</strong> 消息插回历史，而不是自定义 role，
 * 因为 OpenAI 兼容接口只接受 system / user / assistant / tool。
 *
 * <p>压缩按「消息组」切分（assistant 的 tool_calls 与其 tool 结果视为一组），
 * 避免把一对调用与结果切开导致协议非法。
 */
public final class ContextCompactor {

    /** @param coveredMessages 被折叠的消息条数；0 表示未压缩 */
    public record Outcome(int coveredMessages, int charactersBefore, int charactersAfter, String summary) {
        public boolean compacted() {
            return coveredMessages > 0;
        }

        static Outcome none(int characters) {
            return new Outcome(0, characters, characters, "");
        }
    }

    /** 最近保留的消息组数：一组通常是一条 assistant + 若干 tool 结果，约等于 12 条消息。 */
    private static final int DEFAULT_KEEP_RECENT_GROUPS = 6;
    private static final int TOOL_SNIPPET_CHARS = 800;
    private static final int SUMMARY_INPUT_CHARS = 60_000;

    static final String SUMMARY_PROMPT = """
            你在为一个编码代理整理「上下文交接摘要」，供压缩之后的自己继续干活。
            只输出摘要正文，不要解释、不要客套、不要重复原文。
            固定使用以下小节，没有内容的写「无」：
            1. 用户目标与验收标准
            2. 约束与偏好
            3. 已完成（含改动过的文件）
            4. 进行中（当前做到哪一步）
            5. 待办（按顺序）
            6. 关键文件与关键行号
            7. 已执行的验证命令与结果
            8. 关键决策与踩过的坑
            信息必须来自给定记录，不要编造。
            """;

    private final ModelClient client;
    private final int keepRecentGroups;

    public ContextCompactor(ModelClient client) {
        this(client, DEFAULT_KEEP_RECENT_GROUPS);
    }

    public ContextCompactor(ModelClient client, int keepRecentGroups) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (keepRecentGroups < 1) {
            throw new IllegalArgumentException("keepRecentGroups must be >= 1");
        }
        this.client = client;
        this.keepRecentGroups = keepRecentGroups;
    }

    /**
     * 超出预算时压缩最早的一段历史。
     *
     * @return {@code compacted() == false} 表示没动历史，调用方可以走截断兜底
     */
    public Outcome compact(List<Message> messages, int limit) {
        int before = totalChars(messages);
        if (before <= limit) {
            return Outcome.none(before);
        }
        List<int[]> groups = groups(messages);
        int compactable = groups.size() - keepRecentGroups;
        if (compactable <= 0) {
            return Outcome.none(before);
        }

        int end = groups.get(compactable - 1)[1];
        String transcript = transcript(messages, 1, end);
        if (transcript.isBlank()) {
            return Outcome.none(before);
        }

        String summary;
        try {
            summary = summarize(transcript);
        } catch (RuntimeException e) {
            // 摘要失败不能拖垮长任务：交给调用方的截断兜底
            return Outcome.none(before);
        }
        if (summary.isBlank()) {
            return Outcome.none(before);
        }

        int covered = end - 1;
        String head = header(covered);
        int foldedChars = 0;
        for (Message message : messages.subList(1, end)) {
            foldedChars += message.content().length();
        }
        // 摘要不比被折叠的原文短（常见于「只折叠 1 条」）：省不下预算，只会丢信息，直接不动历史
        if (before - foldedChars + head.length() + summary.length() >= before) {
            return Outcome.none(before);
        }
        messages.subList(1, end).clear();
        messages.add(1, Message.user(head + summary));
        return new Outcome(covered, before, totalChars(messages), summary);
    }

    /** 简化 token 估算：每 4 个字符约 1 token，向上取整。 */
    public static int estimateTokens(List<Message> messages) {
        return (totalChars(messages) + 3) / 4;
    }

    /** 只保留最长的工具输出做兜底截断时用得到。 */
    public static int totalChars(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += message.content().length();
        }
        return total;
    }

    private static String header(int covered) {
        return "[上下文交接摘要（自动生成，已折叠 " + covered + " 条较早消息）]\n";
    }

    private String summarize(String transcript) {
        var request = List.of(Message.system(SUMMARY_PROMPT), Message.user(transcript));
        var response = client.chat(request, List.of());
        return response.content() == null ? "" : response.content().strip();
    }

    /**
     * 把消息切成组：assistant(tool_calls) 与其后的 tool 结果是一组，其余每条单独一组。
     * index 0 是 system，不参与分组。
     */
    private static List<int[]> groups(List<Message> messages) {
        var result = new ArrayList<int[]>();
        int i = 1;
        while (i < messages.size()) {
            int start = i;
            if (messages.get(i).hasToolCalls()) {
                i++;
                while (i < messages.size() && "tool".equals(messages.get(i).role())) {
                    i++;
                }
            } else {
                i++;
            }
            result.add(new int[]{start, i});
        }
        return result;
    }

    /** 从最新往最旧拼，超出预算就丢弃更早的部分并标记省略。 */
    private static String transcript(List<Message> messages, int from, int to) {
        var parts = new ArrayList<String>();
        int total = 0;
        for (int i = to - 1; i >= from; i--) {
            String part = render(messages.get(i));
            if (total + part.length() > SUMMARY_INPUT_CHARS) {
                parts.add("...(更早的记录已省略)");
                break;
            }
            parts.add(part);
            total += part.length();
        }
        var builder = new StringBuilder();
        for (int i = parts.size() - 1; i >= 0; i--) {
            builder.append(parts.get(i)).append('\n');
        }
        return builder.toString().strip();
    }

    private static String render(Message message) {
        String content = message.content();
        if ("tool".equals(message.role()) && content.length() > TOOL_SNIPPET_CHARS) {
            content = content.substring(0, TOOL_SNIPPET_CHARS) + "…";
        }
        if (message.hasToolCalls()) {
            var names = message.toolCalls().stream().map(call -> call.name()).toList();
            return "[" + message.role() + "] (调用工具: " + String.join(", ", names) + ") " + content;
        }
        return "[" + message.role() + "] " + content;
    }
}
