import type { ThreadRow, UserRow, TurnRow, PlanRow, ResultRow, SandboxRow, ArbitrationRow, ToolRow as ToolView, Thinking, AgentView, RunUsage } from '../types/thread.js';
import type { MessageAttachment, RequestReport } from '../types/runtime.js';
import type { ApiResults } from '../types/api.js';
import { errorInfo } from '../lib/errors.js';
import { RequestList } from './RequestUsage.jsx';
import { cacheRatio, formatCache, totalCost } from '../lib/request-usage.js';
import ThreadNavigator from './ThreadNavigator.jsx';
import { modifierKey, duplicateResult } from '../lib/interaction.js';
import { buildThreadDisplay, type CompactionGroupRow, type DisplayThreadRow } from '../lib/thread-display.js';
import React, { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Button } from '@astryxdesign/core/Button';
import { Dialog } from '@astryxdesign/core/Dialog';
import { IconButton } from '@astryxdesign/core/IconButton';
import { Spinner } from '@astryxdesign/core/Spinner';
import { StatusDot } from '@astryxdesign/core/StatusDot';
import { useStickToBottom } from 'use-stick-to-bottom';
import { useStore } from '../store.js';
import { api, apiFetch } from '../api.js';
import { historyPrefetchDistance } from '../lib/history.js';
import { MarkdownBody } from './MarkdownBody.jsx';
import { StructuredDocumentView, isStructuredDocumentFormat } from './StructuredDocumentView.jsx';
import {
  TOOL_STATUS_TITLES,
  computeToolSpanSeconds,
  extractFileMeta,
  formatLatency,
  formatProcessDuration,
  parseAsk,
  parseToolDisplay,
  questionChoice,
  summarizeActiveTool,
  toolLabel,
  type ToolIconKind
} from '../lib/tool-meta.js';
import { agentMeta, agentTitle, manifestFacts, splitSubagentResult, summarizeAgents, agentStatusLabel, agentStatusDotVariant } from '../lib/agents.js';
import { splitAttachmentMarkers, attachmentUrl, isImageAttachment, fileBadge, formatBytes } from '../lib/attachments.js';
import { parseDiff } from '../lib/diff.js';
import { writeClipboard } from '../lib/clipboard.js';
import { formatClock, formatCount, formatTime } from '../thread-utils.js';
import { messagePeek } from '../lib/side-chat.js';
import { Icon } from './Icon.jsx';
import './conversation.css';

/* 回到底部的弹簧参数：库的默认值在长距离上要 1s 以上，这里压到 ~200ms 收敛。 */
const BACK_TO_LATEST = { stiffness: 0.2, damping: 0.9, mass: 1 };
function latestAnimation() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : BACK_TO_LATEST;
}
const PdfAttachmentPreview = React.lazy(() => import('./PdfAttachmentPreview.jsx'));

/**
 * 消息流：真实 DOM + use-stick-to-bottom（MIT）+ 浏览器原生 scroll anchoring。
 *
 * 位置正确性全部由两个成熟机制负责，组件里不再有任何 scrollTop 计算：
 * 1. use-stick-to-bottom：只有「用户本来就贴底」时才跟随新内容；滚轮上滑立刻解锁，
 *    流式输出永远抢不走视口，回底交给一个气泡按钮。
 * 2. scroll anchoring：前插历史、视口上方某行长高、图片加载完成……所有布局变化
 *    都由浏览器把视口锚点钉在原地。它生效的前提是 .thread 保持 overflow-anchor: auto，
 *    且消息行不能带 transform（这就是入场动画用 backwards 而不是 both 的原因）。
 */
export function Thread() {
  const rows = useStore((state) => state.thread.rows);
  const history = useStore((state) => state.thread.history);
  const eventCount = useStore((state) => state.thread.windowEvents.length);
  const bottomSignal = useStore((state) => state.ui.bottomSignal);
  const requestEarlierHistory = useStore((state) => state.requestEarlierHistory);
  const requestLaterHistory = useStore((state) => state.requestLaterHistory);
  const prefetchEarlier = useStore((state) => state.prefetchEarlier);
  const applyEarlierPage = useStore((state) => state.applyEarlierPage);

  /*
   * initial / resize 都用 'instant'：首屏直接落在最新消息；流式输出时底部内容持续长高，
   * 每帧瞬时贴底。用弹簧动画追赶会稳定落后一两百像素，还会让「回到底部」按钮反复闪烁。
   */
  const { scrollRef, contentRef, scrollToBottom, isAtBottom, stopScroll } = useStickToBottom({
    initial: 'instant',
    resize: 'instant'
  });

  /*
   * 预取只在「用户自己往上滚」时触发，用方向判断而不是「接近顶部」判断：
   * 前插历史后的位置补偿同样表现为 scrollTop 变大（等价于向下滚），
   * 拿它当信号会把「加载 → 补偿 → 再加载」串成自我触发的死循环。
   *
   * 两段式：进入预取区先 prefetchEarlier() 把更早一页拉进内存（领边触发，不等滚动停稳），
   * 滚动静默后再 requestEarlierHistory() 把它插进 DOM——插入必须是「不动的时候」，
   * 滚动中前插内容浏览器会抑制 scroll anchoring，视口会被推走一截（实测约 200px）。
   * 这样等待时间只剩插入本身，网络那一趟在用户手还在动的时候就跑完了。
   */
  const lastScrollTop = useRef(0);
  const armed = useRef(false);
  const earlierTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const navigationUntil = useRef(0);
  /*
   * 惯性上滑过程中直接前插缓冲页时，部分内核会暂时抑制原生 overflow-anchor。
   * 在插入前后记录 scrollHeight 与 scrollTop，若浏览器未自动补偿锚点，则在 useLayoutEffect
   * 绘制前同步补齐高度差，做到上滑翻页既不撞顶卡顿，也不发生肉眼可见跳动。
   */
  const prependAnchorRef = useRef<{ scrollHeight: number; scrollTop: number; firstKey: string | null; count: number }>({
    scrollHeight: 0, scrollTop: 0, firstKey: null, count: 0
  });
  const capturePrependAnchor = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    prependAnchorRef.current = {
      scrollHeight: el.scrollHeight,
      scrollTop: el.scrollTop,
      firstKey: rows[0]?.key ?? null,
      count: rows.length
    };
  }, [rows, scrollRef]);

  useLayoutEffect(() => {
    const el = scrollRef.current;
    const prev = prependAnchorRef.current;
    const currentFirstKey = rows[0]?.key ?? null;
    if (el && prev.count > 0 && rows.length > prev.count && currentFirstKey !== prev.firstKey && !isAtBottom) {
      const deltaHeight = el.scrollHeight - prev.scrollHeight;
      const browserDeltaTop = el.scrollTop - prev.scrollTop;
      if (deltaHeight > 0 && Math.abs(browserDeltaTop - deltaHeight) > 16) {
        el.scrollTop = prev.scrollTop + deltaHeight;
        lastScrollTop.current = el.scrollTop;
      }
    }
    if (el) {
      prependAnchorRef.current = {
        scrollHeight: el.scrollHeight,
        scrollTop: el.scrollTop,
        firstKey: currentFirstKey,
        count: rows.length
      };
    }
  }, [rows, isAtBottom, scrollRef]);

  const beforeNavigation = useCallback(() => {
    clearTimeout(earlierTimer.current);
    navigationUntil.current = performance.now() + 250;
  }, []);
  const scheduleEarlier = useCallback(() => {
    clearTimeout(earlierTimer.current);
    earlierTimer.current = setTimeout(() => {
      capturePrependAnchor();
      void requestEarlierHistory();
    }, 80);
  }, [capturePrependAnchor, requestEarlierHistory]);
  useEffect(() => () => clearTimeout(earlierTimer.current), []);

  const handleScroll = useCallback((event: React.UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    const threshold = historyPrefetchDistance(el.clientHeight);
    const toTop = el.scrollTop;
    const toBottom = Math.max(0, el.scrollHeight - el.scrollTop - el.clientHeight);
    const goingUp = toTop < lastScrollTop.current;
    const goingDown = toTop > lastScrollTop.current;
    lastScrollTop.current = toTop;
    if (performance.now() < navigationUntil.current) return;
    if (toTop > threshold) {
      // 退出去才重新武装：一次进入只预取一次，避开「插入 → 仍在区内 → 再预取」的连锁
      armed.current = false;
    }
    if (goingUp && toTop <= threshold) {
      if (!armed.current) {
        armed.current = true;
        void prefetchEarlier();
      }
      // 接近顶部缓冲区且预取页已就绪时立即无缝接入，避免惯性滚动一路撞到 scrollTop=0 硬停顿
      if (toTop <= Math.max(120, threshold * 0.35)) {
        capturePrependAnchor();
        if (applyEarlierPage()) {
          clearTimeout(earlierTimer.current);
          return;
        }
      }
      scheduleEarlier();
    }
    if (goingDown && toBottom <= threshold) {
      void requestLaterHistory();
    }
  }, [applyEarlierPage, capturePrependAnchor, prefetchEarlier, requestLaterHistory, scheduleEarlier]);

  /* 已经贴在顶部时继续上滑不会再产生 scroll 事件，靠 wheel 兜住「还想看更早」的意图。 */
  const handleWheel = useCallback((event: React.WheelEvent<HTMLDivElement>) => {
    if (event.deltaY >= 0 || event.currentTarget.scrollTop > 0) {
      return;
    }
    stopScroll();
    capturePrependAnchor();
    // 顶到头还在推：缓冲里有就当场插进去，不用再等空闲计时
    if (!applyEarlierPage()) {
      scheduleEarlier();
    }
  }, [applyEarlierPage, capturePrependAnchor, scheduleEarlier, stopScroll]);

  /* 内容不足一屏时不会产生滚动事件，主动补更早的一页，否则历史永远加载不出来。 */
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || !history.hasMore || history.loading || history.error) {
      return;
    }
    if (el.scrollHeight - el.clientHeight < 8) {
      // 等当前分页 Promise 的 finally 清理完成；同步调用会复用刚结束的请求，
      // 没有新增可见行的那一页就不会再有变化来唤醒下一次加载。
      const timer = setTimeout(() => requestEarlierHistory(), 0);
      return () => clearTimeout(timer);
    }
  }, [rows.length, eventCount, history.hasMore, history.loading, history.error, requestEarlierHistory, scrollRef]);

  /* 发送消息 / 切换会话后主动贴底：此刻用户要看的是刚发出的内容。 */
  const lastSignal = useRef(bottomSignal);
  useEffect(() => {
    if (bottomSignal === lastSignal.current) {
      return;
    }
    lastSignal.current = bottomSignal;
    scrollToBottom({ animation: latestAnimation() });
  }, [bottomSignal, scrollToBottom]);

  const display = useMemo(() => buildThreadDisplay(rows), [rows]);

  if (!rows.length && !eventCount && !history.hasMore) {
    return <EmptyState />;
  }

  return (
    <div className="thread-scroll-region conversation-thread">
      <div className="thread" ref={scrollRef} role="region" aria-label="消息记录" tabIndex={0} onScroll={handleScroll} onWheel={handleWheel}>
        <div className="thread-column" ref={contentRef}>
          {history.hasMore && (
            <RetryRow
              label={history.error ? '加载更早失败，点击重试' : history.loading ? '正在加载更早消息…' : '加载更早消息'}
              disabled={history.loading}
              onClick={() => { stopScroll(); requestEarlierHistory({ force: true }); }}
            />
          )}
          {display.rows.map((row, index) => (
            <RowView
              key={row.key}
              row={row}
              duplicate={row.type === 'result' && duplicateResult(display.rows as ThreadRow[], index)}
              hasPrecedingReply={index > 0 && display.rows[index - 1]?.type === 'turn' && Boolean((display.rows[index - 1] as TurnRow).reply?.text)}
              isLast={!display.rows.slice(index + 1).some(item => item.type === 'turn' || item.type === 'user')}
            />
          ))}
          {history.hasNewer && history.forwardError && (
            <RetryRow label="加载更新失败，点击重试" onClick={() => requestLaterHistory({ force: true })} />
          )}
        </div>
      </div>
      {display.pinnedPlan && (
        <div className="thread-pinned-plan">
          <PlanCard row={display.pinnedPlan} />
        </div>
      )}
      <ThreadNavigator rows={rows} scrollRef={scrollRef} contentRef={contentRef} stopFollowing={stopScroll} beforeJump={beforeNavigation} />
      <ThreadBottomButton visible={!isAtBottom} onJump={() => scrollToBottom({ animation: latestAnimation() })} />
    </div>
  );
}

