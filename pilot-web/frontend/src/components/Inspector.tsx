import { useEffect, useRef, useState } from 'react';
import { DropdownMenu } from '@astryxdesign/core/DropdownMenu';
import { useStore } from '../store.js';
import { Icon } from './Icon.jsx';
import { ErrorBoundary } from './ErrorBoundary.jsx';
import { isCommandActive } from '../lib/command-tasks.js';
import { supportsAnchoredLayers } from '../lib/theme.js';
import { SideChatPanel } from './SideChat.jsx';
import FileWorkbench from './FileWorkbench.js';
import ResearchPanel from './ResearchPanel.jsx';
import WorkProductsPanel from './WorkProductsPanel.jsx';

import { ActivityPanel, Filters } from './inspector/ActivityPanel.js';
import { ChangesPanel } from './inspector/ChangesPanel.js';
import { PreviewPanel } from './inspector/PreviewPanel.js';
import { CommandsPanel } from './inspector/CommandsPanel.js';
import { CapabilitiesPanel } from './inspector/CapabilitiesPanel.js';
import { BrowserPanel } from './inspector/BrowserPanel.js';

const TABS = [
  { id: 'products', label: '成果', title: '交付文件、来源与技能审核' },
  { id: 'chat', label: '对话', title: '侧边对话：问和主线无关的问题，不打断正在跑的任务' },
  { id: 'changes', label: '变更', title: '工作区改动：逐个文件看差异、撤销' },
  { id: 'preview', label: '预览', title: '只读预览文件内容' },
  { id: 'commands', label: '命令', title: '命令任务、实时输出与停止' },
];
const MORE_TABS = [
  { id: 'capabilities', label: '能力', title: '当前生效的工具、技能与 MCP 服务器' },
  { id: 'browser', label: '浏览器', title: '内置浏览器与智能体操作' },
  { id: 'research', label: '开源参考', title: '已阅读来源、复用决定与许可证说明' },
  { id: 'activity', label: '活动', title: '事件时间线' }
];
const ALL_TABS = [...TABS, ...MORE_TABS];

/** 选择器默认值必须稳定，否则每次返回新数组会让 zustand 反复触发重渲染。 */
const NO_TASKS: never[] = [];

/** 右侧工作区：对话 / 变更 / 预览 / 命令 / 能力 / 浏览器镜像 / 活动时间线。 */
export function Inspector() {
  const tab = useStore((state) => state.ui.inspectorTab);
  const open = useStore((state) => state.ui.inspectorOpen);
  const sessionId = useStore((state) => state.sessionId);
  const navRef = useRef<HTMLDivElement>(null);
  const [moreOpen, setMoreOpen] = useState(false);
  const anchored = supportsAnchoredLayers();

  // 切换后将当前常用页签滚回可见区域。
  useEffect(() => {
    const active = navRef.current?.querySelector('[aria-selected="true"]');
    active?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  }, [tab]);

  if (!open) {
    return null;
  }
  return (
    <aside id="activity" className={`activity inspector is-rail is-open${tab === 'products' ? ' products-inspector' : ''}`} aria-label="工作区面板">
      <div className="inspector-main">
      <header className="inspector-panel-head"><strong id="inspector-title">{tab === 'files' ? '审阅文件' : ALL_TABS.find(item => item.id === tab)?.label}</strong><button type="button" className="icon-btn" aria-label="关闭工作区面板" onClick={() => useStore.getState().setUi({ inspectorOpen: false, reviewExpanded: false })}><Icon name="close" /></button></header>
      {tab === 'activity' && <div className="inspector-head"><Filters /></div>}
      <div className="inspector-body" id={`inspector-panel-${tab}`} role="tabpanel" aria-labelledby="inspector-title">
        {/* 每个面板单独兜底：某个页签渲染出错时只废掉这一块，别把整页卸载成白屏 */}
        <ErrorBoundary key={tab} name={TABS.find((item) => item.id === tab)?.label || '面板'} onReset={() => useStore.getState().setUi({ inspectorTab: 'activity' })}>
          {tab === 'chat' && <SideChatPanel />}
          {tab === 'changes' && <ChangesPanel />}
          {tab === 'preview' && <PreviewPanel />}
          {tab === 'commands' && <CommandsPanel />}
          {tab === 'capabilities' && <CapabilitiesPanel />}
          {tab === 'browser' && <BrowserPanel toolsMenuOpen={moreOpen} />}
          {tab === 'products' && <WorkProductsPanel />}
          {tab === 'files' && <FileWorkbench key={sessionId} />}
          {tab === 'research' && <ResearchPanel />}
          {tab === 'activity' && <ActivityPanel />}
        </ErrorBoundary>
      </div>
      </div>
      <nav className="inspector-rail" aria-label="工作区面板导航"><div id="panel-tabs" role="tablist" aria-label="常用面板" aria-orientation="vertical" ref={navRef} onKeyDown={event => {
        const keys = ['ArrowUp', 'ArrowDown', 'Home', 'End'];
        if (!keys.includes(event.key)) return;
        event.preventDefault();
        const buttons = [...(navRef.current?.querySelectorAll<HTMLElement>('[role="tab"]') || [])];
        const index = buttons.findIndex(button => button === event.target);
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
        buttons[next].click(); buttons[next].focus();
      }}>
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            className={tab === item.id ? 'is-on' : ''}
            role="tab"
            id={`inspector-tab-${item.id}`}
            aria-controls={`inspector-panel-${item.id}`}
            tabIndex={tab === item.id ? 0 : -1}
            title={item.title}
            aria-label={item.label}
            aria-selected={tab === item.id}
            onClick={() => useStore.getState().setUi({ inspectorTab: item.id })}
          >
            <RailIcon id={item.id} /><span className="rail-tab-label">{item.label}</span>
            {item.id === 'changes' && <ChangeBadge />}
            {item.id === 'commands' && <CommandDot />}
            {item.id === 'browser' && <BrowserDot />}
          </button>
        ))}
      </div>
      <div className="inspector-more-menu" data-workbench-menu-open={moreOpen || undefined}>
        <DropdownMenu placement="start" alignment="end" menuWidth={176}
          presentation={anchored ? 'popover' : 'bottom-sheet'}
          isMenuOpen={moreOpen} onOpenChange={setMoreOpen}
          renderTrigger={props => <button {...props} type="button"
            className={`rail-button${MORE_TABS.some(item => item.id === tab) ? ' is-on' : ''}`} aria-label="更多工具" title="更多工具"
            onKeyDown={event => {
              props.onKeyDown(event);
              if (!anchored && !moreOpen && event.key === 'ArrowDown' && !event.nativeEvent.isComposing && event.keyCode !== 229) {
                event.preventDefault();
                setMoreOpen(true);
              }
            }}><RailIcon id="more" /><span className="rail-tab-label">更多工具</span></button>}
          items={MORE_TABS.map(item => ({
            id: item.id, label: item.label, icon: <RailIcon id={item.id} />,
            onClick: () => useStore.getState().setUi({ inspectorTab: item.id })
          }))} />
      </div>
      </nav>
    </aside>
  );
}

