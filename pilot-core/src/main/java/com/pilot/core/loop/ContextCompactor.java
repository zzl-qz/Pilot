package com.pilot.core.loop;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelRequestScope;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.RequestBudget;
import com.pilot.core.model.ToolDefinition;
import com.pilot.core.prompt.PromptLibrary;
import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolCallJournal;

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

    /** 无论如何都要留下的最近组数，保证压缩后仍能看到最近完整的行动—观察链。 */
    private static final int MIN_KEEP_GROUPS = 5;
    /** 真正超窗时允许硬裁剪到更少的组；协议可用性优先于近期上下文完整度。 */
    private static final int HARD_DROP_MIN_KEEP_GROUPS = 2;
    /**
     * 摘要后希望总量落到的位置：目标预算的 50%。
     *
     * <p>留一半余量是刻意的：压到 70% 附近的话，模型再跑十几步就会重新越线，
     * 长任务会出现「连着压两次」，既多花摘要调用，又把刚读到的细节抹掉。
     */
    private static final double KEEP_RATIO = 0.50;
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
    private static final int FILE_LIST_MAX_CHARS = 5_000;
    private static final String READ_FILES_START = "<read_files>";
    private static final String READ_FILES_END = "</read_files>";
    private static final String MODIFIED_FILES_START = "<modified_files>";
    private static final String MODIFIED_FILES_END = "</modified_files>";

    /** 摘要提示词正文在 {@code prompts/compact/summary.md}。 */
    static final String SUMMARY_PROMPT = PromptLibrary.standard().get("compact/summary");

    private final ModelClient client;
    private final int minKeepGroups;
    private Supplier<List<ToolDefinition>> tools = List::of;
    private IntSupplier contextLimit = () -> Integer.MAX_VALUE;

    public ContextCompactor withTools(Supplier<List<ToolDefinition>> value) {
        this.tools = value;
        return this;
    }

    public ContextCompactor withContextLimit(IntSupplier value) {
        this.contextLimit = value;
        return this;
    }

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
     * <p>保留范围由预算决定：从最新往回累计，任一预算（字符 / token）超过目标的一半就停，
     * 至少保留 {@code minKeepGroups} 组。这样「组数少但单组巨大」时也能一次压到位。
     *
     * @return {@code compacted() == false} 表示没动历史，调用方可以走截断 / 硬裁剪兜底
     */
    public Outcome compact(List<Message> messages, int limit) {
        return compact(messages, limit, limit);
    }

    /**
     * 双预算版本：字符预算管英文，token 预算管中文（1 个汉字接近 1 token）。
     *
     * <p>只看字符会让中文会话在真正接近窗口上限前就被判为「还很空」；只看 token 又会
     * 让英文会话过早压缩。两个预算同时卡，按更紧的那个走。
     */
    public Outcome compact(List<Message> messages, int targetChars, int targetTokens) {
        int beforeChars = totalChars(messages);
        int beforeTokens = estimateTokens(messages);
        if (beforeChars <= targetChars && beforeTokens <= targetTokens) {
            return Outcome.none(beforeChars);
        }
        List<int[]> groups = groups(messages);
        if (groups.size() <= minKeepGroups) {
            return Outcome.none(beforeChars);
        }

        int reserveChars = Math.max(1, (int)(targetChars * KEEP_RATIO));
        int reserveTokens = Math.max(1, (int)(targetTokens * KEEP_RATIO));
        int start = groups.size();
        int keptGroups = 0;
        int keptChars = 0;
        int keptTokens = 0;
        while (start > 0) {
            int[] group = groups.get(start - 1);
            int sizeChars = chars(messages, group);
            int sizeTokens = tokens(messages, group);
            // 最近 minKeepGroups 组无论多大都保留；之后任一预算超了就停。
            if (keptGroups >= minKeepGroups
                && (keptChars + sizeChars > reserveChars || keptTokens + sizeTokens > reserveTokens)) {
                break;
            }
            keptChars += sizeChars;
            keptTokens += sizeTokens;
            keptGroups++;
            start--;
        }
        if (start <= 0 || start >= groups.size()) {
            return Outcome.none(beforeChars);
        }

        int end = groups.get(start)[0];
        if (messages.subList(1, end).stream().anyMatch(message -> message.content().contains("[工具输出未归档"))) {
            return Outcome.none(beforeChars);
        }
        String pins = ContextPins.capture(messages, end, sourceFingerprint(messages.subList(0, end)));
        if (pins.length() > SUMMARY_INPUT_CHARS / 2) {
            return Outcome.none(beforeChars);
        }
        String transcript = pins + "\n" + transcript(messages, 1, end);
        if (transcript.isBlank()) {
            return Outcome.none(beforeChars);
        }

        List<Message> sourceSnapshot = List.copyOf(messages);
        String sourceFingerprint = sourceFingerprint(sourceSnapshot);
        String summary;
        try {
            summary = summarize(messages, transcript);
        } catch (RuntimeException e) {
            // 摘要失败不能拖垮长任务：交给调用方的截断 / 硬裁剪兜底
            return Outcome.none(beforeChars);
        }
        if (summary.isBlank()) {
            return Outcome.none(beforeChars);
        }
        if (!sourceFingerprint(messages).equals(sourceFingerprint)) {
            return Outcome.none(beforeChars);
        }
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS) + "\n…（摘要过长已截断）";
        }
        summary += fileHistory(messages, 1, end);

        int covered = end - 1;
        String head = header(covered);
        String replacement = head + summary + pins;
        int foldedChars = chars(messages, 1, end);
        int foldedTokens = tokens(messages, 1, end);
        double charGain = (foldedChars - replacement.length()) / (double)Math.max(1, targetChars);
        double tokenGain = (foldedTokens - estimateTokens(replacement)) / (double)Math.max(1, targetTokens);
        if (Math.max(charGain, tokenGain) < MIN_GAIN_RATIO) {
            return Outcome.none(beforeChars);
        }

        // 摘要可以替代旧文本，但不能替代图像本身。把被折叠消息中的稳定附件引用
        // 挂到交接摘要上，Goal reviewer、续跑和子代理继承历史时仍能看到原图。
        List<Attachment> pinnedAttachments = attachments(messages, 1, end);
        messages.subList(1, end).clear();
        messages.add(1, Message.user(replacement, pinnedAttachments));
        return new Outcome(covered, beforeChars, totalChars(messages), summary);
    }

    /**
     * 第一层：把窗口外的大块工具输出换成占位符，保留工具名与目标，模型需要时可以重读。
     *
     * @return 被替换的条数
     */
    public static int pruneToolOutputs(List<Message> messages, int limit) {
        return pruneToolOutputs(messages, limit, observedPrefix(messages));
    }

    public static int pruneToolOutputs(List<Message> messages, int limit, int observedThroughIndex) {
        int keep = Math.max(1, (int)(limit * PRUNE_KEEP_RATIO));
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
            // Only prune observations that the model has already seen. The newest tool result
            // is appended after the previous request and must survive until the next request.
            if (i >= Math.min(observedThroughIndex, messages.size()) || seen <= keep || length < PRUNE_MIN_CHARS
                || message.content().startsWith(PRUNED_PREFIX) || message.content().contains("[工具输出未归档")) {
                continue;
            }
            String target = calls.getOrDefault(message.toolCallId(), "工具");
            messages.set(i, Message.tool(message.toolCallId(),
                PRUNED_PREFIX + "：" + target + "，约 " + length + " 字符]" + archiveReference(message.content())));
            pruned++;
        }
        return pruned;
    }

    /** 保留尾部整批调用、结果和图片，而不只是最后一条结果。 */
    public static int observedPrefix(List<Message> messages) {
        int end = messages.size();
        while (end > 1 && messages.get(end - 1).isToolImages()) {
            end--;
        }
        if (end > 1 && "tool".equals(messages.get(end - 1).role())) {
            while (end > 1 && "tool".equals(messages.get(end - 1).role())) {
                end--;
            }
            if (end > 1 && messages.get(end - 1).hasToolCalls()) {
                end--;
            }
        }
        return end;
    }

    private static String archiveReference(String text) {
        int start = text.lastIndexOf("[工具输出存档 run_id=");
        return start < 0 ? "（旧记录没有存档引用；不得重放有副作用的操作）" : "\n" + text.substring(start);
    }

    private static String sourceFingerprint(List<Message> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Message message : messages) {
                digest.update(message.role().getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0);
                digest.update(message.content().getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(message.toolCallId()).getBytes(StandardCharsets.UTF_8));
                digest.update(message.imagePositions().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0);
                for (ToolCall call : message.toolCalls()) {
                    digest.update(call.callId().getBytes(StandardCharsets.UTF_8));
                    digest.update(ToolCallJournal.fingerprint(call).getBytes(StandardCharsets.UTF_8));
                }
                for (Attachment attachment : message.attachments()) {
                    digest.update(attachment.path().getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 工具图片只保留最近 {@code keep} 条：截图是「此刻页面长什么样」的观察，旧的已经过期，
     * 却会按图片 token 持续占用上下文。更早的图片换成文字说明，模型需要时重新截图。
     *
     * @return 被去掉图片的消息条数
     */
    public static int dropStaleToolImages(List<Message> messages, int keep) {
        return dropStaleToolImages(messages, keep, observedPrefix(messages));
    }

    public static int dropStaleToolImages(List<Message> messages, int keep, int observedThrough) {
        int kept = 0;
        int dropped = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (!message.isToolImages() || message.attachments().isEmpty()) {
                continue;
            }
            if (++kept <= Math.max(0, keep) || i >= observedThrough) {
                continue;
            }
            String names = message.attachments().stream().map(Attachment::name).collect(Collectors.joining(", "));
            messages.set(i,
                new Message("user", Message.TOOL_IMAGE_MARKER + " 较早的图片已省略（" + names + "）；需要当前状态请重新截图或读取。"));
            dropped++;
        }
        return dropped;
    }

    /**
     * 第二层：仍然留在窗口里的超长工具输出只保留头尾，避免单条就把预算吃光。
     *
     * @return 被截断的条数
     */
    public static int truncateLongToolOutputs(List<Message> messages) {
        return truncateLongToolOutputs(messages, observedPrefix(messages));
    }

    public static int truncateLongToolOutputs(List<Message> messages, int observedThroughIndex) {
        int changed = 0;
        for (int i = 0; i < Math.min(messages.size(), observedThroughIndex); i++) {
            Message message = messages.get(i);
            if (!"tool".equals(message.role())) {
                continue;
            }
            String content = message.content();
            if (content.length() <= TRUNCATE_TRIGGER_CHARS || content.startsWith(PRUNED_PREFIX)
                || content.contains("[工具输出未归档")) {
                continue;
            }
            String trimmed = content.substring(0, TRUNCATE_HEAD_CHARS) + "\n" + TRUNCATE_MARK + "\n"
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
        String pinnedState = ContextPins.capture(messages, messages.size(), sourceFingerprint(messages));
        if (pinnedState.length() >= limit) {
            return 0;
        }
        int dropped = 0;
        LinkedHashMap<String, Attachment> pinnedAttachments = new LinkedHashMap<>();
        // 兜底上限：即使分组逻辑写错也不能把调用方卡死
        int guard = messages.size() + 1;
        while (totalChars(messages) + pinnedState.length() + 80 > limit && guard-- > 0) {
            List<int[]> groups = groups(messages);
            if (groups.size() <= HARD_DROP_MIN_KEEP_GROUPS) {
                break;
            }
            // 参数是 [start, end)：要删的是第一组本体，取它的结束下标，别取开始下标
            int end = groups.get(0)[1];
            if (messages.subList(1, end).stream().anyMatch(message -> message.content().contains("[工具输出未归档"))) {
                break;
            }
            for (Attachment attachment : attachments(messages, 1, end)) {
                pinnedAttachments.putIfAbsent(attachment.path(), attachment);
            }
            dropped += end - 1;
            messages.subList(1, end).clear();
        }
        if (dropped > 0) {
            messages.add(1, Message.user("[更早的 " + dropped + " 条消息已因上下文超限丢弃]" + pinnedState,
                List.copyOf(pinnedAttachments.values())));
        }
        return dropped;
    }

    /**
     * 估算 token：ASCII 按 4 字符 1 token，非 ASCII（中文等）按 1 字符 1 token。
     *
     * <p>纯 {@code chars / 4} 会显著低估中文上下文（一个汉字通常就接近一个 token），
     * 压缩触发太晚，请求可能直接超出模型窗口。这里宁可保守。
     */
    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateTokens(message.content());
        }
        return total;
    }

    /** 单段文本的 token 估算：ASCII 4 字符 1 个，非 ASCII 1 字符 1 个。 */
    public static int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int wide = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) < 0x80) {
                ascii++;
            } else {
                wide++;
            }
        }
        return (ascii + 3) / 4 + wide;
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

    private String summarize(List<Message> history, String transcript) {
        var profile = client.profile();
        List<ToolDefinition> definitions = tools.get();
        String instruction = "请只生成当前对话的交接摘要，不调用工具。保留目标、约束、已完成工作、验证证据和剩余事项。\n" + SUMMARY_PROMPT;
        int input = estimateTokens(history) + estimateTokens(String.valueOf(definitions));
        var price = profile.pricing();
        // 实验策略仅在有余量且已配置单价显示潜在节省时启用；默认仍走短输入。
        boolean candidate = "prefix".equals(profile.compactionMode()) && !history.isEmpty()
            && contextLimit.getAsInt() > 0 && input + estimateTokens(instruction) + 2_000 < contextLimit.getAsInt()
            && totalChars(history) + instruction.length() + 8_000 < contextLimit.getAsInt() && price.input() != null
            && price.cachedInput() != null && price.cacheWrite() != null
            && price.cachedInput().multiply(BigDecimal.valueOf(input))
                .add(price.cacheWrite().multiply(BigDecimal.valueOf(estimateTokens(instruction))))
                .compareTo(price.input().multiply(BigDecimal.valueOf(estimateTokens(SUMMARY_PROMPT + transcript)))) < 0;
        if (candidate) {
            List<Message> request = new ArrayList<>(history);
            request.add(Message.user(instruction));
            try {
                if (!RequestBudget.assessRequest(request, definitions, contextLimit.getAsInt()).fits()) {
                    throw new IllegalArgumentException("摘要请求超过安全预算");
                }
                List<Message> admittedRequest = List.copyOf(request);
                ModelResponse response =
                    ModelRequestScope.within("compaction", null, true, () -> client.chat(admittedRequest, definitions));
                if (response.toolCalls().isEmpty() && response.content() != null && !response.content().isBlank()) {
                    return response.content().strip();
                }
            } catch (RuntimeException ignored) {
                if (Thread.currentThread().isInterrupted()) {
                    throw ignored;
                }
            }
        }
        List<Message> request = List.of(Message.system(SUMMARY_PROMPT), Message.user(transcript));
        if (!RequestBudget.assessRequest(request, List.of(), contextLimit.getAsInt()).fits()) {
            return "";
        }
        ModelResponse response =
            ModelRequestScope.within("compaction", null, true, () -> client.chat(request, List.of()));
        if (!response.toolCalls().isEmpty()) {
            return "";
        }
        return response.content() == null ? "" : response.content().strip();
    }

    private static int chars(List<Message> messages, int[] group) {
        return chars(messages, group[0], group[1]);
    }

    private static int tokens(List<Message> messages, int[] group) {
        return tokens(messages, group[0], group[1]);
    }

    private static int tokens(List<Message> messages, int from, int to) {
        int total = 0;
        for (int i = from; i < to; i++) {
            total += estimateTokens(messages.get(i).content());
        }
        return total;
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
        ArrayList<int[]> result = new ArrayList<int[]>();
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
            result.add(new int[] {start, i});
        }
        return result;
    }

    /** 从最新往最旧拼，超出预算就丢弃更早的部分并标记省略。 */
    private static String transcript(List<Message> messages, int from, int to) {
        ArrayList<String> parts = new ArrayList<String>();
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
        StringBuilder builder = new StringBuilder();
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
            List<String> calls = message.toolCalls().stream().map(call -> {
                String target = describe(call).replace('\n', ' ').replace('\r', ' ');
                return target.length() > 240 ? target.substring(0, 240) + "…" : target;
            }).toList();
            return "[" + message.role() + "] (调用工具: " + String.join(", ", calls) + ") " + content;
        }
        return "[" + message.role() + "] " + content;
    }

    /** 文件清单由工具调用确定性提取，上一轮摘要里的清单也要继承，不能依赖模型记住路径。 */
    private static String fileHistory(List<Message> messages, int from, int to) {
        LinkedHashSet<String> read = new LinkedHashSet<>();
        LinkedHashSet<String> modified = new LinkedHashSet<>();
        Map<String, String> results = new HashMap<>();
        for (int index = from; index < to; index++) {
            Message message = messages.get(index);
            if ("tool".equals(message.role())) {
                results.put(message.toolCallId(), message.content());
            }
        }
        for (int index = from; index < to; index++) {
            Message message = messages.get(index);
            if ("user".equals(message.role()) && message.content().startsWith("[上下文交接摘要")) {
                inheritedFiles(message.content(), read, modified);
            }
            for (ToolCall call : message.toolCalls()) {
                Object value = call.args() == null ? null : call.args().get("path");
                if (!(value instanceof String path) || !validTrackedPath(path)) {
                    continue;
                }
                String result = results.get(call.callId());
                if (result == null || result.isBlank() || result.startsWith("ERROR") || result.startsWith("SKIPPED:")) {
                    continue;
                }
                switch (call.name()) {
                    case "read_file" -> read.add(path);
                    case "edit_file", "create_file" -> modified.add(path);
                    default -> {
                    }
                }
            }
        }
        read.removeAll(modified);
        if (read.isEmpty() && modified.isEmpty()) {
            return "";
        }
        StringBuilder output = new StringBuilder("\n\n");
        appendPaths(output, READ_FILES_START, READ_FILES_END, read);
        appendPaths(output, MODIFIED_FILES_START, MODIFIED_FILES_END, modified);
        return output.toString().stripTrailing();
    }

    private static void inheritedFiles(String summary, Set<String> read, Set<String> modified) {
        Set<String> active = null;
        for (String line : summary.lines().toList()) {
            String value = line.strip();
            if (READ_FILES_START.equals(value)) {
                active = read;
            } else if (MODIFIED_FILES_START.equals(value)) {
                active = modified;
            } else if (READ_FILES_END.equals(value) || MODIFIED_FILES_END.equals(value)) {
                active = null;
            } else if (active != null && validTrackedPath(value)) {
                active.add(value);
            }
        }
    }

    private static boolean validTrackedPath(String path) {
        return path != null && !path.isBlank() && path.length() <= 300 && path.indexOf('\n') < 0
            && path.indexOf('\r') < 0 && path.indexOf('<') < 0 && path.indexOf('>') < 0;
    }

    private static void appendPaths(StringBuilder output, String start, String end, Set<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        output.append(start).append('\n');
        int omitted = 0;
        for (String path : paths) {
            if (output.length() + path.length() + end.length() + 2 > FILE_LIST_MAX_CHARS) {
                omitted++;
                continue;
            }
            output.append(path).append('\n');
        }
        output.append(end);
        if (omitted > 0) {
            output.append("\n[还有 ").append(omitted).append(" 条文件路径因长度限制省略]");
        }
        output.append('\n');
    }

    /** 按稳定落盘路径去重；同一图在多轮引用时只向模型重发一次。 */
    private static List<Attachment> attachments(List<Message> messages, int from, int to) {
        LinkedHashMap<String, Attachment> unique = new LinkedHashMap<>();
        for (int index = from; index < to; index++) {
            // 工具截图是一次性观察，折叠后由摘要里的文字证据代替，不跟着交接摘要无限累积
            if (messages.get(index).isToolImages()) {
                continue;
            }
            for (Attachment attachment : messages.get(index).attachments()) {
                unique.putIfAbsent(attachment.path(), attachment);
            }
        }
        return List.copyOf(unique.values());
    }
}
