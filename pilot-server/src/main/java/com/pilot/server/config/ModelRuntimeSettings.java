package com.pilot.server.config;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelPricing;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.NativeCapability;
import com.pilot.server.store.SettingsStore;

import tools.jackson.databind.ObjectMapper;

/** 缓存与报价按端点和模型存储；切换网关不会沿用另一家的价格或专有参数。 */
@Component
public final class ModelRuntimeSettings {
    public record Options(String provider, String cacheMode, String cacheTtl, Boolean instructionMessages,
        Boolean streamUsage, String compactionMode, String webSearchMode, ModelPricing pricing) {
        public Options(String provider, String cacheMode, String cacheTtl, Boolean instructionMessages,
            Boolean streamUsage, String compactionMode, ModelPricing pricing) {
            this(provider, cacheMode, cacheTtl, instructionMessages, streamUsage, compactionMode, "auto", pricing);
        }

        public static Options defaults() {
            return new Options("auto", "auto", "", null, null, "short", "auto", ModelPricing.unknown());
        }
    }

    public record View(String model, String endpoint, Options options, ModelProfile effective,
        Set<String> nativeCapabilities, String webSearchSource, ModelCapabilityDiscovery.Report capabilityDiscovery) {
    }

    private final SettingsStore store;
    private final ModelSettings models;
    private final ObjectMapper mapper;
    private ModelCapabilityDiscovery discovery;

    @org.springframework.beans.factory.annotation.Autowired
    public void setCapabilityDiscovery(ModelCapabilityDiscovery discovery) {
        this.discovery = discovery;
        models.onChange(ignored -> discover());
    }

    @jakarta.annotation.PostConstruct
    public void discover() {
        if (discovery != null && models.snapshot().hasApiKey()) {
            capabilityReport(models.snapshot());
        }
    }

    public ModelCapabilityDiscovery.Report capabilityReport(ModelSettings.Snapshot model) {
        if (discovery == null || "local".equals(options(model).webSearchMode())) {
            return null;
        }
        return discovery.inspect(model, discoveryHint(model));
    }

    private String discoveryHint(ModelSettings.Snapshot model) {
        String mode = options(model).webSearchMode();
        if (mode != null && !"auto".equals(mode)) {
            return mode;
        }
        String provider = profile(model).provider();
        if ("anthropic".equals(provider) || URI.create(model.endpoint()).getPath().endsWith("/messages")) {
            return "anthropic-messages";
        }
        return "auto";
    }

    public boolean rejectNative(ModelSettings.Snapshot model, String protocol, boolean protocolRejected) {
        if (discovery == null || "local".equals(options(model).webSearchMode())) {
            return false;
        }
        discovery.reject(model, discoveryHint(model), protocol, protocolRejected);
        return true;
    }

    public ModelRuntimeSettings(SettingsStore store, ModelSettings models, ObjectMapper mapper) {
        this.store = store;
        this.models = models;
        this.mapper = mapper;
    }

    private static String key(ModelSettings.Snapshot model) {
        return "model.runtime." + MeteredModelClient.hash(model.endpoint().strip() + "\n" + model.model());
    }

    public Options options(ModelSettings.Snapshot model) {
        return store.find(key(model)).map(json -> mapper.readValue(json, Options.class)).orElseGet(Options::defaults);
    }

    public String revision() {
        return store.find(key(models.snapshot())).orElse("");
    }

    public ModelProfile profile(ModelSettings.Snapshot model) {
        return profile(model, options(model));
    }

    /**
     * 生产模式按实际端点后台验证；未知状态保留本地工具，调用不等待探测。
     * 未接入发现服务的嵌入式调用保持静态兼容逻辑。
     */
    public Set<NativeCapability> nativeCapabilities(ModelSettings.Snapshot model) {
        ModelCapabilityDiscovery.Report report = capabilityReport(model);
        if (report != null) {
            return report.capabilities();
        }
        Options options = options(model);
        String mode = options.webSearchMode() == null ? "auto" : options.webSearchMode();
        if ("local".equals(mode)) {
            return Set.of();
        }
        if ("openai-responses".equals(mode)) {
            return Set.of(NativeCapability.WEB_SEARCH);
        }
        if ("anthropic-messages".equals(mode)) {
            return Set.of(NativeCapability.WEB_SEARCH);
        }
        URI endpoint = URI.create(model.endpoint());
        String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        if (!"auto".equals(mode)) {
            return Set.of();
        }
        if ("api.openai.com".equals(host) && profile(model).provider().equals("openai") && model.model() != null
            && model.model().matches("(?:gpt-(?:4o(?:-.*)?|4\\.1(?:-.*)?|5(?:[.-].*)?)|o[134].*)")) {
            return Set.of(NativeCapability.WEB_SEARCH);
        }
        if ("api.anthropic.com".equals(host) && profile(model).provider().equals("anthropic")
            && supportsAnthropicWebSearch(model.model())) {
            return Set.of(NativeCapability.WEB_SEARCH);
        }
        return Set.of();
    }

