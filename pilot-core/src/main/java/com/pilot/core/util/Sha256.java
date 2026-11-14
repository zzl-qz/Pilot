package com.pilot.core.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 的小写十六进制表示，文本固定使用 UTF-8。 */
public final class Sha256 {
    private Sha256() {
    }

    public static String hex(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    public static String hex(Path file, OpenOption... options) throws IOException {
        MessageDigest hash = digest();
        try (var input = new DigestInputStream(Files.newInputStream(file, options), hash)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
