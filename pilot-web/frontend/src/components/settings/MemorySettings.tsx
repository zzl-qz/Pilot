import type { MemoryEntry, MemoryOverview, MemoryScope, MemoryTypeName } from "../../types/api.js";
import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { api } from "../../api.js";
import { errorInfo } from "../../lib/errors.js";
import { useStore } from "../../store.js";

const TYPE_OPTIONS: ReadonlyArray<{ value: MemoryTypeName; label: string }> = [
  { value: 'feedback', label: '偏好与经验 (feedback)' },
  { value: 'project', label: '项目决策 (project)' },
  { value: 'user', label: '用户画像 (user)' },
  { value: 'reference', label: '外部引用 (reference)' }
];

const TYPE_LABELS: Record<string, string> = {
  user: '用户画像',
  feedback: '偏好与经验',
  project: '项目决策',
  reference: '外部引用'
};

interface EditorState {
  editingSlug: string | null;
  scope: MemoryScope;
  type: MemoryTypeName;
  title: string;
  note: string;
  why: string;
}

function emptyEditor(scope: MemoryScope = 'project'): EditorState {
  return { editingSlug: null, scope, type: 'feedback', title: '', note: '', why: '' };
}

function splitNoteAndWhy(body: string): { note: string; why: string } {
  const marker = '\n\nWhy: ';
  const at = body.lastIndexOf(marker);
  if (at < 0) {
    return { note: body, why: '' };
  }
  return { note: body.slice(0, at), why: body.slice(at + marker.length).trim() };
}

