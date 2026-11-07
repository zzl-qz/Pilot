/** 产物工作台的纯函数：可编辑源、块模型、版本差异与页面操作校验。 */

export type StudioBlockType = 'heading' | 'paragraph' | 'bullet' | 'page_break' | 'table';
export interface StudioBlock {
  type: StudioBlockType;
  text?: string;
  level?: number;
  rows?: string[][];
}

export const BLOCK_TYPES: { id: StudioBlockType; label: string }[] = [
  { id: 'heading', label: '标题' },
  { id: 'paragraph', label: '段落' },
  { id: 'bullet', label: '要点' },
  { id: 'table', label: '表格' },
  { id: 'page_break', label: '分页' }
];

export interface ParsedSpec {
  title: string;
  blocks: StudioBlock[];
  importedTextOnly: boolean;
  error: string;
}

const ALLOWED = new Set<string>(BLOCK_TYPES.map(item => item.id));

export function normalizeBlocks(input: unknown): StudioBlock[] {
  if (!Array.isArray(input)) return [];
  const result: StudioBlock[] = [];
  for (const raw of input.slice(0, 400)) {
    if (!raw || typeof raw !== 'object') continue;
    const item = raw as Record<string, unknown>;
    const type = typeof item.type === 'string' && ALLOWED.has(item.type) ? item.type as StudioBlockType : null;
    if (!type) continue;
    const block: StudioBlock = { type };
    if (typeof item.text === 'string') block.text = item.text.slice(0, 20000);
    if (type === 'heading') block.level = item.level === 2 || item.level === 3 ? item.level : 1;
    if (type === 'table' && Array.isArray(item.rows)) {
      block.rows = item.rows.slice(0, 500).map((row: unknown) =>
        (Array.isArray(row) ? row : []).slice(0, 32).map((cell: unknown) => String(cell ?? '').slice(0, 2000)));
    }
    result.push(block);
  }
  return result;
}

export function parseSpec(raw: string): ParsedSpec {
  const empty: ParsedSpec = { title: '', blocks: [], importedTextOnly: false, error: '' };
  if (!raw || typeof raw !== 'string') return { ...empty, error: '规格为空' };
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch (error) {
    return { ...empty, error: `JSON 解析失败：${(error as Error).message}` };
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return { ...empty, error: '规格必须是 JSON 对象' };
  }
  const spec = value as Record<string, unknown>;
  return {
    title: typeof spec.title === 'string' ? spec.title : '',
    blocks: normalizeBlocks(spec.blocks),
    importedTextOnly: spec.imported_text_only === true,
    error: ''
  };
}

export function tableText(rows?: string[][]): string {
  return (rows || []).map(row => row.join(' | ')).join('\n');
}

export function parseTable(text: string): string[][] {
  if (!text.trim()) return [];
  return text.split(/\r?\n/).slice(0, 500).map(line => line.split('|').map(cell => cell.trim().slice(0, 2000)));
}

export function serializeSpec(title: string, blocks: StudioBlock[], importedTextOnly = false): string {
  const payload: Record<string, unknown> = {
    title,
    blocks: blocks.slice(0, 400).map(block => {
      const value: Record<string, unknown> = { type: block.type };
      if (block.type !== 'page_break') value.text = block.text || '';
      if (block.type === 'heading') value.level = block.level || 1;
      if (block.type === 'table') value.rows = parseTable(tableText(block.rows));
      return value;
    })
  };
  if (importedTextOnly) payload.imported_text_only = true;
  return JSON.stringify(payload, null, 2);
}

export function defaultSpec(): string {
  return serializeSpec('未命名文档', [
    { type: 'heading', text: '标题', level: 1 },
    { type: 'paragraph', text: '在这里直接编辑内容，保存后一键导出，不需要让模型重跑。' }
  ]);
}

export function formatLabel(format: string): string {
  return ({ docx: 'Word', pdf: 'PDF', xlsx: 'Excel', pptx: 'PPT' } as Record<string, string>)[format] || format.toUpperCase();
}

/** 按行的 LCS 差异；上限 300 行，避免大规格卡住界面。 */
export function diffLines(before: string, after: string): { kind: 'same' | 'add' | 'remove'; text: string }[] {
  const a = before.split('\n').slice(0, 300);
  const b = after.split('\n').slice(0, 300);
  const table: number[][] = Array.from({ length: a.length + 1 }, () => Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      table[i][j] = a[i] === b[j] ? table[i + 1][j + 1] + 1
        : Math.max(table[i + 1][j], table[i][j + 1]);
    }
  }
  const result: { kind: 'same' | 'add' | 'remove'; text: string }[] = [];
  let i = 0, j = 0;
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      result.push({ kind: 'same', text: a[i] }); i++; j++;
    } else if (j < b.length && (i >= a.length || table[i][j + 1] >= table[i + 1][j])) {
      result.push({ kind: 'add', text: b[j] }); j++;
    } else if (i < a.length) {
      result.push({ kind: 'remove', text: a[i] }); i++;
    }
  }
  return result;
}

/** PDF 页面级操作是可选 JSON；空串表示不做编辑。 */
export function parsePdfOperations(raw: string): { value: Record<string, unknown> | null; error: string } {
  if (!raw || !raw.trim()) return { value: null, error: '' };
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return { value: null, error: '页面操作必须是 JSON 对象' };
    }
    const allowed = new Set(['keep_pages', 'drop_pages', 'rotate', 'page_numbers', 'watermark', 'merge']);
    const keys = Object.keys(value as Record<string, unknown>);
    const unknown = keys.filter(key => !allowed.has(key));
    if (unknown.length) return { value: null, error: `不支持的页面操作：${unknown.join('、')}` };
    return { value: value as Record<string, unknown>, error: '' };
  } catch (error) {
    return { value: null, error: `页面操作 JSON 解析失败：${(error as Error).message}` };
  }
}
