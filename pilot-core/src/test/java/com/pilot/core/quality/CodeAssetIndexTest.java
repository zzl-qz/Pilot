package com.pilot.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeAssetIndexTest {
    @TempDir
    Path workspace;

    @Test
    void indexesJavaTypeScriptPythonAndNamingConventionFilesWithoutBuildOrTestNoise() throws IOException {
        Path javaUtil = workspace.resolve("src/main/java/common/Texts.java");
        Files.createDirectories(javaUtil.getParent());
        Files.writeString(javaUtil, """
            package common;

            public final class Texts {
                public static final int MAX_LEN = 240;

                public static String oneLine(String text, int maxChars) {
                    return text;
                }
            }
            """);

        Path domainEnum = workspace.resolve("src/main/java/order/OrderStatusEnum.java");
        Files.createDirectories(domainEnum.getParent());
        Files.writeString(domainEnum, """
            package order;

            public enum OrderStatusEnum {
                PENDING,
                COMPLETED
            }
            """);

        Path tsLib = workspace.resolve("frontend/src/lib/format.ts");
        Files.createDirectories(tsLib.getParent());
        Files.writeString(tsLib, """
            export const DEFAULT_PAGE_SIZE = 50;
            export function formatCurrency(cents: number): string {
                return String(cents);
            }
            """);

        Path tsHook = workspace.resolve("frontend/src/features/useDebounce.ts");
        Files.createDirectories(tsHook.getParent());
        Files.writeString(tsHook, """
            export const useDebounce = (value: string, delay: number) => value;
            """);

        Path pyHelper = workspace.resolve("scripts/utils/retry_helper.py");
        Files.createDirectories(pyHelper.getParent());
        Files.writeString(pyHelper, """
            def retry_call(fn, attempts):
                return fn()
            """);

        Path generated = workspace.resolve("target/classes/common/Generated.java");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, "public class Generated { public static void ignored() {} }\n");

        Path testHelper = workspace.resolve("src/test/java/common/TestHelper.java");
        Files.createDirectories(testHelper.getParent());
        Files.writeString(testHelper, "public class TestHelper { public static void ignored() {} }\n");

        CodeAssetIndex.Snapshot snapshot = CodeAssetIndex.scan(workspace);
        String summary = snapshot.render();

        assertTrue(summary.contains("Texts"), summary);
        assertTrue(summary.contains("oneLine"), summary);
        assertTrue(summary.contains("OrderStatusEnum"), summary);
        assertTrue(summary.contains("formatCurrency"), summary);
        assertTrue(summary.contains("useDebounce"), summary);
        assertTrue(summary.contains("retry_call"), summary);
        assertFalse(summary.contains("Generated"), summary);
        assertFalse(summary.contains("TestHelper"), summary);

        CodeAssetIndex.ConstantRef enumMatch = snapshot.recommendConstant("COMPLETED", "src/main/java/order/Svc.java");
        assertNotNull(enumMatch);
        assertEquals("OrderStatusEnum.COMPLETED", enumMatch.symbol());

        CodeAssetIndex.ConstantRef numMatch =
            snapshot.recommendConstant("50", "frontend/src/App.tsx", "const pageSize = 50;");
        assertNotNull(numMatch);
        assertTrue(numMatch.symbol().endsWith(".DEFAULT_PAGE_SIZE"), numMatch.symbol());

        // 数值相同但业务量纲冲突（文件上传数量 vs 分页大小）时绝不乱推
        assertEquals(null,
            snapshot.recommendConstant("50", "frontend/src/AttachmentUploader.tsx", "const maxUploadFiles = 50;"));
    }

    @Test
    void returnsEmptyWhenNoReusableCodeExists() {
        assertTrue(CodeAssetIndex.summarize(workspace).isEmpty());
    }
}
