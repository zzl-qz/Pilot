package com.pilot.core.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.pilot.core.event.EventEnvelope;
import com.pilot.core.event.SessionEvent;

/** 进程内存储：默认实现，也是测试用的轻量实现。重启后数据丢失。 */
public class InMemorySessionRepository implements SessionRepository {
    private final ConcurrentMap<String, State> sessions = new ConcurrentHashMap<>();

    @Override
    public <T> T transaction(String sessionId, java.util.function.Supplier<T> work) {
        State state = require(sessionId);
        synchronized (state) {
            List<EventEnvelope> before = List.copyOf(state.events);
            SessionStatus status = state.status;
            long revision = state.historyRevision;
            try { return work.get(); }
            catch (RuntimeException | Error failure) {
                state.events.clear(); state.events.addAll(before);
                state.status = status; state.historyRevision = revision;
                throw failure;
            }
        }
    }

    @Override
    public long historyRevision(String sessionId) {
        State state = require(sessionId);
        synchronized (state) { return state.historyRevision; }
    }

    @Override
    public void advanceHistoryRevision(String sessionId) {
        State state = require(sessionId);
        synchronized (state) { state.historyRevision++; }
    }

    @Override
    public Session create(String sessionId, String title, String workspace, Instant createdAt, String projectId,
        boolean isBlank, String parentSessionId) {
        State state = new State(sessionId, title, workspace, createdAt, projectId, isBlank, parentSessionId);
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
    public boolean updateStatus(String sessionId, SessionStatus status) {
        State state = sessions.get(sessionId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            state.status = status;
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
        return sessions.values().stream().map(State::snapshot).sorted(Comparator.comparing(Session::pinned).reversed()
            .thenComparing(Session::updatedAt, Comparator.reverseOrder()).thenComparing(Session::id)).toList();
    }

    @Override
    public boolean rename(String sessionId, String title) {
        State state = sessions.get(sessionId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            state.title = title;
            state.titleManual = true;
        }
        return true;
    }

    @Override
    public boolean autoRename(String sessionId, String title) {
        State state = require(sessionId);
        synchronized (state) {
            if (state.titleManual) {
                return false;
            }
            state.title = title;
            return true;
        }
    }

    @Override
    public boolean updatePreferences(String sessionId, Boolean pinned, Boolean unread) {
        State state = require(sessionId);
        synchronized (state) {
            if (pinned != null) {
                state.pinned = pinned;
            }
            if (unread != null) {
                state.unread = unread;
            }
            return true;
        }
    }

    @Override
    public boolean touch(String sessionId, Instant timestamp) {
        State state = require(sessionId);
        synchronized (state) {
            if (timestamp.isAfter(state.updatedAt)) {
                state.updatedAt = timestamp;
            }
            return true;
        }
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
            ArrayList<EventEnvelope> result = new ArrayList<EventEnvelope>();
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
    public List<EventEnvelope> replayPage(String sessionId, long after, long through, int limit) {
        State state = require(sessionId);
        synchronized (state) {
            int lo = 0, hi = state.events.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (state.events.get(mid).sequence() <= after) lo = mid + 1; else hi = mid;
            }
            List<EventEnvelope> page = new ArrayList<>();
            for (int i = lo; i < state.events.size() && page.size() < Math.max(1, limit); i++) {
                EventEnvelope event = state.events.get(i);
                if (event.sequence() > through) break;
                page.add(event);
            }
            return List.copyOf(page);
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
            EventEnvelope envelope = EventEnvelope.of(++state.sequence, timestamp, sessionId, runId, event);
            state.events.add(envelope);
            if (timestamp.isAfter(state.updatedAt)) {
                state.updatedAt = timestamp;
            }
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
        private final String parentSessionId;
        private final List<EventEnvelope> events = new ArrayList<>();
        private String title;
        private String workspace;
        private SessionStatus status = SessionStatus.CREATED;
        private long sequence;
        private long historyRevision;
        private boolean isBlank;
        private boolean archived;
        private Instant updatedAt;
        private boolean pinned;
        private boolean unread;
        private boolean titleManual;

        private State(String id, String title, String workspace, Instant createdAt, String projectId, boolean isBlank,
            String parentSessionId) {
            this.id = id;
            this.title = title;
            this.workspace = workspace;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.projectId = projectId;
            this.isBlank = isBlank;
            this.parentSessionId = parentSessionId;
        }

        private Session snapshot() {
            synchronized (this) {
                return new Session(id, title, createdAt, status, workspace, projectId, isBlank, archived,
                    parentSessionId, updatedAt, pinned, unread, titleManual, historyRevision);
            }
        }
    }
}
