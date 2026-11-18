package com.pilot.core.event;

import java.util.List;

import com.pilot.core.plan.PlanItem;

/**
 * 非「完全成功」的终态：部分交付、阻塞、等待用户、预算用尽等。
 *
 * <p>它和 {@link RunFailed} 的区别只有一条：{@link #outcome()} 不是
 * {@link RunOutcome#FAILED} 时，界面不能把它渲染成红色失败，也不能把任务
 * 标成失败；它必须保留改动、证据、未完成项和下一步动作，允许继续。
 *
 * <p>字段与 {@link RunCompleted} 对齐，并额外带 {@code blockedSteps} 与
 * {@code nextActions}：失败卡不再需要二次请求才知道改了什么。
 */
public record RunEnded(RunOutcome outcome, String code, String summary, List<String> changedFiles, List<String> checks,
    Integer planDone, Integer planTotal, List<String> openSteps, List<String> blockedSteps, List<String> nextActions,
    Boolean resumable) implements SessionEvent {

    public RunEnded {
        outcome = outcome == null ? RunOutcome.PARTIAL : outcome;
        code = code == null ? "" : code.strip();
        summary = summary == null ? "" : summary;
        changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
        checks = List.copyOf(checks == null ? List.of() : checks);
        openSteps = List.copyOf(openSteps == null ? List.of() : openSteps);
        blockedSteps = List.copyOf(blockedSteps == null ? List.of() : blockedSteps);
        nextActions = List.copyOf(nextActions == null ? List.of() : nextActions);
        planDone = planDone == null ? 0 : Math.max(0, planDone);
        planTotal = Math.max(planDone, planTotal == null ? 0 : planTotal);
        resumable = resumable == null ? outcome.resumable() : resumable;
    }

    /** 从计划快照自动算完成度与未完成/阻塞项。 */
    public static RunEnded of(RunOutcome outcome, String code, String summary, List<String> changedFiles,
        List<String> checks, List<PlanItem> plan, List<String> nextActions) {
        List<PlanItem> items = plan == null ? List.of() : plan;
        int done = (int)items.stream().filter(PlanItem::done).count();
        List<String> open = items.stream().filter(PlanItem::open).map(PlanItem::step).toList();
        List<String> blocked = items.stream().filter(PlanItem::blocked).map(PlanItem::step).toList();
        return new RunEnded(outcome, code, summary, changedFiles, checks, done, items.size(), open, blocked,
            nextActions, outcome.resumable());
    }

    @Override
    public EventType type() {
        return EventType.RUN_ENDED;
    }
}
