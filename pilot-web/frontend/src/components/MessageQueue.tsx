import type { QueueEntry } from '../types/runtime.js';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { splitAttachmentMarkers, joinAttachmentMarkers } from '../lib/attachments.js';
import { queueHeader } from '../lib/queue.js';
import { Icon } from './Icon.jsx';

/** 排队消息紧贴输入框，所有操作跟随消息本身。 */
export function MessageQueue() {
  const entries = useStore((state) => state.queueEntries);
  const queueEdit = useStore((state) => state.ui.queueEdit);
  const collapsed = useStore((state) => state.ui.queueCollapsed);
  const queueHeld = useStore((state) => state.queueHeld);
  const header = queueHeader({ count: entries.length, held: queueHeld });
  if (!header) {
    return null;
  }
  return (
    <QueueList entries={entries} queueEdit={queueEdit} collapsed={collapsed} header={header} />
  );
}

function QueueList({ entries, queueEdit, collapsed, header }: { entries: QueueEntry[]; queueEdit: number | null; collapsed: boolean; header: NonNullable<ReturnType<typeof queueHeader>> }) {
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const headRef = useRef<HTMLButtonElement>(null);
  const closeEditor = () => {
    useStore.getState().setUi({ queueEdit: null });
    headRef.current?.focus({ preventScroll: true });
  };
  return (
    <div id="message-queue" className="message-queue">
      <button ref={headRef} type="button" className="queue-head" aria-expanded={!collapsed} onClick={() => useStore.getState().setUi({ queueCollapsed: !collapsed })}>
        <span className="queue-title">{header.title}</span>
        <span className="queue-head-meta">{header.meta}</span>
        {!!entries.length && <span className="queue-toggle">{collapsed ? '展开' : '收起'}</span>}
      </button>
      {!collapsed && !!entries.length && (
        <ol className="queue-list">
          {entries.map((entry, index) => {
            const text = entry.text ?? entry.content ?? '';
            return (
              <li
                key={`${index}-${text}`}
                className={`queue-item${dragFrom === index ? ' is-dragging' : ''}`}
                onDragOver={event => {
                  if (dragFrom == null) return;
                  event.preventDefault();
                  event.dataTransfer.dropEffect = 'move';
                }}
                onDrop={event => {
                  if (dragFrom == null) return;
                  event.preventDefault();
                  setDragFrom(null);
                  if (dragFrom !== index && !useStore.getState().queueBusy) {
                    void useStore.getState().moveQueueItem(dragFrom, index);
                  }
                }}
              >
                {queueEdit === index ? (
                  <QueueEditor index={index} text={text} onClose={closeEditor} />
                ) : (
                  <QueueRow entry={entry} index={index} count={entries.length} onDragStart={() => setDragFrom(index)} onDragEnd={() => setDragFrom(null)} />
                )}
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}

function QueueRow({ entry, index, count, onDragStart, onDragEnd }: { entry: QueueEntry; index: number; count: number; onDragStart: () => void; onDragEnd: () => void }) {
  const running = useStore(state => state.running);
  const busy = useStore(state => state.queueBusy || state.cancelling || state.pausing);
  const [more, setMore] = useState(false);
  const moreRef = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    if (!more) return;
    const close = (event: KeyboardEvent | PointerEvent) => {
      if (event instanceof KeyboardEvent && (event.key !== 'Escape' || event.isComposing || event.keyCode === 229 || event.defaultPrevented)) return;
      if (event.type === 'pointerdown' && event.target instanceof Node && moreRef.current?.contains(event.target)) return;
      if (event.type === 'keydown') { event.preventDefault(); event.stopPropagation(); moreRef.current?.querySelector('button')?.focus(); }
      setMore(false);
    };
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', close, true);
    return () => { document.removeEventListener('pointerdown', close); document.removeEventListener('keydown', close, true); };
  }, [more]);
  const text = entry.text ?? entry.content ?? '';
  const spoken = splitAttachmentMarkers(text);
  const images = Array.isArray(entry.attachments) ? entry.attachments : [];
  const files = spoken.files || [];
  const label = spoken.body || (images.length ? '图片' : text);
  return (
    <>
      <button
        type="button"
        className="queue-grip"
        draggable={!busy}
        disabled={busy}
        onDragEnd={onDragEnd}
        aria-label="拖动调整顺序"
        title="拖动调整顺序"
        tabIndex={-1}
        onDragStart={event => {
          event.dataTransfer.setData('application/x-pilot-queue', String(index));
          event.dataTransfer.effectAllowed = 'move';
          onDragStart();
        }}
      >
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 6h8M4 10h8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
      </button>
      <div className="queue-main">
        <button type="button" className="queue-text" disabled={busy} title={label} onClick={() => useStore.getState().setUi({ queueEdit: index })}>{label}</button>
        {(images.length > 0 || files.length > 0) && (
          <span className="attach-strip">
            {images.map((image) => (
              <img key={image.url || image.name} className="queue-thumb" src={image.url} alt={image.name || '图片'} />
            ))}
            {files.map((file) => (
              <span key={file.raw || file.name} className="queue-file" title={file.name}>{file.name}</span>
            ))}
          </span>
        )}
      </div>
      <div className="queue-actions">
        {running && <Button className="queue-steer-action" label="引导这条排队消息" icon={<Icon name="steer" />}
          variant="ghost" size="sm" isDisabled={busy} onClick={() => useStore.getState().steerQueueItem(index)}>引导</Button>}
        <IconButton variant="ghost" size="sm" isDisabled={busy} label="编辑这条排队消息" icon={<Icon name="edit" />}
          onClick={() => useStore.getState().setUi({ queueEdit: index })} />
        <IconButton variant="ghost" size="sm" isDisabled={busy} label="删除这条排队" icon={<Icon name="trash" />}
          onClick={() => useStore.getState().removeQueueItem(index)} />
        {count > 1 && <span className="queue-more" ref={moreRef}>
          <IconButton variant="ghost" size="sm" label="更多排队操作" icon={<Icon name="more" />} aria-expanded={more} isDisabled={busy}
            onClick={() => setMore(!more)} />
          {more && <div className="queue-more-menu">
            <Button label="上移" variant="ghost" size="sm" isDisabled={index === 0 || busy}
              onClick={() => { setMore(false); useStore.getState().moveQueueItem(index, index - 1); }} />
            <Button label="下移" variant="ghost" size="sm" isDisabled={index === count - 1 || busy}
              onClick={() => { setMore(false); useStore.getState().moveQueueItem(index, index + 1); }} />
          </div>}
        </span>}
      </div>
    </>
  );
}

function QueueEditor({ index, text, onClose }: { index: number; text: string; onClose: () => void }) {
  const spoken = splitAttachmentMarkers(text);
  const [value, setValue] = useState(spoken.body);
  const [saving, setSaving] = useState(false);
  return (
    <div id="queue-edit" className="queue-edit" onKeyDown={event => {
      if (event.key === 'Escape' && !event.nativeEvent.isComposing) {
        event.preventDefault(); event.stopPropagation(); onClose();
      }
    }}>
      <textarea autoFocus aria-label="编辑排队消息" disabled={saving} value={value} onChange={(event) => setValue(event.target.value)} rows={2} />
      <div className="queue-edit-actions">
        <Button label="保存" size="sm" isLoading={saving} isDisabled={!value.trim() && !spoken.files.length} onClick={async () => {
          setSaving(true);
          try {
            if (await useStore.getState().saveQueueItem(index, joinAttachmentMarkers(value, spoken.files))) onClose();
          } finally { setSaving(false); }
        }} />
        <Button label="取消" size="sm" variant="ghost" onClick={onClose} />
      </div>
    </div>
  );
}
