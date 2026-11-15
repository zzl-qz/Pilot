package com.pilot.server.validation;

import java.time.Instant;

/**
 * 某个产物此刻的验证结论。
 *
 * <p>它不是一条历史证据，而是「拿当前文件内容去比对账本」得到的即时判断。三种状态互斥：
 *
 * <ul>
 *   <li>{@link #VERIFIED}：账本里最新一条通过证据仍覆盖当前文件内容；</li>
 *   <li>{@link #STALE}：曾经有证据，但文件已经变了，那条证据不能再支撑完成声明；</li>
 *   <li>{@link #UNVERIFIED}：没有任何证据，或最近一次校验没有通过、没有覆盖这个文件。</li>
 * </ul>
 *
 * <p>外部验证工具只回答「有没有证据」；这里多回答一句「这份证据还作不作数」，这是产品差异化的落点。
 *
 * @param status        {@link #VERIFIED}、{@link #STALE}、{@link #UNVERIFIED} 之一
 * @param reason        不可用或不新鲜的原因；可用时为空串
 * @param code          {@link ValidationReason} 里的分类代号，界面据此选视觉，不匹配中文
 * @param candidateHash 计算本结论时使用的产物指纹
 * @param evidenceId    被引用的证据 ID；没有证据时为空串
 * @param recordedAt    被引用证据的记录时间；没有证据时为 {@link Instant#EPOCH}
 */
public record ValidationSnapshot(String status, String reason, String code, String candidateHash, String evidenceId,
    Instant recordedAt) {
    public static final String VERIFIED = "verified";
    public static final String STALE = "stale";
    public static final String UNVERIFIED = "unverified";

    public ValidationSnapshot {
        status = status == null || status.isBlank() ? UNVERIFIED : status.strip();
        reason = reason == null ? "" : reason;
        code = code == null || code.isBlank() ? ValidationReason.NO_EVIDENCE : code;
        candidateHash = candidateHash == null ? "" : candidateHash;
        evidenceId = evidenceId == null ? "" : evidenceId;
        recordedAt = recordedAt == null ? Instant.EPOCH : recordedAt;
    }

    public static ValidationSnapshot unverified(String reason, String code) {
        return new ValidationSnapshot(UNVERIFIED, reason, code, "", "", Instant.EPOCH);
    }

    public static ValidationSnapshot unverified(String reason, String code, String candidateHash) {
        return new ValidationSnapshot(UNVERIFIED, reason, code, candidateHash, "", Instant.EPOCH);
    }

    public boolean verified() {
        return VERIFIED.equals(status);
    }

    /** 曾经有证据，但已经不能证明当前产物。这个状态比「没验证过」更需要提醒用户。 */
    public boolean stale() {
        return STALE.equals(status);
    }
}
