package com.pilot.server.config;

import com.pilot.core.session.SessionRepository;
import com.pilot.core.session.SessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class SessionConfiguration {
    @Bean
    SessionService sessionService(SessionRepository repository) {
        return new SessionService(Clock.systemUTC(), repository);
    }
}
