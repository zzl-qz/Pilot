import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { Dialog } from '@astryxdesign/core/Dialog';
import { DropdownMenu, DropdownMenuDivider, DropdownMenuItem, type DropdownMenuButtonProps } from '@astryxdesign/core/DropdownMenu';
import { restoreFocus } from '../lib/interaction.js';
import { supportsAnchoredLayers } from '../lib/theme.js';
import { Icon } from './Icon.jsx';

type MenuEntry = {
  id?: string;
  label: string;
  icon: ReactNode;
  onClick: () => void;
} | 'divider';

/** 同一组操作可用锚定菜单，也可在旧版浏览器里用原生对话框打开。 */
export function NavigationMenu({ button, entries, side = 'below' }: {
  button: Omit<DropdownMenuButtonProps, 'ref'>;
  entries: MenuEntry[];
  side?: 'below' | 'end';
}) {
  const [open, setOpen] = useState(false);
  const title = useId();
  const trigger = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const element = trigger.current;
    if (!open || !element || typeof IntersectionObserver === 'undefined') return;
    // 切换到抽屉布局或滚动列表后，菜单不能停在不可见的触发位置上。
    const observer = new IntersectionObserver(([entry]) => {
      if (entry?.isIntersecting && getComputedStyle(element).visibility !== 'hidden') return;
      setOpen(false);
      requestAnimationFrame(() => {
        if (document.activeElement === document.body) restoreFocus(null);
      });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [open]);
  const invoke = (action: () => void) => {
    trigger.current?.focus({ preventScroll: true });
    action();
  };

  if (supportsAnchoredLayers()) {
    return (
      <DropdownMenu button={{ ...button, ref: trigger }} isMenuOpen={open} onOpenChange={setOpen}
        placement={side} alignment="end" menuWidth={192} menuMaxHeight={440}
        className="pilot-navigation-menu">
        {entries.map((entry, index) => entry === 'divider'
          ? <DropdownMenuDivider key={index} />
          : <DropdownMenuItem key={entry.label} {...entry} onClick={() => invoke(entry.onClick)} />)}
      </DropdownMenu>
    );
  }

  return (
    <>
      <Button {...button} ref={trigger} aria-haspopup="dialog" aria-expanded={open} onClick={() => {
        trigger.current?.focus({ preventScroll: true });
        setOpen(!open);
      }} />
      {open && (
        <Dialog isOpen onOpenChange={setOpen} aria-labelledby={title} className="pilot-navigation-dialog"
          width={320} padding={0} purpose="form" onKeyDown={event => navigationDialogKeys(event, () => setOpen(false))}>
          <header className="pilot-navigation-dialog-head">
            <h2 id={title}>{button.label}</h2>
            <Button label="关闭操作菜单" icon={<Icon name="close" />} isIconOnly variant="ghost" onClick={() => setOpen(false)} />
          </header>
          <div className="pilot-menu-fallback" role="menu" aria-labelledby={title} onKeyDown={moveMenuFocus}>
            {entries.map((entry, index) => entry === 'divider'
              ? <div key={index} role="separator" />
              : <Button key={entry.label} id={entry.id} label={entry.label} icon={entry.icon}
                  variant="ghost" role="menuitem" tabIndex={index === 0 ? 0 : -1}
                  data-autofocus={index === 0 ? 'true' : undefined} onClick={() => {
                    // 先释放原生模态层，接下来打开的设置或项目弹窗才能接管焦点。
                    setOpen(false);
                    requestAnimationFrame(() => invoke(entry.onClick));
                  }} />)}
          </div>
        </Dialog>
      )}
    </>
  );
}

export function moveMenuFocus(event: KeyboardEvent<HTMLElement>) {
  if (event.nativeEvent.isComposing || !['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
  const items = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('[role="menuitem"]:not([aria-disabled="true"])'));
  if (!items.length) return;
  event.preventDefault();
  const current = items.indexOf(document.activeElement as HTMLElement);
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
    : event.key === 'ArrowDown' ? (current + 1) % items.length
    : (Math.max(current, 0) + items.length - 1) % items.length;
  items[next]?.focus();
}

export function navigationDialogKeys(event: KeyboardEvent<HTMLElement>, close: () => void) {
  if (event.key === 'Tab') return;
  // 对话框内的输入与 Escape 由当前层处理，不能触发工作台快捷键或停止任务。
  event.stopPropagation();
  if (event.key === 'Escape' && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) {
    event.preventDefault();
    close();
  }
}

export function NavigationMoreIcon() {
  return <svg className="ui-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
    <circle cx="3.5" cy="8" r="1.15" />
    <circle cx="8" cy="8" r="1.15" />
    <circle cx="12.5" cy="8" r="1.15" />
  </svg>;
}
