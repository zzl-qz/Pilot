package com.pilot.server.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.pilot.core.model.ModelCatalog;

import tools.jackson.databind.ObjectMapper;

/**
 * 「获取可用模型」的代理执行：由服务端代发 {@code GET {base}/models}。
 *
 * <p>浏览器直连第三方网关会被 CORS 拦住，网关返回的报错格式也五花八门；
 * 走服务端可以复用 {@link ModelSettings} 的端点校验与「留空回退已保存值」语义，
 * 并把上游失败统一翻译成 {@link com.pilot.core.model.ModelClientException}。
 *
 * <p>密钥只在内存里过一遍：探测不落盘、不进日志、不改当前配置。
 */
@Component
public class ModelCatalogService {
    private final ModelSettings settings;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ModelCatalogService(ModelSettings settings, ObjectMapper objectMapper,
        @Qualifier("modelHttpClient") HttpClient httpClient) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** @throws IllegalArgumentException 端点不是合法的 http(s) 地址 */
    public List<String> fetchAvailableModels(String endpoint, String apiKey) {
        ModelSettings.Snapshot target = settings.probeTarget(endpoint, apiKey);
        return ModelCatalog.fetch(httpClient, objectMapper, URI.create(target.endpoint()), target.apiKey());
    }
}
