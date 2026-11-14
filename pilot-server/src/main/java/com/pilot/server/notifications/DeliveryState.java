package com.pilot.server.notifications;

import com.pilot.core.util.WireEnum;

/** 一条通知投递的状态。名字就是投递记录和接口里的字符串。 */
public enum DeliveryState implements WireEnum {
    PENDING, SENDING, RETRY, DELIVERED, FAILED, CANCELLED;

    /** 还在排队或发送中，后面还会被领取。 */
    public static boolean open(String state) {
        return WireEnum.in(state, PENDING, RETRY, SENDING);
    }

    /** 失败过，可以手动重试。 */
    public static boolean retryable(String state) {
        return WireEnum.in(state, FAILED, RETRY);
    }
}
