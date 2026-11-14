package com.pilot.server.validation;

import java.time.Instant;
import java.util.List;

/**
 * 一次校验证据：把「任务成功」绑定到具体产物、命令、环境和结果。
 *
 * <p>只记录模型自述的「测试通过」没有意义。这里固定：候选产物指纹（跑了哪些文件、内容是什么）、
 * 实际执行的命令与结果行、环境指纹、所属运行。之后任何一项对不上，这条证据就不再支撑完成声明。
 *
 * @param candidateHash 本次证据覆盖的产物指纹
 * @param checks        实际执行过的校验命令与结果摘要
 * @param status        passed / failed / skipped
 */
public record ValidationEvidence(String id, String sessionId, String runId, String candidateHash, List<String> files,
    List<String> checks, String status, String environmentFingerprint, String source, Instant recordedAt) {
    public static final String PASSED = "passed";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    public ValidationEvidence {
        files = files == null ? List.of() : List.copyOf(files);
        checks = checks == null ? List.of() : List.copyOf(checks);
        status = status == null || status.isBlank() ? SKIPPED : status.strip();
        environmentFingerprint = environmentFingerprint == null ? "" : environmentFingerprint;
        source = source == null ? "" : source;
        recordedAt = recordedAt == null ? Instant.EPOCH : recordedAt;
    }

    public boolean passed() {
        return PASSED.equals(status);
    }
}
