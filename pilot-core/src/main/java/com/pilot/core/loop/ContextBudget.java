package com.pilot.core.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.pilot.core.event.ContextCompacted;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.message.Message;

/**
 * 上下文预算：每次采样前按代价从低到高分四层收缩历史，并对 token 预算与字符内存上限解耦。
 *
 * <ol>
 *   <li>旧工具输出换占位符 + 超额旧工具截图降级（{@link #PRUNE_RATIO} 80% 起）——不花模型调用</li>
 *   <li>窗口内超长工具输出首尾截断（{@link #TRUNCATE_RATIO} 86% 起）——不花模型调用</li>
 *   <li>折叠成交接摘要（{@link #PRE_COMPACT_RATIO} 90% 起，
 *       {@link #FORCE_COMPACT_RATIO} 94% 以上允许多压几轮，压不动就退避）</li>
 *   <li>硬裁剪最旧的组（{@link #DROP_RATIO} 96% 起，一次退到 {@link #DROP_TARGET_RATIO} 72%）——不花模型调用</li>
 * </ol>
 *
 * <p>抽成独立类是为了让「什么时候压、压到什么水位、压不动怎么退避」这组判断
 * 能脱离 AgentLoop 单独测；AgentLoop 只剩「调用它」这一行。
 */
final class ContextBudget {
    /** 旧工具输出换占位符与超额工具截图降级的起点（预算占比；留足工作集空间避免频繁清掉刚读的文件）。 */
    private static final double PRUNE_RATIO = 0.80;
    /** 窗口内超长工具输出首尾截断的起点。 */
    private static final double TRUNCATE_RATIO = 0.86;
    /** 达到这个比例先压一次；超过强制档时允许多压几轮。 */
    private static final double PRE_COMPACT_RATIO = 0.90;
    private static final double FORCE_COMPACT_RATIO = 0.94;
    /** 仅在接近硬窗保护线时绕过防抖冷却，防止高水位频繁改写破坏 KV 前缀缓存。 */
    private static final double HARD_PROTECT_RATIO = 0.95;
    /** 摘要目标水位：压到这个位置，为后续数十步留出只追加空间。 */
    private static final double TARGET_RATIO = 0.60;
    /** 摘要也压不下去时，超过这个水位就硬裁剪。 */
    private static final double DROP_RATIO = 0.96;
    /** 硬裁剪滞回目标水位：一次腾出余量，避免熔断后每步重写 messages[1]。 */
    private static final double DROP_TARGET_RATIO = 0.72;
    /** 熔断后必须真正降到低水位才恢复，刚低于预压线不足以证明抖动已消失。 */
    private static final double CIRCUIT_RECOVERY_RATIO = 0.70;
    /** 批量清理后的滞回冷却步长（相对各自预算的比例），确保一次清理后有足够长只追加跑道。 */
    private static final double COOLDOWN_STEP_RATIO = 0.16;
    /** Tier 1 清理时保留最近几条工具截图消息，更早的降级为文字占位。 */
    private static final int PRUNE_KEEP_TOOL_IMAGES = 3;
    private static final int MAX_COMPACT_ROUNDS = 3;
    /** 连续三次摘要都没有收益后熔断，避免摘要模型自身形成消耗循环。 */
    private static final int MAX_CONSECUTIVE_COMPACT_FAILURES = 3;
    /** 成功压缩后的三个采样边界内再次越线，视为一次快速回填。 */
    private static final int RAPID_REFILL_WINDOW_APPLIES = 3;
    /** 快速回填连续出现三次后熔断，之后只走本地裁剪。 */
    private static final int MAX_RAPID_REFILLS = 3;

    /** 摘要压缩的动作本身；抽成函数式接口是为了能单独测「压不动时怎么退避」。 */
    @FunctionalInterface
    interface Compactor {
        ContextCompactor.Outcome compact(List<Message> messages, int charBudget, int tokenBudget);
    }

