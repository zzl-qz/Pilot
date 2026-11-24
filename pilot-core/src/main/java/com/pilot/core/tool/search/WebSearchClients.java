package com.pilot.core.tool.search;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.pilot.core.http.BoundedHttp;
import com.pilot.core.tool.search.WebSearchModels.ProviderOutcome;
import com.pilot.core.tool.search.WebSearchModels.SearchHit;
import com.pilot.core.tool.search.WebSearchModels.SearchRequest;
import com.pilot.core.util.SecretScrubber;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 各联网搜索提供商及免密钥官方公开只读 API 的 HTTP 客户端实现。 */
public final class WebSearchClients {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SNIPPET_CHARS = 360;
    private static final Duration PUBLIC_SOURCE_MAX_TIMEOUT = Duration.ofSeconds(8);
    private static final String USER_AGENT = "Pilot-WebSearch/2.0";
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    private WebSearchClients() {
    }

    public static ProviderOutcome execute(HttpClient client, ObjectMapper mapper, WebSearchConfig config,
        WebSearchProvider provider, SearchRequest request) throws InterruptedException {
        return switch (provider) {
            case SEARXNG -> searchSearxng(client, mapper, config, request);
            case BOCHA -> searchBocha(client, mapper, config, request);
            case TAVILY -> searchTavily(client, mapper, config, request);
            case BRAVE -> searchBrave(client, mapper, config, request);
            case SERPER -> searchSerper(client, mapper, config, request);
            case EXA -> searchExa(client, mapper, config, request);
            case PUBLIC -> searchPublicSources(client, mapper, config, request);
            case AUTO -> ProviderOutcome.failure(provider.id(), provider.label(), "未解析具体搜索提供商", false, false);
        };
    }

    private static ProviderOutcome searchSearxng(HttpClient client, ObjectMapper mapper, WebSearchConfig config,
        SearchRequest request) throws InterruptedException {
        String sourceId = WebSearchProvider.SEARXNG.id();
        String label = WebSearchProvider.SEARXNG.label();
        try {
            String base = stripTrailingSlash(config.resolveEndpoint(WebSearchProvider.SEARXNG).toString());
            StringBuilder url = new StringBuilder(base).append("/search?q=")
                .append(encode(request.effectiveQuery())).append("&format=json");
            if (request.page() > 1) {
                url.append("&pageno=").append(request.page());
            }
            if ("it".equals(request.category())) {
                url.append("&categories=general,it");
            } else if (!"general".equals(request.category())) {
                url.append("&categories=").append(encode(request.category()));
            }
            if (!request.timeRange().isEmpty()) {
                url.append("&time_range=").append(encode(request.timeRange()));
            }
            String searxLang = switch (request.language()) {
                case "zh" -> "zh-CN";
                case "en" -> "en-US";
                case "all" -> "all";
                default -> "";
            };
            if (!searxLang.isEmpty()) {
                url.append("&language=").append(encode(searxLang));
            }
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(config.timeout())
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Real-IP", "127.0.0.1")
                .GET()
                .build();
            HttpText response = sendText(client, httpRequest, config.timeout());
            if (response.statusCode() != 200) {
                return ProviderOutcome.failure(sourceId, label,
                    "搜索服务返回 HTTP " + response.statusCode() + detailSnippet(response.body(), ""), false, false);
            }
            JsonNode root = mapper.readTree(response.body());
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                String title = cleanText(item.path("title").asString(""));
                String hitUrl = item.path("url").asString("").strip();
                String snippet = truncate(cleanText(firstNonBlank(item.path("content").asString(""),
                    item.path("description").asString(""))), MAX_SNIPPET_CHARS);
                String published = firstNonBlank(item.path("publishedDate").asString(""),
                    item.path("pubdate").asString(""));
                List<String> engines = readEngines(item);
                double score = item.path("score").isNumber() ? item.path("score").asDouble(0.0) : 0.0;
                if (!title.isEmpty() || !hitUrl.isEmpty()) {
                    hits.add(new SearchHit(title, hitUrl, snippet, normalizeDate(published), engines, score));
                }
            }
            List<String> answers = new ArrayList<>();
            for (JsonNode answer : root.path("answers")) {
                String text = answer.isString() ? answer.asString("")
                    : firstNonBlank(answer.path("answer").asString(""), answer.path("content").asString(""));
                String cleaned = truncate(cleanText(text), MAX_SNIPPET_CHARS);
                if (!cleaned.isEmpty()) {
                    answers.add(cleaned);
                }
            }
            List<String> infoboxes = new ArrayList<>();
            for (JsonNode box : root.path("infoboxes")) {
                String boxTitle = cleanText(box.path("infobox").asString(""));
                String boxContent = truncate(cleanText(box.path("content").asString("")), MAX_SNIPPET_CHARS);
                String boxUrl = "";
                JsonNode urls = box.path("urls");
                if (urls.isArray() && !urls.isEmpty()) {
                    boxUrl = urls.get(0).path("url").asString("").strip();
                }
                if (!boxTitle.isEmpty() || !boxContent.isEmpty()) {
                    StringBuilder line = new StringBuilder();
                    if (!boxTitle.isEmpty()) {
                        line.append(boxTitle);
                    }
                    if (!boxContent.isEmpty()) {
                        if (!line.isEmpty()) {
                            line.append("： ");
                        }
                        line.append(boxContent);
                    }
                    if (!boxUrl.isEmpty()) {
                        line.append(" (").append(boxUrl).append(')');
                    }
                    infoboxes.add(line.toString());
                }
            }
            Set<String> suggestions = new LinkedHashSet<>();
            for (JsonNode item : root.path("suggestions")) {
                String value = cleanText(item.asString(""));
                if (!value.isEmpty() && suggestions.size() < 5) {
                    suggestions.add(value);
                }
            }
            for (JsonNode item : root.path("corrections")) {
                String value = cleanText(item.asString(""));
                if (!value.isEmpty() && suggestions.size() < 5) {
                    suggestions.add(value);
                }
            }
            List<String> warnings = new ArrayList<>();
            List<String> failedEngines = new ArrayList<>();
            for (JsonNode item : root.path("unresponsive_engines")) {
                if (item.isArray() && !item.isEmpty()) {
                    String engine = item.get(0).asString("").strip();
                    String reason = item.size() > 1 ? item.get(1).asString("").strip() : "";
                    if (!engine.isEmpty()) {
                        failedEngines.add(reason.isEmpty() ? engine : engine + "(" + reason + ")");
                    }
                }
            }
            if (!failedEngines.isEmpty()) {
                warnings.add("部分上游引擎未响应: " + String.join(", ", failedEngines));
            }
            List<SearchHit> ranked = WebSearchModels.deduplicateAndRank(hits, request.count(), request.query(),
                request.site());
            if (!hits.isEmpty() && ranked.isEmpty() && !request.site().isEmpty()) {
                warnings.add("上游返回 " + hits.size() + " 条结果均不属于限定站点 " + request.site() + "，已过滤");
            }
            return new ProviderOutcome(sourceId, label, ranked, answers, infoboxes, List.copyOf(suggestions), warnings,
                null, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return ProviderOutcome.failure(sourceId, label, "搜索请求超时", false, true);
        } catch (Exception e) {
            if (isConnectionDown(e)) {
                return ProviderOutcome.failure(sourceId, label,
                    "本地搜索未启动。把 pilot.web-search.local 设为 true 后重启。", true, false);
            }
            return ProviderOutcome.failure(sourceId, label, "搜索请求失败: " + e.getClass().getSimpleName(), false, false);
        }
    }

