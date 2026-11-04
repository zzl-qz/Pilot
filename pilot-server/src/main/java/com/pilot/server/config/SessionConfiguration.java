package com.pilot.server.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;

@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {
    @Bean
    SessionService sessionService(SessionRepository repository) {
        return new SessionService(Clock.systemUTC(), repository);
    }
}