function ThreadBottomButton({ visible, onJump }: { visible: boolean; onJump: () => void }) {
  if (!visible) {
    return null;
  }
  return (
    <IconButton className="conversation-latest" label="回到底部" onClick={onJump}
      icon={<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3.5v12M5.5 11l4.5 4.5 4.5-4.5" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" /></svg>} />
  );
}

function RetryRow({ label, onClick, disabled = false }: { label: string; onClick: () => void; disabled?: boolean }) {
  return (
    <div className="history-more-wrap">
      <Button size="sm" variant="ghost" label={label} isDisabled={disabled} onClick={onClick} />
    </div>
  );
}

function EmptyState() {
  const workspace = useStore(state => state.workspace || state.draftSession?.workspace || '');
  const project = useStore(state => state.projects.find(item => item.id === state.currentProjectId));
  const [scope, setScope] = useState<ApiResults['worktreeInfo'] | null>(null);
  const [scopeUnavailable, setScopeUnavailable] = useState(false);
  useEffect(() => {
    let alive = true;
    setScope(null);
    setScopeUnavailable(false);
    if (workspace) api.worktreeInfo(workspace)
      .then(info => { if (alive) setScope(info); })
      .catch(() => { if (alive) setScopeUnavailable(true); });
    return () => { alive = false; };
  }, [workspace]);
  const prompts = [
    { title: '了解项目', sub: '识别技术栈、入口与运行方式', prompt: '分析当前工作区的结构、技术栈和启动方式，先读取项目实际配置和说明。' },
    { title: '调研问题', sub: '核对来源，交付可下载的报告', prompt: '请帮我调研以下问题，优先查阅原始来源，说明时间与不确定性，整理为报告并保存到成果面板。调研问题：' },
    { title: '整理资料', sub: '提取要点、核对数据与缺失项', prompt: '请根据我提供的文件整理要点或表格，核对数字和来源，缺失数据不要猜测，并将结果保存到成果面板。具体要求：' }
  ];
  return (
    <div className="thread-scroll-region conversation-thread">
      <div className="thread is-empty">
        <div className="thread-column">
          <section className="conversation-empty" id="empty-state" aria-labelledby="conversation-empty-title">
            <h2 id="conversation-empty-title" className="empty-title">从一个任务开始</h2>
            <p className="empty-sub">描述目标或拖入资料，Pilot 会在工作区执行任务，并留下可核对的改动与验证结论。</p>
            <div className="conversation-scope">
              <Icon name="folder" />
              <div>
                <strong>{project?.name || '当前工作区'}</strong>
                <span title={workspace}>{workspace || '尚未选择工作区'}</span>
                {scope?.branch && <span>{scope.worktree ? '源分支' : 'Git 分支'} · {scope.branch}{scope.worktree ? ' · 独立 worktree' : ''}</span>}
                {scopeUnavailable && <span role="status">工作区详情暂不可用</span>}
              </div>
              <Button size="sm" label="选择工作区" onClick={() => useStore.getState().setUi({ picker: { title: '选择任务工作区' } })} />
            </div>
            <ul className="conversation-prompts" aria-label="任务示例">
              {prompts.map(item => (
                <li key={item.title}>
                  <Button className="conversation-prompt" label={item.title + ' · ' + item.sub} size="lg" variant="ghost" width="100%"
                    endContent={<Icon name="chevron" />} onClick={() => {
                      // 草稿态直接填进输入框；发送首条消息时才创建会话。
                      const state = useStore.getState();
                      if (!state.sessionId && !state.draftSession) state.startNewSession();
                      state.composerApi?.appendText((state.composerApi.getText().trim() ? '\n' : '') + item.prompt);
                      state.composerApi?.focus();
                    }}>
                    <span className="conversation-prompt-copy">
                      <span className="conversation-prompt-title">{item.title}</span>
                      <span className="conversation-prompt-note">{item.sub}</span>
                    </span>
                  </Button>
                </li>
              ))}
            </ul>
            <p className="empty-keys">
              <span><kbd>{modifierKey}</kbd><kbd>N</kbd> 新任务</span>
              <span><kbd>@</kbd> 引用文件</span>
              <span><kbd>/</kbd> 快捷指令</span>
              <span><kbd>Esc</kbd> 停止</span>
            </p>
          </section>
        </div>
      </div>
    </div>
  );
}

const RowView = memo(function RowView({ row, isLast, duplicate, hasPrecedingReply = false }: { row: DisplayThreadRow; isLast: boolean; duplicate: boolean; hasPrecedingReply?: boolean }) {
  switch (row.type) {
    case 'user': return <UserMessage row={row} />;
    case 'turn': return <Turn row={row} isLast={isLast} />;
    case 'compaction-group': return <CompactionGroupCard row={row} />;
    case 'result': return <ResultCard row={row} duplicate={duplicate} hasPrecedingReply={hasPrecedingReply} />;
    case 'sandbox': return <SandboxCard row={row} />;
    case 'arb': return <ArbitrationCard row={row} />;
    default: return null;
  }
});

/* ---------- 用户消息 ---------- */

function UserMessage({ row }: { row: UserRow }) {
  const text = row.text || '';
  const [open, setOpen] = useState(false);
  const [previewFile, setPreviewFile] = useState<MessageAttachment | null>(null);
  const parts = splitAttachmentMarkers(text);
  const peek = messagePeek(parts.body || text);
  const attachments = row.attachments || [];
  const images = attachments.filter(isImageAttachment);
  const files = [...(parts.files || []), ...attachments.filter(item => !isImageAttachment(item))];
  const canPeek = peek.collapsed && !images.length && !files.length;
  const actions = row.steered ? null : (
    <div className="msg-actions">
      <IconButton className="message-action msg-edit-btn" size="sm" variant="ghost"
        label="编辑并重新发送" icon={<Icon name="edit" />} onClick={() => beginEdit(row)} />
      {row.sequence != null && (
        <IconButton className="message-action msg-edit-btn" size="sm" variant="ghost" label="回退或另开分支"
          icon={<Icon name="branch" />} onClick={() => useStore.getState().openCheckpoint(row.sequence!)} />
      )}
    </div>
  );
  if (canPeek && !open) {
    return (
      <article className="msg msg-user" tabIndex={-1} data-nav-key={row.key} data-sequence={row.sequence ?? undefined}>
        <button type="button" className="msg-peek" aria-expanded="false" title="展开这条消息" onClick={() => setOpen(true)}>
          <span className="msg-peek-grip" aria-hidden="true">
            <svg viewBox="0 0 16 16"><path d="M3 6.5h10M3 9.5h10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
          </span>
          <span className="msg-peek-text">{peek.preview}</span>
        </button>
        {actions}
      </article>
    );
  }
  return (
    <>
    <article className="msg msg-user" tabIndex={-1} data-nav-key={row.key} data-sequence={row.sequence ?? undefined}>
      <div className="bubble">
        {(parts.body || files.length > 0) && <div className="bubble-flow">{renderMessageBody(parts.body, files, setPreviewFile)}</div>}
        {images.length > 0 && (
          <div className="bubble-images">
            {images.map((image) => <ImageThumb key={image.url || image.path || image.name} image={image} onPreview={setPreviewFile} />)}
          </div>
        )}
      </div>
      {canPeek && (
        <button type="button" className="msg-peek-fold" onClick={() => setOpen(false)}>收起</button>
      )}
      {actions}
    </article>
    {previewFile && <AttachmentPreview file={previewFile} onClose={() => setPreviewFile(null)} />}
    </>
  );
}

function beginEdit(row: UserRow) {
  if (row.attachments?.length) {
    useStore.getState().showNotice('带附件的历史消息请作为新消息补充要求，原附件仍保留在对话中。');
    return;
  }
  const composerApi = useStore.getState().composerApi;
  const plain = row.text || '';
  if (composerApi?.getText().trim() || composerApi?.attachmentIds?.().length) {
    useStore.getState().showNotice('输入框还有未发送的草稿，请先处理草稿再编辑历史消息。');
    composerApi?.focus(); return;
  }
  composerApi?.setText(plain);

  composerApi?.focus();
  useStore.setState((draft) => { draft._editTarget = row.sequence ?? null; });
}

/**
 * 消息正文只渲染文字与文件卡。
 * 图片不再插回输入时的字符位置：粘贴位置会随之后的编辑漂移，图片一旦是行内元素就会把
 * 正文切成绕排的碎片（文字包着图）。图片统一成正文下方一块独立区域，见 bubble-images。
 */
function renderMessageBody(body: string, files: MessageAttachment[] = [], onPreview?: (file: MessageAttachment) => void) {
  return <>
    {body && <span>{body}</span>}
    {files.map((file, index) => <FileCard key={index} file={file} onPreview={onPreview} />)}
  </>;
}

