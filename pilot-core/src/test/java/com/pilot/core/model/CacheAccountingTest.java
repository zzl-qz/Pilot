package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.Attachment;
import com.pilot.core.loop.Message;

import tools.jackson.databind.ObjectMapper;

class CacheAccountingTest {
    private final ObjectMapper json = new ObjectMapper();

    private ModelUsage parse(String value) {
        return ModelUsageParser.parse(json.readTree(value));
    }

    private ModelPricing prices() {
        return new ModelPricing("USD", new BigDecimal("2"), new BigDecimal("0.2"), new BigDecimal("2.5"),
            new BigDecimal("8"), "测试报价");
    }

    @Test
    void distinguishesAbsentFromObservedZero() {
        var absent = parse("{\"prompt_tokens\":1000,\"completion_tokens\":10}");
        var zero =
            parse("{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":0}}");
        assertTrue(absent.exact());
        assertFalse(absent.cacheReadObserved());
        assertTrue(zero.cacheReadObserved());
        assertNull(prices().estimate(absent).total());
    }

    @Test
    void recognizesDeepSeekAndDoesNotDoubleCountMixedGatewayFields() {
        var deepseek = parse(
            "{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_cache_hit_tokens\":800,\"prompt_cache_miss_tokens\":200}");
        assertEquals(800, deepseek.cachedInputTokens());
        assertEquals(.8, deepseek.cacheHitRatio());
        var mixed = parse(
            "{\"prompt_tokens\":1000,\"completion_tokens\":10,\"cache_read_input_tokens\":700,\"cache_creation_input_tokens\":200}");
        assertEquals(1000, mixed.inputTokens());
        assertEquals(100, mixed.fullPriceInputTokens());
        var anthropic = parse(
            "{\"input_tokens\":100,\"output_tokens\":10,\"cache_read_input_tokens\":700,\"cache_creation_input_tokens\":200}");
        assertEquals(mixed, anthropic);
    }

    @Test
    void parsesWritesAndKeepsExplicitZeroInsteadOfFallingBack() {
        var usage = parse(
            "{\"input_tokens\":1000,\"output_tokens\":10,\"cached_tokens\":900,\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":400}}");
        assertEquals(0, usage.cachedInputTokens());
        assertEquals(400, usage.cacheWriteTokens());
        assertTrue(usage.cacheWriteObserved());
        assertTrue(usage.cacheReadObserved());
        var partial = parse("{\"input_tokens\":1000}");
        assertFalse(partial.exact());
    }

    @Test
    void clampsImpossibleCountsWithoutOverflow() {
        var usage = new ModelUsage(100, 2, 0, Long.MAX_VALUE, Long.MAX_VALUE, true);
        assertEquals(100, usage.cachedInputTokens());
        assertEquals(0, usage.cacheWriteTokens());
        assertEquals(0, usage.fullPriceInputTokens());
    }

    @Test
    void pricesEachBucketAndNeverCallsUnknownFree() {
        var cost = prices().estimate(new ModelUsage(1000, 10, 0, 700, 200, true));
        assertEquals(0, new BigDecimal("0.00092").compareTo(cost.total()));
        assertEquals(cost.total(), cost.input().add(cost.cachedInput()).add(cost.cacheWrite()).add(cost.output()));
        assertNull(ModelPricing.unknown().estimate(new ModelUsage(1000, 10, 0, true)).total());
        var free = new ModelPricing("USD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "免费端点");
        assertEquals(0, BigDecimal.ZERO.compareTo(free.estimate(new ModelUsage(1000, 10, 0, true)).total()));
        assertNull(free.estimate(new ModelUsage(1000, 10, 0, false)).total());
        assertThrows(IllegalArgumentException.class,
            () -> new ModelPricing("USD", new BigDecimal("-1"), null, null, null, ""));
    }

    @Test
    void canonicalizesOnlyObjects() {
        var left = new LinkedHashMap<String, Object>();
        left.put("z", 2);
        left.put("a", Map.of("d", 4, "b", 3));
        var right = Map.of("a", Map.of("b", 3, "d", 4), "z", 2);
        assertEquals(json.writeValueAsString(CanonicalJson.ordered(left)),
            json.writeValueAsString(CanonicalJson.ordered(right)));
        assertEquals(List.of(3, 1, 2), CanonicalJson.ordered(List.of(3, 1, 2)));
        assertEquals(List.of(3, 1, 2), CanonicalJson.ordered(new int[] {3, 1, 2}));
        assertEquals(json.writeValueAsString(CanonicalJson.ordered(List.of(left))),
            json.writeValueAsString(CanonicalJson.ordered(new Object[] {right})));
    }

    @Test
    void profilesUseEndpointsNotModelNames() {
        var unknown = ModelProfile.resolve(URI.create("https://gateway.example/v1"), "claude-test", "auto", "auto", "",
            null, null, "short", null);
        assertEquals("generic", unknown.provider());
        assertFalse(unknown.streamUsage());
        assertFalse(unknown.instructionMessages());
        assertThrows(IllegalArgumentException.class, () -> ModelProfile.resolve(URI.create("https://api.openai.com/v1"),
            "gpt-6", "auto", "explicit", "", null, null, "short", null));
        var old = ModelProfile.resolve(URI.create("https://api.openai.com/v1"), "gpt-4o-mini", "auto", "auto", "", null,
            null, "short", null);
        var recent = ModelProfile.resolve(URI.create("https://api.openai.com/v1"), "gpt-5.6", "auto", "auto", "", null,
            null, "short", null);
        assertTrue(old.cacheKey());
        assertFalse(recent.cacheKey());
    }

    @Test
    void explicitMarkersAreBoundedAndOriginalMessagesStayImmutable() {
        var profile = ModelProfile.resolve(URI.create("https://gateway.example/v1"), "alias", "anthropic-compatible",
            "auto", "1h", false, false, "short", null);
        var client = new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), json,
            URI.create("https://gateway.example/v1"), "unused", "alias").withProfile(profile);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("fixed\n\n[Runtime task context]\nchanging"));
        for (int i = 0; i < 30; i++) {
            messages.add(Message.user("question " + i));
            messages.add(Message.assistant("answer " + i, List.of()));
        }
        var body =
            client.requestBody(messages, List.of(new ToolDefinition("read", "read", Map.of("type", "object"))), true);
        String wire = json.writeValueAsString(body);
        assertEquals(3, wire.split("cache_control", -1).length - 1);
        assertTrue(wire.contains("1h"));
        assertFalse(body.containsKey("stream_options"));
        assertEquals("fixed\n\n[Runtime task context]\nchanging", messages.getFirst().content());
        var protectedBody = ModelRequestScope.within("compaction", "group", true,
            () -> client.requestBody(messages, List.of(new ToolDefinition("read", "read", Map.of())), false));
        assertEquals("none", protectedBody.get("tool_choice"));
        assertEquals("", ModelRequestScope.current().purpose());
    }

    @Test
    void meteringRecordsFailuresUnknownCostsAndAppendOnlyHistory() {
        List<RequestReport> reports = new ArrayList<>();
        ModelClient fake = (messages, tools) -> {
            if (messages.getLast().content().equals("fail")) {
                throw new ModelClientException(ModelFailureKind.NETWORK, "offline");
            }
            return new ModelResponse("ok", List.of(), new ModelUsage(1000, 10, 0, 800, 0, true));
        };
        var client = new MeteredModelClient(fake, "session", "main", List.of(), reports::add);
        var first = List.of(Message.system("fixed"), Message.user("hello"));
        client.chat(first, List.of());
        var second = new ArrayList<>(first);
        second.add(Message.assistant("ok", List.of()));
        second.add(Message.user("next"));
        client.chat(second, List.of());
        assertEquals("append_only", reports.get(1).prefix().change());
        assertEquals(2, reports.get(1).prefix().unchangedMessages());
        assertThrows(ModelClientException.class, () -> client.chat(List.of(Message.user("fail")), List.of()));
        assertEquals(3, reports.size());
        assertEquals("failed", reports.getLast().status());
        assertFalse(reports.getLast().usage().exact());
        assertNull(reports.getLast().cost().total());
        assertNull(reports.getFirst().firstTokenMs());
        RequestTotals total = new RequestTotals();
        reports.forEach(total::add);
        assertEquals(3, total.summary().requests());
        assertEquals(2, total.summary().observedCacheRequests());
        assertEquals(2000, total.summary().observedCacheInputTokens());
        assertEquals(1600, total.summary().cacheReadTokens());
    }

    @Test
    void compositionCountsImagesConservativelyInsteadOfTreatingThemAsFree() {
        var parts = List.of(new ContextComposition.Source("system", "fixed"),
            new ContextComposition.Source("rules", "rules text"));
        var context = ContextComposition.estimate(
            List.of(Message.system("fixed\n\nrules text"),
                Message.user("hello", List.of(new Attachment("image.png", "image/png", "/tmp/image.png")))),
            List.of(new ToolDefinition("read", "read", Map.of("type", "object"))), parts);
        assertTrue(context.estimated());
        assertEquals(1, context.attachments());
        assertTrue(context.totalTokens() >= 1_025, "图片必须占用保守预算");
        assertEquals(context.totalTokens(), context.parts().stream().mapToLong(ContextComposition.Part::tokens).sum());
        assertEquals(List.of("system", "tools", "rules", "conversation"),
            context.parts().stream().map(ContextComposition.Part::category).toList());
    }
}
