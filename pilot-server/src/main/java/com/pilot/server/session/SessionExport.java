package com.pilot.server.session;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.RunCompleted;
import com.pilot.core.event.RunContinued;
import com.pilot.core.event.RunEnded;
import com.pilot.core.event.RunFailed;
import com.pilot.core.event.ToolCompleted;
import com.pilot.core.event.ToolStarted;
import com.pilot.core.message.Message;
import com.pilot.core.session.Session;

/**
 * 把一次会话整理成可以转发的 Markdown。复查的人不用打开 Pilot，也能看到失败、工具和对话。
 */
public final class SessionExport {
    private static final int RESULT_CHARS = 500;

    private SessionExport() {
    }

    public static String fileName(String title, LocalDate date) {
        String raw = title == null ? "" : title;
        String safe = raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}\\s]+", "-").replaceAll("^-+|-+$", "");
        if (safe.length() > 40) {
            safe = safe.substring(0, 40).replaceAll("-+$", "");
        }
        if (safe.isBlank()) {
            safe = "对话";
        }
        LocalDate day = date == null ? LocalDate.now(ZoneOffset.UTC) : date;
        return "pilot-" + safe + "-" + day + ".md";
    }

    public static String markdown(Session session, List<Message> messages, List<EventEnvelope> events, Instant exportedAt) {
        StringBuilder out = new StringBuilder();
        String title = session.title() == null || session.title().isBlank() ? "未命名对话" : session.title();
        out.append("# ").append(title).append("\n\n");
        out.append("- 会话 ID：`").append(session.id()).append("`\n");
        out.append("- 状态：").append(session.status()).append("\n");
        if (session.workspace() != null && !session.workspace().isBlank()) {
            out.append("- 工作区：`").append(session.workspace()).append("`\n");
        }
        if (exportedAt != null) {
            out.append("- 导出时间：").append(exportedAt).append("\n");
        }
        out.append("\n## 复查摘要\n\n");
        List<String> failures = new ArrayList<>();
        Map<String, Integer> continued = new LinkedHashMap<>();
        Map<String, ToolStarted> tools = new LinkedHashMap<>();
        List<String> toolLines = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        for (EventEnvelope event : events == null ? List.<EventEnvelope>of() : events) {
            if (event.payload() instanceof RunFailed failed) {
                failures.add(clip(failed.code() + "：" + failed.message()));
            } else if (event.payload() instanceof RunContinued again) {
                continued.merge(again.reason(), 1, Integer::sum);
            } else if (event.payload() instanceof ToolStarted started) {
                tools.put(started.callId(), started);
            } else if (event.payload() instanceof ToolCompleted completed) {
                ToolStarted started = tools.get(completed.callId());
                String name = started == null ? completed.callId() : started.name();
                String summary = started == null ? "" : started.summary();
                toolLines.add("- " + name + (summary == null || summary.isBlank() ? "" : "：" + clip(summary)) + "（"
                    + completed.latencyMs() + " ms）\n  结果：" + clip(completed.resultSummary()));
            } else if (event.payload() instanceof RunCompleted completed) {
                outcomes.add("- 完成：" + clip(completed.summary()));
            } else if (event.payload() instanceof RunEnded ended) {
                outcomes.add("- 结束 " + ended.outcome() + "：" + clip(ended.summary()));
            }
        }
        if (failures.isEmpty()) {
            out.append("这次记录里没有运行失败。\n");
        } else {
            out.append("失败：\n");
            for (String failure : failures) {
                out.append("- ").append(failure).append("\n");
            }
        }
        if (!continued.isEmpty()) {
            out.append("\n自动处理：\n");
            continued.forEach((reason, count) -> out.append("- ").append(clip(reason)).append(" × ").append(count).append("\n"));
        }
        out.append("\n## 对话\n\n");
        int shown = 0;
        for (Message message : messages == null ? List.<Message>of() : messages) {
            if ("system".equals(message.role()) || message.content().contains("[Runtime task context]")) {
                continue;
            }
            if ("user".equals(message.role())) {
                shown++;
                out.append("## 你\n\n").append(clip(message.content(), 4000)).append("\n\n");
            } else if ("assistant".equals(message.role()) && !message.content().isBlank()) {
                shown++;
                out.append("## Pilot\n\n").append(clip(message.content(), 4000)).append("\n\n");
            }
        }
        if (shown == 0) {
            out.append("这次会话还没有消息。\n\n");
        }
        out.append("## 工具\n\n");
        if (toolLines.isEmpty()) {
            out.append("没有工具调用。\n\n");
        } else {
            for (String line : toolLines) {
                out.append(line).append("\n");
            }
            out.append("\n");
        }
        out.append("## 运行结果\n\n");
        if (outcomes.isEmpty()) {
            out.append("没有单独的完成或结束记录。\n");
        } else {
            for (String outcome : outcomes) {
                out.append(outcome).append("\n");
            }
        }
        return redact(out.toString());
    }

    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll("sk-[A-Za-z0-9]{8,}", "sk-***")
            .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}", "$1***")
            .replaceAll("(?i)((?:api[_-]?key|token|secret|password|passwd)\\s*[=:]\\s*)[A-Za-z0-9._\\-]{4,}", "$1***");
    }

    private static String clip(String text) {
        return clip(text, RESULT_CHARS);
    }

    private static String clip(String text, int limit) {
        String value = text == null ? "" : text.strip().replace("\n", " ");
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "…";
    }
}
