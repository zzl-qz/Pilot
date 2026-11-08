package com.pilot.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 附件请求的边界，作为单一事实源供服务层、HTTP 过滤器与移动端能力一起消费，
 * 避免不同入口出现互相矛盾的限额。
 *
 * <p>默认：单条消息最多 64 个文件、单文件 2MB、解码后总量 25MB。
 * 移动端受 12MB 请求体回执上限约束，能力值另行取 min(总量, 8MB)。
 */
@Component
public class AttachmentLimits {
    private final int maxFiles;
    private final int maxFileBytes;
    private final int maxTotalBytes;

    public AttachmentLimits(@Value("${pilot.attachments.max-files:64}") int maxFiles,
        @Value("${pilot.attachments.max-file-bytes:2097152}") int maxFileBytes,
        @Value("${pilot.attachments.max-total-bytes:26214400}") int maxTotalBytes) {
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public int maxFiles() {
        return maxFiles;
    }

    public int maxFileBytes() {
        return maxFileBytes;
    }

    public int maxTotalBytes() {
        return maxTotalBytes;
    }

    /** base64 会放大原始字节约 4/3；此值用于解码前的长度预检（另留 1KB 容 data URL 前缀）。 */
    public int maxEncodedFileChars() {
        return (maxFileBytes / 3) * 4 + 1024;
    }

    public static AttachmentLimits defaults() {
        return new AttachmentLimits(64, 2 * 1024 * 1024, 25 * 1024 * 1024);
    }
}
