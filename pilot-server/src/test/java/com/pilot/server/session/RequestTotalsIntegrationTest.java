package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.model.ContextComposition;
import com.pilot.core.model.ModelPricing;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.RequestReport;

class RequestTotalsIntegrationTest {
    @Test
    void actualAttemptsIncludeCompactionAndChildrenWithoutLegacyDoubleCounting() {
        var totals = new AgentRunService.RunUsageAccumulator();
        totals.main(new RunUsageUpdated(0, 0, 0, 0, 10000, false));
        totals.request(report("main", "USD"));
        totals.request(report("subagent", "CNY"));
        totals.request(report("compaction", "USD"));
        totals.child(new ModelUsage(100, 10, 0, true));
        var current = totals.main(new RunUsageUpdated(1, 100, 10, 100, 10000, true));
        assertEquals(3, current.modelRequests());
        assertEquals(300, current.inputTokens());
        assertEquals(30, current.outputTokens());
        assertEquals(100, current.contextTokens());
        assertEquals(10000, current.contextLimit());
        assertEquals(3, current.details().pricedRequests());
        assertEquals(240, current.details().cacheReadTokens());
        assertEquals(Set.of("USD", "CNY"), current.details().costs().keySet());
        assertEquals(0, current.details().costs().get("USD")
            .compareTo(current.details().costs().get("CNY").multiply(BigDecimal.valueOf(2))));
    }

    @Test
    void unobservedFailureDoesNotDiluteCacheRateAndIsNotFree() {
        var totals = new AgentRunService.RunUsageAccumulator();
        totals.request(report("main", "USD"));
        var failed = new RequestReport("failed", "main", "test", "generic", 1, 2, null, "failed",
            new ModelUsage(200, 0, 0, false), new ContextComposition(200, true, 0, List.of()),
            ModelPricing.Cost.unknown("USD", "failed"), null);
        var current = totals.request(failed);
        assertEquals(2, current.modelRequests());
        assertFalse(current.exact());
        assertEquals(1, current.details().observedCacheRequests());
        assertEquals(100, current.details().observedCacheInputTokens());
        assertEquals(80, current.details().cacheReadTokens());
        assertEquals(1, current.details().pricedRequests());
    }

    private static RequestReport report(String purpose, String currency) {
        var usage = new ModelUsage(100, 10, 0, 80, 0, true);
        return new RequestReport(UUID.randomUUID().toString(), purpose, "test", "generic", 1, 2, null, "completed",
            usage, new ContextComposition(100, true, 0, List.of()),
            new ModelPricing(currency, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "test")
                .estimate(usage),
            null);
    }
}
