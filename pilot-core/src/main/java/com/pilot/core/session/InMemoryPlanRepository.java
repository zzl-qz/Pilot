package com.pilot.core.session;

import com.pilot.core.plan.PlanItem;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内计划存储，供 memory 模式与测试使用。 */
public final class InMemoryPlanRepository implements PlanRepository {
    private final ConcurrentMap<String, List<PlanItem>> plans = new ConcurrentHashMap<>();

    @Override
    public List<PlanItem> load(String sessionId) {
        return plans.getOrDefault(sessionId, List.of());
    }

    @Override
    public void save(String sessionId, List<PlanItem> items) {
        plans.put(sessionId, List.copyOf(items));
    }

    @Override
    public void delete(String sessionId) {
        plans.remove(sessionId);
    }
}
