import { useCallback, useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import type {
  WorkbenchAnnotation, WorkbenchFile, WorkbenchPreview, WorkbenchVersion
} from '../types/api.js';
import {
  changeHint, emptyPdfForm, formatBytes, formatLabel, formatMoment, pdfOperations, type PdfForm
} from '../lib/file-workbench.js';
import './file-workbench.css';

/**
 * 文件工作台：对标 Codex 的审阅模式。工作区里的真实文件是唯一源，
 * 这里只负责预览、批注、用本机应用打开、感知外部改动、版本回退与交付。
 */
export default function FileWorkbench() {
  const session = useStore(state => state.sessionId);
  const focusId = useStore(state => state.ui.workbenchFileId);
  const [files, setFiles] = useState<WorkbenchFile[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [preview, setPreview] = useState<WorkbenchPreview | null>(null);
  const [page, setPage] = useState(1);
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [imageError, setImageError] = useState('');
  const [imageAttempt, retryImage] = useState(0);
  const [annotations, setAnnotations] = useState<WorkbenchAnnotation[]>([]);
  const [versions, setVersions] = useState<WorkbenchVersion[]>([]);
  const [text, setText] = useState('');
  const [annotationText, setAnnotationText] = useState('');
  const [annotationAnchor, setAnnotationAnchor] = useState('');
  const [form, setForm] = useState<PdfForm>(emptyPdfForm);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [listError, setListError] = useState('');
  const [notice, setNotice] = useState('');
  const [showText, setShowText] = useState(false);
  const hashes = useRef<Record<string, string>>({});
  const alive = useRef(true);
  const detailRequest = useRef(0);
  const listRequest = useRef(0);

  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const message = (failure: unknown) => errorInfo(failure).message || '操作失败';

  const active = files.find(file => file.id === activeId) || null;

  const refresh = useCallback(async (preferId?: string) => {
    if (!session) { setFiles([]); return; }
    const request = ++listRequest.current;
    const isCurrent = () => alive.current && listRequest.current === request && useStore.getState().sessionId === session;
    try {
      const next = await api.workbenchFiles(session);
      if (!isCurrent()) return;
      if (!Array.isArray(next)) throw new Error('服务端未返回文件列表');
      setListError('');
      const changed = next.some(file => hashes.current[file.id] && hashes.current[file.id] !== file.sha256);
      for (const file of next) hashes.current[file.id] = file.sha256;
      if (changed) setNotice('检测到文件在外部被修改，已按最新内容刷新预览');
      setFiles(next);
      setActiveId(current => (next.find(file => file.id === (preferId || current)) || next[0])?.id || null);
    } catch (failure) { if (isCurrent()) setListError(message(failure)); }
  }, [session]);

  useEffect(() => {
    if (!session) return;
    void refresh(focusId || undefined);
    const timer = window.setInterval(() => void refresh(), 6000);
    return () => window.clearInterval(timer);
  }, [session, focusId, refresh]);

  const loadDetail = useCallback(async (id: string, force = false) => {
    if (!session) return;
    const request = ++detailRequest.current;
    const isCurrent = () => alive.current && detailRequest.current === request && useStore.getState().sessionId === session;
    try {
      const [loadedPreview, loadedAnnotations, loadedVersions, loadedText] = await Promise.all([
        api.workbenchPreview(session, id), api.workbenchAnnotations(session, id),
        api.workbenchVersions(session, id), api.workbenchText(session, id)
      ]);
      if (!isCurrent()) return;
      setPreview(loadedPreview);
      setAnnotations(loadedAnnotations || []);
      setVersions(loadedVersions || []);
      setText(loadedText?.text || '');
      setPage(previous => force || !loadedPreview ? 1 : Math.min(previous, Math.max(1, loadedPreview.pages)));
    } catch (failure) { if (isCurrent()) setError(message(failure)); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session]);

  useEffect(() => {
    setPreview(null);
    setImageError('');
    setAnnotations([]);
    setVersions([]);
    setText('');
    if (session && activeId) void loadDetail(activeId, true);
    return () => { detailRequest.current++; };
  }, [session, activeId, active?.sha256, loadDetail]);

  useEffect(() => {
    if (!session || !activeId || preview?.fileId !== activeId || preview.pages < 1) return;
    setImageError('');
    let objectUrl: string | null = null;
    let cancelled = false;
    void api.workbenchPreviewImage(session, activeId, page).then(url => {
      if (cancelled) { URL.revokeObjectURL(url); return; }
      objectUrl = url;
      setImageUrl(url);
    }).catch(failure => { if (!cancelled) setImageError(message(failure)); });
    return () => {
      cancelled = true;
      setImageUrl(null);
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session, activeId, page, preview?.fileId, preview?.notice, imageAttempt]);

  const run = async (label: string, action: () => Promise<void>) => {
    setBusy(label); setError('');
    try { await action(); } catch (failure) { if (alive.current) setError(message(failure)); }
    finally { if (alive.current) setBusy(''); }
  };

  const download = () => void run('下载', async () => {
    if (!session || !active) return;
    const blob = await api.workbenchDownload(session, active.id);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url; anchor.download = active.filename;
    document.body.append(anchor); anchor.click(); anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 60000);
  });

  const openNative = () => void run('打开', async () => {
    if (!session || !active) return;
    const result = await api.workbenchOpen(session, active.id);
    if (!result) { setError('打开请求没有返回结果，请点「下载文件」后用本机应用打开'); return; }
    if (result.ok) setNotice(result.reason);
    else setError(`${result.reason} ${result.alternative}`.trim());
  });

  const publish = () => void run('发布', async () => {
    if (!session || !active) return;
    await api.workbenchPublish(session, active.id, { title: active.filename.replace(/\.[^.]+$/, '') });
    setNotice('已登记为成果，可在「成果」页看到');
  });

  const snapshot = () => void run('快照', async () => {
    if (!session || !active) return;
    await api.workbenchSnapshot(session, active.id, '手动快照');
    setVersions((await api.workbenchVersions(session, active.id)) || []);
    setNotice('已保存一个快照，之后可对比外部改动或回退');
  });

  const revert = (version: number) => {
    if (!session || !active) return;
    useStore.getState().askConfirm({
      title: '回退文件版本？',
      message: `将「${active.filename}」回退到 v${version}。回退前的状态会保存为新快照。`,
      confirmText: '确认回退',
      onConfirm: () => run('回退', async () => {
        await api.workbenchRevert(session, active.id, version);
        await refresh(active.id);
        await loadDetail(active.id, true);
        setNotice(`已回退到 v${version}，回退前的状态另存为新快照`);
      })
    });
  };

  const annotate = () => void run('批注', async () => {
    if (!session || !active) return;
    await api.workbenchAnnotate(session, active.id, { page, anchor: annotationAnchor, text: annotationText });
    setAnnotationText(''); setAnnotationAnchor('');
    setAnnotations((await api.workbenchAnnotations(session, active.id)) || []);
    setVersions((await api.workbenchVersions(session, active.id)) || []);
    setNotice('批注已保存，点「发给模型修改」即可按最小范围改动');
  });

  const sendChange = (annotation: WorkbenchAnnotation) => void run('发送', async () => {
    if (!session || !active) return;
    const result = await api.workbenchChangeRequest(session, active.id, annotation.id);
    if (!result?.prompt) { setError('没有生成修改请求，请重新保存批注'); return; }
    await api.sendMessage(session, { content: result.prompt });
    setNotice('已把批注作为审阅指导发给模型，改完会自动刷新这里的文件');
  });

  const applyPdf = () => void run('页面操作', async () => {
    if (!session || !active) return;
    const operations = pdfOperations(form);
    if (!operations) { setError('请至少填写一个页面操作'); return; }
    const created = await api.workbenchPdfOperations(session, active.id, operations);
    if (!created) { setError('页面操作没有返回结果，请检查原文件是否仍然存在'); return; }
    setForm(emptyPdfForm());
    await refresh(created.id);
    setNotice(`已另存为 ${created.filename}，原文件未被覆盖`);
  });

  const select = (id: string) => {
    if (id === activeId) return;
    setActiveId(id); setPage(1); setError(''); setNotice(''); setShowText(false);
    setAnnotationText(''); setAnnotationAnchor(''); setForm(emptyPdfForm());
  };

  return <section className="fw" aria-label="文件工作台">
    {error && <div role="alert" className="fw-alert">{error}</div>}
    {listError && <div role="alert" className="fw-alert">{listError}</div>}
    {notice && <div role="status" className="fw-notice">{notice}</div>}
    {!session && <p role="status">请先打开一个会话。</p>}
    <div className="fw-layout">
      <ul className="fw-files" aria-label="工作区文件">
        {files.map(file => <li key={file.id}>
          <button type="button" className={`fw-file${file.id === activeId ? ' is-active' : ''}`} onClick={() => select(file.id)}>
            <span className="fw-file-name">{file.filename}</span>
            <span className="fw-file-meta">{formatLabel(file.format)} · {formatBytes(file.bytes)} · {formatMoment(file.modifiedAt)}</span>
            {file.changedExternally && <span className="fw-file-flag">外部已修改</span>}
          </button>
        </li>)}
        {!files.length && !listError && <li className="fw-empty">当前工作区还没有 Office 文件。让模型生成一份，或把文件放进工作区就会自动出现在这里。</li>}
      </ul>
      {active && <section className="fw-detail" aria-label={active.filename}>
        <h3>{active.filename}</h3>
        <p className="fw-hint">{active.path} · {changeHint(active.changedExternally)}</p>
        <div className="fw-toolbar">
          <button type="button" className="btn" disabled={!!busy} onClick={openNative}>用本机应用打开</button>
          <button type="button" className="btn" disabled={!!busy} onClick={download}>下载文件</button>
          <button type="button" className="btn" disabled={!!busy} onClick={publish}>发布为成果</button>
          <button type="button" className="btn" disabled={!!busy} onClick={snapshot}>保存快照</button>
          <button type="button" className="btn" onClick={() => useStore.getState().setUi({ inspectorOpen: true, inspectorTab: 'changes' })}>查看工作区改动</button>
          {busy && <small>{busy}中…</small>}
        </div>

        {preview && <div className="fw-preview">
          <div className="fw-preview-head">
            <span>第 {page} / {preview.pages} 页</span>
            <div>
              <button type="button" className="btn" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
              <button type="button" className="btn" disabled={page >= preview.pages} onClick={() => setPage(page + 1)}>下一页</button>
            </div>
          </div>
          {imageError ? <p role="alert" className="fw-alert">{imageError}{' '}
            <button type="button" className="btn" onClick={() => retryImage(value => value + 1)}>重试预览</button>
          </p> : imageUrl ? <img className="fw-preview-image" src={imageUrl} alt={`${active.filename} 第 ${page} 页预览`} />
            : <p role="status">{preview.pages ? '正在生成预览…' : '此文件没有可预览的页面。'}</p>}
          <p className="fw-hint">{preview.notice}</p>
        </div>}

        <div className="fw-annotation">
          <h4>在第 {page} 页留下批注</h4>
          <input aria-label="批注锚点" placeholder="锚点（可选，例如「第二段」「表格第 3 行」）" value={annotationAnchor}
            onChange={event => setAnnotationAnchor(event.target.value)} />
          <textarea aria-label="批注内容" placeholder="想改什么？例如：把标题改成季度报告，其它不动" value={annotationText}
            onChange={event => setAnnotationText(event.target.value)} />
          <button type="button" className="btn" disabled={!!busy || !annotationText.trim()} onClick={annotate}>保存批注</button>
          <ul className="fw-annotations">
            {annotations.map(item => <li key={item.id}>
              <span>第 {item.page} 页{item.anchor ? ` · ${item.anchor}` : ''}：{item.text}</span>
              <button type="button" className="btn" disabled={!!busy} onClick={() => sendChange(item)}>发给模型修改</button>
            </li>)}
            {!annotations.length && <li className="fw-empty">还没有批注。预览时指出要改的地方，模型会只改那一处。</li>}
          </ul>
        </div>

        <div className="fw-versions">
          <h4>版本快照</h4>
          <button type="button" className="btn" onClick={() => setShowText(!showText)}>{showText ? '收起内容读回' : '查看内容读回'}</button>
          {showText && <pre className="fw-text">{text || '（无文本内容）'}</pre>}
          <ul>
            {versions.map(item => <li key={item.version}>
              <span>v{item.version} · {item.label} · {formatMoment(item.createdAt)}</span>
              <button type="button" className="btn" disabled={!!busy} onClick={() => revert(item.version)}>回退</button>
            </li>)}
            {!versions.length && <li className="fw-empty">还没有快照。保存一次后即可感知外部改动并回退。</li>}
          </ul>
        </div>

        {active.format === 'pdf' && <div className="fw-pdf">
          <h4>页面级操作（结果另存为新文件）</h4>
          <label>保留页 <input aria-label="保留页" placeholder="1,3,5-8" value={form.keep}
            onChange={event => setForm({ ...form, keep: event.target.value })} /></label>
          <label>删除页 <input aria-label="删除页" placeholder="2,9" value={form.drop}
            onChange={event => setForm({ ...form, drop: event.target.value })} /></label>
          <label>旋转角度 <select aria-label="旋转角度" value={form.rotateDegrees}
            onChange={event => setForm({ ...form, rotateDegrees: event.target.value })}>
            {['90', '-90', '180', '-180', '270', '-270', '0'].map(value => <option key={value} value={value}>{value}°</option>)}
          </select></label>
          <label>旋转页 <input aria-label="旋转页" placeholder="1,4" value={form.rotatePages}
            onChange={event => setForm({ ...form, rotatePages: event.target.value })} /></label>
          <label>水印（仅 ASCII） <input aria-label="水印" placeholder="DRAFT" value={form.watermark}
            onChange={event => setForm({ ...form, watermark: event.target.value })} /></label>
          <label>合并同目录 PDF <input aria-label="合并文件" placeholder="a.pdf, b.pdf" value={form.merge}
            onChange={event => setForm({ ...form, merge: event.target.value })} /></label>
          <label className="fw-check"><input type="checkbox" checked={form.pageNumbers}
            onChange={event => setForm({ ...form, pageNumbers: event.target.checked })} />添加页码</label>
          <button type="button" className="btn" disabled={!!busy} onClick={applyPdf}>应用并另存</button>
        </div>}
      </section>}
    </div>
  </section>;
}