function ImageThumb({ image, onPreview }: { image: MessageAttachment; onPreview?: (image: MessageAttachment) => void }) {
  const sessionId = useStore((state) => state.sessionId);
  const [broken, setBroken] = useState(false);
  const name = image.name || '图片';
  // 读不到图时留固定尺寸的占位，不把排版撑开也不留破图框。
  if (broken) {
    return <span className="bubble-image is-broken" role="img" aria-label={`${name} 加载失败`}>图片加载失败</span>;
  }
  return (
    <span className="bubble-image">
      <button className="bubble-image-action" type="button" aria-label={`预览图片 ${name}`} onClick={event => { event.currentTarget.focus({ preventScroll: true }); onPreview?.(image); }}>
        <img src={attachmentUrl(sessionId, image)} alt={name} loading="lazy" onError={() => setBroken(true)} />
      </button>
    </span>
  );
}

function FileCard({ file, onPreview }: { file: MessageAttachment; onPreview?: (file: MessageAttachment) => void }) {
  const sessionId = useStore((state) => state.sessionId);
  const url = attachmentUrl(sessionId, file);
  const badge = fileBadge(file.name, file.mimeType);
  if (!url) return <span className="file-card" title={file.name}>
    <span className={`file-card-badge ft-${badge.tone}`}>{badge.label}</span>
    <span className="file-card-name">{file.name || file.id}</span>
    <span className="file-card-meta">附件</span>
  </span>;
  return (
    <span className="file-card" title={file.name || file.id}>
      <span className={`file-card-badge ft-${badge.tone}`}>{badge.label}</span>
      <span className="file-card-name">{file.name || file.id}</span>
      <span className="file-card-meta">{formatBytes(file.size)}</span>
      <button type="button" className="file-card-action" onClick={event => { event.currentTarget.focus({ preventScroll: true }); onPreview?.(file); }} aria-label={`预览附件 ${file.name || file.id}`} title="预览附件">查看</button>
      <a className="file-card-action" href={url} title="下载附件" aria-label={`下载附件 ${file.name || file.id}`} download={file.name || undefined}>下载</a>
    </span>
  );
}

function AttachmentPreview({ file, onClose }: { file: MessageAttachment; onClose: () => void }) {
  const sessionId = useStore(state => state.sessionId);
  const [text, setText] = useState('');
  const [blobUrl, setBlobUrl] = useState('');
  const [officeFallback, setOfficeFallback] = useState(false);
  const [pdfData, setPdfData] = useState<ArrayBuffer | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const name = file.name || '附件';
  const ext = name.split('.').pop()?.toLowerCase() || '';
  const office = ['docx', 'xlsx', 'pptx'].includes(ext);
  const textual = ['txt', 'md', 'json', 'csv', 'xml', 'yaml', 'yml', 'log', 'js', 'ts', 'tsx', 'css', 'html', 'svg', 'java', 'py', 'sh', 'sql'].includes(ext);
  const image = isImageAttachment(file);
  const url = attachmentUrl(sessionId, file);

  useEffect(() => {
    let alive = true;
    let currentUrl = '';
    const controller = new AbortController();
    setLoading(true); setError(''); setText(''); setBlobUrl(''); setPdfData(null); setOfficeFallback(false);
    void (async () => {
      try {
        if (office && sessionId) {
          const storedName = String(file.path || '').split(/[\\/]/).pop() || '';
          const result = await api.attachmentPreviewText(sessionId, storedName, controller.signal);
          if (!result || !alive) throw new Error('附件内容为空或无法读取');
          setOfficeFallback(true);
          setText(result.text || '（没有可读文本内容）');
        } else if (image || ext === 'pdf' || textual) {
          const response = await apiFetch(url, { signal: controller.signal });
          if (!response.ok) throw new Error(`读取附件失败（HTTP ${response.status}）`);
          const blob = await response.blob();
          if (ext === 'pdf') { if (alive) setPdfData(await blob.arrayBuffer()); }
          else if (image) { currentUrl = URL.createObjectURL(blob); if (alive) setBlobUrl(currentUrl); }
          else setText((await blob.text()).slice(0, 250000));
        } else {
          setError('此格式暂不支持应用内预览，请下载后使用本机应用打开。');
        }
      } catch (failure) {
        if (alive && !controller.signal.aborted) setError(errorInfo(failure).message || '预览失败，请下载附件后重试。');
      } finally { if (alive) setLoading(false); }
    })();
    return () => { alive = false; controller.abort(); if (currentUrl) URL.revokeObjectURL(currentUrl); };
  }, [file, sessionId, url, ext, office, textual, image]);

  const supportsModal = typeof HTMLDialogElement !== 'undefined' && typeof HTMLDialogElement.prototype.showModal === 'function';
  const closeRef = useRef<HTMLButtonElement>(null);
  useLayoutEffect(() => {
    if (supportsModal) return;
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeRef.current?.focus();
    return () => { if (trigger?.isConnected) trigger.focus({ preventScroll: true }); };
  }, [supportsModal]);

  const preview = <section className={'attachment-preview conversation-attachment-preview' + (supportsModal ? '' : ' is-inline')}
    aria-label={supportsModal ? undefined : '附件预览：' + name} onKeyDown={event => {
      if (event.key !== 'Escape' || event.nativeEvent.isComposing || event.keyCode === 229) return;
      event.stopPropagation();
      if (!supportsModal) { event.preventDefault(); onClose(); }
    }}>
    <header>
      <strong title={name}>{name}</strong>
      <div>
        {url && <a className="btn" href={url} download={name}>下载</a>}
        <IconButton ref={closeRef} variant="ghost" label="关闭预览" icon={<Icon name="close" />} onClick={onClose} />
      </div>
    </header>
    <div className={'attachment-preview-content' + (pdfData ? ' is-pdf' : '')} aria-busy={loading}>
      {loading ? <p role="status"><Spinner size="sm" shade="subtle" aria-hidden="true" />正在读取附件…</p>
        : error ? <p role="alert">{error}</p>
        : blobUrl && image ? <img src={blobUrl} alt={name} />
        : pdfData ? <React.Suspense fallback={<p role="status">正在加载 PDF 阅读器…</p>}>
          <PdfAttachmentPreview data={pdfData} name={name} />
        </React.Suspense> : isStructuredDocumentFormat(name)
          ? <StructuredDocumentView text={text} filename={name} />
          : <pre tabIndex={0}>{text}</pre>}
    </div>
    {office && officeFallback && <footer role="status">这里只展示可提取文本，不还原 Office 版式；完整文件可用右上角下载。</footer>}
  </section>;
  if (!supportsModal) return preview;
  return <Dialog isOpen padding={0} width="min(1000px, calc(100vw - 24px))" maxHeight="calc(100dvh - 24px)"
    aria-label={'附件预览：' + name} className="conversation-attachment-dialog"
    onOpenChange={open => { if (!open) onClose(); }}>
    {preview}
  </Dialog>;
}

/* ---------- 回合（思考 + 操作组 + 子智能体 + 回复 + 统计） ---------- */

function ProcessChevron() {
  return (
    <svg className="process-chevron-svg" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m6 3.5 4.5 4.5L6 12.5" />
    </svg>
  );
}

function ProcessKindIcon({ kind }: { kind: ToolIconKind }) {
  let content: React.ReactNode;
  switch (kind) {
    case 'java':
      content = (
        <>
          <path d="M4.2 7.2h6.2v2.7a2.8 2.8 0 0 1-2.8 2.8H7a2.8 2.8 0 0 1-2.8-2.8V7.2Z" />
          <path d="M10.4 8h1.1a1.3 1.3 0 0 1 0 2.6h-1.1" />
          <path d="M6.2 4.8c0-.7.7-1 .7-1.7M8.5 4.8c0-.7.7-1 .7-1.7" />
          <path d="M3.6 13.8h7.6" />
        </>
      );
      break;
    case 'markdown':
      content = (
        <>
          <path d="M2.4 11.4V4.8l2.5 3.1 2.5-3.1v6.6" />
          <path d="M11.6 4.8v6.2m-2-2 2 2 2-2" />
        </>
      );
      break;
    case 'typescript':
    case 'javascript':
    case 'python':
    case 'code':
      content = <path d="m5.4 5.2-2.9 2.8 2.9 2.8m5.2-5.6 2.9 2.8-2.9 2.8M9 3.6 7 12.4" />;
      break;
    case 'style':
      content = <path d="M6 3 4.6 13M11.4 3 10 13M3 6.2h10M2.6 9.8h10" />;
      break;
    case 'json':
      content = <path d="M5.6 3.2c-1 0-1.4.5-1.4 1.4v1.8c0 .8-.4 1.3-1.2 1.6.8.3 1.2.8 1.2 1.6v1.8c0 .9.4 1.4 1.4 1.4M10.4 3.2c1 0 1.4.5 1.4 1.4v1.8c0 .8.4 1.3 1.2 1.6-.8.3-1.2.8-1.2 1.6v1.8c0 .9-.4 1.4-1.4 1.4" />;
      break;
    case 'markup':
      content = <path d="m5.5 4.8-3 3.2 3 3.2m5-6.4 3 3.2-3 3.2" />;
      break;
    case 'shell':
    case 'terminal':
      content = <path d="m3.5 5 3.2 3-3.2 3M8.2 11h4.3" />;
      break;
    case 'search':
      content = (
        <>
          <circle cx="7.2" cy="7.2" r="4.2" />
          <path d="m10.5 10.5 2.7 2.7" />
        </>
      );
      break;
    case 'folder':
      content = <path d="M2.4 4.4h3.7l1.5 1.5h6v6.5H2.4Z" />;
      break;
    case 'browser':
      content = (
        <>
          <circle cx="8" cy="8" r="5.4" />
          <path d="M2.8 8h10.4M8 2.6a8 8 0 0 1 0 10.8M8 2.6a8 8 0 0 0 0 10.8" />
        </>
      );
      break;
    case 'plan':
      content = <path d="m3 5 1.3 1.3L6.6 4M8.4 5.2H13M3 9.2l1.3 1.3L6.6 8.2M8.4 9.4H13M8.4 12.6H13" />;
      break;
    case 'agent':
      content = (
        <>
          <circle cx="8" cy="3.8" r="1.6" />
          <circle cx="4" cy="11.8" r="1.6" />
          <circle cx="12" cy="11.8" r="1.6" />
          <path d="M7.2 5.2 4.8 10.4M8.8 5.2l2.4 5.2" />
        </>
      );
      break;
    case 'file':
    default:
      content = <path d="M4.2 2.4h5l3.2 3.2v8H4.2Z M9.2 2.4v3.2h3.2" />;
      break;
  }
  return (
    <svg className={`process-kind-icon is-${kind}`} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {content}
    </svg>
  );
}

type ScrollFade = 'none' | 'top' | 'bottom' | 'both';

/**
 * 限高可滑动容器：默认由父级折叠，展开后限制最大高度并支持内部下拉滚动；
 * 顶部 / 底部在可滚动方向显示柔和渐隐遮罩，流式输出时仅在贴底状态跟随，用户上翻立刻放手。
 */
