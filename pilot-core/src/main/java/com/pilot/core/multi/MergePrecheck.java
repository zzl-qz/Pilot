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
     * @param external 是否牵扯其他会话尚未接受的沙箱
     * @param own      是否牵扯本批子 Agent 之间的冲突
     */
    public record Conflict(String path, List<String> owners, String detail, boolean external, boolean own) {
        public Conflict(String path, List<String> owners, String detail) {
            this(path, owners, detail, false, true);
        }

        public Conflict(String path, List<String> owners, String detail, boolean external) {
            this(path, owners, detail, external, !external);
        }
    }

    /**
     * 另一个会话里尚未接受的改动；只参与冲突判定，不参与合并与硬校验。
     *
     * @param owner   占用方标识，用于在报告里说明"谁占着"
     * @param changes 该占用方的待接受改动
     */
    public record Held(String owner, List<SandboxChange> changes) {
        public Held {
            owner = owner == null ? "" : owner;
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
    }

    /**
     * @param hasConflict 是否存在冲突；存在时禁止自动进入审批池
     * @param conflicts   冲突清单
     */
    public record Report(boolean hasConflict, List<Conflict> conflicts) {
        /** 本批子 Agent 之间的冲突。 */
        public boolean hasOwnConflict() {
            return conflicts.stream().anyMatch(Conflict::own);
        }

        /** 牵扯其他会话未接受沙箱的冲突。 */
        public boolean hasExternalConflict() {
            return conflicts.stream().anyMatch(Conflict::external);
        }

        public List<Conflict> externalConflicts() {
            return conflicts.stream().filter(Conflict::external).toList();
        }

        public String describe() {
            if (!hasConflict) {
                return "合并预检通过：没有文件重叠冲突。";
            }
            StringBuilder out = new StringBuilder("合并预检发现 ").append(conflicts.size()).append(" 处冲突：");
            for (Conflict conflict : conflicts) {
                out.append("\n- ").append(conflict.external() ? "【跨会话】" : "").append(conflict.path()).append("（")
                    .append(String.join(" / ", conflict.owners())).append("）：").append(conflict.detail());
            }
            return out.toString();
        }
    }

    private MergePrecheck() {
    }

    public static Report check(Collection<DiffSandbox> sandboxes) {
        return check(sandboxes, List.of());
    }

    /**
     * 连同其他会话尚未接受的改动一起预检。
     *
     * <p>其他会话之间的冲突与本批无关，不报；只报至少牵扯本批一份改动的冲突。
     *
     * @param held 其他会话的占用，不参与合并与硬校验
     */
    public static Report check(Collection<DiffSandbox> sandboxes, Collection<Held> held) {
        Map<String, Map<String, List<SandboxChange>>> byPath = new LinkedHashMap<>();
        Set<String> externalOwners = new LinkedHashSet<>();
        if (sandboxes != null) {
            for (DiffSandbox sandbox : sandboxes) {
                for (SandboxChange change : sandbox.changes()) {
                    index(byPath, owner(change), change);
                }
            }
        }
        if (held != null) {
            for (Held item : held) {
                for (SandboxChange change : item.changes()) {
                    externalOwners.add(item.owner());
                    index(byPath, item.owner(), change);
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
            Set<String> participants = new LinkedHashSet<>();
            List<String> details = new ArrayList<>();
            boolean ownConflict = false;
            boolean externalConflict = false;
            for (int i = 0; i < names.size(); i++) {
                for (int j = i + 1; j < names.size(); j++) {
                    String left = names.get(i);
                    String right = names.get(j);
                    boolean leftExternal = externalOwners.contains(left);
                    boolean rightExternal = externalOwners.contains(right);
                    if (leftExternal && rightExternal) {
                        continue;
                    }
                    String detail = overlapping(Map.of(left, owners.get(left), right, owners.get(right)));
                    if (detail == null) {
                        continue;
                    }
                    participants.add(left);
                    participants.add(right);
                    details.add(detail);
                    if (leftExternal || rightExternal) {
                        externalConflict = true;
                    } else {
                        ownConflict = true;
                    }
                }
            }
            if (ownConflict || externalConflict) {
                conflicts.add(new Conflict(entry.getKey(), List.copyOf(participants), String.join("；", details),
                    externalConflict, ownConflict));
            }
        }
        return new Report(!conflicts.isEmpty(), List.copyOf(conflicts));
    }

    private static void index(Map<String, Map<String, List<SandboxChange>>> byPath, String owner,
        SandboxChange change) {
        byPath.computeIfAbsent(change.path(), key -> new LinkedHashMap<>())
            .computeIfAbsent(owner, key -> new ArrayList<>()).add(change);
    }

    /** 多份改动是否落在同一区域；返回 {@code null} 表示可以自动合并。 */
    private static String overlapping(Map<String, List<SandboxChange>> owners) {
        List<String> names = new ArrayList<>(owners.keySet());
        List<String> notes = new ArrayList<>();
        Map<String, List<List<int[]>>> ranges = new LinkedHashMap<>();
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
                String hit = rangesHit(ranges.computeIfAbsent(left, key -> oldRanges(leftChanges)),
                    ranges.computeIfAbsent(right, key -> oldRanges(rightChanges)));
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

    private static List<List<int[]>> oldRanges(List<SandboxChange> changes) {
        return changes.stream().map(SandboxChange::diff).map(UnifiedDiff::oldRanges).toList();
    }

    private static String rangesHit(List<List<int[]>> left, List<List<int[]>> right) {
        for (List<int[]> one : left) {
            for (List<int[]> other : right) {
                for (int[] a : one) {
                    for (int[] b : other) {
                        if (a[0] <= b[1] && b[0] <= a[1]) {
                            return "改动行区间重叠 " + a[0] + "-" + bounded(a[1]) + " 与 " + b[0] + "-" + bounded(b[1]);
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
