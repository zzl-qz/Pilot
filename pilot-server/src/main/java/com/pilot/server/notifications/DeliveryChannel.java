package com.pilot.server.notifications;

import com.pilot.core.util.WireEnum;

/** 通知渠道。名字就是投递记录和接口里的字符串。 */
public enum DeliveryChannel implements WireEnum {
    /** 桌面端自己来领取并弹通知。 */
    DESKTOP,
    /** 服务端向用户配置的地址发 HTTP 请求。 */
    WEBHOOK
}
