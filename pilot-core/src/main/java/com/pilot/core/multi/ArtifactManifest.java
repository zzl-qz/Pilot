package com.pilot.core.multi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 候选产物的不可变清单。
 *
 * <p>候选内容哈希只回答「现在是什么」，回答不了「这是哪一版、由什么输入产生、依赖了谁」。
 * 清单固定以下事实：
 * <ul>
 *   <li>基线 commit 与每个文件的基线指纹；</li>
 *   <li>候选内容指纹与清单自身指纹（candidateHash）；</li>
 *   <li>依赖的上游清单指纹，上游一变就能判断下游结果失效；</li>
 *   <li>修订号与创建时间，形成可比较的历史版本。</li>
 * </ul>
 *
 * <p>清单只读：再次修改产生一个新修订，不会改动旧清单。这样校验结果才能精确绑定到某一版。
 *
 * @param candidateHash 清单指纹；验证结果、接受变更都绑定这个值
 * @param revision      同一沙箱内的修订号，从 1 开始
 */
public record ArtifactManifest(String manifestId, String sessionId, String sandboxId, String taskId, String baseCommit,
    String environmentFingerprint, List<ArtifactFile> files, List<String> dependencyManifests,
    List<String> dependencyHashes, int revision, String candidateHash, Instant createdAt) {

    public ArtifactManifest {
        manifestId = requireText(manifestId, "manifestId");
        sessionId = requireText(sessionId, "sessionId");
        sandboxId = requireText(sandboxId, "sandboxId");
        taskId = requireText(taskId, "taskId");
        baseCommit = baseCommit == null ? "" : baseCommit.strip();
        environmentFingerprint = environmentFingerprint == null ? "" : environmentFingerprint.strip();
        files = files == null ? List.of()
            : files.stream().filter(Objects::nonNull).sorted(Comparator.comparing(ArtifactFile::path)).toList();
        dependencyManifests = normalized(dependencyManifests);
        dependencyHashes = normalized(dependencyHashes);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        candidateHash = requireText(candidateHash, "candidateHash");
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }

    /** 由当前沙箱内容生成一版清单。 */
    public static ArtifactManifest create(String sessionId, String sandboxId, String taskId, String baseCommit,
        String environmentFingerprint, List<ArtifactFile> files, List<String> dependencyManifests,
        List<String> dependencyHashes, int revision, Instant createdAt) {
        List<ArtifactFile> sorted = files == null ? List.of()
            : files.stream().filter(Objects::nonNull).sorted(Comparator.comparing(ArtifactFile::path)).toList();
        List<String> manifests = normalized(dependencyManifests);
        List<String> hashes = normalized(dependencyHashes);
        String canonical = String.join("\n", "session=" + sessionId, "sandbox=" + sandboxId, "task=" + taskId,
            "base=" + (baseCommit == null ? "" : baseCommit.strip()),
            "env=" + (environmentFingerprint == null ? "" : environmentFingerprint.strip()),
            sorted.stream()
                .map(file -> file.path() + "\0" + file.baselineHash() + "\0" + file.contentHash() + "\0" + file.size())
                .collect(Collectors.joining("\n")),
            "deps=" + String.join(",", hashes), "depManifests=" + String.join(",", manifests));
        String candidate = DiffSandbox.fingerprint(canonical);
        return new ArtifactManifest("man_" + candidate.substring(0, 16), sessionId, sandboxId, taskId, baseCommit,
            environmentFingerprint, sorted, manifests, hashes, revision, candidate, createdAt);
    }

    /** 用当前内容重算清单，修订号加一；内容没变时返回新修订但 candidateHash 相同。 */
    public ArtifactManifest nextRevision(List<ArtifactFile> newFiles, List<String> newDependencyHashes,
        String newEnvironment, Instant now) {
        return create(sessionId, sandboxId, taskId, baseCommit,
            newEnvironment == null ? environmentFingerprint : newEnvironment, newFiles, dependencyManifests,
            newDependencyHashes, revision + 1, now);
    }

    /** 与上一版比较：新增、删除、修改了哪些文件，候选指纹是否变化。 */
    public ArtifactDiff diff(ArtifactManifest previous) {
        Map<String, String> before = previous == null ? Map.of() : previous.files().stream()
            .collect(Collectors.toMap(ArtifactFile::path, ArtifactFile::contentHash, (a, b) -> a));
        Map<String, String> after =
            files.stream().collect(Collectors.toMap(ArtifactFile::path, ArtifactFile::contentHash, (a, b) -> a));
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        for (Map.Entry<String, String> entry : after.entrySet()) {
            String previousHash = before.get(entry.getKey());
            if (previousHash == null) {
                added.add(entry.getKey());
            } else if (!previousHash.equals(entry.getValue())) {
                modified.add(entry.getKey());
            } else {
                unchanged.add(entry.getKey());
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                removed.add(path);
            }
        }
        return new ArtifactDiff(previous == null ? null : previous.candidateHash(), candidateHash, sorted(added),
            sorted(removed), sorted(modified), sorted(unchanged));
    }

    private static List<String> normalized(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                unique.add(value.strip());
            }
        }
        return List.copyOf(unique).stream().sorted().toList();
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
