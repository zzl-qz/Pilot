import { useLayoutEffect, type ReactNode, type Ref } from 'react';
import { Button } from '@astryxdesign/core/Button';
import { Dialog } from '@astryxdesign/core/Dialog';
import { restoreFocus } from '../lib/interaction.js';
import { Icon } from './Icon.jsx';
import './automation.css';

export function AutomationDialog({ titleId, title, description, onClose, bodyRef, footer, children, width = 960 }: {
  titleId: string;
  title: string;
  description: string;
  onClose: () => void;
  bodyRef?: Ref<HTMLDivElement>;
  footer?: ReactNode;
  children: ReactNode;
  width?: number;
}) {
  // Astryx 优先回到打开入口。入口已折叠或被移除时，沿用工作台的焦点回退。
  useLayoutEffect(() => {
    const previous = document.activeElement;
    return () => queueMicrotask(() => {
      if (document.activeElement === document.body && !document.querySelector('dialog[open], [role="dialog"][aria-modal="true"]')) {
        restoreFocus(previous);
      }
    });
  }, []);

  return (
    <Dialog isOpen onOpenChange={open => { if (!open) onClose(); }} purpose="form" width={width}
      maxHeight="min(800px, calc(100dvh - 48px))" padding={0} className="automation-dialog"
      aria-labelledby={titleId} onKeyDown={event => event.stopPropagation()}>
      <header className="automation-header">
        <div><h1 id={titleId}>{title}</h1><p>{description}</p></div>
        <Button className="automation-close" label={`关闭${title}`} icon={<Icon name="close" />} isIconOnly variant="ghost" onClick={onClose} />
      </header>
      <div className="automation-body" ref={bodyRef}>{children}</div>
      {footer && <footer className="automation-footer">{footer}</footer>}
    </Dialog>
  );
}
