package com.pilot.core.session;

import com.pilot.core.loop.Message;

import java.util.List;

/**
 * 对话记忆存储：保存「真正发给模型的消息」，与会话事件（给人看的审计日志）分开。
 *
 * <p>分开的原因：事件要还原成模型消息需要重建 tool_call_id 与顺序，成本高且容易错；
 * 而压缩后的交接摘要也无法从事件还原。长任务必须把记忆显式落盘。
 */
public interface ConversationRepository {

    List<Message> load(String sessionId);

    /** 覆盖写入该会话的全部消息；消息量在几十到几百条量级，整表替换足够。 */
    void save(String sessionId, List<Message> messages);

    void delete(String sessionId);

    static ConversationRepository noop() {
        return new ConversationRepository() {
            @Override
            public List<Message> load(String sessionId) {
                return List.of();
            }

            @Override
            public void save(String sessionId, List<Message> messages) {
            }

            @Override
            public void delete(String sessionId) {
            }
        };
    }
}
