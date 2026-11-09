/** Pilot 与组件库共用已解析的颜色模式，避免 system 模式移除旧页面的 data-theme。 */
export function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-color-scheme: dark)').matches;
}

export function resolveTheme(mode: string, dark = systemPrefersDark()): 'dark' | 'light' {
  return mode === 'system' ? (dark ? 'dark' : 'light') : mode === 'dark' ? 'dark' : 'light';
}

export function subscribeSystemTheme(change: () => void): () => void {
  const media = window.matchMedia?.('(prefers-color-scheme: dark)');
  media?.addEventListener?.('change', change);
  return () => media?.removeEventListener?.('change', change);
}

/** 缺少弹层定位能力时，页面提供相同功能的直接操作按钮。 */
export function supportsAnchoredLayers(): boolean {
  return typeof CSS !== 'undefined' && typeof CSS.supports === 'function'
    && CSS.supports('anchor-name', '--pilot-menu')
    && typeof HTMLElement !== 'undefined' && typeof HTMLElement.prototype.showPopover === 'function';
}
