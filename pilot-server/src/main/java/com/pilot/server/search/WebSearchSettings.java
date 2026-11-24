package com.pilot.server.search;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.pilot.core.tool.search.WebSearchConfig;
import com.pilot.core.tool.search.WebSearchProvider;
import com.pilot.core.util.SecretScrubber;
import com.pilot.server.config.WebSearchProperties;
import com.pilot.server.store.SettingsStore;

import lombok.extern.slf4j.Slf4j;

/**
 * 联网搜索运行时设置（提供商、API 密钥、SearXNG 地址、回退开关与出网代理）。
 *
 * <p>优先级：SettingsStore 持久化值 &gt; 配置文件 / 环境变量 {@code pilot.web-search.*}。
 * 修改后立即对后续 {@code web_search} 调用生效，无需重启；对外不回传明文密钥。
 */
@Component
@Slf4j
public class WebSearchSettings {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String KEY_PROVIDER = "web-search.provider";
    private static final String KEY_API_KEY = "web-search.api-key";
    private static final String KEY_API_ENDPOINT = "web-search.api-endpoint";
    private static final String KEY_SEARXNG_ENDPOINT = "web-search.searxng-endpoint";
    private static final String KEY_FALLBACK_ENABLED = "web-search.fallback-enabled";
    private static final String KEY_PROXY = "web-search.proxy";

    public record Snapshot(WebSearchProvider provider, String apiKey, Map<WebSearchProvider, String> providerKeys,
        String apiEndpoint, String searxngEndpoint, boolean fallbackEnabled, String proxy, Duration timeout) {

        public WebSearchConfig toConfig(WebSearchConfig.PublicSources publicSources) {
            URI apiUri = apiEndpoint == null || apiEndpoint.isBlank() ? null : URI.create(apiEndpoint);
            return new WebSearchConfig(provider, apiKey, apiUri, URI.create(searxngEndpoint), timeout,
                fallbackEnabled, providerKeys, publicSources);
        }

        public WebSearchConfig toConfig() {
            return toConfig(WebSearchConfig.PublicSources.DEFAULT);
        }

        public String activeApiKey() {
            WebSearchConfig cfg = toConfig();
            if (provider.requiresApiKey()) {
                return cfg.resolveApiKey(provider);
            }
            WebSearchProvider auto = cfg.resolveAutoApiProvider();
            return auto == null ? apiKey : cfg.resolveApiKey(auto);
        }

        public String effectiveProviderId() {
            if (provider != WebSearchProvider.AUTO) {
                return provider.id();
            }
            WebSearchProvider auto = toConfig().resolveAutoApiProvider();
            return auto != null ? auto.id() : WebSearchProvider.SEARXNG.id();
        }

        @Override
        public String toString() {
            return "Snapshot[provider=" + provider + ", apiKey=" + WebSearchConfig.maskSecret(apiKey)
                + ", providerKeys=" + providerKeys.keySet() + ", apiEndpoint=" + apiEndpoint
                + ", searxngEndpoint=" + searxngEndpoint + ", fallbackEnabled=" + fallbackEnabled
                + ", proxy=" + SecretScrubber.scrub(proxy) + ", timeout=" + timeout + "]";
        }
    }

    public record ProviderView(String id, String label, String description, boolean requiresApiKey,
        String defaultEndpoint, boolean apiKeySet, String apiKeyMasked) {
    }

    public record View(String provider, String effectiveProvider, String apiEndpoint, boolean apiKeySet,
        String apiKeyMasked, String searxngEndpoint, boolean fallbackEnabled, String proxy,
        int requestTimeoutSeconds, boolean localEnabled, boolean localManaged, boolean localRunning,
        boolean localPortOpen, boolean localSettingsUpToDate, String localSettingsPath, String localLogPath,
        String localMessage, List<ProviderView> providers) {
    }

    public record UpdateCommand(String provider, String apiKey, Boolean clearApiKey, String apiEndpoint,
        String searxngEndpoint, Boolean fallbackEnabled, String proxy) {
    }

    private final WebSearchProperties defaults;
    private final SettingsStore store;
    private volatile Snapshot current;
    private volatile InetSocketAddress activeHttpProxy;
    private final HttpClient dynamicClient;

