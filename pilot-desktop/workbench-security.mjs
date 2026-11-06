/** 工作台与普通网页分开：窗口、主框架和 Chromium 实际 origin 必须同时可信。 */
export function workbenchOrigin(value) {
  const url = new URL(value);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('Pilot 工作台地址必须是无用户名密码的 HTTP 或 HTTPS 地址');
  }
  return url.origin;
}

export function isWorkbenchUrl(value, appUrl) {
  try { return workbenchOrigin(value) === workbenchOrigin(appUrl); }
  catch { return false; }
}

export function trustedWorkbench(event, window, appUrl) {
  try {
    if (!window || window.isDestroyed()) return false;
    const contents = window.webContents;
    const frame = event?.senderFrame;
    return Boolean(contents && !contents.isDestroyed() && event.sender === contents && frame && !frame.detached
      && frame === contents.mainFrame && frame.origin === workbenchOrigin(appUrl)
      && isWorkbenchUrl(contents.getURL(), appUrl));
  } catch { return false; }
}

const denied = () => ({ ok: false, error: '此操作只能从 Pilot 工作台主页面发起', code: 'UNTRUSTED_WORKBENCH' });

/** 全部接收和推送复用此入口；异步结果返回前重新校验，防止页面在等待期间离开。 */
export function workbenchIpc(ipcMain, getWindow, appUrl) {
  const trusted = event => trustedWorkbench(event, getWindow(), appUrl);
  const current = () => {
    try {
      const contents = getWindow()?.webContents;
      return trusted({ sender: contents, senderFrame: contents?.mainFrame });
    } catch { return false; }
  };
  return {
    trusted,
    current,
    handle(channel, handler) {
      ipcMain.handle(channel, async (event, ...args) => {
        if (!trusted(event)) return denied();
        try {
          const value = await handler(event, ...args);
          return trusted(event) ? value : denied();
        } catch (error) {
          if (!trusted(event)) return denied();
          throw error;
        }
      });
    },
    on(channel, handler) {
      ipcMain.on(channel, (event, ...args) => {
        if (trusted(event)) handler(event, ...args);
      });
    },
    send(channel, ...args) {
      try {
        const window = getWindow();
        const sender = window?.webContents;
        const senderFrame = sender?.mainFrame;
        if (!trustedWorkbench({sender, senderFrame}, window, appUrl)) return false;
        // 绑定刚刚校验过的框架，不能在导航切换期间转发给新的主文档。
        senderFrame.send(channel, ...args);
        return true;
      } catch { return false; }
    }
  };
}

/** 只限制工作台；普通浏览器页依旧可以访问 HTTP(S) 网站。 */
export function installWorkbenchNavigation(contents, appUrl, { openPage, notify } = {}) {
  const guard = (event, legacyUrl, _inPlace, legacyMainFrame, redirect = false) => {
    const url = event.url ?? legacyUrl;
    const mainFrame = event.isMainFrame ?? legacyMainFrame ?? true;
    if (isWorkbenchUrl(url, appUrl) || (!mainFrame && ['about:blank', 'about:srcdoc'].includes(url))) return;
    event.preventDefault();
    // 子框架和重定向不自动创建标签，避免恶意页面通过循环请求制造窗口。
    if (mainFrame && !redirect && ['http:', 'https:'].includes(safeProtocol(url))) openPage?.(url);
    else if (mainFrame) notify?.('已阻止工作台离开可信地址；请直接打开目标网页链接');
  };
  // Electron 会附带旧版位置参数；不能把 frameProcessId 等额外参数误作策略标志。
  contents.on('will-frame-navigate', event => guard(event));
  contents.on('will-navigate', (event, url) => guard(event, url));
  contents.on('will-redirect', (event, url, inPlace, mainFrame) => guard(event, url, inPlace, mainFrame, true));
  contents.setWindowOpenHandler(({ url }) => {
    if (['http:', 'https:'].includes(safeProtocol(url))) {
      openPage?.(url);
    } else {
      notify?.('该链接类型不受支持，请使用 HTTP 或 HTTPS 网页地址');
    }
    return { action: 'deny' };
  });
}

function safeProtocol(value) {
  try {
    const url = new URL(value);
    return url.username || url.password ? '' : url.protocol;
  } catch { return ''; }
}
