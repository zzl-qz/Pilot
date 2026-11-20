package com.pilot.core.model;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

/**
 * 默认关闭的模型请求调试落盘器：开启后将脱敏后的真实线上传输请求体落盘至
 * {@code <dataDir>/debug-requests/<sessionId>/<runId>/req-<seq>.json}，供单步诊断与反事实重放使用。
 *
 * <p>默认关闭（{@link #DISABLED}）时不产生任何目录或磁盘 I/O；落盘异常只记日志，绝不阻断模型调用。
 */
public final class ModelRequestDumper {
    private static final Logger log = LoggerFactory.getLogger(ModelRequestDumper.class);
    private static final Config DISABLED = new Config(false, null);
    private static final Set<String> SENSITIVE_KEYS =
        Set.of("authorization", "api_key", "apikey", "x-api-key", "x_api_key", "access_token", "secret", "password");
    private static final int MAX_TRACKED_RUNS = 256;

    private record Config(boolean enabled, Path root) {
    }

    private static volatile Config config = DISABLED;
    private static final ConcurrentHashMap<String, AtomicInteger> SEQUENCES = new ConcurrentHashMap<>();

    private ModelRequestDumper() {
    }

    public static void configure(boolean enabled, Path dataDirRoot) {
        if (!enabled || dataDirRoot == null) {
            config = DISABLED;
            SEQUENCES.clear();
            return;
        }
        config = new Config(true, dataDirRoot.toAbsolutePath().normalize());
        SEQUENCES.clear();
    }

    public static boolean isEnabled() {
        return config.enabled();
    }

    static void recordIfEnabled(URI endpoint, ObjectMapper mapper, Object orderedBody) {
        Config current = config;
        if (!current.enabled() || current.root() == null || mapper == null) {
            return;
        }
        ModelRequestScope.State scope = ModelRequestScope.current();
        String sessionId = safeSegment(scope.sessionId(), "unknown-session");
        String runId = safeSegment(scope.runId(), "ad-hoc");
        String key = sessionId + "/" + runId;
        if (SEQUENCES.size() >= MAX_TRACKED_RUNS && !SEQUENCES.containsKey(key)) {
            SEQUENCES.clear();
        }
        int seq = SEQUENCES.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sequence", seq);
        record.put("sessionId", scope.sessionId());
        record.put("runId", scope.runId());
        record.put("purpose", scope.purpose());
        record.put("timestamp", Instant.now().toString());
        record.put("endpoint", sanitizeEndpoint(endpoint));
        record.put("body", sanitizeValue(orderedBody));
        Path target =
            current.root().resolve("debug-requests").resolve(sessionId).resolve(runId).resolve("req-" + seq + ".json");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, mapper.writeValueAsString(record), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("模型请求调试落盘失败（已忽略，不影响主请求）：{}", target, e);
        }
    }

    /** 剥除查询参数与用户信息，防止网关 URL 中附带的 key/token 落盘。 */
    static String sanitizeEndpoint(URI endpoint) {
        if (endpoint == null) {
            return "";
        }
        try {
            return new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), endpoint.getPath(), null,
                null).toString();
        } catch (Exception ignored) {
            String raw = endpoint.toString();
            int query = raw.indexOf('?');
            return query >= 0 ? raw.substring(0, query) : raw;
        }
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (isSensitiveKey(name)) {
                    clean.put(name, "[REDACTED]");
                } else {
                    clean.put(name, sanitizeValue(entry.getValue()));
                }
            }
            return clean;
        }
        if (value instanceof List<?> list) {
            List<Object> clean = new ArrayList<>(list.size());
            for (Object item : list) {
                clean.add(sanitizeValue(item));
            }
            return clean;
        }
        return value;
    }

    private static boolean isSensitiveKey(String name) {
        return name != null && SENSITIVE_KEYS.contains(name.toLowerCase(Locale.ROOT).strip());
    }

    private static String safeSegment(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String cleaned = raw.strip().replaceAll("[^A-Za-z0-9._-]", "_");
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.isEmpty() ? fallback : cleaned.length() > 96 ? cleaned.substring(0, 96) : cleaned;
    }
}