function ProcessScrollViewport({
  active,
  className,
  autoFollow = false,
  followKey,
  focusSelector,
  tabIndex,
  children
}: {
  active: boolean;
  className: string;
  autoFollow?: boolean;
  followKey?: unknown;
  focusSelector?: string;
  tabIndex?: number;
  children: React.ReactNode;
}) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const innerRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const thumbRef = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const [fade, setFade] = useState<ScrollFade>('none');
  const [scrollable, setScrollable] = useState(false);

  const measureScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    const maxScroll = el.scrollHeight - el.clientHeight;
    const overflow = maxScroll > 2;
    const top = overflow && el.scrollTop > 2;
    const bottom = overflow && maxScroll - el.scrollTop > 2;
    const next: ScrollFade = top && bottom ? 'both' : top ? 'top' : bottom ? 'bottom' : 'none';
    setFade((prev) => (prev === next ? prev : next));
    setScrollable((prev) => (prev === overflow ? prev : overflow));
    const thumb = thumbRef.current;
    const track = trackRef.current;
    if (thumb && track && overflow) {
      const trackHeight = Math.max(0, track.clientHeight || el.clientHeight - 12);
      const thumbHeight = Math.max(28, Math.min(trackHeight, Math.round((el.clientHeight / el.scrollHeight) * trackHeight)));
      const travel = Math.max(0, trackHeight - thumbHeight);
      const thumbTop = Math.round((el.scrollTop / maxScroll) * travel);
      thumb.style.height = `${thumbHeight}px`;
      thumb.style.transform = `translateY(${thumbTop}px)`;
    }
  }, []);

  useLayoutEffect(() => {
    if (!active) return undefined;
    if (autoFollow) following.current = true;
    const el = scrollRef.current;
    const inner = innerRef.current;
    measureScroll();
    const frame = requestAnimationFrame(measureScroll);
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(measureScroll) : null;
    if (el) observer?.observe(el);
    if (inner) observer?.observe(inner);
    return () => {
      cancelAnimationFrame(frame);
      observer?.disconnect();
    };
  }, [active, autoFollow, measureScroll]);

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el || !active) return;
    if (autoFollow && following.current) {
      el.scrollTop = el.scrollHeight;
    }
    measureScroll();
  }, [active, autoFollow, followKey, measureScroll]);

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el || !active || !focusSelector) return;
    const target = el.querySelector<HTMLElement>(focusSelector);
    if (!target) return;
    const view = el.getBoundingClientRect();
    const box = target.getBoundingClientRect();
    if (box.top < view.top || box.bottom > view.bottom) {
      const offset = box.top - view.top + el.scrollTop;
      el.scrollTop = Math.max(0, offset - Math.max(16, (el.clientHeight - box.height) / 2));
      measureScroll();
    }
  }, [active, focusSelector, measureScroll]);

  const handleScroll = useCallback((event: React.UIEvent<HTMLDivElement>) => {
    const el = event.currentTarget;
    following.current = el.scrollHeight - el.scrollTop - el.clientHeight < 18;
    measureScroll();
  }, [measureScroll]);

  const handleThumbPointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const el = scrollRef.current;
    const track = trackRef.current;
    const thumb = thumbRef.current;
    if (!el || !track || !thumb) return;
    const maxScroll = el.scrollHeight - el.clientHeight;
    if (maxScroll <= 2) return;
    event.preventDefault();
    event.stopPropagation();
    const startY = event.clientY;
    const startScrollTop = el.scrollTop;
    const travel = Math.max(1, track.clientHeight - thumb.offsetHeight);
    const pointerId = event.pointerId;
    thumb.setPointerCapture?.(pointerId);
    thumb.dataset.dragging = 'true';
    const onMove = (move: PointerEvent) => {
      el.scrollTop = startScrollTop + ((move.clientY - startY) / travel) * maxScroll;
    };
    const onEnd = () => {
      delete thumb.dataset.dragging;
      thumb.releasePointerCapture?.(pointerId);
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onEnd);
      window.removeEventListener('pointercancel', onEnd);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onEnd);
    window.addEventListener('pointercancel', onEnd);
  }, []);

  const handleTrackPointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const el = scrollRef.current;
    const track = trackRef.current;
    const thumb = thumbRef.current;
    if (!el || !track || !thumb || event.target === thumb) return;
    const maxScroll = el.scrollHeight - el.clientHeight;
    if (maxScroll <= 2) return;
    const rect = track.getBoundingClientRect();
    const travel = Math.max(1, rect.height - thumb.offsetHeight);
    const offset = event.clientY - rect.top - thumb.offsetHeight / 2;
    el.scrollTop = Math.max(0, Math.min(1, offset / travel)) * maxScroll;
  }, []);

  return (
    <div className="process-scroll-shell" data-fade={fade} data-scrollable={scrollable ? 'true' : 'false'}>
      <div className={className} ref={scrollRef} tabIndex={tabIndex} onScroll={handleScroll}>
        <div className="process-scroll-content" ref={innerRef}>
          {children}
        </div>
      </div>
      <div className="process-scroll-track" ref={trackRef} aria-hidden="true" onPointerDown={handleTrackPointerDown}>
        <div className="process-scroll-thumb" ref={thumbRef} onPointerDown={handleThumbPointerDown} />
      </div>
    </div>
  );
}

const Turn = memo(function Turn({ row, isLast }: { row: TurnRow; isLast: boolean }) {
  // 正文 = 已落库的 delta + 还在路上的 preview；两者不能都拼进 text（会重复）
  const replyText = `${row.reply.text || ''}${row.reply.preview || ''}`;
  const running = useStore((state) => state.running);
  const agentCount = Object.keys(row.agents || {}).length;
  const hasProcess = Boolean(row.thinking) || row.tools.length > 0 || agentCount > 0;
  /*
   * 「重新生成」走 /regenerate：后端用上一条用户输入重跑，历史里不会多一条重复的用户消息。
   * 只在最后一轮、且没有任务在跑时给入口，避免对中间轮次重放出错乱的历史。
   */
  const canRegenerate = isLast && !running && !row.reply.live && Boolean(replyText);
  return (
    <div className="turn-shell" data-run={row.runIds?.join(',') || undefined}>
      {hasProcess && (
        <div className="turn-process">
          {row.thinking && <ThinkingBlock thinking={row.thinking} />}
          {row.tools.length > 0 && <ToolGroup tools={row.tools} />}
          <AgentGroup agents={row.agents} />
        </div>
      )}
      {(replyText || row.reply.live) && (
        <article className={`msg msg-assistant${row.reply.live ? ' is-streaming' : ''}`}>
          <header className="conversation-reply-head">
            <span className="who">Pilot</span>
            {replyText && !row.reply.live && (
              <div className="reply-actions">
                <CopyControl text={replyText} label="复制这条回复" />
                {canRegenerate && <IconButton className="message-action" size="sm" variant="ghost"
                  label="重新生成" icon={<Icon name="refresh" />} onClick={() => useStore.getState().regenerate()} />}
              </div>
            )}
            {row.reply.live && <span className="conversation-streaming" role="status">
              <Spinner size="sm" shade="subtle" aria-hidden="true" />正在回复
            </span>}
          </header>
          {replyText && <div className="bubble"><MarkdownBody text={replyText} /></div>}
        </article>
      )}
      <TurnStats stats={row.stats} reports={row.requests} agentCount={agentCount} />
    </div>
  );
});

const EXPLORE_TOOL_NAMES = new Set(['read_file', 'search_text', 'glob_files', 'list_dir', 'code_symbol']);

type ToolSegment =
  | { kind: 'item'; tool: ToolView }
  | { kind: 'cluster'; key: string; tools: ToolView[] };

function isFoldableExploreTool(tool: ToolView, sessionLive: boolean): boolean {
  if (!EXPLORE_TOOL_NAMES.has(tool.name)) return false;
  const shown = tool.status === 'run' && !sessionLive ? 'interrupted' : tool.status;
  return shown === 'ok';
}

function partitionToolSegments(tools: ToolView[], sessionLive: boolean): ToolSegment[] {
  const segments: ToolSegment[] = [];
  let buffer: ToolView[] = [];
  const flush = () => {
    if (buffer.length >= 3) {
      segments.push({ kind: 'cluster', key: `cluster:${buffer[0].callId}`, tools: buffer });
    } else {
      for (const item of buffer) segments.push({ kind: 'item', tool: item });
    }
    buffer = [];
  };
  for (const tool of tools) {
    if (isFoldableExploreTool(tool, sessionLive)) buffer.push(tool);
    else {
      flush();
      segments.push({ kind: 'item', tool });
    }
  }
  flush();
  return segments;
}

const ExploreCluster = memo(function ExploreCluster({ tools }: { tools: ToolView[] }) {
  const [expanded, setExpanded] = useState(false);
  const spanSeconds = computeToolSpanSeconds(tools, false);
  const durationLabel = spanSeconds != null && spanSeconds >= 1 ? formatProcessDuration(spanSeconds) : '';
  return (
    <div className={`tool-explore-cluster${expanded ? ' is-open' : ''}`}>
      <button
        type="button"
        className="tool-explore-toggle"
        aria-expanded={expanded}
        onClick={() => setExpanded(prev => !prev)}
      >
        <span className="tool-explore-badge">探索 {tools.length} 项</span>
        <span className="tool-explore-summary">{toolCounts(tools)}</span>
        {durationLabel && <span className="tool-explore-meta">{durationLabel}</span>}
        <span className="tool-row-chevron" aria-hidden="true"><ProcessChevron /></span>
      </button>
      {expanded && (
        <div className="tool-explore-items">
          {tools.map(tool => <ToolRow key={tool.callId} tool={tool} />)}
        </div>
      )}
    </div>
  );
});

const INITIAL_TOOL_SEGMENTS = 40;
const TOOL_SEGMENT_STEP = 80;
/** 只有失败和等待批准会在展开组内自动露出详情；「已中断」只是收尾常态，不替用户撑开行。 */
const AUTO_REVEAL_STATUS = new Set(['error', 'wait']);

/**
 * 操作组单独 memo：流式输出时只有 reply 在变，tools 数组引用不变，这一整块（可能几十行）
 * 直接跳过比对。默认收起为单行摘要，展开后在固定最大高度内滚动，不把对话拉成无限高度。
 */
