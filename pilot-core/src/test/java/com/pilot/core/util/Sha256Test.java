package com.pilot.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Sha256Test {
    @TempDir
    Path directory;

    @Test
    void combinesRawFileBytesWithMetadataInOrder() throws IOException {
        Path file = directory.resolve("摘要 文件");
        byte[] body = {(byte)0xff, 0, 42};
        Files.write(file, body);
        var digest = Sha256.newDigest();
        digest.update("a".getBytes(StandardCharsets.UTF_8));
        Sha256.update(digest, file);
        digest.update((byte)'z');
        assertEquals(Sha256.hex(new byte[] {'a', (byte)0xff, 0, 42, 'z'}), Sha256.finishHex(digest));
        assertEquals(Sha256.hex(body), Sha256.hex(file));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"));
    }
}
