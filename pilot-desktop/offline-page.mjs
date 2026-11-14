import { configuredWorkbenchUrl, workbenchAddress } from './workbench-security.mjs';

const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));

export function offlinePage(address) {
  let target;
  try { target = workbenchAddress(address); } catch { target = workbenchAddress(configuredWorkbenchUrl({})); }
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Pilot · 连接服务</title>
<style>
:root {
  color-scheme: light dark;
  --page: #fafafa;
  --ink: #202020;
  --muted: #5c5c5c;
  font: 14px/1.7 system-ui, sans-serif;
  color: var(--ink);
  background: var(--page);
}
@media (prefers-color-scheme: dark) {
  :root { --page: #171717; --ink: #ededed; --muted: #b5b5b5; }
}
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; min-height: 100dvh; display: grid; place-items: center; padding: 24px; }
main { width: 100%; max-width: 400px; }
.brand { color: var(--muted); margin-bottom: 28px; }
h1 { font-size: 26px; font-weight: 600; line-height: 1.4; margin: 0 0 12px; }
p { color: var(--muted); margin: 0 0 20px; }
code { display: block; font-size: 13px; overflow-wrap: anywhere; margin-top: 8px; }
a {
  display: inline-flex; min-height: 44px; align-items: center; justify-content: center;
  background: var(--ink); color: var(--page); border-radius: 8px; padding: 8px 18px;
  text-decoration: none; font-weight: 600;
}
a:hover { opacity: .87; }
a:focus-visible { outline: 2px solid var(--ink); outline-offset: 3px; }
</style></head><body><main><div class="brand">Pilot · 桌面工作台</div><h1>暂时无法连接服务</h1><p>启动 Pilot 服务后，重新连接。<code>${escapeHtml(target.origin)}</code></p><a href="${escapeHtml(target.href)}">重新连接</a></main></body></html>`;
}
