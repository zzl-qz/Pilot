import { useMemo, useState } from 'react';
import './structured-document.css';

const NUMERIC_CELL = /^[+-]?[$¥€£]?\d[\d,]*(?:\.\d+)?%?$/;

interface SheetBlock {
  name: string;
  rows: string[][];
}

interface SlideBlock {
  number: string;
  lines: string[];
}

function parseDelimitedLine(line: string, delimiter: ',' | '\t'): string[] {
  const cells: string[] = [];
  let cur = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') {
      if (quoted && line[i + 1] === '"') { cur += '"'; i++; }
      else quoted = !quoted;
    } else if (ch === delimiter && !quoted) {
      cells.push(cur.trim());
      cur = '';
    } else {
      cur += ch;
    }
  }
  cells.push(cur.trim());
  return cells;
}

function parseSheets(text: string, ext: string): SheetBlock[] | null {
  const rawLines = text.replace(/\r\n/g, '\n').split('\n');
  if (ext === 'csv' || ext === 'tsv') {
    const delim = ext === 'tsv' ? '\t' : ',';
    const rows = rawLines.filter(l => l.trim()).slice(0, 300).map(l => parseDelimitedLine(l, delim));
    return rows.length ? [{ name: ext.toUpperCase(), rows }] : null;
  }
  if (ext !== 'xlsx' && !text.startsWith('[sheet] ')) return null;
  const sheets: SheetBlock[] = [];
  let current: SheetBlock | null = null;
  for (const line of rawLines) {
    if (line.startsWith('[sheet] ')) {
      current = { name: line.slice(8).trim() || `Sheet ${sheets.length + 1}`, rows: [] };
      sheets.push(current);
      continue;
    }
    if (!line.trim()) continue;
    if (!current) {
      current = { name: '工作表', rows: [] };
      sheets.push(current);
    }
    if (current.rows.length < 300) {
      current.rows.push(line.split(' | ').map(cell => cell.trim()));
    }
  }
  return sheets.some(s => s.rows.length > 0) ? sheets : null;
}

function parseSlides(text: string, ext: string): SlideBlock[] | null {
  if (ext !== 'pptx' && !/^\[slide \d+\]/m.test(text)) return null;
  const slides: SlideBlock[] = [];
  let current: SlideBlock | null = null;
  for (const line of text.replace(/\r\n/g, '\n').split('\n')) {
    const match = line.match(/^\[slide\s+(\d+)\]\s*$/i);
    if (match) {
      current = { number: match[1], lines: [] };
      slides.push(current);
      continue;
    }
    if (line.trim() && current) current.lines.push(line.trim());
  }
  return slides.length ? slides : null;
}

type DocSegment = { kind: 'p' | 'h' | 'li'; text: string } | { kind: 'table'; rows: string[][] };

function hasRichDocStructure(segments: DocSegment[]) {
  return segments.some(s => s.kind === 'table' || s.kind === 'li');
}

function parseDocSegments(text: string): DocSegment[] {
  const segments: DocSegment[] = [];
  let tableBuf: string[][] = [];
  const flushTable = () => {
    if (tableBuf.length >= 2) segments.push({ kind: 'table', rows: tableBuf });
    else for (const r of tableBuf) segments.push({ kind: 'p', text: r.join(' | ') });
    tableBuf = [];
  };
  for (const raw of text.replace(/\r\n/g, '\n').split('\n')) {
    const line = raw.trim();
    if (!line) { flushTable(); continue; }
    if (line.includes(' | ')) {
      tableBuf.push(line.split(' | ').map(c => c.trim()));
      continue;
    }
    flushTable();
    if (line.startsWith('•') || line.startsWith('- ')) {
      segments.push({ kind: 'li', text: line.replace(/^[•-]\s*/, '') });
    } else if (line.length <= 42 && !/[。！？.;；，,]$/.test(line) && segments.length < 40) {
      segments.push({ kind: 'h', text: line });
    } else {
      segments.push({ kind: 'p', text: line });
    }
  }
  flushTable();
  return segments;
}

