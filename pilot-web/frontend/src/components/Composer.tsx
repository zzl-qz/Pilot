import type { DraftAttachment, BuiltinCommand, SkillCommand } from '../types/input.js';
import type { ContextStatus } from '../types/runtime.js';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { StatusDot } from '@astryxdesign/core/StatusDot';
type QuickCommand = BuiltinCommand | SkillCommand;
interface ReferenceMenu { items: string[]; message?: string; loading?: boolean }
import { errorInfo } from '../lib/errors.js';
import { formatCache } from '../lib/request-usage.js';
import { ContextUsagePanel } from './RequestUsage.jsx';
import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { Icon } from './Icon.jsx';
import { VoiceInput } from './VoiceInput.jsx';
import { SubmissionNotice } from './SubmissionNotice.jsx';
import { MessageQueue } from './MessageQueue.jsx';
import { createComposerInput } from '../lib/composer-input.js';
import { resolveComposerAction } from '../lib/composer-state.js';
import { rankFileMatches } from '../lib/mention.js';
import { filterQuickCommands, expandQuickCommand } from '../lib/commands.js';
import { stepHistory } from '../lib/input-history.js';
import { readTransferFiles, hasFileTransferHint } from '../lib/clipboard.js';
import { readAttachmentFile } from '../lib/attachment-input.js';
import { checkIncomingFile, requireAttachmentLimits } from '../lib/attachments.js';
import { writeDraft, readDraft, dropDraft } from '../lib/drafts.js';
import { resumeBar } from '../lib/queue.js';
import { PERMISSION_MODES, normalizePermission, permissionLabel } from '../lib/permission.js';
import { ModelPicker } from './ModelPicker.jsx';
import { usageLevel, usagePercent } from '../lib/context-usage.js';
import { agentStatusLabel, summarizeAgents } from '../lib/agents.js';
import './conversation.css';

/**
 * 输入区：contenteditable 桥接旧版 composer-input（附件芯片 / @ 引用 / 粘贴纯文本），
 * 发送按钮状态机走 lib/composer-state。
 */
