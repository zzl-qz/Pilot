package com.pilot.server.mobile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.pilot.server.attachment.AttachmentBodyLimitFilter;
import com.pilot.server.attachment.AttachmentLimits;

/**
 * 手机与 Web 写操作的持久回执。先落盘占位再执行；断电时宁可返回待核实，也不自动重做一次命令。
 * 相同标识必须对应相同方法、路径和请求体。回执不保存附件或指令正文。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MobileRequestFilter extends OncePerRequestFilter {
    private static final int MAX_RESPONSE = 16 * 1024 * 1024;
    private static final int MAX_BODY = 12 * 1024 * 1024;
    private final MobileReceiptStore receipts;
    private final AttachmentLimits limits;

    public MobileRequestFilter(MobileReceiptStore receipts) {
        this(receipts, AttachmentLimits.defaults());
    }

    @Autowired
    public MobileRequestFilter(MobileReceiptStore receipts, AttachmentLimits limits) {
        this.receipts = receipts;
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String id = request.getHeader("X-Pilot-Request-Id");
        if (id == null || "GET".equals(request.getMethod())) {
            if (id == null && request.getHeader("X-Pilot-Device-Id") != null && !"GET".equals(request.getMethod())
                && !"/api/mobile/receipts/ack".equals(request.getRequestURI())) {
                failure(response, 426, "请更新手机和电脑连接组件", true);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (!id.matches("[a-f0-9]{64}:(?:[a-f0-9-]{36}|" + MobileReceiptStore.TOKEN_PATTERN + ")")) {
            failure(response, 400, "无效的请求标识", true);
            return;
        }
        long budget =
            AttachmentBodyLimitFilter.isMessageRequest(request) && request.getHeader("X-Pilot-Device-Id") == null
                ? limits.maxRequestBytes() : MAX_BODY;
        byte[] body = request.getInputStream().readNBytes((int)Math.min(budget + 1, Integer.MAX_VALUE));
        if (body.length > budget) {
            failure(response, 413, "附件或消息过大", false);
            return;
        }
        String fingerprint = MobileReceiptStore.digest((request.getMethod() + " " + request.getRequestURI() + "?"
            + request.getQueryString() + "\n" + MobileReceiptStore.digest(body)).getBytes(StandardCharsets.UTF_8));
        synchronized (receipts) {
            try {
                MobileReceiptStore.Receipt saved = receipts.get(id);
                if (saved != null) {
                    if (!saved.fingerprint().equals(fingerprint)) {
                        failure(response, 409, "同一请求标识不能用于不同操作", true);
                        return;
                    }
                    if (saved.status() == 0) {
                        failure(response, 409, "上次操作的结果待核实，请查看任务记录；不会自动重复执行", true);
                        return;
                    }
                    response.setStatus(saved.status());
                    response.setContentType(saved.contentType());
                    response.setHeader("X-Pilot-Replayed", "true");
                    response.getOutputStream().write(Base64.getDecoder().decode(saved.body()));
                    return;
                }
                long expires = receipts.validateNew(id);
                long created = receipts.now();
                receipts.save(id,
                    new MobileReceiptStore.Receipt(fingerprint, 0, "application/json", "", created, expires, 0));
                ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
                chain.doFilter(repeatable(request, body), cached);
                if (cached.getContentSize() > MAX_RESPONSE) {
                    failure(response, 507, "响应超过回执上限，结果待核实，请查看任务记录", true);
                    return;
                }
                receipts.save(id,
                    new MobileReceiptStore.Receipt(fingerprint, cached.getStatus(),
                        cached.getContentType() == null ? "application/json" : cached.getContentType(),
                        Base64.getEncoder().encodeToString(cached.getContentAsByteArray()), created, expires, 0));
                cached.copyBodyToResponse();
            } catch (MobileReceiptStore.Rejected e) {
                response.setHeader("X-Pilot-Receipt-Error", e.code());
                failure(response, e.status(), e.getMessage(), e.uncertain());
            }
        }
    }

    private static HttpServletRequest repeatable(HttpServletRequest request, byte[] bytes) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return input.read();
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) {
                        return input.read(buffer, offset, length);
                    }

                    @Override
                    public boolean isFinished() {
                        return input.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("同步写请求");
                    }
                };
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        };
    }

    private static void failure(HttpServletResponse response, int status, String message, boolean uncertain)
        throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\",\"uncertain\":" + uncertain + "}");
    }
}
