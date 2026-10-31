package com.pilot.core.loop;

import com.pilot.core.model.ModelClientException;
import com.pilot.core.model.ModelFailureKind;
import com.pilot.core.model.ModelFailureSignals;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型端点兼容性契约测试。
 *
 * <p>样本是脱敏后的真实响应形状：不同兼容端点对同一类错误会给出不同结构
 * （有的只有 message，有的有 type/code，有的直接返回 HTML 网关错误页）。
 * 目标是保证分类稳定：永久错误不会被无限重试，暂时故障不会被当成致命错误。
 */
class ModelContractTest {

    private static String sample(String name) {
        try (InputStream input = ModelContractTest.class.getResourceAsStream("/model-contract/" + name)) {
            return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void invalidApiKeyIsPermanent() {
        String body = sample("auth-401.json");
        assertEquals(ModelFailureKind.PERMANENT, ModelFailureSignals.statusKind(401, body));
        assertFalse(ModelRetryPolicy.classify(new ModelClientException(
                "模型请求失败 (HTTP 401): " + body)).retryable());
    }

    @Test
    void exhaustedQuotaIsPermanentEvenOn429() {
        String body = sample("balance-402.json");
        assertEquals(ModelFailureKind.PERMANENT, ModelFailureSignals.statusKind(402, body));
        // 有些端点把余额问题也报成 429：文本信号必须能压过状态码
        assertEquals(ModelFailureKind.PERMANENT, ModelFailureSignals.statusKind(429, body));
        assertFalse(ModelRetryPolicy.classify(new ModelClientException(
                "模型请求失败 (HTTP 429): " + body)).retryable());
    }

    @Test
    void rateLimitIsTransient() {
        String body = sample("rate-limit-429.json");
        assertEquals(ModelFailureKind.TRANSIENT, ModelFailureSignals.statusKind(429, body));
        assertTrue(ModelRetryPolicy.classify(new ModelClientException(
                "模型请求失败 (HTTP 429): " + body)).retryable());
    }

    @Test
    void gatewayHtmlErrorIsTransient() {
        String body = sample("server-error-500.txt");
        assertEquals(ModelFailureKind.TRANSIENT, ModelFailureSignals.statusKind(502, body));
        assertTrue(ModelRetryPolicy.classify(new ModelClientException(
                "模型请求失败 (HTTP 502): " + body)).retryable());
    }

    @Test
    void streamInterruptionWithoutStatusIsTransient() {
        // 流式响应断开时没有状态码，只有一条不完整的 SSE；必须按网络故障继续重试
        String partial = sample("stream-interrupted.txt");
        assertTrue(partial.contains("data:"));
        assertTrue(ModelRetryPolicy.classify(
                new ModelClientException("流式模型请求失败：连接在完成前关闭", new IOException("reset")))
                .retryable());
    }

    @Test
    void structurallyInvalidResponseIsPermanent() {
        String body = sample("malformed-body.json");
        // 结构非法意味着重试同样的请求也不会变好：判成永久失败，交给上层提示换模型/改配置
        assertFalse(ModelRetryPolicy.classify(new ModelClientException(ModelFailureKind.PERMANENT,
                "模型响应结构异常：choices 为空，body=" + body)).retryable());
    }

    @Test
    void backoffIsBoundedAndMonotonic() {
        assertTrue(ModelRetryPolicy.delayMs(1) < ModelRetryPolicy.delayMs(3));
        assertEquals(ModelRetryPolicy.MAX_RETRY_DELAY_MS, ModelRetryPolicy.delayMs(30));
    }
}
