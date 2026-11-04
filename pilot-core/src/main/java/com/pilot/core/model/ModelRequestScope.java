package com.pilot.core.model;

import java.util.function.Supplier;

/** 仅在当前调用栈传递用途和缓存分组，退出恢复，线程池之间不隐式继承。 */
public final class ModelRequestScope {
    public record State(String purpose, String cacheGroup, boolean toolsDisabled) {
    }

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private ModelRequestScope() {
    }

    public static State current() {
        State value = CURRENT.get();
        return value == null ? new State("", "", false) : value;
    }

    public static <T> T within(String purpose, String group, boolean toolsDisabled, Supplier<T> call) {
        State previous = CURRENT.get();
        State base = current();
        CURRENT.set(new State(purpose == null || purpose.isBlank() ? base.purpose() : purpose,
            group == null || group.isBlank() ? base.cacheGroup() : group, toolsDisabled || base.toolsDisabled()));
        try {
            return call.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
