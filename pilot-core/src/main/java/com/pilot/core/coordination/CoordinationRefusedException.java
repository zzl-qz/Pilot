package com.pilot.core.coordination;

/**
 * 协调操作被拒绝：带上机器可读的原因码和当前占用方，界面据此说明「为什么不能做」。
 */
public final class CoordinationRefusedException extends RuntimeException {

    /** 会话或条目不存在。 */
    public static final String NOT_FOUND = "not-found";
    /** 目标不在发起方的协调范围内。 */
    public static final String OUT_OF_SCOPE = "out-of-scope";
    /** 没有权限：只有目标会话能接受/拒绝，只有发起会话能撤销。 */
    public static final String DENIED = "denied";
    /** 位置已被别的会话占用。 */
    public static final String CLAIMED = "claimed";
    /** 条目已经结束（接受/拒绝/撤销/过期），不能再推进。 */
    public static final String CLOSED = "closed";

    private final String code;
    private final String ownerSessionId;
    private final String resource;

    public CoordinationRefusedException(String code, String ownerSessionId, String resource, String message) {
        super(message);
        this.code = code;
        this.ownerSessionId = ownerSessionId;
        this.resource = resource;
    }

    public String code() {
        return code;
    }

    /** 当前占用方或相关会话，界面上直接展示给用户；没有则为 {@code null}。 */
    public String ownerSessionId() {
        return ownerSessionId;
    }

    public String resource() {
        return resource;
    }
}
