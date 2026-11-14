package com.pilot.core.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import com.pilot.core.http.BoundedHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容端点的模型清单：{@code GET {base}/models}。
 *
 * <p>供设置界面「获取可用模型」使用：只读探测，不改任何配置、不落盘。
 * 端点写法与 {@link OpenAiCompatibleModelClient#resolveEndpoint(URI)} 同一套约定——
 * 服务根地址补 {@code /v1/models}、{@code /v1} 结尾补 {@code /models}、
 * 完整的 {@code /chat/completions} 地址把尾部替换成 {@code /models}。
 *
 * <p>API Key 留空时不带 Authorization 头：Ollama / LM Studio 这类本机端点
 * 允许匿名访问，强制带空密钥反而会被部分网关判 401。
 */
public final class ModelCatalog {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private ModelCatalog() {
    }

    /**
     * 端点写法兼容，规则与 chat completions 客户端保持一致：
     * 服务根地址补 {@code /v1/models}、任意 {@code /v1} 结尾（含 {@code /api/llm/v1}
     * 这类带前缀的）补 {@code /models}、完整的 {@code /chat/completions} 地址尾部替换。
     *
     * <p>其它自定义路径原样追加 {@code /models}：猜错了会在响应里
     * fail-visible 地报出来，用户可以改端点写法再试。
     */
    static URI resolveModelsUri(URI endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("model endpoint must not be null");
        }
        String path = endpoint.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return appendPath(endpoint, "/v1/models");
        }
        if (path.endsWith("/v1") || path.endsWith("/v1/")) {
            return appendPath(endpoint, "/models");
        }
        if (path.endsWith("/chat/completions")) {
            return replaceSuffix(endpoint, "/chat/completions", "/models");
        }
        return appendPath(endpoint, "/models");
    }

    /**
     * 拉取端点上的可用模型清单。
     *
     * @param endpoint 用户配置的端点（任意合法写法，见 {@link #resolveModelsUri(URI)}）
     * @param apiKey   可为空；空白时不带 Authorization 头
     * @return 去重（忽略大小写）并按字母序排序的模型 id 列表；端点没返回任何模型时为空列表
     * @throws ModelClientException 网络失败、非 2xx 响应或响应结构不合法
     */
    public static List<String> fetch(HttpClient httpClient, ObjectMapper objectMapper, URI endpoint, String apiKey) {
        URI modelsUri = resolveModelsUri(endpoint);
        HttpRequest.Builder request =
            HttpRequest.newBuilder(modelsUri).timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET();
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey.strip());
        }

        BoundedHttp.TextResponse response;
        try {
            response = BoundedHttp.text(httpClient, request.build(), REQUEST_TIMEOUT, 4 * 1024 * 1024);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelClientException("模型列表请求被中断", e);
        } catch (IOException e) {
            throw new ModelClientException("模型列表请求失败（" + modelsUri + "）：" + e.getMessage(), e);
        }

        String body = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelClientException(
                "模型列表接口返回 HTTP " + response.statusCode() + "（" + modelsUri + "）：" + ModelHttp.snippet(body));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new ModelClientException("模型列表响应不是合法 JSON（" + modelsUri + "）：" + ModelHttp.snippet(body), e);
        }

        // OpenAI 标准是 data[].id；个别网关返回 models[].id，兜一下
        JsonNode items = root.path("data");
        if (!items.isArray()) {
            items = root.path("models");
        }
        if (!items.isArray()) {
            throw new ModelClientException("模型列表响应缺少 data 数组（" + modelsUri + "）：" + ModelHttp.snippet(body));
        }

        SortedSet<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode item : items) {
            JsonNode id = item.get("id");
            if (id != null && id.isTextual() && !id.asString().isBlank()) {
                ids.add(id.asString());
            }
        }
        return List.copyOf(ids);
    }

    static URI appendPath(URI endpoint, String suffix) {
        String raw = endpoint.toString();
        int queryIndex = raw.indexOf('?');
        String base = queryIndex >= 0 ? raw.substring(0, queryIndex) : raw;
        String query = queryIndex >= 0 ? raw.substring(queryIndex) : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + suffix + query);
    }

    private static URI replaceSuffix(URI endpoint, String suffix, String replacement) {
        String raw = endpoint.toString();
        int queryIndex = raw.indexOf('?');
        String base = queryIndex >= 0 ? raw.substring(0, queryIndex) : raw;
        String query = queryIndex >= 0 ? raw.substring(queryIndex) : "";
        if (!base.endsWith(suffix)) {
            return appendPath(endpoint, replacement);
        }
        return URI.create(base.substring(0, base.length() - suffix.length()) + replacement + query);
    }
}
