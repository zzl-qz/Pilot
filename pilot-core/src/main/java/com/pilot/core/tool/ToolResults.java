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
    /** 被运行策略或中断信号跳过，工具没有执行。 */
    public static final String SKIPPED = "SKIPPED:";

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
}