export function Composer() {
  const sessionId = useStore((state) => state.sessionId);
  const sessionEpoch = useStore(state => state.sessionEpoch);
  const workspace = useStore(state => state.workspace);
  const running = useStore((state) => state.running);
  const lastOutcome = useStore((state) => state.lastOutcome);
  const queueHeld = useStore((state) => state.queueHeld);
  const pendingInputs = useStore((state) => state.pendingInputs);
  const steering = useStore((state) => state.steering);
  const pausing = useStore((state) => state.pausing);
  const cancelling = useStore((state) => state.cancelling);
  const composerEnabled = useStore((state) => state.ui.composerEnabled);
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
  const runUsage = useStore((state) => state.runUsage);
  const editTarget = useStore((state) => state._editTarget);

  const flowRef = useRef<HTMLDivElement>(null);
  const voiceFeedbackRef = useRef<HTMLDivElement>(null);
  const permTriggerRef = useRef<HTMLButtonElement>(null);
  const permMenuRef = useRef<HTMLDivElement>(null);
  const contextTriggerRef = useRef<HTMLButtonElement>(null);
  const referenceRequest = useRef(0);
  const inputRef = useRef<ReturnType<typeof createComposerInput> | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const [sendAction, setSendAction] = useState({ mode: 'send', stop: false, disabled: true, label: '发送', ariaLabel: '发送任务', title: '发送任务 (Enter)' });
  const [toolbarPanel, setToolbarPanel] = useState<'perm' | 'model' | 'context' | null>(null);
  const permOpen = toolbarPanel === 'perm';
  const modelOpen = toolbarPanel === 'model';
  const contextOpen = toolbarPanel === 'context';
  const [refMenu, setRefMenu] = useState<ReferenceMenu | null>(null);
  const [slashMenu, setSlashMenu] = useState<{ items: QuickCommand[]; query: string } | null>(null);
  const [menuIndex, setMenuIndex] = useState(0);
  const menuRef = useRef<HTMLDivElement>(null);
  const draftFilesRef = useRef(new Map<string, DraftAttachment>());
  const fileQueueRef = useRef(Promise.resolve());
  const pendingFilesRef = useRef(0);
  const submittingRef = useRef(false);
  const submittingEpoch = useRef<number | null>(null);
  const [pendingFiles, setPendingFiles] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [voiceBusy, setVoiceBusy] = useState(false);
  const voiceBusyRef = useRef(false);
  const [dragging, setDragging] = useState(false);
  const dragDepthRef = useRef(0);
  const sessionDraftsRef = useRef(new Map<string | null, { html: string; files: Map<string, DraftAttachment> }>());
  const previousSessionRef = useRef(sessionId);

  /*
   * contenteditable 桥接：只在挂载时创建一次。
   * 会话 ID 会在「草稿态 → 发第一条消息后创建」时中途变化，以前按 sessionId 重建编辑器，
   * 那一下正在输入的内容和刚附上的文件都会被冲掉；现在草稿改成按当前会话动态取键。
   */
  useEffect(() => {
    if (!flowRef.current) {
      return undefined;
    }
    const input = createComposerInput(flowRef.current, {
      onRemove: (id) => draftFilesRef.current.delete(id),
      onChange: () => {
        writeDraft(localStorage, useStore.getState().sessionId, input.getText());
        setSendAction(sendState());
      }
    });
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
        writeDraft(localStorage, useStore.getState().sessionId, text);
        if (text.trim() && readDraft(localStorage, useStore.getState().sessionId) !== text) return { ok: false, reason: '当前草稿未能完整保存，已保留页面，请先复制或发送草稿。' };
        return { ok: true };
      },
      getText: () => input.getText(),
      getDraft: () => input.getDraft(),
      attachmentIds: () => input.attachmentIds(),
      setText: (text) => input.setText(text),
      appendText: (text) => input.appendText(text),
      insertAttachments: (items) => input.insertAttachments(items),
      insertMention: (path) => input.insertMention(path),
      focus: () => input.focus()
    } });
    return () => {
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
      sessionDraftsRef.current.set(previous, {
        html: input.root.innerHTML, files: new Map(draftFilesRef.current)
      });
    }
    const saved = sessionDraftsRef.current.get(sessionId);
    if (saved && input) {
      input.root.innerHTML = saved.html;
      draftFilesRef.current = new Map(saved.files);
      input.notifyChange();
    } else {
      draftFilesRef.current.clear();
      input?.setText(readDraft(localStorage, sessionId));
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

  /* 附件上传：选择 / 拖放 / 粘贴共用 */
  function addFiles(fileList: ArrayLike<File> | null) {
    if (submittingRef.current) return;
    const files = Array.from(fileList || []);
    const owner = useStore.getState();
    const ownsDraft = () => {
      const { sessionId, sessionEpoch } = useStore.getState();
      return sessionId === owner.sessionId && sessionEpoch === owner.sessionEpoch;
    };
    pendingFilesRef.current += files.length;
    setPendingFiles(pendingFilesRef.current);
    fileQueueRef.current = fileQueueRef.current.then(async () => {
      for (const file of files) {
        try {
          if (!ownsDraft()) continue;
          const existing = (inputRef.current?.attachmentIds() || [])
            .map((id) => draftFilesRef.current.get(id)).filter((item): item is DraftAttachment => Boolean(item));
          const settings = useStore.getState().settingsData || await api.settings();
          const limits = requireAttachmentLimits(settings?.attachmentLimits);
          if (!ownsDraft()) continue;
          const result = checkIncomingFile(existing, file, limits);
          if (!result.ok) {
            if (!result.silent) useStore.getState().showNotice(result.reason);
            continue;
          }
          const item = await readAttachmentFile(file);
          if (!ownsDraft() || !inputRef.current) continue;
          draftFilesRef.current.set(item.id, item);
          inputRef.current.insertAttachments([item]);
        } catch (error) {
          useStore.getState().showNotice('读取附件失败：' + errorInfo(error).message);
        } finally {
          pendingFilesRef.current -= 1;
          setPendingFiles(pendingFilesRef.current);
        }
      }
    });
  }

  function receiveFiles(event: React.SyntheticEvent, transfer: DataTransfer) {
    const files = readTransferFiles(transfer);
    if (!files.length && !hasFileTransferHint(transfer)) return;
    event.preventDefault();
    if (files.length) addFiles(files);
    else useStore.getState().showNotice('当前环境未提供文件内容，请拖入文件或使用“添加文件或图片”。');
  }

  async function submit() {
    const input = inputRef.current;
    if (voiceBusyRef.current || pendingFilesRef.current || submittingRef.current || sendState().disabled) return;
    if (!input) {
      return;
    }
    const { text, imagePositions } = input.getDraft();
    const attachments = input.attachmentIds().map((id) => draftFilesRef.current.get(id)).filter((item): item is DraftAttachment => Boolean(item));
    const state = useStore.getState();
    if (!text.trim() && !attachments.length) {
      return;
    }
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
        dropDraft(localStorage, useStore.getState().sessionId);
      } else {
        sessionDraftsRef.current.delete(state.sessionId);
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  /* @ 引用菜单：按光标前文本过滤 */
  function onInput(event: React.FormEvent<HTMLDivElement>) {
    if ((event.nativeEvent as InputEvent).isComposing) return;
    const textBefore = inputRef.current?.textBeforeCaret?.() || '';
    referenceRequest.current += 1;
    const atMatch = textBefore.match(/(^|\s)@([^\s@\uFFFC]*)$/);
    if (atMatch) {
      openRefMenu(atMatch[2]);
      return;
    }
    const slashMatch = textBefore.match(/(^|\s)\/([\w-]*)$/);
    if (slashMatch) {
      // 签名是 filterQuickCommands(query, commands)：参数写反会让命令菜单永远是空的
      const commands = filterQuickCommands(slashMatch[2], useStore.getState().quickCommands || []);
      setSlashMenu(commands.length ? { items: commands, query: slashMatch[2] } : null);
      setRefMenu(null);
      setMenuIndex(0);
      return;
    }
    setRefMenu(null);
    setSlashMenu(null);
  }

  async function openRefMenu(query: string) {
    const state = useStore.getState();
    const owner = state.sessionId;
    const workspace = state.workspace || state.draftSession?.workspace;
    const key = owner || workspace ? `${owner || 'draft'}:${workspace || ''}` : '';
    const request = ++referenceRequest.current;
    setSlashMenu(null);
    setMenuIndex(0);
    if (!key) {
      setRefMenu({ items: [], message: '先选择工作区，再引用文件' });
      return;
    }
    setRefMenu({ items: [], loading: true });
    try {
      let items = state.fileIndex.key === key ? state.fileIndex.items : null;
      if (!items) {
        const data = owner ? await api.filesFlat(owner) : await api.workspaceFiles(workspace || '');
        items = data?.files || (Array.isArray(data) ? data : []);
        if (request !== referenceRequest.current) return;
        useStore.setState({ fileIndex: { items, key, sessionId: owner, loading: false } });
      }
      if (request === referenceRequest.current) setRefMenu({ items: rankFileMatches(items, query).slice(0, 8) });
    } catch {
      if (request === referenceRequest.current) setRefMenu({ items: [], message: '读取文件失败，请重新输入 @ 重试' });
    }
  }

  function pickRef(path: string) {
    inputRef.current?.insertMention(path);
    inputRef.current?.focus();
    referenceRequest.current += 1;
    setRefMenu(null);
  }

  function pickSlash(command: QuickCommand) {
    const expansion = 'skill' in command
      ? `${command.name.startsWith('/') ? command.name : '/' + command.name} `
      : expandQuickCommand(command, '');
    if (expansion) inputRef.current?.replaceCommand(expansion);
    setSlashMenu(null);
    inputRef.current?.focus();
  }

  function clearSuggestions() {
    referenceRequest.current += 1;
    setRefMenu(null);
    setSlashMenu(null);
  }

  function toggleToolbarPanel(panel: NonNullable<typeof toolbarPanel>) {
    clearSuggestions();
    setToolbarPanel(current => current === panel ? null : panel);
  }

  useEffect(() => {
    clearSuggestions();
    setToolbarPanel(null);
  }, [sessionId, sessionEpoch, workspace]);

  useLayoutEffect(() => {
    if (permOpen) permMenuRef.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus({ preventScroll: true });
  }, [permOpen]);

  const suggestionItems = refMenu?.items || slashMenu?.items || [];
  const suggestionsOpen = Boolean(refMenu || slashMenu);
  useEffect(() => {
    if (!toolbarPanel) return;
    const key = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.isComposing || event.keyCode === 229 || event.defaultPrevented) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      setToolbarPanel(null);
      document.getElementById(toolbarPanel + '-chip')?.focus({ preventScroll: true });
    };
    const owned = '#' + toolbarPanel + '-chip, #' + toolbarPanel + '-menu';
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

  /* 方向键只滚动候选列表，保持消息区和输入框的位置。 */
  useEffect(() => {
    if (!refMenu && !slashMenu) {
      return;
    }
    const menu = menuRef.current;
    const item = menu?.querySelector('[aria-selected="true"]');
    if (!menu || !item) return;
    const bounds = menu.getBoundingClientRect(), option = item.getBoundingClientRect();
    if (option.top < bounds.top) menu.scrollTop += option.top - bounds.top;
    if (option.bottom > bounds.bottom) menu.scrollTop += option.bottom - bounds.bottom;
  }, [menuIndex, refMenu, slashMenu]);

  /** ↑/↓ 翻本会话发过的输入；多行草稿靠 caretOnEdgeLine 判断，只在首/末行接管。 */
  function handleHistoryKey(event: React.KeyboardEvent<HTMLDivElement>) {
    const state = useStore.getState();
    const input = inputRef.current;
    if (!state.inputHistory.length || !input || input.hasChips?.()) {
      return false;
    }
    const up = event.key === 'ArrowUp';
    if (!input.caretOnEdgeLine?.(up ? 'first' : 'last')) {
      return false;
    }
    const browsing = state.inputHistoryIndex >= 0;
    if (!browsing && !up) {
      return false;
    }
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
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return;
    }
    if (suggestionsOpen) {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        clearSuggestions();
        return;
      }
      if (refMenu?.loading && event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        return;
      }
      if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && suggestionItems.length) {
        event.preventDefault();
        const delta = event.key === 'ArrowDown' ? 1 : -1;
        setMenuIndex(index => (index + delta + suggestionItems.length) % suggestionItems.length);
        return;
      }
      if ((event.key === 'Enter' || event.key === 'Tab') && !event.shiftKey && suggestionItems.length) {
        event.preventDefault();
        const item = suggestionItems[Math.min(menuIndex, suggestionItems.length - 1)];
        if (typeof item === 'string') pickRef(item);
        else pickSlash(item);
        return;
      }
    }
    if ((event.key === 'ArrowUp' || event.key === 'ArrowDown') && !event.shiftKey && !event.metaKey && !event.ctrlKey && !event.altKey) {
      if (handleHistoryKey(event)) {
        return;
      }
    }
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      submit();
    }
  }

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
        <ApprovalDock />
        <ResumeBar />
        <MessageQueue />
        <AgentDock />
      </div>
      <form
        id="composer"
        className={`composer${dragging ? ' is-dragging' : ''}`}
        onSubmit={(event) => { event.preventDefault(); submit(); }}
        onDragEnter={(event) => {
          if (!Array.from(event.dataTransfer?.types || []).some(type => ['Files', 'text/uri-list', 'x-special/gnome-copied-files'].includes(type))) return;
          event.preventDefault();
          dragDepthRef.current += 1;
          setDragging(true);
        }}
        onDragLeave={() => {
          dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
          if (!dragDepthRef.current) setDragging(false);
        }}
        onDragOver={(event) => {
          if (!Array.from(event.dataTransfer?.types || []).some(type => ['Files', 'text/uri-list', 'x-special/gnome-copied-files'].includes(type))) return;
          event.preventDefault();
          event.dataTransfer.dropEffect = 'copy';
        }}
        onDrop={(event) => {
          dragDepthRef.current = 0;
          setDragging(false);
          receiveFiles(event, event.dataTransfer);
        }}
        onPaste={(event) => receiveFiles(event, event.clipboardData)}
      >
        {(pendingFiles > 0 || dragging) && <div className="attachment-status" role="status">
          {dragging ? '松开以添加文件' : `正在读取 ${pendingFiles} 个附件…`}
        </div>}
        <div id="input-flow" className="input-flow" contentEditable={!submitting}
          role="textbox" aria-label="消息输入框" aria-multiline="true" aria-busy={pendingFiles > 0 || submitting}
          aria-readonly={submitting || undefined} tabIndex={0} aria-autocomplete="list" aria-controls={suggestionItems.length ? 'composer-suggestions' : undefined}
          aria-activedescendant={suggestionItems.length ? 'composer-option-' + menuIndex : undefined}
          ref={flowRef}
          data-placeholder="描述任务，@ 引用文件，/ 快捷指令"
          onInput={onInput}
          onCompositionEnd={onInput}
          onKeyDown={onComposerKeydown}
          onBlur={clearSuggestions}
        />
        {suggestionsOpen && (
          <div className="composer-suggestions composer-flyout" ref={menuRef}>
            {refMenu && !refMenu.items.length
              ? <div className="ref-empty" role="status">{refMenu.loading ? '正在读取文件…' : refMenu.message || '没有匹配的文件'}</div>
              : <div id="composer-suggestions" role="listbox" aria-label={refMenu ? '文件引用' : '快捷指令'}>
                {suggestionItems.map((item, index) => (
                  <Button key={typeof item === 'string' ? item : item.name}
                    id={'composer-option-' + index}
                    label={typeof item === 'string' ? item : item.name + (item.hint || item.label ? ' · ' + (item.hint || item.label) : '')}
                    className="ref-item" size="sm" variant="ghost" width="100%"
                    role="option" aria-selected={index === menuIndex} tabIndex={-1}
                    onMouseDown={event => event.preventDefault()} onMouseEnter={() => setMenuIndex(index)}
                    onClick={() => typeof item === 'string' ? pickRef(item) : pickSlash(item)}>
                    <span className="composer-option-label">{typeof item === 'string' ? item
                      : <><strong>{item.name.startsWith('/') ? item.name : '/' + item.name}</strong> · {item.hint || item.label}</>}</span>
                  </Button>
                ))}
              </div>}
          </div>
        )}
        <div className="composer-bar">
          <input ref={fileRef} type="file" multiple hidden onChange={(event) => { addFiles(event.target.files); event.target.value = ''; }} />
          <div className="composer-left">
            <span title="添加文件或图片，也可以拖入或粘贴">
              <IconButton id="attach-button" className="composer-icon" variant="ghost"
                label="添加文件或图片" isDisabled={submitting} onClick={() => fileRef.current?.click()}
                icon={<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13.2 7.4 8.1 12.5a3.1 3.1 0 0 1-4.4-4.4l5.3-5.3a2.1 2.1 0 0 1 3 3L6.9 11a1.1 1.1 0 0 1-1.6-1.6l4.8-4.8" stroke="currentColor" strokeWidth="1.3" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>} />
            </span>
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
              <Button id="model-chip" className="composer-choice composer-model" size="sm" variant="ghost"
                label={currentModel ? '切换模型，当前 ' + currentModel : '选择模型'}
                aria-haspopup="dialog" aria-expanded={modelOpen} aria-controls={modelOpen ? 'model-menu' : undefined}
                endContent={<Icon name="chevron" className="composer-chevron" />} onClick={() => toggleToolbarPanel('model')}>
                <span className="model-chip-name" title={currentModel || undefined}>{currentModel || '选择模型'}</span>
              </Button>
              {modelOpen && (
                <ModelPicker key={currentEndpoint} models={modelOptions} initialModel={currentModel} initialEndpoint={currentEndpoint}
                  onClose={() => { setToolbarPanel(null); document.getElementById('model-chip')?.focus(); }} />
              )}
            </div>
          </div>
          <div className="composer-actions">
            <div className="perm-picker composer-context-picker">
              <span title="上下文与请求用量"><Button id="context-chip" ref={contextTriggerRef} className={'composer-choice composer-context level-' + usageLevel(usagePercent(contextStatus))}
                size="sm" variant="ghost" label={'上下文用量 ' + contextPercent(contextStatus) + (runUsage?.details ? ' · 缓存命中 ' + formatCache(runUsage.details) : '')}
                aria-haspopup="dialog" aria-expanded={contextOpen} aria-controls={contextOpen ? 'context-menu' : undefined}
                icon={<svg className="context-ring" viewBox="0 0 20 20" aria-hidden="true"><circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" opacity=".25" strokeWidth="2" /><circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" strokeWidth="2" pathLength="100" strokeDasharray={usagePercent(contextStatus) + ' 100'} transform="rotate(-90 10 10)" /></svg>}
                onClick={() => toggleToolbarPanel('context')}>{contextPercent(contextStatus)}</Button></span>
              {contextOpen && <ContextUsagePanel status={contextStatus} compacting={compacting}
                report={latestContextReport} reports={requestReports} usage={runUsage}
                onClose={() => { setToolbarPanel(null); contextTriggerRef.current?.focus({ preventScroll: true }); }}
                onCompact={() => useStore.getState().compactContext()}
                onSettings={() => { setToolbarPanel(null); useStore.getState().setUi({ settingsOpen: true, settingsSection: 'model' }); }} />}
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
            <span title={sendAction.title}>
              <IconButton id="send" className="composer-primary" variant="primary"
                type={sendAction.stop ? 'button' : 'submit'} label={sendAction.ariaLabel}
                aria-keyshortcuts={sendAction.stop ? 'Escape' : 'Enter'}
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
    </div>
  );
}

