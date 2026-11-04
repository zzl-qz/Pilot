package com.pilot.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多智能体派发的预算上限。
 *
 * <p>与 {@link MultiAgentProperties} 分开定义：那边是能力开关，这边是配额。
 * 不放进同一个 record 是因为 {@code @ConfigurationProperties} 的构造函数绑定要求唯一构造函数，
 * 多一个便利重载会让 Spring 选错构造函数，整个应用上下文都起不来。
 *
 * @param maxDispatchSeconds 单次派发的墙钟上限（秒）；超了只暂停未启动的任务，已完成的产物全部保留
 * @param maxTotalTokens     单次派发的 token 上限（输入 + 输出）；0 表示不限
 */
@ConfigurationProperties(prefix = "pilot.multi.budget")
public record MultiAgentBudget(Integer maxDispatchSeconds, Integer maxTotalTokens) {
    /** 未配置时的兜底：单次派发最多 30 分钟，token 不限。 */
    public static final MultiAgentBudget DEFAULT = new MultiAgentBudget(null, null);

    public MultiAgentBudget {
        maxDispatchSeconds =
            maxDispatchSeconds == null || maxDispatchSeconds < 30 ? 1_800 : Math.min(maxDispatchSeconds, 21_600);
        maxTotalTokens = maxTotalTokens == null || maxTotalTokens < 0 ? 0 : maxTotalTokens;
    }
}
