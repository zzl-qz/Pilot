import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeQueue, queueHeader, resumeBar } from '../../../frontend/src/lib/queue.js';

test('queue payload uses entries and items, not a missing messages field', () => {
  const normalized = normalizeQueue({
    items: ['下一件事'],
    entries: [{ content: '下一件事', attachments: [], imagePositions: [] }]
  });
  assert.deepEqual(normalized.texts, ['下一件事']);
  assert.equal(normalized.rows[0].text, '下一件事');
});

test('an empty or old payload does not throw and stays empty', () => {
  assert.deepEqual(normalizeQueue(null), { texts: [], rows: [] });
  assert.deepEqual(normalizeQueue({}), { texts: [], rows: [] });
});

test('queue entries keep image attachments for the list', () => {
  const normalized = normalizeQueue({
    items: ['看这张图'],
    entries: [{
      content: '看这张图',
      attachments: [{ name: 'a.png', mimeType: 'image/png', url: '/api/sessions/s/attachments/a.png' }],
      imagePositions: [0]
    }]
  });
  assert.equal(normalized.rows[0].attachments[0].url, '/api/sessions/s/attachments/a.png');
  assert.deepEqual(normalized.rows[0].imagePositions, [0]);
});

test('queue header shows only nonempty queues and retains stopped entries', () => {
  assert.equal(queueHeader({ count: 0, held: false }), null);
  assert.equal(queueHeader({ count: 2, held: false }).title, '2 条排队');
  assert.match(queueHeader({ count: 2, held: false }).meta, /按顺序/);
  assert.equal(queueHeader({ count: 0, held: true }), null);
  assert.match(queueHeader({ count: 1, held: true }).meta, /已暂停/);
});

test('resume bar is the only continue entry, and it stays hidden while a run is active', () => {
  assert.equal(resumeBar({ running: true, resumable: true }), null);
  assert.equal(resumeBar({ running: false }), null);
  assert.equal(resumeBar({ needInput: true }).label, '继续当前任务');
  assert.equal(resumeBar({ resumable: true }).label, '继续执行');
});
