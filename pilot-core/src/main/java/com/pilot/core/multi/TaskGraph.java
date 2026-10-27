package com.pilot.core.multi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局任务 DAG，父 Agent 独占。
 *
 * <p>构造即校验：编号唯一、依赖存在、无环。子 Agent 看不到也改不了这张图，
 * 只能通过上报状态让父 Agent 决定下一个可拉起的节点。
 */
public final class TaskGraph {
    private final Map<String, TaskSpec> nodes;

    public TaskGraph(Collection<TaskSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("任务计划为空");
        }
        Map<String, TaskSpec> ordered = new LinkedHashMap<>();
        for (TaskSpec spec : specs) {
            if (ordered.putIfAbsent(spec.id(), spec) != null) {
                throw new IllegalArgumentException("任务编号重复: " + spec.id());
            }
        }
        this.nodes = ordered;
        validate();
    }

    private void validate() {
        for (TaskSpec spec : nodes.values()) {
            for (String dependency : spec.dependsOn()) {
                if (!nodes.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "任务 " + spec.id() + " 依赖了不存在的任务 " + dependency);
                }
            }
        }
        // Kahn 拓扑排序顺带检测环；同时保证调度顺序确定
        if (topoOrder().size() != nodes.size()) {
            throw new IllegalArgumentException("任务依赖存在环，无法调度");
        }
    }

    public List<TaskSpec> specs() {
        return List.copyOf(nodes.values());
    }

    public Set<String> ids() {
        return nodes.keySet();
    }

    public TaskSpec require(String id) {
        TaskSpec spec = id == null ? null : nodes.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("没有这个任务: " + id);
        }
        return spec;
    }

    /** 拓扑序：前面的任务一定排在依赖它的任务之前。 */
    public List<String> topoOrder() {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (TaskSpec spec : nodes.values()) {
            indegree.putIfAbsent(spec.id(), 0);
            for (String dependency : spec.dependsOn()) {
                indegree.merge(spec.id(), 1, Integer::sum);
                children.computeIfAbsent(dependency, key -> new ArrayList<>()).add(spec.id());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        List<String> order = new ArrayList<>(nodes.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String child : children.getOrDefault(id, List.of())) {
                if (indegree.merge(child, -1, Integer::sum) == 0) {
                    queue.add(child);
                }
            }
        }
        return order;
    }

    /**
     * 当前可以启动的任务：自身未启动、所有前置任务都已完成。
     *
     * @param finished 已完成的任务编号
     * @param started  已经启动（含已完成）的任务编号，避免重复拉起
     */
    public List<TaskSpec> ready(Set<String> finished, Set<String> started) {
        List<TaskSpec> ready = new ArrayList<>();
        for (TaskSpec spec : nodes.values()) {
            if (started.contains(spec.id()) || finished.contains(spec.id())) {
                continue;
            }
            if (finished.containsAll(spec.dependsOn())) {
                ready.add(spec);
            }
        }
        return ready;
    }

    /** 传递闭包：某个任务失败后，所有需要被阻断的下游任务。 */
    public Set<String> downstream(String id) {
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (TaskSpec spec : nodes.values()) {
            for (String dependency : spec.dependsOn()) {
                children.computeIfAbsent(dependency, key -> new ArrayList<>()).add(spec.id());
            }
        }
        Set<String> result = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(children.getOrDefault(id, List.of()));
        while (!stack.isEmpty()) {
            String child = stack.pop();
            if (result.add(child)) {
                stack.addAll(children.getOrDefault(child, List.of()));
            }
        }
        return result;
    }

    /** 某个任务的最长依赖链深度，用于并行调度时按层推进。 */
    public int depth(String id) {
        TaskSpec spec = require(id);
        if (spec.dependsOn().isEmpty()) {
            return 0;
        }
        int max = 0;
        for (String dependency : spec.dependsOn()) {
            max = Math.max(max, depth(dependency) + 1);
        }
        return max;
    }
}
