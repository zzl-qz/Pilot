import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import type { StudioArtifact, StudioDocument, StudioVersion } from '../types/api.js';
import {
  BLOCK_TYPES, defaultSpec, diffLines, formatLabel, parsePdfOperations, parseSpec, parseTable,
  serializeSpec, tableText, type StudioBlock
} from '../lib/document-model.js';
import './document-studio.css';

const FORMATS = ['docx', 'pdf', 'xlsx', 'pptx'];

/** 产物工作台：人直接编辑可编辑源、一键导出与版本回滚，全程不调用模型。 */
export default function DocumentStudio() {
  const session = useStore(state => state.sessionId);
  const targetId = useStore(state => state.ui.studioDocumentId);
  const [documents, setDocuments] = useState<StudioDocument[]>([]);
  const [active, setActive] = useState<StudioDocument | null>(null);
  const [title, setTitle] = useState('');
  const [blocks, setBlocks] = useState<StudioBlock[]>([]);
  const [rawSpec, setRawSpec] = useState('');
  const [importedOnly, setImportedOnly] = useState(false);
  const [versions, setVersions] = useState<StudioVersion[]>([]);
  const [artifacts, setArtifacts] = useState<StudioArtifact[]>([]);
  const [formats, setFormats] = useState<string[]>(['docx', 'pdf']);
  const [pdfOps, setPdfOps] = useState('');
  const [diffWith, setDiffWith] = useState<StudioVersion | null>(null);
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);

  const message = (error: unknown) => errorInfo(error).message || '操作失败';

  const openDocument = useCallback(async (document: StudioDocument) => {
    if (!session) return;
    setActive(document); setTitle(document.title); setDirty(false); setDiffWith(null);
    const parsed = parseSpec(document.spec);
    setBlocks(parsed.blocks); setRawSpec(document.spec); setImportedOnly(parsed.importedTextOnly);
    setError(parsed.error);
    try {
      const [loadedVersions, loadedArtifacts] = await Promise.all([
        api.studioVersions(session, document.id), api.studioArtifacts(session, document.id)
      ]);
      if (!alive.current) return;
      setVersions(loadedVersions || []); setArtifacts(loadedArtifacts || []);
    } catch (failure) { if (alive.current) setError(message(failure)); }
  }, [session]);

  const refreshDocuments = useCallback(async (preferId?: string) => {
    if (!session) { setDocuments([]); setActive(null); return; }
    try {
      const list = await api.studioDocuments(session) || [];
      if (!alive.current) return;
      setDocuments(list);
      const target = preferId ? list.find(item => item.id === preferId) : null;
      if (target) await openDocument(target);
    } catch (failure) { if (alive.current) setError(message(failure)); }
  }, [session, openDocument]);

  useEffect(() => { void refreshDocuments(); }, [refreshDocuments]);

  // 成果面板「在编辑器中打开」会把刚导入的文档 id 传过来。
  useEffect(() => {
    if (!targetId || !session) return;
    void refreshDocuments(targetId).then(() => useStore.getState().setUi({ studioDocumentId: null }));
  }, [targetId, session, refreshDocuments]);

  const currentSpec = () => active?.kind === 'spec' ? rawSpec : serializeSpec(title, blocks, importedOnly);

  const createDocument = async () => {
    if (!session) return;
    setBusy(true); setError('');
    try {
      const created = await api.studioCreate(session, { title: '新建文档', kind: 'blocks', spec: defaultSpec(), created_by: 'human' });
      if (!created) throw new Error('创建未返回结果');
      await refreshDocuments(created.id);
      setNotice('已创建可编辑文档；可直接修改内容并导出，无需调用模型。');
    } catch (failure) { setError(message(failure)); }
    finally { if (alive.current) setBusy(false); }
  };

  const saveDocument = async (silent = false): Promise<StudioDocument | null> => {
    if (!session || !active) return null;
    const spec = currentSpec();
    if (active.kind !== 'spec' && parseSpec(spec).error) throw new Error(parseSpec(spec).error);
    const saved = await api.studioSave(session, active.id, {
      version: active.version, title, spec, created_by: 'human'
    });
    if (!saved) throw new Error('保存未返回结果');
    if (alive.current) {
      setActive(saved); setDirty(false); setRawSpec(saved.spec);
      setDocuments(items => items.map(item => item.id === saved.id ? saved : item));
      const loaded = await api.studioVersions(session, saved.id);
      if (alive.current) setVersions(loaded || []);
      if (!silent) setNotice(`已保存为版本 ${saved.version}；旧版本仍可在下方回滚。`);
    }
    return saved;
  };

  const exportDocument = async () => {
    if (!session || !active) return;
    setBusy(true); setError('');
    try {
      if (dirty) await saveDocument(true);
      const operations = parsePdfOperations(pdfOps);
      if (operations.error) throw new Error(operations.error);
      const body: Record<string, unknown> = { formats };
      if (operations.value) {
        if (!formats.includes('pdf')) throw new Error('页面操作需要同时勾选 PDF');
        body.pdf_operations = operations.value;
      }
      const result = await api.studioRender(session, active.id, body);
      if (!result) throw new Error('导出未返回结果');
      const loaded = await api.studioArtifacts(session, active.id);
      if (alive.current) {
        setArtifacts(loaded || []);
        setNotice(`已导出 ${result.artifacts.length} 个文件（确定性渲染，未调用模型），并逐项完成读回检查。`);
      }
    } catch (failure) { setError(message(failure)); }
    finally { if (alive.current) setBusy(false); }
  };

  const downloadArtifact = async (artifact: StudioArtifact) => {
    if (!session || !active) return;
    setError('');
    try {
      const blob = await api.studioDownload(session, active.id, artifact.id);
      const url = URL.createObjectURL(blob), anchor = document.createElement('a');
      anchor.href = url; anchor.download = artifact.filename; document.body.append(anchor);
      anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch (failure) { setError(message(failure)); }
  };

  const rollback = async (version: StudioVersion) => {
    if (!session || !active) return;
    setBusy(true); setError('');
    try {
      const current = dirty ? await saveDocument(true) : active;
      const restored = await api.studioRollback(session, active.id, {
        version: current?.version || active.version, target_version: version.version, created_by: 'human'
      });
      if (!restored) throw new Error('回滚未返回结果');
      await openDocument(restored);
      setNotice(`已把版本 ${version.version} 的内容另存为版本 ${restored.version}，历史版本未被删除。`);
    } catch (failure) { setError(message(failure)); }
    finally { if (alive.current) setBusy(false); }
  };

  const patchBlock = (index: number, patch: Partial<StudioBlock>) => {
    setBlocks(items => items.map((item, at) => at === index ? { ...item, ...patch } : item));
    setDirty(true);
  };
  const moveBlock = (index: number, delta: number) => {
    setBlocks(items => {
      const next = [...items], target = index + delta;
      if (target < 0 || target >= next.length) return items;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
    setDirty(true);
  };
  const addBlock = (type: StudioBlock['type']) => {
    const block: StudioBlock = type === 'table' ? { type, rows: [['列 A', '列 B'], ['值 1', '值 2']] }
      : type === 'page_break' ? { type } : { type, text: '', level: type === 'heading' ? 1 : undefined };
    setBlocks(items => [...items, block]); setDirty(true);
  };

  const diff = useMemo(() => diffWith ? diffLines(diffWith.spec, currentSpec()) : [], [diffWith, title, blocks, rawSpec, active]);
  const specMode = active?.kind === 'spec';

  return <section className="studio" aria-label="产物工作台">
    <header className="studio-head">
      <div>
        <h3>产物工作台</h3>
        <p>直接编辑文档、一键导出 Word/Excel/PPT/PDF，不调用模型、不消耗 token。PDF 只支持页面级整理，正文修改请回到这里的可编辑源。</p>
      </div>
      <div className="studio-head-actions">
        <button className="btn" type="button" onClick={() => void refreshDocuments()} disabled={busy}>刷新</button>
        <button className="btn primary" type="button" onClick={() => void createDocument()} disabled={!session || busy}>新建文档</button>
      </div>
    </header>
    {!session && <p className="studio-empty" role="status">请先打开或新建一个会话，工作台按会话隔离文档。</p>}
    {error && <p className="studio-error" role="alert">{error}</p>}
    {notice && <p className="studio-notice" role="status">{notice}</p>}

    {documents.length > 0 && <div className="studio-documents" role="group" aria-label="文档列表">
      {documents.map(item => <button key={item.id} type="button"
        className={`studio-chip${active?.id === item.id ? ' is-active' : ''}`}
        onClick={() => void openDocument(item)}>
        {item.title}<small>v{item.version}</small>
      </button>)}
    </div>}

    {!active && session && <p className="studio-empty">当前会话还没有可编辑文档。点「新建文档」从头写，或在「成果」页把已有交付文件导入这里继续手动修改。</p>}

    {active && <div className="studio-grid">
      <article className="studio-card studio-editor">
        <div className="studio-card-head"><strong>可编辑源</strong><span>版本 v{active.version} · {specMode ? '规格模式' : '块编辑模式'}{dirty ? ' · 有未保存改动' : ''}</span></div>
        <label className="studio-field">标题<input value={title} onChange={event => { setTitle(event.target.value); setDirty(true); }} maxLength={120} /></label>
        {importedOnly && <p className="studio-hint">此文档由成果导入，仅包含抽取文本，不含原排版；导出会按新内容重新排版。</p>}
        {specMode ? <label className="studio-field">完整规格（JSON）<textarea className="studio-json" value={rawSpec}
          onChange={event => { setRawSpec(event.target.value); setDirty(true); }} rows={16} spellCheck={false} /></label>
          : <div className="studio-blocks">
            {blocks.map((block, index) => <div className="studio-block" key={index}>
              <div className="studio-block-head">
                <select aria-label={`第 ${index + 1} 块类型`} value={block.type}
                  onChange={event => patchBlock(index, { type: event.target.value as StudioBlock['type'] })}>
                  {BLOCK_TYPES.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
                </select>
                {block.type === 'heading' && <select aria-label="标题级别" value={block.level || 1}
                  onChange={event => patchBlock(index, { level: Number(event.target.value) })}>
                  <option value={1}>一级</option><option value={2}>二级</option><option value={3}>三级</option>
                </select>}
                <span className="studio-block-tools">
                  <button type="button" className="icon-btn" aria-label="上移" onClick={() => moveBlock(index, -1)}>↑</button>
                  <button type="button" className="icon-btn" aria-label="下移" onClick={() => moveBlock(index, 1)}>↓</button>
                  <button type="button" className="icon-btn" aria-label="删除该块" onClick={() => { setBlocks(items => items.filter((_, at) => at !== index)); setDirty(true); }}>×</button>
                </span>
              </div>
              {block.type !== 'page_break' && (block.type === 'table'
                ? <textarea aria-label="表格内容，每行用竖线分隔单元格" rows={4} value={tableText(block.rows)}
                  onChange={event => patchBlock(index, { rows: parseTable(event.target.value) })} spellCheck={false} />
                : <textarea aria-label="文本内容" rows={block.type === 'heading' ? 2 : 3} value={block.text || ''}
                  onChange={event => patchBlock(index, { text: event.target.value })} />)}
              {block.type === 'page_break' && <span className="studio-hint">从此处开始新一页</span>}
            </div>)}
            <div className="studio-add">
              {BLOCK_TYPES.map(item => <button key={item.id} type="button" className="btn" onClick={() => addBlock(item.id)}>+ {item.label}</button>)}
            </div>
          </div>}
        <div className="studio-card-actions">
          <button className="btn primary" type="button" disabled={busy} onClick={() => void saveDocument().catch(failure => setError(message(failure)))}>保存新版本</button>
          <span className="studio-hint">每次保存都保留旧版本，可随时回滚。</span>
        </div>
      </article>

      <div className="studio-side">
        <article className="studio-card">
          <div className="studio-card-head"><strong>导出产物</strong><span>确定性渲染 · 零模型调用</span></div>
          <div className="studio-formats">
            {FORMATS.map(format => <label key={format}><input type="checkbox" checked={formats.includes(format)}
              onChange={event => setFormats(items => event.target.checked ? [...items, format] : items.filter(item => item !== format))} />
              {formatLabel(format)}</label>)}
          </div>
          {formats.includes('pdf') && <label className="studio-field">PDF 页面级操作（可选 JSON）
            <textarea rows={3} spellCheck={false} value={pdfOps} onChange={event => setPdfOps(event.target.value)}
              placeholder={'{"keep_pages":[1,2],"page_numbers":true,"watermark":"DRAFT"}'} /></label>}
          <p className="studio-hint">支持 keep_pages / drop_pages / rotate / page_numbers / watermark / merge；merge 可填下方已导出 PDF 的文件名。页面级操作不改写已有正文文字，正文修改请回到左侧可编辑源。</p>
          <button className="btn primary" type="button" disabled={busy || !formats.length} onClick={() => void exportDocument()}>导出并读回检查</button>
          <ul className="studio-artifacts">
            {artifacts.map(item => <li key={item.id}>
              <div><strong>{formatLabel(item.format)}</strong><small>{item.filename} · v{item.documentVersion} · {Math.max(1, Math.round(item.bytes / 1024))} KB · {item.createdAt.slice(0, 16).replace('T', ' ')}</small></div>
              <button type="button" className="btn" onClick={() => void downloadArtifact(item)}>下载</button>
              <p className="studio-verification">{item.verification}</p>
            </li>)}
            {!artifacts.length && <li className="studio-empty">还没有导出产物。</li>}
          </ul>
        </article>

        <article className="studio-card">
          <div className="studio-card-head"><strong>版本历史</strong><span>回滚不删除历史</span></div>
          <ul className="studio-versions">
            {versions.map(item => <li key={item.version}>
              <span>v{item.version} · {item.createdBy === 'ai' ? '模型' : item.createdBy === 'import' ? '成果导入' : '手动'} · {item.createdAt.slice(0, 16).replace('T', ' ')}</span>
              <span className="studio-version-actions">
                <button type="button" className="btn" onClick={() => setDiffWith(diffWith?.version === item.version ? null : item)}>
                  {diffWith?.version === item.version ? '收起差异' : '对比'}</button>
                {item.version !== active.version && <button type="button" className="btn" disabled={busy} onClick={() => void rollback(item)}>回滚</button>}
              </span>
            </li>)}
          </ul>
          {diffWith && <div className="studio-diff" aria-label={`与版本 ${diffWith.version} 的差异`}>
            {diff.map((line, index) => <div key={index} className={`studio-diff-line is-${line.kind}`}>
              <span>{line.kind === 'add' ? '+' : line.kind === 'remove' ? '−' : ' '}</span><code>{line.text || ' '}</code>
            </div>)}
          </div>}
        </article>
      </div>
    </div>}
  </section>;
}
