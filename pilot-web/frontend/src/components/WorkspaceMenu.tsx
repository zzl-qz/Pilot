import { useCallback, useLayoutEffect, useState } from 'react';
import { DropdownMenu, type DropdownMenuButtonProps, type DropdownMenuOption } from '@astryxdesign/core/DropdownMenu';
import { supportsAnchoredLayers } from '../lib/theme.js';

export function WorkspaceMenu({ button, items, menuWidth, onOpenChange }: {
  button: DropdownMenuButtonProps;
  items: DropdownMenuOption[];
  menuWidth: number;
  onOpenChange?: (open: boolean) => void;
}) {
  const [open, setOpen] = useState(false);
  const changeOpen = useCallback((next: boolean) => {
    setOpen(next);
    onOpenChange?.(next);
  }, [onOpenChange]);

  useLayoutEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing || event.keyCode === 229) return;
      // 由菜单本身处理已进入弹层的焦点。打开到焦点移动之间，先于工作台的停止任务快捷键处理 Esc。
      if (document.activeElement?.closest('[popover], dialog[open], [role="dialog"][aria-modal="true"]')) return;
      event.preventDefault();
      event.stopPropagation();
      changeOpen(false);
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [open, changeOpen]);

  return <DropdownMenu button={button} items={items} menuWidth={menuWidth} hasChevron={false} alignment="end"
    presentation={supportsAnchoredLayers() ? 'popover' : 'bottom-sheet'} isMenuOpen={open} onOpenChange={changeOpen} />;
}