function contextPercent(status: ContextStatus | null) {
  if (!status?.limitTokens) {
    return '—';
  }
  return `${usagePercent(status)}%`;
}

function ResumeBar() {
  const running = useStore((state) => state.running);
  const lastOutcome = useStore((state) => state.lastOutcome);
  const rows = useStore((state) => state.thread?.rows || []);
  const resumable = rows.some((row) => row.type === 'result' && row.resumable && !row.continued);
  const bar = resumeBar({ running, needInput: lastOutcome === 'NEED_INPUT', resumable });
  const barKey = bar ? bar.label : '';
  useEffect(() => {
    document.body.classList.toggle('has-resume-bar', Boolean(barKey));
    return () => document.body.classList.remove('has-resume-bar');
  }, [barKey]);
  if (!bar) {
    return null;
  }
  return (
    <div id="task-resume-controls" className="task-live-controls" role="status">
      <span>{bar.text}</span>
      <Button id="task-resume" size="sm" label={bar.label} onClick={() => useStore.getState().continueRun()} />
    </div>
  );
}

/** 底部审批 dock：需要人工批准的工具调用。 */
function ApprovalDock() {
  const pending = useStore((state) => state.pendingApprovals);
  const resolve = useStore((state) => state.resolveApproval);
  const entries = Object.values(pending || {});
  if (!entries.length) {
    return null;
  }
  return (
    <section id="approval-dock" className="approval-dock" aria-label="待审批操作">
      {entries.map(item => (
        <article key={item.callId} className="approval-card" aria-busy={item.busy}>
          <h3 className="approval-title"><StatusDot variant="warning" label="待审批" aria-hidden="true" />需要你批准：{item.name}</h3>
          <pre className="approval-args" tabIndex={0} aria-label="操作参数">{item.args}</pre>
          <div className="approval-actions">
            {item.busy && <span role="status">正在确认审批…</span>}
            <Button label="拒绝" size="sm" variant="ghost" isDisabled={item.busy} onClick={() => resolve(item.callId, false)} />
            <Button label="批准" size="sm" isDisabled={item.busy} onClick={() => resolve(item.callId, true)} />
          </div>
        </article>
      ))}
    </section>
  );
}

