import type { DesktopBridge, DesktopBrowserState, BrowserRecent } from '../../types/desktop.js';
import type {
  BrowserEvidenceItem,
  BrowserEvidenceSummary,
  BrowserRecordingAction,
  BrowserRecordingReplay
} from '../../types/api.js';
import { errorInfo } from '../../lib/errors.js';
import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../../store.js';
import { api } from '../../api.js';
import { formatScreenshotFeedback, userPageUrl } from '../../lib/browser-mirror.js';

/* ---------- 内置浏览器 ---------- */

const ACTION_TEXT: Record<string, string> = {
  navigate: '导航',
  new_tab: '新建标签',
  switch_tab: '切换标签',
  close_tab: '关闭标签',
  click: '点击',
  dblclick: '双击',
  right_click: '右键',
  hover: '悬停',
  fill: '填写',
  type: '输入',
  press: '按键',
  select: '选择',
  check: '勾选',
  uncheck: '取消勾选',
  scroll: '滚动',
  drag: '拖拽',
  dialog: '弹窗处理',
  batch: '批量操作',
  upload: '上传',
  download: '下载',
  screenshot: '截图',
  record: '过程录像',
  inspect: '检查布局',
  snapshot: '页面快照',
  wait: '等待',
  console: '控制台',
  network: '网络记录',
  evaluate: '执行脚本',
  handoff: '请求接管',
  resize: '调整视口'
};

const HANDOFF_KIND_LABELS: Record<string, string> = {
  auth: '登录 / MFA',
  captcha: '人机验证',
  payment: '支付确认',
  manual: '人工协助'
};

/** 原生页面不吃 CSS 裁剪。按祖先的 overflow 把矩形收到真正看得见的那一块。 */
function visibleStageRect(element: HTMLElement) {
  const rect = element.getBoundingClientRect();
  let top = rect.top;
  let left = rect.left;
  let right = rect.right;
  let bottom = rect.bottom;
  let node = element.parentElement;
  while (node && node !== document.documentElement) {
    const style = getComputedStyle(node);
    const clipsX = /hidden|clip|scroll|auto/.test(style.overflowX);
    const clipsY = /hidden|clip|scroll|auto/.test(style.overflowY);
    if (clipsX || clipsY) {
      const box = node.getBoundingClientRect();
      if (clipsX) {
        left = Math.max(left, box.left);
        right = Math.min(right, box.right);
      }
      if (clipsY) {
        top = Math.max(top, box.top);
        bottom = Math.min(bottom, box.bottom);
      }
    }
    node = node.parentElement;
  }
  for (const host of [document.getElementById('activity'), element.closest('#panel-browser')]) {
    if (!host) continue;
    const box = host.getBoundingClientRect();
    left = Math.max(left, box.left);
    top = Math.max(top, box.top);
    right = Math.min(right, box.right);
    bottom = Math.min(bottom, box.bottom);
  }
  left = Math.max(0, left);
  top = Math.max(0, top);
  right = Math.min(window.innerWidth, right);
  bottom = Math.min(window.innerHeight, bottom);
  return {
    x: left,
    y: top,
    width: Math.max(0, right - left),
    height: Math.max(0, bottom - top)
  };
}

function recentHost(url: string) {
  try {
    return new URL(url).host;
  } catch {
    return String(url || '').replace(/^https?:\/\//, '');
  }
}

function RecentMark({ item }: { item: BrowserRecent }) {
  const [broken, setBroken] = useState(false);
  const mark = (item.title || item.url || '·').trim().slice(0, 1).toUpperCase();
  if (!item.favicon || broken) return <span className="browser-recent-mark">{mark}</span>;
  return <img src={item.favicon} alt="" onError={() => setBroken(true)} />;
}

function cleanLaunchNote(text?: string) {
  return String(text || '')
    .replace(/（浏览器进程自上次使用后重启过[\s\S]*?）/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function AgentControlBar({
  viewMode,
  onViewModeChange
}: {
  viewMode: 'stage' | 'evidence';
  onViewModeChange: (mode: 'stage' | 'evidence') => void;
}) {
  const sessionId = useStore((state) => state.sessionId);
  const mirror = useStore((state) => state.ui.mirror);
  const [toggling, setToggling] = useState(false);
  const [stopping, setStopping] = useState(false);
  const lastAction = mirror.lastAction;
  const actionLabel = lastAction?.action ? ACTION_TEXT[lastAction.action] || lastAction.action : '';
  const lanes = mirror.lanes || [];
  const activeLane = mirror.activeLane || sessionId || '';

  async function togglePause() {
    if (!sessionId || toggling) return;
    setToggling(true);
    try {
      await api.browserToggle(sessionId, Boolean(mirror.paused));
      await useStore.getState().loadBrowserStatus();
    } finally {
      setToggling(false);
    }
  }

  async function stopControl() {
    if (!sessionId || stopping) return;
    setStopping(true);
    try {
      await useStore.getState().stopBrowserControl(activeLane);
    } finally {
      setStopping(false);
    }
  }

  return (
    <div className="browser-agent-bar">
      <div className="browser-agent-status">
        <span className={`browser-pause-pill${mirror.paused ? ' is-paused' : mirror.agentControlled ? ' is-controlled' : ''}`}>
          {mirror.paused
            ? '已接管（暂停自动化）'
            : mirror.agentControlled
              ? `控制中${mirror.controlStatus ? ` · ${mirror.controlStatus.slice(0, 28)}` : ''}`
              : '实时受控'}
        </span>
        {lastAction && (
          <span className="browser-action-badge" title={lastAction.summary || actionLabel}>
            <strong>{actionLabel}</strong>
            {lastAction.summary ? ` · ${lastAction.summary.slice(0, 42)}` : ''}
          </span>
        )}
      </div>
      {lanes.length > 1 && (
        <div className="browser-lanes" role="tablist" aria-label="浏览器执行泳道">
          {lanes.map((lane) => {
            const active = lane.browserSessionId === activeLane;
            return (
              <button
                key={lane.browserSessionId}
                type="button"
                role="tab"
                aria-selected={active}
                className={`browser-lane-pill${active ? ' is-on' : ''}`}
                title={lane.agentId ? `子代理 ${lane.agentId}` : '主会话浏览器'}
                onClick={() => useStore.getState().selectBrowserLane(lane.browserSessionId)}
              >
                {lane.label || (lane.agentId ? lane.agentId : '主会话')}
              </button>
            );
          })}
        </div>
      )}
      <div className="browser-agent-actions">
        {mirror.agentControlled && (
          <button
            type="button"
            className="browser-stop-btn"
            disabled={!sessionId || stopping}
            onClick={stopControl}
          >
            {stopping ? '正在停止…' : '停止控制 (Stop)'}
          </button>
        )}
        {(!mirror.agentControlled || mirror.paused) && (
          <button
            type="button"
            className={`browser-ctrl-btn${mirror.paused ? ' is-primary' : ''}`}
            disabled={!sessionId || toggling}
            onClick={togglePause}
          >
            {mirror.paused ? '交回控制权' : '人工接管'}
          </button>
        )}
        <div className="browser-view-switch" role="tablist" aria-label="视图切换">
          <button
            type="button"
            role="tab"
            aria-selected={viewMode === 'stage'}
            className={`browser-view-tab${viewMode === 'stage' ? ' is-on' : ''}`}
            onClick={() => onViewModeChange('stage')}
          >
            实时画面
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={viewMode === 'evidence'}
            className={`browser-view-tab${viewMode === 'evidence' ? ' is-on' : ''}`}
            onClick={() => onViewModeChange('evidence')}
          >
            证据{mirror.evidenceCount > 0 ? ` (${mirror.evidenceCount})` : ''}
          </button>
        </div>
      </div>
    </div>
  );
}

function HandoffBanner() {
  const handoffs = useStore((state) => state.ui.mirror.handoffs);
  const resumeHandoff = useStore((state) => state.resumeBrowserHandoff);
  const cancelHandoff = useStore((state) => state.cancelBrowserHandoff);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);

  if (!handoffs || !handoffs.length) return null;
  const active = handoffs[0];
  const label = HANDOFF_KIND_LABELS[active.kind] || '请求接管';

  return (
    <div className="browser-handoff-banner" role="alert">
      <div className="browser-handoff-banner-head">
        <span className="browser-handoff-kind">{label}</span>
        {active.url && <span className="browser-handoff-url mono">{active.url}</span>}
      </div>
      <p className="browser-handoff-reason">{active.reason}</p>
      <div className="browser-handoff-banner-controls">
        <input
          type="text"
          className="browser-handoff-note"
          placeholder="可选备注给智能体…"
          value={note}
          disabled={busy}
          onChange={(event) => setNote(event.target.value)}
        />
        <button
          type="button"
          className="browser-ctrl-btn"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            await cancelHandoff(active.browserSessionId, '用户在浏览器面板取消接管');
            setBusy(false);
          }}
        >
          取消
        </button>
        <button
          type="button"
          className="browser-ctrl-btn is-primary"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            await resumeHandoff(active.browserSessionId, note);
            setNote('');
            setBusy(false);
          }}
        >
          {busy ? '恢复中…' : '我已完成，交回智能体'}
        </button>
      </div>
    </div>
  );
}