export function MemorySettings() {
  const workspace = useStore(state => state.workspace);
  const [overview, setOverview] = useState<MemoryOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editor, setEditor] = useState<EditorState | null>(null);
  const sequence = useRef(0);

  const load = useCallback(async () => {
    const request = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await api.memoryOverview(workspace || undefined);
      if (request === sequence.current) {
        setOverview(data);
        useStore.setState({ pendingMemoryDrafts: Array.isArray(data?.drafts) ? data.drafts.length : 0 });
      }
    } catch (failure) {
      if (request === sequence.current) {
        setError(errorInfo(failure).message || '读取记忆列表失败。');
      }
    } finally {
      if (request === sequence.current) {
        setLoading(false);
      }
    }
  }, [workspace]);

  useEffect(() => {
    void load();
    return () => { sequence.current++; };
  }, [load]);

  async function runAction(task: () => Promise<string | void>, successMessage: string) {
    if (busy) {
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const customMessage = await task();
      await load();
      setNotice(typeof customMessage === 'string' && customMessage ? customMessage : successMessage);
    } catch (failure) {
      setError(errorInfo(failure).message || '操作失败，请重试。');
    } finally {
      setBusy(false);
    }
  }

  async function submitEditor(event: React.FormEvent) {
    event.preventDefault();
    if (!editor || busy) {
      return;
    }
    const targetWorkspace = overview?.workspace || workspace || undefined;
    const payload = {
      workspace: targetWorkspace,
      scope: editor.scope,
      type: editor.type,
      title: editor.title.trim(),
      note: editor.note,
      why: editor.why.trim()
    };
    await runAction(async () => {
      if (editor.editingSlug) {
        await api.memoryUpdateEntry(editor.editingSlug, payload);
      } else {
        await api.memoryCreateEntry(payload);
      }
      setEditor(null);
    }, editor.editingSlug ? '记忆条目已更新并重建索引。' : '记忆条目已保存并重建索引。');
  }

  function startEdit(entry: MemoryEntry) {
    const parts = splitNoteAndWhy(entry.body || '');
    setError('');
    setNotice('');
    setEditor({
      editingSlug: entry.slug,
      scope: entry.scope,
      type: entry.type || 'feedback',
      title: entry.title || '',
      note: parts.note,
      why: parts.why
    });
  }

  function confirmDelete(entry: MemoryEntry) {
    useStore.getState().askConfirm({
      title: '删除记忆',
      message: `确定删除「${entry.title || entry.slug}」吗？删除后将从索引移除。`,
      confirmText: '删除',
      danger: true,
      onConfirm: () => {
        void runAction(
          () => api.memoryDeleteEntry(entry.slug, entry.scope, overview?.workspace || workspace || undefined).then(() => {}),
          '记忆条目已删除。'
        );
      }
    });
  }

  const activeWorkspace = overview?.workspace || workspace || undefined;
  let submitLabel = editor?.editingSlug ? '保存修改' : '保存记忆';
  if (busy) {
    submitLabel = '保存中…';
  }

  return <>
    <section className="settings-group">
      <div className="settings-group-heading settings-inline-actions">
        <div>
          <h3>外置记忆概览</h3>
          <p>候选区与正式记忆保存在 Pilot 数据目录，不会改动目标代码仓库；同仓库的多个 worktree 共享同一份项目记忆。</p>
        </div>
        <div className="settings-inline-actions">
          {overview?.hasLegacyDrafts && <Button size="sm" variant="ghost" label="导入旧工作区草稿"
            isDisabled={busy || loading} onClick={() => void runAction(async () => {
              const res = await api.memoryImportLegacy(activeWorkspace);
              return `已导入 ${res?.imported ?? 0} 条候选草稿（跳过 ${res?.skipped ?? 0} 条重复），原工作区文件未修改。`;
            }, '已导入旧工作区草稿。')} />}
          <Button size="sm" label={editor ? '收起表单' : '新增记忆'} isDisabled={busy || loading}
            onClick={() => setEditor(current => (current ? null : emptyEditor('project')))} />
        </div>
      </div>
      {overview && <p className="settings-status">
        项目根：{overview.projectRoot}（键 <code>{overview.projectKey}</code>）· 数据目录：<code>{overview.storagePath}</code>
      </p>}
      <p className="settings-status">记忆仅作为线索参考；涉及代码路径或配置时，模型仍会先查证最新源码。</p>
      {loading && <p className="settings-status" role="status">正在读取记忆…</p>}
      {error && <div className="settings-inline-actions">
        <p className="settings-status" role="alert">{error}</p>
        <Button size="sm" variant="ghost" label="重试" onClick={() => void load()} />
      </div>}
      {notice && <p className="settings-status" role="status">{notice}</p>}

      {editor && <form className="mcp-add-form" onSubmit={submitEditor}>
        <div className="settings-field-grid">
          <label className="settings-native-field">作用域
            <select value={editor.scope} disabled={Boolean(editor.editingSlug) || busy}
              onChange={event => setEditor(current => current && ({ ...current, scope: event.target.value as MemoryScope }))}>
              <option value="project">当前项目 (project)</option>
              <option value="user">个人全局 (user)</option>
            </select>
          </label>
          <label className="settings-native-field">类型
            <select value={editor.type} disabled={busy}
              onChange={event => setEditor(current => current && ({ ...current, type: event.target.value as MemoryTypeName }))}>
              {TYPE_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </label>
        </div>
        <TextInput label="标题（可选，80 字以内）" width="100%" value={editor.title} isDisabled={busy}
          onChange={title => setEditor(current => current && ({ ...current, title }))} />
        <label className="settings-native-field">记忆内容（必填）
          <textarea className="memory-textarea" required value={editor.note} disabled={busy}
            placeholder="例如：集成测试必须连接真实 SQLite，禁止用内存假库绕过迁移校验；时间请写绝对日期 YYYY-MM-DD"
            onChange={event => setEditor(current => current && ({ ...current, note: event.target.value }))} />
        </label>
        <TextInput label="原因 / 适用条件 Why（可选）" width="100%" value={editor.why} isDisabled={busy}
          onChange={why => setEditor(current => current && ({ ...current, why }))} />
        <div className="settings-form-actions">
          <Button type="button" variant="ghost" label="取消" isDisabled={busy} onClick={() => setEditor(null)} />
          <Button type="submit" variant="primary" label={submitLabel}
            isDisabled={busy || !editor.note.trim()} />
        </div>
      </form>}
    </section>

    <section className="settings-group">
      <div className="settings-group-heading">
        <h3>待审候选区（{overview?.drafts.length ?? 0}）</h3>
        <p>模型调用 remember 记下的候选条目不会注入提示词，只有你点「采纳」后才会进入后续会话。</p>
      </div>
      {!loading && overview && overview.drafts.length === 0 && (
        <p className="panel-empty">暂无待审候选记忆。</p>
      )}
      {overview && overview.drafts.length > 0 && <ul className="mcp-server-list">
        {overview.drafts.map(draft => <li key={draft.id} className="mcp-server-item">
          <div className="mcp-server-head">
            <span className="mcp-server-name">{draft.title || draft.id}</span>
            <span className="mcp-source-tag">{TYPE_LABELS[draft.type] || draft.type}</span>
            {draft.date && <span className="mcp-source-tag">{draft.date}</span>}
            <div className="mcp-server-actions">
              <Button size="sm" variant="ghost" label="采纳到项目" isDisabled={busy}
                onClick={() => void runAction(
                  () => api.memoryApproveDraft(draft.id, { workspace: activeWorkspace, scope: 'project' }).then(() => {}),
                  '已采纳到项目记忆。'
                )} />
              <Button size="sm" variant="ghost" label="采纳到全局" isDisabled={busy}
                onClick={() => void runAction(
                  () => api.memoryApproveDraft(draft.id, { workspace: activeWorkspace, scope: 'user' }).then(() => {}),
                  '已采纳到个人全局记忆。'
                )} />
              <Button size="sm" variant="ghost" className="settings-danger" label="丢弃" isDisabled={busy}
                onClick={() => void runAction(
                  () => api.memoryDiscardDraft(draft.id, activeWorkspace).then(() => {}),
                  '已丢弃该候选条目。'
                )} />
            </div>
          </div>
          <p className="memory-body-preview">{draft.note}</p>
          {draft.why && <p className="cap-reason">Why: {draft.why}</p>}
        </li>)}
      </ul>}
    </section>

    <EntryListSection title="当前项目记忆" description="只对该 Git 仓库（含 worktree）或当前目录生效，启动时注入索引。"
      entries={overview?.projectEntries || []} loading={loading} busy={busy} onEdit={startEdit} onDelete={confirmDelete} />

    <EntryListSection title="个人全局记忆" description="跨所有项目生效（用户画像、个人通用习惯等）。"
      entries={overview?.userEntries || []} loading={loading} busy={busy} onEdit={startEdit} onDelete={confirmDelete} />
  </>;
}

