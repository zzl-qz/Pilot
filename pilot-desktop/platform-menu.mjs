/** macOS 保留原生 Zoom；Windows/Linux 明确执行最大化与还原。 */
export function windowSizeMenuItem(platform = process.platform) {
  if (platform === 'darwin') return { role: 'zoom', label: '调整窗口大小' };
  return {
    label: '最大化 / 还原',
    click(_item, window) {
      if (!window || window.isDestroyed()) return;
      if (window.isMaximized()) window.unmaximize();
      else window.maximize();
    }
  };
}

/** 主工作台快捷键明确指定目标，避免焦点在内置浏览器时刷新错页面。 */
export function workbenchShortcut(input, platform = process.platform) {
  if (input?.type !== 'keyDown' || input.isComposing || input.alt || input.isAutoRepeat) return null;
  const key = String(input.key || '').toLowerCase();
  if (key === 'f5' && !input.meta && !input.control) return input.shift ? 'reload' : 'refresh';
  const modifier = platform === 'darwin' ? input.meta : input.control;
  return modifier && key === 'r' ? 'reload' : null;
}

export function workbenchMenuItems(dispatch) {
  return [
    { id: 'workbench-refresh', label: '刷新工作台数据', accelerator: 'F5', click: () => dispatch('refresh') },
    { id: 'workbench-reload', label: '重新载入工作台界面', accelerator: 'CommandOrControl+R', click: () => dispatch('reload') },
  ];
}
