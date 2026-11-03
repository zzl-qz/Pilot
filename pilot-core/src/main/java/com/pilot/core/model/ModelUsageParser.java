package com.pilot.core.model;

import tools.jackson.databind.JsonNode;

/** 字段存在性决定是否已观测；prompt_tokens 总量优先，防兼容网关混合字段重复相加。 */
public final class ModelUsageParser {
    private ModelUsageParser() { }
    public static ModelUsage parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        JsonNode inputDetails = node.path("prompt_tokens_details");
        if (!inputDetails.isObject()) inputDetails = node.path("input_tokens_details");
        boolean anthropic = number(node, "cache_read_input_tokens") || number(node, "cache_creation_input_tokens");
        boolean readKnown = number(inputDetails, "cached_tokens") || number(node, "cached_tokens")
                || number(node, "prompt_cache_hit_tokens") || number(node, "cache_read_input_tokens");
        boolean writeKnown = number(inputDetails, "cache_write_tokens") || number(node, "cache_creation_input_tokens");
        long read = number(inputDetails, "cached_tokens") ? value(inputDetails, "cached_tokens")
                : number(node, "prompt_cache_hit_tokens") ? value(node, "prompt_cache_hit_tokens")
                : number(node, "cache_read_input_tokens") ? value(node, "cache_read_input_tokens") : value(node, "cached_tokens");
        long write = number(inputDetails, "cache_write_tokens") ? value(inputDetails, "cache_write_tokens")
                : value(node, "cache_creation_input_tokens");
        long input = number(node, "prompt_tokens") ? value(node, "prompt_tokens") : value(node, "input_tokens");
        if (!number(node, "prompt_tokens") && anthropic) input = add(input, add(read, write));
        if (!number(node, "prompt_tokens") && !number(node, "input_tokens")
                && number(node, "prompt_cache_hit_tokens") && number(node, "prompt_cache_miss_tokens"))
            input = add(read, value(node, "prompt_cache_miss_tokens"));
        long output = number(node, "completion_tokens") ? value(node, "completion_tokens") : value(node, "output_tokens");
        JsonNode outputDetails = node.path("completion_tokens_details");
        if (!outputDetails.isObject()) outputDetails = node.path("output_tokens_details");
        long reasoning = value(outputDetails, "reasoning_tokens");
        if (input == 0 && output == 0 && reasoning == 0 && !number(node, "prompt_tokens") && !number(node, "input_tokens")) return null;
        boolean complete = (number(node, "prompt_tokens") || number(node, "input_tokens")
                || (number(node, "prompt_cache_hit_tokens") && number(node, "prompt_cache_miss_tokens")))
                && (number(node, "completion_tokens") || number(node, "output_tokens"));
        return new ModelUsage(input, output, reasoning, read, write, complete, readKnown, writeKnown);
    }
    private static boolean number(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value != null && value.isNumber() && value.asDouble() >= 0;
    }
    private static long value(JsonNode node, String name) { return number(node, name) ? Math.max(0, node.get(name).asLong()) : 0; }
    private static long add(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
}
