package com.pilot.core.loop;

import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelFailureKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRetryPolicyTest {

    @Test
    void waitsAboutThreeSecondsBetweenAttempts() {
        // 固定节奏：不瞬间刷请求，也不会越等越久
        assertEquals(3_000L, ModelRetryPolicy.delayMs(1));
        assertEquals(3_000L, ModelRetryPolicy.delayMs(2));
        assertEquals(3_000L, ModelRetryPolicy.delayMs(5));
        assertEquals(3_000L, ModelRetryPolicy.delayMs(20));
        assertEquals(3_000L, ModelRetryPolicy.delayMs(0));
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

    @Test
    void classifiesStructuredKindsWithoutReadingMessageText() {
        assertEquals(ModelFailureKind.NETWORK,
                ModelRetryPolicy.classify(new ModelClientException(ModelFailureKind.NETWORK, "任意文本")));
        assertEquals(ModelFailureKind.TRANSIENT,
                ModelRetryPolicy.classify(new ModelClientException(ModelFailureKind.TRANSIENT, "任意文本")));
        assertEquals(ModelFailureKind.PERMANENT,
                ModelRetryPolicy.classify(new ModelClientException(ModelFailureKind.PERMANENT, "HTTP 429")));
        assertEquals(ModelFailureKind.INTERRUPTED,
                ModelRetryPolicy.classify(new ModelClientException(ModelFailureKind.INTERRUPTED, "任意文本")));
        // 被包装一层也要认出结构化分类
        assertEquals(ModelFailureKind.PERMANENT,
                ModelRetryPolicy.classify(new RuntimeException("外层包装",
                        new ModelClientException(ModelFailureKind.PERMANENT, "模型不支持该参数"))));
    }

    @Test
    void classifiesNetworkFailuresFromIoChain() {
        // 没有状态码的连接问题（断网、连接重置）归网络类，可以一直重连
        assertEquals(ModelFailureKind.NETWORK,
                ModelRetryPolicy.classify(new ModelClientException("流式模型请求失败（10 ms）：connection reset",
                        new IOException("connection reset"))));
        assertEquals(ModelFailureKind.NETWORK,
                ModelRetryPolicy.classify(new UncheckedIOException(new IOException("socket closed"))));
    }

    @Test
    void doesNotRetryWhenThereIsNoMoneyOrQuota() {
        // 余额/额度问题重试多少次都一样
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException(
                "模型接口返回 HTTP 429：{\"error\":{\"code\":\"insufficient_quota\"}}")));
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 402 Payment Required")));
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException(
                "HTTP 400：Your credit balance is too low to access the API")));
        assertFalse(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 429：账户余额不足")));
        // 普通限流仍然可重试
        assertTrue(ModelRetryPolicy.isTransient(new RuntimeException("HTTP 429 Too Many Requests")));
    }
}