    public boolean anthropicMessages(ModelSettings.Snapshot model) {
        ModelCapabilityDiscovery.Report report = capabilityReport(model);
        if (report != null && !report.protocol().isBlank()) {
            return "anthropic-messages".equals(report.protocol());
        }
        String mode = options(model).webSearchMode();
        // 手动选定的协议优先于端点探测，否则官方 Anthropic 主机名会覆盖用户选择的 Responses 协议
        if ("openai-responses".equals(mode)) {
            return false;
        }
        if ("anthropic-messages".equals(mode)) {
            return true;
        }
        String host = URI.create(model.endpoint()).getHost();
        return "anthropic".equals(profile(model).provider()) || "api.anthropic.com".equalsIgnoreCase(host);
    }

    private static boolean supportsAnthropicWebSearch(String model) {
        return model != null && model.matches("claude-(?:opus-(?:5-5|5|4-[6-8])|sonnet-(?:5-5|5|4-6))(?:-.*)?");
    }

    public boolean nativeWebSearch(ModelSettings.Snapshot model) {
        return nativeCapabilities(model).contains(NativeCapability.WEB_SEARCH);
    }

    private ModelProfile profile(ModelSettings.Snapshot model, Options options) {
        String searchMode = options.webSearchMode() == null ? "auto" : options.webSearchMode();
        if (!Set.of("auto", "local", "openai-responses", "anthropic-messages").contains(searchMode)) {
            throw new IllegalArgumentException("不支持的原生网页搜索策略");
        }
        ModelProfile resolved = ModelProfile.resolve(URI.create(model.endpoint()), model.model(), options.provider(),
            options.cacheMode(), options.cacheTtl(), options.instructionMessages(), options.streamUsage(),
            options.compactionMode(), options.pricing());
        String host = URI.create(model.endpoint()).getHost();
        boolean anthropicHost = "api.anthropic.com".equalsIgnoreCase(host);
        boolean openaiHost = "api.openai.com".equalsIgnoreCase(host);
        if ("openai-responses".equals(searchMode) && ("anthropic".equals(resolved.provider()) || anthropicHost)) {
            throw new IllegalArgumentException("Anthropic 端点不能使用 OpenAI Responses 协议");
        }
        if ("anthropic-messages".equals(searchMode) && ("openai".equals(resolved.provider()) || openaiHost)) {
            throw new IllegalArgumentException("OpenAI 端点不能使用 Anthropic Messages 协议");
        }
        return resolved;
    }

    public View current() {
        ModelSettings.Snapshot model = models.snapshot();
        Options options = options(model);
        ModelCapabilityDiscovery.Report report = capabilityReport(model);
        Set<NativeCapability> capabilities = report == null ? nativeCapabilities(model) : report.capabilities();
        Set<String> names = capabilities.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
        String mode = options.webSearchMode() == null ? "auto" : options.webSearchMode();
        String source = switch (mode) {
            case "local" -> "已手动指定 Pilot 本地搜索";
            case "openai-responses" -> "已手动选择 OpenAI Responses 协议";
            case "anthropic-messages" -> "已手动选择 Anthropic Messages 协议";
            default ->
                capabilities.contains(NativeCapability.WEB_SEARCH) ? "按官方端点与模型能力目录自动识别" : "原生能力未知或未确认，回退 Pilot 本地搜索";
        };
        if (report != null) {
            source = report.message();
        }
        return new View(model.model(), model.endpoint(), options, profile(model, options), names, source, report);
    }

    public synchronized View update(String expectedModel, String expectedEndpoint, Options options) {
        if (options == null) {
            throw new IllegalArgumentException("缓存与计价配置不能为空");
        }
        ModelSettings.Snapshot model = models.snapshot();
        if (!model.model().equals(expectedModel) || !model.endpoint().equals(expectedEndpoint)) {
            throw new IllegalArgumentException("模型配置已变化，请刷新后再保存计价与缓存设置");
        }
        profile(model, options);
        store.save(key(model), mapper.writeValueAsString(options));
        return current();
    }
}
