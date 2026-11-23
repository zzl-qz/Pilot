package com.pilot.core.coordination;

/**
 * 工作区事件流的上限：历史缓存、单条事件、订阅数与每个订阅的排队长度都有界。
 *
 * @param maxEvents       历史缓存最多保留多少条事件，更早的会被挤掉
 * @param maxBytes        历史缓存最多占用多少估算字节
 * @param maxEventBytes   单条事件的估算字节上限，超出部分在发布时被截断
 * @param maxSubscribers  同时存在的订阅上限
 * @param queueItems      每个订阅的待投递队列长度，满了即判定跟不上
 * @param replayPageSize  回放时每次从历史取多少条
 */
public record WorkspaceEventLimits(int maxEvents, long maxBytes, int maxEventBytes, int maxSubscribers, int queueItems,
    int replayPageSize) {

    public WorkspaceEventLimits {
        if (maxEvents < 1 || maxBytes < 1 || maxEventBytes < 128 || maxSubscribers < 1 || queueItems < 1
            || replayPageSize < 1) {
            throw new IllegalArgumentException("工作区事件上限必须为正数，且单条事件上限至少 128 字节");
        }
    }

    /** 默认上限：2000 条 / 4 MiB 历史，单条 8 KiB，256 个订阅，每个队列 512 条。 */
    public static WorkspaceEventLimits defaults() {
        return new WorkspaceEventLimits(2000, 4L * 1024 * 1024, 8 * 1024, 256, 512, 256);
    }
}
