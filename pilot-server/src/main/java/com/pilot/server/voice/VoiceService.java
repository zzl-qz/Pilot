package com.pilot.server.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pilot.server.model.ModelSettings;
import com.pilot.server.store.SettingsStore;

import tools.jackson.databind.ObjectMapper;

/** 只在用户完成录音后上传；密钥留在服务端，音频不持久保存、不写日志。 */
@Service
public class VoiceService {
    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 上传音频并等转写结果的上限。 */
    private static final Duration TRANSCRIBE_TIMEOUT = Duration.ofSeconds(60);
    private final ModelSettings models;
    private final SettingsStore store;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String defaultModel;
    private final VoiceLimits limits;

    public VoiceService(ModelSettings models, SettingsStore store, ObjectMapper mapper, VoiceLimits limits,
        @Value("${pilot.voice.model:gpt-4o-mini-transcribe}") String defaultModel) {
        this.models = models;
        this.store = store;
        this.mapper = mapper;
        this.defaultModel = defaultModel;
        this.limits = limits;
        client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    VoiceLimits limits() {
        return limits;
    }

    /** effectiveEndpoint：实际会请求的转写地址，界面直接显示，避免「配了却不知道打到哪」。 */
    public record Settings(String endpoint, String model, boolean apiKeySet, String effectiveEndpoint) {
    }

    public record Update(String endpoint, String model, String apiKey, Boolean clearApiKey) {
    }

    public synchronized Settings settings() {
        String configured = store.find("voice.endpoint").orElse("");
        return new Settings(configured, model(), !key().isBlank(), effectiveEndpoint(configured));
    }

    /** 留空时沿用聊天端点，把 /chat/completions 换成 /audio/transcriptions。 */
    private String effectiveEndpoint(String configured) {
        try {
            var chat = models.snapshot();
            return transcriptionEndpoint(configured.isBlank() ? chat.endpoint() : configured).toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public synchronized Settings update(Update update) {
        if (update == null) {
            throw new IllegalArgumentException("语音配置不能为空");
        }
        String endpoint = update.endpoint() == null ? settings().endpoint() : update.endpoint().strip();
        if (!endpoint.isBlank()) {
            transcriptionEndpoint(endpoint);
        }
        String model = update.model() == null || update.model().isBlank() ? model() : update.model().strip();
        if (!model.matches("[\\w./:@-]{1,200}")) {
            throw new IllegalArgumentException("语音模型名称格式不正确");
        }
        String key = Boolean.TRUE.equals(update.clearApiKey()) ? ""
            : update.apiKey() == null || update.apiKey().isBlank() ? key() : update.apiKey().strip();
        store.saveAll(Map.of("voice.endpoint", endpoint, "voice.model", model, "voice.api-key", key));
        return settings();
    }

    private String model() {
        return store.find("voice.model").orElse(defaultModel);
    }

    private String key() {
        return store.find("voice.api-key").orElse("");
    }

    /** 同一提供商内解析，不把聊天模型的凭据转发到另一家服务。 */
    public static URI transcriptionEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("语音端点不是有效 URL");
        }
        if (uri.getHost() == null
            || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("语音端点应为不含凭据、查询参数的 HTTP/HTTPS 地址");
        }
        String path = uri.getRawPath().replaceAll("/+$", "");
        if (!path.endsWith("/audio/transcriptions")) {
            path = path.replaceFirst("/(chat/completions|responses)$", "");
            if (path.isEmpty()) {
                path = "/v1";
            }
            path += "/audio/transcriptions";
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path);
    }

    public String transcribe(byte[] audio, String contentType) {
        if (audio == null || audio.length == 0) {
            throw new IllegalArgumentException("录音为空，请重新录音");
        }
        if (limits.exceedsUploadLimit(audio.length)) {
            throw new IllegalArgumentException(limits.uploadLimitMessage());
        }
        String mime = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        String extension = switch (mime) {
            case "audio/webm" -> "webm";
            case "audio/mp4", "audio/x-m4a" -> "mp4";
            case "audio/ogg" -> "ogg";
            case "audio/wav", "audio/x-wav" -> "wav";
            default -> throw new IllegalArgumentException("不支持的录音格式，请使用 WebM、MP4、Ogg 或 WAV");
        };
        URI endpoint;
        String apiKey;
        String model;
        synchronized (this) {
            var chat = models.snapshot();
            String configured = settings().endpoint();
            endpoint = transcriptionEndpoint(configured.isBlank() ? chat.endpoint() : configured);
            // 独立服务必须填写自己的 Key；不默认复制聊天服务的密钥。
            apiKey = key().isBlank() && configured.isBlank() ? chat.apiKey() : key();
            model = model();
        }
        String boundary = "pilot-voice-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream(audio.length + 1024);
        try {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording."
                + extension + "\"\r\n" + "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(audio);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(endpoint).timeout(TRANSCRIBE_TIMEOUT).header("Content-Type",
                "multipart/form-data; boundary=" + boundary);
            if (apiKey != null && !apiKey.isBlank()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            var response = client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            byte[] result;
            try (var input = response.body()) {
                result = input.readNBytes(256 * 1024 + 1);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 中转站的 503 通常是「没有该模型的渠道」，把上游原因带出来才排查得动。
                String upstream = upstreamError(result);
                log.warn("语音转写失败：HTTP {}，端点 {}，模型 {}{}", response.statusCode(), endpoint, model,
                    upstream.isBlank() ? "" : "，上游：" + upstream);
                throw new VoiceException(withUpstream(switch (response.statusCode()) {
                    case 401, 403 -> "语音服务鉴权失败，请检查设置中的语音 API Key";
                    case 404, 405 -> "当前服务不支持语音转写，请在设置 → 模型 → 语音输入中配置支持转写的端点和模型";
                    // 中转/兼容网关常常没有 /audio/transcriptions 这条路由，或该模型没有可用渠道。
                    case 500, 502, 503, 504 ->
                        "语音服务不可用（HTTP " + response.statusCode() + "）。端点可能不提供转写能力，请在设置 → 模型 → 语音输入中配置独立的转写端点与模型";
                    case 429 -> "语音服务请求过多或额度不足，请稍后重试";
                    default -> "语音服务未能完成转写（HTTP " + response.statusCode() + "），请检查语音模型配置后重试";
                }, upstream));
            }
            if (result.length > 256 * 1024) {
                throw new VoiceException("语音服务返回内容过大，请缩短录音后重试");
            }
            String text;
            try {
                text = mapper.readTree(result).path("text").asText("").strip();
            } catch (RuntimeException e) {
                throw new VoiceException("语音服务返回格式不正确，请检查转写端点");
            }
            if (text.isEmpty()) {
                throw new VoiceException("没有识别到文字，请重试或重新录音");
            }
            if (text.length() > 20000) {
                throw new VoiceException("转写文字过长，请缩短录音后重试");
            }
            return text;
        } catch (HttpTimeoutException e) {
            throw new VoiceException("语音转写超时，请重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceException("语音转写已取消");
        } catch (IOException e) {
            throw new VoiceException("无法连接语音服务，请检查端点或网络后重试");
        }
    }

    /** 上游报错原因：OpenAI 风格 {@code {"error":{"message":…}}} 和 one-api/new-api 的 {@code {"message":…}} 都覆盖。 */
    private String upstreamError(byte[] body) {
        if (body.length == 0) {
            return "";
        }
        try {
            var node = mapper.readTree(body);
            String text = node.path("error").path("message").asText("");
            if (text.isBlank()) {
                text = node.path("message").asText("");
            }
            text = text.strip().replaceAll("\\s+", " ");
            return text.length() > 120 ? text.substring(0, 120) + "…" : text;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String withUpstream(String message, String upstream) {
        return upstream.isBlank() ? message : message + "（上游： " + upstream + "）";
    }

    public static class VoiceException extends RuntimeException {
        public VoiceException(String message) {
            super(message);
        }
    }
}
