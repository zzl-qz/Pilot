package com.pilot.core.exec;

import java.util.List;

/**
 * 命令执行后端。
 *
 * <p>把「怎么起进程」从命令工具里拆出来，才能在不改业务逻辑的前提下替换成容器或
 * 平台原生沙箱。接口刻意保持很小：可用性、执行、诊断描述。
 */
public interface ExecutionBackend {

    String id();

    ExecutionIsolation isolation();

    /** 当前机器是否真的能用。 */
    boolean available();

    /** 不可用的原因，直接展示给用户和模型，避免「以为隔离了其实没有」。 */
    String unavailableReason();

    ExecutionResult run(ExecutionRequest request);

    /** 诊断用：这次会以什么方式执行（不包含凭据值）。 */
    List<String> describe(ExecutionRequest request);
}
