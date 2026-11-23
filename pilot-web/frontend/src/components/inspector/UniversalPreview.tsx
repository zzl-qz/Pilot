import { lazy, Suspense, useEffect, useState } from 'react';
import { api } from '../../api.js';
import { formatBytes } from '../../lib/attachments/attachments.js';
import type {
  UniversalArchiveItem, UniversalDocBlock, UniversalSheetData, UniversalSlideData, UniversalStructuredPreview
} from '../../types/api.js';
import './universal-preview.css';

const PdfAttachmentPreview = lazy(() => import('../PdfAttachmentPreview.jsx'));

export function ImageFileViewer({ url, name }: { url: string; name: string }) {
  const [zoom, setZoom] = useState<'fit' | number>('fit');
  const [failed, setFailed] = useState(false);

  useEffect(() => { setZoom('fit'); setFailed(false); }, [url]);

  if (failed) {
    return <p className="panel-empty">图片加载失败，可点击上方「系统打开」或「下载」查看。</p>;
  }
  return (
    <div className="upv-media-wrap">
      <div className="upv-subbar">
        <button type="button" className="btn" onClick={() => setZoom(v => Math.max(0.25, (v === 'fit' ? 1 : v) - 0.25))}>缩小</button>
        <span className="upv-zoom-label">{zoom === 'fit' ? '适应' : `${Math.round(zoom * 100)}%`}</span>
        <button type="button" className="btn" onClick={() => setZoom(v => Math.min(3, (v === 'fit' ? 1 : v) + 0.25))}>放大</button>
        <button type="button" className="btn" onClick={() => setZoom('fit')}>适应窗口</button>
        <button type="button" className="btn" onClick={() => setZoom(1)}>1:1</button>
      </div>
      <div className="upv-image-stage">
        <img
          src={url}
          alt={name}
          className={`upv-image${zoom === 'fit' ? ' is-fit' : ''}`}
          style={zoom === 'fit' ? undefined : { width: `${Math.round(zoom * 100)}%` }}
          onError={() => setFailed(true)}
        />
      </div>
    </div>
  );
}

export function PdfFileViewer({ url, name }: { url: string; name: string }) {
  const [buffer, setBuffer] = useState<ArrayBuffer | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setBuffer(null);
    setError('');
    fetch(url, { signal: controller.signal })
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.arrayBuffer();
      })
      .then(data => { if (!controller.signal.aborted) setBuffer(data); })
      .catch(err => {
        if (!controller.signal.aborted) setError(err instanceof Error ? err.message : 'PDF 读取失败');
      });
    return () => controller.abort();
  }, [url]);

  if (error) return <p className="panel-empty">PDF 读取失败（{error}），可点击「系统打开」或「下载」。</p>;
  if (!buffer) return <p className="panel-empty">正在加载 PDF…</p>;
  return (
    <div className="upv-pdf-wrap">
      <Suspense fallback={<p className="panel-empty">正在初始化 PDF 渲染器…</p>}>
        <PdfAttachmentPreview data={buffer} name={name} />
      </Suspense>
    </div>
  );
}

export function MediaFileViewer({ kind, url, mimeType }: { kind: 'audio' | 'video'; url: string; mimeType?: string }) {
  return (
    <div className="upv-media-stage">
      {kind === 'video' ? (
        <video key={url} className="upv-video" controls playsInline preload="metadata">
          <source src={url} type={mimeType || undefined} />
          当前浏览器不支持此视频编码，请点击「系统打开」查看。
        </video>
      ) : (
        <audio key={url} className="upv-audio" controls preload="metadata">
          <source src={url} type={mimeType || undefined} />
          当前浏览器不支持此音频格式，请点击「系统打开」播放。
        </audio>
      )}
    </div>
  );
}

export function StructuredOfficeViewer({ structured }: { structured: UniversalStructuredPreview }) {
  if (structured.error) {
    return <p className="panel-empty">{structured.error}</p>;
  }
  if (structured.kind === 'docx') {
    return <DocxViewer blocks={structured.blocks || []} truncated={Boolean(structured.truncated)} />;
  }
  if (structured.kind === 'xlsx') {
    return <XlsxViewer sheets={structured.sheets || []} truncated={Boolean(structured.truncated)} />;
  }
  if (structured.kind === 'pptx') {
    return <PptxViewer slides={structured.slides || []} truncated={Boolean(structured.truncated)} />;
  }
  if (structured.kind === 'archive') {
    return <ArchiveViewer entries={structured.entries || []} truncated={Boolean(structured.truncated)} />;
  }
  return <p className="panel-empty">暂不支持该结构化预览类型。</p>;
}

