package com.pilot.server.mobile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.stream.IntStream;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.pilot.server.config.PilotDataDir;

import tools.jackson.databind.ObjectMapper;

/**
 * 手机写操作的持久回执。先落盘占位再执行；断电时宁可返回待核实，也不自动重做一次命令。
 * 相同标识必须对应相同方法、路径和请求体。回执不保存附件或指令正文。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MobileRequestFilter extends OncePerRequestFilter {
    private static final int MAX_BODY = 12 * 1024 * 1024;
    private final Path directory;
    private final ObjectMapper mapper;
    private final Object[] locks = IntStream.range(0, 64).mapToObj(index -> new Object()).toArray();

    public MobileRequestFilter(PilotDataDir data, ObjectMapper mapper) {
        directory = data.file("mobile", "receipts");
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String id = request.getHeader("X-Pilot-Request-Id");
        if (id == null || "GET".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (!id.matches("[a-f0-9]{64}:[a-f0-9-]{36}")) {
            failure(response, 400, "无效的请求标识");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            failure(response, 413, "附件或消息过大");
            return;
        }
        String fingerprint = digest(
            (request.getMethod() + " " + request.getRequestURI() + "?" + request.getQueryString() + "\n" + digest(body))
                .getBytes(StandardCharsets.UTF_8));
        String key = digest(id.getBytes(StandardCharsets.UTF_8));
        synchronized (locks[Math.floorMod(key.hashCode(), locks.length)]) {
            Files.createDirectories(directory);
            Path file = directory.resolve(key + ".json");
            if (Files.exists(file)) {
                Receipt saved = mapper.readValue(Files.readString(file), Receipt.class);
                if (!saved.fingerprint().equals(fingerprint)) {
                    failure(response, 409, "同一请求标识不能用于不同操作");
                    return;
                }
                if (saved.status() == 0) {
                    failure(response, 409, "上次操作的结果待核实，请查看任务记录；不会自动重复执行");
                    return;
                }
                response.setStatus(saved.status());
                response.setContentType(saved.contentType());
                response.setHeader("X-Pilot-Replayed", "true");
                response.getOutputStream().write(Base64.getDecoder().decode(saved.body()));
                return;
            }
            save(file, new Receipt(fingerprint, 0, "application/json", ""));
            ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
            chain.doFilter(repeatable(request, body), cached);
            save(file,
                new Receipt(fingerprint, cached.getStatus(),
                    cached.getContentType() == null ? "application/json" : cached.getContentType(),
                    Base64.getEncoder().encodeToString(cached.getContentAsByteArray())));
            cached.copyBodyToResponse();
        }
    }

    private void save(Path file, Receipt receipt) throws IOException {
        Path temp = Files.createTempFile(directory, "receipt-", ".tmp");
        try {
            Files.writeString(temp, mapper.writeValueAsString(receipt));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
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

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void failure(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter()
            .write("{\"message\":\"" + message + "\",\"uncertain\":" + message.startsWith("上次操作") + "}");
    }

    public record Receipt(String fingerprint, int status, String contentType, String body) {
    }
}