    public WebSearchSettings(WebSearchProperties defaults, SettingsStore store) {
        this.defaults = defaults;
        this.store = store;
        this.current = restore(defaults);
        this.activeHttpProxy = parseHttpProxyAddress(this.current.proxy());
        this.dynamicClient = buildClient(this::currentProxyAddress);
    }

    public Snapshot snapshot() {
        return current;
    }

    public WebSearchConfig toConfig() {
        return current.toConfig();
    }

    public String effectiveProxy() {
        return current.proxy();
    }

    /** 共用的动态代理感知 HTTP 客户端：本机回环始终直连，外网按当前 HTTP 代理设置转发。 */
    public HttpClient httpClient() {
        return dynamicClient;
    }

    /** 构造一次性代理客户端（供设置页未保存配置测试用）；无变化时直接返回 null 复用默认客户端。 */
    public HttpClient createTemporaryClientIfNeeded(String proxyOverride) {
        String normalized = proxyOverride == null ? current.proxy()
            : (proxyOverride.isBlank() ? "" : WebSearchProperties.normalizeProxy(proxyOverride));
        if (normalized.equals(current.proxy())) {
            return null;
        }
        InetSocketAddress addr = parseHttpProxyAddress(normalized);
        return buildClient(() -> addr);
    }

    public synchronized Snapshot update(UpdateCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Snapshot before = current;
        Map<String, String> writes = new LinkedHashMap<>();

        WebSearchProvider nextProvider = command.provider() == null
            ? before.provider() : WebSearchProvider.parse(command.provider());
        if (command.provider() != null) {
            writes.put(KEY_PROVIDER, nextProvider.id());
        }

        Map<WebSearchProvider, String> nextProviderKeys = new EnumMap<>(WebSearchProvider.class);
        nextProviderKeys.putAll(before.providerKeys());
        String nextGenericKey = before.apiKey();

        if (Boolean.TRUE.equals(command.clearApiKey())) {
            if (nextProvider.requiresApiKey()) {
                nextProviderKeys.remove(nextProvider);
                writes.put(providerKeySlot(nextProvider), "");
            }
            nextGenericKey = "";
            writes.put(KEY_API_KEY, "");
        } else if (command.apiKey() != null && !command.apiKey().isBlank()) {
            String trimmedKey = command.apiKey().strip();
            if (nextProvider.requiresApiKey()) {
                nextProviderKeys.put(nextProvider, trimmedKey);
                writes.put(providerKeySlot(nextProvider), trimmedKey);
            } else {
                nextGenericKey = trimmedKey;
                writes.put(KEY_API_KEY, trimmedKey);
            }
        }

        String nextApiEndpoint = before.apiEndpoint();
        if (command.apiEndpoint() != null) {
            nextApiEndpoint = command.apiEndpoint().isBlank() ? ""
                : WebSearchProperties.normalizeHttpEndpoint(command.apiEndpoint(), "apiEndpoint");
            writes.put(KEY_API_ENDPOINT, nextApiEndpoint);
        }

        String nextSearxngEndpoint = before.searxngEndpoint();
        if (command.searxngEndpoint() != null) {
            nextSearxngEndpoint = command.searxngEndpoint().isBlank() ? defaults.endpoint()
                : WebSearchProperties.normalizeHttpEndpoint(command.searxngEndpoint(), "searxngEndpoint");
            writes.put(KEY_SEARXNG_ENDPOINT, nextSearxngEndpoint);
        }

        boolean nextFallback = command.fallbackEnabled() == null
            ? before.fallbackEnabled() : command.fallbackEnabled();
        if (command.fallbackEnabled() != null) {
            writes.put(KEY_FALLBACK_ENABLED, Boolean.toString(nextFallback));
        }

        String nextProxy = before.proxy();
        if (command.proxy() != null) {
            nextProxy = command.proxy().isBlank() ? "" : WebSearchProperties.normalizeProxy(command.proxy());
            writes.put(KEY_PROXY, nextProxy);
        }

        if (!writes.isEmpty()) {
            store.saveAll(writes);
        }
        Snapshot next = new Snapshot(nextProvider, nextGenericKey, Map.copyOf(nextProviderKeys), nextApiEndpoint,
            nextSearxngEndpoint, nextFallback, nextProxy, before.timeout());
        current = next;
        activeHttpProxy = parseHttpProxyAddress(nextProxy);
        log.info("联网搜索设置已更新：{}", next);
        return next;
    }