function EntryListSection({ title, description, entries, loading, busy, onEdit, onDelete }: {
  title: string;
  description: string;
  entries: MemoryEntry[];
  loading: boolean;
  busy: boolean;
  onEdit: (entry: MemoryEntry) => void;
  onDelete: (entry: MemoryEntry) => void;
}) {
  return <section className="settings-group">
    <div className="settings-group-heading">
      <h3>{title}（{entries.length}）</h3>
      <p>{description}</p>
    </div>
    {!loading && entries.length === 0 && <p className="panel-empty">暂无条目。</p>}
    {entries.length > 0 && <ul className="mcp-server-list">
      {entries.map(entry => <li key={`${entry.scope}:${entry.slug}`} className="mcp-server-item">
        <div className="mcp-server-head">
          <span className="mcp-server-name">{entry.title || entry.slug}</span>
          <span className="mcp-source-tag">{TYPE_LABELS[entry.type] || entry.type}</span>
          <span className="mcp-source-tag"><code>{entry.slug}</code></span>
          {entry.updated && <span className="mcp-source-tag">{entry.updated}</span>}
          <div className="mcp-server-actions">
            <Button size="sm" variant="ghost" label="编辑" isDisabled={busy} onClick={() => onEdit(entry)} />
            <Button size="sm" variant="ghost" className="settings-danger" label="删除" isDisabled={busy}
              onClick={() => onDelete(entry)} />
          </div>
        </div>
        <p className="memory-body-preview">{entry.body}</p>
      </li>)}
    </ul>}
  </section>;
}
