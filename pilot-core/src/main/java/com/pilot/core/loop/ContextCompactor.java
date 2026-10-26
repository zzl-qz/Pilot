package com.pilot.core.loop;

import com.pilot.core.model.ModelClient;
import com.pilot.core.tool.ToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文压缩，分四层，先做不花钱的，再动模型：
 *
 * <ol>
 *   <li><b>占位符化</b>：窗口外的大块工具输出替换成一句话，保留「读过什么」，需要时重读；</li>
 *   <li><b>首尾截断</b>：窗口内仍然过长的工具输出只留头尾；</li>
 *   <li><b>交接摘要</b>：按预算保留最近一段对话，其余折叠成一份限长摘要；</li>
 *   <li><b>硬裁剪</b>：实在压不下去才整组丢弃更早的消息。</li>
 * </ol>
 *
 * <p>与 Codex / Claude Code / OpenCode 一致的取舍：<strong>保留量按 token（字符）预算算，不按消息条数</strong>，
 * 否则「组数少、单组巨大」时压缩会完全失效。摘要以 <strong>user</strong> 消息插回历史，
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

    /** 无论如何都要留下的最近组数，保证模型还能看到「刚才做了什么」。 */
    private static final int MIN_KEEP_GROUPS = 2;
    /** 摘要后希望总量落到的位置：目标预算的 72%。 */
    private static final double KEEP_RATIO = 0.72;
    /** 压缩至少省下目标预算的 5%，否则不值得多调一次模型。 */
    private static final double MIN_GAIN_RATIO = 0.05;
    /** 摘要正文上限，约 1500 token。 */
    private static final int MAX_SUMMARY_CHARS = 6_000;

    /** 占位符化：最近 40% 预算内的工具输出保持原文。 */
    private static final double PRUNE_KEEP_RATIO = 0.40;
    /** 小于这个长度的工具输出不值得替换，占位符本身也要占地方。 */
    private static final int PRUNE_MIN_CHARS = 4_000;
    private static final String PRUNED_PREFIX = "[旧工具输出已清理";

    /** 首尾截断的参数。 */
    private static final int TRUNCATE_TRIGGER_CHARS = 8_000;
    private static final int TRUNCATE_HEAD_CHARS = 3_000;
    private static final int TRUNCATE_TAIL_CHARS = 1_000;
    private static final String TRUNCATE_MARK = "…（中间已省略，需要时重新读取）…";

    private static final int TOOL_SNIPPET_CHARS = 800;
    private static final int SUMMARY_INPUT_CHARS = 60_000;

    static final String SUMMARY_PROMPT = """
            你在为一个编码代理整理「上下文交接摘要」，供压缩之后的自己继续干活。
            只输出摘要正文，不要解释、不要客套；总长度不超过 1200 字（约 60 行）。
            固定使用以下小节，没有内容的写「无」：
            1. 用户目标与验收标准
            2. 约束与关键决策（含踩过的坑）
            3. 已完成的工作（列出改动过的文件）
            4. 进行中（当前做到哪一步）
            5. 未完成的待办（按顺序）
            6. 已执行的验证命令与结果
            只写结论与事实，不要复述工具原始输出。信息必须来自给定记录，不要编造。
            """;

    private final ModelClient client;
    private final int minKeepGroups;

    public ContextCompactor(ModelClient client) {
        this(client, MIN_KEEP_GROUPS);
    }

    public ContextCompactor(ModelClient client, int minKeepGroups) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (minKeepGroups < 1) {
            throw new IllegalArgumentException("minKeepGroups must be >= 1");
        }
        this.client = client;
        this.minKeepGroups = minKeepGroups;
    }

    /**
     * 超出预算时把较早的对话折叠成一份限长摘要。
     *
     * <p>保留范围由预算决定：从最新往回累计到目标预算的 72%，至少保留 {@code minKeepGroups} 组。
     * 这样「组数少但单组巨大」时也能一次压到位，而不是每组只掉一点。
     *
     * @return {@code compacted() == false} 表示没动历史，调用方可以走截断 / 硬裁剪兜底
     */
    public Outcome compact(List<Message> messages, int limit) {
        int before = totalChars(messages);
        if (before <= limit) {
            return Outcome.none(before);
        }
        List<int[]> groups = groups(messages);
        if (groups.size() <= minKeepGroups) {
            return Outcome.none(before);
        }

        int reserve = Math.max(1, (int) (limit * KEEP_RATIO));
        int start = groups.size();
        int kept = 0;
        while (start > minKeepGroups) {
            int size = chars(messages, groups.get(start - 1));
            // 最新一组无论如何都留下，之后按预算决定还能保留多少
            if (kept > 0 && kept + size > reserve) {
                break;
            }
            kept += size;
            start--;
        }
        if (start >= groups.size()) {
            return Outcome.none(before);
        }

        int end = groups.get(start)[0];
        String transcript = transcript(messages, 1, end);
        if (transcript.isBlank()) {
            return Outcome.none(before);
        }

        String summary;
        try {
            summary = summarize(transcript);
        } catch (RuntimeException e) {
            // 摘要失败不能拖垮长任务：交给调用方的截断 / 硬裁剪兜底
            return Outcome.none(before);
        }
        if (summary.isBlank()) {
            return Outcome.none(before);
        }
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS) + "\n…（摘要过长已截断）";
        }

        int covered = end - 1;
        String head = header(covered);
        int foldedChars = chars(messages, new int[]{1, end});
        int gain = foldedChars - head.length() - summary.length();
        if (gain < Math.max(1_000, (int) (limit * MIN_GAIN_RATIO))) {
            return Outcome.none(before);
        }

        messages.subList(1, end).clear();
        messages.add(1, Message.user(head + summary));
        return new Outcome(covered, before, totalChars(messages), summary);
    }

    /**
     * 第一层：把窗口外的大块工具输出换成占位符，保留工具名与目标，模型需要时可以重读。
     *
     * @return 被替换的条数
     */
    public static int pruneToolOutputs(List<Message> messages, int limit) {
        int keep = Math.max(1, (int) (limit * PRUNE_KEEP_RATIO));
        Map<String, String> calls = callIndex(messages);
        int seen = 0;
        int pruned = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!"tool".equals(message.role())) {
                continue;
            }
            int length = message.content().length();
            seen += length;
            if (seen <= keep || length < PRUNE_MIN_CHARS || message.content().startsWith(PRUNED_PREFIX)) {
                continue;
            }
            String target = calls.getOrDefault(message.toolCallId(), "工具");
            messages.set(i, Message.tool(message.toolCallId(),
                    PRUNED_PREFIX + "：" + target + "，约 " + length + " 字符，需要时重新读取]"));
            pruned++;
        }
        return pruned;
    }

    /**
     * 第二层：仍然留在窗口里的超长工具输出只保留头尾，避免单条就把预算吃光。
     *
     * @return 被截断的条数
     */
    public static int truncateLongToolOutputs(List<Message> messages) {
        int changed = 0;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!"tool".equals(message.role())) {
                continue;
            }
            String content = message.content();
            if (content.length() <= TRUNCATE_TRIGGER_CHARS || content.startsWith(PRUNED_PREFIX)) {
                continue;
            }
            String trimmed = content.substring(0, TRUNCATE_HEAD_CHARS)
                    + "\n" + TRUNCATE_MARK + "\n"
                    + content.substring(content.length() - TRUNCATE_TAIL_CHARS);
            messages.set(i, Message.tool(message.toolCallId(), trimmed));
            changed++;
        }
        return changed;
    }

    /**
     * 第四层：摘要也压不下去时，整组丢弃最早的消息，并留下一条说明。
     *
     * @return 被丢弃的消息条数
     */
    public static int dropOldestGroups(List<Message> messages, int limit) {
        int dropped = 0;
        // 兜底上限：即使分组逻辑写错也不能把调用方卡死
        int guard = messages.size() + 1;
        while (totalChars(messages) > limit && guard-- > 0) {
            List<int[]> groups = groups(messages);
            if (groups.size() <= MIN_KEEP_GROUPS) {
                break;
            }
            // 参数是 [start, end)：要删的是第一组本体，取它的结束下标，别取开始下标
            int end = groups.get(0)[1];
            dropped += end - 1;
            messages.subList(1, end).clear();
        }
        if (dropped > 0) {
            messages.add(1, Message.user("[更早的 " + dropped + " 条消息已因上下文超限丢弃]"));
        }
        return dropped;
    }

    /** 简化 token 估算：每 4 个字符约 1 token，向上取整。 */
    public static int estimateTokens(List<Message> messages) {
        return (totalChars(messages) + 3) / 4;
    }

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

    private static int chars(List<Message> messages, int[] group) {
        return chars(messages, group[0], group[1]);
    }

    private static int chars(List<Message> messages, int from, int to) {
        int total = 0;
        for (int i = from; i < to; i++) {
            total += messages.get(i).content().length();
        }
        return total;
    }

    /** callId → 「工具名 目标」，用于占位符里告诉模型这条输出原本是什么。 */
    private static Map<String, String> callIndex(List<Message> messages) {
        Map<String, String> index = new HashMap<>();
        for (Message message : messages) {
            for (ToolCall call : message.toolCalls()) {
                index.put(call.callId(), describe(call));
            }
        }
        return index;
    }

    private static String describe(ToolCall call) {
        Map<String, Object> args = call.args();
        Object target = args == null ? null : args.get("path");
        if (target == null && args != null) {
            target = args.get("pattern");
        }
        if (target == null && args != null) {
            target = args.get("command");
        }
        return target == null ? call.name() : call.name() + " " + target;
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
            var names = message.toolCalls().stream().map(ToolCall::name).toList();
            return "[" + message.role() + "] (调用工具: " + String.join(", ", names) + ") " + content;
        }
        return "[" + message.role() + "] " + content;
    }
}
