package com.pilot.core.tool;

/**
 * 工具结果文本开头的约定。
 * 工具把结果当一段文本交给模型，开头的标记同时也是程序判断成败的依据。
 * 写结果和认结果的两边都从这里取，免得一边改了字、另一边认不出来。
 * 普通失败直接写 "ERROR: 原因" 即可；下面几个带固定含义的开头才需要用常量。
 */
public final class ToolResults {
    /** 所有失败结果都以它开头。 */
    public static final String ERROR = "ERROR";
    /** 权限规则不允许，工具没有执行。 */
    public static final String PERMISSION_DENIED = "ERROR: 权限拒绝";
    /** 用户在确认框里点了拒绝，工具没有执行。 */
    public static final String USER_DENIED = "ERROR: 用户拒绝";
    /** 外部操作可能已经生效，但没等到确切结果。 */
    public static final String RESULT_UNKNOWN = "ERROR: 结果未确认";
    /** 命令跑完了，但没能完整记下它改了哪些文件。 */
    public static final String CHANGE_TRACKING_INCOMPLETE = "ERROR: 命令已结束，但无法完整确认其工作区变更";
    /** 命令前的变更基线没建立起来，但命令仍会执行；这只是一条降级警告。 */
    public static final String CHANGE_TRACKING_UNAVAILABLE = "WARN: 未能建立命令前文件基线，命令仍会执行；本轮的改动不会进入差异/回滚追踪";
    /**
     * 命令跑完了，但有些文件没进基线（被占用、无权限或临时消失）。
     * 它们不在追踪范围内，也不能因为追踪不到就当成没改动；这是提示，不是失败。
     */
    public static final String CHANGE_TRACKING_PARTIAL = "NOTE: 部分文件没有进入命令前文件基线，其改动不在本轮差异/回滚追踪内，不能据此认为它们没有改动";
    /** 被运行策略或中断信号跳过，工具没有执行。 */
    public static final String SKIPPED = "SKIPPED:";
    /** 外层系统沙箱内再次创建子沙箱失败时的环境提示固定前缀（由命令工具写入头部，不来自命令标准输出）。 */
    public static final String NESTED_SANDBOX_HINT_PREFIX = "[环境提示] 检测到嵌套 OS 沙箱失败";

    private ToolResults() {
    }

    /** 生成一条「已跳过」的结果。 */
    public static String skipped(String reason) {
        return SKIPPED + " " + reason;
    }

    public static boolean isError(String result) {
        return result != null && result.startsWith(ERROR);
    }

    /** 权限拒绝或用户拒绝。 */
    public static boolean isDenied(String result) {
        return result != null && (result.startsWith(PERMISSION_DENIED) || result.startsWith(USER_DENIED));
    }

    public static boolean isSkipped(String result) {
        return result != null && result.startsWith(SKIPPED);
    }

    public static boolean isResultUnknown(String result) {
        return result != null && result.startsWith(RESULT_UNKNOWN);
    }

    public static boolean isChangeTrackingIncomplete(String result) {
        return result != null && result.startsWith(CHANGE_TRACKING_INCOMPLETE);
    }

    /** 仅识别命令工具头部（输出正文之前）由运行时写入的嵌套 OS 沙箱环境提示，防止被命令输出内容伪造。 */
    public static boolean isNestedSandboxFailure(String result) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        int outputIndex = result.indexOf("\n输出:\n");
        String header = outputIndex >= 0 ? result.substring(0, outputIndex) : result;
        int start = 0;
        while (start <= header.length()) {
            int end = header.indexOf('\n', start);
            String line = end < 0 ? header.substring(start) : header.substring(start, end);
            if (line.startsWith(NESTED_SANDBOX_HINT_PREFIX)) {
                return true;
            }
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        return false;
    }
}
