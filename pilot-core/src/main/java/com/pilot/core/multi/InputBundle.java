package com.pilot.core.multi;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 子任务开始时的输入快照。
 *
 * <p>只有产物清单是不够的：同样的 diff 套在不同基线上会产生不同结果。输入快照固定
 * 基线 commit、参与文件在**修改前**的指纹、上游依赖指纹和采集时间，
 * 让「这次校验针对的是哪份输入」可以被复核，也让还原与差异比较有依据。
 */
public record InputBundle(String sessionId, String sandboxId, String baseCommit,
                          String environmentFingerprint, List<ArtifactFile> inputs,
                          List<String> dependencyHashes, Instant capturedAt, String fingerprint) {

    public InputBundle {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (sandboxId == null || sandboxId.isBlank()) {
            throw new IllegalArgumentException("sandboxId must not be blank");
        }
        baseCommit = baseCommit == null ? "" : baseCommit.strip();
        environmentFingerprint = environmentFingerprint == null ? "" : environmentFingerprint.strip();
        inputs = inputs == null ? List.of() : inputs.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ArtifactFile::path))
                .toList();
        dependencyHashes = dependencyHashes == null ? List.of() : dependencyHashes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .sorted()
                .toList();
        capturedAt = capturedAt == null ? Instant.EPOCH : capturedAt;
        fingerprint = fingerprint == null || fingerprint.isBlank()
                ? computeFingerprint(baseCommit, environmentFingerprint, inputs, dependencyHashes)
                : fingerprint.strip();
    }

    /** 从产物清单倒推输入：基线指纹 + 基线 commit + 依赖指纹。 */
    public static InputBundle from(ArtifactManifest manifest, Instant capturedAt) {
        return new InputBundle(manifest.sessionId(), manifest.sandboxId(), manifest.baseCommit(),
                manifest.environmentFingerprint(), manifest.files(), manifest.dependencyHashes(),
                capturedAt, null);
    }

    private static String computeFingerprint(String baseCommit, String environment,
                                             List<ArtifactFile> inputs, List<String> dependencies) {
        String canonical = String.join("\n",
                "base=" + baseCommit,
                "env=" + environment,
                inputs.stream().map(file -> file.path() + "\0" + file.baselineHash())
                        .collect(Collectors.joining("\n")),
                "deps=" + String.join(",", dependencies));
        return DiffSandbox.fingerprint(canonical);
    }

    /** 输入是否仍然与当前基线一致；不一致表示清单不能再当作「基于这份输入验证过」。 */
    public boolean matches(String currentBaseCommit, List<String> currentDependencyHashes,
                           String currentEnvironment) {
        return computeFingerprint(currentBaseCommit == null ? "" : currentBaseCommit.strip(),
                currentEnvironment == null ? "" : currentEnvironment.strip(),
                inputs,
                currentDependencyHashes == null ? List.of() : currentDependencyHashes.stream()
                        .filter(value -> value != null && !value.isBlank()).map(String::strip).sorted().toList())
                .equals(fingerprint);
    }
}
