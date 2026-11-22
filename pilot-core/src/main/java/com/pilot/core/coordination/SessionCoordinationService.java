package com.pilot.core.coordination;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionRepository;

/**
 * 会话间显式协调协议：协作请求、文件/目录占用与任务交接。
 *
 * <p>一次操作要么全部生效、要么整体不动：状态放在本实例的锁里，落盘只写一份不可变快照
 * （{@link CoordinationSnapshotStore}）。占用冲突在同一把锁内判定，因此两个会话同时申请同一位置
 * 只有一个能成功。
 *
 * <p>权限边界：只有目标会话能接受或拒绝请求/交接，只有发起会话能撤销，只有占用方自己能释放；
 * 沙箱的接受与放弃仍只由拥有该沙箱的会话执行，这里只提供 {@link #requireSandboxOwner} 做拦截。
 * 在线状态来自 {@link SessionPresence}，不进持久化快照，重启后不会被当成「还在线」。
 */
public final class SessionCoordinationService {

    /** 默认占用租约：到期自动释放，免得崩溃的会话永久占着文件。 */
    public static final Duration DEFAULT_CLAIM_TTL = Duration.ofMinutes(30);
    /** 默认交接待办期限。 */
    public static final Duration DEFAULT_HANDOFF_TTL = Duration.ofHours(12);

    /** 释放原因：占用方主动释放。 */
    public static final String RELEASED = "released";
    /** 释放原因：租约到期。 */
    public static final String LEASE_EXPIRED = "lease-expired";
    /** 释放原因：占用会话已经不存在（崩溃或被删除）。 */
    public static final String SESSION_GONE = "session-gone";
    /** 交接结束原因：超时而没有接受。 */
    public static final String HANDOFF_EXPIRED = "expired";

    private final SessionRepository sessions;
    private final CoordinationScopeResolver scopes;
    private final WorkspaceEventPublisher events;
    private final CoordinationSnapshotStore store;
    private final SessionPresence presence;
    private final Clock clock;
    private final Duration claimTtl;
    private final Duration handoffTtl;
    private final Object lock = new Object();
    private final List<CoordinationRequest> requests = new ArrayList<>();
    private final List<CoordinationClaim> claims = new ArrayList<>();
    private final List<CoordinationHandoff> handoffs = new ArrayList<>();

    public SessionCoordinationService(SessionRepository sessions, CoordinationScopeResolver scopes,
        WorkspaceEventPublisher events, CoordinationSnapshotStore store, CoordinationSettings settings) {
        this.sessions = sessions;
        this.scopes = scopes;
        this.events = events;
        this.store = store;
        this.presence = settings.presence();
        this.clock = settings.clock();
        this.claimTtl = settings.claimTtl();
        this.handoffTtl = settings.handoffTtl();
        CoordinationSnapshot saved = store.load();
        requests.addAll(saved.requests());
        claims.addAll(saved.claims());
        handoffs.addAll(saved.handoffs());
    }

    /** 内存状态 + 默认设置的装配；测试与不需要持久化的场景使用。 */
    public SessionCoordinationService(SessionRepository sessions, CoordinationScopeResolver scopes,
        WorkspaceEventPublisher events) {
        this(sessions, scopes, events, new CoordinationSnapshotStore.InMemory(), CoordinationSettings.defaults());
    }

    /** 调用方协调范围内的会话、占用、请求与交接；先清掉过期与失效条目。 */
    public CoordinationSnapshot snapshot(String callerSessionId) {
        List<WorkspaceEventDraft> out = new ArrayList<>();
        CoordinationSnapshot snapshot;
        synchronized (lock) {
            sweep(out);
            Session caller = requireSession(callerSessionId);
            snapshot = visibleSnapshot(caller);
        }
        flush(out);
        return snapshot;
    }

