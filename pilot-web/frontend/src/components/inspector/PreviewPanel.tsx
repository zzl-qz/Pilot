import { statusLetter } from './diff-format.js';
import type { ChangeFile } from '../../types/core.js';
import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import { api } from '../../api.js';
import { useStore } from '../../store.js';
import { formatBytes } from '../../lib/attachments/attachments.js';
import { writeClipboard } from '../../lib/clipboard.js';
import { detectCodeLanguage, tokenizeCodeLine } from '../../lib/code-highlight.js';
import { sortChangeFiles, splitPath, statusKind } from '../../lib/changes.js';
import { errorInfo } from '../../lib/errors.js';
import { MarkdownBody } from '../MarkdownBody.jsx';
import { StructuredDocumentView } from '../files/StructuredDocumentView.jsx';
import { ImageFileViewer, MediaFileViewer, previewRawUrl } from './UniversalPreview.jsx';
import { OfficePreview, PdfPreview } from './PreviewMedia.jsx';

/* ---------- 通用文件预览面板（内置跨平台 kkFileView 体验） ---------- */

const KIND_LABELS: Record<string, string> = {
  image: '图片', pdf: 'PDF', audio: '音频', video: '视频',
  docx: 'Word', xlsx: 'Excel', pptx: 'PPT', archive: '压缩包'
};

