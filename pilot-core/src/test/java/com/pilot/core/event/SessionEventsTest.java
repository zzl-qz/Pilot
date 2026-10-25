package com.pilot.core.event;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionEventsTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void runStarted() {
        assertSerialized(new RunStarted("修复构建"), "run.started", """
                {"input":"修复构建"}""");
    }

    @Test
    void assistantDelta() {
        assertSerialized(new AssistantDelta("正在读取文件"), "assistant.delta", """
                {"text":"正在读取文件"}""");
    }

    @Test
    void toolStarted() {
        assertSerialized(new ToolStarted("call_1", "read_file", "AgentLoop.java"), "tool.started", """
                {"callId":"call_1","name":"read_file","summary":"AgentLoop.java"}""");
    }

    @Test
    void approvalRequired() {
        assertSerialized(new ApprovalRequired("call_2", "write_file", "+18 -4"), "approval.required", """
                {"callId":"call_2","name":"write_file","argsSummary":"+18 -4"}""");
    }

    @Test
    void approvalResolved() {
        assertSerialized(new ApprovalResolved("call_2", ApprovalDecision.DENIED), "approval.resolved", """
                {"callId":"call_2","decision":"DENIED"}""");
    }

    @Test
    void toolCompleted() {
        assertSerialized(new ToolCompleted("call_1", "读取 42 行", 128L), "tool.completed", """
                {"callId":"call_1","resultSummary":"读取 42 行","latencyMs":128}""");
    }

    @Test
    void runFailed() {
        assertSerialized(new RunFailed("MODEL_UNAVAILABLE", "模型端点不可用", true), "run.failed", """
                {"code":"MODEL_UNAVAILABLE","message":"模型端点不可用","retryable":true}""");
    }

    @Test
    void runCompleted() {
        assertSerialized(new RunCompleted("完成", List.of("AgentLoop.java"), List.of("mvn test")), "run.completed", """
                {"summary":"完成","changedFiles":["AgentLoop.java"],"checks":["mvn test"]}""");
    }

    private void assertSerialized(SessionEvent event, String expectedType, String expectedJson) {
        assertEquals(expectedType, event.type().wireName());
        assertEquals(json.readTree(expectedJson), json.readTree(json.writeValueAsString(event)));
    }
}
