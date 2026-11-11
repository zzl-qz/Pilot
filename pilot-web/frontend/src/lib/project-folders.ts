export const MAX_PROJECT_FOLDERS = 12;

function cleanPath(path: string): string {
  const value = path.trim();
  if (/^[A-Za-z]:[\\/]/.test(value)) {
    return value.replaceAll('/', '\\').replace(/\\+$/, '') + (/^[A-Za-z]:[\\/]+$/.test(value) ? '\\' : '');
  }
  if (/^\\\\[^\\]+\\[^\\]+/.test(value)) return value.replace(/\\+$/, '');
  return value === '/' ? value : value.replace(/\/+$/, '');
}

export function folderPaths(text: string): string[] {
  const result: string[] = [];
  const seen = new Set<string>();
  for (const line of text.split(/\r?\n/)) {
    const path = cleanPath(line);
    if (!path) continue;
    const key = /^[A-Za-z]:\\|^\\\\/.test(path) ? path.toLowerCase() : path;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(path);
  }
  return result;
}

export function validateProjectFolders(text: string): string[] {
  const paths = folderPaths(text);
  if (!paths.length) throw new Error('请至少选择一个文件夹。');
  if (paths.length > MAX_PROJECT_FOLDERS) throw new Error(`每个项目最多绑定 ${MAX_PROJECT_FOLDERS} 个文件夹，请移除多余目录。`);
  for (const path of paths) {
    if (!/^(?:\/|[A-Za-z]:\\|\\\\[^\\]+\\[^\\]+)/.test(path) || /[\u0000-\u001f]/.test(path)) {
      throw new Error(`请填写服务端的绝对目录路径：${path}`);
    }
  }
  return paths;
}

export function appendProjectFolders(text: string, paths: string[]): string {
  if (paths.some(path => typeof path !== 'string' || /[\r\n\u0000]/.test(path))) {
    throw new Error('所选目录包含不支持的换行或空字符，请使用其他目录。');
  }
  return validateProjectFolders([text, ...paths].join('\n')).join('\n');
}
