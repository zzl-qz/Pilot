import test from 'node:test';
import assert from 'node:assert/strict';
import { agentMayOperate, agentMayTouchPage, formatPageLogs, markerPath, partitionName, snapBounds, userPageUrl } from './protocol.mjs';

test('人的地址栏：本机补 http，外网补 https，拒绝别的协议', () => {
  assert.equal(userPageUrl('localhost:5173'), 'http://localhost:5173/');
  assert.equal(userPageUrl('https://example.com/a'), 'https://example.com/a');
  assert.equal(userPageUrl('example.com'), 'https://example.com/');
  assert.equal(userPageUrl('javascript:alert(1)'), null);
  assert.equal(userPageUrl(''), null);
});

test('桌面里眼前的外网站可以操作，无头浏览器仍然不行', () => {
  assert.equal(agentMayTouchPage('https://example.com/app', true), true);
  assert.equal(agentMayTouchPage('https://example.com/app', false), false);
  assert.equal(agentMayTouchPage('http://127.0.0.1:9999/', false), true);
  assert.equal(agentMayTouchPage('javascript:alert(1)', true), false);
});

test('智能体只允许本机源，人打开的外网页面不算', () => {
  assert.equal(agentMayOperate('http://localhost:5173/app'), true);
  assert.equal(agentMayOperate('http://127.0.0.1:8080/'), true);
  assert.equal(agentMayOperate('http://[::1]:9999/'), true);
  assert.equal(agentMayOperate('https://example.com/'), false);
  assert.equal(agentMayOperate('about:blank'), false);
});

test('工作区分区稳定且彼此不同', () => {
  assert.equal(partitionName('/work/a'), partitionName('/work/a'));
  assert.notEqual(partitionName('/work/a'), partitionName('/work/b'));
  assert.match(partitionName(''), /^persist:pilot-[0-9a-f]{16}$/);
});

test('视口按像素对齐，盖住或太小时隐藏', () => {
  assert.deepEqual(snapBounds({ x: 10.4, y: 20.6, width: 400.2, height: 300.8, visible: true }), {
    x: 10, y: 21, width: 400, height: 301, visible: true
  });
  assert.equal(snapBounds({ x: 0, y: 0, width: 10, height: 10, visible: true }).visible, false);
  assert.equal(snapBounds({ x: 0, y: 0, width: 800, height: 600, visible: false }).visible, false);
});

test('桌面页的控制台和网络记录按旧面板的句子排', () => {
  assert.equal(formatPageLogs([], 'console'), '没有控制台记录');
  assert.equal(formatPageLogs([], 'network'), '没有网络记录');
  assert.match(formatPageLogs([{ level: 'error', message: 'boom', sourceId: 'https://a.test/app.js', lineNumber: 4 }], 'console'), /\[error\] boom @ https:\/\/a\.test\/app\.js:4/);
  assert.match(formatPageLogs([{ method: 'GET', statusCode: 404, url: 'https://a.test/missing' }], 'network'), /GET 404 https:\/\/a\.test\/missing/);
});

test('标签标记路径只含这个标签的 id', () => {
  assert.equal(markerPath('abc'), '/marker/abc');
  assert.equal(markerPath('a/b'), '/marker/a%2Fb');
});
