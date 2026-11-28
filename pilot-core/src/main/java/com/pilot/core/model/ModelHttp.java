package com.pilot.core.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.pilot.core.http.BoundedHttp;
import com.pilot.core.message.Attachment;
import com.pilot.core.message.Message;
import com.pilot.core.model.diagnostics.ModelRequestDumper;
import com.pilot.core.util.SecretScrubber;
import com.pilot.core.util.Texts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 模型 HTTP 请求构造、错误摘要、内容片段和原生协议的公共原语。 */
final class ModelHttp {
    private static final Pattern REJECTED_CACHE_400 = Pattern.compile(
        "(?s).*(unknown|unsupported|unrecognized|unexpected|not allowed|not permitted|extra inputs|invalid|不支持).*");

    sealed interface ContentPart permits TextPart, ImagePart {}

    record TextPart(String text) implements ContentPart {
    }

    record ImagePart(String mimeType, String base64) implements ContentPart {
        String dataUrl() {
            return "data:" + mimeType + ";base64," + base64;
        }
    }

    private ModelHttp() {
    }

    static String[] splitQuery(URI endpoint) {
        String raw = endpoint.toString();
        int queryIndex = raw.indexOf('?');
        return queryIndex >= 0 ? new String[] {raw.substring(0, queryIndex), raw.substring(queryIndex)}
            : new String[] {raw, ""};
    }

