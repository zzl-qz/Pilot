package com.pilot.server.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.pilot.server.store.SettingsStore;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 多模型配置预设管理（本地 Ollama、免费路由、主力模型、子代理模型等）。
 *
 * <p>API Key 只持久化在本地 {@link SettingsStore} 中，对外仅返回 {@code apiKeySet} 与掩码，
 * 绝不向浏览器或日志暴露明文密钥。
 */
@Component
@Slf4j
public class ModelProfileStore {
    private static final String KEY_PROFILES = "model.profiles";
    private static final String KEY_ACTIVE = "model.active-profile";
    private static final String KEY_FALLBACK = "model.fallback-profile";
    private static final int MAX_PROFILES = 30;
    private static final int MAX_NAME_LENGTH = 60;

    private final SettingsStore store;
    private final ModelSettings models;
    private final ObjectMapper mapper;
    private final List<StoredProfile> profiles = new ArrayList<>();
    private String activeProfileId = "";
    private String fallbackProfileId = "";

    public ModelProfileStore(SettingsStore store, ModelSettings models, ObjectMapper mapper) {
        this.store = store;
        this.models = models;
        this.mapper = mapper;
        restore();
    }

    public synchronized ProfilesView list() {
        ModelSettings.Snapshot current = models.snapshot();
        String effectiveActive = resolveEffectiveActive(current);
        String effectiveFallback = resolveEffectiveFallback();
        List<ProfileView> views = profiles.stream()
            .map(profile -> toView(profile, profile.id().equals(effectiveActive), profile.id().equals(effectiveFallback)))
            .toList();
        return new ProfilesView(effectiveActive, effectiveFallback, views);
    }

    public synchronized ProfilesView create(SaveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (profiles.size() >= MAX_PROFILES) {
            throw new IllegalArgumentException("模型预设数量已达上限（最多 " + MAX_PROFILES + " 个）");
        }
        String name = requireValidName(request.name());
        ensureUniqueName(name, null);
        String endpoint = requireHttpEndpoint(request.endpoint());
        String model = requireNonBlank(request.model(), "模型名称不能为空");
        String apiKey = resolveCreateKey(request);
        Long context = positiveLong(request.contextTokens());
        Integer output = positiveInt(request.maxOutputTokens());
        String child = trimToEmpty(request.childModel());

        String id = "prof_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        StoredProfile created = new StoredProfile(id, name, endpoint, model, apiKey, context, output, child);
        profiles.add(created);
        if (Boolean.TRUE.equals(request.activate())) {
            models.applySnapshot(toSnapshot(created));
            activeProfileId = id;
        }
        persist();
        return list();
    }

    public synchronized ProfilesView update(String id, SaveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        int index = findIndex(id);
        if (index < 0) {
            throw new NotFoundException("未找到指定的模型预设: " + id);
        }
        StoredProfile existing = profiles.get(index);
        String name = blank(request.name()) ? existing.name() : requireValidName(request.name());
        ensureUniqueName(name, existing.id());
        String endpoint = blank(request.endpoint()) ? existing.endpoint() : requireHttpEndpoint(request.endpoint());
        String model = blank(request.model()) ? existing.model() : request.model().strip();
        String apiKey;
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            apiKey = "";
        } else if (!blank(request.apiKey())) {
            apiKey = request.apiKey().strip();
        } else {
            apiKey = existing.apiKey();
        }
        Long context = request.contextTokens() == null ? existing.contextTokens() : positiveLong(request.contextTokens());
        Integer output =
            request.maxOutputTokens() == null ? existing.maxOutputTokens() : positiveInt(request.maxOutputTokens());
        String child = request.childModel() == null ? existing.childModel() : request.childModel().strip();