    /** 发起协作请求；同一发起方对同一目标、同一任务的待处理请求重复提交时返回原请求。 */
    public CoordinationRequest request(String fromSessionId, String toSessionId, String reason, String taskId) {
        List<WorkspaceEventDraft> out = new ArrayList<>();
        CoordinationRequest result;
        synchronized (lock) {
            sweep(out);
            Session from = requireSession(fromSessionId);
            Session to = requireSession(toSessionId);
            if (from.id().equals(to.id())) {
                throw new CoordinationRefusedException(CoordinationRefusedException.DENIED, to.id(), null,
                    "不能向自己发起协作请求");
            }
            requireSharedScope(from, to);
            String task = taskId == null ? "" : taskId.strip();
            Optional<CoordinationRequest> existing = requests.stream()
                .filter(entry -> entry.status().open() && entry.fromSessionId().equals(from.id())
                    && entry.toSessionId().equals(to.id()) && entry.taskId().equals(task))
                .findFirst();
            if (existing.isPresent()) {
                result = existing.get();
            } else {
                Instant now = clock.instant();
                result = new CoordinationRequest(newId(), from.projectId(), from.id(), to.id(), reason, task,
                    CoordinationRequestStatus.PENDING, now, now);
                requests.add(result);
                out.add(WorkspaceEventDraft.of(WorkspaceEventType.COORDINATION_REQUESTED, from.id(),
                    directoryOf(from), "协作请求 → " + to.id())
                    .withProject(from.projectId())
                    .withDetail("requestId", result.id())
                    .withDetail("from", from.id())
                    .withDetail("to", to.id())
                    .withDetail("taskId", task));
                persist();
            }
        }
        flush(out);
        return result;
    }

    /** 目标会话接受请求。 */
    public CoordinationRequest acceptRequest(String callerSessionId, String requestId) {
        return decideRequest(callerSessionId, requestId, CoordinationRequestStatus.ACCEPTED);
    }

    /** 目标会话拒绝请求。 */
    public CoordinationRequest declineRequest(String callerSessionId, String requestId) {
        return decideRequest(callerSessionId, requestId, CoordinationRequestStatus.DECLINED);
    }

    /** 发起会话撤销请求。 */
    public CoordinationRequest withdrawRequest(String callerSessionId, String requestId) {
        return decideRequest(callerSessionId, requestId, CoordinationRequestStatus.WITHDRAWN);
    }

    /**
     * 声明文件或目录占用。
     *
     * @param ttl 租约时长；{@code null} 用默认值
     * @throws CoordinationRefusedException 路径不在自己的协调范围内，或已被别的会话占用
     */
    public CoordinationClaim claim(String callerSessionId, String path, CoordinationClaimKind kind, Duration ttl) {
        List<WorkspaceEventDraft> out = new ArrayList<>();
        CoordinationClaim result;
        synchronized (lock) {
            sweep(out);
            Session caller = requireSession(callerSessionId);
            ProjectCoordinationScope scope = scopes.resolve(caller);
            String canonicalPath = canonicalPath(path);
            if (!scope.includes(toPath(canonicalPath))) {
                throw new CoordinationRefusedException(CoordinationRefusedException.OUT_OF_SCOPE, null, canonicalPath,
                    "路径不在你的协调范围内：" + canonicalPath);
            }
            CoordinationClaimKind claimKind = kind == null ? CoordinationClaimKind.FILE : kind;
            Instant now = clock.instant();
            result = new CoordinationClaim(newId(), caller.projectId(), caller.id(), canonicalPath, claimKind, now,
                now.plus(ttl == null ? claimTtl : ttl));
            for (CoordinationClaim existing : claims) {
                if (!existing.ownedBy(caller.id()) && existing.overlaps(result, scope.caseInsensitive())) {
                    throw new CoordinationRefusedException(CoordinationRefusedException.CLAIMED, existing.sessionId(),
                        existing.path(), "已被会话 " + existing.sessionId() + " 占用：" + existing.path());
                }
            }
            claims.removeIf(existing -> existing.ownedBy(caller.id())
                && existing.overlaps(result, scope.caseInsensitive()));
            claims.add(result);
            out.add(WorkspaceEventDraft.of(WorkspaceEventType.FILE_CLAIMED, caller.id(), result.path(),
                "占用 " + result.path()).withProject(caller.projectId())
                .withDetail("claimId", result.id())
                .withDetail("kind", result.kind().name())
                .withDetail("expiresAt", result.expiresAt().toString()));
            persist();
        }
        flush(out);
        return result;
    }

    /** 释放自己持有的占用。 */
    public CoordinationClaim release(String callerSessionId, String claimId) {
        List<WorkspaceEventDraft> out = new ArrayList<>();
        CoordinationClaim result;
        synchronized (lock) {
            sweep(out);
            CoordinationClaim claim = findClaim(claimId);
            if (claim == null) {
                throw new CoordinationRefusedException(CoordinationRefusedException.NOT_FOUND, null, null,
                    "没有这个占用：" + claimId);
            }
            if (!claim.ownedBy(callerSessionId)) {
                throw new CoordinationRefusedException(CoordinationRefusedException.DENIED, claim.sessionId(),
                    claim.path(), "只有占用方可以释放：" + claim.sessionId());
            }
            claims.remove(claim);
            out.add(releaseEvent(claim, RELEASED));
            persist();
            result = claim;
        }
        flush(out);
        return result;
    }

