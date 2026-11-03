package com.pilot.server.config;

import com.pilot.core.model.MeteredModelClient;
import com.pilot.core.model.ModelPricing;
import com.pilot.core.model.ModelProfile;
import com.pilot.server.store.SettingsStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;

/** 缓存与报价按端点和模型存储；切换网关不会沿用另一家的价格或专有参数。 */
@Component
public final class ModelRuntimeSettings {
    public record Options(String provider, String cacheMode, String cacheTtl, Boolean instructionMessages,
                          Boolean streamUsage, String compactionMode, ModelPricing pricing) {
        public static Options defaults() { return new Options("auto", "auto", "", null, null, "short", ModelPricing.unknown()); }
    }
    public record View(String model, String endpoint, Options options, ModelProfile effective) { }
    private final SettingsStore store;
    private final ModelSettings models;
    private final ObjectMapper mapper;
    public ModelRuntimeSettings(SettingsStore store, ModelSettings models, ObjectMapper mapper) {
        this.store = store; this.models = models; this.mapper = mapper;
    }
    private static String key(ModelSettings.Snapshot model) {
        return "model.runtime." + MeteredModelClient.hash(model.endpoint().strip() + "\n" + model.model());
    }
    public Options options(ModelSettings.Snapshot model) {
        return store.find(key(model)).map(json -> mapper.readValue(json, Options.class)).orElseGet(Options::defaults);
    }
    public String revision() { return store.find(key(models.snapshot())).orElse(""); }
    public ModelProfile profile(ModelSettings.Snapshot model) {
        return profile(model, options(model));
    }
    private ModelProfile profile(ModelSettings.Snapshot model, Options options) {
        return ModelProfile.resolve(URI.create(model.endpoint()), model.model(), options.provider(), options.cacheMode(),
                options.cacheTtl(), options.instructionMessages(), options.streamUsage(), options.compactionMode(), options.pricing());
    }
    public View current() {
        ModelSettings.Snapshot model = models.snapshot();
        Options options = options(model);
        return new View(model.model(), model.endpoint(), options, profile(model, options));
    }
    public synchronized View update(String expectedModel, String expectedEndpoint, Options options) {
        if (options == null) throw new IllegalArgumentException("缓存与计价配置不能为空");
        ModelSettings.Snapshot model = models.snapshot();
        if (!model.model().equals(expectedModel) || !model.endpoint().equals(expectedEndpoint))
            throw new IllegalArgumentException("模型配置已变化，请刷新后再保存计价与缓存设置");
        profile(model, options);
        store.save(key(model), mapper.writeValueAsString(options));
        return current();
    }
}
