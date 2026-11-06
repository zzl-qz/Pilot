package com.pilot.core.loop;

import java.util.List;
import java.util.function.Consumer;

import com.pilot.core.event.ContextCompacted;
import com.pilot.core.event.SessionEvent;

/**
 * 上下文预算：每次采样前按代价从低到高分四层收缩历史。
 *
 * <ol>
 *   <li>旧工具输出换占位符（{@link #PRUNE_RATIO} 55% 起）——不花模型调用</li>
 *   <li>窗口内超长工具输出首尾截断（{@link #TRUNCATE_RATIO} 70% 起）——不花模型调用</li>
 *   <li>折叠成交接摘要（{@link #PRE_COMPACT_RATIO} 80% 起，
 *       {@link #FORCE_COMPACT_RATIO} 90% 以上允许多压几轮，压不动就退避）</li>
 *   <li>硬裁剪最旧的组（{@link #DROP_RATIO} 97% 起）——不花模型调用</li>
 * </ol>
 *
 * <p>抽成独立类是为了让「什么时候压、压到什么水位、压不动怎么退避」这组判断
 * 能脱离 AgentLoop 单独测；AgentLoop 只剩「调用它」这一行。
 */
final class ContextBudget {
    /** 旧工具输出换占位符的起点（预算占比）。 */
    private static final double PRUNE_RATIO = 0.55;
    /** 窗口内超长工具输出首尾截断的起点。 */
    private static final double TRUNCATE_RATIO = 0.70;
    /** 摘要也压不下去时，超过这个水位就硬裁剪。 */
    private static final double DROP_RATIO = 0.97;
    /** 摘要目标水位：压到这个位置，留出后续几步的余量。 */
    private static final double TARGET_RATIO = 0.80;
    /** 达到这个比例先压一次；超过强制档时允许多压几轮。 */
    private static final double PRE_COMPACT_RATIO = 0.80;
    private static final double FORCE_COMPACT_RATIO = 0.90;
    private static final int MAX_COMPACT_ROUNDS = 3;
    /** 连续三次摘要都没有收益后熔断，避免摘要模型自身形成消耗循环。 */
    private static final int MAX_CONSECUTIVE_COMPACT_FAILURES = 3;
    /** 成功压缩后的三个采样边界内再次越线，视为一次快速回填。 */
    private static final int RAPID_REFILL_WINDOW_APPLIES = 3;
    /** 快速回填连续出现三次后熔断，之后只走本地裁剪。 */
    private static final int MAX_RAPID_REFILLS = 3;
    /** 熔断后必须真正降到低水位才恢复，刚低于预压线不足以证明抖动已消失。 */
    private static final double CIRCUIT_RECOVERY_RATIO = 0.55;

    /** 摘要压缩的动作本身；抽成函数式接口是为了能单独测「压不动时怎么退避」。 */
    @FunctionalInterface
    interface Compactor {
        ContextCompactor.Outcome compact(List<Message> messages, int charBudget, int tokenBudget);
    }

    private final Compactor compactor;
    /** 上次压缩没有实际收益时的上下文大小；涨过这个水位之前不再尝试，避免每步白跑一次摘要。 */
    private int backoffUntil;
    private int consecutiveCompactFailures;
    private int rapidRefillStreak;
    private int applyIndex;
    private int lastSuccessfulCompactApply = -1;
    private boolean rapidRefillCounted;
    private boolean compactCircuitOpen;
    private int nextPruneAt;
    private int nextTruncateAt;
    private int lastLimit;

    ContextBudget(Compactor compactor) {
        this.compactor = compactor;
    }

    /**
     * @param limit 上下文预算（字符数；token 预算取同值，见 {@link #usage}）
     * @return 本次是否真的压缩过
     */
    boolean apply(List<Message> messages, int limit, Consumer<SessionEvent> events) {
        return apply(messages, limit, events, ContextCompactor.observedPrefix(messages));
    }

