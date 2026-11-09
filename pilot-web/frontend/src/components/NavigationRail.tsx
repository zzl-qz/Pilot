import { Badge } from '@astryxdesign/core/Badge';
import { Button } from '@astryxdesign/core/Button';
import type { UiState } from '../types/store.js';
import { useStore } from '../store.js';
import { shortcut } from '../lib/interaction.js';
import { Icon } from './Icon.jsx';
import { NavigationMenu, NavigationMoreIcon } from './NavigationMenu.jsx';
import './navigation.css';

/** 全局入口独立于项目列表，桌面收起项目栏后仍可使用。 */
export function NavigationRail() {
  const sidebarOpen = useStore(state => state.ui.sidebarOpen);
  const unread = useStore(state => state.ui.resultUnread || 0);
  const resultsOpen = useStore(state => Boolean(state.ui.resultsOpen));
  const searchOpen = useStore(state => Boolean(state.ui.searchOpen));
  const theme = useStore(state => state.ui.themeMode);
  const open = (patch: Partial<UiState>) => useStore.getState().setUi(patch);

  return (
    <nav className="navigation-rail" aria-label="全局功能">
      <div className="navigation-rail-group">
        {!sidebarOpen && <div className="navigation-shortcut">
          <NewTaskButton compact />
          <span className="navigation-tooltip" aria-hidden="true">{`新任务 (${shortcut('N')})`}</span>
        </div>}
        <RailButton icon="inbox" label="任务收件箱" active={resultsOpen} count={unread}
          onClick={() => open({ resultsOpen: true, resultTarget: null })} />
        <RailButton id="nav-message-search" icon="search" label="搜索消息与书签" active={searchOpen}
          onClick={() => open({ searchOpen: true })} />
      </div>
      <div className="navigation-rail-bottom navigation-shortcut">
        <NavigationMenu
          button={{ label: '更多功能', icon: <NavigationMoreIcon />, isIconOnly: true, variant: 'ghost', className: 'navigation-action' }}
          side="end"
          entries={[
            { id: 'nav-schedules', icon: <Icon name="clock" />, label: '定时任务', onClick: () => open({ schedulesOpen: true, scheduleDraft: null }) },
            { id: 'mobile-connect', icon: <Icon name="phone" />, label: '连接手机', onClick: () => open({ mobileConnectionOpen: true }) },
            'divider',
            { icon: <Icon name="code" />, label: `命令面板 (${shortcut('K')})`, onClick: () => open({ paletteOpen: true }) },
            { id: 'settings-open', icon: <Icon name="settings" />, label: `设置 (${shortcut(',')})`, onClick: () => open({ settingsOpen: true }) },
            { id: 'theme-toggle', icon: <Icon name={theme === 'light' ? 'moon' : 'sun'} />, label: '切换明暗主题', onClick: () => {
              useStore.getState().setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');
            } },
          ]}
        />
        <span className="navigation-tooltip" aria-hidden="true">更多功能</span>
      </div>
    </nav>
  );
}

export function NewTaskButton({ compact = false }: { compact?: boolean }) {
  return <Button id={compact ? undefined : 'new-session'} label="新任务" variant={compact ? 'ghost' : 'secondary'}
    className={compact ? 'navigation-action' : 'navigation-new-task'} isIconOnly={compact}
    icon={<NewTaskIcon />} aria-keyshortcuts="Control+N Meta+N"
    endContent={compact ? undefined : <kbd className="navigation-key" aria-hidden="true">{shortcut('N')}</kbd>}
    onClick={() => {
      useStore.getState().startNewSession();
      requestAnimationFrame(() => useStore.getState().composerApi?.focus());
    }} />;
}

export function NewTaskIcon() {
  return <svg className="ui-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" aria-hidden="true">
    <path d="M8 3v10M3 8h10" />
  </svg>;
}

function RailButton({ id, icon, label, active, count = 0, onClick }: {
  id?: string; icon: string; label: string; active: boolean; count?: number; onClick: () => void;
}) {
  const unreadLabel = count > 0 ? `${count} 条未读` : '';
  return (
    <div className="navigation-shortcut">
      <Button id={id} label={label} icon={<Icon name={icon} />} isIconOnly variant="ghost" className="navigation-action"
        aria-haspopup="dialog" aria-expanded={active} aria-describedby={unreadLabel ? 'navigation-unread' : undefined}
        onClick={event => {
          event.currentTarget.focus({ preventScroll: true });
          onClick();
        }} />
      {unreadLabel && <Badge id="navigation-unread" className="navigation-unread" aria-label={unreadLabel} label={count > 99 ? '99+' : count} />}
      <span className="navigation-tooltip" aria-hidden="true">{label}{unreadLabel && ` · ${unreadLabel}`}</span>
    </div>
  );
}
