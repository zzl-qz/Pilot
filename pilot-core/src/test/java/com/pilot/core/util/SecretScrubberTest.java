package com.pilot.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.model.diagnostics.ModelRequestDumper;
import com.pilot.core.model.diagnostics.ModelRequestScope;

import tools.jackson.databind.ObjectMapper;

class SecretScrubberTest {
    @Test
    void scrubsCommonTokensPrivateKeysAndEmbeddedUrlCredentials() {
        String raw = """
            Authorization: Bearer tok_abcdef1234567890
            Authorization: Basic dXNlcjpzZWNyZXQ=
            {"apiKey": "sk-proj-1234567890abcdef", "token": "ghp_1234567890abcdef"}
            aws=AKIAIOSFODNN7EXAMPLE google=AIzaSyA1234567890abcdefghijk
            slack=xoxb-1234567890-abcdef123456 gitlab=glpat-abcdef1234567890 hf=hf_abcdef1234567890
            url=https://alice:supersecret@proxy.example.com/v1
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAsecretbody
            -----END RSA PRIVATE KEY-----
            """;
        String scrubbed = SecretScrubber.scrub(raw);
        for (String leaked : List.of("tok_abcdef1234567890", "dXNlcjpzZWNyZXQ=", "sk-proj-1234567890abcdef",
            "ghp_1234567890abcdef", "AKIAIOSFODNN7EXAMPLE", "AIzaSyA1234567890abcdefghijk",
            "xoxb-1234567890-abcdef123456", "glpat-abcdef1234567890", "hf_abcdef1234567890", "supersecret",
            "MIIEowIBAAKCAQEAsecretbody")) {
            assertFalse(scrubbed.contains(leaked), "不应保留明文凭据 " + leaked + "：\n" + scrubbed);
        }
        assertTrue(scrubbed.contains("[REDACTED_PRIVATE_KEY]"), scrubbed);
        assertTrue(scrubbed.contains("https://alice:***@proxy.example.com/v1"), scrubbed);
    }

    @Test
    void leavesNormalDiagnosticTextIntact() {
        String text = "mvnw -pl pilot-core test → exit 0 (2294 tests passed)";
        assertEquals(text, SecretScrubber.scrub(text));
        assertFalse(SecretScrubber.containsSecretPattern(text));
    }

    @Test
    void modelRequestDumperScrubsSecretsInsideBodyTextAndEndpoint(@TempDir Path tempDir) throws Exception {
        ModelRequestDumper.configure(true, tempDir);
        try {
            ModelRequestScope.within("test", "", false, "ses_sec", "run_sec", () -> {
                ModelRequestDumper.recordIfEnabled(
                    URI.create("https://user:pass@api.example.com/v1/chat?api_key=sk-query123456"), new ObjectMapper(),
                    Map.of("api_key", "sk-field12345678", "messages", List.of(Map.of("role", "user", "content",
                        "Authorization: Bearer sk-inline12345678 and ghp_inline12345678"))));
                return null;
            });
        } finally {
            ModelRequestDumper.configure(false, null);
        }
        Path dumped = tempDir.resolve("debug-requests/ses_sec/run_sec/req-1.json");
        assertTrue(Files.isRegularFile(dumped));
        String content = Files.readString(dumped);
        assertFalse(content.contains("sk-query123456"), content);
        assertFalse(content.contains("sk-field12345678"), content);
        assertFalse(content.contains("sk-inline12345678"), content);
        assertFalse(content.contains("ghp_inline12345678"), content);
        assertFalse(content.contains("user:pass"), content);
        assertTrue(content.contains("[REDACTED]"), content);
    }
}