    private final Compactor compactor;
    /** 上次压缩没有实际收益时的上下文大小；涨过这个水位之前不再尝试，避免每步白跑一次摘要。 */
    private int backoffUntilChars;
    private int backoffUntilTokens;
    private int consecutiveCompactFailures;
    private int rapidRefillStreak;
    private int applyIndex;
    private int lastSuccessfulCompactApply = -1;
    private boolean rapidRefillCounted;
    private boolean compactCircuitOpen;
    private int nextPruneChars;
    private int nextPruneTokens;
    private int nextTruncateChars;
    private int nextTruncateTokens;
    private int lastTokenBudget;
    private int lastCharBudget;

    ContextBudget(Compactor compactor) {
        this.compactor = compactor;
    }

    /**
     * 单预算兼容入口（字符上限与 token 上限取同值，供旧单测使用）。
     *
     * @return 本次是否真的压缩过
     */
    boolean apply(List<Message> messages, int limit, Consumer<SessionEvent> events) {
        return apply(messages, limit, limit, 0, events, ContextCompactor.observedPrefix(messages));
    }

    boolean apply(List<Message> messages, int limit, Consumer<SessionEvent> events, int observedThrough) {
        return apply(messages, limit, limit, 0, events, observedThrough);
    }

    /**
     * 双预算版本：token 预算反映模型输入窗口（含工具/协议/附件固定开销），字符上限独立作为内存护栏。
     * 低水位（&lt; 80%）与冷却期内严格保持只追加，不改写历史前缀。
     *
     * @return 本次是否真的触发了交接摘要压缩
     */
    boolean apply(List<Message> messages, int tokenBudget, int charBudget, int fixedOverheadTokens,
        Consumer<SessionEvent> events, int observedThrough) {
        int boundary = Math.max(1, Math.min(observedThrough, messages.size()));
        applyIndex++;
        int tokensLimit = Math.max(1, tokenBudget);
        int charsLimit = Math.max(1, charBudget);
        int overhead = Math.max(0, fixedOverheadTokens);
        resetCooldownIfBudgetChanged(tokensLimit, charsLimit);
        // 单步大输出直接跨过预压线时，先只读预演本地清理能否压回预压线以下：压不回去就先别原地改写旧前缀，
        // 把没动过的热前缀交给下面的摘要压缩，否则这次压缩请求会白白丢掉原本命中的 KV 前缀缓存。
        boolean compactFirst = !compactCircuitOpen && !inBackoff(messages, overhead)
            && usage(messages, charsLimit, tokensLimit, overhead) >= PRE_COMPACT_RATIO
            && !localCleanupSuffices(messages, tokensLimit, charsLimit, overhead, boundary);
        if (!compactFirst) {
            pruneAndTruncate(messages, tokensLimit, charsLimit, overhead, boundary);
        }

        double usage = usage(messages, charsLimit, tokensLimit, overhead);
        if (usage < PRE_COMPACT_RATIO) {
            updateLowPressureState(usage);
            dropIfOverBudget(messages, charsLimit, tokensLimit, overhead, boundary);
            return false;
        }
        recordRapidRefill();
        if (compactCircuitOpen || inBackoff(messages, overhead)) {
            // 熔断或退避时只剩本地清理；前面为保住热前缀跳过的那次在这里补回来
            if (compactFirst) {
                pruneAndTruncate(messages, tokensLimit, charsLimit, overhead, boundary);
            }
            dropIfOverBudget(messages, charsLimit, tokensLimit, overhead, boundary);
            return false;
        }
        boolean reduced = runCompactionRounds(messages, tokensLimit, charsLimit, overhead, boundary, usage, events);
        if (!reduced && compactFirst) {
            // 摘要压缩没生效：回退本地清理，再让硬裁剪兜底
            pruneAndTruncate(messages, tokensLimit, charsLimit, overhead, boundary);
        }
        updateCompactionOutcome(messages, overhead, reduced);
        dropIfOverBudget(messages, charsLimit, tokensLimit, overhead, boundary);
        return reduced;
    }

