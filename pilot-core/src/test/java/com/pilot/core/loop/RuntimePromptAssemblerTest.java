package com.pilot.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pilot.core.message.Message;

class RuntimePromptAssemblerTest {
    private final RuntimePromptAssembler assembler = new RuntimePromptAssembler();

    @Test
    void appendsTemporaryMessagesWithoutMutatingHistory() {
        List<Message> history = new ArrayList<>(List.of(Message.user("原始输入")));
        List<Message> request =
            assembler.assemble(new RuntimePromptAssembler.Input(history, "提醒模型", "阶段提示", "[路径规则]\n仅限 src"));

        assertEquals(1, history.size());
        assertEquals(4, request.size());
        assertThrows(UnsupportedOperationException.class, () -> request.add(Message.user("禁止修改")));
    }

    @Test
    void preservesCheckpointAsTemporaryUserMessage() {
        List<Message> request = assembler.assemble(
            new RuntimePromptAssembler.Input(List.of(), "说明\n\n" + Message.RUNTIME_CHECKPOINT_MARKER + " 收敛", "", ""));

        assertEquals(2, request.size());
        assertEquals("system", request.get(0).role());
        assertEquals("user", request.get(1).role());
    }
}
