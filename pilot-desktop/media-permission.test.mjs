import test from 'node:test';
import assert from 'node:assert/strict';
import { installMicrophonePermissions } from './media-permission.mjs';
for (const platform of ['darwin', 'win32', 'linux']) {
  test(`${platform} 只对 Pilot 主页面授权音频，macOS 请求系统权限`, async () => {
    let request, check, asked = 0;
    const contents = { isDestroyed: () => false, getURL: () => 'http://localhost:9999/', session: {
      setPermissionCheckHandler: f => { check = f; }, setPermissionRequestHandler: f => { request = f; } } };
    installMicrophonePermissions(contents, 'http://localhost:9999/', { askForMediaAccess: async () => { asked++; return true; } }, platform);
    const ask = (source, permission, details) => new Promise(resolve => request(source, permission, resolve, details));
    const audio = { requestingUrl: 'http://localhost:9999/', mediaTypes: ['audio'], isMainFrame: true };
    assert.equal(await ask(contents, 'media', audio), true);
    assert.equal(asked, platform === 'darwin' ? 1 : 0);
    assert.equal(await ask(contents, 'media', { ...audio, mediaTypes: ['audio', 'video'] }), false);
    assert.equal(await ask(contents, 'media', { ...audio, requestingUrl: 'https://untrusted.test' }), false);
    assert.equal(await ask(contents, 'media', { ...audio, isMainFrame: false }), false);
    assert.equal(await ask({}, 'media', audio), false);
    assert.equal(check(contents, 'media', 'http://localhost:9999', { mediaType: 'audio' }), true);
    assert.equal(check(contents, 'media', 'http://localhost:9999', { mediaType: 'video' }), false);
  });
}
test('macOS 权限拒绝和异常均不放行', async () => {
  for (const askForMediaAccess of [async () => false, async () => { throw Error('denied'); }]) {
    let request;
    const contents = { getURL: () => 'http://localhost:9999/', isDestroyed: () => false, session: { setPermissionCheckHandler() {}, setPermissionRequestHandler: f => { request = f; } } };
    installMicrophonePermissions(contents, 'http://localhost:9999/', { askForMediaAccess }, 'darwin');
    assert.equal(await new Promise(r => request(contents, 'media', r, { requestingUrl: contents.getURL(), mediaTypes: ['audio'] })), false);
  }
});


test('系统权限返回前页面离开 Pilot 时，不授权新页面', async () => {
  let request, grant, url = 'http://localhost:9999/';
  const contents = { getURL: () => url, isDestroyed: () => false, session: { setPermissionCheckHandler() {}, setPermissionRequestHandler: f => { request = f; } } };
  installMicrophonePermissions(contents, url, { askForMediaAccess: () => new Promise(resolve => { grant = resolve; }) }, 'darwin');
  const result = new Promise(resolve => request(contents, 'media', resolve, { requestingUrl: url, mediaTypes: ['audio'] }));
  url = 'https://another.example/'; grant(true); assert.equal(await result, false);
});