type DragRect = { x: number; y: number; w: number; h: number; nw: number; nh: number };

function canvasCoordsFromEvent(img: HTMLImageElement | null, clientX: number, clientY: number) {
  if (!img) return null;
  const rect = img.getBoundingClientRect();
  if (!rect.width || !rect.height) return null;
  const nw = img.naturalWidth || rect.width;
  const nh = img.naturalHeight || rect.height;
  const relX = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
  const relY = Math.max(0, Math.min(1, (clientY - rect.top) / rect.height));
  return { x: relX * nw, y: relY * nh, nw, nh };
}

function submitCanvasFeedback(
  params: { url?: string; title?: string; action?: string; box: DragRect; note: string },
  notice: string,
  onDone: () => void
) {
  const text = formatScreenshotFeedback({
    url: params.url,
    title: params.title,
    action: params.action,
    x: params.box.x,
    y: params.box.y,
    width: params.box.w,
    height: params.box.h,
    naturalWidth: params.box.nw,
    naturalHeight: params.box.nh,
    note: params.note
  });
  const composer = useStore.getState().composerApi;
  if (!composer) return;
  composer.appendText(text);
  composer.focus();
  useStore.getState().showNotice(notice);
  onDone();
}

function RecordingReplayModal({
  sessionId,
  item,
  onClose
}: {
  sessionId: string;
  item: BrowserEvidenceItem;
  onClose: () => void;
}) {
  const [replay, setReplay] = useState<BrowserRecordingReplay | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [frameIdx, setFrameIdx] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState<0.5 | 1 | 2>(1);
  const [dragBox, setDragBox] = useState<DragRect | null>(null);
  const [feedbackNote, setFeedbackNote] = useState('');
  const imgRef = useRef<HTMLImageElement>(null);
  const dragStartRef = useRef<{ x: number; y: number } | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setLoadError('');
    setReplay(null);
    setFrameIdx(0);
    setPlaying(false);
    setDragBox(null);
    api.browserEvidenceRecording(sessionId, item.id)
      .then((res) => {
        if (!alive) return;
        setReplay(res);
      })
      .catch((error) => {
        if (!alive) return;
        setLoadError(errorInfo(error).message || '读取过程录像失败');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => { alive = false; };
  }, [sessionId, item.id]);

  const frames = replay?.frames || [];
  const actions = replay?.actions || [];
  const safeIdx = frames.length ? Math.min(frameIdx, frames.length - 1) : 0;
  const curFrame = frames[safeIdx] || null;

  useEffect(() => {
    if (!playing || frames.length <= 1) return;
    if (safeIdx >= frames.length - 1) {
      setPlaying(false);
      return;
    }
    const cur = frames[safeIdx];
    const next = frames[safeIdx + 1];
    const delta = cur && next ? Math.max(0, next.offsetMs - cur.offsetMs) : 450;
    const waitMs = Math.max(120, Math.min(2000, Math.round((delta || 450) / speed)));
    const timer = window.setTimeout(() => {
      setFrameIdx((prev) => Math.min(frames.length - 1, prev + 1));
    }, waitMs);
    return () => { window.clearTimeout(timer); };
  }, [playing, safeIdx, speed, frames]);

  let activeAction: BrowserRecordingAction | null = null;
  if (curFrame && actions.length) {
    for (const action of actions) {
      if (action.offsetMs <= curFrame.offsetMs + 120) activeAction = action;
    }
    if (!activeAction) activeAction = actions[0] || null;
  }

  const stageW = curFrame?.w || replay?.viewport?.width || 1280;
  const stageH = curFrame?.h || replay?.viewport?.height || 800;

  function jumpToAction(action: BrowserRecordingAction) {
    setPlaying(false);
    setDragBox(null);
    if (!frames.length) return;
    let bestIdx = 0;
    let bestDiff = Infinity;
    for (let i = 0; i < frames.length; i++) {
      const diff = Math.abs(frames[i].offsetMs - action.offsetMs);
      if (diff < bestDiff) {
        bestDiff = diff;
        bestIdx = i;
      }
    }
    setFrameIdx(bestIdx);
  }

  function insertFrameFeedback() {
    if (!curFrame || !dragBox) return;
    submitCanvasFeedback(
      {
        url: curFrame.url || item.url,
        title: item.title,
        action: activeAction?.kind || item.action,
        box: dragBox,
        note: feedbackNote
      },
      '已把录像帧定位反馈插入输入框',
      () => {
        setFeedbackNote('');
        setDragBox(null);
        onClose();
      }
    );
  }

  return (
    <div className="browser-evidence-modal browser-replay-modal" role="dialog" aria-label="浏览器操作过程回放">
      <div className="browser-evidence-modal-head">
        <span>
          操作过程录像回放
          {replay && curFrame ? ` · ${(curFrame.offsetMs / 1000).toFixed(1)}s / ${(replay.durationMs / 1000).toFixed(1)}s` : ''}
        </span>
        <button type="button" className="browser-ctrl-btn" onClick={onClose}>关闭</button>
      </div>
      {loading && <div className="browser-evidence-empty">正在加载操作过程录像…</div>}
      {!loading && loadError && <div className="browser-evidence-empty">{loadError}</div>}
      {!loading && !loadError && replay && !frames.length && (
        <div className="browser-evidence-empty">该录像暂无可展示的页面画面（可能全程处于敏感页面或未产生画面帧）。</div>
      )}
      {!loading && !loadError && replay && frames.length > 0 && curFrame && (
        <>
          <div className="browser-replay-toolbar">
            <button
              type="button"
              className="browser-ctrl-btn is-primary"
              onClick={() => {
                if (safeIdx >= frames.length - 1) setFrameIdx(0);
                setPlaying((prev) => !prev);
              }}
            >
              {playing ? '暂停' : '播放'}
            </button>
            <div className="browser-replay-speed" role="group" aria-label="回放倍速">
              {([0.5, 1, 2] as const).map((factor) => (
                <button
                  key={factor}
                  type="button"
                  className={`browser-ctrl-btn${speed === factor ? ' is-active' : ''}`}
                  onClick={() => setSpeed(factor)}
                >
                  {factor}x
                </button>
              ))}
            </div>
            <input
              type="range"
              aria-label="录像时间轴"
              min={0}
              max={Math.max(0, frames.length - 1)}
              value={safeIdx}
              onChange={(event) => {
                setPlaying(false);
                setDragBox(null);
                setFrameIdx(Number(event.target.value));
              }}
            />
            <span className="mono">{safeIdx + 1}/{frames.length}</span>
          </div>
          <div className="browser-replay-body">
            <div className="browser-replay-stage">
              <div
                className="browser-evidence-canvas"
                onPointerDown={(event) => {
                  const pt = canvasCoordsFromEvent(imgRef.current, event.clientX, event.clientY);
                  if (!pt) return;
                  setPlaying(false);
                  dragStartRef.current = { x: pt.x, y: pt.y };
                  setDragBox({ x: pt.x, y: pt.y, w: 0, h: 0, nw: pt.nw, nh: pt.nh });
                }}
                onPointerMove={(event) => {
                  if (!dragStartRef.current) return;
                  const pt = canvasCoordsFromEvent(imgRef.current, event.clientX, event.clientY);
                  if (!pt) return;
                  const sx = dragStartRef.current.x;
                  const sy = dragStartRef.current.y;
                  setDragBox({
                    x: Math.min(sx, pt.x),
                    y: Math.min(sy, pt.y),
                    w: Math.abs(pt.x - sx),
                    h: Math.abs(pt.y - sy),
                    nw: pt.nw,
                    nh: pt.nh
                  });
                }}
                onPointerUp={() => {
                  dragStartRef.current = null;
                }}
              >
                <img
                  ref={imgRef}
                  src={`data:image/jpeg;base64,${curFrame.data}`}
                  alt={`录像第 ${safeIdx + 1} 帧`}
                  draggable={false}
                />
                {activeAction?.box && stageW > 0 && stageH > 0 && (
                  <div
                    className="browser-target-box"
                    style={{
                      left: `${(activeAction.box.x / stageW) * 100}%`,
                      top: `${(activeAction.box.y / stageH) * 100}%`,
                      width: `${Math.max(0.8, (activeAction.box.width / stageW) * 100)}%`,
                      height: `${Math.max(0.8, (activeAction.box.height / stageH) * 100)}%`
                    }}
                  />
                )}
                {typeof activeAction?.x === 'number' && typeof activeAction?.y === 'number' && stageW > 0 && stageH > 0 && (
                  <div
                    className="browser-agent-cursor-dot"
                    style={{
                      left: `${(activeAction.x / stageW) * 100}%`,
                      top: `${(activeAction.y / stageH) * 100}%`
                    }}
                  />
                )}
                {dragBox && dragBox.nw > 0 && dragBox.nh > 0 && (
                  <div
                    className="browser-evidence-box"
                    style={{
                      left: `${(dragBox.x / dragBox.nw) * 100}%`,
                      top: `${(dragBox.y / dragBox.nh) * 100}%`,
                      width: `${Math.max(1.5, (dragBox.w / dragBox.nw) * 100)}%`,
                      height: `${Math.max(1.5, (dragBox.h / dragBox.nh) * 100)}%`
                    }}
                  />
                )}
              </div>
            </div>
            <aside className="browser-replay-timeline" aria-label="操作步骤时间轴">
              {actions.length === 0 && <div className="browser-evidence-empty">无动作标记</div>}
              {actions.map((action, idx) => (
                <button
                  key={`${action.ts}-${idx}`}
                  type="button"
                  className={`browser-replay-action-item${action === activeAction ? ' is-active' : ''}`}
                  onClick={() => jumpToAction(action)}
                >
                  <span className="mono">{(action.offsetMs / 1000).toFixed(1)}s</span>
                  <span>{ACTION_TEXT[action.kind] || action.kind}</span>
                  {action.status && <span className="browser-replay-action-status">{action.status}</span>}
                </button>
              ))}
            </aside>
          </div>
          <div className="browser-evidence-modal-foot">
            <input
              type="text"
              placeholder="在任意帧暂停后框选画面位置并补充说明…"
              value={feedbackNote}
              onChange={(event) => setFeedbackNote(event.target.value)}
            />
            <button type="button" className="browser-ctrl-btn is-primary" disabled={!dragBox} onClick={insertFrameFeedback}>
              插入该帧坐标反馈到对话
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function BrowserEvidenceView({ sessionId }: { sessionId: string | null }) {
  const evidenceVersion = useStore((state) => state.ui.mirror.evidenceVersion);
  const [items, setItems] = useState<BrowserEvidenceItem[]>([]);
  const [summary, setSummary] = useState<BrowserEvidenceSummary | null>(null);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<BrowserEvidenceItem | null>(null);
  const [replaying, setReplaying] = useState<BrowserEvidenceItem | null>(null);
  const [feedbackNote, setFeedbackNote] = useState('');
  const [dragBox, setDragBox] = useState<DragRect | null>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const dragStartRef = useRef<{ x: number; y: number } | null>(null);

  useEffect(() => {
    if (!sessionId) return;
    let alive = true;
    setLoading(true);
    api.browserEvidence(sessionId, '', 80)
      .then((res) => {
        if (!alive) return;
        setItems(Array.isArray(res?.items) ? res.items : []);
        setSummary(res?.summary || null);
      })
      .catch(() => {
        if (alive) setItems([]);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => { alive = false; };
  }, [sessionId, evidenceVersion]);

  function toImageCoords(clientX: number, clientY: number) {
    return canvasCoordsFromEvent(imgRef.current, clientX, clientY);
  }

  function insertFeedback() {
    if (!selected || !dragBox) return;
    submitCanvasFeedback(
      { url: selected.url, title: selected.title, action: selected.action, box: dragBox, note: feedbackNote },
      '已把截图定位反馈插入输入框',
      () => {
        setFeedbackNote('');
        setSelected(null);
        setDragBox(null);
      }
    );
  }

  if (!sessionId) {
    return <div className="browser-evidence-empty">请先新建或选择一个会话以查看浏览器证据。</div>;
  }

  return (
    <div className="browser-evidence-pane">
      {summary && (
        <div className="browser-evidence-summary">
          <span>记录 {summary.total ?? items.length}</span>
          <span>截图 {summary.screenshots ?? 0}</span>
          {(summary.recordings ?? 0) > 0 && <span>录像 {summary.recordings}</span>}
          <span>下载 {summary.downloads ?? 0}</span>
          {(summary.errors ?? 0) > 0 && <span className="is-warn">异常 {summary.errors}</span>}
          {(summary.handoffs ?? 0) > 0 && <span>接管 {summary.handoffs}</span>}
        </div>
      )}
      {loading && !items.length && <div className="browser-evidence-empty">正在加载宿主机证据…</div>}
      {!loading && !items.length && (
        <div className="browser-evidence-empty">暂无宿主机记录。运行浏览器或 `/browser` 任务后，真实操作、截图与下载会自动记入此处。</div>
      )}
      <div className="browser-evidence-list">
        {items.map((item) => (
          <article key={item.id} className={`browser-evidence-card status-${item.status}`}>
            <div className="browser-evidence-head">
              <span className="browser-evidence-action">{ACTION_TEXT[item.action] || item.action}</span>
              <span className={`browser-evidence-status is-${item.status}`}>{item.status}</span>
              {item.agentId && <span className="browser-evidence-agent mono">{item.agentId}</span>}
            </div>
            {item.url && <div className="browser-evidence-url mono" title={item.url}>{item.url}</div>}
            {item.summary && <p className="browser-evidence-text">{item.summary}</p>}
            {item.downloadName && (
              <div className="browser-evidence-meta">
                下载：{item.downloadName}{item.downloadBytes ? ` (${item.downloadBytes} bytes)` : ''}
              </div>
            )}
            {item.recordingPath && (
              <button
                type="button"
                className="browser-evidence-replay-btn"
                onClick={() => {
                  setSelected(null);
                  setReplaying(item);
                }}
              >
                ▶ 回放操作录像
              </button>
            )}
            {item.screenshotPath && (
              <button
                type="button"
                className="browser-evidence-thumb"
                onClick={() => {
                  setReplaying(null);
                  setSelected(item);
                  setDragBox(null);
                  setFeedbackNote('');
                }}
              >
                <img src={api.browserEvidenceImageUrl(sessionId, item.id)} alt={item.title || item.action} loading="lazy" />
                <span>点击标注并反馈坐标</span>
              </button>
            )}
          </article>
        ))}
      </div>
      {replaying && (
        <RecordingReplayModal sessionId={sessionId} item={replaying} onClose={() => setReplaying(null)} />
      )}
      {selected && (
        <div className="browser-evidence-modal" role="dialog" aria-label="截图标注与坐标反馈">
          <div className="browser-evidence-modal-head">
            <span>在截图上点击或拖拽框选问题位置</span>
            <button type="button" className="browser-ctrl-btn" onClick={() => setSelected(null)}>关闭</button>
          </div>
          <div
            className="browser-evidence-canvas"
            onPointerDown={(event) => {
              const pt = toImageCoords(event.clientX, event.clientY);
              if (!pt) return;
              dragStartRef.current = { x: pt.x, y: pt.y };
              setDragBox({ x: pt.x, y: pt.y, w: 0, h: 0, nw: pt.nw, nh: pt.nh });
            }}
            onPointerMove={(event) => {
              if (!dragStartRef.current) return;
              const pt = toImageCoords(event.clientX, event.clientY);
              if (!pt) return;
              const sx = dragStartRef.current.x;
              const sy = dragStartRef.current.y;
              setDragBox({
                x: Math.min(sx, pt.x),
                y: Math.min(sy, pt.y),
                w: Math.abs(pt.x - sx),
                h: Math.abs(pt.y - sy),
                nw: pt.nw,
                nh: pt.nh
              });
            }}
            onPointerUp={() => {
              dragStartRef.current = null;
            }}
          >
            <img ref={imgRef} src={api.browserEvidenceImageUrl(sessionId, selected.id)} alt={selected.title || '证据截图'} draggable={false} />
            {dragBox && dragBox.nw > 0 && dragBox.nh > 0 && (
              <div
                className="browser-evidence-box"
                style={{
                  left: `${(dragBox.x / dragBox.nw) * 100}%`,
                  top: `${(dragBox.y / dragBox.nh) * 100}%`,
                  width: `${Math.max(1.5, (dragBox.w / dragBox.nw) * 100)}%`,
                  height: `${Math.max(1.5, (dragBox.h / dragBox.nh) * 100)}%`
                }}
              />
            )}
          </div>
          <div className="browser-evidence-modal-foot">
            <input
              type="text"
              placeholder="补充修改说明（可选）…"
              value={feedbackNote}
              onChange={(event) => setFeedbackNote(event.target.value)}
            />
            <button type="button" className="browser-ctrl-btn is-primary" disabled={!dragBox} onClick={insertFeedback}>
              插入坐标反馈到对话
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export function BrowserPanel({ toolsMenuOpen }: { toolsMenuOpen: boolean }) {
  const bridge = window.pilotDesktop;
  return bridge ? <DesktopBrowserPanel desktopBridge={bridge} toolsMenuOpen={toolsMenuOpen} /> : <WebBrowserPanel />;
}

function WebBrowserPanel() {
  const sessionId = useStore((state) => state.sessionId);
  const mirror = useStore((state) => state.ui.mirror);
  const browserStatus = useStore((state) => state.ui.browserStatus);
  const [viewMode, setViewMode] = useState<'stage' | 'evidence'>('stage');
  const [address, setAddress] = useState(mirror.url || '');
  const [editing, setEditing] = useState(false);
  const [frameSrc, setFrameSrc] = useState('');
  const [frameSize, setFrameSize] = useState({ width: 1280, height: 800 });
  const [pickMode, setPickMode] = useState(false);
  const [consoleOpen, setConsoleOpen] = useState(false);
  const [networkLog, setNetworkLog] = useState(false);
  const [consoleText, setConsoleText] = useState('');
  const [launching, setLaunching] = useState(false);
  const [launchNote, setLaunchNote] = useState('');
  const [actionError, setActionError] = useState('');
  const imgRef = useRef<HTMLImageElement>(null);

  const activeLane = mirror.activeLane || sessionId || '';

  useEffect(() => {
    if (sessionId) {
      useStore.getState().ensureMirror();
      void useStore.getState().loadBrowserStatus();
    }
  }, [sessionId]);

  useEffect(() => {
    if (!editing) {
      setAddress(mirror.url || '');
    }
  }, [mirror.url, editing]);

  useEffect(() => {
    if (!sessionId || viewMode !== 'stage' || browserStatus?.capable === false) {
      return undefined;
    }
    const source = new EventSource(`/api/sessions/${encodeURIComponent(sessionId)}/browser/stream`);
    const onFrame = (message: MessageEvent<string>) => {
      try {
        const data = JSON.parse(message.data) as {
          frame?: string; data?: string;
          width?: number; height?: number;
          w?: number; h?: number;
        };
        const raw = data.frame || data.data || '';
        if (raw) {
          setFrameSrc(raw.startsWith('data:') ? raw : `data:image/jpeg;base64,${raw}`);
        }
        const width = Number(data.width ?? data.w);
        const height = Number(data.height ?? data.h);
        if (Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0) {
          setFrameSize({ width, height });
        }
      } catch { /* 忽略格式异常帧 */ }
    };
    source.addEventListener('frame', onFrame as EventListener);
    return () => {
      source.removeEventListener('frame', onFrame as EventListener);
      source.close();
    };
  }, [sessionId, viewMode, browserStatus?.capable]);

  async function submitAddress(event: React.FormEvent) {
    event.preventDefault();
    if (!sessionId) return;
    const page = userPageUrl(address);
    if (!page) {
      setActionError('请输入有效的 http(s) 地址，例如 localhost:5173');
      return;
    }
    setActionError('');
    setEditing(false);
    const res = await api.browserNavigate(sessionId, page, activeLane);
    if (!res?.ok) setActionError(res?.error || '导航失败');
  }

  function pointerCoords(event: React.MouseEvent<HTMLImageElement>) {
    const img = imgRef.current;
    if (!img) return null;
    const rect = img.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    const x = Math.round(((event.clientX - rect.left) / rect.width) * frameSize.width);
    const y = Math.round(((event.clientY - rect.top) / rect.height) * frameSize.height);
    return { x, y };
  }

  async function handleStageClick(event: React.MouseEvent<HTMLImageElement>) {
    if (!sessionId || mirror.agentControlled) return;
    const coords = pointerCoords(event);
    if (!coords) return;
    setActionError('');
    if (pickMode) {
      setPickMode(false);
      const res = await api.browserPick(sessionId, { ...coords, browserSessionId: activeLane });
      if (res?.ok && res.text) {
        const composer = useStore.getState().composerApi;
        if (composer) {
          composer.appendText(`\n> 页面元素引用\n> ${res.text.replace(/\n/g, '\n> ')}\n\n`);
          composer.focus();
          useStore.getState().showNotice('已将元素引用插入输入框');
        }
      } else {
        setActionError(res?.error || '未能拾取到元素');
      }
      return;
    }
    const res = await api.browserPointer(sessionId, { action: 'click', ...coords, browserSessionId: activeLane });
    if (!res?.ok) setActionError(res?.error || '点击未生效');
  }

  async function handleWheel(event: React.WheelEvent<HTMLDivElement>) {
    if (!sessionId || !frameSrc || mirror.agentControlled) return;
    const img = imgRef.current;
    const rect = img?.getBoundingClientRect();
    const x = rect ? Math.round(((event.clientX - rect.left) / rect.width) * frameSize.width) : 0;
    const y = rect ? Math.round(((event.clientY - rect.top) / rect.height) * frameSize.height) : 0;
    await api.browserPointer(sessionId, {
      action: 'wheel',
      x: Math.max(0, x),
      y: Math.max(0, y),
      deltaX: Math.round(event.deltaX),
      deltaY: Math.round(event.deltaY),
      browserSessionId: activeLane
    });
  }

  async function handleKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (!sessionId || !frameSrc || mirror.agentControlled || event.nativeEvent.isComposing) return;
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    if (event.key.length === 1) {
      event.preventDefault();
      await api.browserKeys(sessionId, { text: event.key, browserSessionId: activeLane });
    } else if (['Enter', 'Backspace', 'Tab', 'Escape', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) {
      event.preventDefault();
      await api.browserKeys(sessionId, { key: event.key, browserSessionId: activeLane });
    }
  }

  async function toggleLogs(network: boolean) {
    if (!sessionId) return;
    if (consoleOpen && networkLog === network) {
      setConsoleOpen(false);
      return;
    }
    setNetworkLog(network);
    setConsoleOpen(true);
    const res = await api.browserLogs(sessionId, { network, browserSessionId: activeLane });
    setConsoleText(res?.text || (network ? '没有网络记录' : '没有控制台记录'));
  }

  async function openDesktopWindow() {
    setLaunching(true);
    setLaunchNote('');
    try {
      const data = await api.openBrowserWindow();
      const message = cleanLaunchNote(data?.message);
      setLaunchNote(data?.ok === false ? (message || '未能打开内置窗口') : (message || '窗口已打开'));
    } catch (failure) {
      setLaunchNote(errorInfo(failure).message || '未能打开内置窗口，可继续使用下方网页实时控制面板');
    } finally {
      setLaunching(false);
    }
  }

  const externalUrl = /^https?:\/\//i.test(mirror.url || '') ? mirror.url : null;
  const unavailable = browserStatus && !browserStatus.available;
  const vpW = mirror.lastAction?.viewportWidth || frameSize.width || 1280;
  const vpH = mirror.lastAction?.viewportHeight || frameSize.height || 800;
  const actionBox = mirror.lastAction?.box;
  const cursorX = mirror.lastAction?.x;
  const cursorY = mirror.lastAction?.y;

  return (
    <div id="panel-browser" className="panel browser-panel">
      <AgentControlBar viewMode={viewMode} onViewModeChange={setViewMode} />
      <HandoffBanner />
      {viewMode === 'evidence'
        ? <BrowserEvidenceView sessionId={sessionId} />
        : (
          <>
            <div className="browser-chrome">
              <div className="browser-bar">
                <button type="button" className="icon-btn" aria-label="后退" title="后退" disabled={!sessionId}
                  onClick={() => sessionId && api.browserBack(sessionId, activeLane)}>
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </button>
                <button type="button" className="icon-btn" aria-label="前进" title="前进" disabled={!sessionId}
                  onClick={() => sessionId && api.browserForward(sessionId, activeLane)}>
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3.5 10.5 8 6 12.5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </button>
                <button type="button" className="icon-btn" aria-label="刷新" title="刷新页面" disabled={!sessionId}
                  onClick={() => sessionId && api.browserReload(sessionId, activeLane)}>
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13 8a5 5 0 1 1-1.6-3.7M13 2.6V5.4h-2.8" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </button>
                <form className="browser-address" onSubmit={submitAddress}>
                  <input
                    className="browser-address-input mono"
                    aria-label="地址栏"
                    spellCheck={false}
                    autoComplete="off"
                    placeholder="输入 http(s):// 或 localhost:端口"
                    value={address}
                    onChange={(event) => setAddress(event.target.value)}
                    onFocus={(event) => { setEditing(true); event.target.select(); }}
                    onBlur={() => setEditing(false)}
                  />
                </form>
                <button type="button" className={`browser-ctrl-btn${pickMode ? ' is-primary' : ''}`}
                  disabled={!sessionId || !frameSrc || mirror.agentControlled} onClick={() => setPickMode((v) => !v)}>
                  {pickMode ? '点击页面元素…' : '选元素'}
                </button>
                <button type="button" className="browser-ctrl-btn" disabled={!sessionId} onClick={() => toggleLogs(false)}>
                  控制台
                </button>
                <button type="button" className="browser-ctrl-btn" disabled={!sessionId} onClick={() => toggleLogs(true)}>
                  网络
                </button>
              </div>
            </div>
            {actionError && <p className="browser-reason is-error" role="alert">{actionError}</p>}
            {unavailable && (
              <div className="browser-unavailable" role="status">
                <p><strong>内置浏览器当前不可用：</strong>{browserStatus.reason || '运行时未就绪'}</p>
                <p>可用替代方案：刷新浏览器状态，或改用外部系统浏览器打开目标网址（支持 Windows / macOS / Linux）。</p>
                <div className="browser-unavailable-actions">
                  <button type="button" className="browser-ctrl-btn" onClick={() => useStore.getState().loadBrowserStatus()}>
                    重新检测运行时
                  </button>
                  {externalUrl && (
                    <a className="browser-ctrl-btn" href={externalUrl} target="_blank" rel="noreferrer">
                      在新标签页打开
                    </a>
                  )}
                </div>
              </div>
            )}
            <div
              id="mirror-stage"
              className={`mirror-stage is-interactive${pickMode ? ' is-picking' : ''}${mirror.agentControlled ? ' is-agent-controlled' : ''}`}
              tabIndex={0}
              role="application"
              aria-label="远程浏览器实时交互画面（点击、滚轮与按键会同步到页面）"
              onWheel={handleWheel}
              onKeyDown={handleKeyDown}
            >
              {frameSrc
                ? (
                  <div className="mirror-stage-canvas">
                    <img ref={imgRef} className="mirror-frame" src={frameSrc} alt="浏览器实时画面" onClick={handleStageClick} draggable={false} />
                    {mirror.agentControlled && actionBox && vpW > 0 && vpH > 0 && (
                      <div
                        className="browser-target-box"
                        style={{
                          left: `${(actionBox.x / vpW) * 100}%`,
                          top: `${(actionBox.y / vpH) * 100}%`,
                          width: `${(actionBox.width / vpW) * 100}%`,
                          height: `${(actionBox.height / vpH) * 100}%`
                        }}
                      />
                    )}
                    {mirror.agentControlled && typeof cursorX === 'number' && typeof cursorY === 'number' && vpW > 0 && vpH > 0 && (
                      <div
                        className="browser-agent-cursor-dot"
                        style={{
                          left: `${(cursorX / vpW) * 100}%`,
                          top: `${(cursorY / vpH) * 100}%`
                        }}
                      />
                    )}
                  </div>
                )
                : (
                  <div className="browser-web-launch">
                    <p>{mirror.url ? `当前地址：${mirror.url}` : '浏览器就绪后将在此显示实时画面；也可直接输入地址或通过桌面窗口操作。'}</p>
                    <button type="button" className="btn btn-primary" disabled={launching} onClick={openDesktopWindow}>
                      {launching ? '正在打开…' : '在独立窗口打开'}
                    </button>
                    {launchNote && <p className="browser-web-note" role="status">{launchNote}</p>}
                    {externalUrl && <a className="btn" href={externalUrl} target="_blank" rel="noreferrer">在系统浏览器新标签页打开</a>}
                  </div>
                )}
              {mirror.agentControlled && (
                <div className="browser-stage-control-overlay" onClick={(event) => event.stopPropagation()}>
                  <span className="browser-control-dot" aria-hidden="true" />
                  <span className="browser-stage-control-text">
                    Pilot 正在控制{mirror.controlStatus ? ` · ${mirror.controlStatus}` : ''}
                  </span>
                  <button
                    type="button"
                    className="browser-stop-btn"
                    onClick={() => void useStore.getState().stopBrowserControl(activeLane)}
                  >
                    停止控制 (Stop)
                  </button>
                </div>
              )}
            </div>
            {consoleOpen && (
              <div className="browser-console">
                <div className="browser-console-head">
                  <span>{networkLog ? '网络记录' : '页面控制台'}</span>
                  <button type="button" className="icon-btn" aria-label="关闭日志" onClick={() => setConsoleOpen(false)}>×</button>
                </div>
                <pre className="browser-console-body">{consoleText}</pre>
              </div>
            )}
          </>
        )}
    </div>
  );
}

function DesktopBrowserPanel({ desktopBridge, toolsMenuOpen }: { desktopBridge: DesktopBridge; toolsMenuOpen: boolean }) {
  const sessionId = useStore(state => state.sessionId);
  const workspace = useStore(state => state.workspace);
  const mirror = useStore(state => state.ui.mirror);
  const [viewMode, setViewMode] = useState<'stage' | 'evidence'>('stage');
  const stageRef = useRef<HTMLDivElement>(null);
  const addressRef = useRef<HTMLInputElement>(null);
  const freshTabRef = useRef(false);
  const [address, setAddress] = useState('');
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState('');
  const [consoleOpen, setConsoleOpen] = useState(false);
  const [consoleText, setConsoleText] = useState('');
  const [networkLog, setNetworkLog] = useState(false);
  const [findOpen, setFindOpen] = useState(false);
  const [findText, setFindText] = useState('');
  const [desktopState, setDesktopState] = useState<DesktopBrowserState | null>(null);
  const freshTab = !desktopState?.url || desktopState.url.includes('/marker/');
  freshTabRef.current = freshTab;
  const pageLoading = Boolean(desktopState?.loading);

  useEffect(() => {
    desktopBridge.setWorkspace(workspace || '');
    if (sessionId) {
      desktopBridge.ensureTab(sessionId);
      useStore.getState().ensureMirror();
      void useStore.getState().loadBrowserStatus();
    }
    const stopState = desktopBridge.onState(setDesktopState);
    const stopFocus = desktopBridge.focusAddress(() => addressRef.current?.focus());
    const stopDownload = desktopBridge.onDownload?.((info) => {
      if (info?.state === 'blocked') {
        useStore.getState().showNotice((info as { error?: string }).error || `已阻止危险文件下载：${info?.name || ''}`);
        return;
      }
      const done = info?.state === 'completed';
      useStore.getState().showNotice(done ? `已下载 ${info.name}` : `下载没有完成：${info?.name || ''}`);
    });
    const stopQuote = desktopBridge.onQuote?.((text) => {
      const composer = useStore.getState().composerApi;
      if (!text || !composer) return;
      const quoted = String(text).split('\n').map((line) => `> ${line}`).join('\n');
      composer.appendText(`\n> 页面摘录\n${quoted}\n\n`);
      composer.focus();
    });
    return () => {
      stopState();
      stopFocus();
      stopDownload?.();
      stopQuote?.();
      desktopBridge.setBounds({ visible: false, x: 0, y: 0, width: 0, height: 0 });
    };
  }, [desktopBridge, sessionId, workspace]);

  useEffect(() => {
    if (editing) return;
    const url = desktopState?.url || '';
    setAddress(!url || url.includes('/marker/') ? '' : url);
  }, [desktopState?.url, editing]);

  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) return undefined;
    const push = () => {
      if (viewMode !== 'stage') {
        desktopBridge.setBounds({ visible: false, x: 0, y: 0, width: 0, height: 0 });
        return;
      }
      const rect = visibleStageRect(stage);
      const covered = toolsMenuOpen || document.querySelector('dialog[open], [aria-modal="true"]');
      desktopBridge.setBounds({
        x: rect.x,
        y: rect.y,
        width: rect.width,
        height: rect.height,
        visible: rect.width >= 40 && rect.height >= 40 && !freshTabRef.current
          && !covered && document.visibilityState === 'visible'
      });
    };
    const observer = new ResizeObserver(push);
    observer.observe(stage);
    window.addEventListener('resize', push);
    document.addEventListener('scroll', push, true);
    const timer = setInterval(push, 400);
    push();
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', push);
      document.removeEventListener('scroll', push, true);
      clearInterval(timer);
      desktopBridge.setBounds({ visible: false, x: 0, y: 0, width: 0, height: 0 });
    };
  }, [desktopBridge, toolsMenuOpen, desktopState?.url, viewMode]);

  function submitAddress(event: React.FormEvent) {
    event.preventDefault();
    const value = address.trim();
    if (!value) { addressRef.current?.focus(); return; }
    const page = userPageUrl(value);
    setEditing(false);
    if (!page) { setError('地址无效'); return; }
    setError('');
    desktopBridge.navigate(page);
  }

  async function toggleConsole(network = false) {
    if (consoleOpen && networkLog === network) { setConsoleOpen(false); return; }
    const data = await desktopBridge.logs(network);
    setNetworkLog(network);
    setConsoleText(data?.text || (network ? '没有网络记录' : '没有控制台记录'));
    setConsoleOpen(true);
  }

  useEffect(() => {
    if (!consoleOpen || !desktopBridge.onLog) return undefined;
    return desktopBridge.onLog((entry) => {
      if (Boolean(entry?.network) !== networkLog || !entry?.line) return;
      setConsoleText((text) => (text && !text.startsWith('没有') ? `${text}\n${entry.line}` : entry.line));
    });
  }, [desktopBridge, consoleOpen, networkLog]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'l') return;
      event.preventDefault();
      addressRef.current?.focus();
      addressRef.current?.select();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [desktopBridge]);

  async function openDesktopMenu() {
    const action = await desktopBridge.openMenu();
    if (action === 'find') setFindOpen(true);
    if (action === 'console') toggleConsole(false);
    if (action === 'network') toggleConsole(true);
    if (action === 'pick') {
      const result = await desktopBridge.pick();
      if (!result?.text) return;
      const quoted = result.text.split('\n').map((line) => `> ${line}`).join('\n');
      const composer = useStore.getState().composerApi;
      if (composer) {
        composer.appendText(`\n> 页面元素引用\n${quoted}\n\n`);
        composer.focus();
      }
    }
  }

  const shownUrl = !editing ? (desktopState?.url || '') : address;
  let localPage = false;
  try {
    const host = new URL(shownUrl).hostname.replace(/^\[|\]$/g, '');
    localPage = host === 'localhost' || host === '127.0.0.1' || host === '::1';
  } catch { /* 还不是完整地址 */ }
  const activeDesktopTab = (desktopState?.tabs || []).find((tab) => tab.id === desktopState?.activeId);
  const desktopControlled = Boolean(activeDesktopTab?.agentControlled || mirror.agentControlled);
  const desktopControlStatus = activeDesktopTab?.controlStatus || mirror.controlStatus || '';

  return (
    <div id="panel-browser" className={`panel browser-panel is-desktop${desktopControlled ? ' is-agent-controlled' : ''}`}>
      <AgentControlBar viewMode={viewMode} onViewModeChange={setViewMode} />
      <HandoffBanner />
      {viewMode === 'evidence'
        ? <BrowserEvidenceView sessionId={sessionId} />
        : (
          <>
            <div className="browser-tabs" role="tablist" aria-label="浏览器标签">
              {(desktopState?.tabs || []).map((tab) => (
                <div key={tab.id} className={`browser-tab${tab.id === desktopState?.activeId ? ' is-on' : ''}`}>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={tab.id === desktopState?.activeId}
                    className="browser-tab-label"
                    title={!tab.url || tab.url.includes('/marker/') ? (tab.label || tab.title || '新标签') : tab.url}
                    onClick={() => desktopBridge.selectTab(tab.id)}
                  >
                    {tab.loading
                      ? <span className="browser-tab-spin" aria-hidden="true" />
                      : (tab.favicon ? <img className="browser-tab-icon" src={tab.favicon} alt="" /> : null)}
                    {tab.agentControlled && <span className="browser-tab-control-dot" title="智能体正在控制此标签" />}
                    {tab.agentId && <span className="browser-tab-agent">子代理</span>}
                    <span className="browser-tab-label-text">{tab.label || tab.title || '新标签'}</span>
                  </button>
                  <button
                    type="button"
                    className="browser-tab-close"
                    aria-label={`关闭 ${tab.label || tab.title || '标签'}`}
                    title="关闭标签"
                    onClick={() => desktopBridge.closeTab(tab.id)}
                  >×</button>
                </div>
              ))}
              <button type="button" className="browser-tab-add" title="新标签" aria-label="新标签" onClick={() => desktopBridge.newTab()}>+</button>
            </div>
            <div className="browser-chrome">
              <div className="browser-bar">
                <button
                  type="button"
                  className="icon-btn"
                  aria-label="后退"
                  title={desktopState?.canGoBack ? '后退' : '没有可后退的页面'}
                  disabled={!desktopState?.canGoBack}
                  onClick={() => desktopBridge.back()}
                >
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </button>
                <button
                  type="button"
                  className="icon-btn"
                  aria-label="前进"
                  title={desktopState?.canGoForward ? '前进' : '没有可前进的页面'}
                  disabled={!desktopState?.canGoForward}
                  onClick={() => desktopBridge.forward()}
                >
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3.5 10.5 8 6 12.5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
                </button>
                <button
                  type="button"
                  className="icon-btn"
                  aria-label={pageLoading ? '停止加载' : '刷新页面'}
                  title={pageLoading ? '停止加载' : '刷新当前页面'}
                  disabled={!desktopState?.activeId}
                  onClick={() => (pageLoading ? desktopBridge.stop() : desktopBridge.reload())}
                >
                  {pageLoading
                    ? <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.4 4.4l7.2 7.2M11.6 4.4l-7.2 7.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
                    : <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13 8a5 5 0 1 1-1.6-3.7M13 2.6V5.4h-2.8" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>}
                </button>
                <form className={`browser-address${pageLoading ? ' is-loading' : ''}`} onSubmit={submitAddress}>
                  {localPage && !editing && !freshTab && <span className="browser-address-lock">本机</span>}
                  <input
                    ref={addressRef}
                    className="browser-address-input mono"
                    aria-label="地址栏"
                    spellCheck={false}
                    autoComplete="off"
                    placeholder="搜索或输入网址"
                    value={address}
                    onChange={(event) => setAddress(event.target.value)}
                    onFocus={(event) => { setEditing(true); event.target.select(); }}
                    onBlur={() => setEditing(false)}
                  />
                </form>
                <div className="browser-menu">
                  <button
                    type="button"
                    className="icon-btn"
                    aria-label="浏览器菜单"
                    title="更多"
                    onClick={openDesktopMenu}
                  >
                    <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="3.5" cy="8" r="1.15" fill="currentColor" /><circle cx="8" cy="8" r="1.15" fill="currentColor" /><circle cx="12.5" cy="8" r="1.15" fill="currentColor" /></svg>
                  </button>
                </div>
              </div>
              {findOpen && (
                <form className="browser-find" onSubmit={(event) => { event.preventDefault(); desktopBridge.find(findText); }} onKeyDown={event => {
                  if (event.key !== 'Escape' || event.nativeEvent.isComposing) return;
                  event.preventDefault();
                  setFindOpen(false);
                  setFindText('');
                  desktopBridge.find('');
                  document.querySelector<HTMLButtonElement>('.browser-menu button')?.focus();
                }}>
                  <input
                    autoFocus
                    aria-label="页内查找"
                    placeholder="在页面中查找"
                    value={findText}
                    onChange={(event) => setFindText(event.target.value)}
                  />
                  <button type="submit">查找</button>
                  <button type="button" onClick={() => { setFindOpen(false); setFindText(''); desktopBridge.find(''); }}>关闭</button>
                </form>
              )}
            </div>
            {(desktopState?.error || error) && <p className="browser-reason is-error">{desktopState?.error || error}</p>}
            {desktopControlled && (
              <div className="browser-desktop-control-banner" role="status">
                <span className="browser-control-dot" aria-hidden="true" />
                <span className="browser-desktop-control-text">
                  Pilot 正在控制页面{desktopControlStatus ? ` · ${desktopControlStatus}` : ''}
                </span>
                <button
                  type="button"
                  className="browser-stop-btn"
                  onClick={() => void useStore.getState().stopBrowserControl(mirror.activeLane || undefined)}
                >
                  停止控制 (Stop)
                </button>
              </div>
            )}
            <div
              id="mirror-stage"
              className={`mirror-stage${freshTab ? ' is-fresh' : ''}${desktopControlled ? ' is-agent-controlled' : ''}`}
              ref={stageRef}
              role="application"
              aria-label="内置浏览器画面"
            >
              {freshTab
                ? (
                  <div className="browser-newt">
                    <form onSubmit={submitAddress}>
                      <input
                        aria-label="搜索或输入网址"
                        placeholder="搜索或输入网址"
                        spellCheck={false}
                        autoComplete="off"
                        value={address}
                        onChange={(event) => setAddress(event.target.value)}
                        onFocus={(event) => { setEditing(true); event.target.select(); }}
                        onBlur={() => setEditing(false)}
                      />
                    </form>
                    {(desktopState?.recents || []).length > 0 && (
                      <div className="browser-recents">
                        <p>最近访问</p>
                        {(desktopState?.recents || []).map((item) => (
                          <button
                            key={item.url}
                            type="button"
                            className="browser-recent"
                            onClick={() => desktopBridge.navigate(item.url)}
                          >
                            <RecentMark item={item} />
                            <span className="browser-recent-copy">
                              <strong>{item.title || recentHost(item.url)}</strong>
                              <em>{recentHost(item.url)}</em>
                            </span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )
                : (
                  <div className="mirror-empty">
                    {desktopState?.cover && <img className="browser-cover" src={desktopState.cover} alt="" />}
                  </div>
                )}
            </div>
            {consoleOpen && (
              <div className="browser-console">
                <div className="browser-console-head">
                  <span>{networkLog ? '网络记录' : '页面控制台'}</span>
                  <button type="button" className="icon-btn" aria-label="关闭控制台" title="关闭" onClick={() => setConsoleOpen(false)}>
                    <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.4 4.4l7.2 7.2M11.6 4.4l-7.2 7.2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
                  </button>
                </div>
                <pre className="browser-console-body">{consoleText}</pre>
              </div>
            )}
            {desktopState?.emulated && (
              <div className="browser-status">
                <p id="mirror-hint" className="mirror-hint">
                  {`设备模拟 ${desktopState.emulated.width}×${desktopState.emulated.height}`}
                  <button type="button" className="browser-reset" onClick={() => desktopBridge.resetEmulation()}>还原</button>
                </p>
              </div>
            )}
          </>
        )}
    </div>
  );
}
