package com.pilot.server.coordination;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pilot.core.coordination.SessionCoordinationService;

/**
 * 跨会话协调看门狗：定时主动清扫过期占用/请求/交接、释放异常终止会话的幽灵文件锁，并检测互等死锁与停滞会话。
 *
 * <p>配合 {@link com.pilot.server.run.InterruptedRunRecovery} 的单会话看门狗，形成「单会话防假死 + 多会话防互等/幽灵锁」双层守护。
 */
@Component
public class CoordinationWatchdog {
    private static final Logger log = LoggerFactory.getLogger(CoordinationWatchdog.class);

    private final SessionCoordinationService coordination;

    public CoordinationWatchdog(SessionCoordinationService coordination) {
        this.coordination = coordination;
    }

    @Scheduled(fixedDelayString = "${pilot.coordination.watchdog-ms:15000}",
        initialDelayString = "${pilot.coordination.watchdog-ms:15000}")
    public void patrol() {
        if ("false".equalsIgnoreCase(System.getProperty("pilot.coordination.watchdog"))) {
            return;
        }
        try {
            SessionCoordinationService.WatchdogReport report = coordination.patrol();
            if (report.sweptCount() > 0 || report.deadlockCount() > 0 || report.stalledSessionCount() > 0) {
                log.info("协调看门狗巡检：清理 {} 条失效记录，死锁告警 {} 项，疑似停滞会话 {} 个", report.sweptCount(), report.deadlockCount(),
                    report.stalledSessionCount());
            }
        } catch (RuntimeException e) {
            log.warn("协调看门狗巡检失败", e);
        }
    }
}
