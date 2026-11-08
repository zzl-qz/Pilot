import React, { useEffect, useRef, useState } from 'react';
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

GlobalWorkerOptions.workerSrc = workerUrl;

export default function PdfAttachmentPreview({ data, name }: { data: ArrayBuffer; name: string }) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [document, setDocument] = useState<PDFDocumentProxy | null>(null);
  const [page, setPage] = useState(1);
  const [pageCount, setPageCount] = useState(0);
  const [zoom, setZoom] = useState(1);
  const [width, setWidth] = useState(0);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    const task = getDocument({ data: new Uint8Array(data.slice(0)) });
    setDocument(null);
    setPage(1);
    setError('');
    void task.promise.then(pdf => {
      if (!active) return;
      setDocument(pdf);
      setPageCount(pdf.numPages);
    }).catch(() => {
      if (active) setError('PDF 无法读取或文件已损坏，请下载后重试。');
    });
    return () => { active = false; void task.destroy(); };
  }, [data]);

  useEffect(() => {
    const element = scrollRef.current;
    if (!element) return;
    const measure = () => setWidth(element.clientWidth);
    measure();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure);
      return () => window.removeEventListener('resize', measure);
    }
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!document || !canvasRef.current || !width) return;
    let cancelled = false;
    let renderTask: RenderTask | undefined;
    void document.getPage(page).then(pdfPage => {
      if (cancelled || !canvasRef.current) return;
      const base = pdfPage.getViewport({ scale: 1 });
      const fit = Math.min(1.5, Math.max(0.25, (width - 32) / base.width));
      const viewport = pdfPage.getViewport({ scale: fit * zoom });
      const canvas = canvasRef.current;
      const context = canvas?.getContext('2d', { alpha: false });
      if (!canvas || !context) throw new Error('canvas unavailable');
      const outputScale = window.devicePixelRatio || 1;
      canvas.width = Math.ceil(viewport.width * outputScale);
      canvas.height = Math.ceil(viewport.height * outputScale);
      canvas.style.width = `${viewport.width}px`;
      canvas.style.height = `${viewport.height}px`;
      renderTask = pdfPage.render({
        canvas,
        canvasContext: context,
        viewport,
        transform: outputScale === 1 ? undefined : [outputScale, 0, 0, outputScale, 0, 0],
      });
      return renderTask.promise;
    }).catch(reason => {
      if (!cancelled && (reason as { name?: string })?.name !== 'RenderingCancelledException') {
        setError('PDF 页面渲染失败，请下载后重试。');
      }
    });
    return () => { cancelled = true; renderTask?.cancel(); };
  }, [document, page, width, zoom]);

  return <section className="pdf-attachment-viewer" aria-label={`${name} PDF 阅读器`}>
    <div className="pdf-attachment-toolbar" role="toolbar" aria-label="PDF 阅读工具">
      <button className="btn" type="button" aria-label="上一页" title="上一页" disabled={page <= 1} onClick={() => setPage(value => value - 1)}>上一页</button>
      <span aria-live="polite">{pageCount ? `${page} / ${pageCount}` : '读取中'}</span>
      <button className="btn" type="button" aria-label="下一页" title="下一页" disabled={!pageCount || page >= pageCount} onClick={() => setPage(value => value + 1)}>下一页</button>
      <span className="pdf-attachment-divider" aria-hidden="true" />
      <button className="btn" type="button" aria-label="缩小" title="缩小" disabled={zoom <= 0.5} onClick={() => setZoom(value => Math.max(0.5, Math.round((value - 0.1) * 10) / 10))}>−</button>
      <span>{Math.round(zoom * 100)}%</span>
      <button className="btn" type="button" aria-label="放大" title="放大" disabled={zoom >= 2} onClick={() => setZoom(value => Math.min(2, Math.round((value + 0.1) * 10) / 10))}>+</button>
    </div>
    {error ? <p className="pdf-attachment-error" role="alert">{error}</p> : <div className="pdf-attachment-scroll" ref={scrollRef}>
      {!document && <p role="status">正在解析 PDF…</p>}
      <canvas ref={canvasRef} aria-label={`${name} 第 ${page} 页`} />
    </div>}
  </section>;
}
