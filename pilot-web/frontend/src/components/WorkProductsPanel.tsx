import type * as S from '../types/server.js';
import { errorInfo } from '../lib/errors.js';
import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import { MarkdownBody } from './MarkdownBody.jsx';
import './work-products.css';

const STATUS: Record<string, string> = { PENDING: '待审核', APPROVED: '已接受', REJECTED: '已拒绝', REVERTED: '已撤回', CONFLICT: '版本冲突', APPROVING: '正在接受', REVERTING: '正在撤回' };
const dateText = (value: string | null | undefined) => value ? new Date(value).toLocaleString() : '';
function sourceHref(value: string) {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null; }
  catch { return null; }
}

export default function WorkProductsPanel() {
  const session = useStore(s => s.sessionId);
  return session ? <SessionProducts key={session} session={session} /> : <p className="work-empty">开始一个任务后，可在这里查看交付文件与待审核的技能。</p>;
}

function SessionProducts({ session }: { session: string }) {
  const running = useStore(s => s.running);
  const panel = useRef<HTMLElement>(null), lastSelection = useRef<string | null>(null);
  const [products, setProducts] = useState<S.WorkProductServiceProduct[]>([]), [drafts, setDrafts] = useState<S.SkillDraftServiceDraft[]>([]);
  const [selected, setSelected] = useState<{ kind: 'product' | 'draft'; id: string } | null>(null), [loading, setLoading] = useState(true);
  const [error, setError] = useState(''), [revision, setRevision] = useState(0);
  useEffect(() => {
    if (selected) {
      lastSelection.current = `${selected.kind}:${selected.id}`;
      panel.current?.querySelector<HTMLElement>('article h2')?.focus();
    } else if (lastSelection.current) {
      Array.from(panel.current?.querySelectorAll<HTMLElement>('[data-work-key]') || [])
        .find(button => button.dataset.workKey === lastSelection.current)?.focus();
    }
  }, [selected]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError('');
    Promise.allSettled([api.workProducts(session, controller.signal), api.skillDrafts(session, controller.signal)])
      .then(([files, skills]) => {
        if (controller.signal.aborted) return;
        if (files.status === 'fulfilled') setProducts(files.value || []);
        if (skills.status === 'fulfilled') setDrafts(skills.value || []);
        const failures = [files, skills].filter(item => item.status === 'rejected').map(item => errorInfo(item.reason).message || '加载失败');
        setError(failures.join('；')); setLoading(false);
      });
    return () => controller.abort();
  }, [session, revision, running]);
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => setRevision(v => v + 1), 8000);
    return () => clearInterval(timer);
  }, [running]);
  const refresh = () => setRevision(v => v + 1);
  const product = selected?.kind === 'product' ? products.find(item => item.id === selected.id) : null;
  const draft = selected?.kind === 'draft' ? drafts.find(item => item.id === selected.id) : null;
  return <section ref={panel} className="work-products" aria-label="任务成果">
    <div className="work-toolbar">
      {selected ? <button className="btn" onClick={() => setSelected(null)}>返回成果列表</button> : <span>{products.length} 份成果 · {drafts.filter(d => d.status === 'PENDING').length} 项待审核</span>}
      <button className="btn" onClick={refresh} disabled={loading} aria-label="刷新成果">刷新</button>
    </div>
    {error && <p className="work-error" role="alert">加载失败：{error}。可刷新重试。</p>}
    {loading && !products.length && !drafts.length && <p role="status">正在读取成果…</p>}
    {product ? <ProductDetail key={product.id} session={session} product={product} />
      : draft ? <DraftDetail key={draft.id} session={session} draft={draft} onChange={updated => { setDrafts(items => items.map(item => item.id === updated.id ? updated : item)); }} onRefresh={refresh} />
        : <>
          <h3>交付文件</h3>
          {!products.length && !loading && <p className="work-empty">还没有交付文件。可以让 Pilot 调研一个问题，或把资料整理成报告、表格，完成后会出现在这里。</p>}
          <ul className="work-list">{products.map(item => <li key={item.id}><button data-work-key={`product:${item.id}`} onClick={() => setSelected({ kind: 'product', id: item.id })}>
            <strong>{item.title}</strong><span>{item.summary || item.filename}</span><small>{item.filename} · {Math.max(1, Math.round(item.bytes / 1024))} KB · {dateText(item.createdAt)}</small>
          </button></li>)}</ul>
          <h3>可复用的经验</h3>
          <p className="work-hint">先核对步骤与依据，接受后才会加入当前项目的技能。</p>
          <ul className="work-list">{drafts.map(item => <li key={item.id}><button data-work-key={`draft:${item.id}`} onClick={() => setSelected({ kind: 'draft', id: item.id })}>
            <strong>{item.name}<span className="work-state">{STATUS[item.status] || item.status}</span></strong><span>{item.description}</span>
          </button></li>)}</ul>
          {!drafts.length && !loading && <p className="work-empty">暂无技能草稿。任务完成后，可以让 Pilot 将验证过的方法整理为技能草稿。</p>}
        </>}
  </section>;
}

