package com.pilot.core.event;

import java.util.List;

/**
 * 运行完成：结果摘要、变更文件与已执行的验证项。
 *
 * <p>「运行结束」不等于「任务干完了」：模型经常在中途给出总结文本就收尾，
 * 而计划清单里还有 pending 项。把计划完成度一起带上，客户端才能直接回答
 * 「到底干完了没有、还剩哪些」，而不是只凭模型自述判断。
 *
 * <p>计数字段用包装类型而不是 {@code int}：Jackson 3 默认开启
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES}，历史事件里没有这些字段，
 * 用 {@code int} 会在反序列化时直接抛 MismatchedInputException，
 * 导致整条会话的 SSE 回放失败。缺字段时统一归一化成 0 / 空列表。
 *
 * @param planDone   计划中已完成的项数
 * @param planTotal  计划总项数；0 表示本次运行没有维护计划
 * @param openSteps  仍未完成的步骤（按原顺序）
 */
public record RunCompleted(String summary, List<String> changedFiles, List<String> checks,
                           Integer planDone, Integer planTotal, List<String> openSteps) implements SessionEvent {
    public RunCompleted {
        changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
        checks = List.copyOf(checks == null ? List.of() : checks);
        openSteps = List.copyOf(openSteps == null ? List.of() : openSteps);
        planDone = planDone == null ? 0 : Math.max(0, planDone);
        planTotal = Math.max(planDone, planTotal == null ? 0 : planTotal);
    }

    /** 兼容旧调用方与历史事件：不携带计划信息。 */
    public RunCompleted(String summary, List<String> changedFiles, List<String> checks) {
        this(summary, changedFiles, checks, 0, 0, List.of());
    }

    /** 计划里是否还有没做完的步骤。 */
    public boolean planOpen() {
        return !openSteps.isEmpty();
    }

    @Override
    public EventType type() {
        return EventType.RUN_COMPLETED;
    }
}
