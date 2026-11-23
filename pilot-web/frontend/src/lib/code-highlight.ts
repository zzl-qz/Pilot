export type CodeTokenKind = 'plain' | 'kw' | 'str' | 'num' | 'com' | 'type' | 'fn';
export interface CodeToken { kind: CodeTokenKind; text: string }

const EXT_LANG: Record<string, string> = {
  java: 'java', kt: 'java', kts: 'java',
  ts: 'ts', tsx: 'ts', js: 'ts', jsx: 'ts', mjs: 'ts', cjs: 'ts',
  py: 'py', go: 'go', rs: 'rust',
  c: 'c', h: 'c', cpp: 'c', hpp: 'c', cs: 'java',
  json: 'json', yml: 'yaml', yaml: 'yaml',
  xml: 'xml', html: 'xml', htm: 'xml', svg: 'xml',
  css: 'css', scss: 'css', sql: 'sql',
  sh: 'sh', bash: 'sh', zsh: 'sh', ps1: 'sh', cmd: 'sh', bat: 'sh'
};

const KEYWORDS: Record<string, Set<string>> = {
  java: new Set(['package', 'import', 'public', 'private', 'protected', 'class', 'interface', 'enum', 'record', 'extends', 'implements', 'static', 'final', 'void', 'return', 'if', 'else', 'for', 'while', 'do', 'switch', 'case', 'default', 'break', 'continue', 'new', 'try', 'catch', 'finally', 'throw', 'throws', 'this', 'super', 'boolean', 'int', 'long', 'double', 'float', 'byte', 'char', 'short', 'var', 'true', 'false', 'null', 'sealed', 'permits', 'yield', 'synchronized', 'volatile']),
  ts: new Set(['import', 'export', 'from', 'default', 'function', 'const', 'let', 'var', 'return', 'if', 'else', 'for', 'while', 'do', 'switch', 'case', 'break', 'continue', 'new', 'try', 'catch', 'finally', 'throw', 'async', 'await', 'class', 'extends', 'implements', 'interface', 'type', 'enum', 'readonly', 'private', 'public', 'protected', 'typeof', 'keyof', 'as', 'in', 'of', 'true', 'false', 'null', 'undefined', 'void']),
  py: new Set(['def', 'class', 'import', 'from', 'as', 'return', 'if', 'elif', 'else', 'for', 'while', 'break', 'continue', 'try', 'except', 'finally', 'raise', 'with', 'yield', 'lambda', 'pass', 'and', 'or', 'not', 'in', 'is', 'async', 'await', 'True', 'False', 'None']),
  go: new Set(['package', 'import', 'func', 'type', 'struct', 'interface', 'return', 'if', 'else', 'for', 'range', 'switch', 'case', 'default', 'break', 'continue', 'go', 'defer', 'select', 'chan', 'map', 'var', 'const', 'true', 'false', 'nil']),
  rust: new Set(['fn', 'let', 'mut', 'const', 'pub', 'struct', 'enum', 'impl', 'trait', 'for', 'in', 'while', 'loop', 'if', 'else', 'match', 'return', 'use', 'mod', 'crate', 'where', 'async', 'await', 'move', 'ref', 'self', 'Self', 'true', 'false']),
  c: new Set(['include', 'define', 'struct', 'enum', 'typedef', 'const', 'static', 'void', 'int', 'long', 'char', 'float', 'double', 'bool', 'return', 'if', 'else', 'for', 'while', 'switch', 'case', 'break', 'continue', 'true', 'false', 'nullptr', 'NULL']),
  sql: new Set(['SELECT', 'FROM', 'WHERE', 'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'CREATE', 'TABLE', 'INDEX', 'ALTER', 'DROP', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'ON', 'AND', 'OR', 'NOT', 'NULL', 'AS', 'ORDER', 'BY', 'GROUP', 'LIMIT', 'HAVING', 'DISTINCT', 'PRIMARY', 'KEY']),
  sh: new Set(['if', 'then', 'else', 'elif', 'fi', 'for', 'do', 'done', 'while', 'case', 'esac', 'in', 'function', 'return', 'export', 'local', 'exit', 'echo', 'set', 'true', 'false']),
  json: new Set(['true', 'false', 'null']),
  yaml: new Set(['true', 'false', 'null', 'yes', 'no'])
};

export function detectCodeLanguage(path: string): string | null {
  const clean = (path || '').trim();
  const dot = clean.lastIndexOf('.');
  if (dot < 0) return null;
  return EXT_LANG[clean.slice(dot + 1).toLowerCase()] || null;
}

const TOKEN_RE = /(\/\/.*$|#.*$|--\s.*$|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)/g;

export function tokenizeCodeLine(line: string, lang: string | null): CodeToken[] {
  if (!line || !lang) return [{ kind: 'plain', text: line }];
  const keywords = KEYWORDS[lang];
  const hashComment = lang === 'py' || lang === 'sh' || lang === 'yaml';
  const dashComment = lang === 'sql';
  const slashComment = lang === 'java' || lang === 'ts' || lang === 'go' || lang === 'rust' || lang === 'c' || lang === 'css';
  const tokens: CodeToken[] = [];
  let cursor = 0;

  TOKEN_RE.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = TOKEN_RE.exec(line)) !== null) {
    const start = match.index;
    const text = match[0];
    if (start > cursor) tokens.push({ kind: 'plain', text: line.slice(cursor, start) });

    if (text.startsWith('//')) {
      if (slashComment) {
        tokens.push({ kind: 'com', text });
        cursor = line.length;
        break;
      }
      tokens.push({ kind: 'plain', text: '//' });
      cursor = start + 2;
      TOKEN_RE.lastIndex = cursor;
      continue;
    }
    if (text.startsWith('#')) {
      if (hashComment) {
        tokens.push({ kind: 'com', text });
        cursor = line.length;
        break;
      }
      tokens.push({ kind: 'plain', text: '#' });
      cursor = start + 1;
      TOKEN_RE.lastIndex = cursor;
      continue;
    }
    if (text.startsWith('--')) {
      if (dashComment) {
        tokens.push({ kind: 'com', text });
        cursor = line.length;
        break;
      }
      tokens.push({ kind: 'plain', text: '--' });
      cursor = start + 2;
      TOKEN_RE.lastIndex = cursor;
      continue;
    }

    let kind: CodeTokenKind = 'plain';
    const first = text[0];
    if (first === '"' || first === '\'' || first === '`') {
      kind = 'str';
    } else if (first >= '0' && first <= '9') {
      kind = 'num';
    } else if (keywords && (keywords.has(text) || (lang === 'sql' && keywords.has(text.toUpperCase())))) {
      kind = 'kw';
    } else if (first >= 'A' && first <= 'Z' && /[a-z]/.test(text)) {
      kind = 'type';
    } else if (line[start + text.length] === '(') {
      kind = 'fn';
    }
    tokens.push({ kind, text });
    cursor = start + text.length;
  }

  if (cursor < line.length) tokens.push({ kind: 'plain', text: line.slice(cursor) });
  return tokens;
}
