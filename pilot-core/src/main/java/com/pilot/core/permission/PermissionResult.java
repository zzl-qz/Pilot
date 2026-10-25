package com.pilot.core.permission;

/** 权限检查结果：决策加上给用户/模型看的原因。 */
public record PermissionResult(PermissionDecision decision, String reason) {
    public PermissionResult {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        reason = reason == null ? "" : reason;
    }

    public static PermissionResult allow() {
        return new PermissionResult(PermissionDecision.ALLOW, "");
    }

    public static PermissionResult deny(String reason) {
        return new PermissionResult(PermissionDecision.DENY, reason);
    }

    public static PermissionResult ask(String reason) {
        return new PermissionResult(PermissionDecision.ASK, reason);
    }

    public boolean isAllowed() {
        return decision == PermissionDecision.ALLOW;
    }
}
