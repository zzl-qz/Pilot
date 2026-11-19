package com.pilot.core.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 有限选项时停下等用户点选或自行填写，答案回到当前任务。 */
public final class AskUserTool implements Tool {
    public static final String NAME = "ask_user";
    private static final long WAIT_MINUTES = 10;

    private final QuestionBroker broker;

    public AskUserTool(QuestionBroker broker) {
        this.broker = broker;
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
        return ToolSchemas.object(Map.of("question", ToolSchemas.string("要问的问题"), "options",
            Map.of("type", "array", "items", Map.of("type", "string"), "description", "至少两个选项")),
            List.of("question", "options"));
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> args) {
        String question = args == null || args.get("question") == null ? "" : String.valueOf(args.get("question")).strip();
        List<String> options = options(args == null ? null : args.get("options"));
        if (question.isBlank() || options.size() < 2) {
            return "ERROR: ask_user 需要问题和至少两个选项";
        }
        String callId = ToolCallScope.callId();
        if (callId.isBlank()) {
            return "ERROR: 缺少调用编号，无法等待用户选择";
        }
        try {
            String choice = broker.register(callId).get(WAIT_MINUTES, TimeUnit.MINUTES);
            return choice == null || choice.isBlank() ? "ERROR: 用户没有回答" : "用户选择：" + choice;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: 等待用户选择时被中断";
        } catch (Exception e) {
            return "ERROR: 用户没有在限定时间内回答";
        }
    }

    private static List<String> options(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(value -> value == null ? "" : String.valueOf(value).strip()).filter(value -> !value.isBlank())
            .toList();
    }
}
