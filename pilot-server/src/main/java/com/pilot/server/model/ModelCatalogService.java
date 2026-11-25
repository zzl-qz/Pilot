package com.pilot.server.model;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.pilot.core.model.ModelCatalog;

import tools.jackson.databind.ObjectMapper;

/**
 * 代理后端探测「可用模型」与本机免密钥模型服务清单。
 *
 * <p>浏览器不能直接请求第三方模型服务的 {@code /models}（跨域、自签证书，
 * 而且把已保存密钥发回前端是安全红线），由后端复用意图一致的 HTTP 客户端代查。
 *
 * <p>密钥只在内存里过一遍：探测不落盘、不进日志、不改当前配置；
 * 本机扫描固定不带任何已保存密钥，防止云端密钥泄漏到本机端口。
 */
@Component
public class ModelCatalogService {
    private static final Duration LOCAL_SCAN_TIMEOUT = Duration.ofMillis(1200);
    private static final List<LocalService> LOCAL_SERVICES =
        List.of(new LocalService("Ollama", "http://127.0.0.1:11434/v1"),
            new LocalService("LM Studio", "http://127.0.0.1:1234/v1"),
            new LocalService("llama.cpp", "http://127.0.0.1:8080/v1"),
            new LocalService("vLLM", "http://127.0.0.1:8000/v1"));

    private final ModelSettings settings;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ModelCatalogService(ModelSettings settings, ObjectMapper objectMapper,
        @Qualifier("modelHttpClient") HttpClient httpClient) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public List<String> fetchAvailableModels(String endpoint, String apiKey) {
        ModelSettings.Snapshot target = settings.probeTarget(endpoint, apiKey);
        return ModelCatalog.fetch(httpClient, objectMapper, URI.create(target.endpoint()), target.apiKey());
    }

    /** 并发低超时扫描本机常见免密钥回环服务；离线、超时或非模型服务静默跳过，绝不附带已保存密钥。 */
    public List<LocalModelService> scanLocalServices() {
        return scanLocalServices(LOCAL_SERVICES, LOCAL_SCAN_TIMEOUT);
    }

    List<LocalModelService> scanLocalServices(List<LocalService> targets, Duration timeout) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        Duration effective = timeout == null || timeout.isNegative() || timeout.isZero() ? LOCAL_SCAN_TIMEOUT : timeout;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<LocalModelService>> probes = new ArrayList<>();
            for (LocalService service : targets) {
                probes.add(CompletableFuture.supplyAsync(() -> probeLocal(service, effective), executor));
            }
            return probes.stream()
                .map(probe -> probe.completeOnTimeout(null, effective.toMillis() + 150L, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> null).join())
                .filter(Objects::nonNull).toList();
        }
    }

    private LocalModelService probeLocal(LocalService service, Duration timeout) {
        try {
            List<String> found =
                ModelCatalog.fetch(httpClient, objectMapper, URI.create(service.endpoint()), null, timeout);
            return new LocalModelService(service.name(), service.endpoint(), found);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record LocalModelService(String name, String endpoint, List<String> models) {
    }

    record LocalService(String name, String endpoint) {
    }
}
