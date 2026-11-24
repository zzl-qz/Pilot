package com.pilot.core.tool.search;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.pilot.core.tool.WebSearchTool;

/**
 * 联网搜索运行时配置。
 *
 * <p>覆盖 {@link #toString()} 以避免日志或异常堆栈输出明文 API Key。
 */
public record WebSearchConfig(WebSearchProvider provider, String apiKey, URI apiEndpoint, URI searxngEndpoint,
    Duration timeout, boolean fallbackEnabled, Map<WebSearchProvider, String> providerKeys,
    PublicSources publicSources) {

    /** 免密钥公开只读官方 API 的基址集合；测试可替换为本机模拟服务。 */
    public record PublicSources(URI wikipediaZh, URI wikipediaEn, URI github, URI stackExchange, URI arxiv) {
        public static final PublicSources DEFAULT = new PublicSources(
            URI.create("https://zh.wikipedia.org/w/api.php"),
            URI.create("https://en.wikipedia.org/w/api.php"),
            URI.create("https://api.github.com/search/repositories"),
            URI.create("https://api.stackexchange.com/2.3/search/advanced"),
            URI.create("https://export.arxiv.org/api/query"));
    }

    public WebSearchConfig {
        provider = provider == null ? WebSearchProvider.AUTO : provider;
        apiKey = apiKey == null ? "" : apiKey.strip();
        apiEndpoint = apiEndpoint == null ? null : stripTrailingSlash(apiEndpoint);
        searxngEndpoint =
            searxngEndpoint == null ? WebSearchTool.DEFAULT_ENDPOINT : stripTrailingSlash(searxngEndpoint);
        timeout = Objects.requireNonNull(timeout == null ? WebSearchTool.DEFAULT_TIMEOUT : timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("搜索请求超时必须为正数");
        }
        Map<WebSearchProvider, String> cleaned = new EnumMap<>(WebSearchProvider.class);
        if (providerKeys != null) {
            for (Map.Entry<WebSearchProvider, String> entry : providerKeys.entrySet()) {
                if (entry.getKey() != null && entry.getKey().requiresApiKey()
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                    cleaned.put(entry.getKey(), entry.getValue().strip());
                }
            }
        }
        providerKeys = Map.copyOf(cleaned);
        publicSources = publicSources == null ? PublicSources.DEFAULT : publicSources;
    }

    /** 兼容旧单端点 SearXNG 构造器：不自动回退外部公开源，确保离线与单元测试行为确定。 */
    public static WebSearchConfig searxngOnly(URI endpoint, Duration timeout) {
        return new WebSearchConfig(WebSearchProvider.SEARXNG, "", null, endpoint, timeout, false, Map.of(),
            PublicSources.DEFAULT);
    }

    public String resolveApiKey(WebSearchProvider target) {
        if (target == null || !target.requiresApiKey()) {
            return "";
        }
        if (provider == target && !apiKey.isBlank()) {
            return apiKey;
        }
        String specific = providerKeys.get(target);
        if (specific != null && !specific.isBlank()) {
            return specific;
        }
        if (provider == WebSearchProvider.AUTO && !apiKey.isBlank() && inferGenericApiProvider() == target) {
            return apiKey;
        }
        return "";
    }

    public boolean hasApiKey(WebSearchProvider target) {
        return !resolveApiKey(target).isBlank();
    }

    /** AUTO 模式下按固定顺序挑选首个已配置密钥的搜索 API。 */
    public WebSearchProvider resolveAutoApiProvider() {
        for (WebSearchProvider candidate : WebSearchProvider.apiProviders()) {
            String specific = providerKeys.get(candidate);
            if (specific != null && !specific.isBlank()) {
                return candidate;
            }
        }
        if (!apiKey.isBlank()) {
            return inferGenericApiProvider();
        }
        return null;
    }

    public URI resolveEndpoint(WebSearchProvider target) {
        if (target == WebSearchProvider.SEARXNG) {
            return searxngEndpoint;
        }
        if (apiEndpoint != null && (target == provider || provider == WebSearchProvider.AUTO)) {
            return apiEndpoint;
        }
        if (target != null && !target.defaultEndpoint().isBlank()) {
            return URI.create(target.defaultEndpoint());
        }
        return searxngEndpoint;
    }

    private WebSearchProvider inferGenericApiProvider() {
        if (apiEndpoint != null && apiEndpoint.getHost() != null) {
            String host = apiEndpoint.getHost().toLowerCase(Locale.ROOT);
            if (host.contains("bocha")) {
                return WebSearchProvider.BOCHA;
            }
            if (host.contains("tavily")) {
                return WebSearchProvider.TAVILY;
            }
            if (host.contains("brave")) {
                return WebSearchProvider.BRAVE;
            }
            if (host.contains("serper")) {
                return WebSearchProvider.SERPER;
            }
            if (host.contains("exa.ai")) {
                return WebSearchProvider.EXA;
            }
        }
        if (apiKey.startsWith("tvly-")) {
            return WebSearchProvider.TAVILY;
        }
        if (apiKey.startsWith("BSA")) {
            return WebSearchProvider.BRAVE;
        }
        return null;
    }

    public static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        String trimmed = secret.strip();
        return trimmed.length() <= 8 ? "••••"
            : trimmed.substring(0, Math.min(6, trimmed.length())) + "…" + trimmed.substring(trimmed.length() - 4);
    }

    private static URI stripTrailingSlash(URI uri) {
        String text = uri.toString();
        while (text.endsWith("/") && text.length() > "http://a".length()) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text);
    }

    @Override
    public String toString() {
        return "WebSearchConfig[provider=" + provider + ", apiKey=" + maskSecret(apiKey)
            + ", apiEndpoint=" + apiEndpoint + ", searxngEndpoint=" + searxngEndpoint
            + ", timeout=" + timeout + ", fallbackEnabled=" + fallbackEnabled
            + ", providerKeys=" + providerKeys.keySet() + "]";
    }
}
