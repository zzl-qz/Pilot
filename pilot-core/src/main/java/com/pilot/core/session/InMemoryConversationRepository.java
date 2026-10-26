package com.pilot.core.session;

import com.pilot.core.loop.Message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内实现：给 memory 模式和测试用，重启即丢失。 */
public final class InMemoryConversationRepository implements ConversationRepository {
    private final ConcurrentMap<String, List<Message>> conversations = new ConcurrentHashMap<>();

    @Override
    public List<Message> load(String sessionId) {
        return conversations.getOrDefault(sessionId, List.of());
    }

    @Override
    public void save(String sessionId, List<Message> messages) {
        conversations.put(sessionId, List.copyOf(messages));
    }

    @Override
    public void delete(String sessionId) {
        conversations.remove(sessionId);
    }

    Map<String, List<Message>> snapshot() {
        return Map.copyOf(conversations);
    }
}
