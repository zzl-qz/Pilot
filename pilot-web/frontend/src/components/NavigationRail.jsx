import React from 'react';
import { useStore } from '../store.js';
import { shortcut } from '../lib/interaction.js';
import { Icon } from './Icon.jsx';

/** 全局入口独立于项目列表，桌面收起项目栏后仍可使用。 */
export function NavigationRail() {
  const ui = useStore(state => state.ui);
  const unread = ui.resultUnread || 0;
  const open = patch => useStore.getState().setUi(patch);
  return <nav className="navigation-rail" aria-label="全局功能">
    <div className="navigation-rail-group">
      <RailButton id="nav-projects" icon="home" label="项目与对话" active={ui.sidebarOpen}
        onClick={() => open({ sidebarOpen: !ui.sidebarOpen })} />
      <RailButton icon="search" label="搜索消息与书签" active={ui.searchOpen}
        onClick={() => open({ searchOpen: true })} />
      <RailButton icon="inbox" label="任务收件箱" active={ui.resultsOpen} count={unread}
        onClick={() => open({ resultsOpen: true, resultTarget: null })} />
      <RailButton icon="clock" label="定时任务" active={ui.schedulesOpen}
        onClick={() => open({ schedulesOpen: true, scheduleDraft: null })} />
    </div>
    <div className="navigation-rail-group navigation-rail-bottom">
      <RailButton id="mobile-connect" icon="phone" label="连接手机" active={ui.mobileConnectionOpen}
        onClick={() => open({ mobileConnectionOpen: true })} />
      <RailButton icon="more" label="命令面板" hint={shortcut('K')} active={ui.paletteOpen}
        onClick={() => open({ paletteOpen: true })} />
      <RailButton id="theme-toggle" icon={ui.themeMode === 'light' ? 'moon' : 'sun'} label="切换明暗主题"
        onClick={() => useStore.getState().setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark')} />
      <RailButton id="settings-open" icon="settings" label="设置" hint={shortcut(',')} active={ui.settingsOpen}
        onClick={() => open({ settingsOpen: true })} />
    </div>
  </nav>;
}

function RailButton({ id, icon, label, hint, active, count = 0, onClick }) {
  const description = `${label}${count > 0 ? ` · ${count} 条未读` : ''}${hint ? ` (${hint})` : ''}`;
  return <button id={id} className={`rail-button${active ? ' is-active' : ''}`} type="button"
    aria-label={label} aria-pressed={active} title={description} onClick={onClick}>
    <Icon name={icon} />
    {count > 0 && <span className="rail-unread" aria-label={`${count} 条未读`}>{count > 99 ? '99+' : count}</span>}
    <span className="rail-tooltip" aria-hidden="true">{description}</span>
  </button>;
}
