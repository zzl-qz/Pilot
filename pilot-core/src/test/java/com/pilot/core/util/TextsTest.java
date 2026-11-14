package com.pilot.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class TextsTest {

    @Test
    void oneLineCollapsesWhitespaceAndTruncates() {
        assertEquals("", Texts.oneLine(null, 10));
        assertEquals("", Texts.oneLine("  \n\t ", 10));
        assertEquals("a b c", Texts.oneLine("  a\n\n b\t c  ", 10));
        assertEquals("abcde", Texts.oneLine("abcde", 5));
        assertEquals("abcde…", Texts.oneLine("abcdef", 5));
        assertEquals("中文 换行…", Texts.oneLine("中文\n换行后面还有", 5));
    }

    @Test
    void binaryMeansNulWithinTheSample() {
        assertFalse(Texts.isBinary(new byte[0]));
        assertFalse(Texts.isBinary("纯文本 plain".getBytes(StandardCharsets.UTF_8)));
        assertTrue(Texts.isBinary(new byte[] {'a', 0, 'b'}));

        byte[] nulAtEdge = filled(Texts.BINARY_SAMPLE_BYTES);
        nulAtEdge[Texts.BINARY_SAMPLE_BYTES - 1] = 0;
        assertTrue(Texts.isBinary(nulAtEdge));

        byte[] nulBeyondSample = filled(Texts.BINARY_SAMPLE_BYTES + 1);
        nulBeyondSample[Texts.BINARY_SAMPLE_BYTES] = 0;
        assertFalse(Texts.isBinary(nulBeyondSample));
    }

    private static byte[] filled(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte)'x');
        return bytes;
    }
}
