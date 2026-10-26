package com.pilot.server.session;

import com.pilot.core.event.RunFailed;
import com.pilot.core.session.InterruptedRun;
import com.pilot.core.session.RunRepository;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 启动时的状态收敛：进程重启后，上一次运行不可能继续，必须把「运行」和「会话」一起收敛。
 *
 * <p>只改 runs 表是不够的——会话状态由事件驱动，缺了 {@code run.failed} 事件，
 * 会话会永远停在 RUNNING，客户端回放历史后一直显示「正在处理」。
 * 这里对每个被中断的运行补一条 {@code RunFailed("INTERRUPTED")}，事件驱动状态迁移到
 * {@code SessionStatus.INTERRUPTED}，界面即可显示「已中断 · 可继续」。
 */
@Component
public class InterruptedRunRecovery implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InterruptedRunRecovery.class);
    private static final String MESSAGE = "服务重启导致本次运行中断，对话记忆已保留，可点「继续执行」接着跑。";

    private final RunRepository runs;
    private final SessionService sessions;

    public InterruptedRunRecovery(RunRepository runs, SessionService sessions) {
        this.runs = runs;
        this.sessions = sessions;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        List<InterruptedRun> interrupted = runs.markInterrupted(now);
        Set<String> handled = new HashSet<>();
        for (InterruptedRun run : interrupted) {
            handled.add(run.sessionId());
            closeSession(run.sessionId(), run.runId());
        }
        // runs 表可能压根没有对应记录（例如 run 行没写成或事件丢失），仍要收敛会话状态
        for (Session session : sessions.list()) {
            if (session.status().terminal() || handled.contains(session.id())) {
                continue;
            }
            closeSession(session.id(), "run_recovered-" + UUID.randomUUID());
        }
    }

    private void closeSession(String sessionId, String runId) {
        try {
            sessions.publish(sessionId, runId, new RunFailed("INTERRUPTED", MESSAGE, true));
            log.warn("会话 {} 上次运行 {} 未正常结束，已补 run.failed(INTERRUPTED) 收敛为中断", sessionId, runId);
        } catch (RuntimeException e) {
            log.warn("收敛会话 {} 的中断状态失败：{}", sessionId, e.toString());
        }
    }
}
