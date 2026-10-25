package com.pilot.server.config;

import com.pilot.core.model.ModelClient;
import com.pilot.core.model.OpenAiCompatibleModelClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
public class ModelClientConfiguration {
    @Bean
    ModelClient modelClient(ModelProperties properties, ObjectMapper objectMapper) {
        return new OpenAiCompatibleModelClient(HttpClient.newHttpClient(), objectMapper,
                URI.create(properties.endpoint()), properties.apiKey(), properties.model());
    }
}
