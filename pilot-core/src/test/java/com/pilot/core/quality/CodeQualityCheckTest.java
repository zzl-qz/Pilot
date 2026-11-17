package com.pilot.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeQualityCheckTest {

    @Test
    void reportsOnlyNewlyAddedMagicValuesAndCatchesBranchMagicDigits() {
        String before = """
            class Payment {
                void run(int state) {
                    int oldTimeout = 30000;
                }
            }
            """;
        String after = """
            class Payment {
                void run(int state) {
                    int oldTimeout = 30000;
                    if (state == 3) {
                        String status = "PAID";
                        int timeout = 86400;
                    }
                }
            }
            """;

        String summary = CodeQualityCheck.summary("src/main/Payment.java", before, after);

        assertTrue(summary.contains("裸数字 3"), summary);
        assertTrue(summary.contains("86400"), summary);
        assertTrue(summary.contains("PAID"), summary);
        assertFalse(summary.contains("30000"), summary);
    }

    @Test
    void exemptsNamedConstantsEnumsAnnotationsExceptionsAndLogs() {
        String cleanCode = """
            package demo;

            @Size(max = 256)
            public class OrderConfig {
                private static final int MAX_TIMEOUT = 86400;
                public static final String DEFAULT_STATUS = "PENDING";

                public enum OrderState {
                    PENDING(10),
                    PAID(20),
                    CANCELLED(30);

                    private final int code;
                    OrderState(int code) {
                        this.code = code;
                    }
                }

                public void check(boolean valid) {
                    if (!valid) {
                        log.warn("Order status is pending with code 500");
                        throw new IllegalArgumentException("Order failed with status CANCELLED");
                    }
                }
            }
            """;

        assertEquals("", CodeQualityCheck.summary("src/main/OrderConfig.java", null, cleanCode));
    }

    @Test
    void exemptsTypeScriptAndPythonConstants() {
        String tsCode = """
            export const REQUEST_TIMEOUT_MS = 15000;
            export const ORDER_STATUS = {
                PAID: "PAID",
            } as const;
            """;
        assertEquals("", CodeQualityCheck.summary("src/config/constants.ts", null, tsCode));
    }

    @Test
    void detectsRenamedVariableStructuralClonesWhileIgnoringPlainDtos() {
        String renamedClone = """
            class Processor {
                int calcUserDiscount(Order userOrder, int rate) {
                    if (userOrder == null) {
                        return 0;
                    }
                    int base = userOrder.amount() * rate;
                    if (base > 100) {
                        base = base - 10;
                    }
                    notifyAudit(userOrder.id(), base);
                    return Math.max(base, 0);
                }

                int calcMerchantRebate(Settlement item, int ratio) {
                    if (item == null) {
                        return 0;
                    }
                    int total = item.price() * ratio;
                    if (total > 100) {
                        total = total - 10;
                    }
                    recordLedger(item.code(), total);
                    return Math.max(total, 0);
                }
            }
            """;
        String summary = CodeQualityCheck.summary("src/main/Processor.java", null, renamedClone);
        assertTrue(summary.contains("同构代码"), summary);

        String plainDto = """
            public class UserDto {
                private String firstName;
                private String lastName;
                private String email;
                private String phone;
                private String address;
                private String city;
                private String country;
                private String postalCode;
                private String company;
                private String department;
                private String title;
                private String bio;
                private String avatarUrl;
                private String timezone;
                private String locale;
                private String statusText;
            }
            """;
        assertEquals("", CodeQualityCheck.summary("src/main/UserDto.java", null, plainDto));
    }

    @Test
    void recommendsWorkspaceConstantsDetectsBypassAndCrossFileClones(@TempDir Path workspace) throws IOException {
        Path enumFile = workspace.resolve("src/main/java/demo/common/PaymentStatus.java");
        Files.createDirectories(enumFile.getParent());
        Files.writeString(enumFile, """
            package demo.common;
            public enum PaymentStatus {
                PENDING,
                PAID
            }
            """);

        Path constFile = workspace.resolve("src/main/java/demo/common/TimeConstants.java");
        Files.writeString(constFile, """
            package demo.common;
            public final class TimeConstants {
                public static final int DAY_SECONDS = 86400;
            }
            """);

        Path shaFile = workspace.resolve("src/main/java/demo/util/Sha256.java");
        Files.createDirectories(shaFile.getParent());
        Files.writeString(shaFile, """
            package demo.util;
            public final class Sha256 {
                public static String hex(String input) { return input; }
            }
            """);

        Path sibling = workspace.resolve("src/main/java/demo/service/RefundService.java");
        Files.createDirectories(sibling.getParent());
        Files.writeString(sibling, """
            package demo.service;
            public class RefundService {
                int compute(Item item, int factor) {
                    if (item == null) {
                        return 0;
                    }
                    int val = item.amount() * factor;
                    if (val > 100) {
                        val = val - 10;
                    }
                    saveRecord(item.id(), val);
                    return Math.max(val, 0);
                }
            }
            """);

        String candidate = """
            package demo.service;
            import java.security.MessageDigest;
            public class OrderService {
                int handle(Order order, int rate) throws Exception {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    int expire = 86400;
                    String paymentState = "PAID";
                    if (order == null) {
                        return 0;
                    }
                    int result = order.total() * rate;
                    if (result > 100) {
                        result = result - 10;
                    }
                    writeAudit(order.code(), result);
                    return Math.max(result, 0);
                }
            }
            """;

        String summary =
            CodeQualityCheck.summary(workspace, "src/main/java/demo/service/OrderService.java", null, candidate);

        assertTrue(summary.contains("Sha256"), summary);
        assertTrue(summary.contains("TimeConstants.DAY_SECONDS"), summary);
        assertTrue(summary.contains("PaymentStatus.PAID"), summary);
        assertTrue(summary.contains("RefundService.java"), summary);

        // Windows 反斜杠路径同样兼容，且当前文件自身不会被误匹配为推荐源
        String winSummary =
            CodeQualityCheck.summary(workspace, "src\\main\\java\\demo\\service\\OrderService.java", null, candidate);
        assertTrue(winSummary.contains("PaymentStatus.PAID"), winSummary);
        assertTrue(
            CodeQualityCheck.summary(workspace, "src\\test\\java\\OrderServiceTest.java", null, candidate).isEmpty());
    }

    @Test
    void detectsRepeatedStringLiteralsAndDeepNestingWithoutBeingFooledByBracesInStrings() {
        String code = """
            class Handler {
                String process(int a, int b, int c, int d, int e) {
                    String braceNoise = "{{{{{{{{{{{{{{{{";
                    String headerA = "X-Custom-Tenant-Id";
                    String headerB = "X-Custom-Tenant-Id";
                    String headerC = "X-Custom-Tenant-Id";
                    if (a > 0) {
                        for (int i = 0; i < b; i++) {
                            if (c > 0) {
                                while (d > 0) {
                                    if (e > 0) {
                                        return headerA + headerB + headerC + braceNoise;
                                    }
                                }
                            }
                        }
                    }
                    return "";
                }
            }
            """;

        String summary = CodeQualityCheck.summary("src/main/Handler.java", null, code);
        assertTrue(summary.contains("X-Custom-Tenant-Id"), summary);
        assertTrue(summary.contains("5 层"), summary);
    }

    @Test
    void ignoresTestsDocumentsAndCommonSmallNumbers() {
        String code = """
            class Example {
                void run() {
                    int none = 0;
                    int one = 1;
                    int percent = 100;
                }
            }
            """;

        assertTrue(CodeQualityCheck.summary("src/test/java/ExampleTest.java", null, code).isEmpty());
        assertTrue(CodeQualityCheck.summary("src/components/Button.test.tsx", null, "const x = 86400;").isEmpty());
        assertTrue(CodeQualityCheck.summary("docs/guide.md", null, "timeout = 86400").isEmpty());
        assertTrue(CodeQualityCheck.summary("src/main/Example.java", null, code).isEmpty());
        assertTrue(CodeQualityCheck.summary("src/main/contest.java", null, "class Contest { int year = 2026; }\n")
            .contains("2026"));
    }

    @Test
    void oneLineInsideExistingLongMethodIsNotTreatedAsANewLongMethod() {
        StringBuilder method = new StringBuilder("class Example {\n    void run() {\n");
        for (int i = 0; i < 90; i++) {
            method.append("        value += ").append(i).append(";\n");
        }
        method.append("    }\n}\n");
        String before = method.toString();
        String after = before.replace("value += 1;", "value += 86400;");

        String summary = CodeQualityCheck.summary("src/main/Example.java", before, after);

        assertTrue(summary.contains("86400"), summary);
        assertFalse(summary.contains("新增约"), summary);
    }

    @Test
    void detectsSilentCatchLongParameterListsUnderscoreDecimalsAndElseIfLadders() {
        String smelly = """
            class OrderEngine {
                void createOrder(String a, String b, int c, long d, boolean e, String f, int g) {
                    int ttlSeconds = 86_400;
                    double vipRate = 0.85;
                    try {
                        runStep();
                    } catch (Exception ex) {
                    }
                    if (c == 1) {
                        stepOne();
                    } else if (c == 2) {
                        stepTwo();
                    } else if (c == 0) {
                        stepZero();
                    } else if (d == 1) {
                        stepD1();
                    } else if (d == 2) {
                        stepD2();
                    } else if (e) {
                        stepE();
                    } else if (!e) {
                        stepNotE();
                    }
                }
            }
            """;
        String summary = CodeQualityCheck.summary("src/main/OrderEngine.java", null, smelly);
        assertTrue(summary.contains("静默吞掉"), summary);
        assertTrue(summary.contains("86400"), summary);
        assertTrue(summary.contains("0.85"), summary);
        assertTrue(summary.contains("参数列表达 7 个"), summary);
        assertTrue(summary.contains("else-if"), summary);

        // 显式命名 ignored、泛型逗号、不同方法各自的单个 else-if、超长压缩行均不得误报或卡死
        String cleanAndRobust = "public record Config(int a, int b, int c, int d, int e, int f, int g, int h) {}\n"
            + "class Clean {\n" + "    void m1(java.util.Map<String, java.util.Map<Integer, String>> m, int x) {\n"
            + "        try { work(); } catch (Exception ignored) {}\n"
            + "        if (x == 0) { a(); } else if (x == 1) { b(); }\n" + "    }\n"
            + "    void m2(int x) { if (x == 0) { a(); } else if (x == 1) { b(); } }\n"
            + "    void m3(int x) { if (x == 0) { a(); } else if (x == 1) { b(); } }\n"
            + "    void m4(int x) { if (x == 0) { a(); } else if (x == 1) { b(); } }\n"
            + "    void m5(int x) { if (x == 0) { a(); } else if (x == 1) { b(); } }\n"
            + "    void m6(int x) { if (x == 0) { a(); } else if (x == 1) { b(); } }\n" + "}\n";
        assertEquals("", CodeQualityCheck.summary("src/main/Clean.java", null, cleanAndRobust));

        String minified = "function bundle(){" + "var a=86400;".repeat(10_000) + "}";
        assertEquals("", CodeQualityCheck.summary("src/main/app.min.js", null, minified));
        assertEquals("", CodeQualityCheck.summary("src/main/vendor.js", null, minified));
    }

    @Test
    void completionIssueCountsChangedCodeFilesAndLimitsFindings() {
        String issue = CodeQualityCheck.completionIssue(Map.of("src/main/A.java",
            new CodeQualityCheck.TextChange(null, "class A { void f() { int timeout = 86400; } }\n")));

        assertFalse(issue.isBlank());
        assertTrue(issue.contains("1 个代码文件"));

        StringBuilder many = new StringBuilder("class Limits {\n    void f() {\n");
        for (int value = 10_000; value < 10_030; value++) {
            many.append("        int value").append(value).append(" = ").append(value).append(";\n");
        }
        many.append("    }\n}\n");
        assertEquals(8, CodeQualityCheck.inspect("src/main/Limits.java", null, many.toString()).size());
    }
}
