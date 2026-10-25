package com.pilot.core.workspace;

/** 请求的路径超出工作区边界。 */
public class WorkspaceViolationException extends RuntimeException {
    public WorkspaceViolationException(String message) {
        super(message);
    }
}
