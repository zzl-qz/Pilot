package com.pilot.core.multi;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactManifestTest {
    private static final Instant NOW = Instant.parse("2026-10-31T08:00:00Z");

    private static ArtifactManifest manifest(int revision, List<ArtifactFile> files, List<String> deps) {
        return ArtifactManifest.create("ses_1", "box_1", "task_1", "abc123", "linux|amd64|java25",
                files, List.of("man_upstream"), deps, revision, NOW);
    }

    @Test
    void candidateHashTracksContentNotRevision() {
        List<ArtifactFile> files = List.of(ArtifactFile.of("src/A.java", "old", "class A {}"));
        ArtifactManifest first = manifest(1, files, List.of("dep-a"));
        ArtifactManifest second = manifest(2, files, List.of("dep-a"));

        assertEquals(first.candidateHash(), second.candidateHash(),
                "内容没变时修订号变化不应改变候选指纹");
        assertEquals(1, first.revision());
        assertEquals(2, second.revision());
    }

    @Test
    void candidateHashChangesWhenDependencyChanges() {
        List<ArtifactFile> files = List.of(ArtifactFile.of("src/A.java", "old", "class A {}"));

        assertNotEquals(manifest(1, files, List.of("dep-a")).candidateHash(),
                manifest(1, files, List.of("dep-b")).candidateHash(),
                "上游指纹变化必须传导到下游候选指纹");
    }

    @Test
    void diffSeparatesAddedModifiedAndRemoved() {
        ArtifactManifest before = manifest(1, List.of(
                ArtifactFile.of("src/A.java", "", "class A {}"),
                ArtifactFile.of("src/B.java", "", "class B {}")), List.of());
        ArtifactManifest after = manifest(2, List.of(
                ArtifactFile.of("src/A.java", "", "class A { int x; }"),
                ArtifactFile.of("src/C.java", "", "class C {}")), List.of());

        ArtifactDiff diff = after.diff(before);

        assertEquals(List.of("src/C.java"), diff.added());
        assertEquals(List.of("src/B.java"), diff.removed());
        assertEquals(List.of("src/A.java"), diff.modified());
        assertTrue(diff.changed());
        assertEquals(before.candidateHash(), diff.previousCandidate());
        assertEquals(after.candidateHash(), diff.currentCandidate());
    }

    @Test
    void inputBundleCapturesBaselineRatherThanCurrentContent() {
        ArtifactManifest current = manifest(1, List.of(
                ArtifactFile.of("src/A.java", "baseline-hash", "class A { changed }")), List.of("dep-a"));
        InputBundle bundle = InputBundle.from(current, NOW);

        assertTrue(bundle.fingerprint().length() >= 32);
        assertEquals(1, bundle.inputs().size());
        // 输入快照看的是基线：之后代码再改，输入仍然描述「当时基于什么开始」
        assertTrue(bundle.matches("abc123", List.of("dep-a"), "linux|amd64|java25"));
        assertFalse(bundle.matches("def456", List.of("dep-a"), "linux|amd64|java25"),
                "基线 commit 变了，输入就不再是同一份");
        assertFalse(bundle.matches("abc123", List.of("dep-b"), "linux|amd64|java25"),
                "依赖变了，输入也不再是同一份");
    }

    @Test
    void manifestIsSortedAndImmutable() {
        ArtifactManifest manifest = manifest(1, List.of(
                ArtifactFile.of("b.txt", "", "b"),
                ArtifactFile.of("a.txt", "", "a")), List.of("z", "a"));

        assertEquals(List.of("a.txt", "b.txt"),
                manifest.files().stream().map(ArtifactFile::path).toList());
        assertEquals(List.of("a", "z"), manifest.dependencyHashes());
        assertTrue(manifest.manifestId().startsWith("man_"));
    }
}