/** 子智能体 dock：运行中的子智能体一览。 */
function AgentDock() {
  const agents = useStore((state) => state.agents);
  const open = useStore((state) => state.ui.agentsDockOpen);
  const list = Object.values(agents || {});
  if (!list.length) {
    return null;
  }
  const summary = summarizeAgents(list);
  // 已结束的子任务留在线程中；只有用户点顶部入口时才重开底部列表。
  if (!open && !summary.running) return null;
  const toggle = () => {
    useStore.getState().setUi({ agentsDockOpen: !open });
    if (open && !summary.running) document.getElementById('agents-chip')?.focus();
  };
  return (
    <div id="agents-dock" className="agents-dock">
      <button type="button" className="agents-dock-head" aria-expanded={open} aria-controls="agents-dock-list" onClick={toggle}>
        <span>{summary.text}</span>
        <span className="queue-toggle">{open ? '收起' : '展开'}</span>
      </button>
      {open && (
        <ul id="agents-dock-list" className="agents-dock-list">
          {list.map((agent) => (
            <li key={agent.id}>
              <StatusDot variant={agent.status === 'failed' ? 'error' : agent.status === 'running' ? 'accent' : 'neutral'} label={agentStatusLabel(agent.status)} aria-hidden="true" />
              <span className="agent-name" title={agent.task}>{agent.name || agent.task || agent.id}</span>
              <span className="agent-state">{agentStatusLabel(agent.status)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
