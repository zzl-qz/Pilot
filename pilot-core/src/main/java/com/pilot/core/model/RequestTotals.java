package com.pilot.core.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一个 run 内按真实请求尝试累加；各币种分别汇总，不把重复的累计事件再求和。 */
public final class RequestTotals {
    public record Summary(int requests, int observedCacheRequests, long observedCacheInputTokens,
                          long cacheReadTokens, long cacheWriteTokens, int pricedRequests,
                          Map<String, BigDecimal> costs, boolean exact) { }
    private int requests, observedCacheRequests, pricedRequests;
    private long input, output, cached, written, observedInput;
    private boolean exact = true;
    private final Map<String, BigDecimal> costs = new LinkedHashMap<>();
    public synchronized void add(RequestReport report) {
        requests++;
        ModelUsage usage = report.usage();
        input = sum(input, usage.inputTokens()); output = sum(output, usage.outputTokens());
        cached = sum(cached, usage.cacheReadObserved() ? usage.cachedInputTokens() : 0);
        written = sum(written, usage.cacheWriteObserved() ? usage.cacheWriteTokens() : 0);
        exact &= usage.exact();
        if (usage.cacheReadObserved()) { observedCacheRequests++; observedInput = sum(observedInput, usage.inputTokens()); }
        if (report.cost().total() != null) {
            pricedRequests++; costs.merge(report.cost().currency(), report.cost().total(), BigDecimal::add);
        }
    }
    public synchronized long input() { return input; }
    public synchronized long output() { return output; }
    public synchronized Summary summary() {
        return new Summary(requests, observedCacheRequests, observedInput, cached, written, pricedRequests, Map.copyOf(costs), exact);
    }
    private static long sum(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
}
