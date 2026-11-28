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
    void scrubsDatabaseAndQueueUrlsQuotedCloudSecretsAndJwt() {
        String jwt = "eyJabcdefghijk.eyJabcdefghijk.signature";
        String raw = "postgres://user:pass1234@db/test mysql://u:p12345@db mongodb+srv://u:p12345@db "
            + "rediss://u:p12345@cache amqps://u:p12345@queue api_key='single-secret-value' "
            + "'secret': 'json-secret-value' \\\"api_key\\\":\\\"escaped-secret-value\\\" "
            + "AWS_SECRET_ACCESS_KEY=aws-secret-value AccountKey=azure-secret-value sig=sas-secret-value " + jwt;
        String scrubbed = SecretScrubber.scrub(raw);
        assertTrue(scrubbed.contains("***"), scrubbed);
        assertFalse(scrubbed.contains("escaped-secret-value"), scrubbed);

        for (String secret : List.of("pass1234", "p12345", "single-secret-value", "json-secret-value",
            "escaped-secret-value", "aws-secret-value", "azure-secret-value", "sas-secret-value", jwt)) {
            assertFalse(scrubbed.contains(secret), scrubbed);
        }
        assertTrue(SecretScrubber.containsSecretPattern(raw));
    }

    @Test
    void containsSecretPatternDoesNotFlagNormalJsonKeys() {
        assertFalse(SecretScrubber.containsSecretPattern("key: \"explorer\""));
        assertFalse(SecretScrubber.containsSecretPattern("process exited with sig=SIGTERM"));
        assertEquals("process exited with sig=SIGTERM", SecretScrubber.scrub("process exited with sig=SIGTERM"));
    }

    @Test
    void escapedJsonScrubbingPreservesUnrelatedEscapedQuotes() {
        String text = "note=\\\"keep\\\" \\\"api_key\\\":\\\"escaped-secret-value\\\"";
        String scrubbed = SecretScrubber.scrub(text);
        assertTrue(scrubbed.contains("note=\\\"keep\\\""), scrubbed);
        assertFalse(scrubbed.contains("escaped-secret-value"), scrubbed);
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

    @Test
    void scrubsCliFlagSecretsAndStaysIdempotent() {
        String raw =
            "curl --token my-secret-token --password=superpass --api-key=ak_123456 -secret hidden https://example.com";
        String scrubbed = SecretScrubber.scrub(raw);
        for (String leaked : List.of("my-secret-token", "superpass", "ak_123456", "hidden")) {
            assertFalse(scrubbed.contains(leaked), scrubbed);
        }
        assertTrue(scrubbed.contains("--token ***"), scrubbed);
        assertTrue(scrubbed.contains("--password=***"), scrubbed);
        assertTrue(scrubbed.contains("--api-key=***"), scrubbed);
        assertTrue(SecretScrubber.containsSecretPattern(raw));
        assertFalse(SecretScrubber.containsSecretPattern(scrubbed), "已脱敏文本不应重复触发敏感模式: " + scrubbed);
    }

    @Test
    void cliFlagScrubbingPreservesValidJsonAndDoesNotCrossNewlines() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String[] jsonCases = {"{\"cmd\":\"mysql --password=secret\"}",
            "{\"cmd\":\"curl --token mysecret\",\"ok\":true}", "{\"summary\":\"ran curl --token ***\",\"ok\":true}",
            "{\"cmd\":\"curl --token \\\"my secret\\\"\",\"ok\":true}",
            "{\"cmd\":\"curl --token=\",\"other\":\"value\"}", "{\"cmd\":\"curl --token \",\"other\":\"value\"}"};
        for (String json : jsonCases) {
            String scrubbed = SecretScrubber.scrub(json);
            mapper.readTree(scrubbed);
            assertEquals(scrubbed, SecretScrubber.scrub(scrubbed), "二次脱敏应幂等: " + scrubbed);
            assertFalse(SecretScrubber.containsSecretPattern(scrubbed), "脱敏后不应继续命中敏感模式: " + scrubbed);
        }
        assertFalse(SecretScrubber.scrub(jsonCases[0]).contains("secret"));
        assertFalse(SecretScrubber.scrub(jsonCases[1]).contains("mysecret"));
        assertEquals(jsonCases[2], SecretScrubber.scrub(jsonCases[2]));
        assertFalse(SecretScrubber.scrub(jsonCases[3]).contains("my secret"));

        String multiline = "Missing --token\nPlease pass one";
        assertEquals(multiline, SecretScrubber.scrub(multiline));
        assertFalse(SecretScrubber.containsSecretPattern(multiline));
        assertFalse(SecretScrubber.containsSecretPattern("app --token-limit 100"));

        String punctuated = SecretScrubber.scrub("curl --token \"abc\", done");
        assertEquals("curl --token ***, done", punctuated);
        assertEquals(punctuated, SecretScrubber.scrub(punctuated));
        assertFalse(SecretScrubber.containsSecretPattern(punctuated));
    }
}
