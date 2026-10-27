package com.pilot.core.multi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合并预检：把多个沙箱的 diff 往同一份基线上做虚拟合并，只报冲突，不做取舍。
 *
 * <p>检测两类问题：
 * <ol>
 *   <li>同一文件同一区域被两个子 Agent 改动（文本冲突）；</li>
 *   <li>同一路径被两个子 Agent 同时新建（结构性冲突）。</li>
 * </ol>
 * 语义冲突（接口改了但调用方没改）git 看不出来，交给合并后的编译 / 测试硬校验。
 *
 * <p>预检只产出报告，绝不自动挑选胜出方：冲突一律交人工裁决。
 */
public final class MergePrecheck {

    /**
     * @param path   冲突文件
     * @param owners 参与的 Agent 标识
     * @param detail 冲突细节（行区间）
     */
    public record Conflict(String path, List<String> owners, String detail) {
    }

    /**
     * @param hasConflict 是否存在冲突；存在时禁止自动进入审批池
     * @param conflicts   冲突清单
     */
    public record Report(boolean hasConflict, List<Conflict> conflicts) {
        public String describe() {
            if (!hasConflict) {
                return "合并预检通过：没有文件重叠冲突。";
            }
            StringBuilder out = new StringBuilder("合并预检发现 ").append(conflicts.size()).append(" 处冲突：");
            for (Conflict conflict : conflicts) {
                out.append("\n- ").append(conflict.path())
                        .append("（").append(String.join(" / ", conflict.owners())).append("）：")
                        .append(conflict.detail());
            }
            return out.toString();
        }
    }

    private MergePrecheck() {
    }

    public static Report check(Collection<DiffSandbox> sandboxes) {
        Map<String, Map<String, List<SandboxChange>>> byPath = new LinkedHashMap<>();
        if (sandboxes != null) {
            for (DiffSandbox sandbox : sandboxes) {
                for (SandboxChange change : sandbox.changes()) {
                    byPath.computeIfAbsent(change.path(), key -> new LinkedHashMap<>())
                            .computeIfAbsent(owner(change), key -> new ArrayList<>())
                            .add(change);
                }
            }
        }

        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<SandboxChange>>> entry : byPath.entrySet()) {
            Map<String, List<SandboxChange>> owners = entry.getValue();
            if (owners.size() < 2) {
                continue;
            }
            List<String> names = List.copyOf(owners.keySet());
            String detail = overlapping(owners);
            if (detail != null) {
                conflicts.add(new Conflict(entry.getKey(), names, detail));
            }
        }
        return new Report(!conflicts.isEmpty(), List.copyOf(conflicts));
    }

    /** 多份改动是否落在同一区域；返回 {@code null} 表示可以自动合并。 */
    private static String overlapping(Map<String, List<SandboxChange>> owners) {
        List<String> names = new ArrayList<>(owners.keySet());
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                String left = names.get(i);
                String right = names.get(j);
                List<SandboxChange> leftChanges = owners.get(left);
                List<SandboxChange> rightChanges = owners.get(right);
                boolean bothCreate = allNew(leftChanges) && allNew(rightChanges);
                if (bothCreate) {
                    notes.add(left + " 与 " + right + " 都新建同一文件");
                    continue;
                }
                String hit = rangesHit(leftChanges, rightChanges);
                if (hit != null) {
                    notes.add(left + " 与 " + right + "：" + hit);
                }
            }
        }
        return notes.isEmpty() ? null : String.join("；", notes);
    }

    private static boolean allNew(List<SandboxChange> changes) {
        return changes.stream().noneMatch(SandboxChange::existed);
    }

    private static String rangesHit(List<SandboxChange> left, List<SandboxChange> right) {
        for (SandboxChange one : left) {
            for (SandboxChange other : right) {
                for (int[] a : UnifiedDiff.oldRanges(one.diff())) {
                    for (int[] b : UnifiedDiff.oldRanges(other.diff())) {
                        if (a[0] <= b[1] && b[0] <= a[1]) {
                            return "改动行区间重叠 " + a[0] + "-" + bounded(a[1]) + " 与 "
                                    + b[0] + "-" + bounded(b[1]);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String bounded(int value) {
        return value == Integer.MAX_VALUE ? "文件末尾" : String.valueOf(value);
    }

    /** 所有参与合并的改动，用于硬校验；顺序由调用方按任务拓扑序给出。 */
    public static List<SandboxChange> mergedChanges(Collection<DiffSandbox> sandboxes) {
        List<SandboxChange> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (sandboxes != null) {
            for (DiffSandbox sandbox : sandboxes) {
                for (SandboxChange change : sandbox.changes()) {
                    String key = change.agentId() + '|' + change.taskId() + '|' + change.path();
                    if (seen.add(key)) {
                        merged.add(change);
                    }
                }
            }
        }
        return List.copyOf(merged);
    }

    private static String owner(SandboxChange change) {
        return change.agentId() + "@" + change.taskId();
    }
}
