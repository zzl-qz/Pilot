package com.pilot.server.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedactionTest {

    @Test
    void masksBearerTokens() {
        String masked = Redaction.text("Authorization: Bearer abcdef1234567890");
        assertFalse(masked.contains("abcdef1234567890"), masked);
        assertTrue(masked.contains("***"));
    }

    @Test
    void masksKeyValuePairs() {
        String masked = Redaction.text("curl https://api.example.com?api_key=abcd1234efgh&x=1");
        assertFalse(masked.contains("abcd1234efgh"), masked);
    }

    @Test
    void masksKnownKeyPrefixes() {
        assertFalse(Redaction.text("key sk-abcdefghijklmnop").contains("abcdefghijklmnop"));
        assertFalse(Redaction.text("token ghp_abcdefghijklmnop").contains("abcdefghijklmnop"));
    }

    @Test
    void keepsOrdinaryTextReadable() {
        String text = "mvn -q test → exit 1；查看 surefire 报告";
        assertTrue(Redaction.text(text).equals(text));
    }

    @Test
    void truncatesVeryLongText() {
        String longText = "x".repeat(5_000);
        assertTrue(Redaction.text(longText).length() <= 2_001);
    }
}
