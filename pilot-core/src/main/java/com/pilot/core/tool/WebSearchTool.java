package com.pilot.core.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.pilot.core.message.ToolNames;
import com.pilot.core.tool.search.WebSearchClients;
import com.pilot.core.tool.search.WebSearchConfig;
import com.pilot.core.tool.search.WebSearchModels;
import com.pilot.core.tool.search.WebSearchModels.ProviderOutcome;
import com.pilot.core.tool.search.WebSearchModels.SearchHit;
import com.pilot.core.tool.search.WebSearchModels.SearchRequest;
import com.pilot.core.tool.search.WebSearchProvider;

import tools.jackson.databind.ObjectMapper;

/**
 * 只读联网搜索工具。
 *
 * <p>支持多提供商（博查 / Tavily / Brave / Serper / Exa / 本机或自建 SearXNG）
 * 以及免密钥官方公开只读 API 兜底（Wikipedia / GitHub / StackOverflow / arXiv）。
 */
public final class WebSearchTool implements Tool {
    public static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:8888");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final Supplier<HttpClient> clientSupplier;
    private final ObjectMapper mapper;
    private final Supplier<WebSearchConfig> configSupplier;

    public WebSearchTool(HttpClient client, ObjectMapper mapper) {
        this(client, mapper, DEFAULT_ENDPOINT);
    }

    public WebSearchTool(HttpClient client, ObjectMapper mapper, URI endpoint) {
        this(client, mapper, endpoint, DEFAULT_TIMEOUT);
    }

    public WebSearchTool(HttpClient client, ObjectMapper mapper, URI endpoint, Duration timeout) {
        this(client, mapper, WebSearchConfig.searxngOnly(endpoint, Objects.requireNonNull(timeout)));
    }

    public WebSearchTool(HttpClient client, ObjectMapper mapper, WebSearchConfig config) {
        this(() -> Objects.requireNonNull(client), mapper, () -> Objects.requireNonNull(config));
    }

    public WebSearchTool(HttpClient client, ObjectMapper mapper, Supplier<WebSearchConfig> configSupplier) {
        this(() -> Objects.requireNonNull(client), mapper, configSupplier);
    }

