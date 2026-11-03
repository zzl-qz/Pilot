import { modifierKey, duplicateResult } from '../lib/interaction.js';
import React, { memo, useCallback, useEffect, useRef, useState } from 'react';
import { useStickToBottom } from 'use-stick-to-bottom';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { historyPrefetchDistance } from '../lib/history.js';
import { MarkdownBody } from './MarkdownBody.jsx';
import { TOOL_ICONS, TOOL_STATUS_TITLES, toolArgsText, toolLabel } from '../lib/tool-meta.js';
import { agentMeta, agentTitle, manifestFacts, splitSubagentResult } from '../lib/agents.js';
import { splitAttachmentMarkers, layoutMessageParts, attachmentUrl, isImageAttachment, formatBytes } from '../lib/attachments.js';
import { changeFileTitle, splitPath, statusKind } from '../lib/changes.js';
import { writeClipboard } from '../lib/clipboard.js';
import { formatCount, formatTime } from '../thread-utils.js';
import { messagePeek } from '../lib/side-chat.js';

/* 回到底部的弹簧参数：库的默认值在长距离上要 1s 以上，这里压到 ~200ms 收敛。 */
const BACK_TO_LATEST = { stiffness: 0.2, damping: 0.9, mass: 1 };

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
  const bottomSignal = useStore((state) => state.ui.bottomSignal);
  const requestEarlierHistory = useStore((state) => state.requestEarlierHistory);
  const requestLaterHistory = useStore((state) => state.requestLaterHistory);
  const prefetchEarlier = useStore((state) => state.prefetchEarlier);
  const applyEarlierPage = useStore((state) => state.applyEarlierPage);

  /*
   * initial / resize 都用 'instant'：首屏直接落在最新消息；流式输出时底部内容持续长高，
   * 每帧瞬时贴底。用弹簧动画追赶会稳定落后一两百像素，还会让「回到底部」按钮反复闪烁。
   */
  const { scrollRef, contentRef, scrollToBottom, isAtBottom } = useStickToBottom({
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
  const earlierTimer = useRef(null);
  const scheduleEarlier = useCallback(() => {
    clearTimeout(earlierTimer.current);
    earlierTimer.current = setTimeout(() => requestEarlierHistory(), 140);
  }, [requestEarlierHistory]);
  useEffect(() => () => clearTimeout(earlierTimer.current), []);

  const handleScroll = useCallback((event) => {
    const el = event.currentTarget;
    const threshold = historyPrefetchDistance(el.clientHeight);
    const toTop = el.scrollTop;
    const toBottom = Math.max(0, el.scrollHeight - el.scrollTop - el.clientHeight);
    const goingUp = toTop < lastScrollTop.current;
    const goingDown = toTop > lastScrollTop.current;
    lastScrollTop.current = toTop;
    if (toTop > threshold) {
      // 退出去才重新武装：一次进入只预取一次，避开「插入 → 仍在区内 → 再预取」的连锁
      armed.current = false;
    }
    if (goingUp && toTop <= threshold) {
      if (!armed.current) {
        armed.current = true;
        prefetchEarlier();
      }
      scheduleEarlier();
    }
    if (goingDown && toBottom <= threshold) {
      requestLaterHistory();
    }
  }, [prefetchEarlier, requestLaterHistory, scheduleEarlier]);

  /* 已经贴在顶部时继续上滑不会再产生 scroll 事件，靠 wheel 兜住「还想看更早」的意图。 */
  const handleWheel = useCallback((event) => {
    if (event.deltaY >= 0 || event.currentTarget.scrollTop > 0) {
      return;
    }
    // 顶到头还在推：缓冲里有就当场插进去，不用再等那 140ms 的空闲计时
    if (!applyEarlierPage()) {
      scheduleEarlier();
    }
  }, [applyEarlierPage, scheduleEarlier]);

  /* 内容不足一屏时不会产生滚动事件，主动补更早的一页，否则历史永远加载不出来。 */
  useEffect(() => {
    const el = scrollRef.current;
    if (!el || !history.hasMore || history.loading) {
      return;
    }
    if (el.scrollHeight - el.clientHeight < 8) {
      requestEarlierHistory();
    }
  }, [rows.length, history.hasMore, history.loading, requestEarlierHistory, scrollRef]);

  /* 发送消息 / 切换会话后主动贴底：此刻用户要看的是刚发出的内容。 */
  const lastSignal = useRef(bottomSignal);
  useEffect(() => {
    if (bottomSignal === lastSignal.current) {
      return;
    }
    lastSignal.current = bottomSignal;
    scrollToBottom({ animation: BACK_TO_LATEST });
  }, [bottomSignal, scrollToBottom]);

  if (!rows.length) {
    return <EmptyState />;
  }

  return (
    <div className="thread-scroll-region">
      <div className="thread" ref={scrollRef} onScroll={handleScroll} onWheel={handleWheel}>
        <div className="thread-column" ref={contentRef}>
          {history.hasMore && history.error && (
            <RetryRow label="加载更早失败，点击重试" onClick={() => requestEarlierHistory({ force: true })} />
          )}
          {rows.map((row, index) => (
            <RowView key={row.key} row={row} duplicate={row.type === 'result' && duplicateResult(rows, index)} isLast={!rows.slice(index + 1).some(item => item.type === 'turn' || item.type === 'user')} />
          ))}
          {history.hasNewer && history.forwardError && (
            <RetryRow label="加载更新失败，点击重试" onClick={() => requestLaterHistory({ force: true })} />
          )}
        </div>
      </div>
      <ThreadBottomButton visible={!isAtBottom} onJump={() => scrollToBottom({ animation: BACK_TO_LATEST })} />
    </div>
  );
}

function ThreadBottomButton({ visible, onJump }) {
  if (!visible) {
    return null;
  }
  return (
    <button
      type="button"
      className="thread-bottom"
      aria-label="回到底部"
      title="回到底部"
      onClick={onJump}
    >
      <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3.5v12M5.5 11l4.5 4.5 4.5-4.5" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" /></svg>
    </button>
  );
}

function RetryRow({ label, onClick }) {
  return (
    <div className="history-more-wrap">
      <button type="button" className="history-more" onClick={onClick}>{label}</button>
    </div>
  );
}

function EmptyState() {
  const workspace = useStore(state => state.workspace || state.draftSession?.workspace || '');
  const project = useStore(state => state.projects.find(item => item.id === state.currentProjectId));
  const [scope, setScope] = useState(null);
  useEffect(() => {
    let alive = true; setScope(null);
    if (workspace) api.worktreeInfo(workspace).then(info => { if (alive) setScope(info); }).catch(() => {});
    return () => { alive = false; };
  }, [workspace]);
  const prompts = [
    { title: '了解项目', sub: '识别技术栈、入口与运行方式', prompt: '分析当前工作区的结构、技术栈和启动方式，先读取项目实际配置和说明。' },
    { title: '检查改动', sub: '检查工作区改动中的潜在问题', prompt: '审阅当前工作区的未提交改动，重点检查逻辑错误、边界情况和缺失测试。' },
    { title: '完善测试', sub: '找到测试入口和未覆盖的边界', prompt: '查看项目现有测试，找到关键流程缺少的边界用例，补充测试并实际运行。' }
  ];
  return (
    <div className="thread-scroll-region">
      <div className="thread is-empty">
        <div className="thread-column">
          <div className="empty" id="empty-state">
            <span className="empty-logo" aria-hidden="true">P</span>
            <h2 className="empty-title">从一个任务开始</h2>
            <p className="empty-sub">描述你想完成的工作。Pilot 会在所选工作区内执行，并把每一步和改动留给你审阅。</p>
            <div className="task-scope">
              <strong>{project?.name || '当前工作区'}</strong>
              <span title={workspace}>{workspace || '尚未选择工作区'}</span>
              <span>{scope?.branch ? `${scope.worktree ? '源分支' : 'Git 分支'} · ${scope.branch}` : 'Git 分支 · 未获取'} · {scope?.worktree ? '独立 worktree' : '在所选目录执行'}</span>
              <button type="button" className="btn" onClick={() => useStore.getState().setUi({ picker: { title: '选择任务工作区' } })}>选择工作区</button>
            </div>
            <div className="prompts">
              {prompts.map((item) => (
                <button
                  key={item.title}
                  type="button"
                  onClick={() => {
                    // 草稿态直接填进输入框就够了，真正建会话发生在发送那一刻
                    const state = useStore.getState();
                    if (!state.sessionId && !state.draftSession) {
                      state.startNewSession();
                    }
                    state.composerApi?.appendText((state.composerApi.getText().trim() ? '\n' : '') + item.prompt);
                    state.composerApi?.focus();
                  }}
                >
                  <span className="prompt-title">{item.title}</span>
                  <span className="prompt-sub">{item.sub}</span>
                </button>
              ))}
            </div>
            <p className="empty-keys">
              <kbd>{modifierKey}</kbd><kbd>N</kbd> 新任务
              <span className="sep">·</span>
              <kbd>@</kbd> 引用文件
              <span className="sep">·</span>
              <kbd>/</kbd> 快捷指令
              <span className="sep">·</span>
              <kbd>Esc</kbd> 停止
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

const RowView = memo(function RowView({ row, isLast, duplicate }) {
  switch (row.type) {
    case 'user': return <UserMessage row={row} />;
    case 'turn': return <Turn row={row} isLast={isLast} />;
    case 'plan': return <PlanCard row={row} />;
    case 'compaction': return <CompactionCard row={row} />;
    case 'result': return <ResultCard row={row} duplicate={duplicate} />;
    case 'sandbox': return <SandboxCard row={row} />;
    case 'arb': return <ArbitrationCard row={row} />;
    default: return null;
  }
});

/* ---------- 用户消息 ---------- */

function UserMessage({ row }) {
  const text = row.text || '';
  const [open, setOpen] = useState(false);
  const parts = splitAttachmentMarkers(text);
  const peek = messagePeek(parts.body || text);
  const attachments = row.attachments || [];
  const images = attachments.filter(isImageAttachment);
  const files = [...(parts.files || []), ...attachments.filter(item => !isImageAttachment(item))];
  const canPeek = peek.collapsed && !images.length && !files.length;
  /*
   * 操作只放图标：文字按钮会在气泡下方挤成一块黑板（旧版就是图标 + data-tip 气泡）。
   * SVG 路径与旧版 ACTION_ICONS 的 edit / branch 保持一致。
   */
  const actions = row.steered ? null : (
    <div className="msg-actions">
      <button
        type="button"
        className="icon-action msg-edit-btn"
        aria-label="编辑并重新发送"
        data-tip="编辑"
        title="编辑并重新发送"
        onClick={() => beginEdit(row)}
      >
        <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M10.8 2.7l2.5 2.5-7.6 7.6-3.2.7.7-3.2z" />
          <path d="M9.4 4.1l2.5 2.5" />
        </svg>
      </button>
      {row.sequence != null && (
        <button
          type="button"
          className="icon-action msg-edit-btn"
          aria-label="回退或另开分支"
          data-tip="分支"
          title="回退或另开分支"
          onClick={() => useStore.getState().openCheckpoint(row.sequence)}
        >
          <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="4.5" cy="3.6" r="1.7" />
            <circle cx="4.5" cy="12.4" r="1.7" />
            <circle cx="11.5" cy="5.6" r="1.7" />
            <path d="M4.5 5.3v5.4M11.5 7.3c0 2.5-2.3 3.4-6 3.9" />
          </svg>
        </button>
      )}
    </div>
  );
  if (canPeek && !open) {
    return (
      <article className="msg msg-user" data-sequence={row.sequence ?? undefined}>
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
    <article className="msg msg-user" data-sequence={row.sequence ?? undefined}>
      <div className="bubble">
        <div className="bubble-flow">{renderMessageBody(parts.body, files, images, row.imagePositions)}</div>
      </div>
      {canPeek && (
        <button type="button" className="msg-peek-fold" onClick={() => setOpen(false)}>收起</button>
      )}
      {actions}
    </article>
  );
}

function beginEdit(row) {
  if (row.attachments?.length) {
    useStore.getState().showNotice('带附件的历史消息请作为新消息补充要求，原附件仍保留在对话中。');
    return;
  }
  const composerApi = useStore.getState().composerApi;
  const plain = row.text || '';
  if (composerApi?.getText().trim() || composerApi?.attachmentIds().length) {
    useStore.getState().showNotice('输入框还有未发送的草稿，请先处理草稿再编辑历史消息。');
    composerApi?.focus(); return;
  }
  composerApi?.setText(plain);
  for (const file of row.attachments || []) {
    composerApi?.insertAttachments([{ id: file.id || file.name, name: file.name, kind: isImageAttachment(file) ? 'image' : 'file', url: attachmentUrl(useStore.getState().sessionId, file) }]);
  }
  composerApi?.focus();
  useStore.setState((draft) => { draft._editTarget = row.sequence ?? null; });
}

/** 消息正文：文本 + 图片缩略 + 文件卡（与旧版 layoutMessageParts 顺序一致）。 */
function renderMessageBody(body, files = [], images = [], imagePositions = []) {
  return layoutMessageParts(body, images, files, imagePositions).map((part, index) => {
    if (part.type === 'text') return <span key={index}>{part.text}</span>;
    if (part.type === 'image') return <ImageThumb key={index} image={part.image} />;
    return <React.Fragment key={index}>{part.files.map((file, fileIndex) => <FileCard key={fileIndex} file={file} />)}</React.Fragment>;
  });
}

function ImageThumb({ image }) {
  const sessionId = useStore((state) => state.sessionId);
  return (
    <span className="bubble-image">
      <img src={attachmentUrl(sessionId, image)} alt={image.name || '图片'} loading="lazy" />
    </span>
  );
}

function FileCard({ file }) {
  const sessionId = useStore((state) => state.sessionId);
  const url = attachmentUrl(sessionId, file);
  if (!url) return <span className="file-card" title={file.name}><span className="file-card-name">{file.name || file.id}</span></span>;
  return (
    <a className="file-card" href={url} target="_blank" rel="noreferrer">
      <span className="file-card-name">{file.name || file.id}</span>
      <span className="file-card-size">{formatBytes(file.size)}</span>
    </a>
  );
}

/* ---------- 回合（操作组 + 思考 + 回复 + 统计） ---------- */

const Turn = memo(function Turn({ row, isLast }) {
  // 正文 = 已落库的 delta + 还在路上的 preview；两者不能都拼进 text（会重复）
  const replyText = `${row.reply.text || ''}${row.reply.preview || ''}`;
  const running = useStore((state) => state.running);
  /*
   * 「重新生成」走 /regenerate：后端用上一条用户输入重跑，历史里不会多一条重复的用户消息。
   * 只在最后一轮、且没有任务在跑时给入口，避免对中间轮次重放出错乱的历史。
   */
  const canRegenerate = isLast && !running && !row.reply.live && Boolean(replyText);
  const toolsRunning = row.tools.some((tool) => tool.status === 'run');
  const thinkingLive = Boolean(row.thinking?.live);
  // 还在跑或还在想：用 Cursor / Codex 那一块（命令状态 + 思考卡片），收起后再回到折叠摘要。
  const liveWork = toolsRunning || thinkingLive;
  return (
    <div className="turn-shell" data-run={row.runIds?.join(',') || undefined}>
      {liveWork
        ? <LiveWork tools={row.tools} thinking={row.thinking} />
        : (
          <>
            {row.tools.length > 0 && <ToolGroup tools={row.tools} />}
            {row.thinking && <ThinkingBlock thinking={row.thinking} />}
          </>
        )}
      {Object.values(row.agents || {}).map((agent) => <AgentCard key={agent.id} agent={agent} />)}
      {(replyText || row.reply.live) && (
        <article className={`msg msg-assistant${row.reply.live ? ' is-streaming' : ''}`}>
          <div className="who"><span className="who-mark">P</span>Pilot</div>
          <div className="bubble">
            {replyText
              ? <MarkdownBody text={replyText} />
              : <div className="bubble typing"><span /><span /><span /></div>}
          </div>
          {replyText && !row.reply.live && (
            <div className="msg-actions reply-actions">
              <CopyControl text={replyText} label="复制这条回复" tip="复制" />
              {canRegenerate && (
                <button
                  type="button"
                  className="icon-action"
                  aria-label="重新生成"
                  data-tip="重新生成"
                  title="用上一条输入重新生成（不会重复用户消息）"
                  onClick={() => useStore.getState().regenerate()}
                >
                  <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M13.2 8a5.2 5.2 0 1 1-1.7-3.9" />
                    <path d="M13.4 2.6v3.2h-3.2" />
                  </svg>
                </button>
              )}
            </div>
          )}
        </article>
      )}
      <TurnStats stats={row.stats} />
    </div>
  );
});

/**
 * 操作组单独 memo：流式输出时只有 reply 在变，tools 数组引用不变，这一整块（可能几十行）
 * 直接跳过比对。旧实现靠命令式只改几个节点，React 这边靠这个切分拿到同样的代价。
 */
const ToolGroup = memo(function ToolGroup({ tools }) {
  const running = tools.filter((tool) => tool.status === 'run').length;
  const needsAttention = tools.some(tool => ['error', 'wait', 'interrupted'].includes(tool.status));
  const ref = useRef(null);
  // 只在「是否还在跑」变化时改 open，别把用户手动折叠的状态每帧冲掉
  useEffect(() => {
    if (ref.current) {
      ref.current.open = running > 0 || needsAttention;
    }
  }, [running > 0, needsAttention]);
  return (
    <details className="tool-group" ref={ref}>
      <summary className="tool-group-head">
        <span className="tool-group-chevron" aria-hidden="true" />
        <span className="tool-group-label">{running > 0 ? `正在执行 ${tools.length} 项` : `${tools.length} 项操作`}</span>
        <span className="tool-group-count">{toolCounts(tools)}</span>
        <span className="run-strip" aria-hidden="true">
          {tools.slice(-40).map((tool, index) => (
            <span key={tool.callId || index} className={`strip-dot is-${tool.status}`} />
          ))}
        </span>
      </summary>
      <div className="tool-group-list">
        {tools.map((tool) => <ToolRow key={tool.callId} tool={tool} />)}
      </div>
    </details>
  );
});

/** 「计划 1 · 命令 3 · 读取 2」：按首次出现顺序，带次数（旧版 refreshToolGroup 的文案）。 */
function toolCounts(tools) {
  const order = [];
  const counts = new Map();
  for (const tool of tools) {
    const label = toolLabel(tool.name);
    if (!counts.has(label)) {
      counts.set(label, 0);
      order.push(label);
    }
    counts.set(label, counts.get(label) + 1);
  }
  return order.map((label) => `${label} ${counts.get(label)}`).join(' · ');
}

/**
 * 单条操作。展开状态放组件内：每帧被父级重渲染时不会把用户折叠的动作顶回去。
 *
 * <p>跑得久的工具（大目录 grep、npm test）每秒刷一次「执行中 Ns」——光一个不动的
 * 转圈会被当成卡死，用户只能干等。
 */
const ToolRow = memo(function ToolRow({ tool }) {
  const body = String(tool.body || '').trim();
  const [open, setOpen] = useState(['error', 'wait', 'interrupted'].includes(tool.status));
  useEffect(() => { if (['error', 'wait', 'interrupted'].includes(tool.status)) setOpen(true); }, [tool.status]);
  const running = tool.status === 'run';
  useSecondTick(running);
  const seconds = running ? elapsedSeconds(tool.startedAt) : 0;
  const meta = running && seconds >= 3
    ? `执行中 ${seconds}s`
    : (tool.meta === '未返回结果' ? '' : (tool.meta || ''));
  const args = toolArgsText(tool.name, tool.args);
  const title = tool.name === 'run_command' ? '' : (toolLabel(tool.name));
  return (
    <div className={`tool-row${open && body ? ' is-open' : ''}`}>
      <button type="button" onClick={() => body && setOpen((value) => !value)} aria-expanded={open && Boolean(body)}>
        <span className={`tool-status is-${tool.status}`} title={TOOL_STATUS_TITLES[tool.status] || ''}>
          {running ? '…' : tool.status === 'ok' ? <StatusMark /> : tool.status === 'error' ? <StatusMark failed /> : '!'}
        </span>
        <span className="tool-icon">{TOOL_ICONS[tool.name] || '·'}</span>
        {title && <span className="tool-name">{title}</span>}
        <span className="tool-args">{args}</span>
        {meta && <span className={`tool-meta${running && seconds >= 10 ? ' is-slow' : ''}`}>{meta}</span>}
      </button>
      {body && <div className="tool-body"><pre>{body}</pre></div>}
    </div>
  );
});

/**
 * 进行中的回合：和 Cursor、Codex 一样，命令收成一行，思考原文放在卡片里。
 * 点「正在执行」能展开每条命令；这一轮结束后改回折叠摘要。
 */
const LiveWork = memo(function LiveWork({ tools, thinking }) {
  const runningTools = tools.filter((tool) => tool.status === 'run');
  const live = Boolean(thinking?.live);
  const cardRef = useRef(null);
  useSecondTick(live || runningTools.length > 0);
  useEffect(() => {
    const card = cardRef.current;
    if (card && live) {
      card.scrollTop = card.scrollHeight;
    }
  }, [thinking?.text, live]);
  const commandsOnly = runningTools.length > 0 && runningTools.every((tool) => tool.name === 'run_command');
  const status = runningTools.length > 0
    ? (commandsOnly ? `正在执行 ${runningTools.length} 条命令` : `正在执行 ${runningTools.length} 项`)
    : '';
  return (
    <div className="live-work">
      <div className="live-work-rail" aria-hidden="true" />
      <div className="live-work-main">
        {tools.length > 0 && (
          <details className="live-work-tools">
            <summary>{status || `${tools.length} 项操作`}</summary>
            <div className="live-work-tool-list">
              {tools.map((tool) => (
                <div key={tool.callId} className={`live-work-tool is-${tool.status}`}>
                  <span className={`live-work-dot is-${tool.status}`} />
                  <span>{toolLabel(tool.name)}</span>
                  <span className="live-work-tool-args">{toolArgsText(tool.name, tool.args)}</span>
                </div>
              ))}
            </div>
          </details>
        )}
        {live && <p className="live-work-label">思考中</p>}
        {live && thinking?.text && (
          <div className="live-work-card" ref={cardRef}>{thinking.text}</div>
        )}
      </div>
    </div>
  );
});

/** 思考块：思考中自动展开并滚到底，正文出来就收起（旧版 settleReasoning）；摘要带秒数和当前动作。 */
const ThinkingBlock = memo(function ThinkingBlock({ thinking }) {
  const ref = useRef(null);
  const bodyRef = useRef(null);
  const live = Boolean(thinking.live);
  useSecondTick(live);
  useEffect(() => {
    if (ref.current) {
      ref.current.open = live;
    }
  }, [live]);
  useEffect(() => {
    const body = bodyRef.current;
    if (body && live) {
      body.scrollTop = body.scrollHeight;
    }
  }, [thinking.text, live]);
  const seconds = elapsedSeconds(thinking.startedAt, thinking.finishedAt);
  const action = thinking.action ? ` · ${thinking.action}` : '';
  return (
    <details className={`think-block${live ? ' is-live' : ''}`} ref={ref}>
      <summary>{live ? `思考中 ${seconds}s${action}` : `已思考 ${seconds}s`}</summary>
      <div className="think-body" ref={bodyRef}>{thinking.text}</div>
    </details>
  );
});

/** 每秒重渲染一次，给计时类文案（思考中 Ns、运行耗时）用。 */
function useSecondTick(active) {
  const [, bump] = useState(0);
  useEffect(() => {
    if (!active) {
      return undefined;
    }
    const timer = setInterval(() => bump((value) => value + 1), 1000);
    return () => clearInterval(timer);
  }, [active]);
}

function elapsedSeconds(startedAt, finishedAt) {
  if (!startedAt) {
    return 0;
  }
  return Math.max(0, Math.round(((finishedAt || Date.now()) - startedAt) / 1000));
}

const AgentCard = memo(function AgentCard({ agent }) {
  useSecondTick(agent.status === 'running');
  // 回传正文末尾的 <subagent_result> 是给父模型合并用的清单，不该原样糊在界面上
  const split = splitSubagentResult(agent.result);
  const facts = manifestFacts(split);
  const tools = (agent.tools || []).length;
  const ms = agent.startedAt ? (agent.finishedAt || Date.now()) - agent.startedAt : null;
  return (
    <details className={`tool-group agent-card${agent.status === 'running' ? ' is-running' : ''}`}>
      <summary className="tool-group-head agent-head">
        <span className="tool-group-chevron" aria-hidden="true" />
        <span className="agent-dot" aria-hidden="true" />
        <span className="tool-group-label agent-name" title={agent.task}>
          {agent.name || agentTitle(agent.task) || agent.id}
        </span>
        <span className="tool-group-count">{agentMeta({ tools, ms, status: agent.status })}</span>
      </summary>
      <div className="agent-body">
        <p className="agent-brief">{agent.task}</p>
        <div className="tool-group-list agent-tools">
          {(agent.tools || []).map((tool, index) => (
            <div key={index} className="tool-row">
              <button type="button">
                <span className={`tool-status is-${tool.status}`}>{tool.status === 'run' ? '…' : tool.status === 'ok' ? <StatusMark /> : <StatusMark failed />}</span>
                <span className="tool-icon">{TOOL_ICONS[tool.name] || '·'}</span>
                <span className="tool-name">{tool.name}</span>
                <span className="tool-args">{tool.args}</span>
              </button>
            </div>
          ))}
        </div>
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
const CopyControl = memo(function CopyControl({ text, label = '复制', tip = '复制' }) {
  const [done, setDone] = useState(false);
  const timer = useRef(null);
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
  return (
    <button
      type="button"
      className={`copy-control icon-action${done ? ' is-done' : ''}`}
      aria-label={label}
      data-tip={done ? '已复制' : tip}
      title={label}
      onClick={copy}
    >
      {done ? (
        <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M3.4 8.6l3.1 3.1 6.1-7" />
        </svg>
      ) : (
        <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <rect x="5.6" y="5.6" width="7.9" height="7.9" rx="2" />
          <path d="M10.4 3.4A1.4 1.4 0 0 0 9 2H4.2A1.7 1.7 0 0 0 2.5 3.7V9c0 .8.6 1.4 1.4 1.4" />
        </svg>
      )}
    </button>
  );
});

const TurnStats = memo(function TurnStats({ stats }) {
  const running = Boolean(stats?.startedAt) && !stats?.finishedAt;
  useSecondTick(running);
  if (!stats) {
    return null;
  }
  const elapsed = stats.startedAt ? elapsedSeconds(stats.startedAt, stats.finishedAt) : null;
  // 没有模型实测用量时是估算值，加 ≈（旧版 formatTokenCount 的 exact 语义）
  const mark = stats.exact === false ? '≈' : '';
  const limit = Number(stats.contextLimit) || 0;
  const used = Number(stats.contextTokens) || 0;
  const crowded = limit > 0 && used / limit >= 0.85;
  const input = Number(stats.inputTokens) || 0;
  const cached = Number(stats.cachedInputTokens) || 0;
  // 估算路径没有缓存观测：显示「—」，不能把「没观测」读成「没命中」
  const cacheLabel = stats.exact === false || input <= 0 ? '—' : `${Math.round((cached / input) * 100)}%`;
  return (
    <details className="turn-stats-detail"><summary>{elapsed != null ? `${elapsed}s · ` : ''}用量详情</summary><div className="turn-stats">
      {elapsed != null && <span className="turn-stat"><span>耗时</span><b>{elapsed}s</b></span>}
      <span className="turn-stat"><span>请求</span><b>{stats.modelRequests ?? '—'}</b></span>
      <span className="turn-stat" title={stats.inputTokens ?? ''}><span>输入</span><b>{mark}{formatCount(stats.inputTokens)}</b></span>
      <span className="turn-stat" title={stats.outputTokens ?? ''}><span>输出</span><b>{mark}{formatCount(stats.outputTokens)}</b></span>
      <span className="turn-stat" title={`命中缓存 ${formatCount(stats.cachedInputTokens)} / 总输入 ${formatCount(stats.inputTokens)}`}>
        <span>缓存命中</span><b>{cacheLabel}</b>
      </span>
      <span className={`turn-stat is-context${crowded ? ' is-hot' : ''}`} title={stats.contextTokens ?? ''}>
        <span>上下文</span><b>{formatCount(stats.contextTokens)}</b>
      </span>
      <span className="turn-stat-note">{stats.exact === false ? '主任务 · Token 为估算' : '主任务 · 模型实测用量'}</span>
    </div></details>
  );
});

/* ---------- 卡片 ---------- */

const PlanCard = memo(function PlanCard({ row }) {
  const items = (row.items || []).filter(item => (item.step || item.text || '').trim());
  if (!items.length) return null;
  const done = items.filter((item) => item.status === 'completed' || item.status === 'done').length;
  const finished = items.length > 0 && done === items.length;
  return (
    <aside className="plan-card">
      <details open={!finished}>
      <summary className="plan-head">
        <span className="plan-title">执行计划</span>
        <span className="plan-progress">{items.length ? `${done}/${items.length}` : ''}</span>
      </summary>
      <ol className="plan-list">
        {items.map((item, index) => (
          <li key={index} className="plan-item">
            <span className="plan-index">{index + 1}</span>
            <span className="plan-text">{item.step || item.text || ''}</span>
            <span className={`plan-status is-${item.status || 'pending'}`}>{planStatusLabel(item.status)}</span>
          </li>
        ))}
      </ol>
      </details>
    </aside>
  );
});

function planStatusLabel(status) {
  const labels = {
    completed: '已完成', done: '已完成',
    in_progress: '进行中', doing: '进行中', running: '进行中',
    failed: '受阻', pending: '等待'
  };
  return labels[status] || '等待';
}

function CompactionCard({ row }) {
  return (
    <details className="ctx-card">
      <summary className="ctx-head">
        {`上下文已压缩 · 折叠 ${row.coveredMessages} 条较早消息 · ${row.charactersBefore} → ${row.charactersAfter}`}
      </summary>
      <pre className="ctx-body">{row.summary}</pre>
    </details>
  );
}

function ResultCard({ row, duplicate }) {
  const rollbackRun = useStore((state) => state.rollbackRun);
  const continueRun = useStore((state) => state.continueRun);
  return (
    <article className={`turn-card is-${row.tone}${(!row.detail || duplicate) && !row.changedFiles?.length && !row.resumable ? ' is-compact' : ''}`}>
      <div className="turn-card-head">
        <span className="turn-card-title">{row.title}</span>
        {row.continued && <span className="turn-resolved">已继续执行</span>}
      </div>
      {row.detail && !duplicate && (
        String(row.detail).length > 280
          ? (
            <details className="turn-card-more">
              <summary>查看结论</summary>
              <div className="turn-card-detail"><MarkdownBody text={row.detail} /></div>
            </details>
          )
          : <div className="turn-card-detail"><MarkdownBody text={row.detail} /></div>
      )}
      {!!(row.changedFiles || []).length && (
        <div className="turn-card-files">
          {row.changedFiles.map((file) => (
            <button
              key={file.path}
              type="button"
              className={`change-file is-${statusKind(file.status)}`}
              onClick={() => useStore.getState().openPreview(file.path)}
              title={changeFileTitle(file)}
            >
              <span className="change-file-path">{splitPath(file.path).name}</span>
              <span className="change-file-status">{file.status}</span>
            </button>
          ))}
        </div>
      )}
      <div className="turn-actions">
        {row.resumable && !row.continued && !useStore.getState().running && (
          <button type="button" className="btn is-continue" onClick={() => continueRun()}>继续执行</button>
        )}
        {!row.continued && !!(row.changedFiles || []).length && (
          <button type="button" className="btn" onClick={() => rollbackRun(row.runIds?.[0])}>回滚本次改动</button>
        )}
      </div>
    </article>
  );
}

function SandboxCard({ row }) {
  const [decision, setDecision] = useState('');
  const [busy, setBusy] = useState(false);
  /** 产物清单与校验结论：让「接受」不是盲批。 */
  const [report, setReport] = useState(null);

  useEffect(() => {
    const sessionId = useStore.getState().sessionId;
    if (!row.sandboxId || !sessionId) {
      return undefined;
    }
    let alive = true;
    api.sandboxManifest(sessionId, row.sandboxId)
      .then((data) => { if (alive) setReport(data); })
      .catch(() => { if (alive) setReport(null); });
    return () => { alive = false; };
  }, [row.sandboxId]);

  const decide = async (action) => {
    setBusy(true);
    const ok = await useStore.getState().decideSandbox(row.sandboxId, action);
    setBusy(false);
    // 接受失败（未通过校验且没有确认）时不写状态，用户还能重试
    if (ok) {
      setDecision(action === 'accept' ? '已接受' : '已拒绝');
    } else if (action === 'reject') {
      setDecision('已拒绝');
    }
  };

  const verification = report?.verification || null;
  const verified = Boolean(verification?.current) && verification?.status === 'PASSED';
  const files = report?.manifest?.files?.length ?? 0;

  return (
    <aside className="sandbox-card">
      <header className="sandbox-head">{row.title}</header>
      <pre className="sandbox-body">{row.detail}</pre>
      {report && (
        <p className={`sandbox-verify${verified ? ' is-ok' : ' is-warn'}`}>
          {verified ? '校验通过' : (verification ? `校验：${verification.status}${verification.reason ? ` · ${verification.reason}` : ''}` : '尚未校验')}
          {` · 产物 ${files} 个文件`}
          {(report.dependencies || []).length > 0 && ` · 依赖 ${report.dependencies.length} 个`}
        </p>
      )}
      {row.sandboxId && (
        <div className="sandbox-actions">
          <span className="sandbox-label mono">{row.sandboxId}</span>
          <button type="button" className="btn btn-primary btn-sm" disabled={busy || Boolean(decision)} onClick={() => decide('accept')}>接受变更</button>
          <button type="button" className="btn btn-sm" disabled={busy || Boolean(decision)} onClick={() => decide('reject')}>拒绝</button>
          {decision && <span className="sandbox-decision">{decision}</span>}
        </div>
      )}
    </aside>
  );
}

function ArbitrationCard({ row }) {
  const resolve = useStore((state) => state.resolveArbitration);
  return (
    <aside className={`arb-card${row.resolved ? ' is-resolved' : ''}`} data-arb-id={row.arbitrationId}>
      <header className="arb-head">{row.title}{row.resolved ? ' · 已裁决' : ''}</header>
      <p className="arb-summary">{row.detail}</p>
      {!row.resolved && (
        <div className="arb-options">
          {(row.options || []).map((option) => (
            <button
              key={option.id || option}
              type="button"
              className="arb-option"
              onClick={() => resolve(row.arbitrationId, option.id || option)}
            >
              {option.label || option}
            </button>
          ))}
        </div>
      )}
    </aside>
  );
}

export { formatTime };

function StatusMark({failed = false}) {
  return <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d={failed ? 'M4 4l8 8M12 4l-8 8' : 'M3 8l3 3 7-7'} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>;
}
