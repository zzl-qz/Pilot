import type { QueueEntry } from '../types/runtime.js';
import { Button } from '@astryxdesign/core/Button';
import { IconButton } from '@astryxdesign/core/IconButton';
import { useEffect, useRef, useState } from 'react';
import { useStore } from '../store.js';
import { splitAttachmentMarkers, joinAttachmentMarkers } from '../lib/attachments.js';
import { locateQueueTarget, queueEntryKey, queueHeader } from '../lib/queue.js';
import type { QueueEditTarget } from '../types/store.js';
import { Icon } from './Icon.jsx';

/** 排队消息紧贴输入框，所有操作跟随消息本身。 */
export function MessageQueue() {
  const entries = useStore((state) => state.queueEntries);
  const queueEdit = useStore((state) => state.ui.queueEdit);
  const collapsed = useStore((state) => state.ui.queueCollapsed);
  const queueHeld = useStore((state) => state.queueHeld);
  const queueWaitingReason = useStore((state) => state.queueWaitingReason);
  const header = queueHeader({ count: entries.length, held: queueHeld, waitingReason: queueWaitingReason });
  // 队列被取空时编辑区还要留着，否则用户没保存的文字会一起消失
  if (!header && !queueEdit) {
    return null;
  }
  return (
    <QueueList entries={entries} queueEdit={queueEdit} collapsed={collapsed} header={header} held={queueHeld} />
  );
}

function QueueList({ entries, queueEdit, collapsed, header, held }: { entries: QueueEntry[]; queueEdit: QueueEditTarget | null; collapsed: boolean; header: ReturnType<typeof queueHeader>; held: boolean }) {
  const holding = useStore((state) => state.queueHolding);
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const headRef = useRef<HTMLButtonElement>(null);
  const closeEditor = () => {
    useStore.getState().setUi({ queueEdit: null });
    headRef.current?.focus({ preventScroll: true });
  };
  const editAt = locateQueueTarget(entries, queueEdit);
  const orphan = Boolean(queueEdit) && editAt < 0;
  return (
    <div id="message-queue" className="message-queue">
      {header && <div className="queue-head-row">
        <button ref={headRef} type="button" className="queue-head" aria-expanded={!collapsed} onClick={() => useStore.getState().setUi({ queueCollapsed: !collapsed })}>
          <span className="queue-title">{header.title}</span>
          <span className="queue-head-meta">{header.meta}</span>
          {!!entries.length && <span className="queue-toggle">{collapsed ? '展开' : '收起'}</span>}
        </button>
        {!!entries.length && <span className="queue-hold" title={held ? '恢复后若已空闲且验收通过，会立即开始队首' : '暂停只阻止自动开始下一条，当前任务继续执行'}>
          <Button id="queue-hold" size="sm" variant="ghost" label={held ? '恢复队列' : '暂停队列'}
            isDisabled={holding} onClick={() => void useStore.getState().setQueueHeld(!held)} />
        </span>}
      </div>}
      {orphan && <div className="queue-orphan">
        <p role="status">原来的排队消息已被发送、删除或修改。未保存的输入已保留，可复制后重新选择目标。</p>
        <QueueEditor target={queueEdit!} text="" orphan onClose={closeEditor} />
      </div>}
      {!collapsed && !!entries.length && (
        <ol className="queue-list">
          {entries.map((entry, index) => {
            const text = entry.text ?? entry.content ?? '';
            return (
              <li
                key={`${index}-${queueEntryKey(entry)}`}
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
                {editAt === index ? (
                  <QueueEditor target={{ index, key: queueEntryKey(entry) }} text={text}
                    requirements={entry.requirements} onClose={closeEditor} />
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
  const takingBack = useStore(state => state.queueTakingBack);
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
  const constraints = entry.requirements?.constraints || [];
  const acceptance = entry.requirements?.acceptance || [];
  const requirementSummary = constraints.length || acceptance.length
    ? [constraints.length ? `约束 ${constraints.length}` : '', acceptance.length ? `验收 ${acceptance.length}` : '']
      .filter(Boolean).join(' · ')
    : '';
  const edit = () => useStore.getState().setUi({ queueEdit: { index, key: queueEntryKey(entry) } });
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
        <button type="button" className="queue-text" disabled={busy} title={label} onClick={edit}>{label}</button>
        {requirementSummary && <span className="queue-requirements" title={[...constraints, ...acceptance].join('\n')}>{requirementSummary}</span>}
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
          onClick={edit} />
        <Button className="queue-takeback" label="取回输入框" variant="ghost" size="sm"
          isDisabled={busy || takingBack}
          onClick={() => void useStore.getState().takeBackQueueItem(index)} />
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

/** 未保存的编辑内容按目标指纹暂存：队列刷新、消息被取走时编辑区重建也不会丢。 */
const drafts = new Map<string, string>();

/** 编辑区按内容指纹定位目标；队列刷新不会重建输入，也不会把内容写到别的消息上。 */
function QueueEditor({ target, text, requirements, orphan = false, onClose }: {
  target: QueueEditTarget; text: string; requirements?: QueueEntry['requirements']; orphan?: boolean; onClose: () => void }) {
  const spoken = splitAttachmentMarkers(text);
  const [value, setValue] = useState(() => drafts.get(target.key) ?? spoken.body);
  const [constraintText, setConstraintText] = useState(() => (requirements?.constraints || []).join('\n'));
  const [acceptanceText, setAcceptanceText] = useState(() => (requirements?.acceptance || []).join('\n'));
  const [saving, setSaving] = useState(false);
  const finish = () => { drafts.delete(target.key); onClose(); };
  return (
    <div id="queue-edit" className="queue-edit" onKeyDown={event => {
      if (event.key === 'Escape' && !event.nativeEvent.isComposing) {
        event.preventDefault(); event.stopPropagation(); finish();
      }
    }}>
      <textarea autoFocus aria-label="编辑排队消息" disabled={saving} value={value} rows={2}
        onChange={(event) => { setValue(event.target.value); drafts.set(target.key, event.target.value); }} />
      <label className="queue-edit-field">
        <span>约束（每行一条，可留空）</span>
        <textarea aria-label="这条排队消息的约束" disabled={saving} value={constraintText} rows={2}
          onChange={(event) => setConstraintText(event.target.value)} />
      </label>
      <label className="queue-edit-field">
        <span>验收条件（每行一条，可留空）</span>
        <textarea aria-label="这条排队消息的验收条件" disabled={saving} value={acceptanceText} rows={2}
          onChange={(event) => setAcceptanceText(event.target.value)} />
      </label>
      <div className="queue-edit-actions">
        <Button label="保存" size="sm" isLoading={saving} isDisabled={orphan || (!value.trim() && !spoken.files.length)} onClick={async () => {
          setSaving(true);
          try {
            const requirements = {
              constraints: constraintText.split('\n').map(item => item.trim()).filter(Boolean),
              acceptance: acceptanceText.split('\n').map(item => item.trim()).filter(Boolean)
            };
            if (await useStore.getState().saveQueueItem(target, joinAttachmentMarkers(value, spoken.files), requirements)) finish();
          } finally { setSaving(false); }
        }} />
        <Button label="取消" size="sm" variant="ghost" onClick={finish} />
      </div>
    </div>
  );
}