    static URI nativeEndpoint(URI endpoint, String suffix, boolean preserveExplicitPath) {
        if (endpoint == null) {
            throw new IllegalArgumentException("model endpoint must not be null");
        }
        String[] split = splitQuery(endpoint);
        String base = split[0];
        String query = split[1];
        if (preserveExplicitPath && base.endsWith(suffix)) {
            return endpoint;
        }
        String chatPath = "/chat/completions";
        if (base.endsWith(chatPath)) {
            base = base.substring(0, base.length() - chatPath.length());
        } else if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!preserveExplicitPath && base.endsWith(suffix)) {
            return URI.create(base + query);
        }
        return URI.create(base + (base.endsWith("/v1") ? "" : "/v1") + suffix + query);
    }

    static HttpRequest.Builder jsonPost(URI endpoint, Duration timeout, ObjectMapper mapper, Map<String, Object> body) {
        Object ordered = CanonicalJson.ordered(body);
        ModelRequestDumper.recordIfEnabled(endpoint, mapper, ordered);
        return HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json").timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(ordered), StandardCharsets.UTF_8));
    }

    /** 仅当密钥非空白时附加 Bearer 认证头，避免 Ollama / LM Studio 等免密钥本机服务收到空 Bearer 后返回 401。 */
    static HttpRequest.Builder withBearerAuth(HttpRequest.Builder builder, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.strip());
        }
        return builder;
    }

    /** 仅当请求头值非空白时附加该头，兼容免密钥本地网关。 */
    static HttpRequest.Builder withHeaderIfPresent(HttpRequest.Builder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.header(name, value.strip());
        }
        return builder;
    }

    static JsonNode parseJson(ObjectMapper mapper, String body) {
        try {
            return mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "模型响应不是合法 JSON：" + snippet(body), e);
        }
    }

    static ModelClientException wrapInterrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        return new ModelClientException(ModelFailureKind.INTERRUPTED, "模型请求被中断", e);
    }

    static ModelClientException wrapIo(IOException e) {
        return wrapIo(e, "模型请求失败：" + e.getMessage());
    }

    static ModelClientException wrapIo(IOException e, String message) {
        ModelFailureKind kind = Thread.currentThread().isInterrupted() ? ModelFailureKind.INTERRUPTED
            : e instanceof BoundedHttp.ResponseLimitException ? ModelFailureKind.PERMANENT : ModelFailureKind.NETWORK;
        return new ModelClientException(kind, message, e);
    }

    static void throwNativeHttpError(int statusCode, HttpHeaders headers, String rawBody) {
        String detail = snippet(rawBody);
        if (NativeToolRejectedException.rejected(statusCode, detail)) {
            throw new NativeToolRejectedException("服务端未接受原生工具或协议", statusCode == 404 || statusCode == 405);
        }
        throw ModelClientException.httpError(ModelFailureSignals.statusKind(statusCode, detail), statusCode,
            retryAfterMs(headers), "模型接口返回 HTTP " + statusCode + "：" + detail);
    }

    static BoundedHttp.TextResponse sendNative(HttpClient httpClient, HttpRequest request, Duration idleTimeout) {
        BoundedHttp.TextResponse response;
        try {
            response = BoundedHttp.text(httpClient, request, idleTimeout, BoundedHttp.JSON_BYTES);
        } catch (InterruptedException e) {
            throw wrapInterrupted(e);
        } catch (IOException e) {
            throw wrapIo(e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throwNativeHttpError(response.statusCode(), response.headers(), response.body());
        }
        return response;
    }

    static boolean isRejectedCache400(Throwable failure) {
        if (!(failure instanceof ModelClientException) || failure.getMessage() == null) {
            return false;
        }
        String message = failure.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("http 400") && REJECTED_CACHE_400.matcher(message).matches();
    }

    static String promptCacheKey(String cacheGroup) {
        return "pilot-" + MeteredModelClient.hash(cacheGroup).substring(0, 32);
    }

    static Map<String, Object> parseToolArgs(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode parsed = mapper.readTree(raw);
            if (parsed == null || !parsed.isObject()) {
                throw new ModelClientException(ModelFailureKind.MALFORMED_TOOL_CALL,
                    "工具调用参数必须为 JSON 对象：" + snippet(raw));
            }
            return toObjectMap(mapper, parsed);
        } catch (ModelClientException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ModelClientException(ModelFailureKind.MALFORMED_TOOL_CALL, "工具调用参数不是合法 JSON：" + snippet(raw), e);
        }
    }

    static Map<String, Object> parseToolArgs(ObjectMapper mapper, JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (node.isObject()) {
            return toObjectMap(mapper, node);
        }
        return parseToolArgs(mapper, node.isString() ? node.asString() : node.toString());
    }

    static Map<String, Object> toObjectMap(ObjectMapper mapper, JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node,
            mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    static void emit(Consumer<String> sink, String delta) {
        if (sink != null && delta != null && !delta.isEmpty()) {
            sink.accept(delta);
        }
    }

    static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isString() ? node.asString() : node.toString();
    }

    /**
     * 将含图片附件的消息拆分为协议无关的文本/图片片段；不含图片附件时返回空列表，
     * 调用方直接按纯文本发送，避免给不支持多模态的兼容端点发送数组。
     */
    static List<ContentPart> contentParts(Message message) {
        List<Attachment> images = message.attachments().stream().filter(Attachment::image).toList();
        if (images.isEmpty()) {
            return List.of();
        }
        ArrayList<ContentPart> parts = new ArrayList<>();
        List<Integer> positions = message.imagePositions();
        if (positions.isEmpty()) {
            if (!message.content().isBlank()) {
                parts.add(new TextPart(message.content()));
            }
            for (Attachment image : images) {
                parts.add(imagePart(image));
            }
            return List.copyOf(parts);
        }
        int cursor = 0;
        for (int i = 0; i < images.size(); i++) {
            int position = Math.max(cursor, Math.min(message.content().length(), positions.get(i)));
            if (position > cursor) {
                parts.add(new TextPart(message.content().substring(cursor, position)));
            }
            parts.add(imagePart(images.get(i)));
            cursor = position;
        }
        if (cursor < message.content().length()) {
            parts.add(new TextPart(message.content().substring(cursor)));
        }
        return List.copyOf(parts);
    }

    /** 图片文件丢失时降级为文字占位，不能让整段历史发不出去。 */
    private static ContentPart imagePart(Attachment image) {
        Path path = Path.of(image.path());
        if (!Files.isRegularFile(path)) {
            return new TextPart("[图片已不存在：" + image.name() + "]");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new ImagePart(image.mimeType(), Base64.getEncoder().encodeToString(bytes));
        } catch (IOException e) {
            throw new ModelClientException(ModelFailureKind.PERMANENT, "读取图片附件失败：" + image.name(), e);
        }
    }

    /** 服务端 Retry-After 提示上限（2 分钟），防止异常网关返回超大值卡死重试。 */
    static final long MAX_RETRY_AFTER_MS = 120_000L;

    /** 从响应头解析服务端退避提示（支持 retry-after-ms、Retry-After 秒数/HTTP 日期、x-ratelimit-reset-after）。 */
    static long retryAfterMs(HttpHeaders headers) {
        return retryAfterMs(headers, Instant.now());
    }

    static long retryAfterMs(HttpHeaders headers, Instant now) {
        if (headers == null) {
            return -1L;
        }
        Optional<String> millis = headers.firstValue("retry-after-ms");
        if (millis.isPresent()) {
            long parsed = parseNonNegativeMillis(millis.get(), 1.0);
            if (parsed >= 0) {
                return Math.min(MAX_RETRY_AFTER_MS, parsed);
            }
        }
        Optional<String> retryAfter = headers.firstValue("retry-after");
        if (retryAfter.isPresent()) {
            long parsed = parseRetryAfterValue(retryAfter.get(), now);
            if (parsed >= 0) {
                return Math.min(MAX_RETRY_AFTER_MS, parsed);
            }
        }
        Optional<String> resetAfter = headers.firstValue("x-ratelimit-reset-after");
        if (resetAfter.isPresent()) {
            long parsed = parseNonNegativeMillis(resetAfter.get(), 1_000.0);
            if (parsed >= 0) {
                return Math.min(MAX_RETRY_AFTER_MS, parsed);
            }
        }
        return -1L;
    }

    private static long parseRetryAfterValue(String raw, Instant now) {
        long seconds = parseNonNegativeMillis(raw, 1_000.0);
        if (seconds >= 0) {
            return seconds;
        }
        try {
            Instant target = ZonedDateTime.parse(raw.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0L, Duration.between(now == null ? Instant.now() : now, target).toMillis());
        } catch (DateTimeParseException ignored) {
            return -1L;
        }
    }

    private static long parseNonNegativeMillis(String raw, double multiplier) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        try {
            double value = Double.parseDouble(raw.strip());
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
                return -1L;
            }
            return (long)Math.ceil(value * multiplier);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    static String snippet(String body) {
        return Texts.oneLine(SecretScrubber.scrub(body), 300);
    }
}
