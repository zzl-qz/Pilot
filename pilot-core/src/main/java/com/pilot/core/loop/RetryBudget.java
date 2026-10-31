package com.pilot.core.loop;

import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * 模型请求重试的运行治理。
 *
 * <p>之前是「每 3 秒一次、永不停止」：能扛住短暂故障，但一次长时间的服务端故障会
 * 持续占用线程、不断产生请求，用户只能靠取消结束。这里补上三件事：
 * <ul>
 *   <li>指数退避 + 抖动：连续失败时逐步放慢，抖动避免多个任务同时重连形成尖峰；</li>
 *   <li>预算：最多重试次数与最长等待时长，超出后停下来并明确告知可以「继续执行」；</li>
 *   <li>熔断：连续失败达到阈值视为端点整体不可用，立即停下，而不是等到预算耗尽。</li>
 * </ul>
 *
 * <p>余额、鉴权、模型不支持等永久错误不进入这里——它们在 {@link ModelRetryPolicy} 就被判死。
 * 这个类只管「值得重试的失败还要不要继续等」。
 */
public final class RetryBudget {

    /**
     * @param maxAttempts          最多重试几次（不含首次请求）
     * @param maxElapsedMs         从第一次失败起最长重试多久
     * @param baseDelayMs          第一次重试的基准等待
     * @param maxDelayMs           单次等待上限
     * @param breakerAfterFailures 连续失败达到该次数直接熔断；0 表示不启用
     */
    public record Settings(int maxAttempts, long maxElapsedMs, long baseDelayMs, long maxDelayMs,
                           int breakerAfterFailures) {
        public Settings {
            if (maxAttempts < 0) {
                throw new IllegalArgumentException("maxAttempts must be >= 0");
            }
            if (maxElapsedMs < 0) {
                throw new IllegalArgumentException("maxElapsedMs must be >= 0");
            }
            baseDelayMs = Math.max(1L, baseDelayMs);
            maxDelayMs = Math.max(baseDelayMs, maxDelayMs);
            if (breakerAfterFailures < 0) {
                throw new IllegalArgumentException("breakerAfterFailures must be >= 0");
            }
        }

        /** 默认：最多 60 次、最长 30 分钟、1 秒起翻倍到 30 秒、连续失败 20 次熔断。 */
        public static Settings defaults() {
            return new Settings(60, 30 * 60_000L, 1_000L, 30_000L, 20);
        }

        /** 读取系统属性覆盖，便于本机调参而不改代码。 */
        public static Settings fromSystemProperties() {
            Settings defaults = defaults();
            return new Settings(
                    integer("pilot.retry.max-attempts", defaults.maxAttempts()),
                    integer("pilot.retry.max-elapsed-seconds", (int) (defaults.maxElapsedMs() / 1000)) * 1000L,
                    integer("pilot.retry.base-delay-ms", (int) defaults.baseDelayMs()),
                    integer("pilot.retry.max-delay-ms", (int) defaults.maxDelayMs()),
                    integer("pilot.retry.breaker-after-failures", defaults.breakerAfterFailures()));
        }

        private static int integer(String key, int fallback) {
            String raw = System.getProperty(key);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(raw.strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /**
     * @param retry        是否继续重试
     * @param circuitOpen  是否因为熔断而停止
     * @param remainingMs  预算剩余毫秒，{@code -1} 表示预算不设上限
     */
    public record Decision(boolean retry, int attempt, long delayMs, long elapsedMs, long remainingMs,
                           boolean circuitOpen, String reason) {
        public Decision {
            reason = reason == null ? "" : reason;
        }
    }

    private final Settings settings;
    private final LongSupplier clock;
    private final DoubleSupplier random;

    private boolean started;
    private long startedAt;
    private int attempts;
    private int consecutiveFailures;

    public RetryBudget(Settings settings) {
        this(settings, System::currentTimeMillis, () -> Math.random());
    }

    public RetryBudget(Settings settings, LongSupplier clock, DoubleSupplier random) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    public static RetryBudget fromSystemProperties() {
        return new RetryBudget(Settings.fromSystemProperties());
    }

    /** 一次请求成功后清零：下一次故障重新计算退避与预算。 */
    public synchronized void reset() {
        started = false;
        startedAt = 0;
        attempts = 0;
        consecutiveFailures = 0;
    }

    public synchronized int attempts() {
        return attempts;
    }

    /** 生效的最大重试次数，供界面显示「第 n / N 次」。 */
    public int maxAttempts() {
        return settings.maxAttempts();
    }

    /**
     * 记录一次可重试的失败，返回下一次是否继续、等多久。
     */
    public synchronized Decision afterFailure(String reason) {
        long now = clock.getAsLong();
        if (!started) {
            started = true;
            startedAt = now;
        }
        attempts++;
        consecutiveFailures++;

        long elapsed = Math.max(0L, now - startedAt);
        long remaining = settings.maxElapsedMs() <= 0 ? -1 : Math.max(0L, settings.maxElapsedMs() - elapsed);
        long delay = delayFor(attempts);

        if (settings.breakerAfterFailures() > 0 && consecutiveFailures >= settings.breakerAfterFailures()) {
            return new Decision(false, attempts, 0, elapsed, remaining, true,
                    clip(reason) + "（连续失败 " + consecutiveFailures + " 次，已熔断）");
        }
        if (attempts > settings.maxAttempts()) {
            return new Decision(false, attempts, 0, elapsed, remaining, false,
                    clip(reason) + "（重试次数已达上限 " + settings.maxAttempts() + "）");
        }
        if (remaining == 0) {
            return new Decision(false, attempts, 0, elapsed, 0, false,
                    clip(reason) + "（重试总时长已达上限 " + (settings.maxElapsedMs() / 1000) + " 秒）");
        }
        // 最后一次可用预算不足以覆盖完整等待时，缩短等待，避免「等完就超预算」
        long bounded = remaining > 0 ? Math.min(delay, remaining) : delay;
        return new Decision(true, attempts, bounded, elapsed, remaining, false, clip(reason));
    }

    /** 第 {@code attempt} 次重试的等待：指数增长、封顶，并带等量抖动。 */
    long delayFor(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 20));
        long exponential = settings.baseDelayMs() << Math.min(exponent, 20);
        long capped = Math.min(settings.maxDelayMs(), Math.max(settings.baseDelayMs(), exponential));
        double jitter = Math.max(0.0, Math.min(1.0, random.getAsDouble()));
        // 等量抖动：实际等待落在 [capped/2, capped]，既能退避又不会同步重连
        return Math.max(1L, (long) (capped * (0.5 + 0.5 * jitter)));
    }

    private static String clip(String reason) {
        if (reason == null) {
            return "";
        }
        String oneLine = reason.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200);
    }
}
