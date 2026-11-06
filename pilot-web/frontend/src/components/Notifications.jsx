import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { useStore } from '../store.js';
import './schedules.css';
import { DialogHeader } from './DialogHeader.jsx';
const statuses = { PENDING: '待投递', SENDING: '投递中', RETRY: '等待重试', FAILED: '投递失败', DELIVERED: '已投递', CANCELLED: '已取消' };
export async function enableDesktopNotifications() {
  if (typeof Notification === 'undefined') throw new Error('此环境不支持系统通知，请使用收件箱或 Webhook。');
  const permission = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('系统通知未获授权，请在浏览器或系统设置中允许；仍可使用收件箱或 Webhook。');
}
export function setLocalNotifications(enabled) {
  useStore.setState({ notifyEnabled: enabled });
  try { localStorage.setItem('pilot.notify', enabled ? '1' : '0'); } catch { useStore.getState().showNotice('通知偏好无法保存在此设备，下次打开可能需要重新启用。'); }
}
export default function Notifications() {
  const open = useStore(s => s.ui.notificationsOpen), enabled = useStore(s => s.notifyEnabled);
  useEffect(() => {
    if (!enabled) return;
    let alive = true, running = false;
    const poll = async () => {
      if (!alive || running || typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
      running = true;
      try {
        const d = await api.claimDelivery(); if (!d?.id) return;
        if (!alive) { await api.deliveryReceipt(d.id, { lease: d.lease, success: false, error: '设备通知已关闭，等待其他设备处理' }); return; }
        let timer;
        await new Promise(resolve => {
          let done = false;
          const finish = async (success, error = '') => { if (done) return; done = true; clearTimeout(timer); try { await api.deliveryReceipt(d.id, { lease: d.lease, success, error }); } catch { /* 服务端租约到期重试，稳定 tag 合并通知 */ } resolve(); };
          try {
            const notice = new Notification(`Pilot · ${d.title}`, { body: d.summary, tag: d.id });
            notice.onshow = () => finish(true);
            notice.onerror = () => finish(false, '系统未能展示通知，请检查通知权限');
            notice.onclick = () => { window.focus(); useStore.getState().setUi({ resultsOpen: true, resultTarget: d.resultId }); notice.close(); };
            timer = setTimeout(() => finish(false, '系统未确认展示通知，稍后重试'), 10000);
          } catch { finish(false, '创建系统通知失败，请检查权限'); }
        });
      } catch { /* 连接失败保留服务端待投递记录，面板展示状态 */ }
      finally { running = false; }
    };
    poll(); const timer = setInterval(poll, 5000); return () => { alive = false; clearInterval(timer); };
  }, [enabled]);
  return open ? <NotificationDialog /> : null;
}
function NotificationDialog() {
  const ref = useRef(null), alive = useRef(true);
  const [settings, setSettings] = useState(null), [desktop, setDesktop] = useState(false), [webhook, setWebhook] = useState(''), [rows, setRows] = useState([]), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const close = () => useStore.getState().setUi({ notificationsOpen: false });
  async function refresh() { try { const data = await api.deliveries(); if (alive.current) setRows(Array.isArray(data) ? data : []); } catch (e) { if (alive.current) setError(e.message); } }
  useEffect(() => {
    const previous = document.activeElement; alive.current = true; ref.current.showModal();
    api.deliverySettings().then(data => { if (alive.current) { setSettings(data); setDesktop(data.desktop); setWebhook(data.webhookUrl); } }).catch(e => { if (alive.current) setError(e.message); });
    refresh(); const timer = setInterval(refresh, 10000); return () => { alive.current = false; clearInterval(timer); previous?.focus?.(); };
  }, []);
  async function save(e) {
    e.preventDefault(); setBusy(true); setError('');
    try { if (desktop) await enableDesktopNotifications(); const data = await api.saveDeliverySettings({ version: settings.version, desktop, webhookUrl: webhook }); setSettings(data); setLocalNotifications(desktop); useStore.getState().showNotice('通知设置已保存，只投递启用后的新结果。'); }
    catch (e) { setError(e.message); } finally { setBusy(false); }
  }
  return <dialog ref={ref} className="schedule-dialog" aria-labelledby="notifications-title" onCancel={e => { e.preventDefault(); close(); }} onKeyDown={e => e.stopPropagation()}>
    <DialogHeader id="notifications-title" title="通知与投递" subtitle="Pilot / 结果投递" closeLabel="关闭通知设置" onClose={close} />
    <div className="schedule-dialog-body">
    <p className="schedule-runtime">任务完成或需要处理时发送。投递失败会单独重试，最多 6 次；不会重新执行任务。</p>
    {error && <div className="schedule-error" role="alert">{error} <button onClick={() => { setError(''); refresh(); api.deliverySettings().then(data => { if (alive.current) { setSettings(data); setDesktop(data.desktop); setWebhook(data.webhookUrl); } }).catch(e => { if (alive.current) setError(e.message); }); }}>重新加载</button></div>}
    <form onSubmit={save}><label className="schedule-checkbox"><input type="checkbox" checked={desktop} onChange={e => setDesktop(e.target.checked)} />桌面通知（保存时授权当前设备）</label><p className="schedule-runtime">需要至少一个已授权且开启通知的 Pilot 页面或客户端在线。关闭页面期间的结果会保留，重新连接后投递。</p>
      <label>Webhook 地址<input type="url" aria-label="Webhook 地址" value={webhook} onChange={e => setWebhook(e.target.value)} placeholder="https://你的服务/通知入口" maxLength={2048} /></label><p className="schedule-runtime">留空关闭 Webhook。Pilot 服务在线即可投递，不依赖页面；发送任务标题、结果摘要与结果 ID。地址保存在本机服务中。</p><details className="notification-integration"><summary>Webhook 接入说明</summary><p>接收 JSON：id、resultId、title、summary、state、createdAt。返回 2xx 表示接收成功；超时或非 2xx 会重试，不跟随重定向。使用请求头 Idempotency-Key 去重，避免超时重试产生重复提醒。</p></details><button className="btn btn-primary" disabled={busy || !settings}>保存通知设置</button></form>
    <h2 className="delivery-heading">最近投递</h2><button className="btn" onClick={refresh}>刷新投递记录</button>{!rows.length && <p>暂无投递。启用后新产生的结果会显示在这里。</p>}
    {rows.map(row => <article className="recurring-row" key={row.id}><div><h3>{row.title}</h3><p>{row.channel === 'DESKTOP' ? '桌面' : 'Webhook'} · {statuses[row.state]} · 已尝试 {row.attempts} 次</p>{row.error && <p>{row.error}</p>}{row.state === 'RETRY' && <small>下次重试：{new Date(row.nextAt).toLocaleString()}</small>}</div><div className="schedule-actions"><button className="btn" onClick={() => { close(); useStore.getState().setUi({ resultsOpen: true, resultTarget: row.resultId }); }}>查看结果</button>{['FAILED','RETRY'].includes(row.state) && <button className="btn" disabled={busy} onClick={async () => { setBusy(true); setError(''); try { await api.retryDelivery(row.id, row.version); await refresh(); } catch (e) { setError(e.message); } finally { setBusy(false); } }}>重试通知</button>}</div></article>)}
    </div>
  </dialog>;
}
