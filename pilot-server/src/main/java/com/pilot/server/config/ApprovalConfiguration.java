package com.pilot.server.config;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.approval.InMemoryApprovalBroker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** 审批注册表。等待超时按拒绝处理。 */
@Configuration(proxyBeanMethods = false)
public class ApprovalConfiguration {
    @Bean
    ApprovalBroker approvalBroker(AgentProperties properties) {
        return new InMemoryApprovalBroker(Duration.ofSeconds(properties.approvalTimeoutSeconds()));
    }
}
