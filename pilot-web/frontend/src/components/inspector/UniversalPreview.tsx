import { useEffect, useState } from 'react';
import { api } from '../../api.js';
import './universal-preview.css';

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

export function previewRawUrl(sessionId: string | null, workspace: string, path: string, download = false) {
  return api.fileRawUrl(sessionId, workspace, path, download);
}
