package com.pilot.core.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

import com.pilot.core.http.BoundedHttp;
import com.pilot.core.model.diagnostics.ModelRequestDumper;
import com.pilot.core.util.SecretScrubber;
import com.pilot.core.util.Texts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 模型 HTTP 请求构造、错误摘要和原生协议的发送规则。 */
final class ModelHttp {
    private ModelHttp() {
    }

    static URI nativeEndpoint(URI endpoint, String suffix, boolean preserveExplicitPath) {
        if (endpoint == null) {
            throw new IllegalArgumentException("model endpoint must not be null");
        }
        String raw = endpoint.toString();
        int queryIndex = raw.indexOf('?');
        String base = queryIndex >= 0 ? raw.substring(0, queryIndex) : raw;
        String query = queryIndex >= 0 ? raw.substring(queryIndex) : "";
        if (preserveExplicitPath && base.endsWith(suffix)) {
            return endpoint;
        }
        String chatPath = "/chat/completions";
        if (base.endsWith(chatPath)) {
            base = base.substring(0, base.length() - chatPath.length());
        } else if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!preserveExplicitPath && base.endsWith(suffix)) {
            return URI.create(base + query);
        }
        return URI.create(base + (base.endsWith("/v1") ? "" : "/v1") + suffix + query);
    }

    static HttpRequest.Builder jsonPost(URI endpoint, Duration timeout, ObjectMapper mapper, Map<String, Object> body) {
        Object ordered = CanonicalJson.ordered(body);
        ModelRequestDumper.recordIfEnabled(endpoint, mapper, ordered);
        return HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json").timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(ordered), StandardCharsets.UTF_8));
    }

    static JsonNode parseJson(ObjectMapper mapper, String body) {
        try {
            return mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "模型响应不是合法 JSON：" + snippet(body), e);
        }
    }

    static BoundedHttp.TextResponse sendNative(HttpClient httpClient, HttpRequest request, Duration idleTimeout) {
        BoundedHttp.TextResponse response;
        try {
            response = BoundedHttp.text(httpClient, request, idleTimeout, BoundedHttp.JSON_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelClientException(ModelFailureKind.INTERRUPTED, "模型请求被中断", e);
        } catch (IOException e) {
            ModelFailureKind kind = Thread.currentThread().isInterrupted() ? ModelFailureKind.INTERRUPTED
                : e instanceof BoundedHttp.ResponseLimitException ? ModelFailureKind.PERMANENT
                    : ModelFailureKind.NETWORK;
            throw new ModelClientException(kind, "模型请求失败：" + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = snippet(response.body());
            if (NativeToolRejectedException.rejected(response.statusCode(), detail)) {
                throw new NativeToolRejectedException("服务端未接受原生工具或协议",
                    response.statusCode() == 404 || response.statusCode() == 405);
            }
            throw ModelClientException.httpError(ModelFailureSignals.statusKind(response.statusCode(), detail),
                response.statusCode(), retryAfterMs(response.headers()),
                "模型接口返回 HTTP " + response.statusCode() + "：" + detail);
        }
        return response;
    }

    /** 服务端 Retry-After 提示上限（2 分钟），防止异常网关返回超大值卡死重试。 */
    static final long MAX_RETRY_AFTER_MS = 120_000L;

    /** 从响应头解析服务端退避提示（支持 retry-after-ms、Retry-After 秒数/HTTP 日期、x-ratelimit-reset-after）。 */
    static long retryAfterMs(HttpHeaders headers) {
        return retryAfterMs(headers, Instant.now());
    }

    static long retryAfterMs(HttpHeaders headers, Instant now) {
        if (headers == null) {
            return -1L;
        }
        Optional<String> millis = headers.firstValue("retry-after-ms");
        if (millis.isPresent()) {
            long parsed = parseNonNegativeMillis(millis.get(), 1.0);
            if (parsed >= 0) {
                return Math.min(MAX_RETRY_AFTER_MS, parsed);
            }
        }
        Optional<String> retryAfter = headers.firstValue("retry-after");
        if (retryAfter.isPresent()) {
            long parsed = parseRetryAfterValue(retryAfter.get(), now);
            if (parsed >= 0) {
                return Math.min(MAX_RETRY_AFTER_MS, parsed);
            }
        }
        Optional<String> resetAfter = headers.firstValue("x-ratelimit-reset-after");
        if (resetAfter.isPresent()) {
            long parsed = parseNonNegativeMillis(resetAfter.get(), 1_000.0);
            if (parsed >= 0) {
                return Math.min(MAX_RETRY_AFTER_MS, parsed);
            }
        }
        return -1L;
    }

    private static long parseRetryAfterValue(String raw, Instant now) {
        long seconds = parseNonNegativeMillis(raw, 1_000.0);
        if (seconds >= 0) {
            return seconds;
        }
        try {
            Instant target = ZonedDateTime.parse(raw.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0L, Duration.between(now == null ? Instant.now() : now, target).toMillis());
        } catch (DateTimeParseException ignored) {
            return -1L;
        }
    }

    private static long parseNonNegativeMillis(String raw, double multiplier) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        try {
            double value = Double.parseDouble(raw.strip());
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
                return -1L;
            }
            return (long)Math.ceil(value * multiplier);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    static String snippet(String body) {
        return Texts.oneLine(SecretScrubber.scrub(body), 300);
    }
}
