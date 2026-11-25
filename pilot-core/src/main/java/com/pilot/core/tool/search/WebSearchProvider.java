package com.pilot.core.tool.search;

import java.util.List;
import java.util.Locale;

/** 联网搜索提供商定义。 */
public enum WebSearchProvider {
    AUTO("auto", "自动选择", false, "", "优先使用已配置密钥的搜索 API，未配置时使用本机 SearXNG，无结果时回退公开只读接口"),
    BOCHA("bocha", "博查 Bocha（国内直连）", true, "https://api.bochaai.com/v1/web-search", "国内网络直连、中文网页与时效检索表现好；需博查 API Key"),
    TAVILY("tavily", "Tavily", true, "https://api.tavily.com/search", "面向 Agent 的网页搜索，支持直接回答与站点/时间过滤；需 Tavily API Key"),
    BRAVE("brave", "Brave Search", true, "https://api.search.brave.com/res/v1/web/search",
        "独立网页索引，提供免费额度；需 Brave Search Subscription Token"),
    SERPER("serper", "Serper (Google)", true, "https://google.serper.dev/search",
        "Google 搜索结果 JSON API，中英文覆盖全；需 Serper API Key"),
    EXA("exa", "Exa", true, "https://api.exa.ai/search", "面向语义、技术文档与论文的搜索 API；需 Exa API Key"),
    SEARXNG("searxng", "本机 / 自建 SearXNG", false, "http://127.0.0.1:8888", "免密钥元搜索，聚合多家公开引擎"), PUBLIC("public",
        "公开只读知识源", false, "", "免密钥直连官方公开 API：Wikipedia（中/英）、GitHub、StackOverflow、Hacker News、arXiv、OpenAlex");

    private static final List<WebSearchProvider> API_PROVIDERS = List.of(BOCHA, TAVILY, BRAVE, SERPER, EXA);

    private final String id;
    private final String label;
    private final boolean requiresApiKey;
    private final String defaultEndpoint;
    private final String description;

    WebSearchProvider(String id, String label, boolean requiresApiKey, String defaultEndpoint, String description) {
        this.id = id;
        this.label = label;
        this.requiresApiKey = requiresApiKey;
        this.defaultEndpoint = defaultEndpoint;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    public String defaultEndpoint() {
        return defaultEndpoint;
    }

    public String description() {
        return description;
    }

    public static List<WebSearchProvider> apiProviders() {
        return API_PROVIDERS;
    }

    public static WebSearchProvider parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        for (WebSearchProvider value : values()) {
            if (value.id.equals(normalized) || value.name().equalsIgnoreCase(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException(
            "未知搜索提供商: " + raw + "，可选: auto, bocha, tavily, brave, serper, exa, searxng, public");
    }
}