    private void resetCooldownIfBudgetChanged(int tokensLimit, int charsLimit) {
        if (lastTokenBudget == tokensLimit && lastCharBudget == charsLimit) {
            return;
        }
        nextPruneChars = 0;
        nextPruneTokens = 0;
        nextTruncateChars = 0;
        nextTruncateTokens = 0;
        lastTokenBudget = tokensLimit;
        lastCharBudget = charsLimit;
    }

    private void pruneAndTruncate(List<Message> messages, int tokensLimit, int charsLimit, int overhead, int boundary) {
        localCleanup(messages, tokensLimit, charsLimit, overhead, boundary, true);
    }

    /** 只读预演：本地清理能不能把水位压回预压线以下。真实历史与滞回水位都不动。 */
    private boolean localCleanupSuffices(List<Message> messages, int tokensLimit, int charsLimit, int overhead,
        int boundary) {
        List<Message> probe = new ArrayList<>(messages);
        localCleanup(probe, tokensLimit, charsLimit, overhead, boundary, false);
        return usage(probe, charsLimit, tokensLimit, overhead) < PRE_COMPACT_RATIO;
    }

    /**
     * 第一、二层本地清理：占位符化、旧工具图降级、超长输出首尾截断。
     *
     * @param commit {@code false} 时只预演（传进来的应是副本），不推进下一次批量清理的滞回水位
     */
    private void localCleanup(List<Message> messages, int tokensLimit, int charsLimit, int overhead, int boundary,
        boolean commit) {
        int charStep = Math.max(1, (int)(charsLimit * COOLDOWN_STEP_RATIO));
        int tokenStep = Math.max(1, (int)(tokensLimit * COOLDOWN_STEP_RATIO));
        // 这两层不花模型调用。仅越过滞回冷却步长或触及硬保护线（95%）才批量清理一次，
        // 低水位和冷却期严格 append_only。
        double usage = usage(messages, charsLimit, tokensLimit, overhead);
        int curChars = ContextCompactor.totalChars(messages);
        int curTokens = ContextCompactor.estimateTokens(messages) + overhead;
        int pruneCharsAt = nextPruneChars;
        int pruneTokensAt = nextPruneTokens;
        if (usage >= PRUNE_RATIO
            && (usage >= HARD_PROTECT_RATIO || curTokens >= nextPruneTokens || curChars >= nextPruneChars)) {
            ContextCompactor.dropStaleToolImages(messages, PRUNE_KEEP_TOOL_IMAGES, boundary);
            ContextCompactor.pruneToolOutputs(messages, tokensLimit, charsLimit, boundary);
            pruneCharsAt = ContextCompactor.totalChars(messages) + charStep;
            pruneTokensAt = ContextCompactor.estimateTokens(messages) + overhead + tokenStep;
            usage = usage(messages, charsLimit, tokensLimit, overhead);
        }
        curChars = ContextCompactor.totalChars(messages);
        curTokens = ContextCompactor.estimateTokens(messages) + overhead;
        int truncateCharsAt = nextTruncateChars;
        int truncateTokensAt = nextTruncateTokens;
        if (usage >= TRUNCATE_RATIO
            && (usage >= HARD_PROTECT_RATIO || curTokens >= nextTruncateTokens || curChars >= nextTruncateChars)) {
            ContextCompactor.truncateLongToolOutputs(messages, boundary);
            truncateCharsAt = ContextCompactor.totalChars(messages) + charStep;
            truncateTokensAt = ContextCompactor.estimateTokens(messages) + overhead + tokenStep;
        }
        if (!commit) {
            return;
        }
        nextPruneChars = pruneCharsAt;
        nextPruneTokens = pruneTokensAt;
        nextTruncateChars = truncateCharsAt;
        nextTruncateTokens = truncateTokensAt;
    }

