package com.pilot.server.config;

import com.pilot.core.permission.PermissionGate;
import com.pilot.core.permission.PolicyPermissionGate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 权限网关装配。
 *
 * <p>不区分 mock / real：mock 模式不执行真实工具，但健康检查和设置接口仍需要读取同一个档位。
 */
@Configuration(proxyBeanMethods = false)
public class PermissionConfiguration {
    @Bean
    PermissionGate permissionGate(PermissionSettings settings) {
        return new PolicyPermissionGate(settings::mode);
    }
}
