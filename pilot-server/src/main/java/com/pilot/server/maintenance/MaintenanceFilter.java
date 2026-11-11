package com.pilot.server.maintenance;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pilot.server.config.PilotDataDir;

/** 保护整个 API 操作，包含控制器前后的附件及手机回执写入。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class MaintenanceFilter extends OncePerRequestFilter {
    private final DataMaintenance maintenance;

    public MaintenanceFilter(PilotDataDir dataDir) {
        this.maintenance = dataDir.maintenance();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.startsWith("/api/maintenance/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        try (DataMaintenance.Scope scope = maintenance.tryActivity()) {
            if (scope == null) {
                response.setHeader("Retry-After", "2");
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "正在维护存储，请稍后重试");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
