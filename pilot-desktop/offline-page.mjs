const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));

export function offlinePage(address) {
  let target;
  try { target = new URL(address); } catch { target = new URL('http://127.0.0.1:9999'); }
  if (!['http:', 'https:'].includes(target.protocol)) target = new URL('http://127.0.0.1:9999');
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Pilot · 连接服务</title>
<style>
:root{color-scheme:light dark;font:14px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#e8e8e8;background:#1c1c1c}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;padding:32px}main{width:100%;max-width:460px}.brand{display:flex;align-items:center;gap:10px;margin-bottom:32px;font-weight:600}.mark{display:grid;place-items:center;background:#e8e8e8;color:#1c1c1c;width:30px;height:30px;border-radius:8px}h1{font-size:24px;font-weight:600;line-height:1.4;margin:0 0 12px}p{color:#aaa;margin:0 0 24px}code{display:block;font-size:12px;overflow-wrap:anywhere;margin-top:8px}a{display:inline-block;background:#e8e8e8;color:#1c1c1c;border-radius:6px;padding:8px 18px;text-decoration:none;font-weight:600}a:hover{background:#fff}a:focus-visible{outline:2px solid #72b8ff;outline-offset:4px}@media(prefers-color-scheme:light){:root{color:#252525;background:#f7f7f7}p{color:#626262}a,.mark{background:#252525;color:white}a:hover{background:#444}}
</style></head><body><main><div class="brand"><span class="mark" aria-hidden="true">P</span>Pilot</div><h1>暂时无法连接服务</h1><p>确认 Pilot 服务已经启动，然后重试。窗口可以保持打开。<code>${escapeHtml(target.origin)}</code></p><a href="${escapeHtml(target.href)}">重新连接</a></main></body></html>`;
}
