import test from 'node:test';
import assert from 'node:assert/strict';
import { offlinePage } from './offline-page.mjs';

test('离线页显示实际服务并保留重试地址', () => {
  const page = offlinePage('http://localhost:12345/app?q=a&b=2');
  assert.match(page, /<code>http:\/\/localhost:12345<\/code>/);
  assert.match(page, /href="http:\/\/localhost:12345\/app\?q=a&amp;b=2"/);
  assert.match(page, /重新连接/);
});

test('无效和主动脚本地址回退，不能插入 HTML', () => {
  for (const value of ['javascript:alert(1)', 'invalid', 'data:text/html,<script>']) {
    assert.match(offlinePage(value), /href="http:\/\/127.0.0.1:9999\/"/);
  }
  assert.doesNotMatch(offlinePage('http://localhost/?q="<script>'), /<script>/);
});