    /**
     * 危险操作前的占用预检：位置被别人占着就拒绝，并带上占用方与原因。
     *
     * @return 没人占用时返回 {@code null}；被占用时抛 {@link CoordinationRefusedException}
     */
    public CoordinationClaim requireUnclaimed(String callerSessionId, String path) {
        List<WorkspaceEventDraft> out = new ArrayList<>();
        CoordinationClaim blocker = null;
        synchronized (lock) {
            sweep(out);
            Session caller = requireSession(callerSessionId);
            ProjectCoordinationScope scope = scopes.resolve(caller);
            String canonicalPath = canonicalPath(path);
            for (CoordinationClaim claim : claims) {
                if (!claim.ownedBy(caller.id()) && claim.covers(canonicalPath, scope.caseInsensitive())) {
                    blocker = claim;
                    break;
                }
            }
        }
        flush(out);
        if (blocker != null) {
            throw new CoordinationRefusedException(CoordinationRefusedException.CLAIMED, blocker.sessionId(),
                blocker.path(), "该位置已被会话 " + blocker.sessionId() + " 占用：" + blocker.path());
        }
        return null;
    }

    /** 创建任务交接。 */
    public CoordinationHandoff handoff(String fromSessionId, String toSessionId, String taskId, String summary) {
        List<WorkspaceEventDraft> out = new ArrayList<>();
        CoordinationHandoff result;
        synchronized (lock) {
            sweep(out);
            Session from = requireSession(fromSessionId);
            Session to = requireSession(toSessionId);
            if (from.id().equals(to.id())) {
                throw new CoordinationRefusedException(CoordinationRefusedException.DENIED, to.id(), null,
                    "不能把任务交接给自己");
            }
            requireSharedScope(from, to);
            Instant now = clock.instant();
            result = new CoordinationHandoff(newId(), from.projectId(), from.id(), to.id(), taskId, summary,
                CoordinationHandoffStatus.PENDING, now, now, now.plus(handoffTtl));
            handoffs.add(result);
            out.add(handoffEvent(result, "created", from));
            persist();
        }
        flush(out);
        return result;
    }

    /** 目标会话接受交接。 */
    public CoordinationHandoff acceptHandoff(String callerSessionId, String handoffId) {
        return decideHandoff(callerSessionId, handoffId, CoordinationHandoffStatus.ACCEPTED);
    }

    /** 目标会话拒绝交接。 */
    public CoordinationHandoff declineHandoff(String callerSessionId, String handoffId) {
        return decideHandoff(callerSessionId, handoffId, CoordinationHandoffStatus.DECLINED);
    }

    /**
     * 拦截越权：接受或放弃改动只能由拥有该沙箱的会话执行，其他会话只能先发请求。
     *
     * @throws CoordinationRefusedException 调用方不是沙箱拥有者
     */
    public void requireSandboxOwner(String callerSessionId, String sandboxOwnerSessionId) {
        if (callerSessionId == null || !callerSessionId.equals(sandboxOwnerSessionId)) {
            throw new CoordinationRefusedException(CoordinationRefusedException.DENIED, sandboxOwnerSessionId, null,
                "接受或放弃改动只能由拥有该沙箱的会话执行：" + sandboxOwnerSessionId);
        }
    }

    /** 立即清理过期条目；返回清理条数，供指标端点观测。 */
    public int sweepNow() {
        List<WorkspaceEventDraft> out = new ArrayList<>();
        int before;
        synchronized (lock) {
            before = claims.size() + (int)handoffs.stream().filter(CoordinationHandoffStatus.PENDING::equals).count();
            sweep(out);
            before -= claims.size()
                + (int)handoffs.stream().filter(CoordinationHandoffStatus.PENDING::equals).count();
        }
        flush(out);
        return before;
    }