    public WebSearchTool(Supplier<HttpClient> clientSupplier, ObjectMapper mapper,
        Supplier<WebSearchConfig> configSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier);
        this.mapper = mapper;
        this.configSupplier = Objects.requireNonNull(configSupplier);
    }

    /** 结构化执行报告，供工具输出格式化与设置页实时测试接口共用。 */
    public record SearchReport(boolean ok, String sourceId, String sourceLabel, List<String> triedSources,
        List<SearchHit> hits, List<String> answers, List<String> infoboxes, List<String> suggestions,
        List<String> warnings, String error, String formattedText, long elapsedMs) {
    }

    @Override
    public String name() {
        return ToolNames.WEB_SEARCH;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", ToolSchemas.string("搜索关键词，尽量具体明确"));
        properties.put("count", ToolSchemas.integer("返回结果数量，1 到 15，默认 8"));
        properties.put("category", Map.of("type", "string", "enum", List.of("general", "news", "it", "science"),
            "description", "搜索分类：general（综合）、news（新闻）、it（技术/代码）、science（学术）"));
        properties.put("time_range", Map.of("type", "string", "enum", List.of("day", "week", "month", "year"),
            "description", "时间范围过滤：day、week、month 或 year"));
        properties.put("language", Map.of("type", "string", "enum", List.of("zh", "en", "all"),
            "description", "语言偏好：zh（中文）、en（英文）或 all"));
        properties.put("site", ToolSchemas.string("限定站点域名，例如 docs.spring.io 或 github.com"));
        properties.put("page", ToolSchemas.integer("结果页码，1 到 5，默认 1"));
        return ToolSchemas.object(properties, List.of("query"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        return executeDetailed(args).formattedText();
    }

    public SearchReport executeDetailed(Map<String, Object> args) {
        long startNanos = System.nanoTime();
        String query = ToolSupport.stringArg(args, "query");
        if (query == null || query.isBlank()) {
            return new SearchReport(false, "", "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "缺少参数 query", "ERROR: 缺少参数 query", elapsedMs(startNanos));
        }
        int count = intArg(args, "count", 8);
        int page = intArg(args, "page", 1);
        SearchRequest request = new SearchRequest(query, count, ToolSupport.stringArg(args, "category"),
            ToolSupport.stringArg(args, "time_range"), ToolSupport.stringArg(args, "language"),
            ToolSupport.stringArg(args, "site"), page);
        WebSearchConfig config = Objects.requireNonNull(configSupplier.get(), "搜索配置不能为空");
        HttpClient client = Objects.requireNonNull(clientSupplier.get(), "HTTP 客户端不能为空");
        List<WebSearchProvider> plan = buildExecutionPlan(config);
        List<String> tried = new ArrayList<>();
        List<ProviderOutcome> failedOrEmpty = new ArrayList<>();
        try {
            for (int i = 0; i < plan.size(); i++) {
                WebSearchProvider current = plan.get(i);
                tried.add(current.id());
                ProviderOutcome outcome = WebSearchClients.execute(client, mapper, config, current, request);
                if (outcome.hasContent()) {
                    boolean augmented = false;
                    if (current == WebSearchProvider.SEARXNG && config.fallbackEnabled()
                        && outcome.hits().size() < Math.min(3, request.count())
                        && i + 1 < plan.size() && plan.get(i + 1) == WebSearchProvider.PUBLIC) {
                        tried.add(WebSearchProvider.PUBLIC.id());
                        ProviderOutcome supplement = WebSearchClients.execute(client, mapper, config,
                            WebSearchProvider.PUBLIC, request);
                        if (!supplement.hits().isEmpty()) {
                            outcome = mergeSupplementalOutcome(outcome, supplement, request);
                            augmented = true;
                        }
                    }
                    List<String> warnings = new ArrayList<>();
                    if (i > 0 && !failedOrEmpty.isEmpty()) {
                        warnings.add(buildFallbackNotice(outcome.sourceLabel(), failedOrEmpty));
                    }
                    warnings.addAll(outcome.warnings());
                    String text = formatSuccess(outcome, warnings, i > 0 && !augmented);
                    return new SearchReport(true, outcome.sourceId(), outcome.sourceLabel(), List.copyOf(tried),
                        outcome.hits(), outcome.answers(), outcome.infoboxes(), outcome.suggestions(),
                        List.copyOf(warnings), null, text, elapsedMs(startNanos));
                }
                failedOrEmpty.add(outcome);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SearchReport(false, "", "", List.copyOf(tried), List.of(), List.of(), List.of(), List.of(),
                List.of(), "搜索请求被中断", "ERROR: 搜索请求被中断", elapsedMs(startNanos));
        }
        return buildNoResultReport(plan, tried, failedOrEmpty, config, elapsedMs(startNanos));
    }

    private static ProviderOutcome mergeSupplementalOutcome(ProviderOutcome primary, ProviderOutcome supplement,
        SearchRequest request) {
        List<SearchHit> combined = new ArrayList<>(primary.hits().size() + supplement.hits().size());
        combined.addAll(primary.hits());
        combined.addAll(supplement.hits());
        List<SearchHit> merged = WebSearchModels.deduplicateAndRank(combined, request.count(), request.query(),
            request.site());
        List<String> warnings = new ArrayList<>(primary.warnings());
        warnings.add("主源命中偏少，已合并公开只读知识源结果补齐");
        return new ProviderOutcome(primary.sourceId() + "+" + supplement.sourceId(),
            primary.sourceLabel() + " + " + supplement.sourceLabel() + "（自动补齐）", merged, primary.answers(),
            primary.infoboxes(), primary.suggestions(), warnings, null, false, false);
    }

    private static List<WebSearchProvider> buildExecutionPlan(WebSearchConfig config) {
        List<WebSearchProvider> plan = new ArrayList<>();
        WebSearchProvider selected = config.provider();
        if (selected == WebSearchProvider.AUTO) {
            WebSearchProvider api = config.resolveAutoApiProvider();
            if (api != null) {
                plan.add(api);
            }
            plan.add(WebSearchProvider.SEARXNG);
            if (config.fallbackEnabled()) {
                plan.add(WebSearchProvider.PUBLIC);
            }
        } else if (selected == WebSearchProvider.SEARXNG) {
            plan.add(WebSearchProvider.SEARXNG);
            if (config.fallbackEnabled()) {
                plan.add(WebSearchProvider.PUBLIC);
            }
        } else if (selected == WebSearchProvider.PUBLIC) {
            plan.add(WebSearchProvider.PUBLIC);
        } else {
            plan.add(selected);
            if (config.fallbackEnabled()) {
                plan.add(WebSearchProvider.SEARXNG);
                plan.add(WebSearchProvider.PUBLIC);
            }
        }
        return plan;
    }

    private static String buildFallbackNotice(String activeLabel, List<ProviderOutcome> earlier) {
        List<String> reasons = new ArrayList<>();
        for (ProviderOutcome outcome : earlier) {
            String detail = summarizeFailure(outcome);
            reasons.add(outcome.sourceLabel() + (detail.isEmpty() ? "无结果" : "：" + detail));
        }
        return "已自动回退到 " + activeLabel + "（" + String.join("；", reasons) + "）";
    }

    private static String formatSuccess(ProviderOutcome outcome, List<String> warnings, boolean fallbackUsed) {
        StringBuilder out = new StringBuilder();
        out.append("搜索来源: ").append(outcome.sourceLabel());
        if (fallbackUsed) {
            out.append("（自动回退）");
        }
        out.append('\n');
        if (!outcome.answers().isEmpty()) {
            out.append("直接回答:\n");
            for (String answer : outcome.answers()) {
                out.append("- ").append(answer).append('\n');
            }
        }
        if (!outcome.infoboxes().isEmpty()) {
            out.append("知识摘要:\n");
            for (String box : outcome.infoboxes()) {
                out.append("- ").append(box).append('\n');
            }
        }
        List<SearchHit> hits = outcome.hits();
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            out.append(i + 1).append(". ").append(hit.title().isEmpty() ? "(无标题)" : hit.title());
            StringBuilder meta = new StringBuilder();
            if (!hit.publishedDate().isEmpty()) {
                meta.append(hit.publishedDate());
            }
            if (!hit.engines().isEmpty()) {
                if (!meta.isEmpty()) {
                    meta.append(" · ");
                }
                meta.append(String.join(", ", hit.engines()));
            }
            if (!meta.isEmpty()) {
                out.append(" [").append(meta).append(']');
            }
            out.append('\n');
            if (!hit.url().isEmpty()) {
                out.append("   URL: ").append(hit.url()).append('\n');
            }
            if (!hit.snippet().isEmpty()) {
                out.append("   ").append(hit.snippet()).append('\n');
            }
        }
        if (!outcome.suggestions().isEmpty()) {
            out.append("相关搜索建议: ").append(String.join(" / ", outcome.suggestions())).append('\n');
        }
        if (!warnings.isEmpty()) {
            out.append("诊断提示: ").append(String.join("；", warnings)).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static SearchReport buildNoResultReport(List<WebSearchProvider> plan, List<String> tried,
        List<ProviderOutcome> outcomes, WebSearchConfig config, long elapsedMs) {
        List<String> allWarnings = new ArrayList<>();
        boolean anySuccessResponse = false;
        for (ProviderOutcome outcome : outcomes) {
            allWarnings.addAll(outcome.warnings());
            if (outcome.error() == null) {
                anySuccessResponse = true;
            }
        }
        // 单一 SearXNG 模式保持既有确定性错误文案，兼容旧调用与测试。
        if (plan.size() == 1 && plan.get(0) == WebSearchProvider.SEARXNG && outcomes.size() == 1) {
            ProviderOutcome only = outcomes.get(0);
            if (only.error() != null) {
                return new SearchReport(false, only.sourceId(), only.sourceLabel(), List.copyOf(tried), List.of(),
                    List.of(), List.of(), List.of(), List.copyOf(allWarnings), only.error(),
                    "ERROR: " + only.error(), elapsedMs);
            }
            StringBuilder msg = new StringBuilder("未找到网页结果。");
            if (!only.warnings().isEmpty()) {
                msg.append("\n诊断提示: ").append(String.join("；", only.warnings()));
            }
            if (!only.suggestions().isEmpty()) {
                msg.append("\n相关搜索建议: ").append(String.join(" / ", only.suggestions()));
            }
            return new SearchReport(true, only.sourceId(), only.sourceLabel(), List.copyOf(tried), List.of(),
                List.of(), List.of(), only.suggestions(), List.copyOf(allWarnings), null, msg.toString(), elapsedMs);
        }

        List<String> summaryParts = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        for (ProviderOutcome outcome : outcomes) {
            String detail = summarizeFailure(outcome);
            summaryParts.add(outcome.sourceLabel() + "（" + (detail.isEmpty() ? "返回 0 条结果" : detail) + "）");
            for (String item : outcome.suggestions()) {
                if (!suggestions.contains(item) && suggestions.size() < 5) {
                    suggestions.add(item);
                }
            }
        }
        String hint = "建议：可在「设置 → 搜索」配置博查/Tavily/Brave/Serper/Exa 搜索 API 密钥，或检查网络代理与本机 SearXNG 状态。";
        if (anySuccessResponse) {
            StringBuilder text = new StringBuilder("未找到网页结果。\n已尝试来源: ")
                .append(String.join("；", summaryParts));
            if (!suggestions.isEmpty()) {
                text.append("\n相关搜索建议: ").append(String.join(" / ", suggestions));
            }
            if (!config.hasApiKey(config.provider()) && config.resolveAutoApiProvider() == null) {
                text.append('\n').append(hint);
            }
            return new SearchReport(true, "", "", List.copyOf(tried), List.of(), List.of(), List.of(), suggestions,
                List.copyOf(allWarnings), null, text.toString(), elapsedMs);
        }
        String error = "所有搜索来源均不可用：" + String.join("；", summaryParts) + "。" + hint;
        return new SearchReport(false, "", "", List.copyOf(tried), List.of(), List.of(), List.of(), suggestions,
            List.copyOf(allWarnings), error, "ERROR: " + error, elapsedMs);
    }

    private static String summarizeFailure(ProviderOutcome outcome) {
        if (outcome.error() != null && !outcome.error().isBlank()) {
            return outcome.error();
        }
        if (!outcome.warnings().isEmpty()) {
            return String.join("；", outcome.warnings());
        }
        return "";
    }

    private static int intArg(Map<String, Object> args, String name, int defaultValue) {
        Object raw = args == null ? null : args.get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