const ToolGroup = memo(function ToolGroup({ tools }: { tools: ToolView[] }) {
  const sessionLive = useStore(state => state.running);
  const running = sessionLive ? tools.filter(tool => tool.status === 'run').length : 0;
  // 会话已停时，还停在 run 的行界面上会当成中断；组头统计也要跟显示一致，别再写「正在执行」。
  const attention = tools.filter((tool) => {
    const status = tool.status === 'run' && !sessionLive ? 'interrupted' : tool.status;
    return ['error', 'wait', 'interrupted'].includes(status);
  });
  const needsAttention = attention.length > 0;
  const failed = attention.some(tool => tool.status === 'error');
  const hasActiveAsk = sessionLive && tools.some(tool => tool.status === 'run' && tool.name === 'ask_user' && Boolean(parseAsk(tool.args)));
  // 操作组一律收起，除非用户自己点开：滚动历史或新建回合时不自动撑开，避免把正在看的位置顶走。
  // 需要留意、等待回答只体现在摘要的提示上，不代替用户展开。
  const [open, setOpen] = useState(false);
  const [attentionOnly, setAttentionOnly] = useState(false);
  const [visibleLimit, setVisibleLimit] = useState(INITIAL_TOOL_SEGMENTS);
  const detailsRef = useRef<HTMLDetailsElement>(null);
  useSecondTick(running > 0);
  // 只有「正在等我回答」这一种情况替他展开一次；其余提示留在摘要上，收起状态不动。
  useEffect(() => {
    if (hasActiveAsk) setOpen(true);
  }, [hasActiveAsk]);
  const filterAttention = attentionOnly && needsAttention;
  const visibleTools = filterAttention ? attention : tools;
  const segments = useMemo(
    () => (filterAttention ? visibleTools.map(tool => ({ kind: 'item' as const, tool })) : partitionToolSegments(visibleTools, sessionLive)),
    [filterAttention, visibleTools, sessionLive],
  );
  const spanSeconds = computeToolSpanSeconds(tools, running > 0);
  const durationLabel = spanSeconds != null && spanSeconds >= 1 ? formatProcessDuration(spanSeconds) : '';
  const activePeek = running > 0 ? summarizeActiveTool(tools) : '';
  const lastTool = tools[tools.length - 1];
  const toggleAttentionFilter = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    if (!open) {
      setOpen(true);
      setAttentionOnly(true);
    } else {
      setAttentionOnly(prev => !prev);
    }
  };
  const jumpToLatest = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    const list = detailsRef.current?.querySelector<HTMLElement>('.tool-group-list');
    if (list) list.scrollTop = list.scrollHeight;
  };
  const hiddenSegments = !filterAttention && segments.length > visibleLimit ? segments.length - visibleLimit : 0;
  const renderedSegments = hiddenSegments > 0 ? segments.slice(-visibleLimit) : segments;
  return (
    <details
      ref={detailsRef}
      className={`tool-group conversation-tools${running > 0 ? ' is-live' : ''}`}
      open={open}
      onToggle={event => setOpen(event.currentTarget.open)}
    >
      <summary className="tool-group-head">
        {running > 0 && !needsAttention && <Spinner size="sm" shade="subtle" aria-hidden="true" />}
        <span className="tool-group-label">{running ? running + ' 项正在执行' : tools.length + ' 项操作'}</span>
        {durationLabel && <span className="tool-group-duration">{durationLabel}</span>}
        <span className="tool-group-count">{toolCounts(tools)}</span>
        {!open && activePeek && <span className="tool-group-peek" title={activePeek}>{activePeek}</span>}
        {needsAttention && (
          <button
            type="button"
            className={`conversation-tool-attention${filterAttention ? ' is-active' : ''}`}
            aria-pressed={filterAttention}
            title={filterAttention ? '当前仅显示需留意项，点击恢复全部' : '点击仅查看需留意的操作'}
            onClick={toggleAttentionFilter}
          >
            <StatusDot variant={failed ? 'error' : 'warning'} label="需要留意" aria-hidden="true" />
            {filterAttention ? `仅看 ${attention.length} 项` : `${attention.length} 项需留意`}
          </button>
        )}
        {open && !filterAttention && tools.length >= 25 && (
          <button
            type="button"
            className="tool-group-jump"
            title="滚动到最近一条操作"
            onClick={jumpToLatest}
          >
            跳到最新
          </button>
        )}
        <span className="tool-group-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      <ProcessScrollViewport
        active={open}
        className="tool-group-list"
        autoFollow={running > 0 && !needsAttention && !hasActiveAsk}
        followKey={`${visibleTools.length}:${lastTool?.callId ?? ''}:${lastTool?.status ?? ''}:${filterAttention ? 'attn' : 'all'}`}
        focusSelector={hasActiveAsk ? '.arb-options' : needsAttention ? '.tool-row.is-attention' : undefined}
      >
        {hiddenSegments > 0 && (
          <button
            type="button"
            className="tool-group-earlier"
            onClick={() => setVisibleLimit(prev => prev + TOOL_SEGMENT_STEP)}
          >
            显示更早的 {hiddenSegments} 组记录（当前优先展示最近步骤）
          </button>
        )}
        {renderedSegments.map(seg => (
          seg.kind === 'cluster'
            ? <ExploreCluster key={seg.key} tools={seg.tools} />
            : <ToolRow key={seg.tool.callId} tool={seg.tool} />
        ))}
      </ProcessScrollViewport>
    </details>
  );
});

/** 「计划 1 · 命令 3 · 读取 2」：按首次出现顺序，带次数（旧版 refreshToolGroup 的文案）。 */
function toolCounts(tools: ToolView[]) {
  const order: string[] = [];
  const counts = new Map<string, number>();
  for (const tool of tools) {
    const label = toolLabel(tool.name);
    if (!counts.has(label)) {
      counts.set(label, 0);
      order.push(label);
    }
    counts.set(label, (counts.get(label) || 0) + 1);
  }
  return order.map((label) => `${label} ${counts.get(label)}`).join(' · ');
}

/**
 * 单条操作。默认收起为单行摘要，点击后再展开详情；状态放组件内，流式刷新不会顶掉用户折叠状态。
 */
const ToolRow = memo(function ToolRow({ tool }: { tool: ToolView }) {
  const rawBody = String(tool.body || '').trim();
  const [open, setOpen] = useState(AUTO_REVEAL_STATUS.has(tool.status));
  const [copied, setCopied] = useState(false);
  const rowRef = useRef<HTMLDivElement>(null);
  const userToggled = useRef(false);
  const copyTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => { if (AUTO_REVEAL_STATUS.has(tool.status)) setOpen(true); }, [tool.status]);
  useEffect(() => () => clearTimeout(copyTimer.current), []);
  const sessionLive = useStore(state => state.running);
  const running = tool.status === 'run' && sessionLive;
  const shownStatus = tool.status === 'run' && !sessionLive ? 'interrupted' : tool.status;
  const attention = ['error', 'wait', 'interrupted'].includes(shownStatus);
  // 协议里失败正文常带 ERROR: 前缀，界面改成「失败：」。
  const body = tool.status === 'error' && /^ERROR:\s*/i.test(rawBody)
    ? rawBody.replace(/^ERROR:\s*/i, '失败：')
    : rawBody;
  useSecondTick(running);
  const seconds = running ? elapsedSeconds(tool.startedAt, tool.finishedAt) : 0;
  const meta = formatLatency(tool.meta === '未返回结果' ? '' : (tool.meta || ''));
  const display = parseToolDisplay(tool);
  const status = TOOL_STATUS_TITLES[shownStatus] || '状态未知';
  const hasDetail = Boolean(body);
  const Header = hasDetail ? 'button' : 'div';
  const detailLabel = display.fullTarget
    ? `${display.fullTarget}${display.lineRange ? ` ${display.lineRange}` : ''}`
    : `${toolLabel(tool.name)} · ${status.split('：')[0]}`;

  /* 点击展开靠近限高视口底部的行时，只微调内部滚动列表，确保详情立即可见而不牵动外层消息流。 */
  useLayoutEffect(() => {
    if (!open || !hasDetail || !userToggled.current) return;
    userToggled.current = false;
    const row = rowRef.current;
    const container = row?.closest<HTMLElement>('.tool-group-list');
    if (!row || !container) return;
    const view = container.getBoundingClientRect();
    const box = row.getBoundingClientRect();
    if (box.bottom > view.bottom - 12) {
      const overflow = box.bottom - (view.bottom - 16);
      const maxAdvance = Math.max(0, box.top - view.top - 12);
      container.scrollTop += Math.min(overflow, maxAdvance);
    } else if (box.top < view.top + 8) {
      container.scrollTop -= (view.top + 12) - box.top;
    }
  }, [open, hasDetail]);

  const copyDetail = async () => {
    const ok = await writeClipboard(body);
    if (!ok) {
      useStore.getState().showNotice('复制失败，请手动选择文本');
      return;
    }
    setCopied(true);
    clearTimeout(copyTimer.current);
    copyTimer.current = setTimeout(() => setCopied(false), 1400);
  };

  return (
    <div
      ref={rowRef}
      className={`tool-row${open && hasDetail ? ' is-open' : ''}${attention ? ' is-attention' : ''}${shownStatus === 'error' ? ' is-error' : ''}${running ? ' is-running' : ''}`}
    >
      <Header
        className="conversation-tool-line"
        type={hasDetail ? 'button' : undefined}
        aria-expanded={hasDetail ? open : undefined}
        onClick={hasDetail ? () => { userToggled.current = true; setOpen(value => !value); } : undefined}
      >
        <span className="conversation-tool-name">{toolLabel(tool.name)}</span>
        <ProcessKindIcon kind={display.kind} />
        <span className="tool-args" title={display.fullTarget || undefined}>{display.primary}</span>
        {display.lineRange && <span className="tool-line-range">{display.lineRange}</span>}
        {display.diff && (
          <span className="tool-diff" aria-label={`新增 ${display.diff.added} 行，删除 ${display.diff.removed} 行`}>
            <span className="tool-diff-add">+{display.diff.added}</span>
            <span className="tool-diff-del">-{display.diff.removed}</span>
          </span>
        )}
        {display.exitCode != null && (
          <span className="tool-exit-code">· 退出码 {display.exitCode}</span>
        )}
        {(running || attention) && (
          <span className={'conversation-tool-state is-' + shownStatus} title={status}>
            {running ? <Spinner size="sm" shade="subtle" aria-hidden="true" /> : <Icon name="info" />}
            {running && seconds >= 3 ? formatClock(seconds) : status.split('：')[0]}
          </span>
        )}
        {hasDetail && (
          <span className="tool-row-chevron" aria-hidden="true"><ProcessChevron /></span>
        )}
        {meta && !running && <span className="tool-meta" title={meta}>{meta}</span>}
      </Header>
      {open && hasDetail && (
        <div className="tool-body">
          <div className="tool-body-bar">
            <span className="tool-body-target" title={detailLabel}>{detailLabel}</span>
            <div className="tool-body-actions">
              {display.filePath && (
                <button
                  type="button"
                  className="tool-body-action"
                  onClick={() => useStore.getState().openPreview(display.filePath, display.startLine)}
                >
                  预览文件
                </button>
              )}
              <button
                type="button"
                className={`tool-body-action${copied ? ' is-done' : ''}`}
                onClick={copyDetail}
              >
                {copied ? '已复制' : '复制'}
              </button>
            </div>
          </div>
          <pre tabIndex={0}>{body}</pre>
        </div>
      )}
      {running && <AskChoices tool={tool} />}
    </div>
  );
});