    public synchronized Snapshot clearApiKey(String providerId) {
        Snapshot before = current;
        Map<String, String> writes = new LinkedHashMap<>();
        Map<WebSearchProvider, String> nextProviderKeys = new EnumMap<>(WebSearchProvider.class);
        nextProviderKeys.putAll(before.providerKeys());
        String nextGenericKey = before.apiKey();

        WebSearchProvider target = providerId == null || providerId.isBlank()
            ? before.provider() : WebSearchProvider.parse(providerId);
        if (target.requiresApiKey()) {
            nextProviderKeys.remove(target);
            writes.put(providerKeySlot(target), "");
            if (before.provider() == target || before.provider() == WebSearchProvider.AUTO) {
                nextGenericKey = "";
                writes.put(KEY_API_KEY, "");
            }
        } else {
            nextGenericKey = "";
            writes.put(KEY_API_KEY, "");
        }
        store.saveAll(writes);
        Snapshot next = new Snapshot(before.provider(), nextGenericKey, Map.copyOf(nextProviderKeys),
            before.apiEndpoint(), before.searxngEndpoint(), before.fallbackEnabled(), before.proxy(), before.timeout());
        current = next;
        log.info("已清除联网搜索 API Key（目标提供商：{}）", target.id());
        return next;
    }

    /** 构造只读探测配置（不落盘、不修改当前状态），支持叠加未保存的表单字段。 */
    public WebSearchConfig probeConfig(UpdateCommand overrides, WebSearchConfig.PublicSources publicSources) {
        Snapshot base = current;
        if (overrides == null) {
            return base.toConfig(publicSources);
        }
        WebSearchProvider provider = overrides.provider() == null || overrides.provider().isBlank()
            ? base.provider() : WebSearchProvider.parse(overrides.provider());
        Map<WebSearchProvider, String> keys = new EnumMap<>(WebSearchProvider.class);
        keys.putAll(base.providerKeys());
        String genericKey = base.apiKey();
        if (Boolean.TRUE.equals(overrides.clearApiKey())) {
            if (provider.requiresApiKey()) {
                keys.remove(provider);
            }
            genericKey = "";
        } else if (overrides.apiKey() != null && !overrides.apiKey().isBlank()) {
            String trimmed = overrides.apiKey().strip();
            if (provider.requiresApiKey()) {
                keys.put(provider, trimmed);
            } else {
                genericKey = trimmed;
            }
        }
        String apiEndpoint = overrides.apiEndpoint() == null ? base.apiEndpoint()
            : (overrides.apiEndpoint().isBlank() ? ""
                : WebSearchProperties.normalizeHttpEndpoint(overrides.apiEndpoint(), "apiEndpoint"));
        String searxngEndpoint = overrides.searxngEndpoint() == null || overrides.searxngEndpoint().isBlank()
            ? base.searxngEndpoint()
            : WebSearchProperties.normalizeHttpEndpoint(overrides.searxngEndpoint(), "searxngEndpoint");
        boolean fallback = overrides.fallbackEnabled() == null ? base.fallbackEnabled() : overrides.fallbackEnabled();
        URI apiUri = apiEndpoint.isBlank() ? null : URI.create(apiEndpoint);
        return new WebSearchConfig(provider, genericKey, apiUri, URI.create(searxngEndpoint), base.timeout(), fallback,
            Map.copyOf(keys), publicSources);
    }

    public View view(LocalSearchRuntime.RuntimeStatus runtimeStatus) {
        Snapshot snap = current;
        WebSearchConfig cfg = snap.toConfig();
        String activeKey = snap.activeApiKey();
        List<ProviderView> providers = new ArrayList<>();
        for (WebSearchProvider p : WebSearchProvider.values()) {
            String key = p.requiresApiKey() ? cfg.resolveApiKey(p) : "";
            providers.add(new ProviderView(p.id(), p.label(), p.description(), p.requiresApiKey(), p.defaultEndpoint(),
                !key.isBlank(), WebSearchConfig.maskSecret(key)));
        }
        boolean localEnabled = runtimeStatus != null ? runtimeStatus.localEnabled() : defaults.local();
        boolean localManaged = runtimeStatus != null ? runtimeStatus.managed() : defaults.managedHost() != null;
        boolean localRunning = runtimeStatus != null && runtimeStatus.running();
        boolean localPortOpen = runtimeStatus != null && runtimeStatus.portOpen();
        boolean localSettingsUpToDate = runtimeStatus == null || runtimeStatus.settingsUpToDate();
        String settingsPath = runtimeStatus == null ? "" : runtimeStatus.settingsPath();
        String logPath = runtimeStatus == null ? "" : runtimeStatus.logPath();
        String localMessage = runtimeStatus == null ? "本机搜索运行时未启用" : runtimeStatus.message();
        return new View(snap.provider().id(), snap.effectiveProviderId(), snap.apiEndpoint(), !activeKey.isBlank(),
            WebSearchConfig.maskSecret(activeKey), snap.searxngEndpoint(), snap.fallbackEnabled(),
            SecretScrubber.scrub(snap.proxy()), (int)snap.timeout().toSeconds(), localEnabled, localManaged,
            localRunning, localPortOpen, localSettingsUpToDate, settingsPath, logPath, localMessage,
            List.copyOf(providers));
    }

