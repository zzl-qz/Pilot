import { formatCache } from '../lib/request-usage.js';
import { ContextUsagePanel } from './RequestUsage.jsx';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { Icon } from './Icon.jsx';
import { VoiceInput } from './VoiceInput.jsx';
import { createComposerInput } from '../lib/composer-input.js';
import { resolveComposerAction } from '../lib/composer-state.js';
import { rankFileMatches } from '../lib/mention.js';
import { filterQuickCommands, expandQuickCommand } from '../lib/commands.js';
import { stepHistory } from '../lib/input-history.js';
import { readTransferFiles, hasFileTransferHint } from '../lib/clipboard.js';
import { readAttachmentFile } from '../lib/attachment-input.js';
import { checkIncomingFile } from '../lib/attachments.js';
import { writeDraft, readDraft, dropDraft } from '../lib/drafts.js';
import { splitAttachmentMarkers, joinAttachmentMarkers } from '../lib/attachments.js';
import { queueHeader, resumeBar } from '../lib/queue.js';
import { PERMISSION_MODES, permissionLabel, permissionHint } from '../lib/permission.js';
import { ModelOptions, useModelDiscovery } from './ModelDiscovery.jsx';
import { ModelSaveFeedback, useModelForm } from './ModelForm.jsx';
import { usageLevel, usagePercent } from '../lib/context-usage.js';
import { agentStatusLabel, summarizeAgents } from '../lib/agents.js';

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
  const queue = useStore((state) => state.queue);
  const steering = useStore((state) => state.steering);
  const pausing = useStore((state) => state.pausing);
  const cancelling = useStore((state) => state.cancelling);
  const permissionMode = useStore((state) => state.permissionMode);
  const modelOptions = useStore((state) => state.modelOptions);
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

  const flowRef = useRef(null);
  const referenceRequest = useRef(0);
  const inputRef = useRef(null);
  const fileRef = useRef(null);
  const [sendAction, setSendAction] = useState({ mode: 'send', stop: false, disabled: true, label: '发送', ariaLabel: '发送任务', title: '发送任务 (Enter)' });
  const [permOpen, setPermOpen] = useState(false);
  const [modelOpen, setModelOpen] = useState(false);
  const [contextOpen, setContextOpen] = useState(false);
  const [refMenu, setRefMenu] = useState(null);
  const [slashMenu, setSlashMenu] = useState(null);
  const [menuIndex, setMenuIndex] = useState(0);
  const menuRef = useRef(null);
  const draftFilesRef = useRef(new Map());
  const fileQueueRef = useRef(Promise.resolve());
  const pendingFilesRef = useRef(0);
  const submittingRef = useRef(false);
  const submittingEpoch = useRef(null);
  const [pendingFiles, setPendingFiles] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [voiceBusy, setVoiceBusy] = useState(false);
  const voiceBusyRef = useRef(false);
  const [dragging, setDragging] = useState(false);
  const dragDepthRef = useRef(0);
  const sessionDraftsRef = useRef(new Map());
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
      queuePaused: state.queueHeld
    });
  }

  useEffect(() => {
    setSendAction(sendState());
  }, [running, queueHeld, lastOutcome, pendingInputs.length, steering, pausing, cancelling, editTarget]);

  /* 附件上传：选择 / 拖放 / 粘贴共用 */
  function addFiles(fileList) {
    if (submittingRef.current) return;
    const files = Array.from(fileList || []);
    const owner = useStore.getState().sessionId;
    const epoch = useStore.getState().sessionEpoch;
    pendingFilesRef.current += files.length;
    setPendingFiles(pendingFilesRef.current);
    fileQueueRef.current = fileQueueRef.current.then(async () => {
      for (const file of files) {
        try {
          if ((useStore.getState().sessionId !== owner || useStore.getState().sessionEpoch !== epoch)) continue;
          const existing = (inputRef.current?.attachmentIds() || [])
            .map((id) => draftFilesRef.current.get(id)).filter(Boolean);
          const result = checkIncomingFile(existing, file);
          if (!result.ok) {
            if (!result.silent) useStore.getState().showNotice(result.reason);
            continue;
          }
          const item = await readAttachmentFile(file);
          if ((useStore.getState().sessionId !== owner || useStore.getState().sessionEpoch !== epoch) || !inputRef.current) continue;
          draftFilesRef.current.set(item.id, item);
          inputRef.current.insertAttachments([item]);
        } catch (error) {
          useStore.getState().showNotice('读取附件失败：' + error.message);
        } finally {
          pendingFilesRef.current -= 1;
          setPendingFiles(pendingFilesRef.current);
        }
      }
    });
  }

  async function submit() {
    const input = inputRef.current;
    if (voiceBusy || pendingFilesRef.current || submittingRef.current || sendState().disabled) return;
    if (!input) {
      return;
    }
    const { text, imagePositions } = input.getDraft();
    const attachments = input.attachmentIds().map((id) => draftFilesRef.current.get(id)).filter(Boolean);
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
  function onInput() {
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

  async function openRefMenu(query) {
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
        const data = owner ? await api.filesFlat(owner) : await api.workspaceFiles(workspace);
        items = data?.files || (Array.isArray(data) ? data : []);
        if (request !== referenceRequest.current) return;
        useStore.setState({ fileIndex: { items, key, sessionId: owner, loading: false } });
      }
      if (request === referenceRequest.current) setRefMenu({ items: rankFileMatches(items, query).slice(0, 8) });
    } catch {
      if (request === referenceRequest.current) setRefMenu({ items: [], message: '读取文件失败，请重新输入 @ 重试' });
    }
  }

  function pickRef(path) {
    inputRef.current?.insertMention(path);
    inputRef.current?.focus();
    referenceRequest.current += 1;
    setRefMenu(null);
  }

  function pickSlash(command) {
    const expansion = command.skill
      ? `${command.name.startsWith('/') ? command.name : '/' + command.name} `
      : expandQuickCommand(command, '');
    if (expansion) inputRef.current?.replaceCommand(expansion);
    setSlashMenu(null);
    inputRef.current?.focus();
  }

  useEffect(() => {
    referenceRequest.current += 1;
    setRefMenu(null); setSlashMenu(null);
    setPermOpen(false); setModelOpen(false); setContextOpen(false);
  }, [sessionId, sessionEpoch, workspace]);

  useEffect(() => {
    if (!permOpen && !modelOpen && !contextOpen && !refMenu && !slashMenu) return;
    const close = () => {
      referenceRequest.current += 1;
      setPermOpen(false); setModelOpen(false); setContextOpen(false);
      setRefMenu(null); setSlashMenu(null);
    };
    const key = (event) => {
      if (permOpen && ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        const items = [...document.querySelectorAll('#perm-menu button')];
        if (items.length) {
          event.preventDefault();
          const index = items.indexOf(document.activeElement);
          const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length;
          items[next].focus();
        }
        return;
      }
      if (event.key !== 'Escape' || event.isComposing) return;
      event.preventDefault(); event.stopImmediatePropagation();
      const trigger = permOpen ? 'perm-chip' : modelOpen ? 'model-chip' : contextOpen ? 'context-chip' : 'input-flow';
      close(); document.getElementById(trigger)?.focus();
    };
    const outside = (event) => {
      if (!event.target.closest('.perm-picker, .ref-menu, #input-flow')) close();
    };
    document.addEventListener('keydown', key, true);
    document.addEventListener('pointerdown', outside, true);
    return () => {
      document.removeEventListener('keydown', key, true);
      document.removeEventListener('pointerdown', outside, true);
    };
  }, [permOpen, modelOpen, contextOpen, Boolean(refMenu), Boolean(slashMenu)]);

  /* 菜单只有 250px 高，选中的那项滚出可视区就相当于没选中 */
  useEffect(() => {
    if (!refMenu && !slashMenu) {
      return;
    }
    menuRef.current?.querySelector('.ref-item.is-on')?.scrollIntoView({ block: 'nearest' });
  }, [menuIndex, refMenu, slashMenu]);

  /** ↑/↓ 翻本会话发过的输入；多行草稿靠 caretOnEdgeLine 判断，只在首/末行接管。 */
  function handleHistoryKey(event) {
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

  function onComposerKeydown(event) {
    // 中文输入法组字期间的回车不是发送
    if (event.isComposing || event.nativeEvent?.isComposing || event.keyCode === 229) {
      return;
    }
    const items = refMenu ? (refMenu.items || []) : slashMenu ? (slashMenu.items || []) : [];
    if (refMenu || slashMenu) {
      if (refMenu?.loading && (event.key === 'Enter' || event.key === 'Tab')) { event.preventDefault(); return; }
      if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && items.length) {
        event.preventDefault();
        const delta = event.key === 'ArrowDown' ? 1 : -1;
        setMenuIndex((current) => (current + delta + items.length) % items.length);
        return;
      }
      if ((event.key === 'Enter' || event.key === 'Tab') && !event.shiftKey && items.length) {
        event.preventDefault();
        const picked = items[Math.min(menuIndex, items.length - 1)];
        if (refMenu) {
          pickRef(picked.path || picked);
        } else {
          pickSlash(picked);
        }
        return;
      }
      if (event.key === 'Escape') {
        // 关菜单的这一次 Esc 不往下传：否则全局监听会顺手把正在跑的任务取消掉
        event.preventDefault();
        event.stopPropagation();
        setRefMenu(null);
        setSlashMenu(null);
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
    if (event.key === 'Escape') {
      setRefMenu(null);
      setSlashMenu(null);
    }
  }

  return (
    <div className="composer-wrap">
      {editTarget != null && <div className="send-mode-label">正在编辑历史消息 · 发送将重新执行<button type="button" className="btn" onClick={() => useStore.setState({ _editTarget: null })}>作为新消息发送</button></div>}
      <ResumeBar />
      <ApprovalDock />
      <MessageQueue />
      <AgentDock />
      {refMenu && (
        <div className="ref-menu" role="listbox" ref={menuRef}>
          {refMenu.loading || (refMenu.items || []).length === 0
            ? <div className="ref-empty" role="status">{refMenu.loading ? '正在读取文件…' : refMenu.message || '没有匹配的文件'}</div>
            : refMenu.items.map((item, index) => (
              <button
                key={item.path || item}
                type="button"
                className={`ref-item${index === menuIndex ? ' is-on' : ''}`}
                onMouseDown={(event) => event.preventDefault()}
                role="option" aria-selected={index === menuIndex}
              onMouseEnter={() => setMenuIndex(index)}
                onClick={() => pickRef(item.path || item)}
              >
                {item.path || item}
              </button>
            ))}
        </div>
      )}
      {slashMenu && (
        <div className="ref-menu" role="listbox" ref={menuRef}>
          {slashMenu.items.map((command, index) => (
            <button
              key={command.name}
              type="button"
              className={`ref-item${index === menuIndex ? ' is-on' : ''}`}
              onMouseDown={(event) => event.preventDefault()}
              role="option" aria-selected={index === menuIndex}
              onMouseEnter={() => setMenuIndex(index)}
              onClick={() => pickSlash(command)}
            >
              <b>{command.name.startsWith('/') ? command.name : `/${command.name}`}</b> — {command.hint || command.label || ''}
            </button>
          ))}
        </div>
      )}
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
          const files = readTransferFiles(event.dataTransfer);
          dragDepthRef.current = 0;
          setDragging(false);
          if (files.length) { event.preventDefault(); addFiles(files); }
          else if (hasFileTransferHint(event.clipboardData || event.dataTransfer)) {
            event.preventDefault();
            useStore.getState().showNotice('当前环境未提供文件内容，请拖入文件或使用“添加文件或图片”。');
          }
        }}
        onPaste={(event) => {
          const files = readTransferFiles(event.clipboardData);
          if (files.length) { event.preventDefault(); addFiles(files); }
          else if (hasFileTransferHint(event.clipboardData || event.dataTransfer)) {
            event.preventDefault();
            useStore.getState().showNotice('当前环境未提供文件内容，请拖入文件或使用“添加文件或图片”。');
          }
        }}
      >
        {(pendingFiles > 0 || dragging) && <div className="attachment-status" role="status">
          {dragging ? '松开以添加文件' : `正在读取 ${pendingFiles} 个附件…`}
        </div>}
        <div id="input-flow" className="input-flow" contentEditable={!submitting}
          role="textbox" aria-label="消息输入框" aria-multiline="true" aria-busy={pendingFiles > 0 || submitting}
          ref={flowRef}
          data-placeholder="描述任务，@ 引用文件，/ 使用技能，附件直接拖进来"
          onInput={onInput}
          onKeyDown={onComposerKeydown}
        />
        <div className="composer-bar">
          <input ref={fileRef} type="file" multiple hidden onChange={(event) => { addFiles(event.target.files); event.target.value = ''; }} />
          <span className="composer-left">
            <button
              type="button"
              className="attach-button"
              id="attach-button"
              disabled={submitting}
              aria-label="添加文件或图片"
              title="添加文件或图片（也可以拖入或粘贴）"
              onClick={() => fileRef.current?.click()}
            >
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13.2 7.4 8.1 12.5a3.1 3.1 0 0 1-4.4-4.4l5.3-5.3a2.1 2.1 0 0 1 3 3L6.9 11a1.1 1.1 0 0 1-1.6-1.6l4.8-4.8" stroke="currentColor" strokeWidth="1.3" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
            </button>
            <span className="composer-chips">
              <span className="perm-picker">
                <button
                  type="button"
                  className="perm-chip"
                  id="perm-chip"
                  aria-haspopup="menu"
                  aria-expanded={permOpen}
                  title={`${permissionHint(permissionMode)} · 点击切换`}
                  onClick={() => { setPermOpen(!permOpen); setModelOpen(false); setContextOpen(false); }}
                >
                  {permissionLabel(permissionMode)}
                </button>
                {permOpen && (
                  <div id="perm-menu" className="perm-menu" role="menu" aria-label="权限档位">
                    <p className="menu-head">权限</p>
                    {PERMISSION_MODES.map((item) => {
                      const active = permissionMode === item.mode;
                      return (
                        <button
                          key={item.mode}
                          type="button"
                          role="menuitemradio"
                          aria-checked={active}
                          className={`perm-option${active ? ' is-active' : ''}`}
                          title={item.hint}
                          onClick={() => { useStore.getState().savePermissionMode(item.mode); setPermOpen(false); }}
                        >
                          <span className="perm-option-icon" aria-hidden="true"><PermissionIcon mode={item.mode} /></span>
                          <span className="perm-option-text">
                            <strong>{item.label}</strong>
                            <span className="perm-option-hint">{item.hint}</span>
                          </span>
                          <span className="perm-option-code">{item.code}</span>
                        </button>
                      );
                    })}
                  </div>
                )}
              </span>
              <span className="perm-picker">
                <button
                  type="button"
                  className="perm-chip model-chip"
                  id="model-chip"
                  aria-haspopup="dialog"
                  aria-expanded={modelOpen}
                  title={currentModel ? `当前模型：${currentModel} · 点击切换` : '选择模型'}
                  onClick={() => { setModelOpen(!modelOpen); setPermOpen(false); setContextOpen(false); }}
                >
                  {currentModel || '模型'}
                </button>
                {modelOpen && (
                  <ModelMenu
                    onClose={() => setModelOpen(false)}
                    models={modelOptions}
                    initialModel={currentModel}
                    initialEndpoint={currentEndpoint}
                  />
                )}
              </span>
              <span className="perm-picker context-picker">
                <button
                  type="button"
                  className={`perm-chip context-chip level-${usageLevel(usagePercent(contextStatus))}`}
                  id="context-chip"
                  aria-haspopup="dialog"
                  aria-expanded={contextOpen}
                  title={`上下文已用 ${contextPercent(contextStatus)} · 点击查看构成与压缩`}
                  onClick={() => { setContextOpen(!contextOpen); setPermOpen(false); setModelOpen(false); useStore.getState().refreshContext(); }}
                >
                  <svg className="context-ring" viewBox="0 0 20 20" aria-hidden="true"><circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" opacity=".25" strokeWidth="2" /><circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" strokeWidth="2" pathLength="100" strokeDasharray={`${usagePercent(contextStatus)} 100`} transform="rotate(-90 10 10)" /></svg>
                  上下文 · {contextPercent(contextStatus)}{runUsage?.details && <span className="context-cache-label"> · 命中 {formatCache(runUsage.details)}</span>}
                </button>
                {contextOpen && <ContextUsagePanel status={contextStatus} compacting={compacting}
                  report={latestContextReport} reports={requestReports} usage={runUsage}
                  onClose={() => { setContextOpen(false); document.getElementById('context-chip')?.focus(); }}
                  onCompact={() => useStore.getState().compactContext()}
                  onSettings={() => { setContextOpen(false); useStore.getState().setUi({ settingsOpen: true, settingsSection: 'model' }); }} /> }
              </span>
            </span>
          </span>
          <div className="composer-actions">
            <button type="button" className="icon-btn schedule-compose" aria-label="定时执行" title="安排稍后执行" disabled={pendingFiles > 0 || submitting}
              onClick={() => {
                const state = useStore.getState();
                if (state.composerApi?.attachmentIds()?.length) { state.showNotice('定时入口暂不携带附件。请将文件放入工作区，在目标中注明相对路径；当前草稿与附件已保留。'); return; }
                state.setUi({ schedulesOpen: true, scheduleDraft: state.composerApi?.getText() || '' });
              }}>
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" aria-hidden="true"><circle cx="8" cy="8" r="5.5"/><path d="M8 4.5V8l2.5 1.5"/></svg>
            </button>
            <VoiceInput ownerKey={`${sessionEpoch}:${sessionId || ''}:${workspace}`} disabled={submitting}
              onBusy={value => { voiceBusyRef.current = value; setVoiceBusy(value); }} onText={(text) => { const input = inputRef.current; input?.appendText((input.getText().trim() ? '\n' : '') + text); input?.focus(); }} />

            <button
              type={sendAction.stop ? "button" : "submit"}
              onClick={sendAction.stop ? () => useStore.getState().cancelRun() : undefined}
              className={`send${sendAction.stop ? ' is-running' : ''}`}
              id="send"
              disabled={sendAction.disabled || (!sendAction.stop && (pendingFiles > 0 || submitting || voiceBusy))}
              aria-label={sendAction.ariaLabel}
              title={sendAction.title}
            >
              <svg className="icon-send" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 12.5V3.8M4.6 7.2 8 3.8l3.4 3.4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" fill="none" /></svg>
              <svg className="icon-stop" viewBox="0 0 16 16" aria-hidden="true"><rect x="4.6" y="4.6" width="6.8" height="6.8" rx="1.6" fill="currentColor" /></svg>
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}

