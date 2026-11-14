package com.pilot.core.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 需要完整读入原文件的文本编辑工具共用的读取策略。 */
final class EditableText {
    private static final int MAX_MEBIBYTES = 32;
    static final int MAX_BYTES = MAX_MEBIBYTES * 1024 * 1024;

    private EditableText() {
    }

    static String readForReplacement(Path file, String displayPath) throws IOException {
        return read(file, "，无法整份替换。", "二进制文件，无法编辑: " + displayPath);
    }

    static String readForPatch(Path file, String displayPath) throws IOException {
        return read(file, ": " + displayPath, "二进制文件，无法修改: " + displayPath);
    }

    private static String read(Path file, String largeFileSuffix, String binaryMessage) throws IOException {
        if (Files.size(file) > MAX_BYTES) {
            throw tooLarge(largeFileSuffix);
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw tooLarge(largeFileSuffix);
        }
        if (ToolSupport.isBinary(bytes)) {
            throw new IllegalArgumentException(binaryMessage);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException tooLarge(String suffix) {
        return new IllegalArgumentException("文件过大（上限 " + MAX_MEBIBYTES + "MB）" + suffix);
    }
}
