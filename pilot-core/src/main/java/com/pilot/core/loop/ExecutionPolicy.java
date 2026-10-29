package com.pilot.core.loop;

import com.pilot.core.tool.ToolCall;
import com.pilot.core.tool.ToolExecutor;

import java.util.List;
import java.util.Set;

/** Task-scoped assessment and evidence review, independent of session persistence. */
public interface ExecutionPolicy {
    void beforeRequest(List<Message> history);
    /**
     * 在不增加一次独立分类请求的前提下，给下一次主模型调用注入阶段性纠偏。
     * 返回值只消费一次；没有纠偏时返回 {@code null}。
     */
    default String requestGuidance() { return null; }
    default void beforeTool(ToolCall call) { }
    default boolean permits(ToolCall call) { return true; }
    /** 给模型看的拒绝原因；用于说明预算/阶段限制，而不是统一伪装成权限问题。 */
    default String denialReason(ToolCall call) { return "当前执行阶段不允许这个工具调用。"; }
    default void observed(ToolCall call, ToolExecutor.Outcome outcome) { }
    /** Stop 后允许策略预留一次不带工具的收尾模型调用。 */
    default void beginFinalization() { }
    /** 仅用于观测与提示；普通请求不因此增加核验调用。 */
    default boolean goalActive() { return false; }
    /** 续跑时恢复上一执行段已持久化的改动文件，供通用收尾门使用。 */
    default Set<String> priorChangedPaths() { return Set.of(); }
    /** 续跑时恢复上一执行段的类型化验证记录，不包含任意的成功命令。 */
    default List<String> priorChecks() { return List.of(); }
    /** Null accepts completion; otherwise return the concrete work still required. */
    String review(List<Message> history, Set<String> changedFiles, List<String> checks);

    final class Stop extends RuntimeException {
        private final String code;
        public Stop(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
