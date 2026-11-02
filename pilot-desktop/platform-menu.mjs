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
