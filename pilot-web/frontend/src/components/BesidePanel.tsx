import { useStore } from '../store.js';
import { SessionNotes } from './SessionNotes.jsx';
import { SideChatPanel } from './SideChat.jsx';
import './beside.css';

/**
 * 「旁白」：执行主任务时的一切旁路操作只在这一个面板里。
 *
 * <p>以前是两处入口各开各的——想法是输入框的浮层，侧边对话是右侧面板的一个页签。
 * 两个都叫「旁白」，用户不知道该用哪个，功能也互相不知道对方存在。
 * 现在合成一个面板两个页签：记（随笔，不发出去）与问（分叉出去问，关掉即丢）。
 */
const TABS = [
  { id: 'notes', label: '记', hint: '随手记下，不发出去；需要时再转成任务' },
  { id: 'chat', label: '问', hint: '从当前任务分叉问一句，关掉即丢弃' }
];

export function BesidePanel() {
  const tab = useStore(state => state.ui.besideTab) || 'notes';
  const setTab = (next: string) => useStore.getState().setUi({ besideTab: next });
  /** 就这条想法问一句：带引用开一条侧边对话，并切到「问」页签。 */
  const askAbout = async (text: string) => {
    setTab('chat');
    await useStore.getState().askSideChat(text);
  };
  return (
    <div id="panel-beside" className="panel beside-panel">
      <div className="beside-tabs" role="tablist" aria-label="旁白">
        {TABS.map(item => (
          <button key={item.id} type="button" role="tab" id={`beside-tab-${item.id}`}
            aria-selected={tab === item.id} aria-controls={`beside-panel-${item.id}`}
            className={`beside-tab${tab === item.id ? ' is-active' : ''}`} title={item.hint}
            onClick={() => setTab(item.id)}>{item.label}</button>
        ))}
        <span className="beside-hint">{TABS.find(item => item.id === tab)?.hint}</span>
      </div>
      <div className="beside-body" role="tabpanel" id={`beside-panel-${tab}`} aria-labelledby={`beside-tab-${tab}`}>
        {/* 两个页签都保持挂载：切走再切回来不该丢掉正在写的草稿或刚开的侧边对话 */}
        <div hidden={tab !== 'notes'}><SessionNotes embedded onAsk={(text) => void askAbout(text)} /></div>
        <div hidden={tab !== 'chat'}><SideChatPanel active={tab === 'chat'} /></div>
      </div>
    </div>
  );
}