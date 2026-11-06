import React from 'react';
import { Icon } from './Icon.jsx';
import './dialog-header.css';

export function DialogCloseButton({ onClose, label = '关闭弹窗' }: { onClose?: () => void; label?: string }) {
  return <button type="button" className="dialog-close" aria-label={label} title={`${label}（Esc）`} onClick={onClose}>
    <Icon name="close" />
  </button>;
}

export function DialogHeader({ id, title, subtitle, closeLabel, onClose }: { id?: string; title: React.ReactNode; subtitle?: React.ReactNode; closeLabel?: string; onClose: () => void }) {
  return <header className="schedule-header">
    <div className="dialog-heading"><h1 id={id}>{title}</h1>{subtitle && <span className="schedule-product">{subtitle}</span>}</div>
    <DialogCloseButton label={closeLabel} onClose={onClose} />
  </header>;
}