    boolean apply(List<Message> messages, int limit, Consumer<SessionEvent> events, int observedThrough) {
        int boundary = Math.max(1, Math.min(observedThrough, messages.size()));
        applyIndex++;
        // 字符预算与 token 预算同值：英文按 4 字符 1 token 远远够用，中文接近 1 字符 1 token，
        // 取两者里更满的那个，避免中文会话被「字符还不多」骗过去，一路涨到超窗。
        int charBudget = Math.max(1, limit);
        int tokenBudget = charBudget;
        double usage = usage(messages, charBudget, tokenBudget);
        if (lastLimit != limit) {
            nextPruneAt = 0;
            nextTruncateAt = 0;
            lastLimit = limit;
        }
        int rewriteStep = Math.max(1, (int)(limit * 0.05));

        // 第一、二层不花模型调用，只是把「已经读过的内容」压缩成占位符与头尾
        // 低压区间内批量处理旧输出，避免每新增一个工具结果就改写已缓存的历史前缀。
        // 达到摘要水位后立即恢复裁剪，不为缓存牺牲上下文预算。
        if (usage >= PRUNE_RATIO
            && (usage >= PRE_COMPACT_RATIO || ContextCompactor.totalChars(messages) >= nextPruneAt)) {
            ContextCompactor.pruneToolOutputs(messages, limit, boundary);
            nextPruneAt = ContextCompactor.totalChars(messages) + rewriteStep;
        }
        if (usage >= TRUNCATE_RATIO
            && (usage >= PRE_COMPACT_RATIO || ContextCompactor.totalChars(messages) >= nextTruncateAt)) {
            ContextCompactor.truncateLongToolOutputs(messages, boundary);
            nextTruncateAt = ContextCompactor.totalChars(messages) + rewriteStep;
        }

        usage = usage(messages, charBudget, tokenBudget);
        if (usage < PRE_COMPACT_RATIO) {
            backoffUntil = 0;
            consecutiveCompactFailures = 0;
            if (usage < CIRCUIT_RECOVERY_RATIO) {
                compactCircuitOpen = false;
                rapidRefillStreak = 0;
                lastSuccessfulCompactApply = -1;
                rapidRefillCounted = false;
            } else if (lastSuccessfulCompactApply > 0
                && applyIndex - lastSuccessfulCompactApply > RAPID_REFILL_WINDOW_APPLIES) {
                rapidRefillStreak = 0;
                lastSuccessfulCompactApply = -1;
                rapidRefillCounted = false;
            }
            dropIfOverBudget(messages, limit, boundary);
            return false;
        }

        recordRapidRefill();
        if (compactCircuitOpen) {
            dropIfOverBudget(messages, limit, boundary);
            return false;
        }

        // 上一次摘要白跑了一趟模型：上下文再涨 5% 之前别再试
        int current = ContextCompactor.totalChars(messages);
        if (backoffUntil > 0 && current < backoffUntil) {
            dropIfOverBudget(messages, limit, boundary);
            return false;
        }

        int rounds = usage >= FORCE_COMPACT_RATIO ? MAX_COMPACT_ROUNDS : 1;
        int targetChars = (int)(charBudget * TARGET_RATIO);
        int targetTokens = (int)(tokenBudget * TARGET_RATIO);
        boolean reduced = false;
        for (int round = 0; round < rounds; round++) {
            int before = messages.size();
            ContextCompactor.Outcome outcome =
                compactor.compact(messages.subList(0, boundary), targetChars, targetTokens);
            boundary -= before - messages.size();
            if (!outcome.compacted()) {
                break;
            }
            reduced = true;
            events.accept(new ContextCompacted(outcome.coveredMessages(), outcome.charactersBefore(),
                outcome.charactersAfter(), outcome.summary()));
            if (usage(messages, charBudget, tokenBudget) < PRE_COMPACT_RATIO) {
                break;
            }
        }

        int total = ContextCompactor.totalChars(messages);
        if (reduced) {
            backoffUntil = 0;
            consecutiveCompactFailures = 0;
            lastSuccessfulCompactApply = applyIndex;
            rapidRefillCounted = false;
        } else {
            backoffUntil = (int)(total * 1.05) + 1;
            consecutiveCompactFailures++;
            if (consecutiveCompactFailures >= MAX_CONSECUTIVE_COMPACT_FAILURES) {
                compactCircuitOpen = true;
            }
        }
        dropIfOverBudget(messages, limit, boundary);
        return reduced;
    }

    private void recordRapidRefill() {
        if (lastSuccessfulCompactApply < 0 || rapidRefillCounted) {
            return;
        }
        int distance = applyIndex - lastSuccessfulCompactApply;
        rapidRefillCounted = true;
        if (distance <= RAPID_REFILL_WINDOW_APPLIES) {
            rapidRefillStreak++;
            if (rapidRefillStreak >= MAX_RAPID_REFILLS) {
                compactCircuitOpen = true;
            }
            return;
        }
        rapidRefillStreak = 0;
        lastSuccessfulCompactApply = -1;
    }

    /** 当前占用 / 预算，取字符与 token 两个口径里更满的那个。 */
    static double usage(List<Message> messages, int charBudget, int tokenBudget) {
        double byChars = ContextCompactor.totalChars(messages) / (double)charBudget;
        double byTokens = ContextCompactor.estimateTokens(messages) / (double)tokenBudget;
        return Math.max(byChars, byTokens);
    }

    private static void dropIfOverBudget(List<Message> messages, int limit, int boundary) {
        if (ContextCompactor.totalChars(messages) > limit * DROP_RATIO) {
            int tail = ContextCompactor.totalChars(messages.subList(boundary, messages.size()));
            ContextCompactor.dropOldestGroups(messages.subList(0, boundary), Math.max(1, limit - tail));
        }
    }
}