function contextPercent(status) {
  if (!status?.limitTokens) {
    return '—';
  }
  return `${usagePercent(status)}%`;
}

/** 权限档位的图标：放行的速度感 / 改文件 / 逐次确认 / 只看。 */
function PermissionIcon({ mode }) {
  if (mode === 'AUTO') {
    return <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M9 1.8 3.6 9h3.3l-.9 5.2L12.4 7H9.1z" stroke="currentColor" strokeWidth="1.2" fill="none" strokeLinejoin="round" /></svg>;
  }
  if (mode === 'AUTO_EDIT') {
    return <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10.6 2.9 13 5.3 6.4 11.9 3.4 12.6l.7-3zM9.4 4.1l2.4 2.4" stroke="currentColor" strokeWidth="1.2" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>;
  }
  if (mode === 'ASK') {
    return <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="5.6" stroke="currentColor" strokeWidth="1.2" fill="none" /><path d="M6.4 6.2a1.7 1.7 0 1 1 2.4 1.6c-.5.3-.8.6-.8 1.2" stroke="currentColor" strokeWidth="1.2" fill="none" strokeLinecap="round" /><circle cx="8" cy="11" r=".8" fill="currentColor" /></svg>;
  }
  return <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M1.6 8S4 3.9 8 3.9 14.4 8 14.4 8 12 12.1 8 12.1 1.6 8 1.6 8Z" stroke="currentColor" strokeWidth="1.2" fill="none" strokeLinejoin="round" /><circle cx="8" cy="8" r="1.9" stroke="currentColor" strokeWidth="1.2" fill="none" /></svg>;
}

