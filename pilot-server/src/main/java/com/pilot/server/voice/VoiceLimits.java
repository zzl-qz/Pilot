package com.pilot.server.voice;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 录音上传的实际预算，包含 multipart 请求所需的封装空间。 */
@Component
public final class VoiceLimits {
    private static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;
    private static final int MULTIPART_OVERHEAD = 1024;
    private final Budget budget;

    public record Budget(int maxBytes, int maxSeconds) {
    }

    @Autowired
    public VoiceLimits(ObjectProvider<MultipartConfigElement> multipart,
        @Value("${pilot.voice.max-recording-seconds:120}") int maxSeconds) {
        this(multipart.getIfAvailable(), maxSeconds);
    }

    VoiceLimits(MultipartConfigElement multipart, int maxSeconds) {
        long maxBytes = DEFAULT_MAX_BYTES;
        if (multipart != null) {
            long file = multipart.getMaxFileSize();
            long request = multipart.getMaxRequestSize();
            maxBytes = file >= 0 ? file : DEFAULT_MAX_BYTES;
            if (request >= 0) {
                maxBytes = Math.min(maxBytes, request - MULTIPART_OVERHEAD);
            }
        }
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE - MULTIPART_OVERHEAD || maxSeconds < 1) {
            throw new IllegalArgumentException("录音上传限额和录音时长必须为正数，且 multipart 请求需预留 1024 字节");
        }
        budget = new Budget((int)maxBytes, maxSeconds);
    }

    public Budget budget() {
        return budget;
    }

    boolean exceedsUploadLimit(long bytes) {
        return bytes > budget.maxBytes();
    }

    String uploadLimitMessage() {
        return "录音不能超过 " + budget.maxBytes() + " 字节";
    }
}
