import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertDialog } from '@astryxdesign/core/AlertDialog';
import { Badge } from '@astryxdesign/core/Badge';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { CheckboxInput } from '@astryxdesign/core/CheckboxInput';
import { Collapsible } from '@astryxdesign/core/Collapsible';
import { EmptyState } from '@astryxdesign/core/EmptyState';
import { TextArea } from '@astryxdesign/core/TextArea';
import { TextInput } from '@astryxdesign/core/TextInput';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import type { WorkbenchAnnotation, WorkbenchFile, WorkbenchPreview, WorkbenchVersion } from '../types/api.js';
import {
  changeHint, emptyPdfForm, formatBytes, formatLabel, formatMoment, pdfOperations, type PdfForm
} from '../lib/file-workbench.js';
import './file-workbench.css';

const failureText = (failure: unknown) => errorInfo(failure).message || '操作失败，请重试。';

export default function FileWorkbench() {
  const session = useStore(state => state.sessionId);
  const focusId = useStore(state => state.ui.workbenchFileId);
  return session ? <SessionFiles key={session} session={session} focusId={focusId || null} />
    : <div className="fw"><EmptyState title="请先打开一个会话" description="会话工作区中的 Office 文件和 PDF 会列在这里。" isCompact /></div>;
}

function SessionFiles({ session, focusId }: { session: string; focusId: string | null }) {
  const [files, setFiles] = useState<WorkbenchFile[]>([]);
  const [activeId, setActiveId] = useState(focusId);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [loaded, setLoaded] = useState(false);
  const request = useRef<AbortController | null>(null);
  const panel = useRef<HTMLElement>(null);
  const previousRow = useRef<string | null>(null);
  const active = files.find(file => file.id === activeId);

  const refresh = useCallback(async (preferId?: string, background = false) => {
    if (background && request.current) return;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    if (!background) setLoading(true);
    try {
      const next = await api.workbenchFiles(session, controller.signal);
      if (!Array.isArray(next)) throw new Error('服务端未返回文件列表。');
      if (controller.signal.aborted) return;
      setFiles(next); setLoaded(true); setError('');
      setActiveId(current => {
        const wanted = preferId || current;
        return next.some(file => file.id === wanted) ? wanted : null;
      });
    } catch (failure) {
      if (!controller.signal.aborted) setError(failureText(failure));
    } finally {
      if (request.current === controller) {
        request.current = null;
        setLoading(false);
      }
    }
  }, [session]);

  useEffect(() => {
    void refresh(focusId || undefined);
    const timer = window.setInterval(() => void refresh(undefined, true), 6000);
    return () => { window.clearInterval(timer); request.current?.abort(); request.current = null; };
  }, [focusId, refresh]);

  useEffect(() => {
    if (activeId) panel.current?.querySelector<HTMLElement>('.fw-detail h3')?.focus({ preventScroll: true });
    else if (previousRow.current) {
      const rows = panel.current?.querySelectorAll<HTMLButtonElement>('[data-file-id]');
      const row = [...(rows || [])].find(button => button.dataset.fileId === previousRow.current);
      (row || panel.current?.querySelector<HTMLButtonElement>('.fw-refresh'))?.focus({ preventScroll: true });
      previousRow.current = null;
    }
  }, [active?.id, activeId]);

  return <section className="fw" ref={panel} aria-label="文件工作台">
    <div className="fw-toolbar fw-list-toolbar">
      {active ? <Button label="返回文件列表" variant="ghost" onClick={() => setActiveId(null)} /> : <span role="status">
        {loading && !loaded ? '正在读取文件…' : loaded ? `${files.length} 个文档` : '文件列表尚未读到'}
      </span>}
      <Button label="刷新文件" className="fw-refresh" variant="ghost" isLoading={loading} onClick={() => void refresh()} />
    </div>
    {error && <Banner status="error" role="alert" title="无法读取文件列表" description={error} />}
    {active ? <FileDetail key={active.id} session={session} file={active} refresh={refresh} />
      : loaded && !files.length && !error ? <EmptyState title="工作区还没有可审阅的文档"
        description="生成或将 Office 文件、PDF 放入工作区后，可以在这里预览和批注。" isCompact />
        : <ul className="fw-files" aria-label="工作区文档">
          {files.map(file => <li key={file.id}>
            <button type="button" className="fw-file" data-file-id={file.id} onClick={() => { previousRow.current = file.id; setActiveId(file.id); }}>
              <strong>{file.filename}</strong>
              {file.changedExternally && <Badge label="外部已修改" variant="warning" />}
              <span>{formatLabel(file.format)} · {formatBytes(file.bytes)} · {formatMoment(file.modifiedAt)}</span>
            </button>
          </li>)}
        </ul>}
  </section>;
}

