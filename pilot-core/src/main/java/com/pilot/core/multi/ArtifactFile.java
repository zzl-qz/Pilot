package com.pilot.core.multi;

import java.nio.charset.StandardCharsets;

/**
 * 清单里的一个文件条目。
 *
 * @param baselineHash 修改前的指纹；新建文件为空串
 * @param contentHash  候选内容的指纹
 * @param size         候选内容字节数
 */
public record ArtifactFile(String path, String baselineHash, String contentHash, long size) {

    public ArtifactFile {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        path = path.replace('\\', '/').strip();
        baselineHash = baselineHash == null ? "" : baselineHash.strip();
        contentHash = contentHash == null ? "" : contentHash.strip();
        if (size < 0) {
            throw new IllegalArgumentException("size must be >= 0");
        }
    }

    public static ArtifactFile of(String path, String baselineText, String content) {
        String text = content == null ? "" : content;
        return new ArtifactFile(path, DiffSandbox.fingerprint(baselineText), DiffSandbox.fingerprint(text),
                text.getBytes(StandardCharsets.UTF_8).length);
    }

    public boolean created() {
        return baselineHash.isEmpty();
    }
}
