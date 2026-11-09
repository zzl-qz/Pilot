import type * as S from '../types/server.js';
import { errorInfo } from '../lib/errors.js';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertDialog } from '@astryxdesign/core/AlertDialog';
import { Badge } from '@astryxdesign/core/Badge';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { Collapsible } from '@astryxdesign/core/Collapsible';
import { EmptyState } from '@astryxdesign/core/EmptyState';
import { formatBytes } from '../lib/file-workbench.js';
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
  return session ? <SessionProducts key={session} session={session} /> : <EmptyState className="inspector-empty"
    title="还没有任务成果" description="任务交付的文件和待审核的技能会列在这里。" isCompact />;
}

function SessionProducts({ session }: { session: string }) {
  const running = useStore(s => s.running);
  const panel = useRef<HTMLElement>(null), lastSelection = useRef<string | null>(null);
  const [products, setProducts] = useState<S.WorkProductServiceProduct[] | null>(null);
  const [drafts, setDrafts] = useState<S.SkillDraftServiceDraft[] | null>(null);
  const [selected, setSelected] = useState<{ kind: 'product' | 'draft'; id: string } | null>(null), [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const request = useRef<AbortController | null>(null);
  const product = selected?.kind === 'product' ? products?.find(item => item.id === selected.id) : null;
  const draft = selected?.kind === 'draft' ? drafts?.find(item => item.id === selected.id) : null;
  useEffect(() => {
    if (selected && (product || draft)) {
      lastSelection.current = `${selected.kind}:${selected.id}`;
      panel.current?.querySelector<HTMLElement>('article h2')?.focus();
    } else if (lastSelection.current) {
      const row = Array.from(panel.current?.querySelectorAll<HTMLElement>('[data-work-key]') || [])
        .find(button => button.dataset.workKey === lastSelection.current);
      (row || panel.current?.querySelector<HTMLElement>('.work-refresh'))?.focus();
      lastSelection.current = null;
    }
  }, [selected, product?.id, draft?.id]);

  const refresh = useCallback(async (background = false) => {
    if (background && request.current) return;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    if (!background) setLoading(true);
    const [files, skills] = await Promise.allSettled([
      api.workProducts(session, controller.signal),
      api.skillDrafts(session, controller.signal)
    ]);
    if (controller.signal.aborted) return;
    if (files.status === 'fulfilled' && Array.isArray(files.value)) setProducts(files.value);
    if (skills.status === 'fulfilled' && Array.isArray(skills.value)) {
      const incoming = skills.value;
      setDrafts(current => {
        const versions = new Map(current?.map(item => [item.id, item]));
        return incoming.map(item => {
          const existing = versions.get(item.id);
          return existing && existing.version > item.version ? existing : item;
        });
      });
    }
    const message = (result: PromiseSettledResult<unknown>) => result.status === 'rejected'
      ? errorInfo(result.reason).message || '请刷新重试'
      : !Array.isArray(result.value) ? '服务端未返回数据' : '';
    const fileError = message(files), skillError = message(skills);
    setError([fileError && `交付文件：${fileError}`, skillError && `技能草稿：${skillError}`].filter(Boolean).join('；'));
    request.current = null;
    setLoading(false);
  }, [session]);

  useEffect(() => {
    void refresh();
    return () => { request.current?.abort(); request.current = null; };
  }, [refresh, running]);
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => void refresh(true), 8000);
    return () => clearInterval(timer);
  }, [running, refresh]);

  return <section ref={panel} className="work-products" aria-label="任务成果">
    <div className="work-toolbar">
      {product || draft ? <Button label="返回成果列表" variant="ghost" onClick={() => setSelected(null)} /> : <span role="status">
        {products ? `${products.length} 份成果` : '交付文件未读到'} · {drafts ? `${drafts.filter(d => d.status === 'PENDING').length} 项待审核` : '技能草稿未读到'}
      </span>}
      <Button label="刷新成果" className="work-refresh" variant="ghost" isLoading={loading} onClick={() => void refresh()} />
    </div>
    {error && <Banner status="error" role="alert" title="部分成果未读到" description={error} />}
    {loading && !products && !drafts && <p role="status">正在读取成果…</p>}
    {product ? <ProductDetail key={product.id} session={session} product={product} />
      : draft ? <DraftDetail key={draft.id} session={session} draft={draft} onChange={updated => setDrafts(items => items?.map(item => item.id === updated.id && item.version <= updated.version ? updated : item) || null)} onRefresh={() => void refresh()} />
        : <>
          <h3>交付文件</h3>
          {products?.length === 0 && <EmptyState className="work-empty" title="还没有交付文件"
            description="Pilot 生成的报告、表格和其他文件会列在这里。" isCompact />}
          <ul className="work-list">{products?.map(item => <li key={item.id}><button data-work-key={`product:${item.id}`} onClick={() => setSelected({ kind: 'product', id: item.id })}>
            <strong>{item.title}</strong><span>{item.summary || item.filename}</span><small>{item.filename} · {formatBytes(item.bytes)} · {dateText(item.createdAt)}</small>
          </button></li>)}</ul>
          <h3>可复用的经验</h3>
          <p className="work-hint">先核对步骤与依据，接受后才会加入当前项目的技能。</p>
          <ul className="work-list">{drafts?.map(item => <li key={item.id}><button data-work-key={`draft:${item.id}`} onClick={() => setSelected({ kind: 'draft', id: item.id })}>
            <strong>{item.name}<Badge label={STATUS[item.status] || item.status} variant={item.status === 'CONFLICT' || item.status === 'PENDING' ? 'warning' : 'neutral'} /></strong><span>{item.description}</span>
          </button></li>)}</ul>
          {drafts?.length === 0 && <EmptyState className="work-empty" title="暂无技能草稿" isCompact />}
        </>}
  </section>;
}

