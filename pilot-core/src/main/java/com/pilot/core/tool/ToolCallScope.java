package com.pilot.core.tool;

/** 当前工具调用编号。只在执行该调用的线程上可见。 */
public final class ToolCallScope {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ToolCallScope() {
    }

    public static void bind(String callId) {
        CURRENT.set(callId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String callId() {
        String id = CURRENT.get();
        return id == null ? "" : id;
    }
}
