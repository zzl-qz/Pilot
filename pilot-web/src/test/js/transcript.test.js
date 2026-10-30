import test from 'node:test';
import assert from 'node:assert/strict';
import { buildTranscript, transcriptFileName } from '../../main/resources/static/lib/transcript.js';

test('transcript renders turns in order with a tool summary', () => {
  const md = buildTranscript({
    title: '读 pom',
    workspace: '/work/pilot',
    exportedAt: '2026-10-30 16:00',
    messages: [
      { role: 'user', text: '看下模块' },
      { role: 'tools', summary: '读取 ×1 · 列目录 ×1' },
      { role: 'assistant', text: '有 3 个模块。' }
    ]
  });
  assert.match(md, /^# 读 pom\n/);
  assert.ok(md.indexOf('## 你') < md.indexOf('工具调用') && md.indexOf('工具调用') < md.indexOf('## Pilot'));
  assert.match(md, /工作区：`\/work\/pilot`/);
  assert.ok(md.endsWith('\n') && !md.endsWith('\n\n'));
});

test('transcript skips empty messages and tolerates missing input', () => {
  assert.equal(buildTranscript({ messages: [{ role: 'user', text: '  ' }] }), '# 未命名对话\n');
  assert.equal(buildTranscript({}), '# 未命名对话\n');
});

test('file names are filesystem safe and dated', () => {
  const name = transcriptFileName('a/b: c?*', new Date('2026-10-30T08:00:00Z'));
  assert.equal(name, 'pilot-a-b-c-2026-10-30.md');
  assert.equal(transcriptFileName('', new Date('2026-10-30T08:00:00Z')), 'pilot-对话-2026-10-30.md');
});
