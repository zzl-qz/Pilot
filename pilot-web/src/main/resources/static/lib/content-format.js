const CALLOUTS = {
  NOTE: { kind: 'note', label: '说明' },
  TIP: { kind: 'tip', label: '建议' },
  IMPORTANT: { kind: 'important', label: '重点' },
  WARNING: { kind: 'warning', label: '注意' },
  CAUTION: { kind: 'caution', label: '风险' }
};

export function parseCallout(lines) {
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

export function resolveFileReference(href, workspace) {
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

export function languageLabel(language) {
  const value = String(language || '').trim().toLowerCase();
  const labels = {
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
    css: 'CSS',
    sql: 'SQL',
    md: 'Markdown',
    markdown: 'Markdown',
    diff: 'Diff',
    xml: 'XML'
  };
  return labels[value] || (value ? value.toUpperCase() : '代码');
}

/** 与服务端一致的保守估算：ASCII 约 4 字符/token，中文等宽字符约 1 字符/token。 */
export function estimateTextTokens(content) {
  const text = String(content || '');
  let ascii = 0;
  let wide = 0;
  for (const char of text) {
    if (char.codePointAt(0) < 0x80) ascii += 1;
    else wide += 1;
  }
  return Math.ceil(ascii / 4) + wide;
}
