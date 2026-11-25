package com.pilot.server.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.pilot.core.loop.ModelRetryPolicy;
import com.pilot.core.message.Message;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelFailureKind;
import com.pilot.core.model.ModelFailureSignals;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.ModelResponse;
import com.pilot.core.model.NativeCapability;
import com.pilot.core.model.ToolDefinition;

import lombok.extern.slf4j.Slf4j;

/**
 * 模型客户端代理：每次调用前比对当前配置，变了就用新配置重建底层客户端。
 *
 * <p>这样改模型 / 端点 / API Key 不需要重启服务；配置没变时直接复用已有实例，
 * 不会每次请求都新建 HTTP 客户端。
 */
@Slf4j
public final class ModelClientProvider implements ModelClient {
    private final ModelSettings settings;
    private final Function<ModelSettings.Snapshot, ModelClient> factory;
    private final java.util.function.Supplier<String> revision;
    private volatile Function<ModelSettings.Snapshot, Optional<ModelProfileStore.FallbackTarget>> fallbackResolver =
        ignored -> Optional.empty();

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

    /** 缓存参数降级交给当前生效的客户端处理，与 {@link #snapshot()} 透传的语义一致。 */
    @Override
    public boolean disableRejectedCache(Throwable failure) {
        return snapshot().disableRejectedCache(failure);
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

    public ModelClientProvider
        withFallback(Function<ModelSettings.Snapshot, Optional<ModelProfileStore.FallbackTarget>> resolver) {
        this.fallbackResolver = resolver == null ? ignored -> Optional.empty() : resolver;
        return this;
    }

    /**
     * 为单次运行创建支持自动故障转移的客户端包装：正常时随主配置热更新，
     * 触发限流/额度耗尽/服务故障且配置了备用预设时，仅在本次运行内切换到备用预设，不影响其他会话或全局主配置。
     */
    public ModelClient forRun() {
        return new RunFailoverClient();
    }

    /** 用当前端点和密钥换一个模型名。名字为空时仍用主模型。 */
    public ModelClient clientFor(String model) {
        ModelSettings.Snapshot current = settings.snapshot();
        if (model == null || model.isBlank() || model.equals(current.model())) {
            return client();
        }
        return factory.apply(current.withModel(model.strip()));
    }

    static boolean isFailoverEligible(Throwable failure) {
        if (failure == null) {
            return false;
        }
        RuntimeException runtimeFailure = failure instanceof RuntimeException re ? re : new RuntimeException(failure);
        ModelFailureKind kind = ModelRetryPolicy.classify(runtimeFailure);
        if (kind == ModelFailureKind.INTERRUPTED || kind == ModelFailureKind.CONTEXT_OVERFLOW
            || kind == ModelFailureKind.MALFORMED_TOOL_CALL) {
            return false;
        }
        if (kind == ModelFailureKind.NETWORK || kind == ModelFailureKind.TRANSIENT
            || kind == ModelFailureKind.INCOMPLETE) {
            return true;
        }
        if (failure instanceof ModelClientException mce) {
            int status = mce.statusCode();
            if (status == 400 || status == 422) {
                return false;
            }
            if (status == 401 || status == 402 || status == 403 || status == 408 || status == 429 || status >= 500) {
                return true;
            }
        }
        return ModelFailureSignals.billingOrQuota(failure.getMessage());
    }

    private final class RunFailoverClient implements ModelClient {
        private volatile ModelClient backup;

        @Override
        public ModelClient snapshot() {
            ModelClient active = backup;
            return active != null ? active : ModelClientProvider.this.snapshot();
        }

        @Override
        public ModelProfile profile() {
            return snapshot().profile();
        }

        @Override
        public Set<NativeCapability> nativeCapabilities() {
            return snapshot().nativeCapabilities();
        }

        @Override
        public boolean disableRejectedCache(Throwable failure) {
            return snapshot().disableRejectedCache(failure);
        }

        @Override
        public boolean disableRejectedNativeTools(Throwable failure) {
            return snapshot().disableRejectedNativeTools(failure);
        }

        @Override
        public synchronized String failover(Throwable failure) {
            if (backup != null || !isFailoverEligible(failure)) {
                return "";
            }
            Optional<ModelProfileStore.FallbackTarget> resolved = fallbackResolver.apply(settings.snapshot());
            if (resolved.isEmpty()) {
                return "";
            }
            ModelProfileStore.FallbackTarget target = resolved.get();
            backup = factory.apply(target.snapshot());
            log.warn("主模型调用失败，本次运行自动故障转移至备用预设：name={} model={} endpoint={}", target.name(), target.snapshot().model(),
                target.snapshot().endpoint());
            return target.name() + "（" + target.snapshot().model() + "）";
        }

        @Override
        public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            return snapshot().chat(messages, tools);
        }

        @Override
        public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> text) {
            return snapshot().chat(messages, tools, text);
        }

        @Override
        public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> text,
            Consumer<String> reasoning) {
            return snapshot().chat(messages, tools, text, reasoning);
        }
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
