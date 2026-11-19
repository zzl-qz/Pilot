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
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.message.Message;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionStatus;

class SessionExportTest {
    @Test
    void emptySessionStillExplainsThatThereAreNoMessages() {
        String text = SessionExport.markdown(session("空的"), List.of(), List.of(), Instant.parse("2026-11-19T04:00:00Z"));

        assertTrue(text.contains("这次会话还没有消息。"), text);
        assertTrue(text.contains("ses_1"), text);
    }

    @Test
    void reviewSummaryIncludesFailureAndToolWithoutSecrets() {
        Session session = session("排查 a/b:c");
        List<EventEnvelope> events = List.of(
            envelope(1, new RunFailed("CONTEXT_TOO_LARGE", "密钥 sk-abcdefghijklmnopqrstuvwxyz", false)),
            envelope(2, new RunContinued(1, 2, "上下文超过安全输入预算")),
            envelope(3, new ToolStarted("call_1", "run_command", "password=hunter2hunter2")),
            envelope(4, new ToolCompleted("call_1", "退出码 1", 1200)));

        String text = SessionExport.markdown(session, List.of(Message.user("看一下")), events,
            Instant.parse("2026-11-19T04:00:00Z"));

        assertTrue(text.contains("CONTEXT_TOO_LARGE"), text);
        assertTrue(text.contains("run_command"), text);
        assertTrue(text.contains("1200 ms"), text);
        assertTrue(text.contains("## 你"), text);
        assertFalse(text.contains("sk-abcdefghijklmnopqrstuvwxyz"), text);
        assertFalse(text.contains("hunter2hunter2"), text);
        assertTrue(text.contains("sk-***"), text);
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

    private static EventEnvelope envelope(long sequence, com.pilot.core.event.SessionEvent event) {
        return new EventEnvelope(sequence, Instant.parse("2026-11-19T01:00:00Z"), event.type().wireName(), "ses_1",
            "run_1", event);
    }
}
