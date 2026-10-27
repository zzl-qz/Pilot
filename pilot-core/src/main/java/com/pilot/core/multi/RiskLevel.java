package com.pilot.core.multi;

/**
 * 变更风险等级。高风险任务无论自动校验是否通过，都必须走人工审批，不提供自动合并开关。
 */
public enum RiskLevel {
    /** 注释、文档、测试数据等不影响运行行为的改动。 */
    LOW("low", "低风险"),
    /** 普通业务代码。 */
    MEDIUM("medium", "中风险"),
    /** 核心逻辑、数据库结构、对外接口、构建配置。 */
    HIGH("high", "高风险");

    private final String wireName;
    private final String label;

    RiskLevel(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    public String wireName() {
        return wireName;
    }

    public String label() {
        return label;
    }

    /** 需要强制人工卡点：高风险改动，或校验出现告警。 */
    public boolean requiresHumanApproval() {
        return this == HIGH;
    }

    public static RiskLevel of(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return MEDIUM;
        }
        for (RiskLevel level : values()) {
            if (level.wireName.equalsIgnoreCase(wireName.strip())
                    || level.name().equalsIgnoreCase(wireName.strip())) {
                return level;
            }
        }
        throw new IllegalArgumentException("未知风险等级: " + wireName);
    }
}
