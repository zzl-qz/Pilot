package com.pilot.core.loop;

/**
 * 计划门：多步任务跑了很久仍没有 {@code update_plan} 时，提醒一次。
 *
 * <p>只计数、只提醒一次，绝不因为缺少计划而判失败：计划是给用户看的执行轨迹，
 * 强制它存在反而会把简单的问答任务逼成形式主义。计数阈值按工具调用与模型请求分别累计，
 * 任一到达上限且本次 run 仍无计划，就注入一次提醒，之后不再打扰。
 */
final class PlanGate {
    /** 连续工具调用达到这个次数仍无计划就提醒。 */
    static final int TOOL_CALL_THRESHOLD = 6;
    /** 模型请求达到这个次数仍无计划就提醒。 */
    static final int MODEL_REQUEST_THRESHOLD = 8;

    private static final String REMINDER = """
        连续执行了多步操作，但本次 run 还没有 update_plan。
        如果任务还有多步未完成，请先调用 update_plan 列出剩余可验证步骤（小任务 2–4 步，跨模块大任务 5–8 步），再继续执行；
        只差一两个动作就能收尾时忽略这条提醒。""";

    private int toolCalls;
    private int modelRequests;
    private boolean planned;
    private boolean reminded;

    /** 每个 run 开始时重置状态。 */
    void reset() {
        toolCalls = 0;
        modelRequests = 0;
        planned = false;
        reminded = false;
    }

    /** 本次 run 已经维护过计划。 */
    void markPlanned() {
        planned = true;
    }

    /** 记一批工具调用。 */
    void observeToolCalls(int count) {
        toolCalls += Math.max(0, count);
    }

    /** 记一次模型请求。 */
    void observeModelRequest() {
        modelRequests++;
    }

    /**
     * 达到阈值且仍无计划时返回提醒文本；每个 run 最多返回一次。
     *
     * @return 需要注入的提醒，或 {@code null}
     */
    String takeReminder() {
        if (planned || reminded) {
            return null;
        }
        if (toolCalls < TOOL_CALL_THRESHOLD && modelRequests < MODEL_REQUEST_THRESHOLD) {
            return null;
        }
        reminded = true;
        return REMINDER;
    }
}
