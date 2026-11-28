import assert from 'node:assert/strict';
import test from 'node:test';

import { cdpEndpoint, classifySensitivePage, externalAgentHostAllowed, hostDecision, hostRulesFromEnv,
  inspectDownloadSafety, loopbackOrigin, parseHosts, publicHttps, sanitizeDiagnosticText, sanitizeDiagnosticUrl,
  sanitizeDownloadFilename, sensitivePageUrl, sensitiveRulesFromEnv } from '../../main/resources/browser/browser-policy.mjs';

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

test('cdpEndpoint 只接受回环地址的 http/https 端点', () => {
  assert.equal(cdpEndpoint('http://127.0.0.1:9222'), 'http://127.0.0.1:9222');
  assert.equal(cdpEndpoint('http://localhost:9222/'), 'http://localhost:9222');
  assert.equal(cdpEndpoint(' https://127.0.0.1:9223/devtools '), 'https://127.0.0.1:9223');
  assert.equal(cdpEndpoint('http://[::1]:9222'), 'http://[::1]:9222');
  // 跨网络的端点等于把真实浏览器控制权交出去，一律不认
  assert.equal(cdpEndpoint('http://10.0.0.5:9222'), null);
  assert.equal(cdpEndpoint('ws://127.0.0.1:9222'), null);
  assert.equal(cdpEndpoint('file:///tmp/x'), null);
  assert.equal(cdpEndpoint(''), null);
  assert.equal(cdpEndpoint(undefined), null);
  assert.equal(cdpEndpoint('不是地址'), null);
});

test('externalAgentHostAllowed 空清单等于谁都不碰，且 deny 优先', () => {
  const hosts = { allow: ['example.com', '*.ok.test'], deny: ['bad.example.com'] };
  assert.equal(externalAgentHostAllowed('https://example.com/a', hosts), true);
  assert.equal(externalAgentHostAllowed('https://a.ok.test/a', hosts), true);
  assert.equal(externalAgentHostAllowed('https://bad.example.com/a', hosts), false);
  assert.equal(externalAgentHostAllowed('https://other.com/a', hosts), false);
  // 与 publicHttps 相反：空清单不意味着放行
  assert.equal(externalAgentHostAllowed('http://127.0.0.1:5173/', { allow: [], deny: [] }), false);
  assert.equal(externalAgentHostAllowed('http://127.0.0.1:5173/', undefined), false);
  // 外部浏览器只接管网页，about:blank / 非 http(s) 一律不放
  assert.equal(externalAgentHostAllowed('about:blank', hosts), false);
  assert.equal(externalAgentHostAllowed('file:///etc/passwd', { allow: ['*'], deny: [] }), false);
});

test('浏览器诊断会遮挡认证凭据、令牌和敏感 URL 参数', () => {
  const text = sanitizeDiagnosticText('Authorization: Bearer abcdef123456 token=secret-value sk-abcdefghijklmnop https://x.test/cb?code=oauth-secret');
  assert.doesNotMatch(text, /abcdef123456|secret-value|sk-abcdefghijklmnop|oauth-secret/);
  const url = sanitizeDiagnosticUrl('https://user:pass@example.test/api?ok=1&access_token=secret#state=private');
  assert.match(url, /ok=1/);
  assert.doesNotMatch(url, /user|pass|secret|private/);
});

test('脱敏不会误伤普通筛选参数', () => {
  assert.match(sanitizeDiagnosticUrl('https://example.test/search?author=alice&status=open'), /author=alice/);
});

test('敏感登录和支付页面不向模型返回页面内容', () => {
  assert.equal(sensitivePageUrl('https://accounts.google.com/signin'), true);
  assert.equal(sensitivePageUrl('https://shop.example/checkout/order'), true);
  assert.equal(sensitivePageUrl('http://localhost:5173/dashboard'), false);
  assert.equal(sensitivePageUrl('file:///checkout'), false);
  assert.equal(classifySensitivePage('https://checkout.stripe.com/pay/cs_123'), 'payment');
  assert.equal(classifySensitivePage('https://accounts.google.com/o/oauth2/auth'), 'auth');
  const rules = sensitiveRulesFromEnv({
    PILOT_BROWSER_SENSITIVE_HOSTS: 'vault.internal',
    PILOT_BROWSER_SENSITIVE_ALLOW_HOSTS: 'dev.local'
  });
  assert.equal(classifySensitivePage('https://vault.internal/home', rules), 'auth');
  // sensitive_allow 可放行本地测试 auth，但始终不放行 payment
  assert.equal(sensitivePageUrl('http://dev.local/signin', rules), false);
  assert.equal(classifySensitivePage('http://dev.local/checkout', rules), 'payment');
});

test('loopbackOrigin 严格按协议、回环主机名和端口隔离', () => {
  assert.equal(loopbackOrigin('http://localhost:5173/app'), 'http://localhost:5173');
  assert.equal(loopbackOrigin('http://127.0.0.1:8080/a'), 'http://127.0.0.1:8080');
  assert.equal(loopbackOrigin('https://example.com/a'), null);
});

test('inspectDownloadSafety 校验扩展名、危险 MIME、魔数签名与大小上限', () => {
  assert.equal(inspectDownloadSafety({ filename: 'report.txt', headBytes: Buffer.from('hello'), mimeType: 'text/plain' }).allowed, true);
  assert.equal(inspectDownloadSafety({ filename: 'run.exe', headBytes: Buffer.from('hello') }).allowed, false);
  assert.equal(inspectDownloadSafety({ filename: 'doc.txt', headBytes: Buffer.from('hello'), mimeType: 'application/x-msdownload' }).allowed, false);
  // 伪装成 .txt 的 Windows PE 可执行头（MZ）
  assert.equal(inspectDownloadSafety({ filename: 'fake.txt', headBytes: Buffer.from([0x4d, 0x5a, 0x90, 0x00]) }).allowed, false);
  // ELF 可执行头
  assert.equal(inspectDownloadSafety({ filename: 'bin.dat', headBytes: Buffer.from([0x7f, 0x45, 0x4c, 0x46]) }).allowed, false);
  // Mach-O 与 shebang
  assert.equal(inspectDownloadSafety({ filename: 'macho.bin', headBytes: Buffer.from([0xcf, 0xfa, 0xed, 0xfe]) }).allowed, false);
  assert.equal(inspectDownloadSafety({ filename: 'run.txt', headBytes: Buffer.from('#!/bin/sh\nrm -rf /') }).allowed, false);
  // 超出大小上限
  assert.equal(inspectDownloadSafety({ filename: 'big.zip', sizeBytes: 200, maxBytes: 100 }).allowed, false);
});

test('sanitizeDownloadFilename 防护截断后与点前空格导致的 Windows 保留设备名绕过', () => {
  assert.equal(sanitizeDownloadFilename('CON' + ' '.repeat(120) + 'x'), '_CON');
  assert.equal(sanitizeDownloadFilename('con' + ' '.repeat(118) + '.txt'), '_con.txt');
  assert.equal(sanitizeDownloadFilename('CON .txt'), '_CON .txt');
  const longReserved = sanitizeDownloadFilename('con.' + 'a'.repeat(112) + '.txt');
  assert.ok(longReserved.startsWith('_'), longReserved);
  assert.ok(longReserved.endsWith('.txt'), longReserved);
  assert.ok(longReserved.length <= 120, String(longReserved.length));
});
