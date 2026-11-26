package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.SessionEvent;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.message.Message;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionStatus;

class SessionExportTest {
    @Test
    void emptySessionStillExplainsThatThereAreNoMessages() {
        String text =
            SessionExport.markdown(session("空的"), List.of(), List.of(), Instant.parse("2026-11-19T04:00:00Z"));

        assertTrue(text.contains("这次会话还没有消息。"), text);
        assertTrue(text.contains("ses_1"), text);
    }

    @Test
    void reviewSummaryIncludesFailureAndToolWithoutSecrets() {
        Session session = session("排查 a/b:c");
        List<EventEnvelope> events =
            List.of(envelope(1, new RunFailed("CONTEXT_TOO_LARGE", "密钥 sk-abcdefghijklmnopqrstuvwxyz", false)),
                envelope(2, new RunContinued(1, 2, "上下文超过安全输入预算")),
                envelope(3, new ToolStarted("call_1", "run_command", "password=hunter2hunter2")),
                envelope(4, new ToolCompleted("call_1", "退出码 1", 1200)));

        String text = SessionExport.markdown(session, List.of(Message.user("看一下")), events,
            Instant.parse("2026-11-19T04:00:00Z"));

        assertTrue(text.contains("CONTEXT_TOO_LARGE"), text);
        assertTrue(text.contains("run_command"), text);
        assertTrue(text.contains("1200 ms"), text);
        assertTrue(text.contains("### 你"), text);
        assertFalse(text.contains("sk-abcdefghijklmnopqrstuvwxyz"), text);
        assertFalse(text.contains("hunter2hunter2"), text);
        assertTrue(text.contains("密钥 ***"), text);
    }

    @Test
    void preservesMultilineContentAndClosesUnterminatedFences() {
        String body = "标题\n\n- 项目\n\n行内 ```code``` 不触发围栏\n\n```java\nString value = 1;";
        String text = SessionExport.markdown(session("归档"), List.of(Message.user(body)),
            List.of(envelope(1, new ToolStarted("c1", "run_command", "第一行\n第二行")),
                envelope(2, new ToolCompleted("c1", "通过\n详情", 12))),
            Instant.parse("2026-11-19T04:00:00Z"));

        assertTrue(text.contains("## 对话\n\n### 你\n\n" + body + "\n```"), text);
        assertTrue(text.contains("- run_command：第一行 第二行（12 ms）\n  结果：通过 详情"), text);
    }

    @Test
    void redactsProviderKeysJsonCredentialsAndPasswordUrlsWithoutBreakingNormalKeys() {
        String text = SessionExport.redact("sk-proj-abcdefghijk sk-ant_abcdefghijk ghp_abcdefghijk "
            + "github_pat_abcdefghijk AIza123456789012345678901 AKIA1234567890123456 "
            + "{\"password\":\"very-secret-value\",\"apiKey\":\"secret-api-999\"} "
            + "'token': 'single-quoted-tok' key: \"explorer\" https://user:very-secret-pass@example.com/path");

        assertFalse(text.contains("abcdefghijk"), text);
        assertFalse(text.contains("very-secret"), text);
        assertFalse(text.contains("secret-api-999"), text);
        assertFalse(text.contains("single-quoted-tok"), text);
        assertFalse(text.contains("123456789012345678901"), text);
        assertTrue(text.contains("key: \"explorer\""), text);
        assertTrue(text.contains("https://user:***@example.com/path"), text);
    }

    @Test
    void redactsConversationAndToolOutputCredentials() {
        String message = "api_key='single-secret-value' \\\"api_key\\\":\\\"escaped-secret-value\\\"";
        String summary = "AWS_SECRET_ACCESS_KEY=aws-secret-value AccountKey=azure-secret-value sig=sas-secret-value "
            + "eyJabcdefghijk.eyJabcdefghijk.signature";
        String text = SessionExport.markdown(session("export"), List.of(Message.user(message)),
            List.of(envelope(1, new ToolCompleted("c1", summary, 1))), Instant.parse("2026-11-19T04:00:00Z"));
        for (String secret : List.of("single-secret-value", "escaped-secret-value", "aws-secret-value",
            "azure-secret-value", "sas-secret-value", "eyJabcdefghijk.eyJabcdefghijk.signature")) {
            assertFalse(text.contains(secret), text);
        }
    }

    @Test
    void fileNameDropsCharactersThatBreakOnWindowsMacAndLinux() {
        assertTrue(SessionExport.fileName("图片 a/b:c*?\"<>|", LocalDate.of(2026, 11, 19)).startsWith("pilot-图片-a-b-c-"));
        assertTrue(SessionExport.fileName("图片 a/b:c*?\"<>|", LocalDate.of(2026, 11, 19)).endsWith("-2026-11-19.md"));
        assertTrue(SessionExport.fileName("   ", LocalDate.of(2026, 11, 19)).startsWith("pilot-对话-"));
    }

    private static Session session(String title) {
        return new Session("ses_1", title, Instant.parse("2026-11-19T00:00:00Z"), SessionStatus.INTERRUPTED, "/work");
    }

    private static EventEnvelope envelope(long sequence, SessionEvent event) {
        return new EventEnvelope(sequence, Instant.parse("2026-11-19T01:00:00Z"), event.type().wireName(), "ses_1",
            "run_1", event);
    }
}
