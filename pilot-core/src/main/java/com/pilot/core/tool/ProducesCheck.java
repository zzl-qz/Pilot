package com.pilot.core.tool;

/**
 * 会产生「验证结果」的工具实现该接口，例如执行构建或测试的命令工具。
 * 摘要会汇总进 {@code run.completed.checks}，让用户看到这次运行到底验证了什么。
 */
public interface ProducesCheck {
    /**
     * @param call   本次工具调用
     * @param result 工具返回给模型的完整文本
     * @return 一行摘要，例如 {@code mvn -q test → exit 0}；没有可汇总的信息时返回 {@code null}
     */
    String checkSummary(ToolCall call, String result);
}
