import { mkdtemp, rename, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';

export async function saveSessionMarkdown(name, text, showSaveDialog) {
  if (typeof name !== 'string' || !/^pilot-[^\\/:*?"<>|\p{Cc}]+\.md$/u.test(name) || name.length > 128
      || typeof text !== 'string') throw new Error('无效的会话导出文件');

  const { canceled, filePath } = await showSaveDialog({
    title: '导出会话', defaultPath: name, filters: [{ name: 'Markdown', extensions: ['md'] }]
  });
  if (canceled || !filePath) return { ok: true, canceled: true };
  // 同目录写完再替换，磁盘满时保留用户选择覆盖的原文件。
  const staging = await mkdtemp(join(dirname(filePath), '.pilot-export-'));
  try {
    const temp = join(staging, 'session.md');
    await writeFile(temp, text, { encoding: 'utf8', mode: 0o600 });
    await rename(temp, filePath);
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
  return { ok: true, path: filePath };
}