function DocxViewer({ blocks, truncated }: { blocks: UniversalDocBlock[]; truncated: boolean }) {
  if (!blocks.length) return <p className="panel-empty">文档内容为空。</p>;
  return (
    <div className="upv-doc-sheet">
      {blocks.map((block, idx) => {
        if (block.type === 'table' && block.rows?.length) {
          return (
            <div key={idx} className="upv-table-scroll">
              <table className="upv-table">
                <tbody>
                  {block.rows.map((row, r) => (
                    <tr key={r}>
                      {row.map((cell, c) => r === 0 ? <th key={c}>{cell}</th> : <td key={c}>{cell}</td>)}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          );
        }
        const level = block.level || 0;
        if (level === 1) return <h1 key={idx} className="upv-doc-h1">{block.text}</h1>;
        if (level === 2) return <h2 key={idx} className="upv-doc-h2">{block.text}</h2>;
        if (level >= 3) return <h3 key={idx} className="upv-doc-h3">{block.text}</h3>;
        return <p key={idx} className="upv-doc-p">{block.text}</p>;
      })}
      {truncated && <p className="upv-trunc">内容较长，仅展示前段内容；完整阅读请点击「系统打开」。</p>}
    </div>
  );
}

function XlsxViewer({ sheets, truncated }: { sheets: UniversalSheetData[]; truncated: boolean }) {
  const [active, setActive] = useState(0);
  useEffect(() => { setActive(0); }, [sheets]);
  const sheet = sheets[active] || sheets[0];
  if (!sheet) return <p className="panel-empty">工作簿中没有工作表。</p>;
  const [header, ...body] = sheet.rows || [];
  return (
    <div className="upv-xlsx">
      <div className="upv-sheet-tabs" role="tablist" aria-label="工作表切换">
        {sheets.map((item, idx) => (
          <button
            key={`${item.name}-${idx}`}
            type="button"
            role="tab"
            aria-selected={idx === active}
            className={`upv-sheet-tab${idx === active ? ' is-active' : ''}`}
            onClick={() => setActive(idx)}
          >
            {item.name}（{item.totalRows}）
          </button>
        ))}
      </div>
      <div className="upv-table-scroll upv-xlsx-grid">
        {!header ? <p className="panel-empty">当前工作表为空。</p> : (
          <table className="upv-table">
            <thead>
              <tr>
                <th className="upv-rownum">#</th>
                {header.map((col, c) => <th key={c}>{col || `列 ${c + 1}`}</th>)}
              </tr>
            </thead>
            <tbody>
              {body.map((row, r) => (
                <tr key={r}>
                  <td className="upv-rownum">{r + 2}</td>
                  {row.map((cell, c) => <td key={c}>{cell}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {(truncated || sheet.truncated) && (
        <p className="upv-trunc">表格行数或列数较多，已做预览截断；完整数据请点击「系统打开」。</p>
      )}
    </div>
  );
}

function PptxViewer({ slides, truncated }: { slides: UniversalSlideData[]; truncated: boolean }) {
  if (!slides.length) return <p className="panel-empty">演示文稿为空。</p>;
  return (
    <div className="upv-slides">
      {slides.map(slide => (
        <article key={slide.index} className="upv-slide-card">
          <header className="upv-slide-head">
            <span className="upv-slide-no">第 {slide.index} 页</span>
            {slide.title && <strong>{slide.title}</strong>}
          </header>
          {slide.paragraphs.length > 0 && (
            <ul className="upv-slide-list">
              {slide.paragraphs.map((line, i) => <li key={i}>{line}</li>)}
            </ul>
          )}
          {slide.tables?.map((rows, t) => (
            <div key={t} className="upv-table-scroll">
              <table className="upv-table">
                <tbody>
                  {rows.map((row, r) => (
                    <tr key={r}>{row.map((cell, c) => r === 0 ? <th key={c}>{cell}</th> : <td key={c}>{cell}</td>)}</tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
          {slide.notes && (
            <details className="upv-slide-notes">
              <summary>演讲者备注</summary>
              <p>{slide.notes}</p>
            </details>
          )}
        </article>
      ))}
      {truncated && <p className="upv-trunc">幻灯片页数较多，仅展示前段内容。</p>}
    </div>
  );
}

function ArchiveViewer({ entries, truncated }: { entries: UniversalArchiveItem[]; truncated: boolean }) {
  if (!entries.length) return <p className="panel-empty">压缩包为空。</p>;
  return (
    <div className="upv-table-scroll upv-archive">
      <table className="upv-table">
        <thead>
          <tr>
            <th>内部路径</th>
            <th>原始大小</th>
            <th>压缩大小</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry, idx) => (
            <tr key={`${entry.path}-${idx}`}>
              <td className="upv-mono">{entry.directory ? `📁 ${entry.path}` : entry.path}</td>
              <td>{entry.directory ? '—' : formatBytes(entry.size, '0 B')}</td>
              <td>{entry.directory ? '—' : formatBytes(entry.compressedSize, '0 B')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {truncated && <p className="upv-trunc">条目较多，仅展示前 500 项。</p>}
    </div>
  );
}

export function previewRawUrl(sessionId: string | null, workspace: string, path: string, download = false) {
  return api.fileRawUrl(sessionId, workspace, path, download);
}