    private static ProviderOutcome searchBocha(HttpClient client, ObjectMapper mapper, WebSearchConfig config,
        SearchRequest request) throws InterruptedException {
        WebSearchProvider provider = WebSearchProvider.BOCHA;
        String apiKey = config.resolveApiKey(provider);
        if (apiKey.isBlank()) {
            return ProviderOutcome.failure(provider.id(), provider.label(), "未配置博查 Bocha API Key", false, false);
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", request.effectiveQuery());
            payload.put("count", request.count());
            payload.put("summary", true);
            payload.put("freshness", switch (request.timeRange()) {
                case "day" -> "oneDay";
                case "week" -> "oneWeek";
                case "month" -> "oneMonth";
                case "year" -> "oneYear";
                default -> "noLimit";
            });
            URI uri = config.resolveEndpoint(provider);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(config.timeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpText response = sendTextWithTransientRetry(client, httpRequest, config.timeout());
            if (response.statusCode() != 200) {
                return ProviderOutcome.failure(provider.id(), provider.label(),
                    "博查 API 返回 HTTP " + response.statusCode() + detailSnippet(response.body(), apiKey), false, false);
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode values = root.path("data").path("webPages").path("value");
            if (!values.isArray()) {
                values = root.path("webPages").path("value");
            }
            List<SearchHit> hits = new ArrayList<>();
            int idx = 0;
            for (JsonNode item : values) {
                String title = cleanText(item.path("name").asString(""));
                String hitUrl = item.path("url").asString("").strip();
                String snippet = truncate(cleanText(firstNonBlank(item.path("summary").asString(""),
                    item.path("snippet").asString(""))), MAX_SNIPPET_CHARS);
                String published = firstNonBlank(item.path("datePublished").asString(""),
                    item.path("dateLastCrawled").asString(""));
                if (!title.isEmpty() || !hitUrl.isEmpty()) {
                    hits.add(new SearchHit(title, hitUrl, snippet, normalizeDate(published), List.of("bocha"),
                        Math.max(0.1, 1.0 - idx * 0.02)));
                    idx++;
                }
            }
            List<SearchHit> ranked = WebSearchModels.deduplicateAndRank(hits, request.count(), request.query(),
                request.site());
            List<String> warnings = siteFilterWarnings(hits, ranked, request.site());
            return new ProviderOutcome(provider.id(), provider.label(), ranked, List.of(), List.of(), List.of(),
                warnings, null, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return ProviderOutcome.failure(provider.id(), provider.label(), "博查 API 请求超时", false, true);
        } catch (Exception e) {
            return ProviderOutcome.failure(provider.id(), provider.label(),
                "博查 API 请求失败: " + e.getClass().getSimpleName(), isConnectionDown(e), false);
        }
    }

    private static ProviderOutcome searchTavily(HttpClient client, ObjectMapper mapper, WebSearchConfig config,
        SearchRequest request) throws InterruptedException {
        WebSearchProvider provider = WebSearchProvider.TAVILY;
        String apiKey = config.resolveApiKey(provider);
        if (apiKey.isBlank()) {
            return ProviderOutcome.failure(provider.id(), provider.label(), "未配置 Tavily API Key", false, false);
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("api_key", apiKey);
            payload.put("query", request.query());
            payload.put("max_results", request.count());
            payload.put("search_depth", "basic");
            payload.put("include_answer", true);
            if ("news".equals(request.category())) {
                payload.put("topic", "news");
            }
            if (!request.timeRange().isEmpty()) {
                payload.put("time_range", request.timeRange());
            }
            if (!request.site().isEmpty()) {
                payload.put("include_domains", List.of(request.site()));
            }
            URI uri = config.resolveEndpoint(provider);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(config.timeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpText response = sendTextWithTransientRetry(client, httpRequest, config.timeout());
            if (response.statusCode() != 200) {
                return ProviderOutcome.failure(provider.id(), provider.label(),
                    "Tavily API 返回 HTTP " + response.statusCode() + detailSnippet(response.body(), apiKey),
                    false, false);
            }
            JsonNode root = mapper.readTree(response.body());
            List<String> answers = new ArrayList<>();
            String answer = truncate(cleanText(root.path("answer").asString("")), MAX_SNIPPET_CHARS * 2);
            if (!answer.isEmpty()) {
                answers.add(answer);
            }
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                String title = cleanText(item.path("title").asString(""));
                String hitUrl = item.path("url").asString("").strip();
                String snippet = truncate(cleanText(item.path("content").asString("")), MAX_SNIPPET_CHARS);
                String published = item.path("published_date").asString("");
                double score = item.path("score").isNumber() ? item.path("score").asDouble(0.5) : 0.5;
                if (!title.isEmpty() || !hitUrl.isEmpty()) {
                    hits.add(new SearchHit(title, hitUrl, snippet, normalizeDate(published), List.of("tavily"), score));
                }
            }
            List<SearchHit> ranked = WebSearchModels.deduplicateAndRank(hits, request.count(), request.query(),
                request.site());
            List<String> warnings = siteFilterWarnings(hits, ranked, request.site());
            return new ProviderOutcome(provider.id(), provider.label(), ranked, answers, List.of(), List.of(), warnings,
                null, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return ProviderOutcome.failure(provider.id(), provider.label(), "Tavily API 请求超时", false, true);
        } catch (Exception e) {
            return ProviderOutcome.failure(provider.id(), provider.label(),
                "Tavily API 请求失败: " + e.getClass().getSimpleName(), isConnectionDown(e), false);
        }
    }

    private static ProviderOutcome searchBrave(HttpClient client, ObjectMapper mapper, WebSearchConfig config,
        SearchRequest request) throws InterruptedException {
        WebSearchProvider provider = WebSearchProvider.BRAVE;
        String apiKey = config.resolveApiKey(provider);
        if (apiKey.isBlank()) {
            return ProviderOutcome.failure(provider.id(), provider.label(),
                "未配置 Brave Search API Key", false, false);
        }
        try {
            String base = stripTrailingSlash(config.resolveEndpoint(provider).toString());
            StringBuilder url = new StringBuilder(base).append("?q=").append(encode(request.effectiveQuery()))
                .append("&count=").append(request.count());
            String freshness = switch (request.timeRange()) {
                case "day" -> "pd";
                case "week" -> "pw";
                case "month" -> "pm";
                case "year" -> "py";
                default -> "";
            };
            if (!freshness.isEmpty()) {
                url.append("&freshness=").append(freshness);
            }
            if ("zh".equals(request.language())) {
                url.append("&search_lang=zh-hans");
            } else if ("en".equals(request.language())) {
                url.append("&search_lang=en");
            }
            if (request.page() > 1) {
                url.append("&offset=").append(request.page() - 1);
            }
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(config.timeout())
                .header("X-Subscription-Token", apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
            HttpText response = sendTextWithTransientRetry(client, httpRequest, config.timeout());
            if (response.statusCode() != 200) {
                return ProviderOutcome.failure(provider.id(), provider.label(),
                    "Brave API 返回 HTTP " + response.statusCode() + detailSnippet(response.body(), apiKey),
                    false, false);
            }
            JsonNode root = mapper.readTree(response.body());
            List<SearchHit> hits = new ArrayList<>();
            int idx = 0;
            for (JsonNode item : root.path("web").path("results")) {
                String title = cleanText(item.path("title").asString(""));
                String hitUrl = item.path("url").asString("").strip();
                String snippet = truncate(cleanText(item.path("description").asString("")), MAX_SNIPPET_CHARS);
                String published = firstNonBlank(item.path("page_age").asString(""), item.path("age").asString(""));
                if (!title.isEmpty() || !hitUrl.isEmpty()) {
                    hits.add(new SearchHit(title, hitUrl, snippet, normalizeDate(published), List.of("brave-api"),
                        Math.max(0.1, 1.0 - idx * 0.02)));
                    idx++;
                }
            }
            List<String> infoboxes = new ArrayList<>();
            for (JsonNode item : root.path("infobox").path("results")) {
                String title = cleanText(item.path("title").asString(""));
                String desc = truncate(cleanText(item.path("description").asString("")), MAX_SNIPPET_CHARS);
                if (!title.isEmpty() || !desc.isEmpty()) {
                    infoboxes.add(title.isEmpty() ? desc : (desc.isEmpty() ? title : title + "： " + desc));
                }
            }
            List<SearchHit> ranked = WebSearchModels.deduplicateAndRank(hits, request.count(), request.query(),
                request.site());
            List<String> warnings = siteFilterWarnings(hits, ranked, request.site());
            return new ProviderOutcome(provider.id(), provider.label(), ranked, List.of(), infoboxes, List.of(),
                warnings, null, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return ProviderOutcome.failure(provider.id(), provider.label(), "Brave API 请求超时", false, true);
        } catch (Exception e) {
            return ProviderOutcome.failure(provider.id(), provider.label(),
                "Brave API 请求失败: " + e.getClass().getSimpleName(), isConnectionDown(e), false);
        }
    }

    private static ProviderOutcome searchSerper(HttpClient client, ObjectMapper mapper, WebSearchConfig config,
        SearchRequest request) throws InterruptedException {
        WebSearchProvider provider = WebSearchProvider.SERPER;
        String apiKey = config.resolveApiKey(provider);
        if (apiKey.isBlank()) {
            return ProviderOutcome.failure(provider.id(), provider.label(), "未配置 Serper API Key", false, false);
        }
        try {
            String endpoint = stripTrailingSlash(config.resolveEndpoint(provider).toString());
            if ("news".equals(request.category()) && endpoint.endsWith("/search")) {
                endpoint = endpoint.substring(0, endpoint.length() - "/search".length()) + "/news";
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("q", request.effectiveQuery());
            payload.put("num", request.count());
            if (request.page() > 1) {
                payload.put("page", request.page());
            }
            String tbs = switch (request.timeRange()) {
                case "day" -> "qdr:d";
                case "week" -> "qdr:w";
                case "month" -> "qdr:m";
                case "year" -> "qdr:y";
                default -> "";
            };
            if (!tbs.isEmpty()) {
                payload.put("tbs", tbs);
            }
            if ("zh".equals(request.language())) {
                payload.put("hl", "zh-cn");
            } else if ("en".equals(request.language())) {
                payload.put("hl", "en");
            }
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(config.timeout())
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpText response = sendTextWithTransientRetry(client, httpRequest, config.timeout());
            if (response.statusCode() != 200) {
                return ProviderOutcome.failure(provider.id(), provider.label(),
                    "Serper API 返回 HTTP " + response.statusCode() + detailSnippet(response.body(), apiKey),
                    false, false);
            }
            JsonNode root = mapper.readTree(response.body());
            List<String> answers = new ArrayList<>();
            JsonNode answerBox = root.path("answerBox");
            if (answerBox.isObject()) {
                String ans = firstNonBlank(answerBox.path("answer").asString(""),
                    answerBox.path("snippet").asString(""));
                if (!ans.isEmpty()) {
                    answers.add(truncate(cleanText(ans), MAX_SNIPPET_CHARS));
                }
            }
            List<String> infoboxes = new ArrayList<>();
            JsonNode kg = root.path("knowledgeGraph");
            if (kg.isObject()) {
                String title = cleanText(kg.path("title").asString(""));
                String desc = truncate(cleanText(kg.path("description").asString("")), MAX_SNIPPET_CHARS);
                if (!title.isEmpty() || !desc.isEmpty()) {
                    infoboxes.add(title.isEmpty() ? desc : (desc.isEmpty() ? title : title + "： " + desc));
                }
            }
            JsonNode list = root.path("organic");
            if (!list.isArray() || list.isEmpty()) {
                list = root.path("news");
            }
            List<SearchHit> hits = new ArrayList<>();
            int idx = 0;
            for (JsonNode item : list) {
                String title = cleanText(item.path("title").asString(""));
                String hitUrl = item.path("link").asString("").strip();
                String snippet = truncate(cleanText(item.path("snippet").asString("")), MAX_SNIPPET_CHARS);
                String published = item.path("date").asString("");
                if (!title.isEmpty() || !hitUrl.isEmpty()) {
                    hits.add(new SearchHit(title, hitUrl, snippet, normalizeDate(published), List.of("serper"),
                        Math.max(0.1, 1.0 - idx * 0.02)));
                    idx++;
                }
            }
            List<String> suggestions = new ArrayList<>();
            for (JsonNode related : root.path("relatedSearches")) {
                String q = cleanText(related.path("query").asString(""));
                if (!q.isEmpty() && suggestions.size() < 5) {
                    suggestions.add(q);
                }
            }
            List<SearchHit> ranked = WebSearchModels.deduplicateAndRank(hits, request.count(), request.query(),
                request.site());
            List<String> warnings = siteFilterWarnings(hits, ranked, request.site());
            return new ProviderOutcome(provider.id(), provider.label(), ranked, answers, infoboxes, suggestions,
                warnings, null, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return ProviderOutcome.failure(provider.id(), provider.label(), "Serper API 请求超时", false, true);
        } catch (Exception e) {
            return ProviderOutcome.failure(provider.id(), provider.label(),
                "Serper API 请求失败: " + e.getClass().getSimpleName(), isConnectionDown(e), false);
        }
    }

    private static ProviderOutcome searchExa(HttpClient client, ObjectMapper mapper, WebSearchConfig config,
        SearchRequest request) throws InterruptedException {
        WebSearchProvider provider = WebSearchProvider.EXA;
        String apiKey = config.resolveApiKey(provider);
        if (apiKey.isBlank()) {
            return ProviderOutcome.failure(provider.id(), provider.label(), "未配置 Exa API Key", false, false);
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", request.query());
            payload.put("numResults", request.count());
            payload.put("contents", Map.of("text", Map.of("maxCharacters", 500)));
            if (!request.site().isEmpty()) {
                payload.put("includeDomains", List.of(request.site()));
            }
            if ("news".equals(request.category())) {
                payload.put("category", "news");
            } else if ("science".equals(request.category())) {
                payload.put("category", "research paper");
            }
            Instant start = startDateFor(request.timeRange());
            if (start != null) {
                payload.put("startPublishedDate", start.toString());
            }
            URI uri = config.resolveEndpoint(provider);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(config.timeout())
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpText response = sendTextWithTransientRetry(client, httpRequest, config.timeout());
            if (response.statusCode() != 200) {
                return ProviderOutcome.failure(provider.id(), provider.label(),
                    "Exa API 返回 HTTP " + response.statusCode() + detailSnippet(response.body(), apiKey),
                    false, false);
            }
            JsonNode root = mapper.readTree(response.body());
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                String title = cleanText(item.path("title").asString(""));
                String hitUrl = item.path("url").asString("").strip();
                String snippet = truncate(cleanText(firstNonBlank(item.path("text").asString(""),
                    item.path("summary").asString(""),
                    item.path("highlights").isArray() && !item.path("highlights").isEmpty()
                        ? item.path("highlights").get(0).asString("") : "")),
                    MAX_SNIPPET_CHARS);
                String published = item.path("publishedDate").asString("");
                double score = item.path("score").isNumber() ? item.path("score").asDouble(0.5) : 0.5;
                if (!title.isEmpty() || !hitUrl.isEmpty()) {
                    hits.add(new SearchHit(title, hitUrl, snippet, normalizeDate(published), List.of("exa"), score));
                }
            }
            List<SearchHit> ranked = WebSearchModels.deduplicateAndRank(hits, request.count(), request.query(),
                request.site());
            List<String> warnings = siteFilterWarnings(hits, ranked, request.site());
            return new ProviderOutcome(provider.id(), provider.label(), ranked, List.of(), List.of(), List.of(),
                warnings, null, false, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return ProviderOutcome.failure(provider.id(), provider.label(), "Exa API 请求超时", false, true);
        } catch (Exception e) {
            return ProviderOutcome.failure(provider.id(), provider.label(),
                "Exa API 请求失败: " + e.getClass().getSimpleName(), isConnectionDown(e), false);
        }
    }

    /**
     * 免密钥官方公开只读 API 兜底（Wikipedia / GitHub / StackExchange / arXiv）。
     * 仅调用开放 JSON/Atom 接口，不抓取反爬 HTML，不绕过任何验证。
     */
    private static ProviderOutcome searchPublicSources(HttpClient client, ObjectMapper mapper, WebSearchConfig config,
        SearchRequest request) throws InterruptedException {
        WebSearchProvider provider = WebSearchProvider.PUBLIC;
        WebSearchConfig.PublicSources sources = config.publicSources();
        Duration subTimeout = config.timeout().compareTo(PUBLIC_SOURCE_MAX_TIMEOUT) < 0
            ? config.timeout() : PUBLIC_SOURCE_MAX_TIMEOUT;
        int perSource = Math.max(3, Math.min(6, request.count()));
        List<Callable<SubSourceResult>> tasks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String site = request.site();
        boolean preferZh = "zh".equals(request.language())
            || (request.language().isEmpty() && request.containsCjk());

        if (!site.isEmpty()) {
            if (site.endsWith("wikipedia.org")) {
                if (site.startsWith("zh.") || (preferZh && !site.startsWith("en."))) {
                    tasks.add(() -> queryWikipedia(client, mapper, sources.wikipediaZh(), "zh", request.query(),
                        perSource, subTimeout, 0.92));
                } else {
                    tasks.add(() -> queryWikipedia(client, mapper, sources.wikipediaEn(), "en", request.query(),
                        perSource, subTimeout, 0.92));
                }
            } else if (site.endsWith("github.com")) {
                tasks.add(() -> queryGitHub(client, mapper, sources.github(), request.query(), perSource, subTimeout,
                    0.95));
            } else if (site.endsWith("stackoverflow.com")) {
                tasks.add(() -> queryStackExchange(client, mapper, sources.stackExchange(), request.query(),
                    perSource, subTimeout, 0.95));
            } else if (site.endsWith("arxiv.org")) {
                tasks.add(() -> queryArxiv(client, sources.arxiv(), request.query(), perSource, subTimeout, 0.95));
            } else {
                warnings.add("公开只读知识源仅覆盖 Wikipedia/GitHub/StackOverflow/arXiv，不包含限定站点 " + site);
                return new ProviderOutcome(provider.id(), provider.label(), List.of(), List.of(), List.of(), List.of(),
                    warnings, null, false, false);
            }
        } else if ("science".equals(request.category())) {
            tasks.add(() -> queryArxiv(client, sources.arxiv(), request.query(), perSource, subTimeout, 0.95));
            tasks.add(() -> queryWikipedia(client, mapper, preferZh ? sources.wikipediaZh() : sources.wikipediaEn(),
                preferZh ? "zh" : "en", request.query(), perSource, subTimeout, 0.85));
        } else if ("it".equals(request.category())) {
            tasks.add(() -> queryGitHub(client, mapper, sources.github(), request.query(), perSource, subTimeout,
                0.95));
            tasks.add(() -> queryStackExchange(client, mapper, sources.stackExchange(), request.query(),
                perSource, subTimeout, 0.92));
            tasks.add(() -> queryWikipedia(client, mapper, preferZh ? sources.wikipediaZh() : sources.wikipediaEn(),
                preferZh ? "zh" : "en", request.query(), Math.min(3, perSource), subTimeout, 0.82));
        } else {
            if ("news".equals(request.category())) {
                warnings.add("公开只读知识源偏向百科与技术文档，时效新闻建议配置博查/Tavily/Serper 等搜索 API");
            }
            if (preferZh) {
                tasks.add(() -> queryWikipedia(client, mapper, sources.wikipediaZh(), "zh", request.query(),
                    perSource, subTimeout, 0.92));
                tasks.add(() -> queryWikipedia(client, mapper, sources.wikipediaEn(), "en", request.query(),
                    Math.min(3, perSource), subTimeout, 0.84));
                tasks.add(() -> queryGitHub(client, mapper, sources.github(), request.query(),
                    Math.min(3, perSource), subTimeout, 0.86));
            } else {
                tasks.add(() -> queryWikipedia(client, mapper, sources.wikipediaEn(), "en", request.query(),
                    perSource, subTimeout, 0.92));
                tasks.add(() -> queryGitHub(client, mapper, sources.github(), request.query(),
                    Math.min(4, perSource), subTimeout, 0.86));
                if (request.looksTechnical()) {
                    tasks.add(() -> queryStackExchange(client, mapper, sources.stackExchange(), request.query(),
                        Math.min(4, perSource), subTimeout, 0.84));
                }
            }
        }

        List<SearchHit> allHits = new ArrayList<>();
        List<String> failedSources = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SubSourceResult>> futures = new ArrayList<>(tasks.size());
            for (Callable<SubSourceResult> task : tasks) {
                futures.add(executor.submit(task));
            }
            for (Future<SubSourceResult> future : futures) {
                try {
                    SubSourceResult sub = future.get();
                    allHits.addAll(sub.hits());
                    if (sub.error() != null && !sub.error().isBlank()) {
                        failedSources.add(sub.name() + "(" + sub.error() + ")");
                    }
                } catch (ExecutionException e) {
                    failedSources.add(e.getCause() == null ? "未知错误" : e.getCause().getClass().getSimpleName());
                }
            }
        }
        if (!failedSources.isEmpty()) {
            warnings.add("部分公开源不可用: " + String.join(", ", failedSources));
        }
        List<SearchHit> ranked = WebSearchModels.deduplicateAndRank(allHits, request.count(), request.query(),
            request.site());
        if (ranked.isEmpty() && !failedSources.isEmpty()) {
            return new ProviderOutcome(provider.id(), provider.label(), List.of(), List.of(), List.of(), List.of(),
                warnings, "公开只读知识源均请求失败: " + String.join(", ", failedSources), false, false);
        }
        return new ProviderOutcome(provider.id(), provider.label(), ranked, List.of(), List.of(), List.of(), warnings,
            null, false, false);
    }

    private static SubSourceResult queryWikipedia(HttpClient client, ObjectMapper mapper, URI endpoint, String lang,
        String query, int limit, Duration timeout, double baseScore) throws InterruptedException {
        String source = "wikipedia-" + lang;
        if (endpoint == null) {
            return new SubSourceResult(source, List.of(), "未配置地址");
        }
        try {
            String url = endpoint + "?action=query&list=search&srsearch=" + encode(query)
                + "&srlimit=" + limit + "&utf8=1&format=json";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
            HttpText response = sendText(client, request, timeout);
            if (response.statusCode() != 200) {
                return new SubSourceResult(source, List.of(), "HTTP " + response.statusCode());
            }
            JsonNode list = mapper.readTree(response.body()).path("query").path("search");
            List<SearchHit> hits = new ArrayList<>();
            int idx = 0;
            for (JsonNode item : list) {
                String title = cleanText(item.path("title").asString(""));
                if (title.isEmpty()) {
                    continue;
                }
                String pageUrl = "https://" + lang + ".wikipedia.org/wiki/"
                    + encode(title.replace(' ', '_')).replace("+", "%20");
                String snippet = truncate(cleanText(item.path("snippet").asString("")), MAX_SNIPPET_CHARS);
                String timestamp = normalizeDate(item.path("timestamp").asString(""));
                hits.add(new SearchHit(title, pageUrl, snippet, timestamp, List.of(source),
                    Math.max(0.1, baseScore - idx * 0.03)));
                idx++;
            }
            return new SubSourceResult(source, hits, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return new SubSourceResult(source, List.of(), "超时");
        } catch (Exception e) {
            return new SubSourceResult(source, List.of(), e.getClass().getSimpleName());
        }
    }

    private static SubSourceResult queryGitHub(HttpClient client, ObjectMapper mapper, URI endpoint, String query,
        int limit, Duration timeout, double baseScore) throws InterruptedException {
        String source = "github";
        if (endpoint == null) {
            return new SubSourceResult(source, List.of(), "未配置地址");
        }
        try {
            String url = endpoint + "?q=" + encode(query) + "&per_page=" + limit;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
            HttpText response = sendText(client, request, timeout);
            if (response.statusCode() != 200) {
                return new SubSourceResult(source, List.of(), "HTTP " + response.statusCode());
            }
            JsonNode items = mapper.readTree(response.body()).path("items");
            List<SearchHit> hits = new ArrayList<>();
            int idx = 0;
            for (JsonNode item : items) {
                String title = cleanText(item.path("full_name").asString(""));
                String hitUrl = item.path("html_url").asString("").strip();
                String desc = cleanText(item.path("description").asString(""));
                String lang = cleanText(item.path("language").asString(""));
                long stars = item.path("stargazers_count").asLong(0L);
                StringBuilder snippet = new StringBuilder();
                if (!lang.isEmpty() || stars > 0) {
                    snippet.append('[');
                    if (!lang.isEmpty()) {
                        snippet.append(lang);
                    }
                    if (stars > 0) {
                        if (!lang.isEmpty()) {
                            snippet.append(" · ");
                        }
                        snippet.append('★').append(stars);
                    }
                    snippet.append("] ");
                }
                snippet.append(desc);
                String updated = normalizeDate(item.path("updated_at").asString(""));
                if (!title.isEmpty() || !hitUrl.isEmpty()) {
                    hits.add(new SearchHit(title, hitUrl, truncate(snippet.toString().strip(), MAX_SNIPPET_CHARS),
                        updated, List.of(source), Math.max(0.1, baseScore - idx * 0.03)));
                    idx++;
                }
            }
            return new SubSourceResult(source, hits, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return new SubSourceResult(source, List.of(), "超时");
        } catch (Exception e) {
            return new SubSourceResult(source, List.of(), e.getClass().getSimpleName());
        }
    }

    private static SubSourceResult queryStackExchange(HttpClient client, ObjectMapper mapper, URI endpoint,
        String query, int limit, Duration timeout, double baseScore) throws InterruptedException {
        String source = "stackoverflow";
        if (endpoint == null) {
            return new SubSourceResult(source, List.of(), "未配置地址");
        }
        try {
            String url = endpoint + "?order=desc&sort=relevance&q=" + encode(query)
                + "&site=stackoverflow&pagesize=" + limit;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
            HttpText response = sendText(client, request, timeout);
            if (response.statusCode() != 200) {
                return new SubSourceResult(source, List.of(), "HTTP " + response.statusCode());
            }
            JsonNode items = mapper.readTree(response.body()).path("items");
            List<SearchHit> hits = new ArrayList<>();
            int idx = 0;
            for (JsonNode item : items) {
                String title = cleanText(item.path("title").asString(""));
                String hitUrl = item.path("link").asString("").strip();
                int answers = item.path("answer_count").asInt(0);
                int score = item.path("score").asInt(0);
                boolean answered = item.path("is_answered").asBoolean(false);
                List<String> tags = new ArrayList<>();
                for (JsonNode tag : item.path("tags")) {
                    String value = tag.asString("").strip();
                    if (!value.isEmpty() && tags.size() < 4) {
                        tags.add(value);
                    }
                }
                StringBuilder snippet = new StringBuilder();
                snippet.append(answered ? "已解答" : "未采纳").append(" · 回答 ").append(answers)
                    .append(" · 得分 ").append(score);
                if (!tags.isEmpty()) {
                    snippet.append(" · 标签: ").append(String.join(", ", tags));
                }
                long epoch = item.path("last_activity_date").asLong(0L);
                String date = epoch > 0 ? LocalDate.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC).toString()
                    : "";
                if (!title.isEmpty() || !hitUrl.isEmpty()) {
                    hits.add(new SearchHit(title, hitUrl, snippet.toString(), date, List.of(source),
                        Math.max(0.1, baseScore - idx * 0.03)));
                    idx++;
                }
            }
            return new SubSourceResult(source, hits, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return new SubSourceResult(source, List.of(), "超时");
        } catch (Exception e) {
            return new SubSourceResult(source, List.of(), e.getClass().getSimpleName());
        }
    }

    private static SubSourceResult queryArxiv(HttpClient client, URI endpoint, String query, int limit,
        Duration timeout, double baseScore) throws InterruptedException {
        String source = "arxiv";
        if (endpoint == null) {
            return new SubSourceResult(source, List.of(), "未配置地址");
        }
        try {
            String url = endpoint + "?search_query=all:" + encode(query) + "&start=0&max_results=" + limit;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/atom+xml,application/xml")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
            HttpText response = sendText(client, request, timeout);
            if (response.statusCode() != 200) {
                return new SubSourceResult(source, List.of(), "HTTP " + response.statusCode());
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));
            NodeList entries = doc.getElementsByTagName("entry");
            List<SearchHit> hits = new ArrayList<>();
            for (int i = 0; i < entries.getLength() && hits.size() < limit; i++) {
                Node node = entries.item(i);
                if (!(node instanceof Element entry)) {
                    continue;
                }
                String title = cleanText(childText(entry, "title"));
                String idUrl = childText(entry, "id").strip();
                String summary = truncate(cleanText(childText(entry, "summary")), MAX_SNIPPET_CHARS);
                String published = normalizeDate(childText(entry, "published"));
                if (!title.isEmpty() || !idUrl.isEmpty()) {
                    hits.add(new SearchHit(title, idUrl, summary, published, List.of(source),
                        Math.max(0.1, baseScore - i * 0.03)));
                }
            }
            return new SubSourceResult(source, hits, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (HttpTimeoutException e) {
            return new SubSourceResult(source, List.of(), "超时");
        } catch (Exception e) {
            return new SubSourceResult(source, List.of(), e.getClass().getSimpleName());
        }
    }

    private static HttpText sendTextWithTransientRetry(HttpClient client, HttpRequest request, Duration timeout)
        throws IOException, InterruptedException {
        IOException lastIo = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpText response = sendText(client, request, timeout);
                int code = response.statusCode();
                if (attempt == 0 && (code == 502 || code == 503 || code == 504)) {
                    Thread.sleep(250L);
                    continue;
                }
                return response;
            } catch (HttpTimeoutException e) {
                throw e;
            } catch (IOException e) {
                if (attempt == 0 && !isConnectionDown(e)) {
                    lastIo = e;
                    Thread.sleep(250L);
                    continue;
                }
                throw e;
            }
        }
        throw lastIo == null ? new IOException("搜索请求失败") : lastIo;
    }

    private static List<String> siteFilterWarnings(List<SearchHit> raw, List<SearchHit> ranked, String site) {
        if (!raw.isEmpty() && ranked.isEmpty() && site != null && !site.isBlank()) {
            return List.of("上游返回 " + raw.size() + " 条结果均不属于限定站点 " + site + "，已过滤");
        }
        return List.of();
    }

    private static HttpText sendText(HttpClient client, HttpRequest request, Duration timeout)
        throws IOException, InterruptedException {
        try (BoundedHttp.Exchange exchange = BoundedHttp.open(client, request, timeout, MAX_RESPONSE_BYTES)) {
            int limit = exchange.statusCode() >= 200 && exchange.statusCode() < 300
                ? MAX_RESPONSE_BYTES : BoundedHttp.ERROR_BYTES;
            boolean gzip = exchange.headers().firstValue("content-encoding")
                .map(v -> v.toLowerCase(Locale.ROOT).contains("gzip")).orElse(false);
            byte[] raw = exchange.body().readNBytes(limit);
            byte[] decoded = gzip ? gunzipBounded(raw, limit) : raw;
            return new HttpText(exchange.statusCode(), new String(decoded, StandardCharsets.UTF_8));
        }
    }

    public static byte[] gunzipBounded(byte[] compressed, int maxBytes) throws IOException {
        if (compressed.length < 2 || (compressed[0] & 0xFF) != 0x1F || (compressed[1] & 0xFF) != 0x8B) {
            return compressed;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed));
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BoundedHttp.ResponseLimitException("解压后响应超过 " + maxBytes + " 字节上限");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static List<String> readEngines(JsonNode item) {
        List<String> engines = new ArrayList<>();
        JsonNode array = item.path("engines");
        if (array.isArray()) {
            for (JsonNode entry : array) {
                String value = entry.asString("").strip();
                if (!value.isEmpty()) {
                    engines.add(value);
                }
            }
        }
        if (engines.isEmpty()) {
            String single = item.path("engine").asString("").strip();
            if (!single.isEmpty()) {
                engines.add(single);
            }
        }
        return engines;
    }

    private static String childText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return "";
        }
        String text = list.item(0).getTextContent();
        return text == null ? "" : text;
    }

    private static Instant startDateFor(String timeRange) {
        Instant now = Instant.now();
        return switch (timeRange) {
            case "day" -> now.minus(1, ChronoUnit.DAYS);
            case "week" -> now.minus(7, ChronoUnit.DAYS);
            case "month" -> now.minus(30, ChronoUnit.DAYS);
            case "year" -> now.minus(365, ChronoUnit.DAYS);
            default -> null;
        };
    }

    private static String detailSnippet(String body, String secretToScrub) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String scrubbed = SecretScrubber.scrub(body);
        if (secretToScrub != null && !secretToScrub.isBlank()) {
            scrubbed = scrubbed.replace(secretToScrub, "***");
        }
        String compact = truncate(WebSearchModels.collapseSpace(scrubbed), 180);
        return compact.isEmpty() ? "" : "（" + compact + "）";
    }

    private static String cleanText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String noTags = HTML_TAG.matcher(raw).replaceAll(" ");
        return WebSearchModels.collapseSpace(decodeEntities(noTags));
    }

    public static String decodeEntities(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text == null ? "" : text;
        }
        String replaced = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&middot;", "·")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”");
        Matcher matcher = NUMERIC_ENTITY.matcher(replaced);
        if (!matcher.find()) {
            return replaced;
        }
        StringBuilder out = new StringBuilder();
        int last = 0;
        do {
            out.append(replaced, last, matcher.start());
            String rawCode = matcher.group(1);
            try {
                int code = rawCode.startsWith("x") || rawCode.startsWith("X")
                    ? Integer.parseInt(rawCode.substring(1), 16) : Integer.parseInt(rawCode, 10);
                if (Character.isValidCodePoint(code) && code > 0) {
                    out.appendCodePoint(code);
                } else {
                    out.append(matcher.group(0));
                }
            } catch (NumberFormatException e) {
                out.append(matcher.group(0));
            }
            last = matcher.end();
        } while (matcher.find());
        out.append(replaced, last, replaced.length());
        return out.toString();
    }

    private static String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.strip();
        if (trimmed.length() >= 10 && Character.isDigit(trimmed.charAt(0))
            && trimmed.charAt(4) == '-' && trimmed.charAt(7) == '-') {
            return trimmed.substring(0, 10);
        }
        return truncate(trimmed, 32);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String text) {
        while (text.endsWith("/") && text.length() > "http://a".length()) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    static boolean isConnectionDown(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConnectException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("Connection refused")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record HttpText(int statusCode, String body) {
    }

    private record SubSourceResult(String name, List<SearchHit> hits, String error) {
    }
}
