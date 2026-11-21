const CALLOUTS: Record<string, { kind: string; label: string }> = {
  NOTE: { kind: 'note', label: '说明' },
  TIP: { kind: 'tip', label: '建议' },
  IMPORTANT: { kind: 'important', label: '重点' },
  WARNING: { kind: 'warning', label: '注意' },
  CAUTION: { kind: 'caution', label: '风险' }
};

export function parseCallout(lines: readonly string[] | null | undefined) {
  if (!Array.isArray(lines) || !lines.length) {
    return null;
  }
  const match = String(lines[0]).trim().match(/^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]$/i);
  if (!match) {
    return null;
  }
  const meta = CALLOUTS[match[1].toUpperCase()];
  return {
    ...meta,
    lines: lines.slice(1)
  };
}

export function resolveFileReference(href: unknown, workspace: unknown) {
  if (typeof href !== 'string' || !href.startsWith('/') || href.startsWith('//')) {
    return null;
  }

  let decoded;
  try {
    decoded = decodeURIComponent(href);
  } catch (error) {
    return null;
  }

  const location = decoded.match(/:(\d+)(?::(\d+))?$/);
  const rawPath = (location ? decoded.slice(0, location.index) : decoded).replace(/\\/g, '/');
  const cleanWorkspace = String(workspace || '').replace(/\\/g, '/').replace(/\/+$/, '');
  const workspaceName = cleanWorkspace.split('/').filter(Boolean).pop() || '';
  let path = '';

  if (cleanWorkspace && (rawPath === cleanWorkspace || rawPath.startsWith(cleanWorkspace + '/'))) {
    path = rawPath.slice(cleanWorkspace.length).replace(/^\/+/, '');
  } else {
    const relative = rawPath.replace(/^\/+/, '');
    const first = relative.split('/')[0] || '';
    if (!workspaceName || (first !== workspaceName && !first.startsWith(workspaceName + '-'))) {
      return null;
    }
    path = first === workspaceName ? relative.slice(first.length).replace(/^\/+/, '') : relative;
  }

  if (!path || path.split('/').some((part) => !part || part === '.' || part === '..')) {
    return null;
  }
  return {
    path,
    line: location ? Number(location[1]) : null,
    column: location && location[2] ? Number(location[2]) : null
  };
}

export function languageLabel(language: unknown) {
  const value = String(language || '').trim().toLowerCase();
  const labels: Record<string, string> = {
    js: 'JavaScript',
    javascript: 'JavaScript',
    ts: 'TypeScript',
    typescript: 'TypeScript',
    sh: 'Shell',
    bash: 'Shell',
    zsh: 'Shell',
    py: 'Python',
    python: 'Python',
    java: 'Java',
    json: 'JSON',
    yaml: 'YAML',
    yml: 'YAML',
    html: 'HTML',
    svg: 'SVG',
    css: 'CSS',
    sql: 'SQL',
    md: 'Markdown',
    markdown: 'Markdown',
    diff: 'Diff',
    patch: 'Diff',
    xml: 'XML',
    csv: 'CSV',
    mermaid: '图表',
    math: '公式',
    latex: '公式',
    tex: '公式',
    jsx: 'JSX',
    tsx: 'TSX',
    go: 'Go',
    rs: 'Rust',
    rust: 'Rust',
    toml: 'TOML'
  };
  return labels[value] || (value ? value.toUpperCase() : '代码');
}

/**
 * 模型输出按类型分流，和常见编码产品一致：
 * 能画出来的（SVG、完整 HTML）默认预览；Diff 按行着色；其余保持源码。
 */
export function outputKind(language: unknown, code: unknown) {
  const lang = String(language || '').trim().toLowerCase();
  const body = String(code || '').trim();
  if ((lang === 'svg' || /^<svg\b/i.test(body)) && /<\/svg>/i.test(body)) {
    return 'svg';
  }
  if ((lang === 'html' || lang === 'xhtml' || /^<!doctype\s+html/i.test(body)) && /<\/html>/i.test(body)) {
    return 'html';
  }
  if (lang === 'diff' || lang === 'patch') {
    return 'diff';
  }
  if (lang === 'mermaid') {
    return 'mermaid';
  }
  if (lang === 'math' || lang === 'latex' || lang === 'tex') {
    return 'math';
  }
  return 'code';
}

/** 聊天里的图片只接受网页地址和图片 data URL，避免 javascript: 一类源。 */
export function safeImageSource(url: unknown) {
  const value = String(url || '').trim();
  if (/^https?:\/\//i.test(value)) {
    return value;
  }
  if (/^data:image\/(png|jpeg|jpg|gif|webp|svg\+xml);base64,[a-z0-9+/=\s]+$/i.test(value)) {
    return value.replace(/\s/g, '');
  }
  return null;
}

/** 与服务端一致的保守估算：ASCII 约 4 字符/token，中文等宽字符约 1 字符/token。 */
export function estimateTextTokens(content: unknown) {
  const text = String(content || '');
  let ascii = 0;
  let wide = 0;
  for (const char of text) {
    if (char.codePointAt(0)! < 0x80) ascii += 1;
    else wide += 1;
  }
  return Math.ceil(ascii / 4) + wide;
}
