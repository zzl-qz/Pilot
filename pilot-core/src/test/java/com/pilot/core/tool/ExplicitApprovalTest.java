package com.pilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.pilot.core.loop.ToolExecutor;
import com.pilot.core.message.ToolCall;
import com.pilot.core.permission.PermissionResult;

class ExplicitApprovalTest {
    @Test
    void broadAllowCannotBypassFinalActionApprovalAndFullContentIsShown() {
        var executions = new AtomicInteger();
        var description = new AtomicReference<String>();
        String finalContent = "recipient@example.test\n" + "最终正文".repeat(200);
        Tool action = new Tool() {
            public String name() {
                return "external_action";
            }

            public String description() {
                return "外部动作";
            }

            public Map<String, Object> parameters() {
                return Map.of();
            }

            public boolean readOnly() {
                return false;
            }

            public boolean confirm(Map<String, Object> args) {
                return true;
            }

            public String approvalDescription(Map<String, Object> args) {
                return finalContent;
            }

            public String execute(Map<String, Object> args) {
                executions.incrementAndGet();
                return "executed";
            }
        };
        var registry = new ToolRegistry();
        registry.register(action);
        var call = new ToolCall("a", action.name(), Map.of());
        var denied = new ToolExecutor(registry, (c, t) -> PermissionResult.allow(), (id, name, summary) -> {
            description.set(summary);
            return CompletableFuture.completedFuture(false);
        }).execute(call, event -> {
        });
        assertEquals(0, executions.get());
        assertEquals(finalContent, description.get());
        assertFalse(denied.usableEvidence());
        var approved = new ToolExecutor(registry, (c, t) -> PermissionResult.allow(),
            (id, name, summary) -> CompletableFuture.completedFuture(true)).execute(call, event -> {
            });
        assertTrue(approved.usableEvidence());
        assertEquals(1, executions.get());
    }
}
