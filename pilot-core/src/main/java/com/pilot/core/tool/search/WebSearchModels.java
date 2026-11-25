package com.pilot.core.tool.search;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 联网搜索请求、命中条目、站点过滤、相关性重排与来源结果模型。 */
public final class WebSearchModels {
    public static final int DEFAULT_COUNT = 8;
    public static final int MAX_COUNT = 15;
    public static final int MAX_PAGE = 5;

    private static final Set<String> TRACKING_PARAMS =
        Set.of("spm", "fbclid", "gclid", "msclkid", "yclid", "mc_cid", "mc_eid", "ref_src", "ref_url", "igshid", "si");
    private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "with", "how", "what", "why", "when",
        "where", "from", "into", "that", "this", "are", "was", "were", "can", "use", "using", "about", "site");
    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9][a-z0-9._+-]{1,}");

    private WebSearchModels() {
    }

    public record SearchRequest(String query, int count, String category, String timeRange, String language,
        String site, int page) {

        public SearchRequest {
            query = query == null ? "" : query.strip();
            count = Math.max(1, Math.min(MAX_COUNT, count <= 0 ? DEFAULT_COUNT : count));
            category = normalizeCategory(category);
            timeRange = normalizeTimeRange(timeRange);
            language = normalizeLanguage(language);
            site = normalizeSite(site);
            page = Math.max(1, Math.min(MAX_PAGE, page <= 0 ? 1 : page));
        }

        public String effectiveQuery() {
            if (site.isEmpty()) {
                return query;
            }
            if (query.toLowerCase(Locale.ROOT).contains("site:")) {
                return query;
            }
            return query + " site:" + site;
        }

        public boolean containsCjk() {
            for (int i = 0; i < query.length(); i++) {
                Character.UnicodeScript script = Character.UnicodeScript.of(query.charAt(i));
                if (script == Character.UnicodeScript.HAN) {
                    return true;
                }
            }
            return false;
        }

        public boolean looksTechnical() {
            if ("it".equals(category)) {
                return true;
            }
            String lower = query.toLowerCase(Locale.ROOT);
            for (String token : List.of("java", "python", "typescript", "javascript", "rust", "golang", "spring",
                "react", "docker", "kubernetes", "git", "linux", "macos", "windows", "api", "sdk", "cli", "error",
                "exception", "stacktrace", "github", "npm", "maven", "gradle", "sql", "http", "ssl", "tls")) {
                if (lower.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        private static String normalizeCategory(String raw) {
            if (raw == null || raw.isBlank()) {
                return "general";
            }
            String value = raw.strip().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "news", "it", "science", "general" -> value;
                case "tech", "code", "dev" -> "it";
                case "academic", "paper", "papers" -> "science";
                default -> "general";
            };
        }

        private static String normalizeTimeRange(String raw) {
            if (raw == null || raw.isBlank()) {
                return "";
            }
            String value = raw.strip().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "day", "1d", "24h", "today", "past_day", "oneday" -> "day";
                case "week", "7d", "past_week", "oneweek" -> "week";
                case "month", "30d", "past_month", "onemonth" -> "month";
                case "year", "365d", "past_year", "oneyear" -> "year";
                default -> "";
            };
        }

        private static String normalizeLanguage(String raw) {
            if (raw == null || raw.isBlank()) {
                return "";
            }
            String value = raw.strip().toLowerCase(Locale.ROOT);
            if (value.startsWith("zh")) {
                return "zh";
            }
            if (value.startsWith("en")) {
                return "en";
            }
            if ("all".equals(value) || "auto".equals(value)) {
                return "all";
            }
            return "";
        }

        private static String normalizeSite(String raw) {
            if (raw == null || raw.isBlank()) {
                return "";
            }
            String text = raw.strip();
            if (text.toLowerCase(Locale.ROOT).startsWith("site:")) {
                text = text.substring(5).strip();
            }
            if (text.contains("://")) {
                try {
                    URI parsed = URI.create(text);
                    if (parsed.getHost() != null && !parsed.getHost().isBlank()) {
                        text = parsed.getHost();
                    }
                } catch (IllegalArgumentException ignored) {
                    // 保留原始文本继续清理
                }
            }
            int slash = text.indexOf('/');
            if (slash > 0) {
                text = text.substring(0, slash);
            }
            text = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            while (text.startsWith(".")) {
                text = text.substring(1);
            }
            return text;
        }
    }

    public record SearchHit(String title, String url, String snippet, String publishedDate, List<String> engines,
        double score) {
        public SearchHit {
            title = title == null ? "" : collapseSpace(title);
            url = url == null ? "" : url.strip();
            snippet = snippet == null ? "" : collapseSpace(snippet);
            publishedDate = publishedDate == null ? "" : publishedDate.strip();
            engines = engines == null ? List.of() : List.copyOf(new LinkedHashSet<>(engines));
        }
    }

    public record ProviderOutcome(String sourceId, String sourceLabel, List<SearchHit> hits, List<String> answers,
        List<String> infoboxes, List<String> suggestions, List<String> warnings, String error, boolean down,
        boolean timeout, boolean hardFailure) {

        public ProviderOutcome(String sourceId, String sourceLabel, List<SearchHit> hits, List<String> answers,
            List<String> infoboxes, List<String> suggestions, List<String> warnings, String error, boolean down,
            boolean timeout) {
            this(sourceId, sourceLabel, hits, answers, infoboxes, suggestions, warnings, error, down, timeout, down);
        }

        public ProviderOutcome {
            hits = hits == null ? List.of() : List.copyOf(hits);
            answers = answers == null ? List.of() : List.copyOf(answers);
            infoboxes = infoboxes == null ? List.of() : List.copyOf(infoboxes);
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean hasContent() {
            return !hits.isEmpty() || !answers.isEmpty() || !infoboxes.isEmpty();
        }

        public static ProviderOutcome failure(String sourceId, String sourceLabel, String error, boolean down,
            boolean timeout) {
            return new ProviderOutcome(sourceId, sourceLabel, List.of(), List.of(), List.of(), List.of(), List.of(),
                error, down, timeout, down);
        }

        public static ProviderOutcome hardFailure(String sourceId, String sourceLabel, String error) {
            return new ProviderOutcome(sourceId, sourceLabel, List.of(), List.of(), List.of(), List.of(), List.of(),
                error, false, false, true);
        }
    }

    public static List<SearchHit> deduplicateAndRank(List<SearchHit> rawHits, int limit) {
        return deduplicateAndRank(rawHits, limit, "", "");
    }

    /**
     * 规范化 URL 去重、严格站点域名过滤、多引擎交叉加权与轻量查询词相关性重排。
     */
    public static List<SearchHit> deduplicateAndRank(List<SearchHit> rawHits, int limit, String query, String site) {
        if (rawHits == null || rawHits.isEmpty()) {
            return List.of();
        }
        String normalizedSite = site == null ? "" : site.strip().toLowerCase(Locale.ROOT);
        Map<String, MergedHit> merged = new LinkedHashMap<>();
        int index = 0;
        for (SearchHit hit : rawHits) {
            if (hit == null || (hit.title().isEmpty() && hit.url().isEmpty())) {
                continue;
            }
            if (!normalizedSite.isEmpty() && !matchesSite(hit.url(), normalizedSite)) {
                continue;
            }
            String key =
                hit.url().isEmpty() ? "title:" + hit.title().toLowerCase(Locale.ROOT) : canonicalUrl(hit.url());
            MergedHit existing = merged.get(key);
            if (existing == null) {
                merged.put(key, new MergedHit(hit, index));
            } else {
                existing.merge(hit);
            }
            index++;
        }
        List<String> tokens = extractQueryTokens(query);
        List<MergedHit> ranked = new ArrayList<>(merged.values());
        ranked.sort(Comparator.comparingDouble((MergedHit item) -> item.effectiveScore(tokens)).reversed()
            .thenComparingInt(item -> item.firstIndex));
        List<SearchHit> result = new ArrayList<>(Math.min(limit, ranked.size()));
        for (MergedHit item : ranked) {
            if (result.size() >= limit) {
                break;
            }
            result.add(item.toHit(tokens));
        }
        return List.copyOf(result);
    }

    /** 判断命中 URL 是否归属于限定站点或其子域名。 */
    public static boolean matchesSite(String rawUrl, String site) {
        if (site == null || site.isBlank()) {
            return true;
        }
        if (rawUrl == null || rawUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(rawUrl.strip());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            String lowerSite = site.strip().toLowerCase(Locale.ROOT);
            return lowerHost.equals(lowerSite) || lowerHost.endsWith("." + lowerSite);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static List<String> extractQueryTokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String lower = query.toLowerCase(Locale.ROOT);
        Matcher ascii = ASCII_TOKEN.matcher(lower);
        while (ascii.find()) {
            String token = ascii.group();
            if (!STOP_WORDS.contains(token) && !token.startsWith("site:")) {
                tokens.add(token);
            }
        }
        StringBuilder hanRun = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                hanRun.append(ch);
            } else {
                addHanTokens(hanRun, tokens);
            }
        }
        addHanTokens(hanRun, tokens);
        return List.copyOf(tokens);
    }

    private static void addHanTokens(StringBuilder run, Set<String> out) {
        int len = run.length();
        if (len == 0) {
            return;
        }
        if (len <= 4) {
            out.add(run.toString());
        } else {
            for (int i = 0; i < len - 1 && out.size() < 16; i++) {
                out.add(run.substring(i, i + 2));
            }
        }
        run.setLength(0);
    }

    /** 规范化 URL：统一小写 host、移除默认端口/片段/跟踪参数和尾部斜杠。 */
    public static String canonicalUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        String trimmed = rawUrl.strip();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (scheme.isEmpty() || host.isEmpty()) {
                return trimmed;
            }
            int port = uri.getPort();
            boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if ("/".equals(path)) {
                path = "";
            }
            String query = filterTrackingQuery(uri.getRawQuery());
            StringBuilder out = new StringBuilder();
            out.append(scheme).append("://").append(host);
            if (port > 0 && !defaultPort) {
                out.append(':').append(port);
            }
            out.append(path);
            if (!query.isEmpty()) {
                out.append('?').append(query);
            }
            return out.toString();
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    private static String filterTrackingQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = (eq >= 0 ? pair.substring(0, eq) : pair).toLowerCase(Locale.ROOT);
            if (name.startsWith("utm_") || TRACKING_PARAMS.contains(name)) {
                continue;
            }
            kept.add(pair);
        }
        return String.join("&", kept);
    }

    public static String collapseSpace(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").strip();
    }

    private static final class MergedHit {
        private final int firstIndex;
        private String title;
        private String url;
        private String snippet;
        private String publishedDate;
        private final LinkedHashSet<String> engines = new LinkedHashSet<>();
        private double baseScore;
        private int duplicates;

        private MergedHit(SearchHit hit, int firstIndex) {
            this.firstIndex = firstIndex;
            this.title = hit.title();
            this.url = hit.url();
            this.snippet = hit.snippet();
            this.publishedDate = hit.publishedDate();
            this.engines.addAll(hit.engines());
            this.baseScore = hit.score();
        }

        private void merge(SearchHit other) {
            duplicates++;
            if (other.title().length() > title.length()) {
                title = other.title();
            }
            if (url.isEmpty() || (!other.url().isEmpty() && other.url().length() < url.length())) {
                url = other.url();
            }
            if (other.snippet().length() > snippet.length()) {
                snippet = other.snippet();
            }
            if (publishedDate.isEmpty() && !other.publishedDate().isEmpty()) {
                publishedDate = other.publishedDate();
            }
            engines.addAll(other.engines());
            baseScore = Math.max(baseScore, other.score());
        }

        private double effectiveScore(List<String> tokens) {
            double multiEngineBoost = Math.max(0, engines.size() - 1) * 0.25 + duplicates * 0.35;
            double lexicalBoost = lexicalRelevance(tokens);
            double rankPrior = 0.25 / (1.0 + firstIndex * 0.2);
            return baseScore + multiEngineBoost + lexicalBoost + rankPrior;
        }

        private double lexicalRelevance(List<String> tokens) {
            if (tokens.isEmpty()) {
                return 0.0;
            }
            String lowerTitle = title.toLowerCase(Locale.ROOT);
            String lowerUrl = url.toLowerCase(Locale.ROOT);
            String lowerSnippet = snippet.toLowerCase(Locale.ROOT);
            int titleMatches = 0;
            int urlMatches = 0;
            int snippetMatches = 0;
            int anyMatches = 0;
            for (String token : tokens) {
                boolean inTitle = lowerTitle.contains(token);
                boolean inUrl = lowerUrl.contains(token);
                boolean inSnippet = lowerSnippet.contains(token);
                if (inTitle) {
                    titleMatches++;
                }
                if (inUrl) {
                    urlMatches++;
                }
                if (inSnippet) {
                    snippetMatches++;
                }
                if (inTitle || inUrl || inSnippet) {
                    anyMatches++;
                }
            }
            if (anyMatches == 0) {
                return -0.85;
            }
            double total = tokens.size();
            return 1.2 * (titleMatches / total) + 0.5 * (urlMatches / total) + 0.6 * (snippetMatches / total);
        }

        private SearchHit toHit(List<String> tokens) {
            return new SearchHit(title, url, snippet, publishedDate, List.copyOf(engines), effectiveScore(tokens));
        }
    }
}