function AskChoices({ tool }: { tool: ToolView }) {
  const ask = tool.name === 'ask_user' ? parseAsk(tool.args) : null;
  const [custom, setCustom] = useState('');
  const [sent, setSent] = useState('');
  if (!ask) return null;
  const send = (option: string) => {
    const choice = questionChoice(option, custom);
    if (!choice || sent) return;
    const sessionId = useStore.getState().sessionId;
    if (!sessionId) return;
    setSent(choice);
    void api.answerQuestion(sessionId, tool.callId, choice);
  };
  return (
    <div className="arb-options">
      <p className="arb-summary">{ask.question}</p>
      {ask.options.map(option => (
        <Button key={option} size="sm" label={option} isDisabled={Boolean(sent)} onClick={() => send(option)} />
      ))}
      <input className="arb-custom" value={custom} placeholder="自己填写" aria-label="自己填写"
        onChange={event => setCustom(event.target.value)} />
      <Button size="sm" variant="ghost" label="提交填写" isDisabled={!custom.trim() || Boolean(sent)} onClick={() => send('')} />
      {sent && <span className="sandbox-decision">已提交：{sent}</span>}
    </div>
  );
}

/** 思考块：默认收起为单行摘要，点击后再在限高可滑动区域查看详情；实时思考展开时自动贴底跟随。 */
const ThinkingBlock = memo(function ThinkingBlock({ thinking }: { thinking: Thinking }) {
  const [open, setOpen] = useState(false);
  const live = Boolean(thinking.live);
  useSecondTick(live);
  const seconds = elapsedSeconds(thinking.startedAt, thinking.finishedAt);
  const duration = thinking.startedAt == null || (!live && thinking.finishedAt == null) ? '' : ` ${seconds}s`;
  const action = thinking.action ? ` · ${thinking.action}` : '';
  return (
    <details
      className={`think-block${live ? ' is-live' : ''}`}
      open={open}
      onToggle={event => setOpen(event.currentTarget.open)}
    >
      <summary>
        <span className="think-summary-text">{live ? `思考中${duration}${action}` : `已思考${duration}`}</span>
        <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      <ProcessScrollViewport
        active={open}
        className="think-body"
        autoFollow={live}
        followKey={thinking.text.length}
        tabIndex={0}
      >
        {thinking.text}
      </ProcessScrollViewport>
    </details>
  );
});

/** 每秒重渲染一次，给计时类文案（思考中 Ns、运行耗时）用。 */
function useSecondTick(active: boolean) {
  const [, bump] = useState(0);
  useEffect(() => {
    if (!active) {
      return undefined;
    }
    const timer = setInterval(() => bump((value) => value + 1), 1000);
    return () => clearInterval(timer);
  }, [active]);
}

function elapsedSeconds(startedAt?: number, finishedAt?: number | null) {
  if (startedAt == null || !Number.isFinite(startedAt)) {
    return 0;
  }
  return Math.max(0, Math.floor(((finishedAt ?? Date.now()) - startedAt) / 1000));
}

const AgentGroup = memo(function AgentGroup({ agents }: { agents: Record<string, AgentView> }) {
  const sessionLive = useStore(state => state.running);
  const list = Object.values(agents || {});
  const [open, setOpen] = useState(false);
  if (!list.length) return null;
  const effective = sessionLive
    ? list
    : list.map(agent => (agent.status === 'running' ? { ...agent, status: 'stopped' as const } : agent));
  const summary = summarizeAgents(effective);
  return (
    <details
      className={`agent-group${summary.running > 0 ? ' is-live' : ''}`}
      open={open}
      onToggle={event => setOpen(event.currentTarget.open)}
    >
      <summary className="agent-group-head">
        {summary.running > 0 && <Spinner size="sm" shade="subtle" aria-hidden="true" />}
        {!summary.running && summary.failed > 0 && <StatusDot variant="error" label="有子智能体失败" aria-hidden="true" />}
        <span className="agent-group-label">{summary.text}</span>
        <span className="tool-group-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      <ProcessScrollViewport active={open} className="agent-group-list" followKey={list.length}>
        {list.map(agent => <AgentCard key={agent.id} agent={agent} />)}
      </ProcessScrollViewport>
    </details>
  );
});

const AgentCard = memo(function AgentCard({ agent }: { agent: AgentView }) {
  const sessionLive = useStore(state => state.running);
  const live = agent.status === 'running' && sessionLive;
  useSecondTick(live);
  // 回传正文末尾的 <subagent_result> 是给父模型合并用的清单，不该原样糊在界面上
  const split = splitSubagentResult(agent.result);
  const facts = manifestFacts(split);
  const tools = (agent.tools || []).length;
  const ms = agent.startedAt ? ((live ? Date.now() : (agent.finishedAt || Date.now())) - agent.startedAt) : null;
  const status = live ? agent.status : (agent.status === 'running' ? 'stopped' : agent.status);
  return (
    <details className={`tool-group agent-card is-${status || 'stopped'}`}>
      <summary className="tool-group-head agent-head">
        <StatusDot variant={agentStatusDotVariant(status)}
          label={agentStatusLabel(status)} aria-hidden="true" />
        <span className="tool-group-label agent-name" title={agent.task}>
          {agent.name || agentTitle(agent.task) || agent.id}
        </span>
        <span className="agent-state">{agentStatusLabel(status)}</span>
        <span className="tool-group-count">{agentMeta({ tools, ms, status })}</span>
        <span className="tool-group-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      <div className="agent-body">
        <p className="agent-brief">{agent.task}</p>
        {tools > 0 && (
          <div className="tool-group-list agent-tools">
            {(agent.tools || []).map((tool, index) => {
              const toolStatus = tool.status === 'run' && !sessionLive ? 'interrupted' : tool.status;
              const display = parseToolDisplay(tool);
              return (
                <div key={index} className="tool-row">
                  <div className="conversation-agent-tool">
                    <span className="conversation-tool-name">{toolLabel(tool.name)}</span>
                    <ProcessKindIcon kind={display.kind} />
                    <span className="tool-args" title={display.fullTarget || undefined}>{display.primary}</span>
                    {display.diff && (
                      <span className="tool-diff">
                        <span className="tool-diff-add">+{display.diff.added}</span>
                        <span className="tool-diff-del">-{display.diff.removed}</span>
                      </span>
                    )}
                    <span className={'conversation-tool-state is-' + toolStatus}>{(TOOL_STATUS_TITLES[toolStatus] || '状态未知').split('：')[0]}</span>
                  </div>
                </div>
              );
            })}
          </div>
        )}
        {split.text && (
          <>
            <span className="agent-result-label">结论</span>
            <div className="agent-result"><pre>{split.text}</pre></div>
          </>
        )}
        {facts.length > 0 && <p className="agent-result-facts">{facts.join(' · ')}</p>}
      </div>
    </details>
  );
});

/**
 * 复制按钮：图标点一下变对勾、1.4 秒后复原（旧版 copyControl 的手感）。
 * 剪贴板写入走 lib/clipboard 的双通道，内嵌 webview 里也能复制上。
 */
const CopyControl = memo(function CopyControl({ text, label }: { text: string; label: string }) {
  const [done, setDone] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  async function copy() {
    const ok = await writeClipboard(text);
    if (!ok) {
      useStore.getState().showNotice('复制失败，请手动选择文本');
      return;
    }
    setDone(true);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setDone(false), 1400);
  }
  return <IconButton className={'copy-control message-action' + (done ? ' is-done' : '')}
    label={done ? '已复制回复' : label} size="sm" variant="ghost" icon={<Icon name={done ? 'check' : 'copy'} />} onClick={copy} />;
});

const TurnStats = memo(function TurnStats({ stats, reports, agentCount }: { stats: RunUsage | null; reports?: RequestReport[]; agentCount: number }) {
  const liveRunId = useStore(state => state.running ? state.runId : null);
  const runStartedAt = useStore(state => state.runStartedAt);
  const live = Boolean(liveRunId) && (!stats?.runId || stats.runId === liveRunId);
  useSecondTick(live);
  if (!stats) {
    return null;
  }
  const clockStart = live && runStartedAt
    ? Math.min(stats.startedAt || runStartedAt, runStartedAt)
    : stats.startedAt;
  const elapsed = !clockStart ? null : live
    ? elapsedSeconds(clockStart, null)
    : stats.finishedAt ? elapsedSeconds(clockStart, stats.finishedAt) : null;
  const elapsedLabel = elapsed == null ? '' : formatClock(elapsed);
  // 没有模型实测用量时是估算值，加 ≈（旧版 formatTokenCount 的 exact 语义）
  const mark = stats.exact === false ? '≈' : '';
  const limit = Number(stats.contextLimit) || 0;
  const used = Number(stats.contextTokens) || 0;
  const ratio = limit > 0 ? used / limit : 0;
  const contextTone = ratio >= 0.92 ? ' is-critical' : ratio >= 0.8 ? ' is-warn' : '';
  // 估算路径没有缓存观测：显示「—」，不能把「没观测」读成「没命中」
  const cacheLabel = formatCache(stats.details);
  // 有观测就把命中率提到折叠条上，不展开也能看到；没观测就不占位。
  const cacheSeen = cacheRatio(stats.details) != null;
  const priced = stats.details?.pricedRequests ?? 0;
  const requests = stats.details?.requests ?? stats.modelRequests ?? 0;
  let priceNote: string;
  if (!stats.details) {
    priceNote = '这是较早的记录，没有缓存和单价。';
  } else if (priced === 0) {
    priceNote = '端点没有返回单价，费用先空着。token 和缓存仍然有效。';
  } else {
    const incomplete = priced < requests ? '，合计不完整' : '';
    priceNote = `含压缩与子代理。可计价 ${priced}/${requests} 次${incomplete}。`;
  }
  return (
    <details className="turn-stats-detail">
      <summary>
        <span>{elapsedLabel ? `${elapsedLabel} · ` : ''}用量详情</span>
        {cacheSeen && <span className="turn-cache" title={`本轮累计命中缓存 ${formatCount(stats.cachedInputTokens)} / 总输入 ${formatCount(stats.inputTokens)}`}>缓存 {cacheLabel}</span>}
        <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      <div className="turn-stats">
      {elapsedLabel && <span className="turn-stat"><span>耗时</span><b>{elapsedLabel}</b></span>}
      <span className="turn-stat"><span>模型请求</span><b>{stats.modelRequests == null ? '—' : stats.modelRequests + ' 次'}</b></span>
      {agentCount > 0 && <span className="turn-stat"><span>子智能体</span><b>{agentCount} 个</b></span>}
      <span className="turn-stat" title={String(stats.inputTokens ?? '')}><span>输入</span><b>{mark}{formatCount(stats.inputTokens)}</b></span>
      <span className="turn-stat" title={String(stats.outputTokens ?? '')}><span>输出</span><b>{mark}{formatCount(stats.outputTokens)}</b></span>
      <span className="turn-stat" title={`命中缓存 ${formatCount(stats.cachedInputTokens)} / 总输入 ${formatCount(stats.inputTokens)}`}>
        <span>缓存命中</span><b>{cacheLabel}</b>
      </span>
      <span className={`turn-stat is-context${contextTone}`} title={`当前上下文 ${used} / 有效输入预算 ${limit}（已扣除输出预留与安全余量）`}>
        <span>上下文</span><b>{formatCount(stats.contextTokens)}</b>
      </span>
      <span className="turn-stat"><span>费用估算</span><b>{priced > 0 ? totalCost(stats.details) : '未返回'}</b></span>
      <span className="turn-stat-note">{priceNote}</span>
    </div>{(reports?.length || 0) > 0 && <RequestList reports={reports} />}</details>
  );
});

