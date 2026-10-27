package com.pilot.server.multi;

import com.pilot.core.multi.SandboxChange;

import java.nio.file.Path;
import java.util.List;

/**
 * 非 LLM 硬校验层：编译器与测试结果优先于任何 Agent（包括父 Agent）的主观判断。
 *
 * <p>实现必须在「基线 + 沙箱改动」的隔离副本里跑，不能污染真实工作区；
 * 校验不通过时变更不允许进入审批池。
 */
public interface VerificationGate {

    /**
     * @param workspace  真实工作区（只读，用来创建隔离副本）
     * @param baseCommit 锁定的基线 commit
     * @param changes    待校验的沙箱改动
     */
    Report verify(Path workspace, String baseCommit, List<SandboxChange> changes);

    /**
     * @param status {@code passed} / {@code failed} / {@code skipped}
     */
    record Report(String status, List<String> checks, String detail) {
        public static final String PASSED = "passed";
        public static final String FAILED = "failed";
        public static final String SKIPPED = "skipped";

        public Report {
            status = status == null ? SKIPPED : status;
            checks = checks == null ? List.of() : List.copyOf(checks);
            detail = detail == null ? "" : detail;
        }

        public boolean ok() {
            return PASSED.equals(status);
        }

        public static Report skipped(String reason) {
            return new Report(SKIPPED, List.of(), reason);
        }

        public static Report passed(List<String> checks) {
            return new Report(PASSED, checks, "编译与测试通过");
        }

        public static Report failed(List<String> checks, String detail) {
            return new Report(FAILED, checks, detail);
        }
    }
}