        StoredProfile updated = new StoredProfile(existing.id(), name, endpoint, model, apiKey, context, output, child);
        profiles.set(index, updated);
        if (Boolean.TRUE.equals(request.activate())) {
            models.applySnapshot(toSnapshot(updated));
            activeProfileId = updated.id();
        }
        persist();
        return list();
    }

    public synchronized ProfilesView delete(String id) {
        int index = findIndex(id);
        if (index < 0) {
            throw new NotFoundException("未找到指定的模型预设: " + id);
        }
        profiles.remove(index);
        if (id.equals(activeProfileId)) {
            activeProfileId = "";
        }
        if (id.equals(fallbackProfileId)) {
            fallbackProfileId = "";
        }
        persist();
        return list();
    }

    public synchronized ProfilesView activate(String id) {
        int index = findIndex(id);
        if (index < 0) {
            throw new NotFoundException("未找到指定的模型预设: " + id);
        }
        StoredProfile target = profiles.get(index);
        models.applySnapshot(toSnapshot(target));
        activeProfileId = target.id();
        persist();
        return list();
    }

    /** 指定或清除主模型失败时自动切换的备用预设；传空或空白表示取消自动故障转移。 */
    public synchronized ProfilesView setFallback(String id) {
        if (blank(id)) {
            fallbackProfileId = "";
            persist();
            return list();
        }
        int index = findIndex(id);
        if (index < 0) {
            throw new NotFoundException("未找到指定的模型预设: " + id);
        }
        fallbackProfileId = profiles.get(index).id();
        persist();
        return list();
    }

    /** 解析当前配置对应的自动故障转移目标；未配置、已删除或与当前模型/端点一致时返回空。 */
    public synchronized Optional<FallbackTarget> fallbackTarget(ModelSettings.Snapshot current) {
        if (blank(fallbackProfileId)) {
            return Optional.empty();
        }
        int index = findIndex(fallbackProfileId);
        if (index < 0) {
            return Optional.empty();
        }
        StoredProfile target = profiles.get(index);
        if (current != null && target.endpoint().equals(trimToEmpty(current.endpoint()))
            && target.model().equals(trimToEmpty(current.model()))) {
            return Optional.empty();
        }
        return Optional.of(new FallbackTarget(target.id(), target.name(), toSnapshot(target)));
    }

    private String resolveCreateKey(SaveRequest request) {
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            return "";
        }
        if (!blank(request.apiKey())) {
            return request.apiKey().strip();
        }
        if (Boolean.TRUE.equals(request.inheritCurrentKey())) {
            String currentKey = models.snapshot().apiKey();
            return currentKey == null ? "" : currentKey;
        }
        return "";
    }

    private String resolveEffectiveActive(ModelSettings.Snapshot current) {
        if (blank(activeProfileId)) {
            return "";
        }
        for (StoredProfile profile : profiles) {
            if (profile.id().equals(activeProfileId)) {
                boolean matches = profile.endpoint().equals(trimToEmpty(current.endpoint()))
                    && profile.model().equals(trimToEmpty(current.model()));
                return matches ? profile.id() : "";
            }
        }
        return "";
    }

    private String resolveEffectiveFallback() {
        return findIndex(fallbackProfileId) >= 0 ? fallbackProfileId : "";
    }

    private int findIndex(String id) {
        if (blank(id)) {
            return -1;
        }
        String needle = id.strip();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id().equals(needle)) {
                return i;
            }
        }
        return -1;
    }

    private void ensureUniqueName(String name, String ignoreId) {
        for (StoredProfile profile : profiles) {
            if (profile.id().equals(ignoreId)) {
                continue;
            }
            if (profile.name().equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("预设名称已存在：" + name);
            }
        }
    }

    private void restore() {
        activeProfileId = store.find(KEY_ACTIVE).orElse("").strip();
        fallbackProfileId = store.find(KEY_FALLBACK).orElse("").strip();
        String raw = store.find(KEY_PROFILES).orElse("");
        if (raw.isBlank()) {
            return;
        }
        try {
            List<StoredProfile> loaded = mapper.readValue(raw, new TypeReference<List<StoredProfile>>() {});
            if (loaded != null) {
                for (StoredProfile item : loaded) {
                    if (item != null && !blank(item.id()) && !blank(item.name()) && !blank(item.endpoint())
                        && !blank(item.model())) {
                        profiles.add(new StoredProfile(item.id().strip(), item.name().strip(), item.endpoint().strip(),
                            item.model().strip(), item.apiKey() == null ? "" : item.apiKey(),
                            positiveLong(item.contextTokens()), positiveInt(item.maxOutputTokens()),
                            trimToEmpty(item.childModel())));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("读取模型预设失败，已忽略损坏数据");
        }
    }

    private void persist() {
        try {
            store.save(KEY_PROFILES, mapper.writeValueAsString(profiles));
            store.save(KEY_ACTIVE, activeProfileId == null ? "" : activeProfileId);
            store.save(KEY_FALLBACK, fallbackProfileId == null ? "" : fallbackProfileId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("保存模型预设失败", e);
        }
    }

    private static ModelSettings.Snapshot toSnapshot(StoredProfile profile) {
        return new ModelSettings.Snapshot(profile.endpoint(), profile.model(), profile.apiKey(),
            profile.contextTokens(), profile.maxOutputTokens(), profile.childModel());
    }

    private static ProfileView toView(StoredProfile profile, boolean active, boolean fallback) {
        ModelSettings.Snapshot snap = toSnapshot(profile);
        return new ProfileView(profile.id(), profile.name(), profile.endpoint(), profile.model(), snap.hasApiKey(),
            snap.maskedApiKey(), profile.contextTokens(), profile.maxOutputTokens(), profile.childModel(), active,
            fallback);
    }

    private static String requireValidName(String name) {
        String trimmed = requireNonBlank(name, "预设名称不能为空");
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("预设名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static String requireNonBlank(String value, String message) {
        if (blank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static String requireHttpEndpoint(String endpoint) {
        String trimmed = requireNonBlank(endpoint, "模型端点不能为空");
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("模型端点不是合法 URL：" + trimmed);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("模型端点必须是 http/https 地址：" + trimmed);
        }
        return trimmed;
    }

    private static Long positiveLong(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private static Integer positiveInt(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public record FallbackTarget(String id, String name, ModelSettings.Snapshot snapshot) {
    }

    public record SaveRequest(String name, String endpoint, String model, String apiKey, Boolean clearApiKey,
        Boolean inheritCurrentKey, Long contextTokens, Integer maxOutputTokens, String childModel, Boolean activate) {
    }

    public record ProfileView(String id, String name, String endpoint, String model, boolean apiKeySet,
        String apiKeyMasked, Long contextTokens, Integer maxOutputTokens, String childModel, boolean active,
        boolean fallback) {
        public ProfileView(String id, String name, String endpoint, String model, boolean apiKeySet,
            String apiKeyMasked, Long contextTokens, Integer maxOutputTokens, String childModel, boolean active) {
            this(id, name, endpoint, model, apiKeySet, apiKeyMasked, contextTokens, maxOutputTokens, childModel, active,
                false);
        }
    }

    public record ProfilesView(String activeProfileId, String fallbackProfileId, List<ProfileView> profiles) {
        public ProfilesView(String activeProfileId, List<ProfileView> profiles) {
            this(activeProfileId, "", profiles);
        }
    }

    private record StoredProfile(String id, String name, String endpoint, String model, String apiKey,
        Long contextTokens, Integer maxOutputTokens, String childModel) {
        @Override
        public String toString() {
            return "StoredProfile[id=" + id + ", name=" + name + ", endpoint=" + endpoint + ", model=" + model
                + ", apiKey=" + (apiKey != null && !apiKey.isBlank() ? "***" : "") + "]";
        }
    }
}
