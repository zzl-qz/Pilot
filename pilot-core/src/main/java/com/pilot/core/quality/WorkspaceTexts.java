package com.pilot.core.quality;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.pilot.core.util.Texts;
import com.pilot.core.workspace.PathValidator;

/**
 * 安全有界的工作区源码读取器。
 * 拒绝符号链接、超大文件、二进制与非 UTF-8 编码，使用有界流读取防止并发膨胀导致 OOM。
 */
public final class WorkspaceTexts {
    /** 单文件最大读取上限（与运行快照对齐）。 */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    private WorkspaceTexts() {
    }

    public static String read(Path workspace, String relativePath) {
        return readBounded(workspace, relativePath, MAX_BYTES);
    }

    static String readBounded(Path workspace, String relativePath, int maxBytes) {
        if (workspace == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        try {
            Path file = new PathValidator(workspace).resolve(relativePath);
            return readRegularFile(file, maxBytes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String readRegularFile(Path file, int maxBytes) {
        if (file == null || maxBytes <= 0 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes || Texts.isBinary(bytes)) {
                return null;
            }
            return decodeUtf8(bytes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
