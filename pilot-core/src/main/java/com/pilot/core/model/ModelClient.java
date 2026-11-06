package com.pilot.core.model;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.pilot.core.loop.Message;

public interface ModelClient {
    /** 一次请求冻结端点、模型及计价配置，热更新从下一请求生效。 */
    default ModelClient snapshot() {
        return this;
    }

    default ModelProfile profile() {
        return ModelProfile.generic();
    }

    /** Provider-side capabilities handled inside the model request rather than by Pilot tools. */
    default Set<NativeCapability> nativeCapabilities() {
        return Set.of();
    }

    /** 仅明确的参数拒绝允许去掉优化参数重试一次，网络失败不走此路径。 */
    default boolean disableRejectedCache(Throwable failure) {
        return false;
    }

    /** 实际端点明确拒绝原生工具时，恢复本地工具；网络/权限错误不能通过此入口重放。 */
    default boolean disableRejectedNativeTools(Throwable failure) {
        return false;
    }

    ModelResponse chat(List<Message> messages, List<ToolDefinition> tools);

    default ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta) {
        return chat(messages, tools, textDelta, null);
    }

    /**
     * @param reasoningDelta 模型单独返回的推理增量；没有这条通道时实现可以忽略
     */
    default ModelResponse chat(List<Message> messages, List<ToolDefinition> tools, Consumer<String> textDelta,
        Consumer<String> reasoningDelta) {
        ModelResponse response = chat(messages, tools);
        if (textDelta != null && response.content() != null && !response.content().isEmpty()) {
            textDelta.accept(response.content());
        }
        return response;
    }
}
