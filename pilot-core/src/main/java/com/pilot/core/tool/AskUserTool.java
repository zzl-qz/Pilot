package com.pilot.core.tool;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 有限选项时停下等用户点选或自行填写，答案回到当前任务。 */
public final class AskUserTool implements Tool {
    public static final String NAME = "ask_user";
    private static final long WAIT_MINUTES = 10;

    private final QuestionBroker broker;
    private final String sessionId;

    public AskUserTool(QuestionBroker broker) {
        this(broker, "");
    }

    public AskUserTool(QuestionBroker broker, String sessionId) {
        this.broker = broker;
        this.sessionId = sessionId == null ? "" : sessionId.strip();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "需要用户从至少两个明确选项里选择时使用。用户也可以自行填写。不要在正文里把选项再问一遍。";
    }

    @Override
    public Map<String, Object> parameters() {
        return ToolSchemas.object(
            Map.of("question", ToolSchemas.string("要问的问题"), "options",
                Map.of("type", "array", "items", Map.of("type", "string"), "description", "至少两个选项")),
            List.of("question", "options"));
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        return execute(args, Duration.ofMinutes(WAIT_MINUTES));
    }

    String execute(Map<String, Object> args, Duration waitTimeout) {
        String question =
            args == null || args.get("question") == null ? "" : String.valueOf(args.get("question")).strip();
        List<String> options = options(args == null ? null : args.get("options"));
        if (question.isBlank() || options.size() < 2) {
            return "ERROR: ask_user 需要问题和至少两个选项";
        }
        if (placeholder(question, options)) {
            return "ERROR: 问题还没写具体，选项也不能用 a、b 这种占位。写清要用户决定的事，并给出真实选项。";
        }
        String callId = ToolCallScope.callId();
        if (callId.isBlank()) {
            return "ERROR: 缺少调用编号，无法等待用户选择";
        }
        Duration effectiveTimeout = waitTimeout == null || waitTimeout.isNegative() || waitTimeout.isZero()
            ? Duration.ofMinutes(WAIT_MINUTES) : waitTimeout;
        try {
            String choice = broker.register(callId, sessionId, question, options, effectiveTimeout)
                .get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return choice == null || choice.isBlank() ? "ERROR: 等待用户选择已取消" : "用户选择：" + choice;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            broker.cancel(callId);
            return "ERROR: 等待用户选择时被中断";
        } catch (TimeoutException e) {
            broker.cancel(callId);
            return "ERROR: 用户没有在限定时间内回答";
        } catch (Exception e) {
            broker.cancel(callId);
            return "ERROR: 等待用户选择失败";
        }
    }

    /** 占位提问会把用户卡住，又不能推进任务。 */
    private static boolean placeholder(String question, List<String> options) {
        if (question.contains("占位") || question.contains("稍后")) {
            return true;
        }
        return options.stream().allMatch(option -> option.length() == 1);
    }

    private static List<String> options(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(value -> value == null ? "" : String.valueOf(value).strip())
            .filter(value -> !value.isBlank()).toList();
    }
}
