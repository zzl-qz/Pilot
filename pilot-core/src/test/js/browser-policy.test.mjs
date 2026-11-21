import assert from 'node:assert/strict';
import test from 'node:test';

import { hostDecision, hostRulesFromEnv, parseHosts, publicHttps } from '../../main/resources/browser/browser-policy.mjs';

test('parseHosts 接受逗号与空白两种分隔，并统一小写去空白', () => {
  assert.deepEqual(parseHosts('a.com, b.com  c.com'), ['a.com', 'b.com', 'c.com']);
  assert.deepEqual(parseHosts(' A.com ,, B.com. '), ['a.com', 'b.com']);
  assert.deepEqual(parseHosts(''), []);
  assert.deepEqual(parseHosts(undefined), []);
  assert.deepEqual(parseHosts(['a.com', 'b.com c.com']), ['a.com', 'b.com', 'c.com']);
});

test('hostDecision 三态：拒绝清单优先、空放行清单不收紧、清单外拦下', () => {
  const hosts = { allow: ['example.com'], deny: ['bad.example.com'] };
  assert.equal(hostDecision('bad.example.com', hosts), 'deny');
  assert.equal(hostDecision('example.com', hosts), 'allow');
  assert.equal(hostDecision('other.com', hosts), 'blocked');
  // 同一主机同时在两个清单里时 deny 胜出
  assert.equal(hostDecision('same.com', { allow: ['same.com'], deny: ['same.com'] }), 'deny');
});

test('hostDecision 空放行清单表示不额外收紧，写错 allow 也不会放过被拒主机', () => {
  assert.equal(hostDecision('any.com', { allow: [], deny: [] }), 'allow');
  assert.equal(hostDecision('any.com', {}), 'allow');
  assert.equal(hostDecision('any.com', { allow: [], deny: ['any.com'] }), 'deny');
  // 主机名为空无法判定，按拒绝处理
  assert.equal(hostDecision('', { allow: ['any.com'] }), 'deny');
});

test('hostDecision 的通配只覆盖整段标签，不做前缀拼接', () => {
  assert.equal(hostDecision('example.com', { allow: ['*.example.com'] }), 'allow');
  assert.equal(hostDecision('a.example.com', { allow: ['*.example.com'] }), 'allow');
  assert.equal(hostDecision('a.b.example.com', { allow: ['*.example.com'] }), 'allow');
  assert.equal(hostDecision('notexample.com', { allow: ['*.example.com'] }), 'blocked');
  assert.equal(hostDecision('example.com.evil.com', { allow: ['*.example.com'] }), 'blocked');
  // 不带 * 的写法只匹配自身，不覆盖子域
  assert.equal(hostDecision('a.example.com', { allow: ['example.com'] }), 'blocked');
});

test('hostDecision 支持 * 全通配，写掉的忽略大小写', () => {
  assert.equal(hostDecision('anything.test', { allow: ['*'] }), 'allow');
  assert.equal(hostDecision('Example.COM', { allow: ['example.com'] }), 'allow');
  assert.equal(hostDecision('example.com.', { allow: ['example.com'] }), 'allow');
});

test('hostRulesFromEnv 从 sidecar 环境变量读两份清单', () => {
  assert.deepEqual(hostRulesFromEnv({ PILOT_BROWSER_ALLOW_HOSTS: 'a.com b.com',
    PILOT_BROWSER_DENY_HOSTS: 'bad.com' }), { allow: ['a.com', 'b.com'], deny: ['bad.com'] });
  assert.deepEqual(hostRulesFromEnv({}), { allow: [], deny: [] });
});

test('publicHttps 先过地址校验再过主机清单', async () => {
  const resolve = async () => [{ address: '93.184.216.34' }];
  assert.equal(await publicHttps('https://example.com/a', resolve, { allow: ['example.com'], deny: [] }), true);
  assert.equal(await publicHttps('https://other.com/a', resolve, { allow: ['example.com'], deny: [] }), false);
  assert.equal(await publicHttps('https://example.com/a', resolve, { allow: ['*'], deny: ['example.com'] }), false);
  // 未给清单时保持原行为：公开 HTTPS 即通过
  assert.equal(await publicHttps('https://example.com/a', resolve), true);
  assert.equal(await publicHttps('http://example.com/a', resolve, { allow: ['*'] }), false);
  assert.equal(await publicHttps('https://127.0.0.1/a', resolve, { allow: ['*'] }), false);
});