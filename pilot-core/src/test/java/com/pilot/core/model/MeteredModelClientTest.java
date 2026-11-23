package com.pilot.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;

class MeteredModelClientTest {

    /** 400 缓存参数拒绝必须能穿透记账包装：由底层客户端决定去掉优化参数后自动重试一次。 */
    @Test
    void cacheRejectionDowngradeRetriesThroughTheMeteringWrapper() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger downgrades = new AtomicInteger();
        ModelClient delegate = new ModelClient() {
            @Override
            public Set<NativeCapability> nativeCapabilities() {
                return Set.of(NativeCapability.WEB_SEARCH);
            }

            @Override
            public boolean disableRejectedCache(Throwable failure) {
                downgrades.incrementAndGet();
                return true;
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                if (attempts.incrementAndGet() == 1) {
                    throw ModelClientException.httpError(ModelFailureKind.PERMANENT, 400, -1,
                        "HTTP 400：不支持的 prompt_cache_key");
                }
                return new ModelResponse("重试成功", List.of());
            }
        };
        AtomicInteger reports = new AtomicInteger();
        ModelClient metered = new MeteredModelClient(delegate, "session", "main", List.of(), report -> {
            reports.incrementAndGet();
        });

        assertEquals("重试成功", metered.chat(List.of(Message.user("hi")), List.of()).content());
        assertEquals(2, attempts.get(), "缓存参数被拒绝后应去掉优化参数重试一次");
        assertEquals(1, downgrades.get());
        assertEquals(2, reports.get(), "两次请求都要计入埋点");
    }

    /** 记账包装只负责统计，原生能力与两类降级必须透传到底层客户端。 */
    @Test
    void observeUsageDelegatesNativeCapabilitiesAndRejections() {
        AtomicInteger nativeDowngrades = new AtomicInteger();
        AtomicInteger cacheDowngrades = new AtomicInteger();
        ModelClient delegate = new ModelClient() {
            @Override
            public Set<NativeCapability> nativeCapabilities() {
                return Set.of(NativeCapability.WEB_SEARCH);
            }

            @Override
            public boolean disableRejectedNativeTools(Throwable failure) {
                nativeDowngrades.incrementAndGet();
                return true;
            }

            @Override
            public boolean disableRejectedCache(Throwable failure) {
                cacheDowngrades.incrementAndGet();
                return true;
            }

            @Override
            public ModelResponse chat(List<Message> messages, List<ToolDefinition> tools) {
                return new ModelResponse("ok", List.of());
            }
        };
        ModelClient metered = MeteredModelClient.observeUsage(delegate, () -> {
        }, (elapsedMillis, usage) -> {
        });

        assertEquals(Set.of(NativeCapability.WEB_SEARCH), metered.nativeCapabilities());
        assertTrue(metered.disableRejectedNativeTools(new IllegalStateException("原生工具被拒绝")));
        assertTrue(metered.disableRejectedCache(new IllegalStateException("HTTP 400 缓存参数被拒绝")));
        assertEquals(1, nativeDowngrades.get());
        assertEquals(1, cacheDowngrades.get());
    }
}
