package com.pilot.server.config;

import com.pilot.server.store.SettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 当前生效的模型配置（端点 / 模型名 / API Key）。
 *
 * <p>优先级：库中保存的值 &gt; 配置文件 {@code pilot.model.*}。
 * 通过 {@code PUT /api/settings/model} 修改后立即对下一次模型调用生效，重启仍然保留。
 *
 * <p>API Key 只写不读：对外只暴露 {@link Snapshot#hasApiKey()} 与掩码，
 * 避免浏览器或日志里出现明文密钥。
 */
@Component
public class ModelSettings {
    private static final Logger log = LoggerFactory.getLogger(ModelSettings.class);
    private static final String KEY_ENDPOINT = "model.endpoint";
    private static final String KEY_MODEL = "model.name";
    private static final String KEY_API_KEY = "model.api-key";

    public record Snapshot(String endpoint, String model, String apiKey) {
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** 只用于界面提示，不可反推原值。 */
        public String maskedApiKey() {
            if (!hasApiKey()) {
                return "";
            }
            String key = apiKey.strip();
            return key.length() <= 8
                    ? "••••"
                    : key.substring(0, Math.min(6, key.length())) + "…" + key.substring(key.length() - 4);
        }
    }

    private final SettingsStore store;
    private final List<String> options;
    private volatile Snapshot current;

    public ModelSettings(ModelProperties defaults, SettingsStore store) {
        this.store = store;
        this.options = defaults.options();
        this.current = restore(defaults);
    }

    public Snapshot snapshot() {
        return current;
    }

    /** 界面上可选的模型名（不含用户自己填的），供下拉列表使用。 */
    public List<String> options() {
        return options;
    }

    /**
     * 局部更新：入参为 null 或空白表示保持原值。
     *
     * @throws IllegalArgumentException 端点不是合法的 http(s) 地址
     */
    public synchronized Snapshot update(String endpoint, String model, String apiKey) {
        return update(endpoint, model, apiKey, false);
    }

    /**
     * 局部更新并落盘。
     *
     * @param clearApiKey true 表示明确清除已保存的密钥（优先于 {@code apiKey}）；
     *                    否则 {@code apiKey} 为空白时保留原值
     */
    public synchronized Snapshot update(String endpoint, String model, String apiKey, boolean clearApiKey) {
        Snapshot before = current;
        String nextEndpoint = blank(endpoint) ? before.endpoint() : endpoint.strip();
        String nextModel = blank(model) ? before.model() : model.strip();
        String nextKey = clearApiKey ? "" : blank(apiKey) ? before.apiKey() : apiKey.strip();
        requireHttpEndpoint(nextEndpoint);

        Snapshot next = new Snapshot(nextEndpoint, nextModel, nextKey);
        store.saveAll(Map.of(
                KEY_ENDPOINT, next.endpoint(),
                KEY_MODEL, next.model(),
                KEY_API_KEY, next.apiKey()));
        current = next;
        log.info("模型配置已更新：model={} endpoint={} apiKey={}",
                next.model(), next.endpoint(), next.hasApiKey() ? "已配置" : "未配置");
        return next;
    }

    private Snapshot restore(ModelProperties defaults) {
        String endpoint = store.find(KEY_ENDPOINT).orElse(defaults.endpoint());
        String model = store.find(KEY_MODEL).orElse(defaults.model());
        String apiKey = store.find(KEY_API_KEY).orElse(defaults.apiKey());
        Snapshot restored = new Snapshot(endpoint, model, apiKey == null ? "" : apiKey);
        log.info("模型配置：model={} endpoint={} apiKey={}", restored.model(), restored.endpoint(),
                restored.hasApiKey() ? "已配置" : "未配置（可在界面设置）");
        return restored;
    }

    private static void requireHttpEndpoint(String endpoint) {
        if (blank(endpoint)) {
            throw new IllegalArgumentException("模型端点不能为空");
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("模型端点不是合法 URL：" + endpoint);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("模型端点必须是 http/https 地址：" + endpoint);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
