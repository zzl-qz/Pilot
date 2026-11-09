import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Badge } from '@astryxdesign/core/Badge';
import { Banner } from '@astryxdesign/core/Banner';
import { Button } from '@astryxdesign/core/Button';
import { EmptyState } from '@astryxdesign/core/EmptyState';
import { Switch } from '@astryxdesign/core/Switch';
import { TextInput } from '@astryxdesign/core/TextInput';
import { api } from '../api.js';
import { errorInfo } from '../lib/errors.js';
import { useStore } from '../store.js';
import type { DeliveryServiceDelivery, DeliveryServiceSettings } from '../types/server.js';
import { AutomationDialog } from './AutomationDialog.jsx';
import { useAutomationList } from './useAutomationList.js';

const statuses: Record<string, string> = {
  PENDING: '待投递', SENDING: '投递中', RETRY: '等待重试', FAILED: '投递失败', DELIVERED: '已投递', CANCELLED: '已取消'
};

export async function enableDesktopNotifications() {
  if (typeof Notification !== 'function') throw new Error('此环境不支持系统通知，请使用收件箱或 Webhook。');
  if (Notification.permission === 'granted') return;
  if (typeof Notification.requestPermission !== 'function') throw new Error('此环境无法申请通知权限，请使用收件箱或 Webhook。');
  try { if (await Notification.requestPermission() === 'granted') return; }
  catch { /* 无法调起浏览器授权时，继续提供可用渠道。 */ }
  throw new Error('系统通知未获授权。可在浏览器或系统设置中允许，也可使用收件箱或 Webhook。');
}

export function setLocalNotifications(enabled: boolean) {
  useStore.setState({ notifyEnabled: enabled });
  try { localStorage.setItem('pilot.notify', enabled ? '1' : '0'); }
  catch { useStore.getState().showNotice('通知偏好无法保存在此设备，下次打开可能需要重新启用。'); }
}

export default function Notifications() {
  const open = useStore(s => s.ui.notificationsOpen);
  const enabled = useStore(s => s.notifyEnabled);
  useEffect(() => {
    if (!enabled) return;
    let alive = true, running = false;
    const poll = async () => {
      if (!alive || running || typeof Notification !== 'function' || Notification.permission !== 'granted') return;
      running = true;
      try {
        const delivery = await api.claimDelivery();
        if (!delivery?.id) return;
        if (!alive) {
          await api.deliveryReceipt(delivery.id, { lease: delivery.lease, success: false, error: '设备通知已关闭，等待其他设备处理' });
          return;
        }
        let timer: ReturnType<typeof setTimeout> | undefined;
        await new Promise<void>(resolve => {
          let done = false;
          const finish = async (success: boolean, error = '') => {
            if (done) return;
            done = true;
            clearTimeout(timer);
            try { await api.deliveryReceipt(delivery.id, { lease: delivery.lease, success, error }); }
            catch { /* 服务端租约到期重试，稳定 tag 合并通知 */ }
            resolve();
          };
          try {
            const notice = new Notification(`Pilot · ${delivery.title}`, { body: delivery.summary, tag: delivery.id });
            notice.onshow = () => finish(true);
            notice.onerror = () => finish(false, '系统未能展示通知，请检查通知权限');
            notice.onclick = () => {
              window.focus();
              useStore.getState().setUi({ resultsOpen: true, resultTarget: delivery.resultId });
              notice.close();
            };
            timer = setTimeout(() => finish(false, '系统未确认展示通知，稍后重试'), 10000);
          } catch { void finish(false, '创建系统通知失败，请检查权限'); }
        });
      } catch { /* 连接失败保留服务端待投递记录，面板展示状态 */ }
      finally { running = false; }
    };
    void poll();
    const timer = setInterval(poll, 5000);
    return () => { alive = false; clearInterval(timer); };
  }, [enabled]);
  return open ? <NotificationDialog /> : null;
}

