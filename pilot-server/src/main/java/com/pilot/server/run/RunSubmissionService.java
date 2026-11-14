package com.pilot.server.run;

import java.util.List;

import com.pilot.core.event.RunFailed;
import com.pilot.core.event.RunFailureCode;
import com.pilot.core.event.RunStarted;
import com.pilot.core.message.Attachment;
import com.pilot.core.session.SessionService;

/** HTTP 与定时器共用的已占位投递入口；调用前 claimRun，失败必释放。 */
public final class RunSubmissionService {
    private RunSubmissionService() {
    }

    public static void start(SessionService sessions, RunStarter runs, String sessionId, String runId, String input,
        List<Attachment> attachments, List<Integer> imagePositions, TaskControlService.Requirements task,
        String skill) {
        submit(sessions, runs, sessionId, runId, new RunStarted(input, attachments, imagePositions),
            () -> runs.start(sessionId, runId, input, attachments, imagePositions, task, skill));
    }

    public static void resume(SessionService sessions, RunStarter runs, String sessionId, String runId) {
        submit(sessions, runs, sessionId, runId, new RunStarted("（继续上一次未完成的任务）"), () -> runs.resume(sessionId, runId));
    }

    private static void submit(SessionService sessions, RunStarter runs, String sessionId, String runId,
        RunStarted started, Runnable action) {
        try {
            sessions.publish(sessionId, runId, started);
            action.run();
        } catch (RuntimeException e) {
            runs.abandon(runId);
            try {
                sessions.publish(sessionId, runId,
                    new RunFailed(RunFailureCode.START_FAILED, "启动失败：" + e.getMessage(), true));
            } catch (RuntimeException reportingFailure) {
                // 回执失败不能盖过原始投递异常，也不能丢掉第二次失败的诊断信息。
                if (reportingFailure != e) {
                    e.addSuppressed(reportingFailure);
                }
            }
            throw e;
        }
    }
}
