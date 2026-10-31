package com.pilot.core.multi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 跨平台行为回归。
 *
 * <p>项目主要在 Windows 上开发，但 CI 要覆盖 Linux/macOS。这里固定几个容易只在某个平台
 * 出问题的点：路径分隔符、大小写、符号链接、换行。符号链接在无权限账户下无法创建，
 * 这种环境跳过而不是失败——跳过本身也是信息，说明该平台没有验证到符号链接行为。
 */
class CrossPlatformPathTest {

    @TempDir
    Path workspace;

    @Test
    void sandboxPathsAreSeparatorAndCaseInsensitive() {
        DiffSandbox sandbox = new DiffSandbox("sbx_1", "agent_1", "T1", "");
        sandbox.add("src\\Feature\\A.java", "", "class A {}", false, RiskLevel.LOW);

        assertTrue(sandbox.hasChange("src/feature/a.java"),
                "Windows 与 POSIX 的路径分隔符、大小写差异不应产生两份改动");
        assertEquals(1, sandbox.fileCount());
    }

    @Test
    void artifactPathsAreNormalizedToForwardSlashes() {
        ArtifactFile file = ArtifactFile.of("src\\main\\A.java", "", "class A {}");
        assertEquals("src/main/A.java", file.path());
    }

    @Test
    void crlfSourcesStillProduceADiff() {
        String oldText = "line1\r\nline2\r\n";
        String newText = "line1\r\nCHANGED\r\n";

        String diff = UnifiedDiff.create("src/A.txt", oldText, newText);

        assertTrue(diff.contains("+CHANGED"), diff);
        assertTrue(newText.contains("\r\n"));
    }

    @Test
    void symlinkTestIsSkippedWhenThePlatformRefusesIt() throws IOException {
        Path link = workspace.resolve("link.txt");
        Path target = workspace.resolve("target.txt");
        Files.writeString(target, "x");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            // Windows 非管理员/未开启开发者模式时常见；跳过并保留原因
            assumeTrue(false, "当前账户无法创建符号链接：" + e.getMessage());
            return;
        }
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    void fileNamesWithSpacesAndUnicodeSurviveRoundTrip() {
        ArtifactFile file = ArtifactFile.of("docs/带 空格 的文档.md", "", "# 标题");
        assertTrue(file.path().contains("带 空格"));
        assertTrue(file.contentHash().length() == 64);
    }
}