function ProductDetail({ session, product }: { session: string; product: S.WorkProductServiceProduct }) {
  const [preview, setPreview] = useState<S.WorkProductServicePreview | null>(null), [error, setError] = useState('');
  const [downloading, setDownloading] = useState(false), [retry, setRetry] = useState(0);
  const alive = useRef(true), downloadAbort = useRef<AbortController | null>(null);
  useEffect(() => { alive.current = true; return () => { alive.current = false; downloadAbort.current?.abort(); }; }, []);
  useEffect(() => {
    const controller = new AbortController(); setPreview(null); setError('');
    api.productPreview(session, product.id, controller.signal).then(value => { if (!controller.signal.aborted) setPreview(value); })
      .catch(e => { if (!controller.signal.aborted) setError(errorInfo(e).message || '操作失败'); });
    return () => controller.abort();
  }, [session, product.id, retry]);
  const download = async () => {
    setDownloading(true); setError(''); downloadAbort.current = new AbortController();
    try {
      const blob = await api.downloadProduct(session, product.id, downloadAbort.current.signal);
      if (!alive.current) return;
      const url = URL.createObjectURL(blob), anchor = document.createElement('a');
      anchor.href = url; anchor.download = product.filename; document.body.append(anchor); anchor.click(); anchor.remove();
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch (e) { if (alive.current) setError(errorInfo(e).message || '操作失败'); }
    finally { if (alive.current) setDownloading(false); }
  };
  /** 成果本来就是工作区里的真实文件，直接切到文件工作台查看，不再抽取文本另建一份源。 */
  const openInWorkbench = () => {
    useStore.getState().setUi({ inspectorOpen: true, inspectorTab: 'files', workbenchFileId: null });
  };
  return <article className="work-detail">
    <h2 tabIndex={-1}>{product.title}</h2><p>{product.summary}</p>
    <div className="work-toolbar"><small>{product.filename} · {dateText(product.createdAt)}</small>
      <button className="btn" onClick={openInWorkbench}>在文件工作台查看</button>
      <button className="btn" onClick={download} disabled={downloading}>{downloading ? '正在下载…' : '下载文件'}</button></div>
    {error && <div role="alert" className="work-error">{error}<button className="btn" onClick={() => setRetry(v => v + 1)}>重试预览</button></div>}
    {!preview && !error && <p role="status">正在读取预览…</p>}
    {preview && <><p className="work-hint">{preview.notice}{preview.truncated ? ' 预览已截断，完整内容请下载。' : ''}</p>
      <div className="work-preview">{preview.format === 'markdown' ? <MarkdownBody text={preview.text} /> : preview.text ? <pre>{preview.text}</pre> : null}</div></>}
    <h3>引用来源</h3>
    {product.sources?.length ? <ul className="work-sources">{product.sources.map(source => <li key={source.url}>
      {sourceHref(source.url) ? <a href={sourceHref(source.url) || undefined} target="_blank" rel="noreferrer">{source.url}</a> : <span>{source.url}</span>}
      <small>{source.observedRead ? '有工具读取记录' : '未记录工具读取'}</small>
    </li>)}</ul> : <p className="work-hint">此成果未附外部引用。</p>}
  </article>;
}

function DraftDetail({ session, draft, onChange, onRefresh }: { session: string; draft: S.SkillDraftServiceDraft; onChange: (updated: S.SkillDraftServiceDraft) => void; onRefresh: () => void }) {
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const decide = async (action: string) => {
    if (action === 'revert' && !window.confirm('撤回这个技能版本？已有技能将恢复为接受前的内容；之后另有修改时会停止操作。')) return;
    setBusy(true); setError('');
    try { const updated = await api.decideSkill(session, draft.id, action, draft.version); if (!updated) throw new Error('服务端返回空审核结果，请刷新后重试'); if (alive.current) onChange(updated); }
    catch (e) { if (alive.current) { setError(errorInfo(e).message || '操作失败'); onRefresh(); } }
    finally { if (alive.current) setBusy(false); }
  };
  return <article className="work-detail">
    <h2 tabIndex={-1}>{draft.name}</h2><p>{draft.description}</p><p className="work-hint">{STATUS[draft.status] || draft.status} · 当前项目</p>
    <h3>经验依据</h3><p className="work-evidence">{draft.evidence}</p>
    <h3>技能步骤</h3><div className="work-preview"><MarkdownBody text={draft.body} /></div>
    {draft.previousContent != null && <details><summary>查看接受前的版本</summary><pre>{draft.previousContent}</pre></details>}
    {error && <p className="work-error" role="alert">{error}</p>}
    <div className="work-actions">
      {draft.status === 'PENDING' && <button className="btn btn-primary" disabled={busy} onClick={() => decide('approve')}>接受技能</button>}
      {['PENDING', 'CONFLICT'].includes(draft.status) && <button className="btn" disabled={busy} onClick={() => decide('reject')}>拒绝草稿</button>}
      {draft.status === 'APPROVED' && <button className="btn" disabled={busy} onClick={() => decide('revert')}>撤回此版本</button>}
      {busy && <span role="status">正在保存审核结果…</span>}
    </div>
  </article>;
}