function DataTable({ rows }: { rows: string[][] }) {
  const colCount = Math.max(1, ...rows.map(r => r.length));
  const head = rows[0] || [];
  const body = rows.slice(1);
  return (
    <div className="sdoc-table-wrap" tabIndex={0}>
      <table className="sdoc-table">
        <thead>
          <tr>
            {Array.from({ length: colCount }, (_, c) => {
              const sample = body.slice(0, 12).map(r => r[c] || '').filter(Boolean);
              const numericCol = sample.length > 0 && sample.every(v => NUMERIC_CELL.test(v));
              return <th key={c} className={numericCol ? 'is-num' : undefined}>{head[c] || `列 ${c + 1}`}</th>;
            })}
          </tr>
        </thead>
        <tbody>
          {body.map((row, r) => (
            <tr key={r}>
              {Array.from({ length: colCount }, (_, c) => {
                const val = row[c] ?? '';
                const isNum = val !== '' && NUMERIC_CELL.test(val);
                return <td key={c} className={isNum ? 'is-num' : val === '' ? 'is-empty' : undefined}>{val || '—'}</td>;
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Office / 表格文本的结构化可读渲染：将工作表、幻灯片与带表文档渲染为对齐清晰的界面，
 * 替代原先挤成一团的裸 <pre> 管道文本。
 */
export function StructuredDocumentView({ text, filename }: { text: string; filename?: string }) {
  const ext = (filename || '').split('.').pop()?.toLowerCase() || '';
  const sheets = useMemo(() => parseSheets(text || '', ext), [text, ext]);
  const slides = useMemo(() => (sheets ? null : parseSlides(text || '', ext)), [sheets, text, ext]);
  const docSegments = useMemo(() => (!sheets && !slides && ext === 'docx' ? parseDocSegments(text || '') : null), [sheets, slides, ext, text]);
  const [sheetIdx, setSheetIdx] = useState(0);

  if (!text || !text.trim()) {
    return <p className="sdoc-empty">（没有可提取的文本内容）</p>;
  }

  if (sheets) {
    const active = sheets[Math.min(sheetIdx, sheets.length - 1)] || sheets[0];
    return (
      <div className="sdoc-root">
        {sheets.length > 1 && (
          <div className="sdoc-tabs" role="tablist" aria-label="工作表切换">
            {sheets.map((s, idx) => (
              <button key={s.name + idx} type="button" role="tab" aria-selected={idx === sheetIdx}
                className={`sdoc-tab${idx === sheetIdx ? ' is-active' : ''}`} onClick={() => setSheetIdx(idx)}>
                {s.name}
                <span className="sdoc-tab-count">{Math.max(0, s.rows.length - 1)}</span>
              </button>
            ))}
          </div>
        )}
        <DataTable rows={active.rows} />
      </div>
    );
  }

  if (slides) {
    return (
      <div className="sdoc-root sdoc-slides">
        {slides.map(slide => (
          <article key={slide.number} className="sdoc-slide">
            <header className="sdoc-slide-head">
              <span className="sdoc-slide-no">幻灯片 {slide.number}</span>
              {slide.lines[0] && <strong>{slide.lines[0]}</strong>}
            </header>
            {slide.lines.length > 1 && (
              <ul className="sdoc-slide-list">
                {slide.lines.slice(1).map((line, i) => <li key={i}>{line}</li>)}
              </ul>
            )}
          </article>
        ))}
      </div>
    );
  }

  if (docSegments && hasRichDocStructure(docSegments)) {
    return (
      <div className="sdoc-root sdoc-paper">
        {docSegments.map((seg, i) => {
          if (seg.kind === 'table') return <DataTable key={i} rows={seg.rows} />;
          if (seg.kind === 'h') return <h4 key={i} className="sdoc-heading">{seg.text}</h4>;
          if (seg.kind === 'li') return <p key={i} className="sdoc-bullet"><span>•</span>{seg.text}</p>;
          return <p key={i} className="sdoc-para">{seg.text}</p>;
        })}
      </div>
    );
  }

  return <pre className={ext === 'docx' ? 'sdoc-paper-pre' : 'sdoc-raw'} tabIndex={0}>{text}</pre>;
}

export function isStructuredDocumentFormat(filename?: string) {
  const ext = (filename || '').split('.').pop()?.toLowerCase() || '';
  return ['xlsx', 'docx', 'pptx', 'csv', 'tsv'].includes(ext);
}
