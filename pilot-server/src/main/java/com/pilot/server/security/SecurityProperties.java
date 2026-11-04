package com.pilot.server.security;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 远程访问安全配置。
 *
 * @param mode                {@code local}：只允许本机回环地址；{@code token}：允许远程，但必须有访问令牌
 * @param token               访问令牌；{@code token} 模式下为空会拒绝启动远程访问
 * @param allowedOrigins      允许的浏览器来源；为空表示只允许同源
 * @param rateLimitPerMinute  单个来源 IP 每分钟最多多少请求；{@code <=0} 表示不限制
 * @param audit               是否把访问拒绝与限流事件写入审计日志
 */
@ConfigurationProperties(prefix = "pilot.security")
public record SecurityProperties(String mode, String token, List<String> allowedOrigins, Integer rateLimitPerMinute,
    Boolean audit) {
    public SecurityProperties {
        mode = mode == null || mode.isBlank() ? "local" : mode.strip().toLowerCase(Locale.ROOT);
        token = token == null ? "" : token.strip();
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
            .filter(origin -> origin != null && !origin.isBlank()).map(origin -> origin.strip()).toList();
        rateLimitPerMinute = rateLimitPerMinute == null || rateLimitPerMinute < 0 ? 0 : rateLimitPerMinute;
        audit = audit == null || audit;
    }

    public boolean tokenMode() {
        return "token".equals(mode);
    }

    public boolean localOnly() {
        return !tokenMode();
    }
}
