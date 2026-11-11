/** 本机目录只对连接本机服务的工作台有意义；远程服务应浏览其自己的目录。 */
export function localDirectoryPickerAvailable(value) {
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password
      && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  } catch { return false; }
}

export async function selectProjectDirectories({ origin, parent, dialog }) {
  if (!localDirectoryPickerAvailable(origin)) {
    return { ok: false, code: 'REMOTE_WORKBENCH', message: '当前连接远程服务，请浏览服务端文件夹或手动填写服务器路径。' };
  }
  if (!parent || parent.isDestroyed()) {
    return { ok: false, code: 'WINDOW_UNAVAILABLE', message: '工作台窗口已关闭，请重新打开后选择目录。' };
  }
  try {
    const result = await dialog.showOpenDialog(parent, {
      title: '选择项目文件夹', buttonLabel: '添加文件夹', properties: ['openDirectory', 'multiSelections'],
    });
    return { ok: true, canceled: Boolean(result.canceled), paths: result.canceled ? [] : result.filePaths || [] };
  } catch (error) {
    return { ok: false, code: 'DIALOG_FAILED', message: String(error?.message || error) };
  }
}