function CheckIcon() {
  return <svg className="menu-check" viewBox="0 0 16 16" aria-hidden="true"><path d="m3.5 8.4 3 3 6-6.4" stroke="currentColor" strokeWidth="1.7" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>;
}

function ModelMenu({ onClose, models, initialModel = '', initialEndpoint = '' }) {
  // 打开菜单时预填当前配置：之前是空白表单，用户会以为模型没配
  const { name, endpoint, key, setName, setEndpoint, setKey, saving, feedback, save } = useModelForm({ model: initialModel, endpoint: initialEndpoint });
  const discovery = useModelDiscovery({ endpoint, apiKey: key, initialModels: models, initialEndpoint });
  return (
    <div className="chip-menu model-menu" id="model-menu" role="dialog" aria-label="模型设置">
      <p className="menu-head">模型</p>
      <label>模型名称<input disabled={saving} value={name} onChange={(event) => setName(event.target.value)} placeholder="如 deepseek-chat" /></label>
      <label>端点<input disabled={saving} value={endpoint} onChange={(event) => setEndpoint(event.target.value)} placeholder="模型服务地址，以 /v1 结尾" /></label>
      <label>API Key<input disabled={saving} type="password" value={key} onChange={(event) => setKey(event.target.value)} placeholder="不填则沿用已保存的" /></label>
      <div className="chip-menu-actions">
        <button type="button" className="btn" disabled={saving || discovery.probing} aria-busy={discovery.probing} onClick={discovery.probe}>{discovery.probing ? '探测中…' : '探测模型'}</button>
        <button type="button" className="btn btn-primary" disabled={saving || !name.trim()} aria-busy={saving} onClick={async () => { if (await save()) onClose(); }}>{saving ? '保存中…' : '保存'}</button>
      </div>
      <ModelSaveFeedback feedback={feedback} />
      {discovery.hint && <p className="chip-hint" role={discovery.error ? 'alert' : 'status'}>{discovery.hint}</p>}
      <ModelOptions models={discovery.models} value={name} onSelect={setName} disabled={saving} discovered={discovery.discovered} />
    </div>
  );
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
    <div id="task-resume-controls" className="task-live-controls">
      <span>{bar.text}</span>
      <button id="task-resume" type="button" onClick={() => useStore.getState().continueRun()}>{bar.label}</button>
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
    <div id="approval-dock" className="approval-dock">
      {entries.map((item) => (
        <div key={item.callId} className="approval-card">
          <p className="approval-title">需要你批准：{item.name}</p>
          <pre className="approval-args">{item.args}</pre>
          <div className="approval-actions">
            <button type="button" className="btn btn-primary" disabled={item.busy} onClick={() => resolve(item.callId, true)}>批准</button>
            <button type="button" className="btn" disabled={item.busy} onClick={() => resolve(item.callId, false)}>拒绝</button>
          </div>
        </div>
      ))}
    </div>
  );
}

