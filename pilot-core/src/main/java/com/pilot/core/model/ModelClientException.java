package com.pilot.core.model;

/** 模型调用失败（网络、HTTP 状态、响应结构或工具参数解析）。 */
public class ModelClientException extends RuntimeException {
    public ModelClientException(String message) {
        super(message);
    }

    public ModelClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
