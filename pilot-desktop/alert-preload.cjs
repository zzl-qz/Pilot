const { contextBridge, ipcRenderer } = require('electron');

// 独立悬浮提醒窗仅接收展示快照与派发白名单动作，不暴露文件系统或主工作台桌面桥。
contextBridge.exposeInMainWorld('pilotAlert', {
  onState: (callback) => {
    if (typeof callback !== 'function') return () => {};
    const listener = (_event, state) => callback(state);
    ipcRenderer.on('pilot-alert-state', listener);
    return () => ipcRenderer.removeListener('pilot-alert-state', listener);
  },
  act: (payload) => {
    if (payload && typeof payload === 'object') {
      ipcRenderer.send('pilot-alert-act', payload);
    }
  },
  resize: (height) => {
    if (Number.isFinite(height) && height > 0) {
      ipcRenderer.send('pilot-alert-resize', Math.round(height));
    }
  }
});
