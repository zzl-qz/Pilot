package com.pilot.core.browser;

/**
 * 浏览器证据记录回调：由宿主机在工具执行边界记录真实结果，不依赖模型复述。
 */
@FunctionalInterface
public interface BrowserEvidenceRecorder {
    void record(BrowserEvidenceRecord record);

    static BrowserEvidenceRecorder none() {
        return record -> {
        };
    }
}
