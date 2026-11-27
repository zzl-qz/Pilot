import { memo, useEffect, useLayoutEffect, useRef } from 'react';
import { createBlockStreamRenderer } from '../lib/content/markdown-stream.js';
import { markdownBlockRecords, patchMarkdownBlock } from '../lib/content/markdown-render.js';
import { nextSmoothLength, safeSliceLength } from '../lib/content/stream-smoother.js';

function prefersReducedMotion() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * 历史与流式回复共享块级增量渲染器：
 * - 非流式文本同步落位，切换会话/回放历史零延迟；
 * - 流式文本通过 rAF + 后台定时兜底自适应平滑消化突发块，结合就地尾块修补与延迟富预览，消除跳动与闪烁。
 */
export const MarkdownBody = memo(function MarkdownBody({ text, streaming = false }: { text?: string | null; streaming?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const renderer = useRef<ReturnType<typeof createBlockStreamRenderer> | null>(null);
  const targetRef = useRef('');
  const shownLenRef = useRef(0);
  const initializedRef = useRef(false);
  const streamingRef = useRef(false);
  const rafRef = useRef<number | undefined>(undefined);
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const fullText = text || '';

  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    renderer.current ??= createBlockStreamRenderer(element, {
      parse: markdownBlockRecords,
      patch: patchMarkdownBlock
    });

    const cancelPending = () => {
      if (rafRef.current != null && typeof cancelAnimationFrame === 'function') {
        cancelAnimationFrame(rafRef.current);
      }
      clearTimeout(timerRef.current);
      rafRef.current = undefined;
      timerRef.current = undefined;
    };

    targetRef.current = fullText;
    streamingRef.current = streaming;

    // 首次挂载（如切会话/重连恢复已生成文本）、非流式、后台标签、减少动效、文本缩短/重置、或增量很小：立刻全量渲染
    const hidden = typeof document !== 'undefined' && document.hidden;
    const isInitialMount = !initializedRef.current;
    initializedRef.current = true;
    const immediate = isInitialMount
      || !streaming
      || hidden
      || prefersReducedMotion()
      || fullText.length <= shownLenRef.current
      || fullText.length - shownLenRef.current <= 6;

    if (immediate) {
      cancelPending();
      shownLenRef.current = fullText.length;
      renderer.current.update(fullText, { streaming });
      return;
    }

    const step = () => {
      cancelPending();
      const target = targetRef.current;
      const live = streamingRef.current;
      const hiddenNow = typeof document !== 'undefined' && document.hidden;
      if (!live || hiddenNow || prefersReducedMotion() || target.length <= shownLenRef.current) {
        shownLenRef.current = target.length;
        renderer.current?.update(target, { streaming: live });
        return;
      }
      const nextLen = safeSliceLength(target, nextSmoothLength(shownLenRef.current, target.length, false));
      shownLenRef.current = nextLen;
      renderer.current?.update(target.slice(0, nextLen), { streaming: live });
      if (nextLen < target.length) {
        timerRef.current = setTimeout(step, 40);
        if (typeof requestAnimationFrame === 'function') {
          rafRef.current = requestAnimationFrame(step);
        }
      }
    };

    step();
    return cancelPending;
  }, [fullText, streaming]);

  useEffect(() => () => {
    if (rafRef.current != null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(rafRef.current);
    }
    clearTimeout(timerRef.current);
  }, []);

  return <div ref={ref} className={`bubble-md${streaming ? ' is-streaming' : ''}`} />;
});
