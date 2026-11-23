package com.pilot.core.model;

import java.util.Set;

/**
 * 只转发能力查询与降级、不改变记账或预算边界的包装基类。
 *
 * <p>子类只需要覆盖真正要拦截的 {@code chat}：能力与降级必须穿透到底层客户端，
 * 否则原生工具拒绝与缓存参数拒绝的自动恢复会被包装层挡掉；{@link #snapshot()} 保持接口默认，
 * 仍返回包装自身，避免记账或预算拦截被绕过。
 */
public abstract class DelegatingModelClient implements ModelClient {
    private final ModelClient delegate;

    protected DelegatingModelClient(ModelClient delegate) {
        this.delegate = delegate;
    }

    /** 被包装的底层客户端。 */
    protected final ModelClient delegate() {
        return delegate;
    }

    @Override
    public ModelProfile profile() {
        return delegate.profile();
    }

    @Override
    public Set<NativeCapability> nativeCapabilities() {
        return delegate.nativeCapabilities();
    }

    @Override
    public boolean disableRejectedCache(Throwable failure) {
        return delegate.disableRejectedCache(failure);
    }

    @Override
    public boolean disableRejectedNativeTools(Throwable failure) {
        return delegate.disableRejectedNativeTools(failure);
    }
}
