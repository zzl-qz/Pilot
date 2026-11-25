import { useRef, useState, type ReactNode } from 'react';
import { ContextMenu } from '@astryxdesign/core/ContextMenu';
import { DropdownMenu, type DropdownMenuItemData, type DropdownMenuOption } from '@astryxdesign/core/DropdownMenu';
import type { SessionSummary } from '../types/core.js';
import { writeClipboard } from '../lib/clipboard.js';
import { supportsAnchoredLayers } from '../lib/theme.js';
import { useStore } from '../store.js';
import { Icon } from './Icon.jsx';
import type { SessionAction } from './SessionActionDialog.jsx';

export type { SessionAction };

/** 右键、长按和行尾按钮共用同一组操作，所有回调都绑定当时选择的会话。 */
export function SessionMenu({ session, children, onAction }: {
  session: SessionSummary; children: ReactNode; onAction: (action: SessionAction) => void;
}) {
  const row = useRef<HTMLElement>(null);
  const more = useRef<HTMLElement | null>(null);
  const pending = useRef(false);
  const touch = useRef({ holding: false, openedMenu: false });
  const [busy, setBusy] = useState(false);
  const [contextOpen, setContextOpen] = useState(false);
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const anchored = supportsAnchoredLayers();
  const presentation = anchored ? 'popover' : 'bottom-sheet';
  const title = session.title || '未命名对话';
  const label = `对话操作：${title}`;

  function items(trigger: () => HTMLElement | null): DropdownMenuOption[] {
    const origin = () => ({ trigger: trigger(), title });
    const item = (label: string, icon: string, onClick: () => void, variant?: DropdownMenuItemData['variant']): DropdownMenuItemData =>
      ({ label, icon: <Icon name={icon} />, onClick, variant, isDisabled: busy });
    const run = async (retry: () => Promise<boolean>) => {
      if (pending.current) return;
      const source = origin();
      pending.current = true;
      setBusy(true);
      try {
        if (!await retry()) onAction({ ...source, kind: 'retry', retry });
      } catch {
        onAction({ ...source, kind: 'retry', retry });
      } finally {
        pending.current = false;
        setBusy(false);
      }
    };
    const exportSession = async () => {
      const source = origin();
      const { downloadSessionExport, exportFailureMessage } = await import('../lib/session-export.js');
      try {
        const result = await downloadSessionExport(session.id, title);
        if (result.mode === 'manual') onAction({ ...source, kind: 'copy', value: result.text });
        else useStore.getState().showNotice(result.notice);
      } catch (error) {
        useStore.getState().showNotice(exportFailureMessage(error));
      }
    };
    const copy = async (value: string) => {
      const source = origin();
      if (await writeClipboard(value)) useStore.getState().showNotice('已复制');
      else onAction({ ...source, kind: 'copy', value });
    };
    const preference = (values: { pinned?: boolean; unread?: boolean }) =>
      run(() => useStore.getState().updateSessionPreferences(session.id, values));
    return [
      item('重命名', 'edit', () => onAction({ ...origin(), kind: 'rename', session })),
      item(session.pinned ? '取消置顶' : '置顶', 'pin', () => void preference({ pinned: !session.pinned })),
      item(session.unread ? '标记为已读' : '标记为未读', 'unread', () => void preference({ unread: !session.unread })),
      { type: 'divider' },
      item('复制标题', 'copy', () => void copy(title)),
      item('复制会话 ID', 'copy', () => void copy(session.id)),
      item('导出会话', 'file', () => void exportSession()),
      { type: 'divider' },
      item(session.archived ? '恢复到列表' : '归档', 'inbox',
        () => void run(() => useStore.getState().archiveSession(session.id, !session.archived))),
      item('永久删除', 'trash', () => onAction({ ...origin(), kind: 'delete', session }), 'destructive')
    ];
  }

  return <div className="session-menu-trigger" data-workbench-menu-open={contextOpen || dropdownOpen || undefined}>
    <ContextMenu ref={row} label={label} menuWidth={244} presentation={presentation}
      onOpenChange={open => {
        if (open && touch.current.holding) touch.current.openedMenu = true;
        setContextOpen(open);
      }} items={items(() => row.current?.querySelector('.session-item') ?? null)}>
      <span className="session-context-target"
        onTouchStartCapture={() => { touch.current = { holding: true, openedMenu: false }; }}
        onTouchEndCapture={event => {
          touch.current.holding = false;
          // 底部弹层的遮罩也不能收到本次抬手合成的点击。
          if (touch.current.openedMenu && event.cancelable) event.preventDefault();
        }}
        onTouchCancelCapture={() => { touch.current.holding = false; }}
        onPointerDownCapture={event => {
          if (event.pointerType !== 'touch') touch.current.openedMenu = false;
        }}
        onClickCapture={event => {
          // Chromium 可能在长按菜单显示后仍为抬手生成一次会话按钮 click。
          if (!touch.current.openedMenu || event.detail === 0) return;
          touch.current.openedMenu = false;
          event.preventDefault();
          event.stopPropagation();
        }}
        onKeyDown={event => {
          if (event.nativeEvent.isComposing || event.keyCode === 229 || event.repeat) return;
          if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return;
          event.preventDefault();
          // 某些浏览器不会从按键派发 contextmenu。使用同一入口并让 Astryx 恢复原焦点。
          event.target.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
        }}>{children}</span>
    </ContextMenu>
    <DropdownMenu aria-label={label} menuWidth={244} placement="end" presentation={presentation}
      isMenuOpen={dropdownOpen} onOpenChange={setDropdownOpen} items={items(() => more.current)}
      renderTrigger={props => <button {...props} type="button" className="session-more icon-btn"
        ref={element => { more.current = element; props.ref(element); }}
        title="更多操作" aria-label={label}
        onKeyDown={event => {
          props.onKeyDown(event);
          if (!anchored && !dropdownOpen && event.key === 'ArrowDown' && !event.nativeEvent.isComposing && event.keyCode !== 229) {
            event.preventDefault();
            setDropdownOpen(true);
          }
        }}><Icon name="more" /></button>} />
  </div>;
}
