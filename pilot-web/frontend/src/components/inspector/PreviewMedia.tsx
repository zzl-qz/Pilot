import { lazy, Suspense, useEffect, useState } from 'react';
import { apiFetch } from '../../api.js';
import type { UniversalStructuredPreview } from '../../types/api.js';

const PdfAttachmentPreview = lazy(() => import('../PdfAttachmentPreview.jsx'));
const StructuredOfficeViewer = lazy(() => import('./StructuredOfficePreview.jsx'));

export function PdfPreview({ url, name }: { url: string; name: string }) {
  const [buffer, setBuffer] = useState<ArrayBuffer | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setBuffer(null);
    setError('');
    apiFetch(url, { signal: controller.signal })
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

export function OfficePreview({ structured }: { structured: UniversalStructuredPreview }) {
  return (
    <Suspense fallback={<p className="panel-empty">正在加载文档预览…</p>}>
      <StructuredOfficeViewer structured={structured} />
    </Suspense>
  );
}
