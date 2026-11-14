package com.pilot.server.schedule;

import java.time.Duration;

/**
 * 定时计划和周期计划共用的边界与默认值。
 * 提示语里的数字也从这里取，判断和文案不会各写一份。
 */
final class ScheduleLimits {
    /** 两个调度器的扫描间隔，可用 pilot.schedules.poll-ms 覆盖。 */
    static final String POLL_DELAY = "${pilot.schedules.poll-ms:2000}";
    /** 请求没带时区时使用。 */
    static final String DEFAULT_TIMEZONE = "UTC";

    static final int MAX_CONTENT_CHARS = 100_000;
    static final int MAX_NAME_CHARS = 120;
    /** 没填名称时，取任务目标开头这么多字当名称。 */
    static final int DEFAULT_NAME_CHARS = 40;

    /** 同一个计划最多被唤醒这么多次。 */
    static final int MAX_WAKE_ATTEMPTS = 100;
    /** 执行中断后最多自动重试这么多次。 */
    static final int MAX_RECOVERY_ATTEMPTS = 3;
    /** 中断后首次重试的等待；每多中断一次翻倍。 */
    static final long RECOVERY_DELAY_SECONDS = 30;
    /** 任务自己进入等待后，隔这么久再检查。 */
    static final long WAITING_DELAY_SECONDS = 60;
    /** wait_until 允许预约的最短等待。 */
    static final long MIN_WAIT_SECONDS = 30;
    /** 从首次派发算起的总执行时限。 */
    static final Duration TOTAL_EXECUTION_LIMIT = Duration.ofHours(24);

    static final int MAX_CRON_CHARS = 200;
    /** 相邻两轮的最短间隔。 */
    static final long MIN_PERIOD_SECONDS = 60;
    /** 错过策略为跳过时，迟到超过这么久才真的跳过。 */
    static final long MISFIRE_GRACE_SECONDS = 60;
    /** 预览时在首次时间之后再列出几次。 */
    static final int PREVIEW_OCCURRENCES = 4;

    private ScheduleLimits() {
    }
}
