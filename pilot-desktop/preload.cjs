const { contextBridge, ipcRenderer } = require('electron');

const expectedOrigin = process.argv.find(value => value.startsWith('--pilot-workbench-origin='))?.slice('--pilot-workbench-origin='.length);
// 默认拒绝：离线页、外部文档和子框架不获得桌面桥；主进程仍独立校验实际来源。
if (window === window.top && ['http:', 'https:'].includes(location.protocol) && location.origin === expectedOrigin) {
  contextBridge.exposeInMainWorld('pilotDesktop', {
    onBrowserOpen: callback => {
      const listener = (_event, message) => callback(message);
      ipcRenderer.on('pilot-browser-open', listener);
      return () => ipcRenderer.removeListener('pilot-browser-open', listener);
    },
    onNavigationNotice: callback => {
      const listener = (_event, message) => callback(message);
      ipcRenderer.on('pilot-navigation-notice', listener);
      return () => ipcRenderer.removeListener('pilot-navigation-notice', listener);
    },
    reloadWorkbench: () => ipcRenderer.invoke('pilot-workbench-reload'),
    onWorkbenchCommand: callback => {
      const listener = (_event, command) => callback(command);
      ipcRenderer.on('pilot-workbench-command', listener);
      ipcRenderer.send('pilot-workbench-ready');
      return () => ipcRenderer.removeListener('pilot-workbench-command', listener);
    },
    copyPairingCode: code => ipcRenderer.invoke('pilot-mobile-copy', code),
    selectDirectories: () => ipcRenderer.invoke('pilot:select-directories'),
    onMobileConnection: callback => {
      const listener = () => callback();
      ipcRenderer.on('pilot-mobile-open', listener);
      return () => ipcRenderer.removeListener('pilot-mobile-open', listener);
    },
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
    onLog: (callback) => {
      const listener = (_event, entry) => callback(entry);
      ipcRenderer.on('pilot-browser-log', listener);
      return () => ipcRenderer.removeListener('pilot-browser-log', listener);
    },
    onQuote: (callback) => {
      const listener = (_event, text) => callback(text);
      ipcRenderer.on('pilot-browser-quote', listener);
      return () => ipcRenderer.removeListener('pilot-browser-quote', listener);
    },
    onDownload: (callback) => {
      const listener = (_event, info) => callback(info);
      ipcRenderer.on('pilot-browser-download', listener);
      return () => ipcRenderer.removeListener('pilot-browser-download', listener);
    },
    pick: () => ipcRenderer.invoke('pilot-browser-pick'),
    resetEmulation: () => ipcRenderer.invoke('pilot-browser-reset-emulation'),
    setBounds: (bounds) => ipcRenderer.send('pilot-browser-bounds', bounds),
    setWorkspace: (workspace) => ipcRenderer.send('pilot-browser-workspace', workspace),
    onState: (callback) => {
      const listener = (_event, state) => callback(state);
      ipcRenderer.on('pilot-browser-state', listener);
      return () => ipcRenderer.removeListener('pilot-browser-state', listener);
    },
    focusAddress: (callback) => {
      const listener = () => callback();
      ipcRenderer.on('pilot-browser-focus-address', listener);
      return () => ipcRenderer.removeListener('pilot-browser-focus-address', listener);
    }
  });

}
