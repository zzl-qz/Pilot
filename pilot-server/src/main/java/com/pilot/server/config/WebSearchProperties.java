package com.pilot.server.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import com.pilot.core.tool.WebSearchTool;

/** 本机 SearXNG。local 为 true 时，Pilot 启动会把它拉起来。 */
@ConfigurationProperties(prefix = "pilot.web-search")
public record WebSearchProperties(boolean local, String endpoint, String python, String home,
    Integer requestTimeoutSeconds) {
    public WebSearchProperties(boolean local, String endpoint, String python, String home) {
        this(local, endpoint, python, home, null);
    }

    @ConstructorBinding
    public WebSearchProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = WebSearchTool.DEFAULT_ENDPOINT.toString();
        }
        endpoint = endpoint.strip();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        URI uri = URI.create(endpoint);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
            || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException("pilot.web-search.endpoint 必须为不含凭据、查询参数或片段的 HTTP/HTTPS 地址");
        }
        if (local && managedHost(uri) != null && uri.getPort() < 0) {
            endpoint = uri.getScheme() + "://" + uri.getHost() + ":" + WebSearchTool.DEFAULT_ENDPOINT.getPort();
        }
        if (requestTimeoutSeconds != null && requestTimeoutSeconds < 1) {
            throw new IllegalArgumentException("pilot.web-search.request-timeout-seconds 必须为正数");
        }
        python = python == null ? "" : python.strip();
        home = home == null ? "" : home.strip();
    }

    public URI endpointUri() {
        return URI.create(endpoint);
    }

    /** 内置服务只监听回环 HTTP 根地址。代理和独立部署由外部进程提供。 */
    public String managedHost() {
        return local ? managedHost(endpointUri()) : null;
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
}
