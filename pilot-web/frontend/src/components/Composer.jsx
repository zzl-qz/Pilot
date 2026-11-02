import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { createComposerInput } from '../lib/composer-input.js';
import { resolveComposerAction } from '../lib/composer-state.js';
import { rankFileMatches } from '../lib/mention.js';
import { filterQuickCommands, expandQuickCommand } from '../lib/commands.js';
import { stepHistory } from '../lib/input-history.js';
import { checkIncomingFile } from '../lib/attachments.js';
import { writeDraft, readDraft, dropDraft } from '../lib/drafts.js';
import { isImageAttachment } from '../lib/attachments.js';
import { PERMISSION_MODES, permissionLabel, permissionHint } from '../lib/permission.js';
import { buildModelProbe, buildModelUpdate, describeModelsFetched } from '../lib/model-settings.js';
import {
  formatTokens, remainingTokens, usageLevel, usageNote, usagePercent, usageSegments
} from '../lib/context-usage.js';

/**
 * 输入区：contenteditable 桥接旧版 composer-input（附件芯片 / @ 引用 / 粘贴纯文本），
 * 发送按钮状态机走 lib/composer-state。
 */
export function Composer() {
  const sessionId = useStore((state) => state.sessionId);
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
  const editTarget = useStore((state) => state._editTarget);

  const flowRef = useRef(null);
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
    // state 被 immer 冻结，必须走 setState，不能直接赋值
    useStore.setState({ composerApi: {
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
      inputRef.current = null;
      useStore.setState({ composerApi: null });
    };
  }, []);

  /* 切换会话 / 草稿落库成真实会话后，把对应草稿回填（没有草稿就是清空）。 */
  useEffect(() => {
    inputRef.current?.setText(readDraft(localStorage, sessionId));
  }, [sessionId]);

  function sendState() {
    const state = useStore.getState();
    const text = inputRef.current?.getText() || '';
    return resolveComposerAction({
      running: state.running,
      editing: Boolean(state.ui.queueEdit),
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
  }, [running, queueHeld, lastOutcome, pendingInputs.length]);

  /* 附件上传：选择 / 拖放 / 粘贴共用 */
  async function addFiles(fileList) {
    const files = Array.from(fileList || []);
    for (const file of files) {
      const problem = checkIncomingFile(file);
      if (problem) {
        useStore.getState().showNotice(problem);
        continue;
      }
      try {
        // 附件必须挂在会话上；草稿态下附件本身就是「我要发东西」的明确信号，这时候落库
        let targetId = useStore.getState().sessionId;
        if (!targetId) {
          targetId = await useStore.getState().ensureSession();
        }
        if (!targetId) {
          useStore.getState().showNotice('附件上传失败：还没有可用的对话');
          continue;
        }
        const uploaded = await api.uploadAttachment(targetId, file);
        const id = uploaded.id || uploaded.fileId || uploaded.name;
        draftFilesRef.current.set(id, uploaded);
        inputRef.current?.insertAttachments([{
          id,
          name: uploaded.name || file.name,
          kind: isImageAttachment(uploaded) ? 'image' : 'file',
          size: file.size
        }]);
      } catch (error) {
        useStore.getState().showNotice('附件上传失败：' + error.message);
      }
    }
  }

  async function submit() {
    const input = inputRef.current;
    if (!input) {
      return;
    }
    const { text, imagePositions } = input.getDraft();
    const attachments = input.attachmentIds().map((id) => draftFilesRef.current.get(id)).filter(Boolean);
    const state = useStore.getState();
    if (state.ui.queueEdit != null) {
      await state.saveQueueItem(state.ui.queueEdit, text);
      state.setUi({ queueEdit: null });
      input.setText('');
      return;
    }
    if (editTarget != null && state._editTarget != null) {
      await state.resendMessage(editTarget, text, attachments);
      useStore.setState((draft) => { draft._editTarget = null; });
      input.setText('');
      return;
    }
    if (!text.trim() && !attachments.length) {
      return;
    }
    if (state.running) {
      if (queueHeld) {
        await state.steer(text, attachments);
      } else {
        await state.submitInput(text, attachments, imagePositions);
      }
    } else if (state.lastOutcome === 'NEED_INPUT') {
      await state.continueRun();
      await state.submitInput(text, attachments, imagePositions);
    } else {
      await state.submitInput(text, attachments, imagePositions);
    }
    input.setText('');
    draftFilesRef.current.clear();
    dropDraftSafe();
  }

  function dropDraftSafe() {
    try {
      dropDraft(localStorage, sessionId);
    } catch { /* 忽略 */ }
  }

  /* @ 引用菜单：按光标前文本过滤 */
  function onInput() {
    const textBefore = inputRef.current?.textBeforeCaret?.() || '';
    const atMatch = textBefore.match(/(^|\s)@([\w./-]*)$/);
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
    // 草稿态还没有会话，取不到文件索引：等第一条消息落库后 @ 再用
    if (!sessionId) {
      return;
    }
    if (state.fileIndex.sessionId !== sessionId) {
      set((draft) => { draft.fileIndex.loading = true; });
      try {
        const data = await api.filesFlat(sessionId);
        useStore.setState((draft) => {
          draft.fileIndex = { items: data?.files || data || [], sessionId, loading: false };
        });
      } catch {
        useStore.setState((draft) => { draft.fileIndex = { items: [], sessionId, loading: false, failed: true }; });
      }
    }
    const matches = rankFileMatches(useStore.getState().fileIndex.items || [], query).slice(0, 8);
    setSlashMenu(null);
    setRefMenu({ items: matches });
    setMenuIndex(0);
  }

  function pickRef(path) {
    const before = inputRef.current?.textBeforeCaret?.() || '';
    const after = inputRef.current?.textAfterCaret?.() || '';
    const trimmed = before.replace(/@([\w./-]*)$/, '@' + path + ' ');
    inputRef.current?.setText(trimmed + after);
    inputRef.current?.focus();
    setRefMenu(null);
  }

  function pickSlash(command) {
    // 技能命令只补指令名（它没有本地展开模板，发送时由后端注入指引）；快捷指令展开成自然语言
    const expansion = command.skill
      ? `${command.name} `
      : expandQuickCommand(command, slashMenu?.query || '');
    if (expansion) {
      inputRef.current?.setText(expansion);
    }
    setSlashMenu(null);
    inputRef.current?.focus();
  }

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
    if (event.isComposing || event.keyCode === 229) {
      return;
    }
    const items = refMenu ? (refMenu.items || []) : slashMenu ? (slashMenu.items || []) : [];
    if (refMenu || slashMenu) {
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

  const composerEnabled = true;

  return (
    <div className="composer-wrap">
      <ApprovalDock />
      <MessageQueue />
      <AgentDock />
      {refMenu && (
        <div className="ref-menu" role="listbox" ref={menuRef}>
          {refMenu.loading || (refMenu.items || []).length === 0
            ? <div className="ref-empty">没有匹配的文件</div>
            : refMenu.items.map((item, index) => (
              <button
                key={item.path || item}
                type="button"
                className={`ref-item${index === menuIndex ? ' is-on' : ''}`}
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
        className="composer"
        onSubmit={(event) => { event.preventDefault(); submit(); }}
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => {
          event.preventDefault();
          if (event.dataTransfer?.files?.length) {
            addFiles(event.dataTransfer.files);
          }
        }}
        onPaste={(event) => {
          if (event.clipboardData?.files?.length) {
            event.preventDefault();
            addFiles(event.clipboardData.files);
          }
        }}
      >
        <div id="input-flow" className="input-flow" contentEditable
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
                  onClick={() => setPermOpen((open) => !open)}
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
                  onClick={() => setModelOpen((open) => !open)}
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
                  className={`perm-chip context-chip level-${usageLevel(contextPercent(contextStatus))}`}
                  id="context-chip"
                  aria-haspopup="dialog"
                  aria-expanded={contextOpen}
                  title={`上下文已用 ${contextPercent(contextStatus)} · 点击查看构成与压缩`}
                  onClick={() => { setContextOpen((open) => !open); useStore.getState().refreshContext(); }}
                >
                  上下文 · {contextPercent(contextStatus)}
                </button>
                {contextOpen && <ContextMenu status={contextStatus} compacting={compacting} />}
              </span>
            </span>
            <span className="composer-hint">
              <kbd>Enter</kbd> 发送
              <span className="sep">·</span> 拖入 / 粘贴文件
              <span className="sep">·</span> <kbd>@</kbd> 文件
              <span className="sep">·</span> <kbd>/</kbd> 命令
            </span>
          </span>
          <div className="composer-actions">
            <span id="send-mode-label" className="send-mode-label">
              {running ? (queueHeld ? '暂停中 · 发送即纠正' : '运行中 · 发送即排队') : sendAction.label}
            </span>
            <button
              type="submit"
              className={`send${sendAction.stop ? ' is-running' : ''}`}
              id="send"
              disabled={sendAction.disabled}
              aria-label={sendAction.ariaLabel}
              title={sendAction.title}
            >
              <svg className="icon-send" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 12.5V3.8M4.6 7.2 8 3.8l3.4 3.4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" fill="none" /></svg>
              <svg className="icon-stop" viewBox="0 0 16 16" aria-hidden="true"><rect x="4.6" y="4.6" width="6.8" height="6.8" rx="1.6" fill="currentColor" /></svg>
            </button>
          </div>
        </div>
      </form>
      <TaskControls />
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
  const [name, setName] = useState(initialModel);
  const [endpoint, setEndpoint] = useState(initialEndpoint);
  const [key, setKey] = useState('');
  const [hint, setHint] = useState('');
  const [probing, setProbing] = useState(false);
  return (
    <div className="chip-menu model-menu" id="model-menu">
      <p className="menu-head">模型</p>
      <label>模型名称<input value={name} onChange={(event) => setName(event.target.value)} placeholder="如 deepseek-chat" /></label>
      <label>端点<input value={endpoint} onChange={(event) => setEndpoint(event.target.value)} placeholder="https://api.example.com/v1" /></label>
      <label>API Key<input type="password" value={key} onChange={(event) => setKey(event.target.value)} placeholder="不填则沿用已保存的" /></label>
      <div className="chip-menu-actions">
        <button type="button" className="btn" disabled={probing} onClick={async () => {
          setProbing(true);
          try {
            const list = await useStore.getState().probeModels(buildModelProbe({ endpoint, apiKey: key }));
            setHint(describeModelsFetched(list, { endpoint }));
          } catch (error) {
            setHint('探测失败：' + error.message);
          } finally {
            setProbing(false);
          }
        }}>{probing ? '探测中…' : '探测模型'}</button>
        <button type="button" className="btn btn-primary" onClick={async () => {
          await useStore.getState().saveModel(buildModelUpdate({ model: name, endpoint, apiKey: key }));
          onClose();
        }}>保存</button>
      </div>
      {models?.length > 0 && (
        <div className="model-options" role="listbox" aria-label="探测到的模型">
          <p className="model-options-head">端点可用模型<span>{models.length}</span></p>
          {models.slice(0, 12).map((model) => (
            <button
              key={model}
              type="button"
              role="option"
              aria-selected={model === name}
              className={`model-option${model === name ? ' is-on' : ''}`}
              onClick={() => setName(model)}
            >
              <span className="model-option-name">{model}</span>
              {model === name && <CheckIcon />}
            </button>
          ))}
        </div>
      )}
      {hint && <p className="chip-hint">{hint}</p>}
    </div>
  );
}

/** 上下文占用：用量条 + 记忆构成 + 自动收缩水位，照着 Cursor 的用法说明白。 */
function ContextMenu({ status, compacting }) {
  const percent = usagePercent(status);
  const level = usageLevel(percent);
  const segments = usageSegments(status);
  const remaining = remainingTokens(status);
  return (
    <div className="chip-menu context-menu" id="context-menu">
      <div className="context-head">
        <p className="menu-head">上下文</p>
        <span className={`context-percent level-${level}`}>{percent}%</span>
      </div>
      <div className={`context-meter level-${level}`}>
        <span id="context-meter-fill" style={{ width: `${percent}%` }} />
      </div>
      <p className="context-line">
        <span className="mono">{formatTokens(status?.usedTokens)} / {formatTokens(status?.limitTokens)}</span>
        <span>tokens</span>
        {status?.estimated && <em title="按字符数保守估算，不是端点返回的精确值">估算</em>}
      </p>
      {segments.length > 0 && (
        <ul className="context-segments">
          {segments.map((segment) => (
            <li key={segment.role}>
              <span className="context-segment-name">{segment.label}</span>
              <span className="context-segment-bar"><i style={{ width: `${Math.max(2, Math.round(segment.share * 100))}%` }} /></span>
              <span className="context-segment-value mono">{formatTokens(segment.chars)} 字</span>
            </li>
          ))}
        </ul>
      )}
      <p className="context-note">{usageNote(status, percent)}</p>
      <div className="context-foot">
        <span className="context-remaining">
          {remaining == null ? `${status?.messages || 0} 条记忆` : `剩余 ${formatTokens(remaining)} tokens`}
        </span>
        <button
          type="button"
          className="btn"
          disabled={compacting || status?.canCompact === false}
          title={status?.canCompact === false ? '当前没有可折叠的历史' : '把手头的历史折叠成交接摘要，聊天记录不删'}
          onClick={() => useStore.getState().compactContext()}
        >
          {compacting ? '压缩中…' : '压缩上下文'}
        </button>
      </div>
    </div>
  );
}

/** 运行控制条：暂停 / 取消 / 继续，只在有活跃 run 时出现。 */
function TaskControls() {
  const running = useStore((state) => state.running);
  const pausing = useStore((state) => state.pausing);
  const cancelling = useStore((state) => state.cancelling);
  const lastOutcome = useStore((state) => state.lastOutcome);
  if (!running && lastOutcome !== 'NEED_INPUT') {
    return null;
  }
  return (
    <div id="task-live-controls" className="task-controls">
      {running && (
        <>
          <button type="button" className="btn" disabled={pausing} onClick={() => useStore.getState().pauseRun()}>{pausing ? '暂停中…' : '暂停'}</button>
          <button type="button" className="btn btn-danger" disabled={cancelling} onClick={() => useStore.getState().cancelRun()}>{cancelling ? '取消中…' : '取消任务'}</button>
        </>
      )}
      {!running && lastOutcome === 'NEED_INPUT' && (
        <button type="button" className="btn btn-primary" onClick={() => useStore.getState().continueRun()}>继续当前任务</button>
      )}
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

/** 排队消息：编辑 / 删除 / 上下移。 */
function MessageQueue() {
  const entries = useStore((state) => state.queueEntries);
  const queueEdit = useStore((state) => state.ui.queueEdit);
  const collapsed = useStore((state) => state.ui.queueCollapsed);
  if (!entries.length) {
    return null;
  }
  return (
    <QueueList entries={entries} queueEdit={queueEdit} collapsed={collapsed} />
  );
}

function QueueList({ entries, queueEdit, collapsed }) {
  const [dragFrom, setDragFrom] = useState(null);
  return (
    <div id="message-queue" className="message-queue">
      <header className="queue-head" onClick={() => useStore.getState().setUi({ queueCollapsed: !collapsed })}>
        <span>{entries.length} 条排队</span>
        <span className="queue-toggle">{collapsed ? '展开' : '收起'}</span>
      </header>
      {!collapsed && (
        <ol className="queue-list">
          {entries.map((entry, index) => {
            const text = entry.text ?? entry;
            return (
              <li
                key={index}
                className="queue-entry"
                onDragOver={(event) => event.preventDefault()}
                onDrop={() => {
                  if (dragFrom == null || dragFrom === index) return;
                  useStore.getState().moveQueueItem(dragFrom, index);
                  setDragFrom(null);
                }}
              >
                {queueEdit === index ? (
                  <QueueEditor index={index} text={text} />
                ) : (
                  <>
                    <button
                      type="button"
                      className="queue-grip"
                      draggable
                      aria-label="拖动调整顺序"
                      title="拖动调整顺序"
                      onDragStart={() => setDragFrom(index)}
                    >
                      <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 6h8M4 10h8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
                    </button>
                    <button type="button" className="queue-text" title={text} onClick={() => useStore.getState().setUi({ queueEdit: index })}>{text}</button>
                    <button type="button" className="queue-icon" title="删除" aria-label="删除" onClick={() => useStore.getState().removeQueueItem(index)}>×</button>
                  </>
                )}
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}

function QueueEditor({ index, text }) {
  const [value, setValue] = useState(text);
  return (
    <div id="queue-edit" className="queue-edit">
      <textarea value={value} onChange={(event) => setValue(event.target.value)} rows={2} />
      <div className="queue-edit-actions">
        <button type="button" className="btn btn-primary" onClick={async () => {
          await useStore.getState().saveQueueItem(index, value);
          useStore.getState().setUi({ queueEdit: null });
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
  const running = list.filter((agent) => agent.status === 'running').length;
  return (
    <div id="agents-dock" className="agents-dock">
      <header className="agents-dock-head" onClick={() => useStore.getState().setUi({ agentsDockOpen: !open })}>
        <span>子智能体 · {list.length}{running ? ` · ${running} 个运行中` : ''}</span>
        <span className="queue-toggle">{open ? '收起' : '展开'}</span>
      </header>
      {open && (
        <ul className="agents-dock-list">
          {list.map((agent) => (
            <li key={agent.id}>
              <span className={`agent-status is-${agent.status}`} />
              <span className="agent-name">{agent.name || agent.id}</span>
              <span className="agent-latest">{agent.latest || agent.task?.slice(0, 48) || ''}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
