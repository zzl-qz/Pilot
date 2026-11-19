package com.pilot.core.tool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 点选问题的等待表。工具线程阻塞，界面提交选项或自行填写的文字后唤醒。 */
public final class QuestionBroker {
    private final ConcurrentMap<String, CompletableFuture<String>> waiting = new ConcurrentHashMap<>();

    public CompletableFuture<String> register(String callId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (waiting.putIfAbsent(callId, future) != null) {
            throw new IllegalStateException("问题已经在等待：" + callId);
        }
        return future;
    }

    public boolean answer(String callId, String choice) {
        if (callId == null || choice == null || choice.isBlank()) {
            return false;
        }
        CompletableFuture<String> future = waiting.remove(callId);
        if (future == null) {
            return false;
        }
        future.complete(choice.strip());
        return true;
    }
}
