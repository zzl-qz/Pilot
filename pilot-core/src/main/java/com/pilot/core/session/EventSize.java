package com.pilot.core.session;

import java.lang.reflect.RecordComponent;
import java.util.Map;

/** UTF-8 编码的保守预算，不依赖传输层或完整 JSON 序列化。 */
public final class EventSize {
    private EventSize() {}
    public static long estimate(Object value) {
        if (value == null) return 4;
        if (value instanceof String text) return 6L * text.length() + 2; // 包含 JSON 转义的最坏情况
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return 64;
        if (value instanceof Iterable<?> items) {
            long size = 2; for (Object item : items) size += estimate(item) + 1; return size;
        }
        if (value instanceof Map<?, ?> map) {
            long size = 2; for (var entry : map.entrySet()) size += estimate(entry.getKey()) + estimate(entry.getValue()) + 2; return size;
        }
        if (value.getClass().isRecord()) {
            long size = 2;
            for (RecordComponent part : value.getClass().getRecordComponents()) {
                try { size += part.getName().length() + 4 + estimate(part.getAccessor().invoke(value)); }
                catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("无法计算事件预算", failure); }
            }
            return size;
        }
        return 6L * value.toString().length() + 64;
    }
}