function NotificationDialog() {
  const deliveries = useAutomationList(api.deliveries, 10000);
  const [settings, setSettings] = useState<DeliveryServiceSettings | null>(null);
  const [desktop, setDesktop] = useState(false);
  const [webhook, setWebhook] = useState('');
  const [settingsLoading, setSettingsLoading] = useState(true);
  const [settingsError, setSettingsError] = useState('');
  const [saveError, setSaveError] = useState<{ message: string; conflict: boolean } | null>(null);
  const [retryError, setRetryError] = useState<{ id: string; message: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const alive = useRef(true);
  const pending = useRef(false);

  const close = () => useStore.getState().setUi({ notificationsOpen: false });
  const loadSettings = useCallback(async () => {
    setSettingsLoading(true);
    try {
      const data = await api.deliverySettings();
      if (!data) throw new Error('服务端返回空通知设置，请重新加载');
      if (!alive.current) return;
      setSettings(data);
      setDesktop(data.desktop);
      setWebhook(data.webhookUrl || '');
      setSettingsError('');
      setSaveError(null);
      setSaved(false);
    } catch (err) {
      if (alive.current) setSettingsError(errorInfo(err).message || '请稍后重试');
    } finally {
      if (alive.current) setSettingsLoading(false);
    }
  }, []);

  useEffect(() => {
    alive.current = true;
    void loadSettings();
    return () => { alive.current = false; };
  }, [loadSettings]);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!settings || settingsLoading || pending.current) return;
    pending.current = true;
    setBusy(true);
    setSaveError(null);
    setSaved(false);
    try {
      if (desktop) await enableDesktopNotifications();
      const data = await api.saveDeliverySettings({ version: settings.version, desktop, webhookUrl: webhook.trim() });
      if (!data) throw new Error('未收到保存回执，请重新加载设置核对');
      setLocalNotifications(data.desktop);
      if (!alive.current) return;
      setSettings(data);
      setDesktop(data.desktop);
      setWebhook(data.webhookUrl || '');
      setSaved(true);
    } catch (err) {
      const { message, status } = errorInfo(err);
      if (alive.current) setSaveError({ message: message || '请重试', conflict: status === 409 });
    } finally {
      pending.current = false;
      if (alive.current) setBusy(false);
    }
  }

  async function retry(row: DeliveryServiceDelivery) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setRetryError(null);
    try {
      await api.retryDelivery(row.id, row.version);
      if (!alive.current) return;
      document.getElementById(`delivery-${row.id}`)?.focus({ preventScroll: true });
      await deliveries.refresh();
    } catch (err) {
      if (alive.current) setRetryError({ id: row.id, message: errorInfo(err).message || '请重试' });
    } finally {
      pending.current = false;
      if (alive.current) setBusy(false);
    }
  }

  const settingsDisabled = busy || settingsLoading || !settings;
  return (
    <AutomationDialog titleId="notifications-title" title="通知与投递"
      description="任务完成或需要处理时发送结果摘要。" onClose={close}>
      <div className="notifications-layout">
        <section aria-labelledby="notification-channels-title">
          <h2 id="notification-channels-title">投递渠道</h2>
          <p className="automation-meta automation-section-note">启用后，只投递此后产生的新结果。</p>
          <form className="notifications-form" onSubmit={save} aria-busy={settingsLoading || busy}>
            {settingsLoading && <p className="automation-meta" role="status">正在读取通知设置…</p>}
            {settingsError && <Banner status="error" role="alert" title={`读取通知设置失败：${settingsError}`}
              endContent={<Button label="重新加载" isDisabled={busy || settingsLoading} onClick={() => { void loadSettings(); }} />} />}
            <Switch label="桌面通知" labelPosition="start" labelSpacing="spread" width="100%"
              description="保存时授权当前设备。结果会保留，至少有一台已授权且开启通知的 Pilot 页面或客户端在线时投递。"
              value={desktop} isDisabled={settingsDisabled} onChange={value => { setDesktop(value); setSaved(false); }} />
            <TextInput controlClassName="automation-input" label="Webhook 地址" type="url" size="lg" width="100%" value={webhook} isDisabled={settingsDisabled}
              description="留空关闭。地址保存在 Pilot 服务中，服务在线即可投递。"
              placeholder="https://你的服务/通知入口" autoCapitalize="none" hasSpellCheck={false}
              onChange={value => { setWebhook(value.slice(0, 2048)); setSaved(false); }} />
            <details className="automation-disclosure">
              <summary>Webhook 接入说明</summary>
              <p>请求包含 id、resultId、title、summary、state 和 createdAt。返回 2xx 表示成功。超时或非 2xx 会重试，重定向响应视为失败。</p>
              <p>使用请求头 <code>Idempotency-Key</code> 去重，避免超时重试产生重复提醒。</p>
            </details>
            {saveError && <Banner status="error" role="alert" title={`保存通知设置失败：${saveError.message}`}
              description={saveError.conflict ? '重新加载后将显示服务端配置，请核对后再编辑。' : undefined}
              endContent={saveError.conflict ? <Button label="重新加载设置" isDisabled={settingsDisabled} onClick={() => { void loadSettings(); }} /> : undefined} />}
            <div className="automation-actions">
              <Button label="保存通知设置" type="submit" variant="primary" isDisabled={settingsDisabled} />
              <span className="automation-meta" role="status">{saved && '通知设置已保存'}</span>
            </div>
          </form>
        </section>
        <section className="notification-deliveries" aria-labelledby="notification-deliveries-title" aria-busy={deliveries.loading}>
          <div className="automation-toolbar">
            <h2 id="notification-deliveries-title">最近投递</h2>
            <Button label="刷新投递记录" variant="ghost" isDisabled={deliveries.loading || busy} onClick={() => { void deliveries.refresh(); }} />
          </div>
          <p className="automation-meta automation-section-note">自动重试最多 6 次。重试只发送通知，原任务执行记录保留。</p>
          {deliveries.loading && <p className="automation-meta" role="status">{deliveries.rows ? '正在刷新投递记录…' : '正在读取投递记录…'}</p>}
          {deliveries.error && <Banner className="automation-message" status="error" role="alert" title={`读取投递记录失败：${deliveries.error}`}
            description={deliveries.rows ? '当前显示上次成功读取的记录。' : undefined}
            endContent={<Button label="重新加载记录" isDisabled={deliveries.loading || busy} onClick={() => { void deliveries.refresh(); }} />} />}
          {deliveries.rows?.length === 0 && !deliveries.loading && !deliveries.error && <EmptyState title="暂无投递记录" isCompact
            headingLevel={3} description="启用渠道后，新产生的结果会显示在这里。" />}
          {deliveries.rows?.map(row => (
            <article className="automation-record" key={row.id}>
              <div className="automation-record-content">
                <div className="automation-record-heading">
                  <h3 id={`delivery-${row.id}`} tabIndex={-1}>{row.title}</h3>
                  <Badge label={statuses[row.state] || row.state} variant={row.state === 'FAILED' ? 'error' : row.state === 'RETRY' ? 'warning' : 'neutral'} />
                </div>
                <p className="automation-meta">{row.channel === 'DESKTOP' ? '桌面' : 'Webhook'} · 已尝试 {row.attempts} 次</p>
                {row.error && <p className="automation-meta">{row.error}</p>}
                {row.state === 'RETRY' && row.nextAt && <p className="automation-meta">下次重试 <time dateTime={row.nextAt}>{new Date(row.nextAt).toLocaleString()}</time></p>}
              </div>
              <div className="automation-actions">
                <Button label="查看结果" variant="ghost" isDisabled={busy} onClick={() => useStore.getState().setUi({
                  notificationsOpen: false, resultsOpen: true, resultTarget: row.resultId
                })} />
                {['FAILED', 'RETRY'].includes(row.state) && <Button label="重试通知" isDisabled={busy || !!deliveries.error}
                  onClick={() => { void retry(row); }} />}
              </div>
              {retryError?.id === row.id && <Banner className="automation-record-feedback" status="error" role="alert"
                title={`重试失败：${retryError.message}`} />}
            </article>
          ))}
        </section>
      </div>
    </AutomationDialog>
  );
}
