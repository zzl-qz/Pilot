package com.pilot.server.config;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import com.pilot.core.tool.WebSearchTool;
import com.pilot.core.tool.search.WebSearchConfig;
import com.pilot.core.tool.search.WebSearchProvider;
import com.pilot.core.util.SecretScrubber;

/** 联网搜索配置：支持多搜索 API 提供商、本机或自建 SearXNG、代理以及免密钥公开源兜底。 */
@ConfigurationProperties(prefix = "pilot.web-search")
public record WebSearchProperties(boolean local, String endpoint, String python, String home,
    Integer requestTimeoutSeconds, String provider, String apiKey, String apiEndpoint, Boolean fallbackEnabled,
    String proxy, String bochaApiKey, String tavilyApiKey, String braveApiKey, String serperApiKey, String exaApiKey) {

    public WebSearchProperties(boolean local, String endpoint, String python, String home) {
        this(local, endpoint, python, home, null, null, null, null, null, null, null, null, null, null, null);
    }

    public WebSearchProperties(boolean local, String endpoint, String python, String home,
        Integer requestTimeoutSeconds) {
        this(local, endpoint, python, home, requestTimeoutSeconds, null, null, null, null, null, null, null, null,
            null, null);
    }

    @ConstructorBinding
    public WebSearchProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = WebSearchTool.DEFAULT_ENDPOINT.toString();
        }
        endpoint = normalizeHttpEndpoint(endpoint, "pilot.web-search.endpoint");
        URI uri = URI.create(endpoint);
        if (local && managedHost(uri) != null && uri.getPort() < 0) {
            endpoint = uri.getScheme() + "://" + uri.getHost() + ":" + WebSearchTool.DEFAULT_ENDPOINT.getPort();
        }
        if (requestTimeoutSeconds != null && requestTimeoutSeconds < 1) {
            throw new IllegalArgumentException("pilot.web-search.request-timeout-seconds 必须为正数");
        }
        python = clean(python);
        home = clean(home);
        provider = WebSearchProvider.parse(provider).id();
        apiKey = clean(apiKey);
        apiEndpoint = apiEndpoint == null || apiEndpoint.isBlank() ? ""
            : normalizeHttpEndpoint(apiEndpoint, "pilot.web-search.api-endpoint");
        proxy = proxy == null || proxy.isBlank() ? "" : normalizeProxy(proxy);
        bochaApiKey = clean(bochaApiKey);
        tavilyApiKey = clean(tavilyApiKey);
        braveApiKey = clean(braveApiKey);
        serperApiKey = clean(serperApiKey);
        exaApiKey = clean(exaApiKey);
    }

    public URI endpointUri() {
        return URI.create(endpoint);
    }

    public WebSearchProvider searchProvider() {
        return WebSearchProvider.parse(provider);
    }

    public URI apiEndpointUri() {
        return apiEndpoint.isBlank() ? null : URI.create(apiEndpoint);
    }

    public boolean fallbackEnabledDefault() {
        return fallbackEnabled == null || fallbackEnabled;
    }

    public Map<WebSearchProvider, String> providerKeys() {
        Map<WebSearchProvider, String> map = new EnumMap<>(WebSearchProvider.class);
        putIfPresent(map, WebSearchProvider.BOCHA, bochaApiKey);
        putIfPresent(map, WebSearchProvider.TAVILY, tavilyApiKey);
        putIfPresent(map, WebSearchProvider.BRAVE, braveApiKey);
        putIfPresent(map, WebSearchProvider.SERPER, serperApiKey);
        putIfPresent(map, WebSearchProvider.EXA, exaApiKey);
        return Map.copyOf(map);
    }

    public WebSearchConfig toConfig() {
        return new WebSearchConfig(searchProvider(), apiKey, apiEndpointUri(), endpointUri(), requestTimeout(),
            fallbackEnabledDefault(), providerKeys(), WebSearchConfig.PublicSources.DEFAULT);
    }

    /** 内置服务只监听回环 HTTP 根地址。代理和独立部署由外部进程提供。 */
    public String managedHost() {
        return local ? managedHost(endpointUri()) : null;
    }

    public static String normalizeHttpEndpoint(String raw, String fieldName) {
        String value = raw.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " 必须为不含凭据、查询参数或片段的 HTTP/HTTPS 地址");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
            || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException(fieldName + " 必须为不含凭据、查询参数或片段的 HTTP/HTTPS 地址");
        }
        return value;
    }

    public static String normalizeProxy(String raw) {
        String value = raw.strip();
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("代理地址格式无效，请使用 http://host:port 或 socks5://host:port");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("代理地址不支持内嵌账号密码，请使用无凭据的代理地址（如 http://127.0.0.1:7890）");
        }
        if (!("http".equals(scheme) || "https".equals(scheme) || "socks5".equals(scheme) || "socks5h".equals(scheme))
            || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
            || uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("代理地址必须为合法的 http/https/socks5 地址（如 http://127.0.0.1:7890）");
        }
        return value;
    }

    private static String managedHost(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || !uri.getRawPath().isEmpty()) {
            return null;
        }
        String host = uri.getHost();
        if ("[::1]".equals(host)) {
            return "::1";
        }
        return WebSearchTool.DEFAULT_ENDPOINT.getHost().equals(host) || "localhost".equalsIgnoreCase(host)
            ? WebSearchTool.DEFAULT_ENDPOINT.getHost() : null;
    }

    public Duration requestTimeout() {
        return requestTimeoutSeconds == null ? WebSearchTool.DEFAULT_TIMEOUT
            : Duration.ofSeconds(requestTimeoutSeconds);
    }

    private static void putIfPresent(Map<WebSearchProvider, String> target, WebSearchProvider key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    @Override
    public String toString() {
        return "WebSearchProperties[local=" + local + ", endpoint=" + endpoint + ", provider=" + provider
            + ", apiKey=" + WebSearchConfig.maskSecret(apiKey) + ", apiEndpoint=" + apiEndpoint
            + ", fallbackEnabled=" + fallbackEnabledDefault() + ", proxy=" + SecretScrubber.scrub(proxy)
            + ", configuredProviderKeys=" + providerKeys().keySet() + "]";
    }
}
