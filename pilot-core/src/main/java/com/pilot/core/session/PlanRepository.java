package com.pilot.core.session;

import com.pilot.core.plan.PlanItem;

import java.util.List;

/** 计划清单存储：按会话保存，用于跨轮次与跨进程恢复进度。 */
public interface PlanRepository {

    List<PlanItem> load(String sessionId);

    void save(String sessionId, List<PlanItem> items);

    void delete(String sessionId);

    static PlanRepository noop() {
        return new PlanRepository() {
            @Override
            public List<PlanItem> load(String sessionId) {
                return List.of();
            }

            @Override
            public void save(String sessionId, List<PlanItem> items) {
            }

            @Override
            public void delete(String sessionId) {
            }
        };
    }
}
