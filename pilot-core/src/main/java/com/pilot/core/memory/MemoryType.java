package com.pilot.core.memory;

import java.util.Locale;

/** 闭合的长期记忆分类：只允许这四种类型，防止开放分类导致检索噪声和索引膨胀。 */
public enum MemoryType {
    USER("user", "用户画像"), FEEDBACK("feedback", "偏好与经验"), PROJECT("project", "项目决策"), REFERENCE("reference", "外部引用");

    private final String wire;
    private final String label;

    MemoryType(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public String wire() {
        return wire;
    }

    public String label() {
        return label;
    }

    public static MemoryType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("记忆类型不能为空（支持 user / feedback / project / reference）");
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        for (MemoryType value : values()) {
            if (value.wire.equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("不支持的记忆类型：" + raw + "（支持 user / feedback / project / reference）");
    }

    public static MemoryType parseOrDefault(String raw, MemoryType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return parse(raw);
    }
}
