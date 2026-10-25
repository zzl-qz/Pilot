package com.pilot.server.config;

import com.pilot.core.session.SessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {
    @Bean
    SessionService sessionService() {
        return new SessionService();
    }
}
