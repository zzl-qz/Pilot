const token = location.hash.slice(1);
history.replaceState(null, '', '/');
const $ = id => document.getElementById(id);
let code = '', deadline = 0;
async function api(path, method = 'GET', body) {
  const response = await fetch(path, { method, headers: { 'X-Pilot-Pairing': token, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(25000) });
  if (!response.ok) {
    const details = await response.json().catch(() => ({}));
    throw new Error(response.status === 403 ? '页面授权已失效，请从电脑重新打开连接页面。' : details.error || '无法生成本机配对码，请重试。');
  }
  return response.json();
}
async function devices() {
  const rows = await api('/api/devices');
  $('devices').replaceChildren();
  if (!rows.length) { $('devices').textContent = '还没有配对的手机'; return; }
  for (const row of rows) {
    const line = document.createElement('div'), name = document.createElement('p'), remove = document.createElement('button');
    name.textContent = row.name; remove.textContent = '移除'; remove.setAttribute('aria-label', `移除 ${row.name}`);
    remove.onclick = async () => {
      if (!confirm(`移除“${row.name}”后，它将立即失去连接权限。`)) return;
      remove.disabled = true;
      try { await api('/api/revoke', 'POST', { id: row.id }); await devices(); } catch (error) { $('status').textContent = error.message; remove.disabled = false; }
    };
    line.append(name, remove); $('devices').append(line);
  }
}
$('create').onclick = async () => {
  $('create').disabled = true; $('status').textContent = '正在生成这台电脑的配对码…'; $('invite').hidden = true;
  try {
    const result = await api('/api/pair', 'POST'); code = result.code; deadline = Date.now() + result.expiresIn * 1000;
    $('qr').src = result.image; $('invite').hidden = false; $('status').textContent = '配对码已在本机生成，等待手机扫码。'; $('create').textContent = '重新生成';
    $('mode').textContent = result.connectionMode === 'relay-enabled'
      ? '后端已配置中继，必要时使用已配置的中继。'
      : '未配置中继，仅尝试 P2P 直连；两端需有可互达的网络地址。';
  } catch (error) { $('status').textContent = error.message; }
  finally { $('create').disabled = false; }
};
$('copy').onclick = async () => {
  try { await navigator.clipboard.writeText(code); $('status').textContent = '已复制，可在手机 App 中粘贴配对码。'; }
  catch {
    let field = document.getElementById('manual-code');
    if (!field) { field = document.createElement('textarea'); field.id = 'manual-code'; field.readOnly = true; field.setAttribute('aria-label', '完整配对码'); $('invite').append(field); }
    field.value = code; field.focus(); field.select();
    $('status').textContent = '自动复制不可用。已选中完整配对码，请使用系统复制菜单，或扫描二维码。';
  }
};
setInterval(() => {
  const seconds = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
  $('expiry').textContent = seconds ? `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒后过期` : '配对码已过期，请重新生成';
  if (!seconds) { $('invite').hidden = true; code = ''; }
}, 1000);
devices().catch(error => { $('devices').textContent = error.message; });
setInterval(() => { if (!document.hidden) devices().catch(() => {}); }, 5000);
