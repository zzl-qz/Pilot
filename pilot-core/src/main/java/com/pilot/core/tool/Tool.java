package com.pilot.core.tool;

import com.pilot.core.prompt.PromptLibrary;

import java.util.Map;

/**
 * 模型可调用的单一操作。实现只负责执行，不负责权限判断与事件上报。
 */
public interface Tool {
    String name();

    String description();

    /**
     * 从 {@code prompts/tools/<name>.md} 读取工具描述。
     *
     * <p>工具描述是模型行为最主要的杠杆之一，放在资源文件里才能不改代码就调。
     * 需要插值的实现（例如命令白名单）自己调 {@link PromptLibrary#render}。
     */
    default String promptDescription() {
        return PromptLibrary.standard().get("tools/" + name()).strip();
    }

    /** JSON Schema，用于生成请求中的 tools 定义。 */
    Map<String, Object> parameters();

    /** 是否只读。Sprint 2 的 READ_ONLY 策略据此放行。 */
    boolean readOnly();

    /** 同一模型回复中的多个此类调用能否并行执行；只读并不自动意味着并行安全。 */
    default boolean parallelSafe() {
        return false;
    }

    /**
     * 执行工具。
     *
     * @param args 已解析的参数
     * @return 返回给模型的文本结果；失败时以 {@code ERROR: } 开头说明原因
     */
    String execute(Map<String, Object> args);

    /**
     * 需要把多个改动路径或验证项传给父执行循环的工具可覆盖此方法。
     * 普通工具仍只需实现 {@link #execute(Map)}，行为保持不变。
     */
    default ToolResult executeResult(Map<String, Object> args) {
        return ToolResult.text(execute(args));
    }
}
