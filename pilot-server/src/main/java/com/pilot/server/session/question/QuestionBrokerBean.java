package com.pilot.server.session.question;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.tool.QuestionBroker;

@Configuration
public class QuestionBrokerBean {
    @Bean
    QuestionBroker questionBroker() {
        return new QuestionBroker();
    }
}
