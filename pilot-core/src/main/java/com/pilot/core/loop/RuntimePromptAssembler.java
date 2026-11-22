package com.pilot.core.loop;

import java.util.ArrayList;
import java.util.List;

import com.pilot.core.message.Message;

/** 为单次模型请求追加临时运行提示，不修改会话历史或保存运行状态。 */
public final class RuntimePromptAssembler {
    public record Input(List<Message> baseMessages, String runtimeGuidance, String phaseHint, String pathRules) {
        public Input {
            baseMessages = List.copyOf(baseMessages == null ? List.of() : baseMessages);
            runtimeGuidance = runtimeGuidance == null ? "" : runtimeGuidance;
            phaseHint = phaseHint == null ? "" : phaseHint;
            pathRules = pathRules == null ? "" : pathRules;
        }
    }

    public List<Message> assemble(Input input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (input.runtimeGuidance().isBlank() && input.phaseHint().isBlank() && input.pathRules().isBlank()) {
            return input.baseMessages();
        }
        List<Message> request = new ArrayList<>(input.baseMessages());
        if (!input.phaseHint().isBlank()) {
            request.add(Message.system(input.phaseHint()));
        }
        appendGuidance(request, input.runtimeGuidance());
        if (!input.pathRules().isBlank()) {
            request.add(Message.system(input.pathRules()));
        }
        return List.copyOf(request);
    }

    private static void appendGuidance(List<Message> request, String guidance) {
        String trimmed = guidance.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.startsWith(Message.RUNTIME_CHECKPOINT_MARKER)) {
            request.add(Message.user(trimmed));
            return;
        }
        int marker = trimmed.indexOf("\n\n" + Message.RUNTIME_CHECKPOINT_MARKER);
        if (marker >= 0) {
            String before = trimmed.substring(0, marker).strip();
            String checkpoint = trimmed.substring(marker + 2).strip();
            if (!before.isEmpty()) {
                request.add(Message.system("[Runtime guidance; applies only to this request]\n" + before));
            }
            request.add(Message.user(checkpoint));
            return;
        }
        request.add(Message.system("[Runtime guidance; applies only to this request]\n" + trimmed));
    }
}
