package com.pilot.server.model;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelPricing;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.NativeCapability;
import com.pilot.server.store.SettingsStore;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** 运行选项按端点和模型存储；切换网关不会沿用另一家的价格、专有参数或等待时限。 */
@Component
public final class ModelRuntimeSettings {
    private static final long DEFAULT_TIMEOUT_SECONDS = 900;
    private static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 600;

    public record Options(String provider, String cacheMode, String cacheTtl, Boolean instructionMessages,
        Boolean streamUsage, String compactionMode, String webSearchMode, ModelPricing pricing,
        @JsonDeserialize(using = IntegerSeconds.class) Long timeoutSeconds,
        @JsonDeserialize(using = IntegerSeconds.class) Long idleTimeoutSeconds) {
        public Options(String provider, String cacheMode, String cacheTtl, Boolean instructionMessages,
            Boolean streamUsage, String compactionMode, String webSearchMode, ModelPricing pricing) {
            this(provider, cacheMode, cacheTtl, instructionMessages, streamUsage, compactionMode, webSearchMode,
                pricing, null, null);
        }

        public Options(String provider, String cacheMode, String cacheTtl, Boolean instructionMessages,
            Boolean streamUsage, String compactionMode, ModelPricing pricing) {
            this(provider, cacheMode, cacheTtl, instructionMessages, streamUsage, compactionMode, ModelProtocols.AUTO,
                pricing);
        }

        public static Options defaults() {
            return new Options(ModelProfile.AUTO, ModelProfile.AUTO, "", null, null, ModelProfile.COMPACTION_SHORT,
                ModelProtocols.AUTO, ModelPricing.unknown());
        }
    }

