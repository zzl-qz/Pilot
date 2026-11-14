package com.pilot.server.model;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.model.ModelClient;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ModelClientConfiguration {
    @Bean
    HttpClient modelHttpClient(@Value("${pilot.model.connect-timeout-seconds:10}") long connectTimeoutSeconds) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build();
    }

    /**
     * 返回热切换代理：按当前端点协议选择 OpenAI Chat Completions、OpenAI Responses 或 Anthropic Messages，
     * 配置变化时在下一次请求前重建对应客户端。
     */
    @Bean
    ModelClient modelClient(ModelSettings settings, ObjectMapper objectMapper, ModelRuntimeSettings runtime,
        @Value("${pilot.model.prompt-caching:false}") boolean promptCaching,
        @Qualifier("modelHttpClient") HttpClient httpClient) {
        // 显式缓存标记只给支持 cache_control 的端点；OpenAI 官方端点自动缓存，保持默认关闭
        return new ModelClientProvider(settings, snapshot -> {
            var limits = runtime.timeouts(snapshot);
            return new DiscoveredModelClient(snapshot, runtime, httpClient, objectMapper,
                Duration.ofSeconds(limits.totalSeconds()), Duration.ofSeconds(limits.idleSeconds()), promptCaching);
        }, runtime::revision);
    }
}
