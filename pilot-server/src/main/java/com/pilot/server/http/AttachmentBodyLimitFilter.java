package com.pilot.server.http;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pilot.server.config.AttachmentLimits;

/**
 * 消息入口的请求体总量预检：附件以 base64 内联在 JSON 里（约放大 4/3），
 * 在 Jackson 读取前按 Content-Length 早拒，避免为超限附件一次性分配大内存。
 *
 * <p>只作用于携带附件的 {@code POST /api/sessions/{id}/messages}；
 * steer/queue 等纯文本入口不适用。无 Content-Length（分块）时放行，
 * 由 {@code AttachmentService.prepare} 逐条兜底。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AttachmentBodyLimitFilter extends OncePerRequestFilter {
    private final AttachmentLimits limits;

    public AttachmentBodyLimitFilter(AttachmentLimits limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
            || !request.getRequestURI().matches("^/api/sessions/[^/]+/messages$");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        long budget = limits.maxTotalBytes() * 4L / 3L + 2_000_000L;
        if (contentLength > 0 && contentLength > budget) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                .write("{\"message\":\"附件或消息数据超过 " + limits.maxTotalBytes() / 1024 / 1024 + " MB 的总量预算\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
