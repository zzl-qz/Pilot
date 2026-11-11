import { statusLetter } from './diff-format.js';
import type { ChangeFile } from '../../types/core.js';
import { useMemo, useState } from 'react';
import { useStore } from '../../store.js';
import { writeClipboard } from '../../lib/clipboard.js';
import { sortChangeFiles, splitPath, statusKind } from '../../lib/changes.js';
import { MarkdownBody } from '../MarkdownBody.jsx';

/* ---------- 预览面板 ---------- */

export function PreviewPanel() {
  const path = useStore((state) => state.ui.previewPath);
  const data = useStore((state) => state._previewData);
  const loading = useStore((state) => state._previewLoading);
  const report = useStore((state) => state.changeReport);
  const [wrap, setWrap] = useState(false);

  const changed = useMemo(() => sortChangeFiles<ChangeFile>(Array.isArray(report?.files) ? report.files : []).slice(0, 12), [report]);
  const isMarkdown = /\.(md|markdown|mdx)$/i.test(path || '');
  const content = data?.content || '';
  const lineCount = content ? content.split('\n').length : 0;

  const copy = async () => {
    if (!content) {
      return;
    }
    try {
      if (!await writeClipboard(content)) throw new Error('clipboard unavailable');
      useStore.getState().showNotice('已复制文件内容');
    } catch {
      useStore.getState().showNotice('复制失败，请手动选择内容');
    }
  };

  return (
    <div id="panel-preview" className={`panel${data && !data.binary ? ' has-content' : ''}`}>
      <div className="panel-toolbar">
        <input
          id="preview-path"
          className="preview-path"
          value={path}
          placeholder="文件路径"
          aria-label="预览文件路径"
          onChange={(event) => useStore.getState().setUi({ previewPath: event.target.value })}
          onKeyDown={(event) => { if (event.key === 'Enter') useStore.getState().openPreview(path); }}
        />
        <button
          type="button"
          className="btn"
          id="preview-refresh"
          disabled={!path.trim()}
          onClick={() => useStore.getState().openPreview(path)}
        >打开</button>
        <button
          type="button"
          className={`btn preview-wrap${wrap ? ' is-on' : ''}`}
          title="长行是否自动换行"
          aria-pressed={wrap}
          onClick={() => setWrap((value) => !value)}
        >换行</button>
        <button type="button" className="btn" disabled={!content} onClick={copy}>复制</button>
      </div>
      {data && !data.binary && (
        <div className="preview-meta">
          <span className="preview-meta-path" title={data.path || path}>{data.path || path}</span>
          <span className="preview-meta-item">{lineCount} 行</span>
          <span className="preview-meta-item">{formatBytes(data.size)}</span>
          {isMarkdown && <span className="preview-meta-tag">Markdown</span>}
          {data.truncated && <span className="preview-meta-tag is-warn">已截断</span>}
        </div>
      )}
      {loading && <p className="panel-empty">读取中…</p>}
      {!loading && data && (
        data.binary
          ? <p className="panel-empty">（二进制文件，无法预览）</p>
          : (isMarkdown
            ? (
              <div className="bubble preview-markdown">
                <MarkdownBody text={data.truncated ? `${content}\n\n…（内容过长，已截断）` : (content || '（空文件）')} />
              </div>
            )
            : (
              <pre id="preview-body" className={`preview-body${wrap ? '' : ' is-nowrap'}`}>
                {data.truncated ? `${content}\n…（内容过长，已截断）` : (content || '（空文件）')}
              </pre>
            ))
      )}
      {!loading && !data && (
        <div className="preview-empty">
          <p>选一个改动文件直接预览，或在上面输入路径。</p>
          {changed.length > 0 && (
            <ul className="preview-suggest">
              {changed.map((file) => (
                <li key={file.path}>
                  <button type="button" title={file.path} onClick={() => useStore.getState().openPreview(file.path)}>
                    <span className={`change-status is-${statusKind(file.status)}`}>{statusLetter(file.status)}</span>
                    <span className="preview-suggest-name">{splitPath(file.path).name}</span>
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

function formatBytes(size: unknown) {
  const value = Number(size);
  if (!Number.isFinite(value) || value <= 0) {
    return '0 B';
  }
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
