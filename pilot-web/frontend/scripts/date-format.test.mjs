import assert from 'node:assert/strict';
import { formatDateTime, formatDate, safeIsoDate, parseValidDate } from '../src/lib/date-format.ts';

assert.equal(parseValidDate(null), null);
assert.equal(parseValidDate(''), null);
assert.equal(parseValidDate('not-a-date'), null);
assert.equal(formatDateTime('not-a-date', { fallback: '时间未知' }), '时间未知');
assert.equal(formatDate(undefined, { fallback: '—' }), '—');
assert.equal(safeIsoDate('bad-timestamp'), undefined);

const iso = '2026-11-20T08:00:00.000Z';
assert.equal(safeIsoDate(iso), iso);
assert.ok(formatDateTime(iso, { timeZone: 'UTC' }).includes('2026'));
assert.ok(formatDate(iso).includes('2026'));
console.log('PASS date-format.test.mjs');
