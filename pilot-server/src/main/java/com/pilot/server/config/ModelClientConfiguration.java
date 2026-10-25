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
    @Bean
    ModelClient modelClient(ModelProperties properties, ObjectMapper objectMapper,
                            @Value("${pilot.model.timeout-seconds:90}") long timeoutSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return new OpenAiCompatibleModelClient(httpClient, objectMapper,
                URI.create(properties.endpoint()), properties.apiKey(), properties.model(),
                Duration.ofSeconds(Math.max(5, timeoutSeconds)));
    }
}
