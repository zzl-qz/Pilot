package com.pilot.core.session;

/** 只在进程存活期间恢复的当前输出；baseSequence 是捕获时已落库的高水位。 */
public record LiveOutput(String runId, String previewId, long previewOffset, long baseSequence, long historyRevision,
    String text, String reasoning, boolean complete) {
}
