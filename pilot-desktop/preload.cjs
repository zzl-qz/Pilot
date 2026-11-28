const { contextBridge, ipcRenderer } = require('electron');

const expectedOrigin = process.argv.find(value => value.startsWith('--pilot-workbench-origin='))?.slice('--pilot-workbench-origin='.length);
// 默认拒绝：离线页、外部文档和子框架不获得桌面桥；主进程仍独立校验实际来源。
if (window === window.top && ['http:', 'https:'].includes(location.protocol) && location.origin === expectedOrigin) {
  const subscribe = (channel, callback, passPayload = true) => {
    const listener = passPayload ? (_event, payload) => callback(payload) : () => callback();
    ipcRenderer.on(channel, listener);
    return () => ipcRenderer.removeListener(channel, listener);
  };

  contextBridge.exposeInMainWorld('pilotDesktop', {
    onBrowserOpen: callback => subscribe('pilot-browser-open', callback),
    onNavigationNotice: callback => subscribe('pilot-navigation-notice', callback),
    reloadWorkbench: () => ipcRenderer.invoke('pilot-workbench-reload'),
    onWorkbenchCommand: callback => {
      const unsubscribe = subscribe('pilot-workbench-command', callback);
      ipcRenderer.send('pilot-workbench-ready');
      return unsubscribe;
    },
    copyPairingCode: code => ipcRenderer.invoke('pilot-mobile-copy', code),
    readClipboardFiles: limits => ipcRenderer.invoke('pilot:read-clipboard-files', limits),
    saveSessionMarkdown: (name, text) => ipcRenderer.invoke('pilot:save-session-markdown', name, text),
    selectDirectories: () => ipcRenderer.invoke('pilot:select-directories'),
    showFloatingAlert: (payload) => ipcRenderer.invoke('pilot:alert-show', payload),
    dismissFloatingAlert: (id) => ipcRenderer.invoke('pilot:alert-dismiss', id),
    showNativeNotification: (payload) => ipcRenderer.invoke('pilot:notify-show', payload),
    setWindowAttention: (payload) => ipcRenderer.invoke('pilot:window-attention', payload),
    focusMainWindow: () => ipcRenderer.invoke('pilot:window-focus'),
    onAlertAction: (callback) => subscribe('pilot-alert-action', callback),
    onWindowFocusChange: (callback) => subscribe('pilot-window-focus', callback),
    syncDesktopRuntime: (payload) => ipcRenderer.invoke('pilot:desktop-runtime-sync', payload),
    setGlobalShortcut: (accelerator) => ipcRenderer.invoke('pilot:desktop-shortcut-set', accelerator),
    onDesktopPreferenceAction: (callback) => subscribe('pilot-desktop-pref-action', callback),
    onMobileConnection: callback => subscribe('pilot-mobile-open', callback, false),
    openMobileConnection: () => ipcRenderer.invoke('pilot-mobile-open'),
    ensureTab: (sessionId) => ipcRenderer.invoke('pilot-browser-ensure', sessionId),
    navigate: (url) => ipcRenderer.invoke('pilot-browser-navigate', url),
    back: () => ipcRenderer.invoke('pilot-browser-go', 'back'),
    forward: () => ipcRenderer.invoke('pilot-browser-go', 'forward'),
    reload: () => ipcRenderer.invoke('pilot-browser-go', 'reload'),
    stop: () => ipcRenderer.invoke('pilot-browser-go', 'stop'),
    newTab: () => ipcRenderer.invoke('pilot-browser-new'),
    closeTab: (id) => ipcRenderer.invoke('pilot-browser-close', id),
    selectTab: (id) => ipcRenderer.invoke('pilot-browser-select', id),
    detach: () => ipcRenderer.invoke('pilot-browser-detach'),
    devtools: () => ipcRenderer.invoke('pilot-browser-devtools'),
    logs: (network) => ipcRenderer.invoke('pilot-browser-logs', Boolean(network)),
    openMenu: () => ipcRenderer.invoke('pilot-browser-menu'),
    find: (text) => ipcRenderer.invoke('pilot-browser-find', text),
    onLog: (callback) => subscribe('pilot-browser-log', callback),
    onQuote: (callback) => subscribe('pilot-browser-quote', callback),
    onDownload: (callback) => subscribe('pilot-browser-download', callback),
    pick: () => ipcRenderer.invoke('pilot-browser-pick'),
    resetEmulation: () => ipcRenderer.invoke('pilot-browser-reset-emulation'),
    setBounds: (bounds) => ipcRenderer.send('pilot-browser-bounds', bounds),
    setWorkspace: (workspace) => ipcRenderer.send('pilot-browser-workspace', workspace),
    onState: (callback) => subscribe('pilot-browser-state', callback),
    focusAddress: (callback) => subscribe('pilot-browser-focus-address', callback, false)
  });
}
