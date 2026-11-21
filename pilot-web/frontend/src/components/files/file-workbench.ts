/** 文件工作台纯函数：表单与后端页面操作之间的转换，单独放便于测试。 */

export interface PdfForm {
  keep: string;
  drop: string;
  rotateDegrees: string;
  rotatePages: string;
  watermark: string;
  pageNumbers: boolean;
  merge: string;
}

export const emptyPdfForm = (): PdfForm => ({
  keep: '', drop: '', rotateDegrees: '90', rotatePages: '', watermark: '', pageNumbers: false, merge: ''
});

/** 解析「1,3,5-8」这类页码输入；忽略非法片段，去重升序。 */
export function parsePageList(text: string): number[] {
  const pages = new Set<number>();
  // 只按逗号切，保留「2 ~ 4」这类带空格的区间，让下面的正则统一识别。
  for (const part of (text || '').split(/[,，\n]+/)) {
    const value = part.trim();
    if (!value) continue;
    const range = value.match(/^(\d+)\s*[-~]\s*(\d+)$/);
    if (range) {
      const from = Number(range[1]);
      const to = Number(range[2]);
      if (!Number.isInteger(from) || !Number.isInteger(to) || from < 1 || to < from || to - from > 200) continue;
      for (let page = from; page <= to; page++) pages.add(page);
      continue;
    }
    if (!/^\d+$/.test(value)) continue;
    const page = Number(value);
    if (page >= 1 && page <= 100000) pages.add(page);
  }
  return [...pages].sort((a, b) => a - b);
}

/** 把表单转成 DocumentRenderer.editPdf 的 operations；没有任何操作时返回 null。 */
export function pdfOperations(form: PdfForm): Record<string, unknown> | null {
  const operations: Record<string, unknown> = {};
  const keep = parsePageList(form.keep);
  const drop = parsePageList(form.drop);
  const rotatePages = parsePageList(form.rotatePages);
  const degrees = Number(form.rotateDegrees);
  const merge = (form.merge || '').split(/[,，\n]+/).map(item => item.trim()).filter(Boolean);
  const watermark = (form.watermark || '').trim();
  if (keep.length) operations.keep_pages = keep;
  if (drop.length) operations.drop_pages = drop;
  if (rotatePages.length) {
    if (![90, -90, 180, -180, 270, -270, 0].includes(degrees)) {
      throw new Error('旋转角度须为 90 的倍数且不超过 270');
    }
    operations.rotate = { degrees, pages: rotatePages };
  }
  if (watermark) {
    // eslint-disable-next-line no-control-regex
    if (/[^\x20-\x7E]/.test(watermark)) throw new Error('水印只支持 ASCII 文本');
    operations.watermark = watermark;
  }
  if (form.pageNumbers) operations.page_numbers = true;
  if (merge.length) operations.merge = merge;
  return Object.keys(operations).length ? operations : null;
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

const LABELS: Record<string, string> = {
  docx: 'Word 文档', xlsx: 'Excel 表格', pptx: '演示文稿', pdf: 'PDF'
};

export function formatLabel(format: string): string {
  return LABELS[format] || format.toUpperCase();
}

export function formatMoment(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
  });
}

export function changeHint(changed: boolean): string {
  return changed ? '检测到文件在外部被修改，工作台已按最新内容刷新' : '工作区文件即为唯一源，可随时用本机应用编辑';
}
