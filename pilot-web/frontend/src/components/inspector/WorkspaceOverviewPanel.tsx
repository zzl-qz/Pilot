import { useEffect, useRef, useState } from 'react';
import { api } from '../../api.js';
import { useStore } from '../../store.js';
import { createCoordinationFeed, type CoordinationFeedState } from '../../lib/coordination/feed.js';
import {
  claimsOwnedBy, connectionLabel, handoffStatusLabel, openFor, otherSessions, readBoard, requestStatusLabel,
  sessionStatusLabel
} from '../../lib/coordination/board.js';
import type { CoordinationBoard } from '../../types/coordination.js';

/** 一批事件合成一次重读：短时间内的多次改动不该每次都整块拉取看板。 */
const EVENT_REFRESH_MS = 200;

/**
 * 工作区看板：同一协调范围内所有会话在做什么、谁占着文件、谁还在等回应或仲裁。
 *
 * <p>自动接收工作区事件流（游标续传、断线重连），手动刷新留作兜底。
 * 对其他会话只提供查看、请求协作、请求交接；接受或放弃改动始终由拥有沙箱的会话自己做。
 */
export function WorkspaceOverviewPanel() {
  const workspace = useStore((state) => state.workspace);
  const sessionId = useStore((state) => state.sessionId);
  const [board, setBoard] = useState<CoordinationBoard | null>(null);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [epoch, setEpoch] = useState(0);
  const [feed, setFeed] = useState<CoordinationFeedState | null>(null);
  const [target, setTarget] = useState('');
  const [reason, setReason] = useState('');
  const [handoffTarget, setHandoffTarget] = useState('');
  const [handoffTask, setHandoffTask] = useState('');
  const [handoffSummary, setHandoffSummary] = useState('');
  const [expanded, setExpanded] = useState('');
  const feedRef = useRef<ReturnType<typeof createCoordinationFeed> | null>(null);

  useEffect(() => {
    if (!workspace) {
      setBoard(null);
      setError('');
      return;
    }
    let alive = true;
    setLoading(true);
    // 没有会话时退回旧概览接口：协调动作都要以某个会话发起方身份执行。
    const request = sessionId ? api.coordinationBoard(sessionId) : api.workspaceOverview(workspace);
    request.then((result) => {
      if (!alive) return;
      const next = readBoard(result);
      setBoard(next);
      setError(next ? '' : '看板响应无法识别');
    }).catch((reason: unknown) => {
      if (!alive) return;
      setBoard(null);
      setError(reason instanceof Error ? reason.message : String(reason));
    }).finally(() => {
      if (alive) setLoading(false);
    });
    return () => { alive = false; };
  }, [workspace, sessionId, epoch]);

  useEffect(() => {
    if (!workspace) return;
    let refreshTimer: ReturnType<typeof setTimeout> | null = null;
    const refreshSoon = () => {
      if (refreshTimer) clearTimeout(refreshTimer);
      refreshTimer = setTimeout(() => {
        refreshTimer = null;
        setEpoch((value) => value + 1);
      }, EVENT_REFRESH_MS);
    };
    const stream = createCoordinationFeed({
      url: (cursor) => `/api/workspace/events?workspace=${encodeURIComponent(workspace)}`
        + (sessionId ? `&session=${encodeURIComponent(sessionId)}` : '') + `&after=${cursor}`,
      createSource: (url) => new EventSource(url),
      onEvent: () => refreshSoon(),
      onReset: () => refreshSoon(),
      onState: (state) => setFeed({ ...state })
    });
    feedRef.current = stream;
    stream.start();
    setFeed({ ...stream.state() });
    return () => {
      if (refreshTimer) clearTimeout(refreshTimer);
      stream.stop();
      feedRef.current = null;
    };
  }, [workspace, sessionId]);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setActionError('');
    try {
      await action();
      setEpoch((value) => value + 1);
      return true;
    } catch (failure) {
      setActionError(failure instanceof Error ? failure.message : String(failure));
      return false;
    } finally {
      setBusy(false);
    }
  };

  if (!workspace) {
    return <p className="overview-empty">先选定一个工作区目录。</p>;
  }

  const snapshot = board?.snapshot ?? null;
  const me = sessionId || '';
  // 旧服务端只返回概览：没有协调快照时仍按概览里的会话列表展示。
  const sessions = snapshot?.sessions.length ? snapshot.sessions : (board?.overview?.sessions || []).map((item) => ({
    sessionId: item.sessionId, title: item.title, status: item.running ? 'RUNNING' : 'PAUSED',
    workspace: board?.overview?.workspace || '', online: false, archived: false
  }));
  const others = snapshot ? otherSessions(snapshot, me) : [];
  const claims = snapshot?.claims || [];
  const incoming = snapshot ? openFor(snapshot.requests, me, 'incoming') : [];
  const outgoing = snapshot ? openFor(snapshot.requests, me, 'outgoing') : [];
  const incomingTasks = snapshot ? openFor(snapshot.handoffs, me, 'incoming') : [];
  const outgoingTasks = snapshot ? openFor(snapshot.handoffs, me, 'outgoing') : [];
  const mine = claimsOwnedBy(claims, me);
  const owners = board?.overview?.fileOwners || [];
  const detailOf = (id: string) => board?.overview?.sessions.find((item) => item.sessionId === id);
  const sessionNameOf = (id: string) => {
    if (!id) return '未知会话';
    if (id === me) return '本会话';
    return sessions.find((item) => item.sessionId === id)?.title || id;
  };
  const arbitrationKindLabel = (kind: string) => ({
    FILE_CONFLICT: '文件冲突',
    WRITE_CONFLICT: '写入冲突',
    SANDBOX_CONFLICT: '沙箱冲突',
    HANDOFF: '任务交接',
    APPROVAL: '权限确认'
  }[String(kind || '').toUpperCase()] || kind);
  const feedStatus = feed?.status || 'idle';

  return (
    <div className="overview-panel" data-testid="workspace-overview">
      <div className="overview-bar">
        <span className="overview-count">{loading ? '读取中…' : `${sessions.length} 个会话`}</span>
        <span className={`overview-feed is-${feedStatus}`} data-testid="overview-feed">
          <span className="overview-feed-dot" aria-hidden="true" />
          {connectionLabel(feedStatus, feed?.cursor || 0, feed?.attempts || 0)}
        </span>
        <span className="overview-bar-actions">
          {feed && feed.status !== 'live' && feed.status !== 'idle' && (
            <button type="button" className="overview-refresh" onClick={() => feedRef.current?.retry()} disabled={busy}>重连</button>
          )}
          <button type="button" className="overview-refresh" onClick={() => setEpoch((value) => value + 1)} disabled={loading}>刷新</button>
        </span>
      </div>
      {feed?.lastError && <p className="overview-line is-warn" role="status">{feed.lastError}</p>}
      {error && <p className="overview-error" role="alert">看板读取失败：{error}</p>}
      {actionError && <p className="overview-error" role="alert">协调失败：{actionError}</p>}
      {!error && owners.length > 0 && (
        <section className="overview-conflicts">
          <h4>同一批文件被多个会话改着</h4>
          <ul>
            {owners.map((owner) => (
              <li key={owner.path}><code>{owner.path}</code><span>{owner.sessions.length} 个会话</span></li>
            ))}
          </ul>
        </section>
      )}
      {!error && !loading && sessions.length === 0 && <p className="overview-empty">这个目录下还没有会话。</p>}
      <ul className="overview-sessions">
        {sessions.map((session) => {
          const detail = detailOf(session.sessionId);
          const isCurrent = session.sessionId === sessionId;
          return (
            <li key={session.sessionId} className={isCurrent ? 'is-current' : ''}>
              <div className="overview-head">
                <strong title={session.title || session.sessionId}>{session.title || session.sessionId}</strong>
                <span className="overview-tags">
                  <span className={`overview-tag is-${String(session.status || '').toLowerCase()}`}>{sessionStatusLabel(session.status)}</span>
                  {session.online && <span className="overview-tag is-online">在线</span>}
                  {isCurrent && <span className="overview-tag is-current">当前</span>}
                </span>
              </div>
              {[
                detail && detail.pendingFiles?.length > 0
                  ? `待接受改动 ${detail.pendingFiles.length} 个：${detail.pendingFiles.join('、')}` : '',
                detail && detail.runningCommands?.length > 0
                  ? `后台命令：${detail.runningCommands.map((item) => item.command).join('、')}` : '',
                detail && detail.arbitrations?.length > 0
                  ? `等待人工决定 ${detail.arbitrations.length} 项（${detail.arbitrations.map((item) => arbitrationKindLabel(item.kind)).join('、')}）` : '',
              ].filter(Boolean).map((text) => <p key={text} className="overview-line">{text}</p>)}
              {detail && detail.tasks?.length > 0 && (
                <ul className="overview-session-tasks">
                  {detail.tasks.map((task) => (
                    <li key={task.taskId}>
                      <span>{task.title || task.taskId}</span>
                      <span className="overview-tag">{sessionStatusLabel(task.status)}</span>
                    </li>
                  ))}
                </ul>
              )}
              <div className="overview-actions">
                <button type="button" className="overview-refresh"
                  onClick={() => setExpanded(expanded === session.sessionId ? '' : session.sessionId)}>
                  {expanded === session.sessionId ? '收起' : '查看'}
                </button>
                {!isCurrent && (
                  <>
                    <button type="button" className="overview-refresh" disabled={busy}
                      onClick={() => { setTarget(session.sessionId); setHandoffTarget(''); }}>请求协作</button>
                    <button type="button" className="overview-refresh" disabled={busy}
                      onClick={() => { setHandoffTarget(session.sessionId); setTarget(''); }}>请求交接</button>
                  </>
                )}
              </div>
              {expanded === session.sessionId && (
                <p className="overview-line overview-path">{session.workspace || '未记录目录'}{session.archived ? '（已归档）' : ''}</p>
              )}
            </li>
          );
        })}
      </ul>
      {target && (
        <section className="overview-form" onKeyDown={(e) => { if (e.key === 'Escape') setTarget(''); }}>
          <h4 title={target}>请求协作 · {sessionNameOf(target)}</h4>
          <input className="overview-input" value={reason} onChange={(event) => setReason(event.target.value)}
            placeholder="说明为什么需要对方配合" autoFocus />
          <div className="overview-form-actions">
            <button type="button" className="overview-refresh" onClick={() => setTarget('')}>取消</button>
            <button type="button" className="overview-refresh is-primary" disabled={busy || !reason.trim()}
              onClick={async () => {
                if (!sessionId) return;
                const sent = await run(() => api.coordinationRequest(sessionId, { toSessionId: target, reason: reason.trim() }));
                if (sent) { setTarget(''); setReason(''); }
              }}>发送请求</button>
          </div>
        </section>
      )}
      {handoffTarget && (
        <section className="overview-form" onKeyDown={(e) => { if (e.key === 'Escape') setHandoffTarget(''); }}>
          <h4 title={handoffTarget}>请求交接 · {sessionNameOf(handoffTarget)}</h4>
          <input className="overview-input" value={handoffTask} onChange={(event) => setHandoffTask(event.target.value)}
            placeholder="任务标识（可留空）" autoFocus />
          <input className="overview-input" value={handoffSummary} onChange={(event) => setHandoffSummary(event.target.value)}
            placeholder="说明需要交接的任务内容与现状" />
          <div className="overview-form-actions">
            <button type="button" className="overview-refresh" onClick={() => setHandoffTarget('')}>取消</button>
            <button type="button" className="overview-refresh is-primary" disabled={busy || !handoffSummary.trim()}
              onClick={async () => {
                if (!sessionId) return;
                const sent = await run(() => api.coordinationHandoff(sessionId, {
                  toSessionId: handoffTarget, taskId: handoffTask.trim(), summary: handoffSummary.trim()
                }));
                if (sent) { setHandoffTarget(''); setHandoffTask(''); setHandoffSummary(''); }
              }}>发送交接</button>
          </div>
        </section>
      )}
      {snapshot && (claims.length > 0 || incoming.length > 0 || outgoing.length > 0
        || incomingTasks.length > 0 || outgoingTasks.length > 0) && (
        <section className="overview-coord-sections">
          {claims.length > 0 && (
            <div className="overview-coord-block">
              <h4>占用者</h4>
              <ul className="overview-coord-list">
                {claims.map((claim) => (
                  <li key={claim.id}>
                    <div className="overview-coord-copy">
                      <code title={claim.path}>{claim.path}</code>
                      <span title={claim.sessionId}>{sessionNameOf(claim.sessionId)} · {claim.kind === 'DIRECTORY' ? '目录' : '文件'}</span>
                    </div>
                    {claim.sessionId === sessionId && (
                      <button type="button" className="overview-refresh" disabled={busy}
                        onClick={() => sessionId && run(() => api.coordinationReleaseClaim(sessionId, claim.id))}>释放</button>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {incoming.length > 0 && (
            <div className="overview-coord-block">
              <h4>待处理协作请求</h4>
              <ul className="overview-coord-list">
                {incoming.map((item) => (
                  <li key={item.id}>
                    <div className="overview-coord-copy">
                      <strong title={item.fromSessionId}>{sessionNameOf(item.fromSessionId)}</strong>
                      <span>{item.reason}</span>
                    </div>
                    <div className="overview-coord-btns">
                      <button type="button" className="overview-refresh is-primary" disabled={busy}
                        onClick={() => sessionId && run(() => api.coordinationResolveRequest(sessionId, item.id, 'accept'))}>接受</button>
                      <button type="button" className="overview-refresh" disabled={busy}
                        onClick={() => sessionId && run(() => api.coordinationResolveRequest(sessionId, item.id, 'decline'))}>拒绝</button>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {outgoing.length > 0 && (
            <div className="overview-coord-block">
              <h4>已发出、待回应</h4>
              <ul className="overview-coord-list">
                {outgoing.map((item) => (
                  <li key={item.id}>
                    <div className="overview-coord-copy">
                      <strong title={item.toSessionId}>{sessionNameOf(item.toSessionId)}</strong>
                      <span>{item.reason}（{requestStatusLabel(item.status)}）</span>
                    </div>
                    <button type="button" className="overview-refresh" disabled={busy}
                      onClick={() => sessionId && run(() => api.coordinationResolveRequest(sessionId, item.id, 'withdraw'))}>撤销</button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {incomingTasks.length > 0 && (
            <div className="overview-coord-block">
              <h4>待交接任务</h4>
              <ul className="overview-coord-list">
                {incomingTasks.map((item) => (
                  <li key={item.id}>
                    <div className="overview-coord-copy">
                      <strong title={item.fromSessionId}>{sessionNameOf(item.fromSessionId)}</strong>
                      <span>{item.summary}（{handoffStatusLabel(item.status)}）</span>
                    </div>
                    <div className="overview-coord-btns">
                      <button type="button" className="overview-refresh is-primary" disabled={busy}
                        onClick={() => sessionId && run(() => api.coordinationResolveHandoff(sessionId, item.id, 'accept'))}>接受</button>
                      <button type="button" className="overview-refresh" disabled={busy}
                        onClick={() => sessionId && run(() => api.coordinationResolveHandoff(sessionId, item.id, 'decline'))}>拒绝</button>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {outgoingTasks.length > 0 && (
            <div className="overview-coord-block">
              <h4>已请求的交接</h4>
              <ul className="overview-coord-list">
                {outgoingTasks.map((item) => (
                  <li key={item.id}>
                    <div className="overview-coord-copy">
                      <strong title={item.toSessionId}>{sessionNameOf(item.toSessionId)}</strong>
                      <span>{item.summary}（{handoffStatusLabel(item.status)}）</span>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {mine.length === 0 && others.length > 0 && <p className="overview-line">本会话目前没有声明占用。</p>}
        </section>
      )}
    </div>
  );
}
