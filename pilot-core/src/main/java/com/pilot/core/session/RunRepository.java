package com.pilot.core.session;

import java.time.Instant;
import java.util.List;

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
     * <p>返回被标记的运行本身就是调用方需要的信息：只有 runId + sessionId 齐了，
     * 才能在会话事件流里补上终态，把会话状态一起收敛掉。
     *
     * @return 被标记的运行
     */
    List<InterruptedRun> markInterrupted(Instant at);

    /** 删除会话时一并清掉运行记录，避免 runs 表只增不减。 */
    default int deleteBySession(String sessionId) {
        return 0;
    }

    static RunRepository noop() {
        return new RunRepository() {
            @Override
            public void started(String runId, String sessionId, Instant at) {
            }

            @Override
            public void finished(String runId, String status, Instant at) {
            }

            @Override
            public List<InterruptedRun> markInterrupted(Instant at) {
                return List.of();
            }
        };
    }
}
