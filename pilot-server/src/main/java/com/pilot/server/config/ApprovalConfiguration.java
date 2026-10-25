package com.pilot.server.config;

import com.pilot.core.approval.ApprovalBroker;
import com.pilot.core.approval.InMemoryApprovalBroker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** 审批注册表与运行模式无关，mock 模式下也可用于联调审批接口。 */
@Configuration(proxyBeanMethods = false)
public class ApprovalConfiguration {
    @Bean
    ApprovalBroker approvalBroker(AgentProperties properties) {
        return new InMemoryApprovalBroker(Duration.ofSeconds(properties.approvalTimeoutSeconds()));
    }
}
