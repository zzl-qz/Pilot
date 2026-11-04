package com.pilot.core.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一个 run 内按真实请求尝试累加；各币种分别汇总，不把重复的累计事件再求和。 */
public final class RequestTotals {
    public record Summary(int requests, int observedCacheRequests, long observedCacheInputTokens, long cacheReadTokens,
        long cacheWriteTokens, int pricedRequests, Map<String, BigDecimal> costs, boolean exact) {
    }

    private int requests;
    private int observedCacheRequests;
    private int pricedRequests;
    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheWriteTokens;
    private long observedCacheInputTokens;
    private boolean exact = true;
    private final Map<String, BigDecimal> costs = new LinkedHashMap<>();

    public synchronized void add(RequestReport report) {
        requests++;
        ModelUsage usage = report.usage();
        inputTokens = saturatedAdd(inputTokens, usage.inputTokens());
        outputTokens = saturatedAdd(outputTokens, usage.outputTokens());
        cacheReadTokens = saturatedAdd(cacheReadTokens, usage.cacheReadObserved() ? usage.cachedInputTokens() : 0);
        cacheWriteTokens = saturatedAdd(cacheWriteTokens, usage.cacheWriteObserved() ? usage.cacheWriteTokens() : 0);
        exact &= usage.exact();
        if (usage.cacheReadObserved()) {
            observedCacheRequests++;
            observedCacheInputTokens = saturatedAdd(observedCacheInputTokens, usage.inputTokens());
        }
        if (report.cost().total() != null) {
            pricedRequests++;
            costs.merge(report.cost().currency(), report.cost().total(), BigDecimal::add);
        }
    }

    public synchronized long input() {
        return inputTokens;
    }

    public synchronized long output() {
        return outputTokens;
    }

    public synchronized Summary summary() {
        return new Summary(requests, observedCacheRequests, observedCacheInputTokens, cacheReadTokens, cacheWriteTokens,
            pricedRequests, Map.copyOf(costs), exact);
    }

    private static long saturatedAdd(long total, long increment) {
        return total > Long.MAX_VALUE - increment ? Long.MAX_VALUE : total + increment;
    }
}
