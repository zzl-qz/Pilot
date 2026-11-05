import test from 'node:test';
import assert from 'node:assert/strict';
import { mobileBridgeOptions, parseMobileAdmin, resolveMobileJava } from './mobile-bridge.mjs';

test('移动连接仅接入本机，拒绝外部服务与不支持的平台', () => {
  const options = { pilotUrl: 'http://127.0.0.1:9999', libraries: '/tmp/path with spaces/lib', dataDir: '/tmp/data with spaces', env: {}, platform: 'linux', arch: 'x64' };
  const command = mobileBridgeOptions(options);
  assert.equal(command.java, 'java');
  assert.ok(command.args.includes('/tmp/path with spaces/lib/*'));
  assert.throws(() => mobileBridgeOptions({ ...options, pilotUrl: 'http://example.com' }));
  assert.throws(() => mobileBridgeOptions({ ...options, platform: 'darwin', arch: 'x64' }));
  for (const [platform, arch] of [['darwin', 'arm64'], ['win32', 'x64'], ['linux', 'arm64']]) {
    assert.ok(mobileBridgeOptions({ ...options, platform, arch }).args.includes('9999'));
  }
});
test('配对管理地址必须是回环地址且带随机凭证', () => {
  const url = `http://127.0.0.1:1234/#${'a'.repeat(43)}`;
  assert.equal(parseMobileAdmin(`PILOT_MOBILE_ADMIN ${url}\n`), url);
  assert.equal(parseMobileAdmin('starting'), null);
  assert.throws(() => parseMobileAdmin(`PILOT_MOBILE_ADMIN https://evil.test/#${'a'.repeat(43)}`));
  assert.throws(() => parseMobileAdmin('PILOT_MOBILE_ADMIN http://127.0.0.1:1234/'));
});

test('三个平台保留可用 Java；macOS 默认版本过低时查找已安装运行时', async () => {
  const base = { pilotUrl: 'http://127.0.0.1:9999', libraries: '/lib', dataDir: '/data', env: {}, arch: 'x64' };
  for (const [platform, arch] of [['win32', 'x64'], ['linux', 'x64'], ['darwin', 'arm64']]) {
    assert.equal(await resolveMobileJava({ ...base, platform, arch }, async () => ({ stderr: 'openjdk version "25.0.1"' })), 'java');
  }
  const calls = [];
  const resolved = await resolveMobileJava({ ...base, platform: 'darwin', arch: 'arm64' }, async (file, args) => {
    calls.push([file, args]);
    if (file === '/usr/libexec/java_home') return { stdout: '/installed jdk\n' };
    return { stderr: `openjdk version "${file === 'java' ? 17 : 21}.0.1"` };
  });
  assert.equal(resolved, '/installed jdk/bin/java');
  assert.deepEqual(calls[1], ['/usr/libexec/java_home', ['-v', '21+']]);
  await assert.rejects(resolveMobileJava({ ...base, platform: 'linux' }, async () => ({ stderr: 'openjdk version "17.0.1"' })), /Java 21/);
});

test('默认自动连接且显式直连不允许中继冲突，三个平台传参一致', () => {
  for (const [platform,arch] of [['darwin','arm64'],['win32','x64'],['linux','arm64'],['linux','x64']]) {
    const base={pilotUrl:'http://127.0.0.1:9999',libraries:'/lib',dataDir:'/data',platform,arch};
    assert.equal(mobileBridgeOptions({...base,env:{}}).args.includes('--no-relay'),false);
    assert.equal(mobileBridgeOptions({...base,env:{PILOT_MOBILE_MODE:'direct'}}).args.includes('--no-relay'),true);
    assert.throws(()=>mobileBridgeOptions({...base,env:{PILOT_MOBILE_MODE:'unknown'}}),/只能配置/);
    assert.throws(()=>mobileBridgeOptions({...base,env:{PILOT_MOBILE_MODE:'direct',PILOT_MOBILE_RELAY:'https:\/\/relay.example.com'}}),/不能同时/);
  }
});
