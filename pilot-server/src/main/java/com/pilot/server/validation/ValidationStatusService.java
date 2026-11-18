package com.pilot.server.validation;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 把「产物此刻值不值得信」算成一个结论，供成果登记、任务卡和诊断接口共用。
 *
 * <p>存在的理由只有一个：不要让每个调用点各自拼一套判断。账本负责存证据，
 * 这里负责拿当前文件内容去比对，并说明为什么不能算通过。
 */
@Service
@RequiredArgsConstructor
public class ValidationStatusService {
    private final ValidationLedger ledger;

    /**
     * 计算当前结论。
     *
     * @param artifactPath 参与判断的产物相对路径；为空时只按账本最近一次证据覆盖的文件集合判断。
     *                     传入该参数后，若证据没有覆盖这个文件，结论降级为未验证——不能拿别的文件的证据冒充自己。
     */
    public ValidationSnapshot snapshot(String sessionId, Path workspace, String artifactPath) {
        List<ValidationEvidence> recent = ledger.recent(sessionId);
        if (recent.isEmpty()) {
            return ValidationSnapshot.unverified("还没有任何校验证据", ValidationReason.NO_EVIDENCE);
        }
        ValidationEvidence latest = recent.getLast();
        if (workspace == null) {
            return ValidationSnapshot.unverified("没有可校验的工作区", ValidationReason.NO_WORKSPACE);
        }
        String current = WorkspaceFingerprint.of(workspace.toAbsolutePath().normalize(), latest.files());
        if (current.isBlank()) {
            return ValidationSnapshot.unverified("这次运行没有记录到产物变化，无需校验", ValidationReason.NO_CHANGES);
        }
        if (!current.equals(latest.candidateHash())) {
            return new ValidationSnapshot(ValidationSnapshot.STALE, "证据针对的是旧产物，文件已经变化",
                ValidationReason.STALE_CANDIDATE, current, latest.id(), latest.recordedAt());
        }
        if (ValidationEvidence.SKIPPED.equals(latest.status())) {
            return ValidationSnapshot.unverified("这一轮没有跑完校验", ValidationReason.SKIPPED, current);
        }
        if (!latest.passed()) {
            return ValidationSnapshot.unverified("最近一次校验没有通过（" + latest.status() + "）", ValidationReason.FAILED,
                current);
        }
        if (artifactPath != null && !artifactPath.isBlank() && !covers(latest.files(), artifactPath)) {
            return ValidationSnapshot.unverified("最近一次校验没有覆盖这个文件", ValidationReason.NOT_COVERED, current);
        }
        return new ValidationSnapshot(ValidationSnapshot.VERIFIED, "", ValidationReason.OK, current, latest.id(),
            latest.recordedAt());
    }

    /** 比较路径时忽略分隔符差异：账本里存的是工具写过的相对路径，调用方可能传入平台风格的写法。 */
    private static boolean covers(List<String> files, String artifactPath) {
        String target = normalize(artifactPath);
        return files.stream().map(ValidationStatusService::normalize).anyMatch(target::equals);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').strip();
    }
}
