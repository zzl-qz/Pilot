import React, { useRef, useState } from 'react';
import type { UiState } from '../types/store.js';
import { useStore } from '../store.js';
import { shortcut } from '../lib/interaction.js';
import { Icon } from './Icon.jsx';

/** 全局入口独立于项目列表，桌面收起项目栏后仍可使用。 */
export function NavigationRail() {
  const ui = useStore(state => state.ui);
  const unread = ui.resultUnread || 0;
  const moreRef = useRef<HTMLDetailsElement>(null);
  const [moreOpen, setMoreOpen] = useState(false);
  const open = (patch: Partial<UiState>) => useStore.getState().setUi(patch);
  const invoke = (action: () => void) => {
    if (moreRef.current) moreRef.current.open = false;
    setMoreOpen(false);
    moreRef.current?.querySelector('summary')?.focus();
    action();
  };
  return <nav className="navigation-rail" aria-label="全局功能">
    <div className="navigation-rail-group">
      <RailButton id="nav-projects" icon="home" label="项目与对话" active={ui.sidebarOpen}
        onClick={() => open({ sidebarOpen: !ui.sidebarOpen })} />
      <RailButton icon="search" label="搜索消息与书签" active={ui.searchOpen}
        onClick={() => open({ searchOpen: true })} />
      <RailButton icon="inbox" label="任务收件箱" active={ui.resultsOpen} count={unread}
        onClick={() => open({ resultsOpen: true, resultTarget: null })} />
    </div>
    <div className="navigation-rail-group navigation-rail-bottom">
      <details className="navigation-rail-more" ref={moreRef} onToggle={event => setMoreOpen(event.currentTarget.open)}
        onKeyDown={event => {
          if (event.key === 'Escape') {
            event.preventDefault();
            event.currentTarget.open = false;
            setMoreOpen(false);
            event.currentTarget.querySelector('summary')?.focus();
          }
        }}>
        <summary className="rail-button" role="button" aria-label="更多功能" aria-haspopup="menu" aria-expanded={moreOpen}
          aria-controls="navigation-more-menu" title="更多功能">
          <Icon name="more" />
          <span className="rail-tooltip" aria-hidden="true">更多功能</span>
        </summary>
        <div id="navigation-more-menu" className="navigation-more-menu" role="menu" aria-label="更多功能">
          <MenuButton id="nav-schedules" icon="clock" label="定时任务" onClick={() => invoke(() => open({ schedulesOpen: true, scheduleDraft: null }))} />
          <MenuButton id="mobile-connect" icon="phone" label="连接手机" onClick={() => invoke(() => open({ mobileConnectionOpen: true }))} />
          <MenuButton icon="code" label={`命令面板 (${shortcut('K')})`} onClick={() => invoke(() => open({ paletteOpen: true }))} />
          <MenuButton id="theme-toggle" icon={ui.themeMode === 'light' ? 'moon' : 'sun'} label="切换明暗主题"
            onClick={() => invoke(() => useStore.getState().setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'))} />
          <MenuButton id="settings-open" icon="settings" label={`设置 (${shortcut(',')})`} onClick={() => invoke(() => open({ settingsOpen: true }))} />
        </div>
      </details>
    </div>
  </nav>;
}

function MenuButton({ id, icon, label, onClick }: { id?: string; icon: string; label: string; onClick: () => void }) {
  return <button id={id} className="navigation-more-item" type="button" role="menuitem" onClick={onClick}>
    <Icon name={icon} /><span>{label}</span>
  </button>;
}

function RailButton({ id, icon, label, hint, active, count = 0, onClick }: { id?: string; icon: string; label: string; hint?: string; active?: boolean; count?: number; onClick: () => void }) {
  const description = `${label}${count > 0 ? ` · ${count} 条未读` : ''}${hint ? ` (${hint})` : ''}`;
  return <button id={id} className={`rail-button${active ? ' is-active' : ''}`} type="button"
    aria-label={label} aria-pressed={active} title={description} onClick={onClick}>
    <Icon name={icon} />
    {count > 0 && <span className="rail-unread" aria-label={`${count} 条未读`}>{count > 99 ? '99+' : count}</span>}
    <span className="rail-tooltip" aria-hidden="true">{description}</span>
  </button>;
}
