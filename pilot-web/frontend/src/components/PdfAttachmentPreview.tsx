import { useEffect, useRef, useState } from 'react';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import './pdf-attachment.css';

export default function PdfAttachmentPreview({ data, name }: { data: ArrayBuffer; name: string }) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [pdfDoc, setPdfDoc] = useState<PDFDocumentProxy | null>(null);
  const [page, setPage] = useState(1);
  const [zoom, setZoom] = useState(1);
  const [width, setWidth] = useState(0);
  const [loadError, setLoadError] = useState('');
  const [renderError, setRenderError] = useState('');
  const [rendering, setRendering] = useState(true);
  const [painted, setPainted] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const pageCount = pdfDoc?.numPages || 0;

  useEffect(() => {
    let active = true;
    let task: import('pdfjs-dist').PDFDocumentLoadingTask | undefined;
    setPdfDoc(null);
    setPage(1);
    setZoom(1);
    setPainted(false);
    setLoadError('');
    setRenderError('');
    setRendering(true);
    void import('pdfjs-dist').then(({ getDocument, GlobalWorkerOptions }) => {
      if (!active) return;
      GlobalWorkerOptions.workerSrc = workerUrl;
      task = getDocument({ data: new Uint8Array(data.slice(0)) });
      return task.promise;
    }).then(loaded => {
      if (!loaded) return;
      if (!active) return;
      setPdfDoc(loaded);
    }).catch(() => {
      if (active) {
        setLoadError('PDF 无法读取或文件已损坏，请下载后用 PDF 应用打开。');
        setRendering(false);
      }
    });
    return () => { active = false; if (task) void task.destroy().catch(() => {}); };
  }, [data, attempt]);

  /*
   * 用 rAF 合并宽度测量并加入 2px 死区，配合 CSS scrollbar-gutter: stable，
   * 彻底杜绝「渲染完成出现滚动条 → clientWidth 缩小 → 隐藏画布重绘 → 滚动条消失」的无限闪烁死循环。
   */
  useEffect(() => {
    const element = scrollRef.current;
    if (!element) return;
    let frame = 0;
    const measure = () => {
      const next = Math.floor(element.clientWidth);
      if (next > 0) {
        setWidth(prev => (prev > 0 && Math.abs(prev - next) <= 2 ? prev : next));
      }
    };
    const schedule = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(measure);
    };
    measure();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', schedule);
      return () => { cancelAnimationFrame(frame); window.removeEventListener('resize', schedule); };
    }
    const observer = new ResizeObserver(schedule);
    observer.observe(element);
    return () => { cancelAnimationFrame(frame); observer.disconnect(); };
  }, []);

  /*
   * 离屏双缓冲渲染：新页面或新缩放比例先在内存离屏画布完整绘好，
   * 完成后单帧原子拷入可见 canvas；首帧绘出后翻页/缩放永不隐藏画布，零白屏闪烁。
   */
  useEffect(() => {
    if (!pdfDoc || !canvasRef.current || !width) return;
    let cancelled = false;
    let renderTask: RenderTask | undefined;
    let pageProxy: import('pdfjs-dist').PDFPageProxy | undefined;
    setRenderError('');
    setRendering(true);

    void pdfDoc.getPage(page).then(pdfPage => {
      pageProxy = pdfPage;
      if (cancelled || !canvasRef.current) return;
      const base = pdfPage.getViewport({ scale: 1 });
      const fit = Math.min(1.6, Math.max(0.25, (width - 36) / base.width));
      const viewport = pdfPage.getViewport({ scale: fit * zoom });
      const outputScale = window.devicePixelRatio || 1;
      const pixelW = Math.max(1, Math.ceil(viewport.width * outputScale));
      const pixelH = Math.max(1, Math.ceil(viewport.height * outputScale));

      const offscreen = window.document.createElement('canvas');
      offscreen.width = pixelW;
      offscreen.height = pixelH;
      const offContext = offscreen.getContext('2d', { alpha: false });
      if (!offContext) throw new Error('canvas unavailable');

      renderTask = pdfPage.render({
        canvas: offscreen,
        canvasContext: offContext,
        viewport,
        transform: outputScale === 1 ? undefined : [outputScale, 0, 0, outputScale, 0, 0],
      });
      return renderTask.promise.then(() => {
        if (cancelled || !canvasRef.current) return;
        const target = canvasRef.current;
        const targetCtx = target.getContext('2d', { alpha: false });
        if (!targetCtx) throw new Error('canvas unavailable');
        target.width = pixelW;
        target.height = pixelH;
        target.style.width = `${Math.round(viewport.width)}px`;
        target.style.height = `${Math.round(viewport.height)}px`;
        targetCtx.drawImage(offscreen, 0, 0);
        setPainted(true);
        setRendering(false);
      });
    }).catch(reason => {
      if (!cancelled && (reason as { name?: string })?.name !== 'RenderingCancelledException') {
        setRenderError('PDF 页面渲染失败，请重试或下载。');
        setRendering(false);
      }
    }).finally(() => {
      pageProxy?.cleanup();
      pageProxy = undefined;
    });
    return () => { cancelled = true; renderTask?.cancel(); pageProxy?.cleanup(); pageProxy = undefined; };
  }, [pdfDoc, page, width, zoom]);

  const error = loadError || renderError;
  return <section className="pdf-attachment-viewer" aria-label={`${name} PDF 阅读器`}
    onKeyDown={event => {
      if (!pageCount) return;
      if ((event.key === 'ArrowLeft' || event.key === 'PageUp') && page > 1) {
        event.preventDefault();
        setPage(value => Math.max(1, value - 1));
      } else if ((event.key === 'ArrowRight' || event.key === 'PageDown') && page < pageCount) {
        event.preventDefault();
        setPage(value => Math.min(pageCount, value + 1));
      }
    }}>
    <div className="pdf-attachment-toolbar" role="group" aria-label="PDF 阅读工具">
      <div className="pdf-attachment-control">
        <Button label="上一页" variant="ghost" size="sm" isDisabled={page <= 1 || !pageCount}
          onClick={() => setPage(value => value - 1)} />
        <span className="pdf-attachment-page-indicator" aria-live="polite">
          {loadError ? '读取失败' : pageCount ? `${page} / ${pageCount}` : '读取中'}
        </span>
        <Button label="下一页" variant="ghost" size="sm" isDisabled={!pageCount || page >= pageCount}
          onClick={() => setPage(value => value + 1)} />
      </div>
      <div className="pdf-attachment-control">
        <IconButton label="缩小" variant="ghost" size="sm" icon={<span aria-hidden="true">−</span>}
          isDisabled={!pageCount || zoom <= 0.5}
          onClick={() => setZoom(value => Math.max(0.5, Math.round((value - 0.1) * 10) / 10))} />
        <span className="pdf-attachment-zoom-indicator">{Math.round(zoom * 100)}%</span>
        <IconButton label="放大" variant="ghost" size="sm" icon={<span aria-hidden="true">+</span>}
          isDisabled={!pageCount || zoom >= 2}
          onClick={() => setZoom(value => Math.min(2, Math.round((value + 0.1) * 10) / 10))} />
      </div>
    </div>
    <div className="pdf-attachment-scroll" ref={scrollRef} tabIndex={0} aria-label="PDF 页面"
      data-rendering={rendering && painted ? 'true' : undefined}>
      {error ? <Banner status="error" role="alert" title={error}
        endContent={<Button label="重试 PDF 预览" variant="ghost" onClick={() => setAttempt(value => value + 1)} />} />
        : !painted && <p className="pdf-attachment-loading" role="status">{pdfDoc ? '正在渲染页面…' : '正在解析 PDF…'}</p>}
      <canvas className="pdf-attachment-canvas" ref={canvasRef} aria-label={`${name} 第 ${page} 页`}
        hidden={!!error || !painted} />
    </div>
  </section>;
}