export function PreviewPanel() {
  const sessionId = useStore((state) => state.sessionId);
  const workspace = useStore((state) => state.workspace);
  const path = useStore((state) => state.ui.previewPath);
  const data = useStore((state) => state._previewData);
  const loading = useStore((state) => state._previewLoading);
  const line = useStore((state) => state._previewLine);
  const request = useStore((state) => state._previewRequest);
  const report = useStore((state) => state.changeReport);
  const [wrap, setWrap] = useState(false);
  const [opening, setOpening] = useState(false);

  const changed = useMemo(() => sortChangeFiles<ChangeFile>(Array.isArray(report?.files) ? report.files : []).slice(0, 12), [report]);
  const activePath = data?.path || path;
  const kind = data?.kind || (data?.binary ? 'binary' : 'text');
  const isMarkdown = kind === 'text' && /\.(md|markdown|mdx)$/i.test(activePath || '');
  const isTabular = kind === 'text' && /\.(csv|tsv)$/i.test(activePath || '');
  const codeLang = useMemo(() => (kind === 'text' && !isMarkdown && !isTabular ? detectCodeLanguage(activePath || '') : null), [kind, isMarkdown, isTabular, activePath]);
  const content = data?.content || '';
  const lines = useMemo(() => (content ? content.split(/\r\n|\r|\n/) : []), [content]);
  const target = line && line <= lines.length ? line : null;
  const source = useRef<HTMLPreElement>(null);
  const highlighted = useRef<HTMLSpanElement>(null);

  useLayoutEffect(() => {
    const row = highlighted.current;
    const pane = source.current;
    if (!row || !pane) return;
    const rowOffset = row.getBoundingClientRect().top - pane.getBoundingClientRect().top;
    pane.scrollTop += rowOffset - pane.clientHeight / 3;
  }, [loading, request, target, wrap]);

  const rawUrl = activePath && (sessionId || workspace) ? previewRawUrl(sessionId, workspace, activePath, false) : '';
  const downloadUrl = activePath && (sessionId || workspace) ? previewRawUrl(sessionId, workspace, activePath, true) : '';

  const copy = async () => {
    if (!content) return;
    try {
      if (!await writeClipboard(content)) throw new Error('clipboard unavailable');
      useStore.getState().showNotice('已复制文件内容');
    } catch {
      useStore.getState().showNotice('复制失败，请手动选择内容');
    }
  };

  const openNative = async (mode: 'open' | 'reveal') => {
    if (!activePath || opening || (!sessionId && !workspace)) return;
    setOpening(true);
    try {
      const result = await api.openFileNative(sessionId, workspace, activePath, mode);
      if (!result) {
        useStore.getState().showNotice('服务端未返回打开结果');
      } else {
        useStore.getState().showNotice(result.ok ? result.message : [result.message, result.alternative].filter(Boolean).join(' '));
      }
    } catch (failure) {
      useStore.getState().showNotice(errorInfo(failure).message || '调用系统应用失败');
    } finally {
      setOpening(false);
    }
  };

  function renderCodeLines(withSourceClass: boolean) {
    const preClass = `preview-body${wrap ? '' : ' is-nowrap'}${withSourceClass ? ' preview-source' : ''}`;
    const highlightLimit = lines.length <= 2500 ? codeLang : null;
    return (
      <pre ref={source} id="preview-body" className={preClass}>
        {lines.map((text, index) => {
          const number = index + 1;
          const selected = number === target;
          const tokens = highlightLimit ? tokenizeCodeLine(text, highlightLimit) : null;
          return (
            <span key={number} ref={selected ? highlighted : undefined} data-line={number}
              aria-current={selected ? 'location' : undefined} className="preview-source-line">
              {tokens
                ? tokens.map((tok, i) => tok.kind === 'plain' ? tok.text : <span key={i} className={`tok-${tok.kind}`}>{tok.text}</span>)
                : (text || '\u200b')}
              {tokens && !text ? '\u200b' : null}
            </span>
          );
        })}
        {data?.truncated && '\n…（内容过长，已截断）'}
      </pre>
    );
  }

  function renderContent() {
    if (!data || loading) return null;
    if (kind === 'image' && rawUrl) return <ImageFileViewer url={rawUrl} name={activePath} />;
    if (kind === 'pdf' && rawUrl) return <PdfPreview url={rawUrl} name={activePath} />;
    if ((kind === 'audio' || kind === 'video') && rawUrl) {
      return <MediaFileViewer kind={kind} url={rawUrl} mimeType={data.mimeType} />;
    }
    if ((kind === 'docx' || kind === 'xlsx' || kind === 'pptx' || kind === 'archive') && data.structured) {
      return <OfficePreview structured={data.structured} />;
    }
    if (data.binary || kind === 'binary') {
      return (
        <div className="upv-binary-card">
          <p>该文件为通用二进制格式（{formatBytes(data.size, '0 B')}），可直接交给本机默认软件打开或下载查看。</p>
          <div className="upv-binary-actions">
            <button type="button" className="btn" disabled={opening} onClick={() => void openNative('open')}>系统默认应用打开</button>
            <button type="button" className="btn" disabled={opening} onClick={() => void openNative('reveal')}>在文件夹中显示</button>
            {downloadUrl && <a className="btn" href={downloadUrl} download>下载文件</a>}
          </div>
        </div>
      );
    }
    if (line && content) return renderCodeLines(true);
    const text = data.truncated ? `${content}\n\n…（内容过长，已截断）` : (content || '（空文件）');
    if (isMarkdown) return <div className="bubble preview-markdown"><MarkdownBody text={text} /></div>;
    if (isTabular) return <StructuredDocumentView text={content} filename={activePath} />;
    if (content) return renderCodeLines(false);
    return <pre id="preview-body" className={`preview-body${wrap ? '' : ' is-nowrap'}`}>{text}</pre>;
  }

  const canRichPreview = Boolean(data && (!data.binary || kind !== 'binary'));
  return (
    <div id="panel-preview" className={`panel${canRichPreview ? ' has-content' : ''}`}>
      <div className="panel-toolbar">
        <input
          id="preview-path"
          className="preview-path"
          value={path}
          placeholder="文件路径"
          aria-label="预览文件路径"
          onChange={(event) => useStore.getState().setUi({ previewPath: event.target.value })}
 onKeyDown={(event) => { if (event.nativeEvent?.isComposing || event.keyCode === 229) return; if (event.key === 'Enter') { event.preventDefault(); useStore.getState().openPreview(path); } }}
        />
        <button type="button" className="btn" id="preview-refresh" disabled={!path.trim()} onClick={() => useStore.getState().openPreview(path)}>打开</button>
        {kind === 'text' && (
          <button type="button" className={`btn preview-wrap${wrap ? ' is-on' : ''}`} title="长行是否自动换行" aria-pressed={wrap} onClick={() => setWrap((value) => !value)}>换行</button>
        )}
        {kind === 'text' && (
          <button type="button" className="btn" disabled={!content} onClick={copy}>复制</button>
        )}
        {activePath && (
          <>
            <button type="button" className="btn" title="用系统默认应用打开" disabled={opening} onClick={() => void openNative('open')}>系统打开</button>
            <button type="button" className="btn" title="在系统文件夹中定位" disabled={opening} onClick={() => void openNative('reveal')}>定位</button>
            {downloadUrl && <a className="btn" href={downloadUrl} download title="下载原文件">下载</a>}
          </>
        )}
      </div>
      {data && (
        <div className="preview-meta">
          <span className="preview-meta-path" title={activePath}>{activePath}</span>
          {kind === 'text' && <span className="preview-meta-item">{lines.length} 行</span>}
          <span className="preview-meta-item">{formatBytes(data.size, '0 B')}</span>
          {KIND_LABELS[kind] && <span className="preview-meta-tag">{KIND_LABELS[kind]}</span>}
          {codeLang && <span className="preview-meta-tag">{codeLang.toUpperCase()}</span>}
          {isMarkdown && <span className="preview-meta-tag">Markdown</span>}
          {data.truncated && <span className="preview-meta-tag is-warn">已截断</span>}
          {line && kind === 'text' && <span className={`preview-meta-tag${target ? '' : ' is-warn'}`}>
            {target ? `第 ${target} 行` : `第 ${line} 行不在预览范围内`}
          </span>}
        </div>
      )}
      {loading && <p className="panel-empty">读取中…</p>}
      {renderContent()}
      {!loading && !data && (
        <div className="preview-empty">
          <p>支持直接预览代码、Markdown、图片、PDF、音视频、Word/Excel/PPT 与压缩包；选一个文件或输入路径即可查看。</p>
          {changed.length > 0 && (
            <ul className="preview-suggest">
              {changed.map((file) => (
                <li key={file.path}>
                  <button type="button" title={file.path} onClick={() => useStore.getState().openPreview(file.path)}>
                    <span className={`change-status is-${statusKind(file.status)}`}>{statusLetter(file.status)}</span>
                    <span className="preview-suggest-name">{splitPath(file.path).name}</span>
                    <span className="preview-suggest-dir">{splitPath(file.path).dir}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
