package com.pilot.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** 本机 SearXNG。local 为 true 时，Pilot 启动会把它拉起来。 */
@ConfigurationProperties(prefix = "pilot.web-search")
public record WebSearchProperties(boolean local, String endpoint, String python, String home) {
    public WebSearchProperties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://127.0.0.1:8888";
        }
        endpoint = endpoint.strip();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        python = python == null ? "" : python.strip();
        home = home == null ? "" : home.strip();
    }

    public URI endpointUri() {
        return URI.create(endpoint);
    }

    /** 只在访问本机时才由 Pilot 负责拉起进程。 */
    public boolean loopback() {
        String host = endpointUri().getHost();
        return host == null
                || "127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host);
    }

    public int port() {
        int port = endpointUri().getPort();
        return port > 0 ? port : 8888;
    }
}
