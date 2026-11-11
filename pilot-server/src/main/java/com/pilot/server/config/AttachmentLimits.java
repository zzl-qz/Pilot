package com.pilot.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 附件请求的边界，作为单一事实源供服务层、HTTP 过滤器与移动端能力一起消费，
 * 避免不同入口出现互相矛盾的限额。
 *
 * <p>默认：单条消息最多 64 个文件、单文件 2MB、解码后总量 25MB；
 * 文本附件内联超过 20000 字符时降采样压缩（保留头尾）。
 * 移动端受传输与请求回执上限约束，最多 16 个文件、总量 6MB。
 */
@Component
public class AttachmentLimits {
    private final int maxFiles;
    private final int maxFileBytes;
    private final int maxTotalBytes;
    private final int inlineMaxChars;

    public AttachmentLimits(@Value("${pilot.attachments.max-files:64}") int maxFiles,
        @Value("${pilot.attachments.max-file-bytes:2097152}") int maxFileBytes,
        @Value("${pilot.attachments.max-total-bytes:26214400}") int maxTotalBytes,
        @Value("${pilot.attachments.inline-max-chars:20000}") int inlineMaxChars) {
        if (maxFiles < 1 || maxFileBytes < 1 || maxTotalBytes < 1 || inlineMaxChars < 1) {
            throw new IllegalArgumentException("附件数量、字节和内联预算必须大于 0");
        }
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.inlineMaxChars = inlineMaxChars;
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

    /** 内联进消息正文的单文件字符预算；超过则保留头尾、省略中段。 */
    public int inlineMaxChars() {
        return inlineMaxChars;
    }

    /** base64 会放大原始字节约 4/3；此值用于解码前的长度预检（另留 1KB 容 data URL 前缀）。 */
    public long maxEncodedFileChars() {
        return ((maxFileBytes + 2L) / 3L) * 4L + 1024L;
    }

    public long maxRequestBytes() {
        return ((maxTotalBytes + 2L) / 3L) * 4L + maxFiles * 1024L + 2_000_000L;
    }

    public record Budget(int maxFiles, int maxFileBytes, int maxTotalBytes, long maxRequestBytes) {
    }

    public Budget budget() {
        return new Budget(maxFiles, maxFileBytes, maxTotalBytes, maxRequestBytes());
    }

    public AttachmentLimits mobile() {
        return new AttachmentLimits(Math.min(maxFiles, 16), Math.min(maxFileBytes, 2 * 1024 * 1024),
            Math.min(maxTotalBytes, 6 * 1024 * 1024), inlineMaxChars);
    }

    public static AttachmentLimits defaults() {
        return new AttachmentLimits(64, 2 * 1024 * 1024, 25 * 1024 * 1024, 20_000);
    }
}
