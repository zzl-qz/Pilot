import type { DraftAttachment } from '../types/input.js';
import type { ThreadRow } from '../types/thread.js';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { formatCache, requestContext } from '../lib/request-usage.js';
import React, { lazy, Suspense, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { ErrorBoundary } from './ErrorBoundary.jsx';
import { Icon } from './Icon.jsx';
import { VoiceInput } from './VoiceInput.jsx';
import { SubmissionNotice } from './SubmissionNotice.jsx';
import { VerificationStrip } from './VerificationStrip.jsx';
import { createComposerInput } from '../lib/composer-input.js';
import { resolveComposerAction, resolveSteerEntry } from '../lib/composer-state.js';
import { stepHistory } from '../lib/input-history.js';
import { DRAFT_STORAGE as localStore, writeDraft, readDraft, dropDraft, readDraftSnapshot, writeDraftSnapshot, dropDraftSnapshot } from '../lib/drafts.js';
import { PERMISSION_MODES, normalizePermission, permissionLabel } from '../lib/permission.js';
import { usageBudgetTitle, usageLevel } from '../lib/context-usage.js';
import { useComposerAttachments } from './composer/useComposerAttachments.js';
import { ComposerSuggestions, useComposerSuggestions } from './composer/ComposerSuggestions.js';
import './conversation.css';

const ContextUsagePanel = lazy(() => import('./RequestUsage.jsx').then(m => ({ default: m.ContextUsagePanel })));
const MAX_SESSION_DRAFTS = 20;

function releaseDraftPreview(file: DraftAttachment) {
  if (file.dataBase64?.startsWith('blob:')) URL.revokeObjectURL(file.dataBase64);
}
const ImagePreview = lazy(() => import('./ImagePreview.jsx').then(m => ({ default: m.ImagePreview })));
const MessageQueue = lazy(() => import('./MessageQueue.jsx').then(m => ({ default: m.MessageQueue })));
const ModelPicker = lazy(() => import('./model/ModelPicker.jsx').then(m => ({ default: m.ModelPicker })));
const ComposerTopDocks = lazy(() => import('./composer/ComposerDocks.js').then(m => ({ default: m.ComposerTopDocks })));
const AgentDock = lazy(() => import('./composer/ComposerDocks.js').then(m => ({ default: m.AgentDock })));
const ComposerChangesStrip = lazy(() => import('./composer/ComposerDocks.js').then(m => ({ default: m.ComposerChangesStrip })));

function DeferredTopDocks() {
  const active = useStore((state) => Boolean(
    Object.keys(state.pendingApprovals || {}).length
    || Object.keys(state.pendingQuestions || {}).length
    || state.ui.mirror.agentControlled
    || state.ui.mirror.handoffs?.length
    || state.lastOutcome === 'NEED_INPUT'
    || state.thread?.rows?.length
  ));
  if (!active) return null;
  return <ErrorBoundary name="输入区工具"><Suspense fallback={null}><ComposerTopDocks /></Suspense></ErrorBoundary>;
}

function DeferredMessageQueue() {
  const active = useStore((state) => Boolean(
    state.queueEntries.length || state.ui.queueEdit || state.queueHeld || state.queueWaitingReason
  ));
  if (!active) return null;
  return <ErrorBoundary name="消息队列"><Suspense fallback={null}><MessageQueue /></Suspense></ErrorBoundary>;
}

function DeferredAgentDock() {
  const active = useStore((state) => {
    const list = Object.values(state.agents || {});
    if (!list.length) return false;
    return Boolean(state.ui.agentsDockOpen || list.some((agent) => agent?.status === 'running'));
  });
  if (!active) return null;
  return <ErrorBoundary name="智能体面板"><Suspense fallback={null}><AgentDock /></Suspense></ErrorBoundary>;
}

function DeferredChangesStrip() {
  const active = useStore((state) => Boolean(!state.embeddedSessionId && !state.ui.inspectorOpen && state.changeReport?.files?.length));
  if (!active) return null;
  return <ErrorBoundary name="变更摘要"><Suspense fallback={null}><ComposerChangesStrip /></Suspense></ErrorBoundary>;
}

function countSteeredRows(rows?: readonly ThreadRow[]): number {
  if (!rows) return 0;
  let count = 0;
  for (const row of rows) {
    if (row.type === 'user' && row.steered) count += 1;
  }
  return count;
}

/**
 * 输入区：contenteditable 桥接旧版 composer-input（附件芯片 / @ 引用 / 粘贴纯文本），
 * 发送按钮状态机走 lib/composer-state。
 */
export function Composer() {
  const sessionId = useStore((state) => state.sessionId);
  const sessionEpoch = useStore((state) => state.sessionEpoch);
  const workspace = useStore((state) => state.workspace);
  const running = useStore((state) => state.running);
  const lastOutcome = useStore((state) => state.lastOutcome);
  const queueHeld = useStore((state) => state.queueHeld);
  const pendingInputs = useStore((state) => state.pendingInputs);
  const steering = useStore((state) => state.steering);
  const pausing = useStore((state) => state.pausing);
  const cancelling = useStore((state) => state.cancelling);
  const composerEnabled = useStore((state) => state.ui.composerEnabled);
  const sendKey = useStore((state) => state.alertPrefs.sendKey);
  const executionMode = useStore((state) => (state.ui.executionMode === 'plan' ? 'plan' : 'act'));
  const permissionMode = useStore((state) => normalizePermission(state.permissionMode));
  const modelOptions = useStore((state) => state.modelOptions);
  const modelSaving = useStore((state) => state.modelSave.busy);
  const settingsData = useStore((state) => state.settingsData);
  // /api/settings 的字段是 model（字符串）+ modelEndpoint，不是嵌套对象
  const currentModel = typeof settingsData?.model === 'string' ? settingsData.model : '';
  const currentEndpoint = settingsData?.modelEndpoint || '';
  const contextStatus = useStore((state) => state.contextStatus);
  const compacting = useStore((state) => state.compactingContext);
  const requestReports = useStore((state) => state.requestReports);
  const latestContextReport = useStore((state) => state.latestContextReport);
  const context = requestContext(contextStatus, latestContextReport);
  const contextUsagePercent = context.percent == null ? null : Math.round(context.percent);
  const runUsage = useStore((state) => state.runUsage);
  const editTarget = useStore((state) => state._editTarget);

  const flowRef = useRef<HTMLDivElement>(null);
  const voiceFeedbackRef = useRef<HTMLDivElement>(null);
  const permTriggerRef = useRef<HTMLButtonElement>(null);
  const permMenuRef = useRef<HTMLDivElement>(null);
  const modelTriggerRef = useRef<HTMLButtonElement>(null);
  const contextTriggerRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<ReturnType<typeof createComposerInput> | null>(null);
  const railRef = useRef<HTMLDivElement>(null);
  const [preview, setPreview] = useState<{ name: string; src: string } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const [sendAction, setSendAction] = useState({ mode: 'send', stop: false, disabled: true, label: '发送', ariaLabel: '发送任务', title: '发送任务 (Enter)' });
  const [toolbarPanel, setToolbarPanel] = useState<'perm' | 'model' | 'context' | null>(null);
  const permOpen = toolbarPanel === 'perm';
  const modelOpen = toolbarPanel === 'model';
  const contextOpen = toolbarPanel === 'context';
  const notesOpen = useStore((state) => Boolean(state.ui.ideasOpen));
  const draftFilesRef = useRef(new Map<string, DraftAttachment>());
  const submittingRef = useRef(false);
  const submittingEpoch = useRef<number | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [voiceBusy, setVoiceBusy] = useState(false);
  const voiceBusyRef = useRef(false);
  const [steerPending, setSteerPending] = useState<{ runId: string; text: string; baseline: number } | null>(null);
  const runId = useStore((state) => state.runId);
  const steeredCount = useStore((state) => (steerPending ? countSteeredRows(state.thread?.rows) : 0));
  const sessionDraftsRef = useRef(new Map<string | null, { html: string; files: Map<string, DraftAttachment>; attachmentIds: string[] }>());
  const previousSessionRef = useRef(sessionId);
  const snapshotTimer = useRef<number | null>(null);
  const snapshotWarnedRef = useRef(false);

  const {
    dragging,
    pendingFiles,
    pendingFilesRef,
    currentDraftFiles,
    addFiles,
    receiveFiles
  } = useComposerAttachments({ flowRef, inputRef, draftFilesRef, submittingRef });

  const suggestions = useComposerSuggestions({ inputRef, sessionId, sessionEpoch, workspace });

  function focusToolbarTrigger(panel: 'perm' | 'model' | 'context') {
    const ref = { perm: permTriggerRef, model: modelTriggerRef, context: contextTriggerRef }[panel];
    ref.current?.focus({ preventScroll: true });
  }

  /** 把当前正文、附件与图片位置写成草稿副本；附件为空时清掉旧副本。 */
  function persistSnapshot(sessionKey: string | null = useStore.getState().sessionId) {
    const input = inputRef.current;
    if (!input) return;
    const files = currentDraftFiles(input);
    if (!files.length) {
      dropDraftSnapshot(localStore, sessionKey);
      return;
    }
    const result = writeDraftSnapshot(localStore, sessionKey, {
      text: input.getText(), files, imagePositions: input.getDraft().imagePositions
    });
    if (!result.ok && !snapshotWarnedRef.current) {
      snapshotWarnedRef.current = true;
      useStore.getState().showNotice(result.reason || '未发送附件无法保存为草稿，刷新后可能丢失。');
    }
  }

  /** 附件副本体积可能不小，输入时合并写入，避免每个按键都序列化图片。 */
  function scheduleSnapshot() {
    if (snapshotTimer.current) window.clearTimeout(snapshotTimer.current);
    snapshotTimer.current = window.setTimeout(() => {
      snapshotTimer.current = null;
      persistSnapshot();
    }, 400);
  }

  /*
   * contenteditable 桥接：只在挂载时创建一次。
   * 会话 ID 会在「草稿态 → 发第一条消息后创建」时中途变化，以前按 sessionId 重建编辑器，
   * 那一下正在输入的内容和刚附上的文件都会被冲掉；现在草稿改成按当前会话动态取键。
   */
  useEffect(() => {
    if (!flowRef.current) return undefined;
    const input = createComposerInput(flowRef.current, {
      rail: railRef.current,
      onRemove: (id) => draftFilesRef.current.delete(id),
      onPreview: setPreview,
      onChange: () => {
        const currentSession = useStore.getState().sessionId;
        writeDraft(localStore, currentSession, input.getText());
        if (!input.attachmentIds().length) {
          if (snapshotTimer.current) {
            window.clearTimeout(snapshotTimer.current);
            snapshotTimer.current = null;
          }
          dropDraftSnapshot(localStore, currentSession);
        } else {
          scheduleSnapshot();
        }
        setSendAction(sendState());
      }
    });
    const flushSnapshotOnUnload = () => {
      if (snapshotTimer.current) {
        window.clearTimeout(snapshotTimer.current);
        snapshotTimer.current = null;
        persistSnapshot();
      }
    };
    window.addEventListener('pagehide', flushSnapshotOnUnload);
    inputRef.current = input;
    let composing = false;
    const startComposition = () => { composing = true; };
    const endComposition = () => { composing = false; };
    input.root.addEventListener('compositionstart', startComposition);
    input.root.addEventListener('compositionend', endComposition);
    // state 被 immer 冻结，必须走 setState，不能直接赋值
    useStore.setState({ composerApi: {
      prepareReload: () => {
        if (composing) return { ok: false, reason: '请先完成输入法中的文字，再重新载入。' };
        if (voiceBusyRef.current) return { ok: false, reason: '请先结束语音输入，再重新载入。' };
        if (submittingRef.current || pendingFilesRef.current) return { ok: false, reason: '正在发送或读取附件，请完成后重新载入。' };
        if (input.attachmentIds().length) return { ok: false, reason: '有未发送的附件，请先发送或移除，再重新载入。' };
        const text = input.getText();
        writeDraft(localStore, useStore.getState().sessionId, text);
        if (text.trim() && readDraft(localStore, useStore.getState().sessionId) !== text) return { ok: false, reason: '当前草稿未能完整保存，已保留页面，请先复制或发送草稿。' };
        return { ok: true };
      },
      getText: () => input.getText(),
      getDraft: () => input.getDraft(),
      attachmentIds: () => input.attachmentIds(),
      setText: (text) => input.setText(text),
      replaceText: (text) => input.replaceText(text),
      appendText: (text) => input.appendText(text),
      insertAttachments: (items) => {
        for (const item of items || []) {
          if (item?.id) draftFilesRef.current.set(item.id, item);
        }
        input.insertAttachments(items);
      },
      insertMention: (path) => input.insertMention(path),
      focus: () => input.focus()
    } });
    return () => {
      window.removeEventListener('pagehide', flushSnapshotOnUnload);
      if (snapshotTimer.current) {
        window.clearTimeout(snapshotTimer.current);
        snapshotTimer.current = null;
      }
      persistSnapshot();
      input.root.removeEventListener('compositionstart', startComposition);
      input.root.removeEventListener('compositionend', endComposition);
      input.destroy();
      inputRef.current = null;
      useStore.setState({ composerApi: null });
    };
  }, []);

  /* 切换会话 / 草稿落库成真实会话后，把对应草稿回填（没有草稿就是清空）。 */
  useEffect(() => {
    const input = inputRef.current;
    const previous = previousSessionRef.current;
    // 首条消息落库时保留编辑器；真正切换会话才换草稿。
    if (previous == null && sessionId && submittingRef.current && submittingEpoch.current === useStore.getState().sessionEpoch) {
      previousSessionRef.current = sessionId;
      return;
    }
    if (previous !== sessionId && input) {
      // 先把上一个会话的附件副本落盘，再切换编辑区
      if (snapshotTimer.current) {
        window.clearTimeout(snapshotTimer.current);
        snapshotTimer.current = null;
        persistSnapshot(previous);
      }
      // 附件在 rail 里、不在 root 的 innerHTML 内，必须连同文件副本一起存
      sessionDraftsRef.current.delete(previous);
      sessionDraftsRef.current.set(previous, {
        html: input.root.innerHTML, files: new Map(draftFilesRef.current), attachmentIds: input.attachmentIds()
      });
      while (sessionDraftsRef.current.size > MAX_SESSION_DRAFTS) {
        const oldest = sessionDraftsRef.current.keys().next().value;
        if (oldest === undefined) break;
        const draft = sessionDraftsRef.current.get(oldest);
        draft?.files.forEach(releaseDraftPreview);
        sessionDraftsRef.current.delete(oldest);
      }
    }
    const saved = sessionDraftsRef.current.get(sessionId);
    const stored = readDraftSnapshot(localStore, sessionId);
    if (stored.error === 'corrupt') {
      useStore.getState().showNotice('未发送的附件草稿已损坏并清除，请重新添加需要保留的附件。');
    }
    if (saved && input) {
      input.root.innerHTML = saved.html;
      draftFilesRef.current = new Map(saved.files);
      input.restoreAttachments((saved.attachmentIds || []).map((id) => saved.files.get(id)).filter((item): item is DraftAttachment => Boolean(item)));
    } else if (stored.snapshot && input) {
      // 刷新/重开工作台：正文、文件、图片和图片位置一起恢复
      draftFilesRef.current = new Map(stored.snapshot.files.map((item) => [item.id, item]));
      input.restoreDraft(stored.snapshot.text || readDraft(localStore, sessionId), stored.snapshot.files,
        stored.snapshot.imagePositions);
    } else {
      draftFilesRef.current.clear();
      input?.setText(readDraft(localStore, sessionId));
    }
    previousSessionRef.current = sessionId;
  }, [sessionId]);

  function sendState() {
    const state = useStore.getState();
    const text = inputRef.current?.getText() || '';
    return resolveComposerAction({
      running: state.running,
      editing: state._editTarget != null,
      hasText: Boolean(text.trim()),
      attachmentCount: inputRef.current ? (inputRef.current.attachmentIds?.()?.length || 0) : 0,
      composerEnabled: state.ui.composerEnabled !== false,
      steering: state.steering,
      pausing: state.pausing,
      cancelling: state.cancelling,
      modelSaving: state.modelSave.busy,
      queuePaused: state.queueHeld
    });
  }

  useEffect(() => {
    setSendAction(sendState());
  }, [running, queueHeld, lastOutcome, pendingInputs.length, steering, pausing, cancelling, editTarget, modelSaving, composerEnabled]);

  async function submit() {
    const input = inputRef.current;
    if (voiceBusyRef.current || pendingFilesRef.current || submittingRef.current || sendState().disabled) return;
    if (!input) return;
    const { text, imagePositions } = input.getDraft();
    const attachments = currentDraftFiles(input);
    const state = useStore.getState();
    if (!text.trim() && !attachments.length) return;
    submittingRef.current = true;
    submittingEpoch.current = state.sessionEpoch;
    setSubmitting(true);
    let sent;
    try {
      if (state._editTarget != null) {
        sent = await state.resendMessage(state._editTarget, text, attachments);
        if (sent) useStore.setState({ _editTarget: null });
      } else {
        sent = await state.submitInput(text, attachments, imagePositions);
      }
      if (sent !== true) return;
      if (useStore.getState().sessionEpoch === state.sessionEpoch &&
          (useStore.getState().sessionId === state.sessionId || !state.sessionId)) {
        input.setText('');
        draftFilesRef.current.clear();
        sessionDraftsRef.current.delete(state.sessionId);
        dropDraft(localStore, useStore.getState().sessionId);
        dropDraftSnapshot(localStore, useStore.getState().sessionId);
        if (!state.sessionId) dropDraftSnapshot(localStore, null);
      } else {
        sessionDraftsRef.current.delete(state.sessionId);
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  /** 「纠正本轮」：绑定点击时看到的运行，成功后才清空输入；本轮结束仍未应用时把内容放回输入框。 */
  async function steerCurrent() {
    const input = inputRef.current;
    if (!input || !runId || steerPending) return;
    const text = input.getText();
    if (!text.trim()) return;
    const state = useStore.getState();
    const baseline = countSteeredRows(state.thread?.rows);
    const ownerSession = state.sessionId;
    const ownerEpoch = state.sessionEpoch;
    const sent = await state.steer(text, []);
    const current = useStore.getState();
    if (current.sessionId !== ownerSession || current.sessionEpoch !== ownerEpoch) return;
    if (!sent) return;
    if (!inputRef.current) return;
    inputRef.current.setText('');
    draftFilesRef.current.clear();
    writeDraft(localStore, current.sessionId, '');
    dropDraftSnapshot(localStore, current.sessionId);
    setSteerPending({ runId, text, baseline });
    current.showNotice('纠正已提交，等待本轮在安全点应用。');
  }

  /* 收到对应的纠正事件后才显示已应用 */
  useEffect(() => {
    if (!steerPending) return;
    if (steeredCount > steerPending.baseline) {
      useStore.getState().showNotice('纠正已应用。');
      setSteerPending(null);
    }
  }, [steeredCount, steerPending]);

  /* 本轮结束后仍未看到纠正事件：内容放回输入框，避免用户以为已经生效 */
  useEffect(() => {
    if (!steerPending || runId === steerPending.runId) return;
    const pending = steerPending;
    setSteerPending(null);
    const input = inputRef.current;
    if (input && !input.getText().trim()) {
      input.setText(pending.text);
      writeDraft(localStore, useStore.getState().sessionId, pending.text);
    }
    useStore.getState().showNotice('本轮已结束，纠正可能未被应用；内容已放回输入框，可重试或加入队列。');
  }, [runId, steerPending]);

  function toggleToolbarPanel(panel: NonNullable<typeof toolbarPanel>) {
    suggestions.clearSuggestions();
    setToolbarPanel((current) => (current === panel ? null : panel));
  }

  useEffect(() => {
    setToolbarPanel(null);
  }, [sessionId, sessionEpoch, workspace]);

  useLayoutEffect(() => {
    if (permOpen) permMenuRef.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus({ preventScroll: true });
  }, [permOpen]);

  useEffect(() => {
    if (!toolbarPanel) return;
    const key = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.isComposing || event.keyCode === 229 || event.defaultPrevented) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      setToolbarPanel(null);
      focusToolbarTrigger(toolbarPanel);
    };
    const owned = `#${toolbarPanel}-chip, #${toolbarPanel}-menu`;
    const closeOutside = (event: Event) => {
      if (event.target instanceof Element && !event.target.closest(owned)) {
        setToolbarPanel(null);
      }
    };
    document.addEventListener('keydown', key, true);
    document.addEventListener('pointerdown', closeOutside, true);
    document.addEventListener('focusin', closeOutside);
    return () => {
      document.removeEventListener('keydown', key, true);
      document.removeEventListener('pointerdown', closeOutside, true);
      document.removeEventListener('focusin', closeOutside);
    };
  }, [toolbarPanel]);

  function movePermissionFocus(event: React.KeyboardEvent) {
    if (event.nativeEvent.isComposing || !['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    const items = [...(permMenuRef.current?.querySelectorAll('button') || [])];
    if (!items.length) {
      if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
        event.preventDefault();
        toggleToolbarPanel('perm');
      }
      return;
    }
    event.preventDefault();
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    const next = {
      Home: 0,
      End: items.length - 1,
      ArrowDown: (index + 1) % items.length,
      ArrowUp: (Math.max(0, index) + items.length - 1) % items.length
    }[event.key];
    if (next !== undefined) items[next]?.focus();
  }

  /** ↑/↓ 翻本会话发过的输入；多行草稿靠 caretOnEdgeLine 判断，只在首/末行接管。 */
  function handleHistoryKey(event: React.KeyboardEvent<HTMLDivElement>) {
    const state = useStore.getState();
    const input = inputRef.current;
    if (!state.inputHistory.length || !input || input.hasChips?.()) return false;
    const up = event.key === 'ArrowUp';
    if (!input.caretOnEdgeLine?.(up ? 'first' : 'last')) return false;
    const browsing = state.inputHistoryIndex >= 0;
    if (!browsing && !up) return false;
    event.preventDefault();
    const draftText = browsing ? state.inputHistoryDraft : input.getText();
    const next = stepHistory(state.inputHistory, browsing ? state.inputHistoryIndex : -1, up ? 'up' : 'down', draftText);
    useStore.setState((draft) => {
      draft.inputHistoryIndex = next.index;
      draft.inputHistoryDraft = draftText || '';
    });
    if (next.text != null) {
      input.setText(next.text);
      setSendAction(sendState());
    }
    input.focusEnd?.();
    return true;
  }

  function onComposerKeydown(event: React.KeyboardEvent<HTMLDivElement>) {
    // 中文输入法组字期间的回车不是发送
    if (event.nativeEvent.isComposing || event.keyCode === 229) return;
    if (suggestions.handleKeyDown(event)) return;
    if ((event.key === 'ArrowUp' || event.key === 'ArrowDown') && !event.shiftKey && !event.metaKey && !event.ctrlKey && !event.altKey) {
      if (handleHistoryKey(event)) return;
    }
    if (event.key === 'Tab' && event.shiftKey && !event.metaKey && !event.ctrlKey && !event.altKey) {
      event.preventDefault();
      const nextMode = executionMode === 'plan' ? 'act' : 'plan';
      const state = useStore.getState();
      state.setUi({ executionMode: nextMode });
      state.showNotice(nextMode === 'plan' ? '已切换为只读分析模式（Shift+Tab）' : '已切换为执行模式（Shift+Tab）');
      return;
    }
    if (sendKey === 'mod-enter') {
      if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        void submit();
      }
    } else if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void submit();
    }
  }

  const defaultSendTitle = sendKey === 'mod-enter'
    ? '发送任务 (Ctrl/Cmd+Enter · Shift+Tab 切模式)'
    : '发送任务 (Enter · Shift+Tab 切模式)';
  const sendTitle = sendAction.stop ? sendAction.title : defaultSendTitle;
  const defaultSendShortcut = sendKey === 'mod-enter' ? 'Control+Enter Meta+Enter' : 'Enter';
  const sendShortcut = sendAction.stop ? 'Escape' : defaultSendShortcut;

  return (
    <div className="composer-wrap conversation-composer">
      <div className="composer-docks">
        <SubmissionNotice key={sessionId || 'draft'} sessionId={sessionId} />
        {editTarget != null && <div className="send-mode-label">
          <span>正在编辑历史消息 · 发送将重新执行</span>
          <Button size="sm" variant="ghost" label="作为新消息发送" onClick={() => {
            useStore.setState({ _editTarget: null });
            inputRef.current?.focus();
          }} />
        </div>}
        <DeferredTopDocks />
        <DeferredMessageQueue />
        <DeferredAgentDock />
        {/* 验证结论放在输入框容器内侧：和输入框同宽，不会比它更靠外。 */}
        <VerificationStrip />
        <DeferredChangesStrip />
      </div>
      {/* 附件单独一排，排在输入框上方外侧：不挤占正文宽度，也不被输入框边框包住 */}
      <div className="composer-attachments" ref={railRef} hidden aria-label="待发送的附件" />
      <form
        id="composer"
        className={`composer${dragging ? ' is-dragging' : ''}${executionMode === 'plan' ? ' is-plan-mode' : ''}`}
        onSubmit={(event) => { event.preventDefault(); void submit(); }}
        onPaste={(event) => void receiveFiles(event, event.clipboardData, 'paste')}
      >
        {dragging && <div className="attachment-drop-overlay" aria-hidden="true">松开以添加文件</div>}
        {pendingFiles > 0 && <div className="attachment-status" role="status">
          正在读取 {pendingFiles} 个附件…
        </div>}
        <div id="input-flow" className="input-flow" contentEditable={!submitting}
          role="textbox" aria-label="消息输入框" aria-multiline="true" aria-busy={pendingFiles > 0 || submitting}
          aria-readonly={submitting || undefined} tabIndex={0} aria-autocomplete="list"
          aria-controls={suggestions.items.length ? 'composer-suggestions' : undefined}
          aria-activedescendant={suggestions.items.length ? 'composer-option-' + suggestions.menuIndex : undefined}
          ref={flowRef}
          data-placeholder={executionMode === 'plan' ? '只读分析模式：提问、排查或规划方案，不会修改文件' : '描述任务，@ 引用文件，/ 快捷指令'}
          onInput={suggestions.onInput}
          onCompositionEnd={suggestions.onInput}
          onKeyDown={onComposerKeydown}
          onBlur={suggestions.clearSuggestions}
        />
        <ComposerSuggestions
          open={suggestions.open}
          refMenu={suggestions.refMenu}
          items={suggestions.items}
          menuIndex={suggestions.menuIndex}
          onHoverIndex={suggestions.setMenuIndex}
          onPickRef={suggestions.pickRef}
          onPickSlash={suggestions.pickSlash}
        />
        <div className="composer-bar">
          <input ref={fileRef} type="file" multiple hidden onChange={(event) => { addFiles(event.target.files); event.target.value = ''; }} />
          <div className="composer-left">
            <span title="添加文件或图片，也可以拖入或粘贴">
              <IconButton id="attach-button" className="composer-icon" variant="ghost"
                label="添加文件或图片" isDisabled={submitting} onClick={() => fileRef.current?.click()}
                icon={<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13.2 7.4 8.1 12.5a3.1 3.1 0 0 1-4.4-4.4l5.3-5.3a2.1 2.1 0 0 1 3 3L6.9 11a1.1 1.1 0 0 1-1.6-1.6l4.8-4.8" stroke="currentColor" strokeWidth="1.3" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>} />
            </span>
            <div className="perm-picker composer-notes-picker">
              <span title="打开跨项目的想法库">
                <IconButton id="notes-chip" className="composer-icon" variant="ghost"
                  label="想法库" icon={<Icon name="note" />}
                  aria-haspopup="dialog" aria-expanded={notesOpen}
                  onClick={() => {
                    setToolbarPanel(null);
                    useStore.getState().setUi({ ideasOpen: true });
                  }} />
              </span>
            </div>
            <div className="perm-picker">
              <Button id="perm-chip" ref={permTriggerRef} className="composer-choice" size="sm" variant="ghost"
                label={permissionLabel(permissionMode)} icon={<Icon name="shield" />}
                aria-haspopup="menu" aria-expanded={permOpen} aria-controls={permOpen ? 'perm-menu' : undefined}
                onKeyDown={movePermissionFocus} onClick={() => toggleToolbarPanel('perm')} />
              {permOpen && (
                <div id="perm-menu" ref={permMenuRef} className="composer-permissions composer-flyout" role="menu" aria-label="权限档位"
                  onKeyDown={movePermissionFocus}>
                  <p className="composer-popover-label" aria-hidden="true">权限</p>
                  {PERMISSION_MODES.map(item => (
                    <Button key={item.mode} className="composer-permission-option" variant="ghost" width="100%"
                      label={item.label + ' · ' + item.hint} role="menuitemradio" tabIndex={-1}
                      aria-checked={permissionMode === item.mode} onClick={() => {
                        void useStore.getState().savePermissionMode(item.mode);
                        setToolbarPanel(null);
                        permTriggerRef.current?.focus({ preventScroll: true });
                      }}>
                      <span className="composer-permission-content">
                        <span className="composer-permission-text"><strong>{item.label}</strong><span>{item.hint}</span></span>
                        {permissionMode === item.mode && <Icon name="check" />}
                      </span>
                    </Button>
                  ))}
                </div>
              )}
            </div>
            <div className="perm-picker composer-model-picker">
              <Button id="model-chip" ref={modelTriggerRef} className="composer-choice composer-model" size="sm" variant="ghost"
                label={currentModel ? '切换模型，当前 ' + currentModel : '选择模型'}
                aria-haspopup="dialog" aria-expanded={modelOpen} aria-controls={modelOpen ? 'model-menu' : undefined}
                endContent={<Icon name="chevron" className="composer-chevron" />} onClick={() => toggleToolbarPanel('model')}>
                <span className="model-chip-name" title={currentModel || undefined}>{currentModel || '选择模型'}</span>
              </Button>
              {modelOpen && (
                <ErrorBoundary name="模型选择" onReset={() => setToolbarPanel(null)}><Suspense fallback={null}>
                  <ModelPicker key={currentEndpoint} models={modelOptions} initialModel={currentModel} initialEndpoint={currentEndpoint}
                    onClose={() => { setToolbarPanel(null); focusToolbarTrigger('model'); }} />
                </Suspense></ErrorBoundary>
              )}
            </div>
          </div>
          <div className="composer-actions">
            <div className="perm-picker composer-context-picker">
              <span title={usageBudgetTitle(context.used, context.limit)}><Button id="context-chip" ref={contextTriggerRef} className={'composer-choice composer-context level-' + usageLevel(contextUsagePercent)}
                size="sm" variant="ghost" label={'上下文用量 ' + contextPercent(contextUsagePercent) + (runUsage?.details ? ' · 缓存命中 ' + formatCache(runUsage.details) : '')}
                aria-haspopup="dialog" aria-expanded={contextOpen} aria-controls={contextOpen ? 'context-menu' : undefined}
                icon={<svg className="context-ring" viewBox="0 0 20 20" aria-hidden="true"><circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" opacity=".25" strokeWidth="2" /><circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" strokeWidth="2" pathLength="100" strokeDasharray={`${contextUsagePercent ?? 0} 100`} transform="rotate(-90 10 10)" /></svg>}
                onClick={() => toggleToolbarPanel('context')}>{contextPercent(contextUsagePercent)}</Button></span>
              {contextOpen && <ErrorBoundary name="上下文用量" onReset={() => setToolbarPanel(null)}><Suspense fallback={null}><ContextUsagePanel status={contextStatus} compacting={compacting}
                report={latestContextReport} reports={requestReports} usage={runUsage}
                onClose={() => { setToolbarPanel(null); contextTriggerRef.current?.focus({ preventScroll: true }); }}
                onCompact={(focus) => useStore.getState().compactContext(focus)}
                onSettings={() => { setToolbarPanel(null); useStore.getState().setUi({ settingsOpen: true, settingsSection: 'model' }); }} /></Suspense></ErrorBoundary>}
            </div>
            <IconButton className="composer-icon schedule-compose" variant="ghost" label="定时执行" icon={<Icon name="clock" />}
              isDisabled={pendingFiles > 0 || submitting}
              onClick={() => {
                const state = useStore.getState();
                if (state.composerApi?.attachmentIds?.()?.length) { state.showNotice('定时入口暂不携带附件。请将文件放入工作区，在目标中注明相对路径；当前草稿与附件已保留。'); return; }
                state.setUi({ schedulesOpen: true, scheduleDraft: state.composerApi?.getText() || '' });
              }} />
            <VoiceInput ownerKey={sessionEpoch + ':' + (sessionId || '') + ':' + workspace} disabled={submitting} feedbackRef={voiceFeedbackRef}
              onBusy={value => { voiceBusyRef.current = value; setVoiceBusy(value); }}
              onText={text => { const input = inputRef.current; input?.appendText((input.getText().trim() ? '\n' : '') + text); input?.focus(); }} />
            {(() => {
              const steerAction = resolveSteerEntry({
                running, editing: editTarget != null, hasText: Boolean(inputRef.current?.getText().trim()),
                attachmentCount: inputRef.current?.attachmentIds?.().length || 0,
                steering, pending: steerPending != null
              });
              return steerAction.visible ? <span title={steerAction.title}>
                <Button id="steer-current" className="composer-choice" size="sm" variant="ghost"
                  label={steerAction.label} isDisabled={steerAction.disabled} onClick={() => void steerCurrent()} />
              </span> : null;
            })()}
            <span title={sendTitle}>
              <IconButton id="send" className="composer-primary" variant="primary"
                type={sendAction.stop ? 'button' : 'submit'} label={sendAction.ariaLabel}
                aria-keyshortcuts={sendShortcut}
                isLoading={sendAction.stop ? cancelling : submitting}
                isDisabled={sendAction.disabled || (!sendAction.stop && (pendingFiles > 0 || submitting || voiceBusy))}
                onClick={sendAction.stop ? () => useStore.getState().cancelRun() : undefined}
                icon={sendAction.stop
                  ? <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="4.6" y="4.6" width="6.8" height="6.8" rx="1.6" fill="currentColor" /></svg>
                  : <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 12.5V3.8M4.6 7.2 8 3.8l3.4 3.4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" fill="none" /></svg>} />
            </span>
          </div>
        </div>
        <div className="composer-voice-feedback" ref={voiceFeedbackRef} />
      </form>
      {preview && <ErrorBoundary name="图片预览" onReset={() => setPreview(null)}><Suspense fallback={null}><ImagePreview name={preview.name} src={preview.src} onClose={() => setPreview(null)} /></Suspense></ErrorBoundary>}
    </div>
  );
}

function contextPercent(value: number | null) {
  return value == null ? '—' : `${value}%`;
}
