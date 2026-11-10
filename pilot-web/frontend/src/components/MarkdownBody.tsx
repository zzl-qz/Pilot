import { memo, useEffect, useRef } from 'react';
import { createBlockStreamRenderer } from '../lib/markdown-stream.js';
import { markdownBlockRecords } from '../lib/markdown-render.js';

/** 历史与流式回复共享安全的块级渲染器，更新时复用已经落定的 DOM。 */
export const MarkdownBody = memo(function MarkdownBody({ text }: { text?: string | null }) {
  const ref = useRef<HTMLDivElement>(null);
  const renderer = useRef<ReturnType<typeof createBlockStreamRenderer> | null>(null);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    renderer.current ??= createBlockStreamRenderer(element, { parse: markdownBlockRecords });
    renderer.current.update(text || '');
  }, [text]);
  return <div ref={ref} className="bubble-md" />;
});
