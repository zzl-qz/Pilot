import React, { useEffect, useRef, useState } from 'react';
import { apiJson } from '../api.js';
import { Icon } from './Icon.jsx';
import { writeClipboard } from '../lib/clipboard.js';

const endpoint = '/api/mobile/connection';
const post = (path, body) => apiJson(`${endpoint}/${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, 65000);

/** 管理页与具体任务无关；Web 和桌面使用同一个后台连接服务。 */
export function MobileConnectionContent({ onDone }) {
  const [tab, setTab] = useState('add');
  const [status, setStatus] = useState(null);
  const [invite, setInvite] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copyStatus, setCopyStatus] = useState('');
  const [manual, setManual] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [paired, setPaired] = useState(false);
  const [pairedDevice, setPairedDevice] = useState(null);
  const [remove, setRemove] = useState(null);
  const pending = useRef(false);
  const alive = useRef(false);
  const codeArea = useRef(null);
  const activeCode = useRef('');
  const doneButton = useRef(null);
  useEffect(() => { if (paired) doneButton.current?.focus(); }, [paired]);

  const apply = result => {
    if (!alive.current) return;
    setStatus(result);
    if (result.invite) { setPaired(false); setInvite(result.invite); activeCode.current = result.invite.code; }
    else if (activeCode.current && result.pairingState === 'paired') {
      setPaired(true); setPairedDevice(result.pairedDevice || null); setInvite(null);
      setManual(false); setCopyStatus(''); activeCode.current = '';
    } else if (result.pairingState !== 'paired') { setPaired(false); setPairedDevice(null); }
  };
  async function generate(reuse = false) {
    if (pending.current) return;
    pending.current = true; setBusy(true); setError(''); setCopyStatus(''); setManual(false);
    try {
      const result = await post('pair', { reuse });
      if (alive.current) { setInvite(result); activeCode.current = result.code; setPaired(false); }
    } catch (e) { if (alive.current) setError(e.message); }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }
  useEffect(() => {
    alive.current = true;
    const controller = new AbortController();
    let running = false;
    async function refresh(first = false) {
      if (running || pending.current || document.hidden) return;
      running = true;
      try {
        const result = await apiJson(endpoint, { signal: controller.signal }, 40000);
        if (!alive.current || pending.current) return;
        apply(result); setError('');
        if (first && !result.invite) {
          if (result.devices?.length) { setTab('devices'); setPaired(false); }
          else await generate(true);
        }
      } catch (e) { if (alive.current && !controller.signal.aborted) setError(e.message); }
      finally { running = false; }
    }
    refresh(true);
    const poll = setInterval(refresh, 3000);
    const tick = setInterval(() => setNow(Date.now()), 1000);
    return () => { alive.current = false; controller.abort(); clearInterval(poll); clearInterval(tick); };
  }, []);

  const maxDevices = status?.maxDevices || 16;
  const full = (status?.devices?.length || 0) >= maxDevices;
  function showAdd() {
    setTab('add');
    if (tab !== 'add' && !invite && !full) generate(true);
  }
  const seconds = Math.max(0, Math.ceil(((invite?.expiresAt || 0) - now) / 1000));
  async function copy() {
    if (!invite?.code || !seconds) return;
    const copiedCode = invite.code;
    let ok = false;
    try {
      if (window.pilotDesktop?.copyPairingCode) ok = (await window.pilotDesktop.copyPairingCode(invite.code))?.ok === true;
      if (!ok) ok = await writeClipboard(invite.code);
    } catch { /* 继续提供可选择的原文。 */ }
    if (!alive.current || activeCode.current !== copiedCode) return;
    setCopyStatus(ok ? '已复制到这台电脑的剪贴板' : '自动复制不可用，请选中下方配对码手动复制。');
    if (!ok) setManual(true);
  }
  async function revoke() {
    if (!remove || pending.current) return;
    pending.current = true; setBusy(true); setError('');
    try { await post('revoke', { id: remove.id }); setRemove(null); apply(await apiJson(endpoint)); }
    catch (e) { if (alive.current) setError(e.message); }
    finally { pending.current = false; if (alive.current) setBusy(false); }
  }
  return <div className="mobile-connection-content">
    <p className="mobile-scope">连接这台电脑，管理全部项目与任务。任务继续在电脑上执行。</p>
    <div className="mobile-tabs" role="tablist" aria-label="手机连接">
      <button role="tab" aria-selected={tab === 'add'} onClick={showAdd}>添加设备</button>
      <button role="tab" aria-selected={tab === 'devices'} onClick={() => setTab('devices')}>已配对设备{status ? ` · ${status.devices?.length || 0}` : ''}</button>
    </div>
    {error && <div className="mobile-connection-error" role="alert"><p>{error}</p><button className="btn" onClick={() => generate(true)} disabled={busy}>重试连接</button></div>}
    {tab === 'add' && paired ? <div className="mobile-pair-success" role="tabpanel" aria-label="配对完成">
      <Icon name="check" className="mobile-pair-success-icon" />
      <h3 role="status">配对成功</h3>
      <p>{pairedDevice?.name ? `“${pairedDevice.name}”已获得授权。` : '手机已获得授权。'}现在可以在手机上管理项目和任务。</p>
      <p className="chip-hint">关闭此窗口不会断开手机连接。最多可配对 {maxDevices} 台设备。</p>
      <div className="mobile-completion-actions">
        <button ref={doneButton} className="btn btn-primary" onClick={onDone}>完成</button>
        <button className="btn" disabled={busy || full} onClick={() => generate(false)}>{busy ? '正在生成…' : '添加另一台设备'}</button>
        <button className="btn" onClick={() => setTab('devices')}>查看已配对设备</button>
      </div>
      {full && <p className="chip-hint">已达到 {maxDevices} 台上限，请先撤销不再使用的设备。</p>}
    </div> : tab === 'add' && full ? <div className="mobile-pair-success" role="tabpanel" aria-label="设备数量已满">
      <h3>已配对 {maxDevices} 台设备</h3><p>请先撤销不再使用的设备，再添加新的手机。</p>
      <div className="mobile-completion-actions"><button className="btn btn-primary" onClick={() => setTab('devices')}>管理已配对设备</button><button className="btn" onClick={onDone}>完成</button></div>
    </div> : tab === 'add' ? <div role="tabpanel" aria-label="添加设备" className="mobile-pair-layout">
      <div className="mobile-qr-area">
        {invite && seconds > 0 ? <img src={invite.image} alt="用 Pilot 手机 App 扫描此配对二维码" />
          : <div className="mobile-qr-placeholder" role="status">{busy || !status ? '正在准备连接…' : invite ? '配对码已过期' : '还未生成配对码'}</div>}
        <p role="status">{seconds > 0 ? `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} 后过期 · 仅限一次配对` : ''}</p>
      </div>
      <div className="mobile-pair-actions">
        <h3>用 Pilot App 扫码</h3>
        <p>打开手机上的 Pilot，选择“扫码连接电脑”。手机与电脑无需处于同一个网络。</p>
        <p className="chip-hint">请保持电脑在线。配对后，无需为每个任务重新扫码。</p>
        <button className="btn btn-primary" disabled={!seconds || busy} onClick={copy}>复制配对码</button>
        <button className="btn" disabled={busy} onClick={() => generate(false)}>{busy ? '正在生成…' : invite ? '重新生成' : '生成配对码'}</button>
        <button className="link-btn" onClick={() => setManual(!manual)} disabled={!seconds}>手动复制配对码</button>
        <p className="chip-hint" role="status">{copyStatus}</p>
        {manual && seconds > 0 && <><label htmlFor="mobile-pair-code">完整配对码</label><textarea id="mobile-pair-code" ref={codeArea} readOnly rows={3} value={invite.code} onFocus={e => e.target.select()} /><button className="btn" onClick={() => { codeArea.current?.focus(); codeArea.current?.select(); }}>全选配对码</button></>}
        {invite?.development && <p className="chip-hint">已自动配置公共连接服务，无需自建服务器；连接速度与可用性受网络及公共服务影响。</p>}
      </div>
    </div> : <div role="tabpanel" aria-label="已配对设备" className="mobile-device-list">
      {!status ? <p>正在读取设备…</p> : !status.devices?.length ? <p>还没有配对设备。添加手机后会显示在这里。</p> : status.devices.map(device => <div className="mobile-device-row" key={device.id}>
        <div><strong>{device.name}</strong><p>{device.online ? '在线' : '离线'} · 配对于 {new Date(device.pairedAt).toLocaleDateString()}</p></div>
        <button className="btn" onClick={() => setRemove(device)}>撤销授权</button>
      </div>)}
      {remove && <div className="mobile-revoke" role="alert"><p>撤销“{remove.name}”的授权？已有连接会立即断开，电脑上的任务继续运行。</p><button className="btn" disabled={busy} onClick={revoke}>确认撤销</button><button className="btn" disabled={busy} onClick={() => setRemove(null)}>取消</button></div>}
      <div className="mobile-device-footer"><p className="chip-hint">最多配对 {maxDevices} 台设备，可同时连接。每台新设备需单独扫码授权。</p><button className="btn" onClick={onDone}>完成</button></div>
    </div>}
  </div>;
}