/** 排队消息紧贴输入框，所有操作跟随消息本身。 */
function MessageQueue() {
  const entries = useStore((state) => state.queueEntries);
  const queueEdit = useStore((state) => state.ui.queueEdit);
  const collapsed = useStore((state) => state.ui.queueCollapsed);
  const queueHeld = useStore((state) => state.queueHeld);
  const header = queueHeader({ count: entries.length, held: queueHeld });
  if (!header) {
    return null;
  }
  return (
    <QueueList entries={entries} queueEdit={queueEdit} collapsed={collapsed} header={header} />
  );
}

function QueueList({ entries, queueEdit, collapsed, header }) {
  const [dragFrom, setDragFrom] = useState(null);
  return (
    <div id="message-queue" className="message-queue">
      <button type="button" className="queue-head" aria-expanded={!collapsed} onClick={() => useStore.getState().setUi({ queueCollapsed: !collapsed })}>
        <span className="queue-title">{header.title}</span>
        <span className="queue-head-meta">{header.meta}</span>
        {!!entries.length && <span className="queue-toggle">{collapsed ? '展开' : '收起'}</span>}
      </button>
      {!collapsed && !!entries.length && (
        <ol className="queue-list">
          {entries.map((entry, index) => {
            const text = entry.text ?? entry.content ?? '';
            return (
              <li
                key={`${index}-${text}`}
                className={`queue-item${dragFrom === index ? ' is-dragging' : ''}`}
                onDragOver={(event) => event.preventDefault()}
                onDrop={() => {
                  if (dragFrom == null || dragFrom === index || useStore.getState().queueBusy) return;
                  useStore.getState().moveQueueItem(dragFrom, index);
                  setDragFrom(null);
                }}
              >
                {queueEdit === index ? (
                  <QueueEditor index={index} text={text} />
                ) : (
                  <QueueRow entry={entry} index={index} count={entries.length} onDragStart={() => setDragFrom(index)} onDragEnd={() => setDragFrom(null)} />
                )}
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}

function QueueRow({ entry, index, count, onDragStart, onDragEnd }) {
  const running = useStore(state => state.running);
  const busy = useStore(state => state.queueBusy || state.cancelling || state.pausing);
  const [more, setMore] = useState(false);
  const moreRef = useRef(null);
  useEffect(() => {
    if (!more) return;
    const close = event => {
      if (event.type === 'keydown' && event.key !== 'Escape') return;
      if (event.type === 'pointerdown' && moreRef.current?.contains(event.target)) return;
      if (event.type === 'keydown') { event.preventDefault(); event.stopPropagation(); moreRef.current?.querySelector('button')?.focus(); }
      setMore(false);
    };
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', close, true);
    return () => { document.removeEventListener('pointerdown', close); document.removeEventListener('keydown', close, true); };
  }, [more]);
  const text = entry.text ?? entry.content ?? '';
  const spoken = splitAttachmentMarkers(text);
  const images = Array.isArray(entry.attachments) ? entry.attachments : [];
  const files = spoken.files || [];
  const label = spoken.body || (images.length ? '图片' : text);
  return (
    <>
      <button
        type="button"
        className="queue-grip"
        draggable={!busy}
        disabled={busy}
        onDragEnd={onDragEnd}
        aria-label="拖动调整顺序"
        title="拖动调整顺序"
        onDragStart={onDragStart}
      >
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 6h8M4 10h8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
      </button>
      <div className="queue-main">
        <button type="button" className="queue-text" disabled={busy} title={label} onClick={() => useStore.getState().setUi({ queueEdit: index })}>{label}</button>
        {(images.length > 0 || files.length > 0) && (
          <span className="attach-strip">
            {images.map((image) => (
              <img key={image.url || image.name} className="queue-thumb" src={image.url} alt={image.name || '图片'} />
            ))}
            {files.map((file) => (
              <span key={file.raw || file.name} className="queue-file" title={file.name}>{file.name}</span>
            ))}
          </span>
        )}
      </div>
      <div className="queue-actions">
        {running && <button type="button" className="queue-steer" disabled={busy}
          title="立即引导当前任务" aria-label="引导这条排队消息"
          onClick={() => useStore.getState().steerQueueItem(index)}><Icon name="steer" />引导</button>}
        <button type="button" className="queue-icon" disabled={busy} title="编辑" aria-label="编辑这条排队消息"
          onClick={() => useStore.getState().setUi({ queueEdit: index })}><Icon name="edit" /></button>
        <button type="button" className="queue-icon" disabled={busy} title="删除" aria-label="删除这条排队"
          onClick={() => useStore.getState().removeQueueItem(index)}><Icon name="trash" /></button>
        {count > 1 && <span className="queue-more" ref={moreRef}>
          <button type="button" className="queue-icon" aria-label="更多排队操作" aria-expanded={more} disabled={busy}
            onClick={() => setMore(!more)}><Icon name="more" /></button>
          {more && <div className="queue-more-menu">
            <button type="button" disabled={index === 0 || busy} onClick={() => { setMore(false); useStore.getState().moveQueueItem(index, index - 1); }}>上移</button>
            <button type="button" disabled={index === count - 1 || busy} onClick={() => { setMore(false); useStore.getState().moveQueueItem(index, index + 1); }}>下移</button>
          </div>}
        </span>}
      </div>
    </>
  );
}

function QueueEditor({ index, text }) {
  const spoken = splitAttachmentMarkers(text);
  const [value, setValue] = useState(spoken.body);
  const [saving, setSaving] = useState(false);
  return (
    <div id="queue-edit" className="queue-edit" onKeyDown={event => {
      if (event.key === 'Escape' && !event.nativeEvent.isComposing) {
        event.preventDefault(); event.stopPropagation(); useStore.getState().setUi({ queueEdit: null });
      }
    }}>
      <textarea aria-label="编辑排队消息" disabled={saving} value={value} onChange={(event) => setValue(event.target.value)} rows={2} />
      <div className="queue-edit-actions">
        <button type="button" className="btn btn-primary" disabled={saving || (!value.trim() && !spoken.files.length)} onClick={async () => {
          setSaving(true);
          try {
            if (await useStore.getState().saveQueueItem(index, joinAttachmentMarkers(value, spoken.files))) useStore.getState().setUi({ queueEdit: null });
          } finally { setSaving(false); }
        }}>保存</button>
        <button type="button" className="btn" onClick={() => useStore.getState().setUi({ queueEdit: null })}>取消</button>
      </div>
    </div>
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
              <span className={`agent-status is-${agent.status}`} />
              <span className="agent-name" title={agent.task}>{agent.name || agent.task || agent.id}</span>
              <span className="agent-state">{agentStatusLabel(agent.status)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
