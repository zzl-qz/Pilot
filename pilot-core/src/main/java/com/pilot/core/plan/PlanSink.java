package com.pilot.core.plan;

import java.util.List;

/**
 * 计划更新出口：由接入层实现（落库 + 发事件），core 只负责解析与校验。
 */
@FunctionalInterface
public interface PlanSink {
    void update(List<PlanItem> items, String explanation);
}
