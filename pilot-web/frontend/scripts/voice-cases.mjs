import assert from 'node:assert/strict';
import { join } from 'node:path';

/** Chromium / Electron 的假音频设备 + 真实 MediaRecorder，不使用人的麦克风。 */
export async function checkVoice(page, name, temp) {
  const input = page.locator('#input-flow');
  await page.evaluate(() => {
    store.setState(s => { s.running = false; s.queueEntries = []; s.ui.queueEdit = null; s.ui.inspectorOpen = false; });
    store.getState().composerApi.setText('原有草稿');
    window.originalGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    api.transcribeVoice = window.realTranscribe;
  });
  await input.evaluate(el => {
    const data = new DataTransfer(); data.items.add(new File(['keep'], 'voice-note.txt', { type: 'text/plain' }));
    el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  });
  await page.waitForFunction(() => document.querySelectorAll('[data-chip=attachment]').length === 1);
  let uploads = 0;
  await page.route('**/api/voice/transcriptions', async route => {
    uploads++;
    const request = route.request();
    assert.match(request.headers()['content-type'], /multipart\/form-data; boundary=/);
    assert.ok(request.postDataBuffer().length > 100);
    const payload = request.postDataBuffer().toString('latin1');
    assert.match(payload.slice(0, 300), /filename="recording\.(wav|webm|mp4|ogg)"/);
    if (payload.includes('filename="recording.wav"')) {
      assert.match(payload.slice(0, 300), /Content-Type: audio\/wav/);
      assert.ok(payload.includes('RIFF') && payload.includes('WAVE'), 'WAV 上传应包含真实音频头');
    }
    await route.fulfill({ json: { text: '请检查粘贴和队列边界' } });
  });
  const sent = await page.evaluate(() => calls.filter(c => c.name === 'sendMessage').length);
  await page.getByRole('button', { name: '语音输入', exact: true }).click();
  await page.getByRole('button', { name: '完成录音', exact: true }).waitFor();
  assert.equal(await page.locator('#send').isDisabled(), true);
  await page.screenshot({ path: join(temp, `${name}-recording.png`) });
  await page.waitForTimeout(350); await page.getByRole('button', { name: '完成录音', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('#input-flow').innerText.includes('请检查粘贴和队列边界'));
  assert.equal(uploads, 1); assert.match(await input.innerText(), /原有草稿/);
  assert.equal(await page.locator('[data-chip=attachment]').count(), 1);
  assert.equal(await page.evaluate(() => calls.filter(c => c.name === 'sendMessage').length), sent, '语音不能自动发消息');
  // 权限拒绝可恢复；不会更改已有草稿。
  const before = await page.evaluate(() => store.getState().composerApi.getText());
  await page.evaluate(() => { navigator.mediaDevices.getUserMedia = async () => { throw new DOMException('denied', 'NotAllowedError'); }; });
  await page.getByRole('button', { name: '语音输入', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '麦克风权限被拒绝' }).waitFor();
  assert.equal(await page.evaluate(() => store.getState().composerApi.getText()), before);
  await page.locator('.voice-panel').getByRole('button', { name: '关闭', exact: true }).click();
  await page.evaluate(() => { navigator.mediaDevices.getUserMedia = originalGetUserMedia; api.transcribeVoice = async () => { throw Error('转写服务不可用'); }; });
  await page.getByRole('button', { name: '语音输入', exact: true }).click();
  await page.getByRole('button', { name: '完成录音', exact: true }).waitFor(); await page.waitForTimeout(300);
  await page.getByRole('button', { name: '完成录音', exact: true }).click();
  await page.getByRole('button', { name: '重试转写' }).waitFor();
  await page.evaluate(() => { api.transcribeVoice = async () => ({ text: '重试保留录音' }); });
  await page.getByRole('button', { name: '重试转写' }).click();
  await page.waitForFunction(() => document.querySelector('#input-flow').innerText.includes('重试保留录音'));
  // 运行中 Esc 先取消录音，不停止任务。
  await page.evaluate(() => store.setState({ running: true, runId: 'voice-run' }));
  const cancelled = await page.evaluate(() => calls.filter(c => c.name === 'cancelRun').length);
  await page.getByRole('button', { name: '语音输入', exact: true }).click();
  await page.getByRole('button', { name: '完成录音', exact: true }).waitFor(); await page.keyboard.press('Escape');
  assert.equal(await page.locator('.voice-panel').count(), 0);
  assert.equal(await page.evaluate(() => calls.filter(c => c.name === 'cancelRun').length), cancelled);
  // 迟到结果不得进入新会话。
  await page.evaluate(() => { api.transcribeVoice = () => new Promise(resolve => { window.resolveVoice = resolve; }); });
  await page.getByRole('button', { name: '语音输入', exact: true }).click();
  await page.getByRole('button', { name: '完成录音', exact: true }).waitFor(); await page.waitForTimeout(300);
  await page.getByRole('button', { name: '完成录音', exact: true }).click();
  await page.waitForFunction(() => !!window.resolveVoice);
  await page.evaluate(() => store.setState(s => { s.sessionId = 'voice-next'; s.sessionEpoch++; s.running = false; }));
  await page.waitForFunction(() => !document.querySelector('.voice-panel'));
  await page.evaluate(() => resolveVoice({ text: '不能出现在新会话' })); await page.waitForTimeout(40);
  assert.doesNotMatch(await input.innerText(), /不能出现在新会话/);
  await page.unroute('**/api/voice/transcriptions');
}
