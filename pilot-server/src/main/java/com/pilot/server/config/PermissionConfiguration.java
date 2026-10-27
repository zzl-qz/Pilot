package com.pilot.server.config;

import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PolicyPermissionGate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 权限网关装配。健康检查和设置接口读取同一个档位。
 */
@Configuration(proxyBeanMethods = false)
public class PermissionConfiguration {
    @Bean
    PermissionGate permissionGate(PermissionSettings settings) {
        return new PolicyPermissionGate(settings::mode);
    }
}
