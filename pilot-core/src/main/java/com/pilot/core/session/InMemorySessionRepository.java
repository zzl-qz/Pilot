package com.pilot.core.session;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.SessionEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 进程内存储：默认实现，也是测试用的轻量实现。重启后数据丢失。 */
public final class InMemorySessionRepository implements SessionRepository {
    private final ConcurrentMap<String, State> sessions = new ConcurrentHashMap<>();

    @Override
    public Session create(String sessionId, String title, String workspace, Instant createdAt, String projectId,
                          boolean isBlank) {
        var state = new State(sessionId, title, workspace, createdAt, projectId, isBlank);
        sessions.put(sessionId, state);
        return state.snapshot();
    }

    @Override
    public boolean markNotBlank(String sessionId) {
        State state = sessionId == null ? null : sessions.get(sessionId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            boolean changed = state.isBlank;
            state.isBlank = false;
            return changed;
        }
    }

    @Override
    public boolean setArchived(String sessionId, boolean archived) {
        State state = sessionId == null ? null : sessions.get(sessionId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            state.archived = archived;
        }
        return true;
    }

    @Override
    public boolean updateWorkspace(String sessionId, String workspace) {
        State state = sessionId == null ? null : sessions.get(sessionId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            state.workspace = workspace;
        }
        return true;
    }

    @Override
    public Optional<Session> find(String sessionId) {
        State state = sessionId == null ? null : sessions.get(sessionId);
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    @Override
    public List<Session> list() {
        return sessions.values().stream()
                .map(State::snapshot)
                .sorted(Comparator.comparing(Session::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean rename(String sessionId, String title) {
        State state = sessions.get(sessionId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            state.title = title;
        }
        return true;
    }

    @Override
    public boolean delete(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    @Override
    public List<EventEnvelope> events(String sessionId) {
        State state = require(sessionId);
        synchronized (state) {
            return List.copyOf(state.events);
        }
    }

    @Override
    public List<EventEnvelope> eventsAfter(String sessionId, long afterSequence) {
        State state = require(sessionId);
        synchronized (state) {
            var result = new ArrayList<EventEnvelope>();
            for (EventEnvelope envelope : state.events) {
                if (envelope.sequence() > afterSequence) {
                    result.add(envelope);
                }
            }
            return List.copyOf(result);
        }
    }

    @Override
    public int dropEventsFrom(String sessionId, long fromSequence) {
        State state = require(sessionId);
        synchronized (state) {
            int before = state.events.size();
            state.events.removeIf(envelope -> envelope.sequence() >= fromSequence);
            return before - state.events.size();
        }
    }

    @Override
    public long latestSequence(String sessionId) {
        State state = sessionId == null ? null : sessions.get(sessionId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.sequence;
        }
    }

    @Override
    public EventEnvelope append(String sessionId, String runId, Instant timestamp, SessionEvent event,
                                SessionStatus status) {
        State state = require(sessionId);
        synchronized (state) {
            var envelope = EventEnvelope.of(++state.sequence, timestamp, sessionId, runId, event);
            state.events.add(envelope);
            state.status = status;
            return envelope;
        }
    }

    private State require(String sessionId) {
        State state = sessionId == null ? null : sessions.get(sessionId);
        if (state == null) {
            throw new NoSuchSessionException(sessionId);
        }
        return state;
    }

    private static final class State {
        private final String id;
        private final Instant createdAt;
        private final String projectId;
        private final List<EventEnvelope> events = new ArrayList<>();
        private String title;
        private String workspace;
        private SessionStatus status = SessionStatus.CREATED;
        private long sequence;
        private boolean isBlank;
        private boolean archived;

        private State(String id, String title, String workspace, Instant createdAt, String projectId,
                      boolean isBlank) {
            this.id = id;
            this.title = title;
            this.workspace = workspace;
            this.createdAt = createdAt;
            this.projectId = projectId;
            this.isBlank = isBlank;
        }

        private Session snapshot() {
            synchronized (this) {
                return new Session(id, title, createdAt, status, workspace, projectId, isBlank, archived);
            }
        }
    }
}
