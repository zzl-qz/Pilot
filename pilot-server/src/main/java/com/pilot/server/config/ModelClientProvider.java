package com.pilot.server.config;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ToolDefinition;

/**
 * 模型客户端代理：每次调用前比对当前配置，变了就用新配置重建底层客户端。
 *
 * <p>这样改模型 / 端点 / API Key 不需要重启服务；配置没变时直接复用已有实例，
 * 不会每次请求都新建 HTTP 客户端。
 */
public final class ModelClientProvider implements ModelClient {
    private final ModelSettings settings;
    private final Function<ModelSettings.Snapshot, ModelClient> factory;
    private final java.util.function.Supplier<String> revision;

    /** 配置与对应客户端一次性发布，避免并发读取时把新配置和旧客户端配在一起。 */
    private record AppliedClient(ModelSettings.Snapshot settings, String revision, ModelClient client) {
    }

    private volatile AppliedClient applied;

    public ModelClientProvider(ModelSettings settings, Function<ModelSettings.Snapshot, ModelClient> factory) {
        this(settings, factory, () -> "");
    }

    public ModelClientProvider(ModelSettings settings, Function<ModelSettings.Snapshot, ModelClient> factory,
        java.util.function.Supplier<String> revision) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
        this.revision = revision;
    }

    @Override
    public boolean disableRejectedNativeTools(Throwable failure) {
        return client().disableRejectedNativeTools(failure);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return client().chat(messages, tools);
    }

    @Override
    public ModelClient snapshot() {
        return client();
    }

    @Override
    public com.pilot.core.model.ModelProfile profile() {
        return client().profile();
    }

    @Override
    public java.util.Set<com.pilot.core.model.NativeCapability> nativeCapabilities() {
        return client().nativeCapabilities();
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools,
        java.util.function.Consumer<String> text) {
        return client().chat(messages, tools, text);
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools,
        java.util.function.Consumer<String> text, java.util.function.Consumer<String> reasoning) {
        return client().chat(messages, tools, text, reasoning);
    }

    /** 当前配置快照，供健康检查与设置接口展示。 */
    public ModelSettings.Snapshot describe() {
        return settings.snapshot();
    }

    private ModelClient client() {
        ModelSettings.Snapshot current = settings.snapshot();
        AppliedClient local = applied;
        if (local != null && current.equals(local.settings()) && Objects.equals(revision.get(), local.revision())) {
            return local.client();
        }
        synchronized (this) {
            ModelSettings.Snapshot now = settings.snapshot();
            String version = revision.get();
            local = applied;
            if (local == null || !now.equals(local.settings()) || !Objects.equals(version, local.revision())) {
                local = new AppliedClient(now, version, factory.apply(now));
                applied = local;
            }
            return local.client();
        }
    }
}
