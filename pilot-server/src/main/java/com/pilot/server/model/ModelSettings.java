package com.pilot.server.model;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.pilot.core.model.ModelCapacity;
import com.pilot.server.store.SettingsStore;

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class ModelSettings {
    private static final String KEY_ENDPOINT = "model.endpoint";
    private static final String KEY_MODEL = "model.name";
    private static final String KEY_API_KEY = "model.api-key";
    private static final String KEY_CONTEXT = "model.context-tokens";
    private static final String KEY_OUTPUT = "model.max-output-tokens";
    private static final String KEY_CHILD = "model.child-name";

    public record Snapshot(String endpoint, String model, String apiKey, Long contextTokens, Integer maxOutputTokens,
        String childModel) {
        public Snapshot(String endpoint, String model, String apiKey, Long contextTokens, Integer maxOutputTokens) {
            this(endpoint, model, apiKey, contextTokens, maxOutputTokens, "");
        }

        public Snapshot(String endpoint, String model, String apiKey) {
            this(endpoint, model, apiKey, null, null, "");
        }

        public Snapshot withModel(String name) {
            return new Snapshot(endpoint, name, apiKey, contextTokens, maxOutputTokens, childModel);
        }

        public ModelCapacity capacity() {
            return ModelCapacity.of(contextTokens, maxOutputTokens);
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** 只用于界面提示，不可反推原值。 */
        public String maskedApiKey() {
            if (!hasApiKey()) {
                return "";
            }
            String key = apiKey.strip();
            return key.length() <= 8 ? "••••"
                : key.substring(0, Math.min(6, key.length())) + "…" + key.substring(key.length() - 4);
        }

        /** 覆盖默认 record.toString()，避免日志或异常堆栈误打印明文 API Key。 */
        @Override
        public String toString() {
            return "Snapshot[endpoint=" + endpoint + ", model=" + model + ", apiKey="
                + (hasApiKey() ? maskedApiKey() : "") + ", contextTokens=" + contextTokens + ", maxOutputTokens="
                + maxOutputTokens + ", childModel=" + childModel + "]";
        }
    }

    private final SettingsStore store;
    private final List<String> options;
    private volatile Snapshot current;
    private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();

    public void onChange(Consumer<Snapshot> listener) {
        listeners.add(listener);
    }

    public ModelSettings(ModelProperties defaults, SettingsStore store) {
        this.store = store;
        this.options = defaults.options();
        this.current = restore(defaults);
    }

    public Snapshot snapshot() {
        return current;
    }

    /**
     * 「获取可用模型」的探测目标：入参留空时回退到已保存值（与 {@link #update} 同语义），
     * 只读、不落盘、不改当前配置。
     *
     * @throws IllegalArgumentException 回退后仍没有合法的 http(s) 端点
     */
    public synchronized Snapshot probeTarget(String endpoint, String apiKey) {
        Snapshot before = current;
        String targetEndpoint = blank(endpoint) ? before.endpoint() : endpoint.strip();
        String targetKey = blank(apiKey) ? before.apiKey() : apiKey.strip();
        requireHttpEndpoint(targetEndpoint);
        return new Snapshot(targetEndpoint, before.model(), targetKey, before.contextTokens(), before.maxOutputTokens(),
            before.childModel());
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

        Snapshot next = new Snapshot(nextEndpoint, nextModel, nextKey, before.contextTokens(), before.maxOutputTokens(),
            before.childModel());
        save(next);
        current = next;
        if (!next.equals(before)) {
            listeners.forEach(listener -> {
                try {
                    listener.accept(next);
                } catch (RuntimeException failure) {
                    log.warn("模型配置已保存，后台能力验证暂未启动");
                }
            });
        }
        log.info("模型配置已更新：model={} endpoint={} apiKey={}", next.model(), next.endpoint(),
            next.hasApiKey() ? "已配置" : "未配置");
        return next;
    }

    /** 留空或非正数表示恢复默认窗口；正数写入后，下一次请求按这个容量计算。 */
    public synchronized Snapshot updateWindow(Long contextTokens, Integer maxOutputTokens) {
        Snapshot before = current;
        Long context = contextTokens == null ? before.contextTokens() : contextTokens <= 0 ? null : contextTokens;
        Integer output =
            maxOutputTokens == null ? before.maxOutputTokens() : maxOutputTokens <= 0 ? null : maxOutputTokens;
        Snapshot next =
            new Snapshot(before.endpoint(), before.model(), before.apiKey(), context, output, before.childModel());
        save(next);
        current = next;
        ModelCapacity.activate(next.capacity());
        return next;
    }

    /** 空白表示清除子代理模型名。null 保持原值。不改主模型、密钥和窗口。 */
    public synchronized Snapshot updateChild(String childModel) {
        Snapshot before = current;
        String nextChild = childModel == null ? before.childModel() : childModel.strip();
        Snapshot next = new Snapshot(before.endpoint(), before.model(), before.apiKey(), before.contextTokens(),
            before.maxOutputTokens(), nextChild == null ? "" : nextChild);
        save(next);
        current = next;
        return next;
    }

    /** 原子切换整套模型配置（供多模型预设一键切换使用），统一落盘并触发能力刷新。 */
    public synchronized Snapshot applySnapshot(Snapshot target) {
        if (target == null) {
            throw new IllegalArgumentException("模型配置不能为空");
        }
        String nextEndpoint = target.endpoint() == null ? "" : target.endpoint().strip();
        String nextModel = target.model() == null ? "" : target.model().strip();
        if (blank(nextModel)) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        requireHttpEndpoint(nextEndpoint);
        String nextKey = target.apiKey() == null ? "" : target.apiKey().strip();
        Long context = target.contextTokens() != null && target.contextTokens() > 0 ? target.contextTokens() : null;
        Integer output = target.maxOutputTokens() != null && target.maxOutputTokens() > 0
            ? target.maxOutputTokens() : null;
        String child = target.childModel() == null ? "" : target.childModel().strip();

        Snapshot before = current;
        Snapshot next = new Snapshot(nextEndpoint, nextModel, nextKey, context, output, child);
        save(next);
        current = next;
        ModelCapacity.activate(next.capacity());
        if (!next.equals(before)) {
            listeners.forEach(listener -> {
                try {
                    listener.accept(next);
                } catch (RuntimeException failure) {
                    log.warn("模型预设已切换，后台能力验证暂未启动");
                }
            });
        }
        log.info("模型配置已通过预设切换：model={} endpoint={} apiKey={}", next.model(), next.endpoint(),
            next.hasApiKey() ? "已配置" : "未配置");
        return next;
    }

    private void save(Snapshot next) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_ENDPOINT, next.endpoint());
        values.put(KEY_MODEL, next.model());
        values.put(KEY_API_KEY, next.apiKey() == null ? "" : next.apiKey());
        values.put(KEY_CONTEXT, next.contextTokens() == null ? "" : Long.toString(next.contextTokens()));
        values.put(KEY_OUTPUT, next.maxOutputTokens() == null ? "" : Integer.toString(next.maxOutputTokens()));
        values.put(KEY_CHILD, next.childModel() == null ? "" : next.childModel());
        store.saveAll(values);
    }

    private Snapshot restore(ModelProperties defaults) {
        String endpoint = store.find(KEY_ENDPOINT).orElse(defaults.endpoint());
        String model = store.find(KEY_MODEL).orElse(defaults.model());
        String apiKey = store.find(KEY_API_KEY).orElse(defaults.apiKey());
        String child = store.find(KEY_CHILD).orElse("").strip();
        Snapshot restored = new Snapshot(endpoint, model, apiKey == null ? "" : apiKey, number(store.find(KEY_CONTEXT)),
            integer(store.find(KEY_OUTPUT)), child);
        ModelCapacity.activate(restored.capacity());
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

    private static Long number(Optional<String> text) {
        if (text.isEmpty() || text.get().isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(text.get().strip());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(Optional<String> text) {
        Long value = number(text);
        if (value == null || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }
}
