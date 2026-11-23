package com.pilot.core.model;

import tools.jackson.databind.JsonNode;

/** 字段存在性决定是否已观测；prompt_tokens 总量优先，防兼容网关混合字段重复相加。 */
public final class ModelUsageParser {
    private ModelUsageParser() {
    }

    public static ModelUsage parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        JsonNode inputDetails = details(node, "prompt_tokens_details", "input_tokens_details");
        Long readTokens = cacheRead(inputDetails, node);
        Long writeTokens = cacheWrite(inputDetails, node);
        long read = readTokens == null ? 0L : readTokens;
        long write = writeTokens == null ? 0L : writeTokens;
        long input = inputTokens(node, read, write);
        long output =
            number(node, "completion_tokens") ? value(node, "completion_tokens") : value(node, "output_tokens");
        long reasoning = value(details(node, "completion_tokens_details", "output_tokens_details"), "reasoning_tokens");
        // 没有总量字段又一字未报：兼容端点会回全零 usage，等同于没报，不能当精确值记账。
        if (input == 0 && output == 0 && reasoning == 0 && !reportsTotalInput(node)) {
            return null;
        }
        return new ModelUsage(input, output, reasoning, read, write, complete(node), readTokens != null,
            writeTokens != null);
    }

    /** 两套命名的同一字段：主字段存在就用它，否则退回兼容字段。 */
    private static JsonNode details(JsonNode node, String name, String fallback) {
        JsonNode details = node.path(name);
        return details.isObject() ? details : node.path(fallback);
    }

    /**
     * 命中量按权威度取值：details 里的同名字段、DeepSeek 命中字段、Anthropic 缓存读字段、顶层 cached_tokens。
     * 四个同义字段都没报时返回 null，用来区分「实测零」与「字段缺失」。
     */
    private static Long cacheRead(JsonNode details, JsonNode node) {
        if (number(details, "cached_tokens")) {
            return value(details, "cached_tokens");
        }
        if (number(node, "prompt_cache_hit_tokens")) {
            return value(node, "prompt_cache_hit_tokens");
        }
        if (number(node, "cache_read_input_tokens")) {
            return value(node, "cache_read_input_tokens");
        }
        return number(node, "cached_tokens") ? value(node, "cached_tokens") : null;
    }

    /** 写缓存同理：details 优先，其次 Anthropic 的 cache_creation_input_tokens；缺失返回 null。 */
    private static Long cacheWrite(JsonNode details, JsonNode node) {
        if (number(details, "cache_write_tokens")) {
            return value(details, "cache_write_tokens");
        }
        return number(node, "cache_creation_input_tokens") ? value(node, "cache_creation_input_tokens") : null;
    }

    /** prompt_tokens 是含缓存的总额，优先级最高；只有缺总额时才按协议把缓存量加回 input_tokens。 */
    private static long inputTokens(JsonNode node, long read, long write) {
        if (number(node, "prompt_tokens")) {
            return value(node, "prompt_tokens");
        }
        // DeepSeek 只给命中/未命中两段时，两者之和就是总输入，此时 Anthropic 口径不再叠加。
        if (splitInputIsTotal(node)) {
            return add(read, value(node, "prompt_cache_miss_tokens"));
        }
        long input = value(node, "input_tokens");
        return anthropicCachedInput(node) ? add(input, add(read, write)) : input;
    }

    /** Anthropic 的 input_tokens 不含缓存读写，缓存量必须加回总量。 */
    private static boolean anthropicCachedInput(JsonNode node) {
        return number(node, "cache_read_input_tokens") || number(node, "cache_creation_input_tokens");
    }

    private static boolean splitInputIsTotal(JsonNode node) {
        return !number(node, "input_tokens") && number(node, "prompt_cache_hit_tokens")
            && number(node, "prompt_cache_miss_tokens");
    }

    /** 输入与输出都来自端点才算精确用量；DeepSeek 的命中/未命中两段之和也算输入已报。 */
    private static boolean complete(JsonNode node) {
        return (reportsTotalInput(node) || splitInputIsTotal(node))
            && (number(node, "completion_tokens") || number(node, "output_tokens"));
    }

    private static boolean reportsTotalInput(JsonNode node) {
        return number(node, "prompt_tokens") || number(node, "input_tokens");
    }

    private static boolean number(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value != null && value.isNumber() && value.asDouble() >= 0;
    }

    private static long value(JsonNode node, String name) {
        return number(node, name) ? Math.max(0, node.get(name).asLong()) : 0;
    }

    private static long add(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