    /** 只约束这两个新字段，避免 JSON 小数被静默截断而改变实际等待行为。 */
    public static final class IntegerSeconds extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return context.reportInputMismatch(Long.class, "请求时限必须为整数秒，留空请传 null");
            }
            return parser.getLongValue();
        }
    }

    public record RequestTimeouts(long totalSeconds, long idleSeconds) {
    }

    public record View(String model, String endpoint, Options options, ModelProfile effective,
        Set<String> nativeCapabilities, String webSearchSource, ModelCapabilityDiscovery.Report capabilityDiscovery,
        RequestTimeouts timeoutDefaults, RequestTimeouts timeouts) {
    }

    private final SettingsStore store;
    private final ModelSettings models;
    private final ObjectMapper mapper;
    private final RequestTimeouts timeoutDefaults;
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
        if (discovery == null || ModelProtocols.LOCAL.equals(options(model).webSearchMode())) {
            return null;
        }
        return discovery.inspect(model, discoveryHint(model));
    }

    private String discoveryHint(ModelSettings.Snapshot model) {
        String mode = options(model).webSearchMode();
        if (mode != null && !ModelProtocols.AUTO.equals(mode)) {
            return mode;
        }
        String provider = profile(model).provider();
        if (ModelProfile.PROVIDER_ANTHROPIC.equals(provider)
            || URI.create(model.endpoint()).getPath().endsWith("/messages")) {
            return ModelProtocols.ANTHROPIC_MESSAGES;
        }
        return ModelProtocols.AUTO;
    }

    public boolean rejectNative(ModelSettings.Snapshot model, String protocol, boolean protocolRejected) {
        if (discovery == null || ModelProtocols.LOCAL.equals(options(model).webSearchMode())) {
            return false;
        }
        discovery.reject(model, discoveryHint(model), protocol, protocolRejected);
        return true;
    }

    public ModelRuntimeSettings(SettingsStore store, ModelSettings models, ObjectMapper mapper) {
        this(store, models, mapper, DEFAULT_TIMEOUT_SECONDS, DEFAULT_IDLE_TIMEOUT_SECONDS);
    }

    @Autowired
    public ModelRuntimeSettings(SettingsStore store, ModelSettings models, ObjectMapper mapper,
        @Value("${pilot.model.timeout-seconds:" + DEFAULT_TIMEOUT_SECONDS + "}") long timeoutSeconds,
        @Value("${pilot.model.idle-timeout-seconds:" + DEFAULT_IDLE_TIMEOUT_SECONDS + "}") long idleTimeoutSeconds) {
        if (timeoutSeconds <= 0 || idleTimeoutSeconds < 0) {
            throw new IllegalArgumentException("模型总超时必须为正数，空闲超时不能为负数");
        }
        this.store = store;
        this.models = models;
        this.mapper = mapper;
        this.timeoutDefaults = new RequestTimeouts(timeoutSeconds, idleTimeoutSeconds);
    }

    public RequestTimeouts timeouts(ModelSettings.Snapshot model) {
        return timeouts(options(model));
    }

    private RequestTimeouts timeouts(Options options) {
        if (options.timeoutSeconds() != null && (options.timeoutSeconds() < 1 || options.timeoutSeconds() > 86400)) {
            throw new IllegalArgumentException("单次模型请求总时限必须为 1 至 86400 秒，留空使用服务端默认值");
        }
        if (options.idleTimeoutSeconds() != null
            && (options.idleTimeoutSeconds() < 0 || options.idleTimeoutSeconds() > 86400)) {
            throw new IllegalArgumentException("模型无数据等待时限必须为 0 至 86400 秒，0 表示跟随总时限");
        }
        long total = options.timeoutSeconds() == null ? timeoutDefaults.totalSeconds() : options.timeoutSeconds();
        long idle = options.idleTimeoutSeconds() == null ? timeoutDefaults.idleSeconds() : options.idleTimeoutSeconds();
        return new RequestTimeouts(total, idle == 0 ? total : Math.min(idle, total));
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
        String mode = options.webSearchMode() == null ? ModelProtocols.AUTO : options.webSearchMode();
        if (ModelProtocols.LOCAL.equals(mode)) {
            return Set.of();
        }
        if (ModelProtocols.OPENAI_RESPONSES.equals(mode)) {
            return Set.of(NativeCapability.WEB_SEARCH);
        }
        if (ModelProtocols.ANTHROPIC_MESSAGES.equals(mode)) {
            return Set.of(NativeCapability.WEB_SEARCH);
        }
        URI endpoint = URI.create(model.endpoint());
        String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        if (!ModelProtocols.AUTO.equals(mode)) {
            return Set.of();
        }
        if (ModelProfile.HOST_OPENAI.equals(host) && profile(model).provider().equals(ModelProfile.PROVIDER_OPENAI)
            && model.model() != null
            && model.model().matches("(?:gpt-(?:4o(?:-.*)?|4\\.1(?:-.*)?|5(?:[.-].*)?)|o[134].*)")) {
            return Set.of(NativeCapability.WEB_SEARCH);
        }
        if (ModelProfile.HOST_ANTHROPIC.equals(host)
            && profile(model).provider().equals(ModelProfile.PROVIDER_ANTHROPIC)
            && supportsAnthropicWebSearch(model.model())) {
            return Set.of(NativeCapability.WEB_SEARCH);
        }
        return Set.of();
    }

    public boolean anthropicMessages(ModelSettings.Snapshot model) {
        ModelCapabilityDiscovery.Report report = capabilityReport(model);
        if (report != null && !report.protocol().isBlank()) {
            return ModelProtocols.ANTHROPIC_MESSAGES.equals(report.protocol());
        }
        String mode = options(model).webSearchMode();
        // 手动选定的协议优先于端点探测，否则官方 Anthropic 主机名会覆盖用户选择的 Responses 协议
        if (ModelProtocols.OPENAI_RESPONSES.equals(mode)) {
            return false;
        }
        if (ModelProtocols.ANTHROPIC_MESSAGES.equals(mode)) {
            return true;
        }
        String host = URI.create(model.endpoint()).getHost();
        return ModelProfile.PROVIDER_ANTHROPIC.equals(profile(model).provider())
            || ModelProfile.HOST_ANTHROPIC.equalsIgnoreCase(host);
    }

    private static boolean supportsAnthropicWebSearch(String model) {
        return model != null && model.matches("claude-(?:opus-(?:5-5|5|4-[6-8])|sonnet-(?:5-5|5|4-6))(?:-.*)?");
    }

    public boolean nativeWebSearch(ModelSettings.Snapshot model) {
        return nativeCapabilities(model).contains(NativeCapability.WEB_SEARCH);
    }

    private ModelProfile profile(ModelSettings.Snapshot model, Options options) {
        String searchMode = options.webSearchMode() == null ? ModelProtocols.AUTO : options.webSearchMode();
        if (!Set.of(ModelProtocols.AUTO, ModelProtocols.LOCAL, ModelProtocols.OPENAI_RESPONSES,
            ModelProtocols.ANTHROPIC_MESSAGES).contains(searchMode)) {
            throw new IllegalArgumentException("不支持的原生网页搜索策略");
        }
        ModelProfile resolved = ModelProfile.resolve(URI.create(model.endpoint()), model.model(), options.provider(),
            options.cacheMode(), options.cacheTtl(), options.instructionMessages(), options.streamUsage(),
            options.compactionMode(), options.pricing());
        String host = URI.create(model.endpoint()).getHost();
        boolean anthropicHost = ModelProfile.HOST_ANTHROPIC.equalsIgnoreCase(host);
        boolean openaiHost = ModelProfile.HOST_OPENAI.equalsIgnoreCase(host);
        if (ModelProtocols.OPENAI_RESPONSES.equals(searchMode)
            && (ModelProfile.PROVIDER_ANTHROPIC.equals(resolved.provider()) || anthropicHost)) {
            throw new IllegalArgumentException("Anthropic 端点不能使用 OpenAI Responses 协议");
        }
        if (ModelProtocols.ANTHROPIC_MESSAGES.equals(searchMode)
            && (ModelProfile.PROVIDER_OPENAI.equals(resolved.provider()) || openaiHost)) {
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
        String mode = options.webSearchMode() == null ? ModelProtocols.AUTO : options.webSearchMode();
        String source = switch (mode) {
            case ModelProtocols.LOCAL -> "已手动指定 Pilot 本地搜索";
            case ModelProtocols.OPENAI_RESPONSES -> "已手动选择 OpenAI Responses 协议";
            case ModelProtocols.ANTHROPIC_MESSAGES -> "已手动选择 Anthropic Messages 协议";
            default ->
                capabilities.contains(NativeCapability.WEB_SEARCH) ? "按官方端点与模型能力目录自动识别" : "原生能力未知或未确认，回退 Pilot 本地搜索";
        };
        if (report != null) {
            source = report.message();
        }
        return new View(model.model(), model.endpoint(), options, profile(model, options), names, source, report,
            timeoutDefaults, timeouts(options));
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
        timeouts(options);
        store.save(key(model), mapper.writeValueAsString(options));
        return current();
    }
}