function FileDetail({ session, file, refresh }: { session: string; file: WorkbenchFile; refresh: (preferId?: string) => Promise<void> }) {
  const { id, sha256, filename } = file;
  const running = useStore(state => state.running);
  const [preview, setPreview] = useState<WorkbenchPreview | null>(null);
  const [previewError, setPreviewError] = useState('');
  const [previewAttempt, retryPreview] = useState(0);
  const [page, setPage] = useState(1);
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [imageError, setImageError] = useState('');
  const [imageAttempt, retryImage] = useState(0);
  const [annotations, setAnnotations] = useState<WorkbenchAnnotation[]>([]);
  const [versions, setVersions] = useState<WorkbenchVersion[]>([]);
  const [text, setText] = useState('');
  const [detailError, setDetailError] = useState('');
  const [detailLoading, setDetailLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const [annotationText, setAnnotationText] = useState('');
  const [annotationAnchor, setAnnotationAnchor] = useState('');
  const [form, setForm] = useState<PdfForm>(emptyPdfForm);
  const [busy, setBusy] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [revertVersion, setRevertVersion] = useState<number | null>(null);
  const pending = useRef(false);
  const downloadRequest = useRef<AbortController | null>(null);
  const alive = useRef(true);
  const seenHash = useRef(sha256);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; downloadRequest.current?.abort(); };
  }, []);
  useEffect(() => {
    if (seenHash.current === sha256) return;
    seenHash.current = sha256;
    setNotice('文件内容已更新，正在刷新预览。');
  }, [sha256]);

  useEffect(() => {
    const controller = new AbortController();
    setPreview(null); setPreviewError(''); setImageUrl(null); setImageError('');
    api.workbenchPreview(session, id, controller.signal).then(next => {
      if (!next) throw new Error('服务端未返回预览。');
      if (controller.signal.aborted) return;
      setPage(current => Math.min(current, Math.max(1, next.pages)));
      setPreview(next);
    }).catch(failure => { if (!controller.signal.aborted) setPreviewError(failureText(failure)); });
    return () => controller.abort();
  }, [session, id, sha256, previewAttempt]);

  useEffect(() => {
    if (!preview || preview.pages < 1) return;
    const controller = new AbortController();
    let objectUrl: string | null = null;
    setImageUrl(null); setImageError('');
    api.workbenchPreviewImage(session, id, page, controller.signal).then(url => {
      if (controller.signal.aborted) { URL.revokeObjectURL(url); return; }
      objectUrl = url;
      setImageUrl(url);
    }).catch(failure => { if (!controller.signal.aborted) setImageError(failureText(failure)); });
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [session, id, page, preview, imageAttempt]);

  useEffect(() => {
    const controller = new AbortController();
    const signal = controller.signal;
    setDetailLoading(true);
    Promise.allSettled([
      api.workbenchAnnotations(session, id, signal),
      api.workbenchVersions(session, id, signal),
      api.workbenchText(session, id, signal)
    ]).then(([notes, snapshots, readback]) => {
      if (signal.aborted) return;
      if (notes.status === 'fulfilled' && notes.value) setAnnotations(notes.value);
      if (snapshots.status === 'fulfilled' && snapshots.value) setVersions(snapshots.value);
      if (readback.status === 'fulfilled') setText(readback.value?.text || '');
      const failed = [notes, snapshots, readback].filter(result => result.status === 'rejected');
      setDetailError(failed.map(result => failureText(result.reason)).join('；'));
      setDetailLoading(false);
    });
    return () => controller.abort();
  }, [session, id, sha256, revision]);

  async function run(label: string, action: () => Promise<void>) {
    if (pending.current) return;
    pending.current = true;
    setBusy(label); setActionError(''); setNotice('');
    try { await action(); }
    catch (failure) { if (alive.current) setActionError(failureText(failure)); }
    finally {
      pending.current = false;
      if (alive.current) setBusy('');
    }
  }

  const download = () => run('下载', async () => {
    const controller = new AbortController();
    downloadRequest.current = controller;
    const blob = await api.workbenchDownload(session, id, controller.signal);
    if (controller.signal.aborted) return;
    const url = URL.createObjectURL(blob);
    const anchor = Object.assign(document.createElement('a'), { href: url, download: filename });
    document.body.append(anchor); anchor.click(); anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 60000);
  });

  const openNative = () => run('打开', async () => {
    const result = await api.workbenchOpen(session, id);
    if (!result?.ok) throw new Error([result?.reason, result?.alternative || '请下载文件后用本机应用打开。'].filter(Boolean).join(' '));
    setNotice(result.reason || '已交给本机应用打开。');
  });

  const publish = () => run('发布', async () => {
    const result = await api.workbenchPublish(session, id, { title: filename.replace(/\.[^.]+$/, '') });
    if (!result) throw new Error('服务端未返回登记结果，请到成果页核对。');
    setNotice('已登记为成果，可在「成果」页查看。');
  });

  const snapshot = () => run('快照', async () => {
    const saved = await api.workbenchSnapshot(session, id, '手动快照');
    if (!saved) throw new Error('服务端未返回快照，请刷新核对。');
    setRevision(value => value + 1);
    setNotice('已保存快照，可用于核对或回退文件。');
  });

  const revert = (version: number) => run('回退', async () => {
    if (useStore.getState().running) throw new Error('请等待当前任务结束后再回退。');
    const restored = await api.workbenchRevert(session, id, version);
    if (!restored) throw new Error('服务端未返回回退结果，请刷新核对。');
    if (!alive.current) return;
    await refresh();
    setRevision(value => value + 1); retryPreview(value => value + 1);
    setNotice(`已回退到 v${version}，回退前的文件已保存为快照。`);
  });

  const annotate = () => run('批注', async () => {
    const annotation = await api.workbenchAnnotate(session, id, { page, anchor: annotationAnchor.trim(), text: annotationText.trim() });
    if (!annotation) throw new Error('服务端未返回批注，请刷新核对。');
    setAnnotationText(''); setAnnotationAnchor('');
    setRevision(value => value + 1);
    setNotice('批注已保存。');
  });

  const sendChange = (annotation: WorkbenchAnnotation) => run('发送', async () => {
    if (useStore.getState().running) throw new Error('请等待当前任务结束后再发送批注。');
    const result = await api.workbenchChangeRequest(session, id, annotation.id);
    if (!result?.prompt) throw new Error('没有生成修改请求，请重新读取批注。');
    if (!alive.current) return;
    await api.sendMessage(session, { content: result.prompt });
    setNotice('已把批注发给模型修改。');
  });

  const applyPdf = () => run('页面操作', async () => {
    const operations = pdfOperations(form);
    if (!operations) throw new Error('请至少填写一个页面操作。');
    const created = await api.workbenchPdfOperations(session, id, operations);
    if (!created) throw new Error('页面操作未返回新文件，请刷新文件列表核对。');
    if (!alive.current) return;
    setForm(emptyPdfForm());
    useStore.getState().showNotice(`已另存为 ${created.filename}`);
    await refresh(created.id);
  });

  const disabled = !!busy;
  const previewFailed = previewError || imageError;
  const retry = previewError ? retryPreview : retryImage;
  const setPdfField = (key: keyof PdfForm, value: string | boolean) => setForm(current => ({ ...current, [key]: value }));

  return <section className="fw-detail" aria-label={filename}>
    <div><h3 tabIndex={-1}>{filename}</h3><p className="fw-hint">{file.path} · {changeHint(file.changedExternally)}</p></div>
    <div className="fw-toolbar">
      <Button label="用本机应用打开" isDisabled={disabled} onClick={openNative} />
      <Button label="下载文件" isDisabled={disabled} isLoading={busy === '下载'} onClick={download} />
      <Button label="发布为成果" variant="ghost" isDisabled={disabled} onClick={publish} />
      <Button label="保存快照" variant="ghost" isDisabled={disabled} onClick={snapshot} />
      <Button label="查看工作区改动" variant="ghost" onClick={() => useStore.getState().setUi({ inspectorOpen: true, inspectorTab: 'changes' })} />
    </div>
    {busy && <p role="status" className="fw-hint">{busy}中…</p>}
    {actionError && <Banner status="error" role="alert" title={actionError} />}
    {notice && <p role="status" className="fw-hint">{notice}</p>}

    <div className="fw-preview">
      <div className="fw-preview-head">
        <span>{preview?.pages ? `第 ${page} / ${preview.pages} 页` : '文件预览'}</span>
        {!!preview?.pages && <div>
          <Button label="上一页" size="sm" variant="ghost" isDisabled={page <= 1} onClick={() => { setImageUrl(null); setImageError(''); setPage(page - 1); }} />
          <Button label="下一页" size="sm" variant="ghost" isDisabled={page >= preview.pages} onClick={() => { setImageUrl(null); setImageError(''); setPage(page + 1); }} />
        </div>}
      </div>
      {previewFailed ? <Banner status="error" role="alert" title="无法显示预览" description={previewFailed}
        endContent={<Button label="重试预览" variant="ghost" onClick={() => retry(value => value + 1)} />} />
        : imageUrl && preview ? <img className="fw-preview-image" src={imageUrl} alt={`${filename} 第 ${page} 页预览`}
          onError={() => setImageError('预览图片无法显示，可重试或下载文件。')} />
          : preview?.pages === 0 ? <p className="fw-hint">此文件没有可预览的页面。</p>
            : <p className="fw-hint" role="status">正在生成预览…</p>}
      {preview?.notice && <p className="fw-hint">{preview.notice}</p>}
    </div>

    {detailError && <Banner status="error" role="alert" title="有审阅记录未读到" description={detailError}
      endContent={<Button label="重试读取记录" variant="ghost" onClick={() => setRevision(value => value + 1)} />} />}
    <div className="fw-section">
      <h4>第 {page} 页的批注</h4>
      <TextInput label="批注锚点" placeholder="例如第二段或表格第 3 行" value={annotationAnchor} onChange={setAnnotationAnchor} isOptional />
      <TextArea label="批注内容" placeholder="描述要修改的内容" value={annotationText} onChange={setAnnotationText} rows={2} />
      <Button label="保存批注" isDisabled={disabled || !annotationText.trim()} onClick={annotate} />
      <ul className="fw-records">
        {annotations.map(item => <li key={item.id}>
          <span>第 {item.page} 页{item.anchor ? ` · ${item.anchor}` : ''}：{item.text}</span>
          <Button label="发给模型修改" size="sm" variant="ghost" isDisabled={disabled || running} onClick={() => sendChange(item)} />
        </li>)}
      </ul>
      {!annotations.length && !detailError && <p className="fw-hint">{detailLoading ? '正在读取批注…' : '还没有批注。'}</p>}
    </div>

    <div className="fw-section">
      <h4>版本快照</h4>
      <Collapsible trigger="查看内容读回"><pre className="fw-text" tabIndex={0}>{detailLoading ? '正在读取内容…' : text || '（无文本内容）'}</pre></Collapsible>
      <ul className="fw-records">
        {versions.map(item => <li key={item.version}>
          <span>v{item.version} · {item.label} · {formatMoment(item.createdAt)}</span>
          <Button label="回退" size="sm" variant="ghost" isDisabled={disabled || running} onClick={() => setRevertVersion(item.version)} />
        </li>)}
      </ul>
      {!versions.length && !detailError && <p className="fw-hint">{detailLoading ? '正在读取快照…' : '还没有快照。'}</p>}
      {running && <p className="fw-hint">任务结束后可回退快照或发送批注。</p>}
    </div>

    {file.format === 'pdf' && <div className="fw-section">
      <h4>PDF 页面操作</h4>
      <p className="fw-hint">操作结果另存为新文件。页码可用逗号或范围填写。</p>
      <div className="fw-pdf-fields">
        <TextInput label="保留页" placeholder="1,3,5-8" value={form.keep} onChange={value => setPdfField('keep', value)} />
        <TextInput label="删除页" placeholder="2,9" value={form.drop} onChange={value => setPdfField('drop', value)} />
        <label className="fw-angle">旋转角度
          <select value={form.rotateDegrees} onChange={event => setPdfField('rotateDegrees', event.target.value)}>
            {['90', '-90', '180', '-180', '270', '-270', '0'].map(value => <option key={value} value={value}>{value}°</option>)}
          </select>
        </label>
        <TextInput label="旋转页" placeholder="1,4" value={form.rotatePages} onChange={value => setPdfField('rotatePages', value)} />
        <TextInput label="水印" description="仅支持 ASCII" placeholder="DRAFT" value={form.watermark} onChange={value => setPdfField('watermark', value)} />
        <TextInput label="合并同目录 PDF" placeholder="a.pdf, b.pdf" value={form.merge} onChange={value => setPdfField('merge', value)} />
      </div>
      <CheckboxInput label="添加页码" value={form.pageNumbers} onChange={value => setPdfField('pageNumbers', value)} />
      <Button label="应用并另存" isDisabled={disabled} onClick={applyPdf} />
    </div>}

    {revertVersion !== null && <AlertDialog title="回退文件版本？" isOpen
      description={`${filename} 将恢复到 v${revertVersion}。当前文件会另存为快照。`}
      cancelLabel="取消" actionLabel="确认回退"
      onOpenChange={open => { if (!open && !pending.current) setRevertVersion(null); }}
      onAction={() => { const version = revertVersion; setRevertVersion(null); void revert(version); }} />}
  </section>;
}
