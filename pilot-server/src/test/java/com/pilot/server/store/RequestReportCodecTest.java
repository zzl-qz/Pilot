package com.pilot.server.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.ModelRequestCompleted;
import com.pilot.core.event.RunUsageUpdated;
import com.pilot.core.model.ContextComposition;
import com.pilot.core.model.ModelPricing;
import com.pilot.core.model.ModelUsage;
import com.pilot.core.model.RequestReport;
import com.pilot.core.model.RequestTotals;

import tools.jackson.databind.json.JsonMapper;

class RequestReportCodecTest {
    private final EventCodec codec = new EventCodec(new JsonMapper());

    @Test
    void oldUsageEventsDoNotInventCacheCoverageOrCosts() {
        var old = (RunUsageUpdated)codec.decode("run.usage",
            "{\"modelRequests\":2,\"inputTokens\":1000,\"outputTokens\":10,\"exact\":true}");
        assertNull(old.details());
        assertEquals(0, old.cachedInputTokens());
    }

    @Test
    void reportAndCumulativeUsageRoundTripWithoutPromptContent() {
        var usage = new ModelUsage(1000, 10, 0, 800, 0, true);
        var pricing = new ModelPricing("USD", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, "test");
        var report = new RequestReport("id", "subagent", "model", "generic", 123, 456, null, "completed", usage,
            new ContextComposition(1000, true, 0, List.of(new ContextComposition.Part("system", "系统提示", 1000))),
            pricing.estimate(usage), new RequestReport.Prefix("implicit", "digest", 1, "append_only"));
        var event = new ModelRequestCompleted(report);
        assertEquals(event, codec.decode("model.request", codec.encode(event)));
        var totals = new RequestTotals();
        totals.add(report);
        var update = new RunUsageUpdated(1, 1000, 10, 800, 1000, 10000, true, totals.summary());
        assertEquals(update, codec.decode("run.usage", codec.encode(update)));
    }
}
