package com.pilot.core.coordination;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨会话协调图的确定性诊断：检测未关闭请求/交接形成的互等死锁环路，以及等待目标停滞或已异常结束的风险。
 */
final class CoordinationDiagnostics {
    private static final Set<String> BROKEN_TARGET_STATUSES = Set.of("FAILED", "CANCELLED", "INTERRUPTED");

    private CoordinationDiagnostics() {
    }

    static List<CoordinationAlert> analyze(List<CoordinationSessionView> sessions, List<CoordinationRequest> requests,
        List<CoordinationHandoff> handoffs) {
        Map<String, CoordinationSessionView> byId = new LinkedHashMap<>();
        for (CoordinationSessionView session : sessions) {
            byId.put(session.sessionId(), session);
        }
        Map<String, Set<String>> waitFor = new LinkedHashMap<>();
        for (CoordinationRequest request : requests) {
            if (request.status().open()) {
                waitFor.computeIfAbsent(request.fromSessionId(), ignored -> new LinkedHashSet<>())
                    .add(request.toSessionId());
            }
        }
        for (CoordinationHandoff handoff : handoffs) {
            if (handoff.status().open()) {
                waitFor.computeIfAbsent(handoff.fromSessionId(), ignored -> new LinkedHashSet<>())
                    .add(handoff.toSessionId());
            }
        }
        if (waitFor.isEmpty()) {
            return List.of();
        }

        List<CoordinationAlert> alerts = new ArrayList<>();
        detectDeadlocks(waitFor, alerts);
        detectStalledDependencies(waitFor, byId, alerts);
        return List.copyOf(alerts);
    }

    private static void detectDeadlocks(Map<String, Set<String>> waitFor, List<CoordinationAlert> alerts) {
        Set<String> seenSignatures = new LinkedHashSet<>();
        List<String> nodes = new ArrayList<>(waitFor.keySet());
        nodes.sort(Comparator.naturalOrder());
        for (String start : nodes) {
            List<String> path = new ArrayList<>();
            path.add(start);
            dfs(start, start, waitFor, path, new LinkedHashSet<>(List.of(start)), seenSignatures, alerts);
        }
    }

    private static void dfs(String origin, String current, Map<String, Set<String>> waitFor, List<String> path,
        Set<String> inPath, Set<String> seenSignatures, List<CoordinationAlert> alerts) {
        for (String next : waitFor.getOrDefault(current, Set.of())) {
            if (next.equals(origin) && path.size() >= 2) {
                List<String> canonical = canonicalCycle(path);
                String signature = String.join("->", canonical);
                if (seenSignatures.add(signature)) {
                    alerts.add(new CoordinationAlert(CoordinationAlert.DEADLOCK, canonical,
                        "检测到会话互相等待（" + canonical.size() + " 个会话形成等待环路），请撤销或人工介入其中一项请求/交接"));
                }
            } else if (!inPath.contains(next) && waitFor.containsKey(next)) {
                inPath.add(next);
                path.add(next);
                dfs(origin, next, waitFor, path, inPath, seenSignatures, alerts);
                path.removeLast();
                inPath.remove(next);
            }
        }
    }

    private static List<String> canonicalCycle(List<String> cycle) {
        int minIdx = 0;
        for (int i = 1; i < cycle.size(); i++) {
            if (cycle.get(i).compareTo(cycle.get(minIdx)) < 0) {
                minIdx = i;
            }
        }
        List<String> rotated = new ArrayList<>(cycle.size());
        for (int i = 0; i < cycle.size(); i++) {
            rotated.add(cycle.get((minIdx + i) % cycle.size()));
        }
        return List.copyOf(rotated);
    }

    private static void detectStalledDependencies(Map<String, Set<String>> waitFor,
        Map<String, CoordinationSessionView> byId, List<CoordinationAlert> alerts) {
        for (var entry : waitFor.entrySet()) {
            String waiter = entry.getKey();
            for (String target : entry.getValue()) {
                CoordinationSessionView view = byId.get(target);
                if (view == null) {
                    continue;
                }
                if (view.stalled()) {
                    alerts.add(new CoordinationAlert(CoordinationAlert.STALLED_DEPENDENCY, List.of(waiter, target),
                        "等待中的目标会话长时间无新活动（疑似停滞），建议切换查看或撤销待处理请求"));
                } else if (BROKEN_TARGET_STATUSES.contains(view.status())) {
                    alerts.add(new CoordinationAlert(CoordinationAlert.STALLED_DEPENDENCY, List.of(waiter, target),
                        "等待中的目标会话已异常停止（" + statusLabel(view.status()) + "），不会自动回应待处理请求"));
                }
            }
        }
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "FAILED" -> "失败";
            case "CANCELLED" -> "已取消";
            case "INTERRUPTED" -> "已中断";
            default -> status;
        };
    }
}