function RailIcon({ id }: { id: string }) {
  const common = { viewBox: '0 0 16 16', width: 16, height: 16, 'aria-hidden': true };
  if (id === 'products') return <svg {...common}><path d="M4 2.5h5l3 3v8H4zM9 2.5v3h3M6 8h4M6 10.5h4" fill="none" stroke="currentColor" strokeWidth="1.2" /></svg>;
  if (id === 'studio') return <svg {...common}><path d="M3 2.5h6l4 4v7H3zM9 2.5v4h4M6 9h4M6 11h4" fill="none" stroke="currentColor" strokeWidth="1.2" /></svg>;
  if (id === 'research') return <svg {...common}><path d="M8 4C6 2.5 3 3 2 3v9c2-.5 4-.5 6 1 2-1.5 4-1.5 6-1V3c-2-.5-4-.5-6 1Zm0 0v9" fill="none" stroke="currentColor" strokeWidth="1.2" /></svg>;
  if (id === 'chat') return <svg {...common}><path d="M3 3.5h10v7H6.2L3 12.8z" fill="none" stroke="currentColor" strokeWidth="1.3" /></svg>;
  if (id === 'changes') return <svg {...common}><path d="M4 3.5h5.2L12 6.2V12.5H4z" fill="none" stroke="currentColor" strokeWidth="1.3" /></svg>;
  if (id === 'preview') return <svg {...common}><path d="M2.5 8s2.2-3.5 5.5-3.5S13.5 8 13.5 8 11.3 11.5 8 11.5 2.5 8 2.5 8z" fill="none" stroke="currentColor" strokeWidth="1.3" /><circle cx="8" cy="8" r="1.4" fill="currentColor" /></svg>;
  if (id === 'commands') return <svg {...common}><path d="M3.5 5.2 6.2 8 3.5 10.8M7.4 11h5" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" /></svg>;
  if (id === 'capabilities') return <svg {...common}><circle cx="8" cy="8" r="2" fill="none" stroke="currentColor" strokeWidth="1.3" /><path d="M8 2.8v1.6M8 11.6v1.6M2.8 8h1.6M11.6 8h1.6" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" /></svg>;
  if (id === 'browser') return <svg {...common}><circle cx="8" cy="8" r="5" fill="none" stroke="currentColor" strokeWidth="1.3" /><path d="M3 8h10M8 3c1.6 1.8 1.6 8.2 0 10M8 3c-1.6 1.8-1.6 8.2 0 10" fill="none" stroke="currentColor" strokeWidth="1.1" /></svg>;
  return <svg {...common}><path d="M3.5 4h9M3.5 8h9M3.5 12h6" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" /></svg>;
}

function ChangeBadge() {
  const report = useStore((state) => state.changeReport);
  // 未跟踪文件动辄几百个，页签上只显示真正的改动数，否则和面板里的「N 个文件」对不上
  const files = Array.isArray(report?.files) ? report.files : [];
  const count = files.filter((file) => file.status !== '??').length;
  return count ? <span className="tab-count">{count}</span> : null;
}

/** 有命令还在跑时页签上点一下：不用切过去也知道后台没空着。 */
function CommandDot() {
  const tasks = useStore((state) => state.commandTasks || NO_TASKS);
  return tasks.some((task) => isCommandActive(task)) ? <span className="tab-dot is-pulse" /> : null;
}

function BrowserDot() {
  const dot = useStore((state) => state.ui.browserDot);
  const live = useStore((state) => state.ui.mirror.mode === 'live');
  return (dot || live) ? <span className="tab-dot" /> : null;
}
