package com.pilot.core.coordination;

import java.util.List;

/**
 * 协调状态的不可变快照：查询接口返回它，持久化也只落这一份。
 *
 * <p>只包含协调数据本身，不含沙箱改动、仲裁报告和事件正文。
 */
public record CoordinationSnapshot(String projectId, List<CoordinationSessionView> sessions,
    List<CoordinationClaim> claims, List<CoordinationRequest> requests, List<CoordinationHandoff> handoffs) {

    public CoordinationSnapshot {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        claims = claims == null ? List.of() : List.copyOf(claims);
        requests = requests == null ? List.of() : List.copyOf(requests);
        handoffs = handoffs == null ? List.of() : List.copyOf(handoffs);
    }

    public static CoordinationSnapshot empty() {
        return new CoordinationSnapshot(null, List.of(), List.of(), List.of(), List.of());
    }
}
