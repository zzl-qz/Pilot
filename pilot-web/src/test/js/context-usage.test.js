import test from 'node:test';
import assert from 'node:assert/strict';

import {
  formatTokens, remainingTokens, usageLevel, usageNote, usagePercent, usageSegments
} from '../../../frontend/src/lib/context-usage.js';

test('usagePercent 优先用后端算好的值，缺了按用量除预算', () => {
  assert.equal(usagePercent({ percent: 13, usedTokens: 1, limitTokens: 1000 }), 13);
  assert.equal(usagePercent({ usedTokens: 250, limitTokens: 1000 }), 25);
  assert.equal(usagePercent({ usedTokens: 2000, limitTokens: 1000 }), 100);
  assert.equal(usagePercent({ usedTokens: 10 }), 0);
  assert.equal(usagePercent(null), 0);
});

test('usageLevel 与自动收缩水位对齐：80% 折叠、97% 硬裁剪', () => {
  assert.equal(usageLevel(0), 'normal');
  assert.equal(usageLevel(79), 'normal');
  assert.equal(usageLevel(80), 'warn');
  assert.equal(usageLevel(96), 'warn');
  assert.equal(usageLevel(97), 'danger');
  assert.equal(usageLevel(100), 'danger');
});

test('formatTokens 千分位简写，未知给占位', () => {
  assert.equal(formatTokens(0), '0');
  assert.equal(formatTokens(999), '999');
  assert.equal(formatTokens(1200), '1.2k');
  assert.equal(formatTokens(128000), '128k');
  assert.equal(formatTokens(1500000), '1.5M');
  assert.equal(formatTokens(null), '—');
  assert.equal(formatTokens(Number.NaN), '—');
});

test('remainingTokens 不给出负数，预算未知返回 null', () => {
  assert.equal(remainingTokens({ usedTokens: 200, limitTokens: 1000 }), 800);
  assert.equal(remainingTokens({ usedTokens: 2000, limitTokens: 1000 }), 0);
  assert.equal(remainingTokens({ usedTokens: 200 }), null);
});

test('usageSegments 丢掉空项、按字符降序并算占比', () => {
  const segments = usageSegments({
    segments: [
      { role: 'user', messages: 2, chars: 100 },
      { role: 'tool', messages: 5, chars: 300 },
      { role: 'system', messages: 1, chars: 0 },
      { role: 'assistant', messages: 1, chars: 600 }
    ]
  });
  assert.deepEqual(segments.map((item) => item.role), ['assistant', 'tool', 'user']);
  assert.equal(segments[0].label, '助手回复');
  assert.equal(segments[0].share, 0.6);
  assert.equal(segments[2].label, '你的输入');
  assert.deepEqual(usageSegments(null), []);
});

test('usageNote 说清处在哪一档', () => {
  assert.match(usageNote({}, 0), /还没/);
  assert.match(usageNote({ usedTokens: 100, limitTokens: 1000 }, 20), /离自动折叠.*还有/);
  assert.match(usageNote({ usedTokens: 850, limitTokens: 1000 }, 85), /折叠/);
  assert.match(usageNote({ usedTokens: 990, limitTokens: 1000 }, 99), /硬裁剪/);
});
