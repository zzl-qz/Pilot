package com.pilot.core.trace;

import com.pilot.core.tool.ToolCall;

public interface TraceRecorder {
    void record(ToolCall call, String result, long latencyMs);
}
