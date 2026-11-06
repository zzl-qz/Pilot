import React, { useEffect, useRef } from 'react';
import { createBlockStreamRenderer } from '../lib/markdown-stream.js';
import { markdownBlockRecords } from '../lib/markdown-render.js';

/**
 * Markdown 气泡：桥接旧版的块级增量渲染器（lib/markdown-stream.js）。
 * 同一容器复用同一个渲染器实例，只把增量部分补绘上去（与旧版 paintMarkdown 一致）；
 * 历史消息与流式消息共用这一条路径，落库内容怎么渲染、历史就怎么渲染。
 */
export function MarkdownBody({ text }: { text?: string | null }) {
  const ref = useRef<HTMLDivElement & { __markdownStream?: ReturnType<typeof createBlockStreamRenderer> }>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) {
      return;
    }
    if (!el.__markdownStream) {
      el.__markdownStream = createBlockStreamRenderer(el, { parse: markdownBlockRecords });
    }
    el.__markdownStream.update(text || '');
  }, [text]);
  return <div ref={ref} className="bubble-md" />;
}
