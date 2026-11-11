package com.pilot.server.session;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 回退时排空同会话 API 读取，防止任务文件与数据库之间的中间状态被展示。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 16)
public class HistoryAccessFilter extends OncePerRequestFilter {
    private final HistoryEditCoordinator history;
    public HistoryAccessFilter(HistoryEditCoordinator history) { this.history = history; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/sessions/") || ("POST".equals(request.getMethod()) && path.endsWith("/checkpoints/restore"))) {
            chain.doFilter(request, response); return;
        }
        String sessionId = path.substring("/api/sessions/".length()).split("/", 2)[0];
        try (var scope = history.tryRead(sessionId)) {
            if (scope == null) {
                response.setHeader("Retry-After", "2");
                response.sendError(503, "正在恢复会话历史，请稍后重试");
            } else chain.doFilter(request, response);
        }
    }
}
