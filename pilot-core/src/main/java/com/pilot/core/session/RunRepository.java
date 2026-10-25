package com.pilot.core.session;

import java.time.Instant;

/**
 * 运行记录端口：记录每次运行的开始/结束，用于断线后的状态校准，
 * 也为后续「断点续跑」保留基础数据。
 */
public interface RunRepository {

    void started(String runId, String sessionId, Instant at);

    void finished(String runId, String status, Instant at);

    /**
     * 把仍处于 RUNNING 的记录标记为中断（进程重启后调用），
     * 避免界面把已经死掉的运行显示成「正在运行」。
     *
     * @return 被标记的记录数
     */
    int markInterrupted(Instant at);

    static RunRepository noop() {
        return new RunRepository() {
            @Override
            public void started(String runId, String sessionId, Instant at) {
            }

            @Override
            public void finished(String runId, String status, Instant at) {
            }

            @Override
            public int markInterrupted(Instant at) {
                return 0;
            }
        };
    }
}
