package com.pilot.core.loop;

import com.pilot.core.model.ModelClientException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRetryPolicyTest {

    @Test
    void backsOffExponentiallyAndCapsAtThirtySeconds() {
        assertEquals(2_000L, ModelRetryPolicy.delayMs(1));
        assertEquals(4_000L, ModelRetryPolicy.delayMs(2));
        assertEquals(8_000L, ModelRetryPolicy.delayMs(3));
        assertEquals(16_000L, ModelRetryPolicy.delayMs(4));
        // 封顶：再往后不再翻倍
        assertEquals(30_000L, ModelRetryPolicy.delayMs(5));
        assertEquals(30_000L, ModelRetryPolicy.delayMs(20));
        // 非法入参按第一次处理，不能算出 0 或负数
        assertEquals(2_000L, ModelRetryPolicy.delayMs(0));
    }

    @Test
    void retriesRateLimitsTimeoutsAndServerErrors() {
        assertTrue(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 429 Too Many Requests")));
        assertTrue(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 500 boom")));
        assertTrue(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 503 unavailable")));
        assertTrue(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 408 timeout")));
        assertTrue(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 502")));
    }

    @Test
    void doesNotRetryClientErrors() {
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 400 bad request")));
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 401 unauthorized")));
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 403 forbidden")));
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 404 not found")));
    }

    @Test
    void neverRetriesUserCancellation() {
        // 取消是线程中断引起的，重试会让「停止」再等一轮
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException("模型请求被中断")));
    }

    @Test
    void retriesUnknownFailuresThatWrapIoErrors() {
        assertTrue(ModelRetryPolicy.isTransient(new UncheckedIOException(new IOException("connection reset"))));
        assertTrue(ModelRetryPolicy.isTransient(new RuntimeException("包装过的错误",
                new UncheckedIOException(new IOException("socket closed")))));
    }

    @Test
    void treatsExplicitModelRequestFailureAsTransient() {
        assertTrue(ModelRetryPolicy.isTransient(new ModelClientException("模型请求失败：连接超时")));
    }

    @Test
    void givesUpOnPlainRuntimeErrors() {
        assertFalse(ModelRetryPolicy.isTransient(new IllegalStateException("解析响应失败")));
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException()));
    }
}
