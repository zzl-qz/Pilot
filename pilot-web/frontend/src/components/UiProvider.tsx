import { useLayoutEffect, useSyncExternalStore, type ReactNode } from 'react';
import { Theme } from '@astryxdesign/core/theme';
import { InternationalizationProvider } from '@astryxdesign/core/i18n';
import zhCN from '@astryxdesign/core/locales/zh-CN.generated.js';
import { neutralTheme } from '@astryxdesign/theme-neutral/built';
import { useStore } from '../store.js';
import { resolveTheme, subscribeSystemTheme, systemPrefersDark } from '../lib/theme.js';
import '@astryxdesign/core/reset.css';
import '@astryxdesign/core/astryx.css';
import '@astryxdesign/theme-neutral/theme.css';
import './ui-theme.css';

const messages = { 'zh-CN': zhCN };

export function UiProvider({ children }: { children: ReactNode }) {
  const mode = useStore(state => state.ui.themeMode);
  const dark = useSyncExternalStore(subscribeSystemTheme, systemPrefersDark, () => false);
  useLayoutEffect(() => { document.documentElement.dataset.themeMode = mode; }, [mode]);
  return <Theme theme={neutralTheme} mode={resolveTheme(mode, dark)}>
    <InternationalizationProvider locale="zh-CN" messages={messages}>{children}</InternationalizationProvider>
  </Theme>;
}
