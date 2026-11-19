package com.pilot.core.loop;

import java.util.List;

import com.pilot.core.message.Message;
import com.pilot.core.model.RequestBudget;
import com.pilot.core.model.ToolDefinition;

/** 收尾催办会不会把已经接近窗口的请求顶爆。 */
final class CompletionPressure {
    private CompletionPressure() {
    }

    static boolean tooFull(List<Message> messages, List<ToolDefinition> tools, int contextLimit) {
        RequestBudget.Assessment admission = RequestBudget.assessRequest(messages, tools, contextLimit);
        return admission.inputLimit() > 0 && admission.estimatedInputTokens() * 100 > admission.inputLimit() * 88;
    }
}
