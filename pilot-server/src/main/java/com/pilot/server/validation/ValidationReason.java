package com.pilot.server.validation;

/**
 * 验证结论的原因分类。
 *
 * <p>界面靠这些代号决定视觉与文案，不去匹配中文句子：中文一改，判断就会悄悄失效。
 * 代号同时出现在 {@link ValidationLedger.Report} 和 {@link ValidationSnapshot} 里，两边语义一致。
 */
public final class ValidationReason {
    /** 已通过，且证据仍覆盖当前产物。 */
    public static final String OK = "ok";
    /** 还没有任何校验证据。 */
    public static final String NO_EVIDENCE = "no_evidence";
    /** 本轮没有记录到产物变化，没有可校验的对象。 */
    public static final String NO_CHANGES = "no_changes";
    /** 证据针对的是旧产物：文件在这次校验之后被改动过。 */
    public static final String STALE_CANDIDATE = "stale_candidate";
    /** 最近一次校验没有通过。 */
    public static final String FAILED = "failed";
    /** 证据存在，但没有覆盖到这份产物。 */
    public static final String NOT_COVERED = "not_covered";
    /** 账本自身读写异常，结论不可信。 */
    public static final String STORAGE_DEGRADED = "storage_degraded";
    /** 拿不到可校验的工作区。 */
    public static final String NO_WORKSPACE = "no_workspace";

    private ValidationReason() {
    }
}
