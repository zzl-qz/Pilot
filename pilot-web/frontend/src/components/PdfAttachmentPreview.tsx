import { useEffect, useRef, useState } from 'react';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import './pdf-attachment.css';

GlobalWorkerOptions.workerSrc = workerUrl;

export default function PdfAttachmentPreview({ data, name }: { data: ArrayBuffer; name: string }) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [document, setDocument] = useState<PDFDocumentProxy | null>(null);
  const [page, setPage] = useState(1);
  const [zoom, setZoom] = useState(1);
  const [width, setWidth] = useState(0);
  const [loadError, setLoadError] = useState('');
  const [renderError, setRenderError] = useState('');
  const [rendering, setRendering] = useState(true);
  const [attempt, setAttempt] = useState(0);
  const pageCount = document?.numPages || 0;

  useEffect(() => {
    let active = true;
    const task = getDocument({ data: new Uint8Array(data.slice(0)) });
    setDocument(null);
    setPage(1);
    setLoadError(''); setRenderError(''); setRendering(true);
    void task.promise.then(pdf => {
      if (!active) return;
      setDocument(pdf);
    }).catch(() => {
      if (active) setLoadError('PDF 无法读取或文件已损坏，请下载后用 PDF 应用打开。');
    });
    return () => { active = false; void task.destroy().catch(() => {}); };
  }, [data, attempt]);

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
    setRenderError(''); setRendering(true);
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
    }).then(() => {
      if (!cancelled) setRendering(false);
    }).catch(reason => {
      if (!cancelled && (reason as { name?: string })?.name !== 'RenderingCancelledException') {
        setRenderError('PDF 页面渲染失败，请重试或下载。');
        setRendering(false);
      }
    });
    return () => { cancelled = true; renderTask?.cancel(); };
  }, [document, page, width, zoom]);

  const error = loadError || renderError;
  return <section className="pdf-attachment-viewer" aria-label={`${name} PDF 阅读器`}>
    <div className="pdf-attachment-toolbar" role="group" aria-label="PDF 阅读工具">
      <div className="pdf-attachment-control">
        <Button label="上一页" variant="ghost" size="sm" isDisabled={page <= 1 || !pageCount} onClick={() => setPage(value => value - 1)} />
        <span aria-live="polite">{loadError ? '读取失败' : pageCount ? `${page} / ${pageCount}` : '读取中'}</span>
        <Button label="下一页" variant="ghost" size="sm" isDisabled={!pageCount || page >= pageCount} onClick={() => setPage(value => value + 1)} />
      </div>
      <div className="pdf-attachment-control">
        <IconButton label="缩小" variant="ghost" size="sm" icon={<span aria-hidden="true">−</span>}
          isDisabled={!pageCount || zoom <= 0.5} onClick={() => setZoom(value => Math.max(0.5, Math.round((value - 0.1) * 10) / 10))} />
        <span>{Math.round(zoom * 100)}%</span>
        <IconButton label="放大" variant="ghost" size="sm" icon={<span aria-hidden="true">+</span>}
          isDisabled={!pageCount || zoom >= 2} onClick={() => setZoom(value => Math.min(2, Math.round((value + 0.1) * 10) / 10))} />
      </div>
    </div>
    <div className="pdf-attachment-scroll" ref={scrollRef} tabIndex={0} aria-label="PDF 页面">
      {error ? <Banner status="error" role="alert" title={error}
        endContent={<Button label="重试 PDF 预览" variant="ghost" onClick={() => setAttempt(value => value + 1)} />} />
        : rendering && <p role="status">{document ? '正在渲染页面…' : '正在解析 PDF…'}</p>}
      <canvas ref={canvasRef} aria-label={`${name} 第 ${page} 页`} hidden={!!error || rendering} />
    </div>
  </section>;
}
