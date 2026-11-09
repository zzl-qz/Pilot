import { useEffect, useRef, useState } from 'react';
import { Badge } from '@astryxdesign/core/Badge';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { EmptyState } from '@astryxdesign/core/EmptyState';
import { SegmentedControl, SegmentedControlItem } from '@astryxdesign/core/SegmentedControl';
import { TextArea } from '@astryxdesign/core/TextArea';
import { apiJson } from '../api.js';
import { writeClipboard } from '../lib/clipboard.js';
import { errorInfo } from '../lib/errors.js';
import type { MobileDevice, MobileInvite, MobileStatus } from '../types/mobile.js';
import './mobile-connection.css';

const endpoint = '/api/mobile/connection';
const post = <T,>(path: string, body: object) => apiJson<T>(`${endpoint}/${path}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
}, 65000);
type Operation = 'pair' | 'revoke' | 'repair';

/** 管理页与具体任务无关，Web 和桌面使用同一个后台连接服务。 */
export function MobileConnectionContent({ onDone }: { onDone: () => void }) {
  const [tab, setTab] = useState('add');
  const [status, setStatus] = useState<MobileStatus | null>(null);
  const [invite, setInvite] = useState<MobileInvite | null>(null);
  const [operation, setOperation] = useState<Operation | null>(null);
  const [readError, setReadError] = useState('');
  const [failure, setFailure] = useState<{ operation: Operation; message: string } | null>(null);
  const [copyStatus, setCopyStatus] = useState('');
  const [manual, setManual] = useState(false);
  const [failedImage, setFailedImage] = useState('');
  const [now, setNow] = useState(Date.now());
  const [paired, setPaired] = useState(false);
  const [pairedDevice, setPairedDevice] = useState<MobileDevice | null>(null);
  const [remove, setRemove] = useState<MobileDevice | null>(null);
  const [deviceMessage, setDeviceMessage] = useState('');
  const pending = useRef(false);
  const revision = useRef(0);
  const alive = useRef(false);
  const codeArea = useRef<HTMLTextAreaElement>(null);
  const activeCode = useRef('');
  const doneButton = useRef<HTMLButtonElement>(null);
  const deviceButton = useRef<HTMLButtonElement>(null);
  const cancelRevoke = useRef<HTMLButtonElement>(null);
  const views = useRef<HTMLDivElement>(null);
  const needsInitialPair = useRef(true);

  useEffect(() => { if (paired && tab === 'add') doneButton.current?.focus(); }, [paired, tab]);
  useEffect(() => { if (manual && tab === 'add') codeArea.current?.focus(); }, [manual, tab]);
  useEffect(() => { if (remove) cancelRevoke.current?.focus(); }, [remove]);

  function apply(result: MobileStatus) {
    setStatus(result);
    if (result.environment && result.environment.state !== 'ready' && result.environment.state !== 'disabled') {
      setInvite(null);
      activeCode.current = '';
      setPaired(false);
      setManual(false);
      setCopyStatus('');
      return;
    }
    if (result.invite) {
      if (activeCode.current !== result.invite.code) setCopyStatus('');
      setPaired(false);
      setInvite(result.invite);
      activeCode.current = result.invite.code;
      return;
    }
    if (activeCode.current && result.pairingState === 'paired') {
      setFailure(null);
      setPaired(true);
      setPairedDevice(result.pairedDevice || null);
    } else if (result.pairingState !== 'paired') {
      setPaired(false);
      setPairedDevice(null);
    }
    setInvite(null);
    setManual(false);
    setCopyStatus('');
    activeCode.current = '';
  }

  async function operate(kind: Operation, action: () => Promise<void>) {
    if (pending.current) return;
    pending.current = true;
    revision.current++;
    setOperation(kind);
    setFailure(null);
    try { await action(); }
    catch (err) {
      if (alive.current) setFailure({ operation: kind, message: errorInfo(err).message || '操作失败，请重试' });
    } finally {
      pending.current = false;
      if (alive.current) setOperation(null);
    }
  }

  function generate(reuse = false) {
    return operate('pair', async () => {
      setCopyStatus('');
      setManual(false);
      const result = await post<MobileInvite>('pair', { reuse });
      if (!result?.code) throw new Error('服务端返回空配对信息，请重试');
      if (!alive.current) return;
      setReadError('');
      setFailedImage('');
      setNow(Date.now());
      setInvite(result);
      activeCode.current = result.code;
      setPaired(false);
      setStatus(current => ({
        ...current, connectionMode: result.connectionMode || current?.connectionMode,
        networkState: result.networkState, networkMessage: result.networkMessage,
        networkEnvironment: result.networkEnvironment || current?.networkEnvironment
      }));
    });
  }

  useEffect(() => {
    alive.current = true;
    const controller = new AbortController();
    let running = false;
    async function refresh() {
      if (running || pending.current || document.hidden) return;
      running = true;
      const token = revision.current;
      try {
        const result = await apiJson<MobileStatus>(endpoint, { signal: controller.signal }, 40000);
        if (controller.signal.aborted || token !== revision.current) return;
        if (!result) throw new Error('服务端返回空连接状态，请重试');
        apply(result);
        setReadError('');
        if (result.environment?.state === 'preparing' || result.environment?.state === 'failed') return;
        if (needsInitialPair.current) {
          needsInitialPair.current = false;
          if (!result.invite) {
            if (result.devices?.length) setTab('devices');
            else await generate(true);
          }
        }
      } catch (err) {
        if (!controller.signal.aborted && token === revision.current) setReadError(errorInfo(err).message || '读取连接状态失败');
      } finally { running = false; }
    }
    void refresh();
    const poll = setInterval(refresh, 3000);
    const tick = setInterval(() => setNow(Date.now()), 1000);
    return () => {
      alive.current = false;
      controller.abort();
      clearInterval(poll);
      clearInterval(tick);
    };
  }, []);

  const devices = status?.devices ?? [];
  const maxDevices = status?.maxDevices || 16;
  const full = devices.length >= maxDevices;
  const preparing = status?.environment?.state === 'preparing';
  const environmentError = status?.environment?.state === 'failed' ? status.environment.message || '环境补全未完成' : '';
  const relayConfigured = status?.connectionMode === 'relay-enabled';
  const busy = operation !== null;
  const seconds = Math.max(0, Math.ceil(((invite?.expiresAt || 0) - now) / 1000));
  const expired = status?.pairingState === 'expired' || (invite && !seconds);
  const connectionError = (failure?.operation !== 'revoke' && failure?.message) || environmentError || readError;

  function selectView(value: string, focus = false) {
    needsInitialPair.current = false;
    setTab(value);
    setRemove(null);
    setDeviceMessage('');
    if (value === 'add' && tab !== 'add' && !paired && !invite && !full && !preparing && !environmentError) void generate(true);
    if (focus) requestAnimationFrame(() => views.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus());
  }

  async function copy() {
    if (!invite?.code || invite.expiresAt <= Date.now()) return;
    const { code, expiresAt } = invite;
    let ok = false;
    try {
      if (window.pilotDesktop?.copyPairingCode) ok = (await window.pilotDesktop.copyPairingCode(code))?.ok === true;
    } catch { /* 桌面桥不可用时继续尝试浏览器剪贴板。 */ }
    if (!alive.current || activeCode.current !== code || expiresAt <= Date.now()) return;
    if (!ok) ok = await writeClipboard(code);
    if (!alive.current || activeCode.current !== code || expiresAt <= Date.now()) return;
    setCopyStatus(ok ? '已复制到这台电脑的剪贴板' : '自动复制不可用，请选中下方配对码手动复制。');
    if (!ok) {
      setManual(true);
      codeArea.current?.focus();
      codeArea.current?.select();
    }
  }

  function repair() {
    void operate('repair', async () => {
      const result = await post<MobileStatus>('repair', {});
      if (!result) throw new Error('服务端返回空连接状态，请重试');
      if (!alive.current) return;
      setReadError('');
      apply(result);
      needsInitialPair.current = true;
    });
  }

  function revoke(device: MobileDevice) {
    void operate('revoke', async () => {
      await post('revoke', { id: device.id });
      if (!alive.current) return;
      setStatus(current => current && { ...current, devices: current.devices?.filter(item => item.id !== device.id) });
      setRemove(null);
      setDeviceMessage(`已撤销“${device.name}”的授权`);
      views.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus();
    });
  }

  return (
    <div className="pilot-mobile-content">
      <p className="pilot-mobile-meta">授权手机管理这台电脑上的项目和任务。任务继续在电脑上执行。</p>
      <SegmentedControl ref={views} className="pilot-mobile-views" label="手机连接视图" value={tab} onChange={selectView} isDisabled={busy}>
        <SegmentedControlItem value="add" label="添加设备" />
        <SegmentedControlItem value="devices" label={`已配对设备${status?.devices ? ` · ${devices.length}` : ''}`} />
      </SegmentedControl>
      {connectionError && <div className="pilot-mobile-feedback">
        <Banner status="error" role="alert" title={connectionError} />
        <div className="pilot-mobile-actions">
          <Button label="重新生成配对码" isDisabled={busy || preparing || full || !!environmentError} onClick={() => { void generate(true); }} />
          <Button label="检查并修复环境" variant="ghost" isDisabled={busy || preparing} onClick={repair} />
        </div>
      </div>}
      {preparing && <p role="status" className="pilot-mobile-meta">{status?.environment?.message || '正在检查与补全连接环境…'}</p>}
      {tab === 'add' ? (
        <section aria-label="添加设备">
          {paired ? (
            <div className="pilot-mobile-summary">
              <h3 role="status">配对成功</h3>
              <p>{pairedDevice?.name ? `“${pairedDevice.name}”已获得授权。` : '手机已获得授权。'}现在可以在手机上管理项目和任务。</p>
              <p className="pilot-mobile-meta">关闭窗口后手机仍可连接。最多可配对 {maxDevices} 台设备。</p>
              <div className="pilot-mobile-actions">
                <Button label="完成" variant="primary" ref={doneButton} onClick={onDone} />
                <Button label={operation === 'pair' ? '正在生成…' : '添加另一台设备'} isDisabled={busy || full} onClick={() => { void generate(false); }} />
                <Button label="查看已配对设备" variant="ghost" onClick={() => selectView('devices', true)} />
              </div>
              {full && <p className="pilot-mobile-meta">已达到 {maxDevices} 台上限，可以在设备列表中撤销不再使用的设备。</p>}
            </div>
          ) : full ? (
            <div className="pilot-mobile-summary">
              <h3>已配对 {maxDevices} 台设备</h3>
              <p>请先撤销不再使用的设备，再添加新的手机。</p>
              <div className="pilot-mobile-actions">
                <Button label="管理已配对设备" variant="primary" onClick={() => selectView('devices', true)} />
                <Button label="完成" onClick={onDone} />
              </div>
            </div>
          ) : (
            <div className="pilot-mobile-pair">
              <div className="pilot-mobile-qr">
                {invite && seconds > 0 && invite.image && invite.image !== failedImage
                  ? <img src={invite.image} onError={() => setFailedImage(invite.image)} alt="用 Pilot 手机 App 扫描此配对二维码" />
                  : <div className="pilot-mobile-qr-placeholder" role="status">
                    {preparing ? '正在检查电脑端组件…' : operation === 'pair' ? '正在生成本机配对码…'
                      : invite && seconds > 0 ? '二维码无法显示，可复制配对码'
                        : connectionError ? '配对码暂不可用' : !status ? '正在读取配对信息…' : expired ? '配对码已过期' : '还未生成配对码'}
                  </div>}
                {seconds > 0 && <p className="pilot-mobile-meta" role="timer">{Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')} 后过期 · 仅限一次配对</p>}
              </div>
              <div className="pilot-mobile-pair-controls">
                <h3>用 Pilot App 扫码</h3>
                <p>打开手机上的 Pilot，选择“扫码连接电脑”。配对期间请保持电脑在线。</p>
                <div className="pilot-mobile-actions">
                  {invite && seconds > 0 && <Button label="复制配对码" variant="primary" isDisabled={busy || preparing} onClick={() => { void copy(); }} />}
                  {!connectionError && <Button label={preparing ? '正在准备环境…' : operation === 'pair' ? '正在生成…' : invite || expired ? '重新生成' : '生成配对码'}
                    variant={invite && seconds > 0 ? 'secondary' : 'primary'}
                    isDisabled={busy || preparing} onClick={() => { void generate(false); }} />}
                  {invite && seconds > 0 && <Button label="手动复制配对码" variant="ghost" aria-expanded={manual} isDisabled={busy}
                    aria-controls={manual ? 'mobile-pair-code' : undefined} onClick={() => setManual(value => !value)} />}
                </div>
                <div className="pilot-mobile-meta" role="status">{copyStatus}</div>
                {manual && invite && seconds > 0 && <div className="pilot-mobile-pair-controls" id="mobile-pair-code">
                  <TextArea ref={codeArea} isReadOnly rows={3} width="100%" label="完整配对码" value={invite.code} onFocus={event => event.currentTarget.select()} />
                  <div className="pilot-mobile-actions"><Button label="全选配对码" onClick={() => {
                    codeArea.current?.focus();
                    codeArea.current?.select();
                  }} /></div>
                </div>}
                {status?.connectionMode && <p className="pilot-mobile-meta">{status.connectionMode === 'automatic'
                  ? '自动连接：已启用公共发现与中继，尽力直连，必要时经中继。'
                  : relayConfigured ? '后端已配置中继。连接将尝试直连，必要时使用已配置的中继。'
                    : '未配置中继，仅尝试 P2P 直连。二维码在本机生成，等待手机扫码；两端需有可互达的网络地址。'}</p>}
                {status?.networkMessage && <p className="pilot-mobile-meta" role="status" data-network-state={status.networkState}>{status.networkMessage}</p>}
                {status?.networkEnvironment && <details className="pilot-mobile-network">
                  <summary>网络与代理说明</summary>
                  <p className="pilot-mobile-meta">{status.networkEnvironment.message}</p>
                  <p className="pilot-mobile-meta">网线与 Wi-Fi 无需分别配置。HTTP/SOCKS 代理和 TUN 属于不同路径。Pilot 会沿用电脑的系统代理、路由和防火墙设置。</p>
                </details>}
              </div>
            </div>
          )}
        </section>
      ) : (
        <section className="pilot-mobile-devices" aria-label="已配对设备" aria-busy={!status && !readError || preparing}>
          {!status || preparing || environmentError ? (!connectionError && <p role="status">正在读取设备，环境准备完成后会自动更新…</p>)
            : devices.length === 0 ? <EmptyState title="还没有配对设备" headingLevel={3} description="添加手机后会显示在这里。" isCompact />
              : devices.map(device => (
                <div className="pilot-mobile-device" key={device.id}>
                  <div className="pilot-mobile-device-name">
                    <strong>{device.name}</strong>
                    <Badge label={device.online ? '在线' : '离线'} variant={device.online ? 'success' : 'neutral'} />
                    <p className="pilot-mobile-meta">配对于 <time dateTime={new Date(device.pairedAt).toISOString()}>{new Date(device.pairedAt).toLocaleDateString()}</time></p>
                  </div>
                  <Button ref={remove?.id === device.id ? deviceButton : undefined} label="撤销授权" variant="ghost" isDisabled={busy}
                    onClick={event => { deviceButton.current = event.currentTarget; setFailure(null); setRemove(device); }} />
                  {remove?.id === device.id && <div className="pilot-mobile-revoke">
                    <Banner status="warning" title={`撤销“${device.name}”的授权？`} description="已有连接会立即断开，电脑上的任务继续运行。" />
                    {failure?.operation === 'revoke' && <Banner status="error" role="alert" title={`撤销授权失败：${failure.message}`} />}
                    <div className="pilot-mobile-actions">
                      <Button label="取消" ref={cancelRevoke} isDisabled={busy} onClick={() => {
                        deviceButton.current?.focus();
                        setRemove(null);
                        setFailure(null);
                      }} />
                      <Button label="确认撤销" variant="destructive" isDisabled={busy} onClick={() => revoke(device)} />
                    </div>
                  </div>}
                </div>
              ))}
          <div className="pilot-mobile-device-footer">
            <p className="pilot-mobile-meta">最多配对 {maxDevices} 台设备，可同时连接。每台新设备需单独扫码授权。</p>
            <Button label="完成" onClick={onDone} />
          </div>
          <div className="pilot-mobile-meta" role="status">{deviceMessage}</div>
        </section>
      )}
    </div>
  );
}
