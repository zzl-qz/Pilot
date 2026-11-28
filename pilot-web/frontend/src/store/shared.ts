export function requireData<T>(value: T | null): T {
  if (value === null) throw new Error('服务端返回空响应，请刷新后重试');
  return value;
}

export function dismissDesktopAlert(id: string) {
  void globalThis.window?.pilotDesktop?.dismissFloatingAlert?.(id)?.catch?.(() => {});
}

export function setDesktopWindowAttention(payload: { urgentCount: number; kind?: string; bounce?: boolean }) {
  void globalThis.window?.pilotDesktop?.setWindowAttention?.(payload)?.catch?.(() => {});
}
