package com.pilot.server.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.model.AnthropicMessagesModelClient;
import com.pilot.core.model.ModelClient;
import com.pilot.core.model.ModelProfile;
import com.pilot.core.model.NativeCapability;
import com.pilot.core.model.OpenAiCompatibleModelClient;
import com.pilot.core.model.OpenAiResponsesModelClient;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ModelClientConfiguration {
    /**
     * 返回热切换代理：按当前端点协议选择 OpenAI Chat Completions、OpenAI Responses 或 Anthropic Messages，
     * 配置变化时在下一次请求前重建对应客户端。
     */
    @Bean
    ModelClient modelClient(ModelSettings settings, ObjectMapper objectMapper, ModelRuntimeSettings runtime,
        @Value("${pilot.model.timeout-seconds:90}") long timeoutSeconds,
        @Value("${pilot.model.prompt-caching:false}") boolean promptCaching) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Duration timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        // 显式缓存标记只给支持 cache_control 的端点；OpenAI 官方端点自动缓存，保持默认关闭
        return new ModelClientProvider(settings, snapshot -> {
            ModelProfile profile = runtime.profile(snapshot);
            Set<NativeCapability> nativeCapabilities = runtime.nativeCapabilities(snapshot);
            URI endpoint = URI.create(snapshot.endpoint());
            if (runtime.anthropicMessages(snapshot)) {
                return new AnthropicMessagesModelClient(httpClient, objectMapper, endpoint, snapshot.apiKey(),
                    snapshot.model(), timeout, profile, nativeCapabilities);
            }
            if (nativeCapabilities.contains(NativeCapability.WEB_SEARCH)) {
                return new OpenAiResponsesModelClient(httpClient, objectMapper, endpoint, snapshot.apiKey(),
                    snapshot.model(), timeout, profile);
            }
            return new OpenAiCompatibleModelClient(httpClient, objectMapper, endpoint, snapshot.apiKey(),
                snapshot.model(), timeout).withProfile(profile).withPromptCaching(promptCaching);
        }, runtime::revision);
    }
}
