package com.pilot.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pilot.model")
public record ModelProperties(String apiKey, String endpoint, String model) {
}
