package com.pilot.core.model;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/** 只排序对象键，绝不改变数组、消息和工具结果的语义顺序。 */
public final class CanonicalJson {
    private CanonicalJson() {
    }

    public static Object ordered(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), ordered(item)));
            return result;
        }
        if (value instanceof Iterable<?> items) {
            var result = new ArrayList<>();
            items.forEach(item -> result.add(ordered(item)));
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            var result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                result.add(ordered(Array.get(value, i)));
            }
            return result;
        }
        return value;
    }
}
