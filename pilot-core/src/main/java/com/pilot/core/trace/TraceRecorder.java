package com.pilot.core.trace;

import com.pilot.core.message.ToolCall;

public interface TraceRecorder {
    void record(ToolCall call, String result, long latencyMs);
}