function ProductDetail({ session, product }: { session: string; product: S.WorkProductServiceProduct }) {
  const [preview, setPreview] = useState<S.WorkProductServicePreview | null>(null);
  const [previewError, setPreviewError] = useState('');
  const [downloadError, setDownloadError] = useState('');
  const [retry, setRetry] = useState(0);
  const downloadAbort = useRef<AbortController | null>(null);
  useEffect(() => () => downloadAbort.current?.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    setPreview(null); setPreviewError('');
    api.productPreview(session, product.id, controller.signal).then(value => {
      if (!value) throw new Error('服务端未返回成果预览。');
      if (!controller.signal.aborted) setPreview(value);
    }).catch(failure => { if (!controller.signal.aborted) setPreviewError(errorInfo(failure).message || '无法读取成果预览。'); });
    return () => controller.abort();
  }, [session, product.id, retry]);

  const download = async () => {
    setDownloadError('');
    const controller = new AbortController();
    downloadAbort.current = controller;
    try {
      const blob = await api.downloadProduct(session, product.id, controller.signal);
      if (controller.signal.aborted) return;
      const url = URL.createObjectURL(blob);
      const anchor = Object.assign(document.createElement('a'), { href: url, download: product.filename });
      document.body.append(anchor); anchor.click(); anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch (failure) {
      if (!controller.signal.aborted) setDownloadError(errorInfo(failure).message || '请重试下载。');
    } finally { if (downloadAbort.current === controller) downloadAbort.current = null; }
  };
  /** 专项文件审阅只从具体成果进入，不在全局导航占一个平级入口。 */
  const openInWorkbench = () => {
    useStore.getState().setUi({ inspectorOpen: true, inspectorTab: 'files', workbenchFileId: null });
  };
  return <article className="work-detail">
    <h2 tabIndex={-1}>{product.title}</h2><p>{product.summary}</p>
    <p className="work-hint">{product.filename} · {dateText(product.createdAt)}</p>
    <div className="work-actions">
      <Button label="下载文件" clickAction={download} />
      <Button label="审阅工作区文件" variant="ghost" onClick={openInWorkbench} />
    </div>
    {downloadError && <Banner status="error" role="alert" title="下载失败" description={downloadError} />}
    {previewError && <Banner status="error" role="alert" title="预览读取失败" description={previewError}
      endContent={<Button label="重试预览" variant="ghost" onClick={() => setRetry(value => value + 1)} />} />}
    {!preview && !previewError && <p role="status">正在读取预览…</p>}
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
  const [revertVersion, setRevertVersion] = useState<number | null>(null);
  const pending = useRef(false);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const decide = async (action: string, version = draft.version) => {
    if (pending.current) return;
    pending.current = true;
    setBusy(true); setError('');
    try { const updated = await api.decideSkill(session, draft.id, action, version); if (!updated) throw new Error('服务端返回空审核结果，请刷新后重试'); if (alive.current) onChange(updated); }
    catch (e) { if (alive.current) { setError(errorInfo(e).message || '操作失败'); onRefresh(); } }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  };
  return <article className="work-detail">
    <h2 tabIndex={-1}>{draft.name}</h2><p>{draft.description}</p><p className="work-hint">{STATUS[draft.status] || draft.status} · 当前项目</p>
    <h3>经验依据</h3><p className="work-evidence">{draft.evidence}</p>
    <h3>技能步骤</h3><div className="work-preview"><MarkdownBody text={draft.body} /></div>
    {draft.previousContent != null && <Collapsible trigger="查看接受前的版本"><pre tabIndex={0}>{draft.previousContent}</pre></Collapsible>}
    {error && <Banner status="error" role="alert" title="无法保存审核结果" description={error} />}
    <div className="work-actions">
      {draft.status === 'PENDING' && <Button label="接受技能" variant="primary" isDisabled={busy} onClick={() => decide('approve')} />}
      {['PENDING', 'CONFLICT'].includes(draft.status) && <Button label="拒绝草稿" isDisabled={busy} onClick={() => decide('reject')} />}
      {draft.status === 'APPROVED' && <Button label="撤回此版本" isDisabled={busy} onClick={() => setRevertVersion(draft.version)} />}
      {busy && <span role="status">正在保存审核结果…</span>}
    </div>
    {revertVersion !== null && <AlertDialog isOpen title="撤回技能版本？"
      description={`${draft.name} 将恢复到接受前的内容。若文件之后被修改，服务端会停止操作。`}
      cancelLabel="取消" actionLabel="确认撤回"
      onOpenChange={open => { if (!open) setRevertVersion(null); }}
      onAction={() => { setRevertVersion(null); void decide('revert', revertVersion); }} />}
  </article>;
}
