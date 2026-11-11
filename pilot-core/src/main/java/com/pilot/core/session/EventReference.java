package com.pilot.core.session;

/** 正式事件过大时只向订阅者发送定位信息，客户端获取并应用原事件后再确认游标。 */
public record EventReference(long sequence, String sessionId, String runId, String type) {}