    private CoordinationRequest decideRequest(String callerSessionId, String requestId,
        CoordinationRequestStatus next) {
        List<WorkspaceEventDraft> out = new ArrayList<>();
        CoordinationRequest result;
        synchronized (lock) {
            sweep(out);
            CoordinationRequest request = requests.stream().filter(entry -> entry.id().equals(requestId)).findFirst()
                .orElseThrow(() -> new CoordinationRefusedException(CoordinationRefusedException.NOT_FOUND, null, null,
                    "没有这个协作请求：" + requestId));
            if (!request.status().open()) {
                throw new CoordinationRefusedException(CoordinationRefusedException.CLOSED, null, null,
                    "协作请求已经结束：" + request.status());
            }
            boolean target = request.toSessionId().equals(callerSessionId);
            boolean requester = request.fromSessionId().equals(callerSessionId);
            if (next == CoordinationRequestStatus.WITHDRAWN ? !requester : !target) {
                throw new CoordinationRefusedException(CoordinationRefusedException.DENIED,
                    next == CoordinationRequestStatus.WITHDRAWN ? request.fromSessionId() : request.toSessionId(), null,
                    next == CoordinationRequestStatus.WITHDRAWN ? "只有发起会话可以撤销请求"
                        : "只有目标会话可以处理请求");
            }
            result = request.withStatus(next, clock.instant());
            requests.set(requests.indexOf(request), result);
            out.add(WorkspaceEventDraft.of(WorkspaceEventType.COORDINATION_UPDATED, callerSessionId, null,
                "协作请求 " + next.name().toLowerCase(java.util.Locale.ROOT))
                .withProject(request.projectId())
                .withDetail("requestId", request.id())
                .withDetail("status", next.name())
                .withDetail("from", request.fromSessionId())
                .withDetail("to", request.toSessionId()));
            persist();
        }
        flush(out);
        return result;
    }

    private CoordinationHandoff decideHandoff(String callerSessionId, String handoffId,
        CoordinationHandoffStatus next) {
        List<WorkspaceEventDraft> out = new ArrayList<>();
        CoordinationHandoff result;
        synchronized (lock) {
            sweep(out);
            CoordinationHandoff handoff = handoffs.stream().filter(entry -> entry.id().equals(handoffId)).findFirst()
                .orElseThrow(() -> new CoordinationRefusedException(CoordinationRefusedException.NOT_FOUND, null, null,
                    "没有这个交接：" + handoffId));
            if (!handoff.status().open()) {
                throw new CoordinationRefusedException(CoordinationRefusedException.CLOSED, null, null,
                    "交接已经结束：" + handoff.status());
            }
            if (!handoff.toSessionId().equals(callerSessionId)) {
                throw new CoordinationRefusedException(CoordinationRefusedException.DENIED, handoff.toSessionId(), null,
                    "只有目标会话可以处理交接");
            }
            result = handoff.withStatus(next, clock.instant());
            handoffs.set(handoffs.indexOf(handoff), result);
            out.add(handoffEvent(result, next.name().toLowerCase(java.util.Locale.ROOT), null));
            persist();
        }
        flush(out);
        return result;
    }

