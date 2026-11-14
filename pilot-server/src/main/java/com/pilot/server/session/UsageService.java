package com.pilot.server.session;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.model.RequestTotals.Summary;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;

import lombok.RequiredArgsConstructor;

/**
 * 用量聚合：把落库的 {@code run.usage} 累计快照聚成日/会话两个视角，供设置页成本面板展示。
 *
 * <p>{@code run.usage} 是 run 内累计快照（每请求更新一次、含子代理归集），直接求和会把同一
 * run 的中间快照重复计入——必须每个 run 只取最后一条。事件按时间有序读入，Map 覆盖即得末值。
 *
 * <p>只覆盖主 Agent 与已归集的子 Agent（spawn/dispatch 都注入同一 run 级 sink）；
 * 中断 run 的最后快照也计入（已发生的成本不该因为中断就消失）。
 */
@Service
@RequiredArgsConstructor
public class UsageService {

    /** 单个 run 的末值快照。 */
    record RunTotal(String sessionId, String runId, Instant timestamp, RunUsageUpdated usage) {
    }

    /** 聚合结果：日序列 + 会话 Top + 合计。cachedInputTokens 与 inputTokens 同口径（总输入含缓存）。 */
    record DayTotal(LocalDate date, int requests, long inputTokens, long outputTokens, long cachedInputTokens) {
    }

    record SessionTotal(String sessionId, String title, int requests, long inputTokens, long outputTokens,
        long cachedInputTokens) {
    }

    record UsageReport(int days, List<DayTotal> daily, List<SessionTotal> sessions, long totalRequests,
        long totalInputTokens, long totalOutputTokens, long totalCachedInputTokens, Summary details) {
    }

    private static final int MAX_SESSION_ROWS = 10;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final SessionRepository repository;
    private final SessionService sessions;

    /**
     * @param days 聚合窗口天数；夹在 1-90，超大窗口会拖慢启动页
     */
    public UsageReport report(int days) {
        int window = Math.max(1, Math.min(90, days));
        Instant since = Instant.now().minusSeconds((long)window * 24 * 3600);
        List<EventEnvelope> events = repository.eventsOfType("run.usage", since);

        // 每 run 取最后一条快照（事件升序读入，覆盖即末值）
        Map<String, EventEnvelope> lastByRun = new LinkedHashMap<>();
        for (EventEnvelope event : events) {
            if (event.payload() instanceof RunUsageUpdated) {
                lastByRun.put(event.runId(), event);
            }
        }

        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        Map<String, long[]> bySession = new LinkedHashMap<>();
        for (EventEnvelope event : lastByRun.values()) {
            RunUsageUpdated usage = (RunUsageUpdated)event.payload();
            LocalDate date = LocalDate.ofInstant(event.timestamp(), ZONE);
            accumulate(byDay.computeIfAbsent(date, ignored -> new long[4]), usage);
            accumulate(bySession.computeIfAbsent(event.sessionId(), ignored -> new long[4]), usage);
        }

        List<
            DayTotal> daily =
                byDay
                    .entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> new DayTotal(entry.getKey(),
                        (int)entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3]))
                    .toList();
        List<SessionTotal> sessionTotals = bySession.entrySet().stream()
            .map(entry -> new SessionTotal(entry.getKey(),
                sessions.find(entry.getKey()).map(Session::title).orElse("已删除的会话"), (int)entry.getValue()[0],
                entry.getValue()[1], entry.getValue()[2], entry.getValue()[3]))
            .sorted(
                Comparator.comparingLong((SessionTotal total) -> total.inputTokens() + total.outputTokens()).reversed())
            .limit(MAX_SESSION_ROWS).toList();

        long requests = 0;
        long input = 0;
        long output = 0;
        long cached = 0;
        for (long[] totals : byDay.values()) {
            requests += totals[0];
            input += totals[1];
            output += totals[2];
            cached += totals[3];
        }
        int observedRequests = 0;
        int pricedRequests = 0;
        long observedInput = 0;
        long writes = 0;
        long observedCached = 0;
        boolean exact = true;
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        for (EventEnvelope event : lastByRun.values()) {
            RunUsageUpdated usage = (RunUsageUpdated)event.payload();
            exact &= usage.exact();
            var detail = usage.details();
            if (detail == null) {
                continue;
            }
            observedRequests += detail.observedCacheRequests();
            pricedRequests += detail.pricedRequests();
            observedInput += detail.observedCacheInputTokens();
            writes += detail.cacheWriteTokens();
            observedCached += detail.cacheReadTokens();
            detail.costs().forEach((currency, cost) -> costs.merge(currency, cost, BigDecimal::add));
        }
        var detail = new Summary((int)Math.min(Integer.MAX_VALUE, requests), observedRequests, observedInput,
            observedCached, writes, pricedRequests, Map.copyOf(costs), exact);
        return new UsageReport(window, daily, sessionTotals, requests, input, output, cached, detail);
    }

    private static void accumulate(long[] totals, RunUsageUpdated usage) {
        totals[0] += usage.modelRequests();
        totals[1] += usage.inputTokens();
        totals[2] += usage.outputTokens();
        totals[3] += usage.cachedInputTokens();
    }
}