/* ---------- 卡片 ---------- */

function planStatusTone(status?: string) {
  if (status === 'completed' || status === 'done') return 'completed';
  if (status === 'in_progress' || status === 'doing' || status === 'running') return 'in_progress';
  if (status === 'blocked' || status === 'failed') return 'blocked';
  if (status === 'skipped') return 'skipped';
  return 'pending';
}

const PlanCard = memo(function PlanCard({ row }: { row: PlanRow }) {
  const running = useStore((state) => state.running);
  const items = (row.items || []).filter(item => (item.step || item.text || '').trim());
  if (!items.length) return null;
  const done = items.filter((item) => item.status === 'completed' || item.status === 'done').length;
  const finished = items.length > 0 && done === items.length;
  const pct = Math.round((done / items.length) * 100);
  const activeItem = !finished
    ? (items.find(item => planStatusTone(item.status) === 'in_progress')
      || items.find(item => planStatusTone(item.status) === 'blocked')
      || items.find(item => planStatusTone(item.status) === 'pending'))
    : null;
  const activeStep = activeItem ? (activeItem.step || activeItem.text || '').trim() : '';
  // 已完成、已结束或长计划（>4 步）默认收成单行进度胶囊，避免霸占半屏；点开仍可看全部步骤。
  const defaultOpen = running && !finished && items.length <= 4;
  return (
    <aside className={`plan-card${finished ? ' is-complete' : ''}`}>
      <details open={defaultOpen}>
      <summary className="plan-head">
        <span className="plan-title">执行计划</span>
        <span className="plan-mini-bar" aria-hidden="true"><i style={{ width: `${pct}%` }} /></span>
        <span className="plan-progress">{items.length ? `${done}/${items.length}` : ''}</span>
        {activeStep && <span className="plan-current" title={activeStep}>{activeStep}</span>}
        <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
      </summary>
      {!finished && <div className="plan-bar" aria-hidden="true"><i style={{ width: `${pct}%` }} /></div>}
      <ol className="plan-list">
        {items.map((item, index) => {
          const tone = planStatusTone(item.status);
          return (
          <li key={index} className={`plan-item is-${tone}`}>
            <span className="plan-index">{index + 1}</span>
            <span className="plan-text" title={item.note || undefined}>{item.step || item.text || ''}</span>
            <span className={`plan-status is-${tone}`}>{planStatusLabel(item.status)}</span>
            {item.note && (
              <span className="plan-note">{item.note}</span>
            )}
          </li>
          );
        })}
      </ol>
      </details>
    </aside>
  );
});

function planStatusLabel(status: string) {
  const labels: Record<string, string> = {
    completed: '已完成', done: '已完成',
    in_progress: '进行中', doing: '进行中', running: '进行中',
    blocked: '已阻塞', skipped: '已跳过',
    failed: '受阻', pending: '未开始'
  };
  return labels[status] || '未开始';
}

function CompactionGroupCard({ row }: { row: CompactionGroupRow }) {
  const count = row.items.length;
  const latest = row.items[count - 1];
  const totalMessages = row.items.reduce((sum, item) => sum + (item.coveredMessages || 0), 0);
  return (
    <details className="ctx-card is-divider">
      <summary className="ctx-head">
        <span className="ctx-line" aria-hidden="true" />
        <span className="ctx-pill">
          {count > 1 ? `较早上下文已自动整理（${count} 次）` : '较早上下文已自动整理'}
          <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
        </span>
        <span className="ctx-line" aria-hidden="true" />
      </summary>
      <div className="ctx-panel">
        <p className="ctx-note">
          共整理 {totalMessages} 条较早消息{count > 1 ? `（最近一次：${latest.charactersBefore} → ${latest.charactersAfter} 字符）` : `（${latest.charactersBefore} → ${latest.charactersAfter} 字符）`}。
        </p>
        <pre className="ctx-body">{latest.summary}</pre>
      </div>
    </details>
  );
}

const CHANGED_COLLAPSED_ROWS = 3;
const CHANGED_COUNT = new Intl.NumberFormat('en-US');

type ChangedFile = ResultRow['changedFiles'][number];

function changedCount(value: number) {
  return CHANGED_COUNT.format(value);
}

function ChangedCounts({ added, removed }: { added: number; removed: number }) {
  return (
    <span className="changed-counts">
      <span className="changed-added">+{changedCount(added)}</span>
      <span className="changed-deleted">-{changedCount(removed)}</span>
    </span>
  );
}

function EditedGlyph() {
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true">
      <path d="M3.2 2.6h6.1L12.8 6v7.4H3.2z" />
      <path d="M9.1 2.8V6h3.4" />
      <path d="M5.2 8.6h5.2M5.2 10.8h3.2" />
    </svg>
  );
}

function UndoGlyph() {
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true">
      <path d="M3.4 6.4h4.6a3.3 3.3 0 1 1 0 6.6H6.2" />
      <path d="M5.6 3.8 3.2 6.4l2.4 2.6" />
    </svg>
  );
}

const PREVIEW_DELAY_MS = 500;
const PREVIEW_FADE_MS = 100;
const PREVIEW_GRACE_MS = 200;
const PREVIEW_MAX_HEIGHT = 420;
const PREVIEW_INSET = 24;
const PREVIEW_GAP = 8;
const PREVIEW_MARGIN = 8;

function pointerCanHover(event: React.PointerEvent) {
  if (event.pointerType === 'touch') return false;
  if (event.pointerType === 'mouse' && !window.matchMedia('(any-hover: hover)').matches) return false;
  return true;
}

function ChangedFilePreview({ file }: { file: ChangedFile }) {
  const parsed = parseDiff(file.diff || '')[0];
  const lines = (parsed?.lines || []).filter((line) => line.kind === 'add' || line.kind === 'del' || line.kind === 'ctx').slice(0, 240);
  return (
    <div className="changed-hover" data-changes-hover-preview>
      <div className="changed-hover-path" data-changes-preview-path>{file.path}</div>
      {lines.length === 0
        ? <p className="changed-hover-empty" data-diff-note="empty">{file.note || '两边的内容一样'}</p>
        : (
          <div className="changed-hover-body" data-review-view="unified">
            {lines.map((line, index) => (
              <div key={index} className={`changed-hover-line is-${line.kind}`} data-diff-line={line.kind}>
                <span className="changed-hover-no">{line.old === '' ? '' : line.old}</span>
                <span className="changed-hover-no">{line.next === '' ? '' : line.next}</span>
                <span className="changed-hover-sign">{line.kind === 'add' ? '+' : line.kind === 'del' ? '-' : ''}</span>
                <span className="changed-hover-text">{line.text}</span>
              </div>
            ))}
          </div>
        )}
    </div>
  );
}

