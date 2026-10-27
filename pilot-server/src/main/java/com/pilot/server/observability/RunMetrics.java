package com.pilot.server.observability;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 运行指标：只做进程内累计，重启归零。
 *
 * <p>日志只能回答「这一轮发生了什么」，回答不了「最近成功率如何、压缩是不是太频繁」。
 * 这里按 run 结束状态、事件类型和失败码累加计数，并通过 {@code /api/metrics} 暴露，
 * 用来区分「模型不行」和「工程有问题」。
 */
@Component
public class RunMetrics {
    /** 事件类型 → 次数。键形如 {@code event.tool.started}。 */
    private final ConcurrentMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final LongAdder runs = new LongAdder();
    private final LongAdder runDurationMs = new LongAdder();

    /** 记一次事件或结果。 */
    public void count(String name) {
        counters.computeIfAbsent(name, key -> new LongAdder()).increment();
    }

    /** 记一次运行的结束：状态计数 + 耗时。 */
    public void recordRun(String status, long durationMs) {
        runs.increment();
        runDurationMs.add(Math.max(0L, durationMs));
        count("run." + status);
    }

    public Snapshot snapshot() {
        Map<String, Long> values = new TreeMap<>();
        counters.forEach((name, adder) -> values.put(name, adder.sum()));
        long total = runs.sum();
        long duration = runDurationMs.sum();
        return new Snapshot(values, total, duration, total == 0 ? 0 : duration / total);
    }

    /**
     * @param counters     各类计数
     * @param runs         已结束的运行数
     * @param totalRunMs   所有运行累计耗时
     * @param averageRunMs 平均耗时
     */
    public record Snapshot(Map<String, Long> counters, long runs, long totalRunMs, long averageRunMs) {
    }
}
