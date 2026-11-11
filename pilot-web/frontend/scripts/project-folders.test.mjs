import assert from 'node:assert/strict';
import test from 'node:test';
import { appendProjectFolders, folderPaths, validateProjectFolders } from '../src/lib/project-folders.js';

test('保留中文、空格和 POSIX 大小写，消除重复和尾部分隔符', () => {
  assert.deepEqual(folderPaths('/\n/work/资料 空间/\n/work/资料 空间\n/Work\n/work'), ['/', '/work/资料 空间', '/Work', '/work']);
});
test('模拟 Windows 盘符和 UNC 路径，不依赖宿主操作系统', () => {
  assert.deepEqual(folderPaths('C:/\nc:\\\nC:/项目 空间/\nc:\\项目 空间\n\\\\server\\share\\\n\\\\SERVER\\SHARE'), ['C:\\', 'C:\\项目 空间', '\\\\server\\share']);
});
test('最多十二个，重复添加不消耗数量，超限不丢弃已有选择', () => {
  const full = Array.from({ length:12 }, (_, i) => `/workspace/${i}`).join('\n');
  assert.equal(validateProjectFolders(appendProjectFolders(full, ['/workspace/0/'])).length, 12);
  assert.throws(() => appendProjectFolders(full, ['/workspace/12']), /最多绑定 12/);
});
test('拒绝空列表、相对路径、控制字符和路径中的换行注入', () => {
  for (const text of ['', '.', 'folder', 'C:relative', '/tmp/\u0000bad']) assert.throws(() => validateProjectFolders(text));
  assert.throws(() => appendProjectFolders('', ['/real\n/injected']), /换行/);
});