    private Snapshot restore(WebSearchProperties props) {
        WebSearchProvider provider = props.searchProvider();
        String storedProvider = store.find(KEY_PROVIDER).orElse("");
        if (!storedProvider.isBlank()) {
            try {
                provider = WebSearchProvider.parse(storedProvider);
            } catch (IllegalArgumentException e) {
                log.warn("忽略无效的已保存搜索提供商 {}，回退为 {}", storedProvider, provider.id());
            }
        }
        String genericKey = store.find(KEY_API_KEY).orElse(props.apiKey()).strip();
        Map<WebSearchProvider, String> providerKeys = new EnumMap<>(WebSearchProvider.class);
        Map<WebSearchProvider, String> defaultKeys = props.providerKeys();
        for (WebSearchProvider candidate : WebSearchProvider.apiProviders()) {
            String value = store.find(providerKeySlot(candidate))
                .orElse(defaultKeys.getOrDefault(candidate, "")).strip();
            if (!value.isBlank()) {
                providerKeys.put(candidate, value);
            }
        }
        String apiEndpoint = restoreOptionalEndpoint(store.find(KEY_API_ENDPOINT).orElse(props.apiEndpoint()),
            props.apiEndpoint());
        String searxngEndpoint = restoreRequiredEndpoint(store.find(KEY_SEARXNG_ENDPOINT).orElse(props.endpoint()),
            props.endpoint());
        boolean fallbackEnabled = store.find(KEY_FALLBACK_ENABLED)
            .filter(v -> !v.isBlank())
            .map(Boolean::parseBoolean)
            .orElse(props.fallbackEnabledDefault());
        String proxy = restoreOptionalProxy(store.find(KEY_PROXY).orElse(props.proxy()), props.proxy());
        return new Snapshot(provider, genericKey, Map.copyOf(providerKeys), apiEndpoint, searxngEndpoint,
            fallbackEnabled, proxy, props.requestTimeout());
    }

    private static String restoreOptionalEndpoint(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return WebSearchProperties.normalizeHttpEndpoint(raw, "apiEndpoint");
        } catch (IllegalArgumentException e) {
            return fallback == null ? "" : fallback;
        }
    }

    private static String restoreRequiredEndpoint(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return WebSearchProperties.normalizeHttpEndpoint(raw, "searxngEndpoint");
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String restoreOptionalProxy(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return WebSearchProperties.normalizeProxy(raw);
        } catch (IllegalArgumentException e) {
            return fallback == null ? "" : fallback;
        }
    }

    private static String providerKeySlot(WebSearchProvider provider) {
        return KEY_API_KEY + "." + provider.id();
    }

    private InetSocketAddress currentProxyAddress() {
        return activeHttpProxy;
    }

    private static InetSocketAddress parseHttpProxyAddress(String proxy) {
        if (proxy == null || proxy.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(proxy.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            return InetSocketAddress.createUnresolved(host, port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static HttpClient buildClient(java.util.function.Supplier<InetSocketAddress> proxySupplier) {
        ProxySelector selector = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                InetSocketAddress proxy = proxySupplier.get();
                if (proxy == null || uri == null || isLoopbackHost(uri.getHost())) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(Proxy.Type.HTTP, proxy));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                log.debug("搜索代理连接失败：{} via {} ({})", uri, sa, ioe.getMessage());
            }
        };
        return HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(selector)
            .build();
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.strip().toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || "127.0.0.1".equals(lower) || "::1".equals(lower) || "[::1]".equals(lower);
    }
}
