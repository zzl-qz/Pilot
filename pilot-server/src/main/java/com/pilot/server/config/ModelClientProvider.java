package com.pilot.server.config;

import com.pilot.core.loop.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.ToolDefinition;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 模型客户端代理：每次调用前比对当前配置，变了就用新配置重建底层客户端。
 *
 * <p>这样改模型 / 端点 / API Key 不需要重启服务；配置没变时直接复用已有实例，
 * 不会每次请求都新建 HTTP 客户端。
 */
public final class ModelClientProvider implements ModelClient {
    private final ModelSettings settings;
    private final Function<ModelSettings.Snapshot, ModelClient> factory;

    private volatile ModelClient delegate;
    private volatile ModelSettings.Snapshot applied;

    public ModelClientProvider(ModelSettings settings, Function<ModelSettings.Snapshot, ModelClient> factory) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
    }

    @Override
    public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return client().chat(messages, tools);
    }

    /** 当前配置快照，供健康检查与设置接口展示。 */
    public ModelSettings.Snapshot describe() {
        return settings.snapshot();
    }

    private ModelClient client() {
        ModelSettings.Snapshot current = settings.snapshot();
        ModelClient local = delegate;
        if (local != null && current.equals(applied)) {
            return local;
        }
        synchronized (this) {
            ModelSettings.Snapshot now = settings.snapshot();
            if (delegate == null || !now.equals(applied)) {
                delegate = factory.apply(now);
                applied = now;
            }
            return delegate;
        }
    }
}