    /** 清理过期占用与超时交接；返回要发布的事件，由调用方在解锁后发布。 */
    private void sweep(List<WorkspaceEventDraft> out) {
        Instant now = clock.instant();
        boolean changed = false;
        for (Iterator<CoordinationClaim> iterator = claims.iterator(); iterator.hasNext();) {
            CoordinationClaim claim = iterator.next();
            String reason = null;
            if (claim.expiredAt(now)) {
                reason = LEASE_EXPIRED;
            } else if (sessions.find(claim.sessionId()).isEmpty()) {
                reason = SESSION_GONE;
            }
            if (reason != null) {
                iterator.remove();
                out.add(releaseEvent(claim, reason));
                changed = true;
            }
        }
        for (int index = 0; index < handoffs.size(); index++) {
            CoordinationHandoff handoff = handoffs.get(index);
            if (handoff.expiredAt(now)) {
                CoordinationHandoff expired = handoff.withStatus(CoordinationHandoffStatus.EXPIRED, now);
                handoffs.set(index, expired);
                out.add(handoffEvent(expired, HANDOFF_EXPIRED, null));
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    private CoordinationSnapshot visibleSnapshot(Session caller) {
        ProjectCoordinationScope callerScope = scopes.resolve(caller);
        List<CoordinationSessionView> visibleSessions = new ArrayList<>();
        for (Session candidate : sessions.list()) {
            if (!shares(callerScope, candidate)) {
                continue;
            }
            visibleSessions.add(new CoordinationSessionView(candidate.id(), candidate.title(),
                candidate.status().name(), candidate.workspace(), presence.isOnline(candidate.id()),
                candidate.archived()));
        }
        List<CoordinationClaim> visibleClaims = claims.stream().filter(claim -> claimVisible(caller, callerScope, claim))
            .toList();
        List<CoordinationRequest> visibleRequests =
            requests.stream().filter(entry -> partiesVisible(caller, callerScope, entry.fromSessionId(),
                entry.toSessionId())).toList();
        List<CoordinationHandoff> visibleHandoffs =
            handoffs.stream().filter(entry -> partiesVisible(caller, callerScope, entry.fromSessionId(),
                entry.toSessionId())).toList();
        return new CoordinationSnapshot(callerScope.projectId(), visibleSessions, visibleClaims, visibleRequests,
            visibleHandoffs);
    }

    private boolean claimVisible(Session caller, ProjectCoordinationScope callerScope, CoordinationClaim claim) {
        if (claim.projectId() != null && callerScope.projectId() != null
            && !claim.projectId().equals(callerScope.projectId())) {
            return false;
        }
        return callerScope.includes(toPath(claim.path()));
    }

    private boolean partiesVisible(Session caller, ProjectCoordinationScope callerScope, String fromSessionId,
        String toSessionId) {
        if (caller.id().equals(fromSessionId) || caller.id().equals(toSessionId)) {
            return true;
        }
        Session from = sessions.find(fromSessionId).orElse(null);
        Session to = sessions.find(toSessionId).orElse(null);
        return from != null && to != null && shares(callerScope, from) && shares(callerScope, to);
    }

    private boolean shares(ProjectCoordinationScope callerScope, Session candidate) {
        return callerScope.sharesCoordinationWith(scopes.resolve(candidate));
    }

    private void requireSharedScope(Session from, Session to) {
        if (!scopes.resolve(from).sharesCoordinationWith(scopes.resolve(to))) {
            throw new CoordinationRefusedException(CoordinationRefusedException.OUT_OF_SCOPE, to.id(), null,
                "目标会话不在你的协调范围内：" + to.id());
        }
    }

    private Session requireSession(String sessionId) {
        return sessions.find(sessionId).orElseThrow(() -> new CoordinationRefusedException(
            CoordinationRefusedException.NOT_FOUND, null, null, "没有这个会话：" + sessionId));
    }

    private CoordinationClaim findClaim(String claimId) {
        return claims.stream().filter(claim -> claim.id().equals(claimId)).findFirst().orElse(null);
    }

    private WorkspaceEventDraft releaseEvent(CoordinationClaim claim, String reason) {
        return WorkspaceEventDraft.of(WorkspaceEventType.FILE_RELEASED, claim.sessionId(), claim.path(),
            "释放 " + claim.path()).withProject(claim.projectId())
            .withDetail("claimId", claim.id())
            .withDetail("reason", reason);
    }

    private WorkspaceEventDraft claimEvent(CoordinationClaim claim) {
        return WorkspaceEventDraft.of(WorkspaceEventType.FILE_CLAIMED, claim.sessionId(), claim.path(),
            "占用 " + claim.path()).withProject(claim.projectId()).withDetail("claimId", claim.id());
    }

    private WorkspaceEventDraft handoffEvent(CoordinationHandoff handoff, String decision, Session actor) {
        String sessionId = actor == null ? handoff.toSessionId() : actor.id();
        return WorkspaceEventDraft.of(decision.equals("created") ? WorkspaceEventType.HANDOFF_CREATED
            : WorkspaceEventType.HANDOFF_RESOLVED, sessionId, null, "交接 " + decision)
            .withProject(handoff.projectId())
            .withDetail("handoffId", handoff.id())
            .withDetail("status", handoff.status().name())
            .withDetail("from", handoff.fromSessionId())
            .withDetail("to", handoff.toSessionId());
    }

    private String directoryOf(Session session) {
        return session.workspace();
    }

    private String canonicalPath(String path) {
        if (path == null || path.isBlank()) {
            throw new CoordinationRefusedException(CoordinationRefusedException.NOT_FOUND, null, null, "路径不能为空");
        }
        return ProjectCoordinationScope.canonical(toPath(path.strip())).toString();
    }

    private Path toPath(String path) {
        try {
            return Path.of(path);
        } catch (InvalidPathException invalid) {
            throw new CoordinationRefusedException(CoordinationRefusedException.NOT_FOUND, null, path,
                "路径无法解析：" + path);
        }
    }

    private void persist() {
        store.save(new CoordinationSnapshot(null, List.of(), claims, requests, handoffs));
    }

    private void flush(List<WorkspaceEventDraft> out) {
        for (WorkspaceEventDraft draft : out) {
            events.publish(draft);
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
