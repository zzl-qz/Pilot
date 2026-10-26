package com.pilot.core.session;

/**
 * 进程重启时被判定为中断的运行记录。
 *
 * <p>只把 runs 表标成 INTERRUPTED 是不够的：会话状态由事件驱动，没有终态事件时
 * 会话会永远停在 RUNNING，界面就一直显示「正在处理」。因此这里把 sessionId 一并带出来，
 * 供启动收敛逻辑补一条 {@code run.failed} 事件。
 */
public record InterruptedRun(String runId, String sessionId) {
}
