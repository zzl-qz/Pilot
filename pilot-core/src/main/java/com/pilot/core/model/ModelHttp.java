package com.pilot.core.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.pilot.core.http.BoundedHttp;
import com.pilot.core.tool.ToolSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 模型 HTTP 请求构造、错误摘要和原生协议的发送规则。 */
final class ModelHttp {
    private ModelHttp() {
    }

    static HttpRequest.Builder jsonPost(URI endpoint, Duration timeout, ObjectMapper mapper, Map<String, Object> body) {
        return HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json").timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(CanonicalJson.ordered(body)),
                StandardCharsets.UTF_8));
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
            throw new ModelClientException(ModelFailureSignals.statusKind(response.statusCode(), detail),
                "模型接口返回 HTTP " + response.statusCode() + "：" + detail);
        }
        return response;
    }

    static String snippet(String body) {
        return ToolSupport.oneLine(body, 300);
    }
}