function ChangedFilesCard({ files, canRollback, onOpen, onRollback }: {
  files: ResultRow['changedFiles'];
  canRollback: boolean;
  onOpen: (file: ChangedFile) => void;
  onRollback: () => void;
}) {
  const cardRef = useRef<HTMLElement>(null);
  const anchorRef = useRef<HTMLElement | null>(null);
  const popRef = useRef<HTMLDivElement>(null);
  const openTimer = useRef<number | null>(null);
  const closeTimer = useRef<number | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [preview, setPreview] = useState<number | null>(null);
  const [closing, setClosing] = useState(false);
  const [place, setPlace] = useState<{ left: number; top: number; width: number; maxHeight: number } | null>(null);
  const added = files.reduce((sum, file) => sum + Number(file.added || 0), 0);
  const removed = files.reduce((sum, file) => sum + Number(file.removed || 0), 0);
  const foldable = files.length > CHANGED_COLLAPSED_ROWS;
  const rows = foldable && !expanded ? files.slice(0, CHANGED_COLLAPSED_ROWS) : files;
  const hiddenCount = files.length - rows.length;

  const clearOpen = () => {
    if (openTimer.current != null) window.clearTimeout(openTimer.current);
    openTimer.current = null;
  };
  const clearClose = () => {
    if (closeTimer.current != null) window.clearTimeout(closeTimer.current);
    closeTimer.current = null;
  };
  const dismissPreview = useCallback((immediate = false) => {
    clearOpen();
    clearClose();
    if (immediate) {
      setClosing(false);
      setPreview(null);
      return;
    }
    setClosing(true);
    closeTimer.current = window.setTimeout(() => {
      closeTimer.current = null;
      setClosing(false);
      setPreview(null);
    }, PREVIEW_FADE_MS);
  }, []);
  const armClose = () => {
    clearOpen();
    if (preview == null) return;
    clearClose();
    closeTimer.current = window.setTimeout(() => {
      closeTimer.current = null;
      dismissPreview();
    }, PREVIEW_GRACE_MS);
  };
  const schedulePreview = (index: number, anchor: HTMLElement, event: React.PointerEvent) => {
    if (!pointerCanHover(event)) return;
    clearClose();
    anchorRef.current = anchor;
    if (preview === index) {
      setClosing(false);
      return;
    }
    clearOpen();
    openTimer.current = window.setTimeout(() => {
      openTimer.current = null;
      setClosing(false);
      setPreview(index);
    }, PREVIEW_DELAY_MS);
  };
  useEffect(() => () => { clearOpen(); clearClose(); }, []);
  useEffect(() => {
    if (preview == null) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') dismissPreview();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [preview, dismissPreview]);
  useLayoutEffect(() => {
    if (preview == null) {
      setPlace(null);
      return undefined;
    }
    const measure = () => {
      const anchor = anchorRef.current;
      const card = cardRef.current;
      if (!anchor || !card) return;
      const row = anchor.getBoundingClientRect();
      const bounds = card.getBoundingClientRect();
      const height = popRef.current?.offsetHeight ?? 0;
      const width = Math.max(0, Math.min(bounds.width - PREVIEW_INSET * 2, window.innerWidth - PREVIEW_MARGIN * 2));
      const belowTop = Math.max(PREVIEW_MARGIN, row.bottom + PREVIEW_GAP);
      const above = Math.max(0, row.top - PREVIEW_GAP - PREVIEW_MARGIN);
      const below = Math.max(0, window.innerHeight - belowTop - PREVIEW_MARGIN);
      const onTop = above >= Math.min(PREVIEW_MAX_HEIGHT, below);
      const maxHeight = Math.min(PREVIEW_MAX_HEIGHT, onTop ? above : below);
      const shown = Math.min(height || maxHeight, maxHeight);
      setPlace({
        left: Math.max(PREVIEW_MARGIN, Math.min(bounds.left + PREVIEW_INSET, window.innerWidth - width - PREVIEW_MARGIN)),
        top: onTop ? Math.max(PREVIEW_MARGIN, row.top - shown - PREVIEW_GAP) : belowTop,
        width,
        maxHeight
      });
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    if (popRef.current) observer?.observe(popRef.current);
    if (cardRef.current) observer?.observe(cardRef.current);
    window.addEventListener('scroll', measure, true);
    window.addEventListener('resize', measure);
    return () => {
      observer?.disconnect();
      window.removeEventListener('scroll', measure, true);
      window.removeEventListener('resize', measure);
    };
  }, [preview]);

  return (
    <section ref={cardRef} className="changed-card turn-card-files" data-changed-files>
      <div className="changed-bar">
        <span className="changed-tile"><EditedGlyph /></span>
        <span className="changed-titles">
          <span className="changed-title">{`已编辑 ${files.length} 个文件`}</span>
          <ChangedCounts added={added} removed={removed} />
        </span>
        <span className="changed-actions">
          {canRollback && (
            <button type="button" className="changed-undo" title="撤回这一轮写进工作区的文件" onClick={onRollback}>
              撤销
              <UndoGlyph />
            </button>
          )}
          <button
            type="button"
            className="changed-review"
            onClick={() => { dismissPreview(true); onOpen(files[0]); }}
          >
            查看变更
          </button>
        </span>
      </div>
      <ul className="changed-list">
        {rows.map((file, index) => (
          <li key={file.path}>
            <button
              type="button"
              className="changed-row"
              aria-label={`查看 ${file.path} 的改动`}
              onClick={() => { dismissPreview(true); onOpen(file); }}
              onPointerEnter={(event) => schedulePreview(index, event.currentTarget, event)}
              onPointerLeave={() => armClose()}
            >
              <span className="changed-file-label">
                <ProcessKindIcon kind={extractFileMeta(file.path).kind} />
                <span className="changed-path">{file.path}</span>
              </span>
              <ChangedCounts added={Number(file.added || 0)} removed={Number(file.removed || 0)} />
            </button>
          </li>
        ))}
      </ul>
      {foldable && (
        <button
          type="button"
          className="changed-toggle"
          aria-expanded={expanded}
          aria-label={expanded ? '收起改动文件列表' : `再显示 ${hiddenCount} 个改动文件`}
          onClick={() => setExpanded((value) => !value)}
        >
          <span>{expanded ? '收起' : `再显示 ${hiddenCount} 个文件`}</span>
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d={expanded ? 'M4 10l4-4 4 4' : 'M4 6l4 4 4-4'} /></svg>
        </button>
      )}
      {preview != null && files[preview] && place && createPortal(
        <div
          ref={popRef}
          className="changed-hover-card"
          data-closing={closing || undefined}
          style={{ left: place.left, top: place.top, width: place.width, maxHeight: place.maxHeight }}
          onPointerEnter={() => { clearClose(); setClosing(false); }}
          onPointerLeave={() => armClose()}
        >
          <ChangedFilePreview file={files[preview]} />
        </div>,
        document.body
      )}
    </section>
  );
}

function ResultCard({ row, duplicate, hasPrecedingReply = false }: { row: ResultRow; duplicate: boolean; hasPrecedingReply?: boolean }) {
  const rollbackRun = useStore((state) => state.rollbackRun);
  const files = row.changedFiles || [];
  const settled = row.tone === 'ok' || row.tone === 'text';
  // 失败时结论收成一行，改动卡不跟着失败原因换样子。
  const compact = !row.detail || duplicate || row.continued || row.resumable || (!settled && files.length > 0);
  // 纯文本问答已经有回复正文时，不再重复一行「执行结束」。改过的文件仍然单独留下。
  const hideStatus = compact && hasPrecedingReply && (row.tone === 'ok' || row.tone === 'text') && !row.continued && !row.resumable;
  if (hideStatus && !files.length) return null;
  const filesCard = files.length > 0 && (
    <ChangedFilesCard
      files={files}
      canRollback={!row.continued}
      onRollback={() => {
        const runId = row.key.startsWith('result:run_') ? row.key.slice('result:'.length) : row.runIds.at(-1);
        if (runId) rollbackRun(runId);
      }}
      onOpen={(file) => {
        const state = useStore.getState();
        const runId = row.runIds.at(-1);
        if (runId) state.setUi({ inspectorOpen: true, inspectorTab: 'changes', reviewRunId: runId, changeView: file.path });
        else state.openPreview(file.path);
      }}
    />
  );
  if (hideStatus) return filesCard;
  return (
    <>
      <article className={`turn-card is-${row.tone}${compact ? ' is-compact' : ''}${row.continued ? ' is-continued' : ''}`}>
        <div className="turn-card-head">
          <span className="turn-card-title">{row.title}</span>
          {row.continued && <span className="turn-resolved">已继续执行</span>}
        </div>
        {row.detail && !duplicate && (
          row.continued || row.resumable || (!settled && files.length > 0)
            ? (
              <details className="turn-card-more">
                <summary>
                  <span>{row.continued ? '查看错误详情' : '查看运行详情'}</span>
                  <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
                </summary>
                <div className="turn-card-detail"><MarkdownBody text={row.detail} /></div>
              </details>
            )
            : String(row.detail).length > 280
            ? (
              <details className="turn-card-more">
                <summary>
                  <span>查看结论</span>
                  <span className="process-chevron" aria-hidden="true"><ProcessChevron /></span>
                </summary>
                <div className="turn-card-detail"><MarkdownBody text={row.detail} /></div>
              </details>
            )
            : <div className="turn-card-detail"><MarkdownBody text={row.detail} /></div>
        )}
      </article>
      {filesCard}
    </>
  );
}

function SandboxCard({ row }: { row: SandboxRow }) {
  const [decision, setDecision] = useState('');
  const [busy, setBusy] = useState<string | false>(false);
  /** 产物清单与校验结论：让「接受」不是盲批。 */
  const [report, setReport] = useState<ApiResults['sandboxManifest'] | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [loadError, setLoadError] = useState('');
  const sessionId = useStore((state) => state.sessionId);

  useEffect(() => {
    setReport(null);
    setLoadError('');
    setDecision('');
    if (!row.sandboxId || !sessionId) {
      return undefined;
    }
    let alive = true;
    api.sandboxManifest(sessionId, row.sandboxId)
      .then((data) => { if (alive && data) { setReport(data); setDecision(({accepted:'已接受',rejected:'已拒绝',closed:'已处理',empty:'没有待处理变更'} as Record<string, string>)[data.disposition] || ''); } })
      .catch((error) => { if (alive) setLoadError(errorInfo(error).message || '读取候选版本失败'); });
    return () => { alive = false; };
  }, [row.sandboxId, sessionId, refresh]);

  const decide = async (action: string) => {
    setBusy(action);
    try {
      const ok = await useStore.getState().decideSandbox(row.sandboxId, action, report?.reviewHash);
      if (useStore.getState().sessionId !== sessionId) return;
      if (ok && action !== 'recover') setDecision(action === 'accept' ? '已接受' : '已拒绝');
      else setRefresh((value) => value + 1);
    } finally { setBusy(false); }
  };

  const verification = report?.verification || null;
  const verified = Boolean(verification?.current) && String(verification?.status).toLowerCase() === 'passed';
  const files = report?.manifest?.files?.length ?? 0;

  return (
    <aside className="sandbox-card">
      <header className="sandbox-head">{row.title}</header>
      <pre className="sandbox-body">{row.detail}</pre>
      {report && (
        <p className={`sandbox-verify${verified ? ' is-ok' : ' is-warn'}`}>
          {verified ? '校验通过' : (verification ? `校验：${({passed:'通过',failed:'失败',skipped:'未执行',missing:'尚未校验'} as Record<string, string>)[String(verification.status).toLowerCase()] || '状态未知'}${verification.reason ? ` · ${verification.reason}` : ''}` : '尚未校验')}
          {` · 产物 ${files} 个文件`}
          {(report.dependencies || []).length > 0 && ` · 依赖 ${report.dependencies.length} 个`}
        </p>
      )}
      {loadError && <p role="alert">{loadError} <Button size="sm" variant="ghost" label="重新加载" onClick={() => setRefresh(value => value + 1)} /></p>}
      {report?.recovery && <p role="status" className="sandbox-verify is-warn">
        上次变更写入中断，需先恢复。恢复遇到外部修改时会停止并保留备份。
        {report.recovery.sessionId === sessionId && report.recovery.sandboxId === row.sandboxId
          ? <Button size="sm" label="恢复写入状态" isLoading={busy === 'recover'} isDisabled={Boolean(busy)} onClick={() => decide('recover')} />
          : `请先处理 ${report.recovery.sandboxId} 的恢复。`}
      </p>}
      {row.sandboxId && (
        <div className="sandbox-actions">
          <span className="sandbox-label mono">{row.sandboxId}</span>
          <Button size="sm" label={busy === 'accept' ? '校验并写入中…' : '接受变更'} isLoading={busy === 'accept'}
            isDisabled={Boolean(busy) || Boolean(decision) || !report?.reviewHash || Boolean(report?.recovery)} onClick={() => decide('accept')} />
          <Button size="sm" variant="ghost" label="拒绝"
            isDisabled={Boolean(busy) || Boolean(decision) || !report?.reviewHash || Boolean(report?.recovery)} onClick={() => decide('reject')} />
          {decision && <span className="sandbox-decision">{decision}</span>}
        </div>
      )}
    </aside>
  );
}

function ArbitrationCard({ row }: { row: ArbitrationRow }) {
  const resolve = useStore((state) => state.resolveArbitration);
  return (
    <aside className={`arb-card${row.resolved ? ' is-resolved' : ''}`} data-arb-id={row.arbitrationId}>
      <header className="arb-head">{row.title}{row.resolved ? ' · 已裁决' : ''}</header>
      <p className="arb-summary">{row.detail}</p>
      {!row.resolved && (
        <div className="arb-options">
          {(row.options || []).map((option) => (
            <Button
              key={typeof option === 'string' ? option : option.id}
              label={typeof option === 'string' ? option : option.label}
              size="sm"
              onClick={() => resolve(row.arbitrationId, typeof option === 'string' ? option : option.id)}
            />
          ))}
        </div>
      )}
    </aside>
  );
}

export { formatTime };