    private void updateLowPressureState(double usage) {
        backoffUntilChars = 0;
        backoffUntilTokens = 0;
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
    }

    private boolean inBackoff(List<Message> messages, int overhead) {
        // 上一次摘要白跑了一趟模型：上下文再涨 5% 之前别再试
        int currentChars = ContextCompactor.totalChars(messages);
        int currentTokens = ContextCompactor.estimateTokens(messages) + overhead;
        return (backoffUntilChars > 0 || backoffUntilTokens > 0) && currentChars < backoffUntilChars
            && currentTokens < backoffUntilTokens;
    }

    private boolean runCompactionRounds(List<Message> messages, int tokensLimit, int charsLimit, int overhead,
        int boundary, double usage, Consumer<SessionEvent> events) {
        int rounds = usage >= FORCE_COMPACT_RATIO ? MAX_COMPACT_ROUNDS : 1;
        int targetChars = Math.max(1, (int)(charsLimit * TARGET_RATIO));
        int targetTokens = Math.max(1, (int)(tokensLimit * TARGET_RATIO) - overhead);
        boolean reduced = false;
        int end = boundary;
        for (int round = 0; round < rounds; round++) {
            int before = messages.size();
            ContextCompactor.Outcome outcome = compactor.compact(messages.subList(0, end), targetChars, targetTokens);
            end -= before - messages.size();
            if (!outcome.compacted()) {
                break;
            }
            reduced = true;
            events.accept(new ContextCompacted(outcome.coveredMessages(), outcome.charactersBefore(),
                outcome.charactersAfter(), outcome.summary()));
            if (usage(messages, charsLimit, tokensLimit, overhead) < PRE_COMPACT_RATIO) {
                break;
            }
        }
        return reduced;
    }

    private void updateCompactionOutcome(List<Message> messages, int overhead, boolean reduced) {
        if (reduced) {
            backoffUntilChars = 0;
            backoffUntilTokens = 0;
            consecutiveCompactFailures = 0;
            lastSuccessfulCompactApply = applyIndex;
            rapidRefillCounted = false;
            return;
        }
        int totalChars = ContextCompactor.totalChars(messages);
        int totalTokens = ContextCompactor.estimateTokens(messages) + overhead;
        backoffUntilChars = (int)(totalChars * 1.05) + 1;
        backoffUntilTokens = (int)(totalTokens * 1.05) + 1;
        consecutiveCompactFailures++;
        if (consecutiveCompactFailures >= MAX_CONSECUTIVE_COMPACT_FAILURES) {
            compactCircuitOpen = true;
        }
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
        return usage(messages, charBudget, tokenBudget, 0);
    }

    static double usage(List<Message> messages, int charBudget, int tokenBudget, int fixedOverheadTokens) {
        double byChars = ContextCompactor.totalChars(messages) / (double)Math.max(1, charBudget);
        double byTokens = (ContextCompactor.estimateTokens(messages) + Math.max(0, fixedOverheadTokens))
            / (double)Math.max(1, tokenBudget);
        return Math.max(byChars, byTokens);
    }

    private static void dropIfOverBudget(List<Message> messages, int charBudget, int tokenBudget,
        int fixedOverheadTokens, int boundary) {
        if (usage(messages, charBudget, tokenBudget, fixedOverheadTokens) >= DROP_RATIO) {
            List<Message> tail = messages.subList(boundary, messages.size());
            int tailChars = ContextCompactor.totalChars(tail);
            int tailTokens = ContextCompactor.estimateTokens(tail) + Math.max(0, fixedOverheadTokens);
            int targetChars = Math.max(1, (int)(charBudget * DROP_TARGET_RATIO) - tailChars);
            int targetTokens = Math.max(1, (int)(tokenBudget * DROP_TARGET_RATIO) - tailTokens);
            ContextCompactor.dropOldestGroups(messages.subList(0, boundary), targetChars, targetTokens);
        }
    }
}
