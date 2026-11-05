package com.pilot.core.runtime.bootstrap;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 使用既有代理与系统证书，不降低 TLS 校验，也不把代理密码写入命令行。 */
final class BootstrapHttp {
    static HttpClient client(Map<String, String> env) {
        var builder =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL);
        String value = variable(env, "HTTPS_PROXY");
        if (value.isBlank()) {
            value = variable(env, "HTTP_PROXY");
        }
        if (value.isBlank()) {
            return builder.build();
        }
        URI proxy;
        try {
            proxy = URI.create(value);
            if (!"http".equals(proxy.getScheme()) || proxy.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("下载代理配置无效，请使用 HTTP CONNECT 代理地址");
        }
        int port = proxy.getPort() < 0 ? 80 : proxy.getPort();
        var address = new InetSocketAddress(proxy.getHost(), port);
        String noProxy = variable(env, "NO_PROXY");
        builder.proxy(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return bypass(uri.getHost(), noProxy) ? List.of(Proxy.NO_PROXY)
                    : List.of(new Proxy(Proxy.Type.HTTP, address));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress target, IOException failure) {
                // 不记录可能包含代理认证信息的地址。
            }
        });
        if (proxy.getRawUserInfo() != null) {
            String[] credentials = proxy.getRawUserInfo().split(":", 2);
            String user = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8);
            char[] password = credentials.length == 2
                ? URLDecoder.decode(credentials[1], StandardCharsets.UTF_8).toCharArray() : new char[0];
            String host = proxy.getHost();
            builder.authenticator(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return getRequestorType() == RequestorType.PROXY && host.equalsIgnoreCase(getRequestingHost())
                        && port == getRequestingPort() ? new PasswordAuthentication(user, password) : null;
                }
            });
        }
        return builder.build();
    }

    static boolean bypass(String host, String exclusions) {
        if (host == null) {
            return false;
        }
        for (String item : exclusions.split(",")) {
            String rule = item.trim();
            if (rule.equals("*") || host.equalsIgnoreCase(rule)) {
                return true;
            }
            String domain = rule.startsWith(".") ? rule.substring(1) : rule;
            if (!domain.isBlank() && host.equalsIgnoreCase(domain)) {
                return true;
            }
            String suffix = "." + domain;
            if (!rule.isBlank() && host.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String variable(Map<String, String> env, String key) {
        return env.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue)
            .findFirst().orElse("");
    }
}
