package com.pilot.server.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.server.config.AttachmentLimits;

/**
 * 消息入口的请求体总量预检：附件以 base64 内联在 JSON 里（约放大 4/3），
 * 在 Jackson 读取前按 Content-Length 早拒，同时对实际读取的字节计数。
 *
 * <p>只作用于携带附件的 {@code POST /api/sessions/{id}/messages}；
 * 包括无 Content-Length 的分块请求；过滤器先于移动请求回执读取正文。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public class AttachmentBodyLimitFilter extends OncePerRequestFilter {
    private final AttachmentLimits limits;

    public AttachmentBodyLimitFilter(AttachmentLimits limits) {
        this.limits = limits;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 容器映射后的路径已解码，不能让 %6dessages 等等价 URL 绕过入口限流。
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        return !"POST".equalsIgnoreCase(request.getMethod())
            || !path.matches("^/api/sessions/[^/]+/messages(?:;[^/]*)?/?$");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long budget = (request.getHeader("X-Pilot-Device-Id") == null ? limits : limits.mobile()).maxRequestBytes();
        if (request.getContentLengthLong() > budget) {
            reject(response);
            return;
        }
        LimitedInput input = new LimitedInput(request.getInputStream(), budget);
        HttpServletRequest bounded = new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                return input;
            }

            @Override
            public BufferedReader getReader() {
                String encoding = getCharacterEncoding();
                return new BufferedReader(new InputStreamReader(input,
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
            }
        };
        try {
            chain.doFilter(bounded, response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (!input.exceeded) {
                throw e;
            }
        }
        if (input.exceeded && !response.isCommitted()) {
            response.resetBuffer();
            reject(response);
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"附件或消息数据超过总量预算\"}");
    }

    private static final class LimitedInput extends ServletInputStream {
        private final ServletInputStream input;
        private long remaining;
        private boolean exceeded;

        private LimitedInput(ServletInputStream input, long budget) {
            this.input = input;
            this.remaining = budget;
        }

        private int count(int bytes) {
            if (bytes > 0) {
                remaining -= bytes;
            }
            if (remaining < 0) {
                exceeded = true;
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "附件或消息数据超过总量预算");
            }
            return bytes;
        }

        @Override
        public int read() throws IOException {
            int value = input.read();
            count(value < 0 ? 0 : 1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
            return count(input.read(buffer, offset, (int)Math.min(length, Math.max(1, remaining + 1))));
        }

        @Override
        public boolean isFinished() {
            return exceeded || input.isFinished();
        }

        @Override
        public boolean isReady() {
            return input.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            input.setReadListener(listener);
        }
    }
}
