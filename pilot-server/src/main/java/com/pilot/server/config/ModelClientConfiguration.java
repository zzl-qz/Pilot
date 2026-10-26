package com.pilot.server.config;

import com.pilot.core.model.ModelClient;
import com.pilot.core.model.OpenAiCompatibleModelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ModelClientConfiguration {
    /**
     * 返回的是可热切换的代理：真正构造 {@link OpenAiCompatibleModelClient} 的时机推迟到首次调用，
     * 并在模型配置变化时重建，因此改模型 / 端点 / API Key 不需要重启。
     */
    @Bean
    ModelClient modelClient(ModelSettings settings, ObjectMapper objectMapper,
                            @Value("${pilot.model.timeout-seconds:90}") long timeoutSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Duration timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        return new ModelClientProvider(settings, snapshot -> new OpenAiCompatibleModelClient(
                httpClient, objectMapper, URI.create(snapshot.endpoint()), snapshot.apiKey(),
                snapshot.model(), timeout));
    }
}
