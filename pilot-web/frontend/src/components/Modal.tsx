import React, { useEffect, useLayoutEffect, useRef } from 'react';
import { Dialog } from '@astryxdesign/core/Dialog';
import { restoreFocus } from '../lib/interaction.js';
import { DialogCloseButton } from './DialogHeader.jsx';
import './modals.css';

interface ModalProps {
  id: string;
  title: React.ReactNode;
  onClose: () => void;
  children: React.ReactNode;
  wide?: boolean;
  width?: number;
  footer?: React.ReactNode;
  dismissible?: boolean;
  initialFocus?: string;
}

const fallbackStack: HTMLDivElement[] = [];

export function Modal({ id, title, onClose, children, wide, width, footer, dismissible = true, initialFocus }: ModalProps) {
  const dialog = useRef<HTMLDialogElement>(null);
  const fallback = useRef<HTMLDivElement>(null);
  const close = useRef<(() => void) | null>(null);
  close.current = dismissible ? onClose : null;
  const native = typeof HTMLDialogElement !== 'undefined' && typeof HTMLDialogElement.prototype.showModal === 'function';

  useLayoutEffect(() => {
    const previous = document.activeElement;
    return () => {
      // 原入口可能随窗口缩窄或菜单关闭而移除。保留新弹窗或操作已主动设置的焦点。
      requestAnimationFrame(() => {
        if (document.activeElement === document.body) restoreFocus(previous === document.body ? null : previous);
      });
    };
  }, []);

  useEffect(() => {
    const node = dialog.current || fallback.current;
    if (!node) return;
    const target = initialFocus ?? (wide ? '.dialog-close' : 'input:not([disabled]):not([type=radio]):not([type=checkbox]), textarea:not([disabled])');
    const frame = requestAnimationFrame(() => {
      if (node.contains(document.activeElement) && document.activeElement !== node.querySelector('.dialog-close')) return;
      node.querySelector<HTMLElement>(target)?.focus({ preventScroll: true });
    });
    return () => cancelAnimationFrame(frame);
  }, [initialFocus, wide]);

  useEffect(() => {
    const node = fallback.current;
    if (!node) return;
    // 旧 WebView 沿用同一组内容和按钮，并把 Tab / Escape 交给最后打开的弹窗。
    const previous = document.activeElement;
    node.style.zIndex = String(Number(fallbackStack.at(-1)?.style.zIndex || 100) + 1);
    fallbackStack.push(node);
    const topmost = () => fallbackStack.at(-1) === node;
    const items = () => [...node.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), summary, a[href], [tabindex="0"]')]
      .filter(el => el.tabIndex >= 0 && el.getClientRects().length);
    (items()[0] || node).focus({ preventScroll: true });
    const onKey = (event: KeyboardEvent) => {
      if (!topmost()) return;
      if (event.key === 'Escape' && !event.isComposing && event.keyCode !== 229) {
        event.preventDefault();
        event.stopImmediatePropagation();
        close.current?.();
      } else if (event.key === 'Tab') {
        const targets = items();
        const first = targets[0], last = targets.at(-1);
        const active = document.activeElement;
        if (!first || !node.contains(active) || active === (event.shiftKey ? first : last)) {
          event.preventDefault();
          (event.shiftKey ? last || node : first || node).focus();
        }
      }
    };
    const onFocus = (event: FocusEvent) => {
      if (topmost() && event.target instanceof Node && !node.contains(event.target)) (items()[0] || node).focus();
    };
    document.addEventListener('keydown', onKey, true);
    document.addEventListener('focusin', onFocus);
    return () => {
      fallbackStack.splice(fallbackStack.indexOf(node), 1);
      document.removeEventListener('keydown', onKey, true);
      document.removeEventListener('focusin', onFocus);
      if (!fallbackStack.length || fallbackStack.at(-1)?.contains(previous)) restoreFocus(previous);
    };
  }, []);

  const titleId = `${id}-title`;
  const preferredWidth = width ?? (wide ? 880 : 560);
  const content = <div className={`modal-box pilot-dialog-frame${wide ? ' is-wide' : ''}`}>
    <header className="pilot-dialog-header">
      <h1 id={titleId}>{title}</h1>
      <DialogCloseButton onClose={() => close.current?.()} disabled={!dismissible} />
    </header>
    <div className={wide ? 'pilot-dialog-content is-wide' : 'pilot-dialog-content'}>{children}</div>
    {footer && <footer className="pilot-dialog-footer">{footer}</footer>}
  </div>;

  if (!native) return <div ref={fallback} id={id} className="modal pilot-dialog-fallback" role="dialog" aria-modal="true"
    aria-labelledby={titleId} tabIndex={-1} style={{ '--pilot-dialog-width': `${preferredWidth}px` } as React.CSSProperties}
    onClick={event => { if (event.target === event.currentTarget) close.current?.(); }}>{content}</div>;

  return <Dialog ref={dialog} id={id} className="modal pilot-dialog" isOpen aria-labelledby={titleId}
    width={preferredWidth} maxHeight="100dvh" padding={0} purpose={dismissible ? 'info' : 'required'}
    onOpenChange={open => { if (!open) close.current?.(); }}
    onKeyDown={event => {
      if (event.key !== 'Escape') return;
      // 原生 cancel 只发给最上层弹窗。拦住冒泡，避免触发工作台的 Esc 停止任务。
      if (event.nativeEvent.isComposing || event.keyCode === 229) event.preventDefault();
      event.stopPropagation();
    }}>{content}</Dialog>;
}
