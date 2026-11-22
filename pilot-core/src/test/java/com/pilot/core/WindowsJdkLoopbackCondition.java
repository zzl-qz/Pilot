package com.pilot.core;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class WindowsJdkLoopbackCondition implements ExecutionCondition {
    private static final String DISABLED_REASON =
        "Windows JDK 24/25 HttpClient selector initialization fails to establish its internal loopback pipe";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        boolean affectedWindowsJdk = System.getProperty("os.name", "").toLowerCase().contains("windows")
            && (Runtime.version().feature() == 24 || Runtime.version().feature() == 25);
        return affectedWindowsJdk ? ConditionEvaluationResult.disabled(DISABLED_REASON)
            : ConditionEvaluationResult.enabled("Loopback tests supported on this platform and JDK");
    }
}
