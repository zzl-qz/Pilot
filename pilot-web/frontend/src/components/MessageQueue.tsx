import type { QueueEntry } from '../types/runtime.js';
import { Button } from '@astryxdesign/core/Button';
import { useRef, useState } from 'react';
import { useStore } from '../store.js';
import { splitAttachmentMarkers, joinAttachmentMarkers } from '../lib/attachments/attachments.js';
import { locateQueueTarget, queueBlockReason, queueEntryBadges, queueEntryKey, queueHeader, queueLane, textWithoutParkedMark } from '../lib/queue.js';
import type { QueueEditTarget } from '../types/store.js';
import { Icon } from './Icon.jsx';

/** 排队消息紧贴输入框，所有操作跟随消息本身。 */
export function MessageQueue() {
  const entries = useStore((state) => state.queueEntries);
  const queueEdit = useStore((state) => state.ui.queueEdit);
  const collapsed = useStore((state) => state.ui.queueCollapsed);
  const queueHeld = useStore((state) => state.queueHeld);
  const queueWaitingReason = useStore((state) => state.queueWaitingReason);
  const sendable = entries.filter(entry => queueLane(entry.text ?? entry.content ?? '') === 'send');
  const header = queueHeader({ count: sendable.length, held: queueHeld, waitingReason: queueWaitingReason });
  // 队列被取空时编辑区还要留着，否则用户没保存的文字会一起消失
  const hasSide = entries.some(entry => queueLane(entry.text ?? entry.content ?? '') !== 'send');
  if (!header && !queueEdit && !hasSide) {
    return null;
  }
  return (
    <QueueList entries={entries} queueEdit={queueEdit} collapsed={collapsed} header={header} held={queueHeld} />
  );
}

function QueueList({ entries, queueEdit, collapsed, header, held }: { entries: QueueEntry[]; queueEdit: QueueEditTarget | null; collapsed: boolean; header: ReturnType<typeof queueHeader>; held: boolean }) {
  const holding = useStore((state) => state.queueHolding);
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [dragOver, setDragOver] = useState<number | null>(null);
  const headRef = useRef<HTMLButtonElement>(null);
  const closeEditor = () => {
    useStore.getState().setUi({ queueEdit: null });
    headRef.current?.focus({ preventScroll: true });
  };
  const editAt = locateQueueTarget(entries, queueEdit);
  const orphan = Boolean(queueEdit) && editAt < 0;
  return (
    <div id="message-queue" className={`message-queue${held ? ' is-held' : ''}${collapsed ? ' is-collapsed' : ''}`}>
      {header && <div className="queue-head-row">
        <button ref={headRef} type="button" className="queue-head" aria-expanded={!collapsed} onClick={() => useStore.getState().setUi({ queueCollapsed: !collapsed })}>
          <span className={`queue-status-dot${held ? ' is-held' : ''}`} aria-hidden="true" />
          <span className="queue-title">{header.title}</span>
          <span className="queue-head-meta">{header.meta}</span>
          <span className="queue-toggle">{collapsed ? '展开' : '收起'}</span>
        </button>
        <span className="queue-hold">
          <Button id="queue-hold" size="sm" variant="ghost"
            label={holding ? (held ? '正在恢复…' : '正在暂停…') : (held ? '恢复队列' : '暂停队列')}
            isDisabled={holding} onClick={() => void useStore.getState().setQueueHeld(!held).then(ok => {
              if (ok) useStore.getState().showNotice(held ? '队列已恢复。空闲后只会发送上面会做的那几条。' : '队列已暂停，不会自动发送下一条。');
            })} />
        </span>
      </div>}
      {orphan && <div className="queue-orphan">
        <p role="status">原来的排队消息已被发送、删除或修改。未保存的输入已保留，可复制后重新选择目标。</p>
        <QueueEditor target={queueEdit!} text="" orphan onClose={closeEditor} />
      </div>}
      {!collapsed && <QueueGroup entries={entries} lane="send" held={held} editAt={editAt} dragFrom={dragFrom} dragOver={dragOver} setDragFrom={setDragFrom} setDragOver={setDragOver} closeEditor={closeEditor} />}
      <QueueGroup entries={entries} lane="blocked" held={held} title="还不能做" hint="这些不会自动发出去。写清点的是哪个按钮、或哪一个名字之后，才会进入上面的排队。" editAt={editAt} dragFrom={dragFrom} dragOver={dragOver} setDragFrom={setDragFrom} setDragOver={setDragOver} closeEditor={closeEditor} />
      <QueueGroup entries={entries} lane="parked" held={held} title="这次不会执行" hint="范围外的说明留在这里。点「移回排队」之前，不会变成下一条任务。" editAt={editAt} dragFrom={dragFrom} dragOver={dragOver} setDragFrom={setDragFrom} setDragOver={setDragOver} closeEditor={closeEditor} />
      <QueueGroup entries={entries} lane="wrapup" held={held} title="这些做完后再提交并推送" hint="这是收尾方式，不和界面修改抢顺序。当前这些功能做完之后再提交。" editAt={editAt} dragFrom={dragFrom} dragOver={dragOver} setDragFrom={setDragFrom} setDragOver={setDragOver} closeEditor={closeEditor} />
    </div>
  );
}

function QueueGroup({ entries, lane, held, title, hint, editAt, dragFrom, dragOver, setDragFrom, setDragOver, closeEditor }: {
  entries: QueueEntry[]; lane: 'send' | 'blocked' | 'parked' | 'wrapup'; held: boolean; title?: string; hint?: string;
  editAt: number; dragFrom: number | null; dragOver: number | null; setDragFrom: (index: number | null) => void; setDragOver: (index: number | null) => void; closeEditor: () => void }) {
  const rows = entries.map((entry, index) => ({ entry, index })).filter(row => queueLane(row.entry.text ?? row.entry.content ?? '') === lane);
  if (!rows.length) return null;
  return (
    <section className={`queue-lane is-${lane}`}>
      {title && <h3 className="queue-lane-title">{title}</h3>}
      {hint && <p className="queue-lane-hint">{hint}</p>}
      <ol className="queue-list">
        {rows.map(({ entry, index }, position) => {
          const text = entry.text ?? entry.content ?? '';
          const isEditing = editAt === index;
          return (
            <li
              key={`${index}-${queueEntryKey(entry)}`}
              className={`queue-item${isEditing ? ' is-editing' : ''}${dragFrom === index ? ' is-dragging' : ''}${dragOver === index && dragFrom !== null && dragFrom !== index ? ' is-drop-target' : ''}`}
              onDragOver={event => {
                if (dragFrom == null) return;
                event.preventDefault();
                event.dataTransfer.dropEffect = 'move';
                if (dragOver !== index) setDragOver(index);
              }}
              onDragLeave={() => {
                if (dragOver === index) setDragOver(null);
              }}
              onDrop={event => {
                if (dragFrom == null) return;
                event.preventDefault();
                setDragFrom(null);
                setDragOver(null);
                if (dragFrom !== index && !useStore.getState().queueBusy) {
                  void useStore.getState().moveQueueItem(dragFrom, index);
                }
              }}
              onKeyDown={event => {
                if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;
                if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
                event.preventDefault();
                const next = event.key === 'ArrowUp' ? index - 1 : index + 1;
                if (next < 0 || next >= entries.length || useStore.getState().queueBusy) return;
                void useStore.getState().moveQueueItem(index, next);
              }}
            >
              {isEditing ? (
                <QueueEditor target={{ index, key: queueEntryKey(entry) }} text={text}
                  requirements={entry.requirements} onClose={closeEditor} />
              ) : (
                <QueueRow entry={entry} index={index} position={position} count={entries.length} lane={lane} held={held}
                  onDragStart={() => setDragFrom(index)} onDragEnd={() => { setDragFrom(null); setDragOver(null); }} />
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

function QueueRow({ entry, index, position, count, lane, held, onDragStart, onDragEnd }: {
  entry: QueueEntry; index: number; position: number; count: number; lane: 'send' | 'blocked' | 'parked' | 'wrapup'; held: boolean;
  onDragStart: () => void; onDragEnd: () => void }) {
  const running = useStore(state => state.running);
  const busy = useStore(state => state.queueBusy || state.cancelling || state.pausing);
  const takingBack = useStore(state => state.queueTakingBack);
  const skills = useStore(state => state.skills);
  const [open, setOpen] = useState(false);
  const text = entry.text ?? entry.content ?? '';
  const spoken = splitAttachmentMarkers(text);
  const images = Array.isArray(entry.attachments) ? entry.attachments : [];
  const files = spoken.files || [];
  const label = spoken.body || (images.length || files.length ? '这条没有文字，只有附件' : '这条没有文字');
  const long = spoken.body.length > 80 || spoken.body.includes('\n');
  const blocked = queueBlockReason(text);
  const constraints = entry.requirements?.constraints || [];
  const acceptance = entry.requirements?.acceptance || [];
  const requirementSummary = constraints.length || acceptance.length
    ? [constraints.length ? `约束 ${constraints.length}` : '', acceptance.length ? `验收 ${acceptance.length}` : '']
      .filter(Boolean).join(' · ')
    : '';
  const badges = queueEntryBadges(entry, skills);
  const edit = () => useStore.getState().setUi({ queueEdit: { index, key: queueEntryKey(entry) } });
  return (
    <>
      <button
        type="button"
        className="queue-grip"
        draggable={!busy}
        disabled={busy}
        onDragEnd={onDragEnd}
        aria-label="拖动排序"
        title="拖动排序"
        tabIndex={-1}
        onDragStart={event => {
          event.dataTransfer.setData('application/x-pilot-queue', String(index));
          event.dataTransfer.effectAllowed = 'move';
          onDragStart();
        }}
      >
        <svg viewBox="0 0 16 16" aria-hidden="true">
          <circle cx="6" cy="4" r="1.1" fill="currentColor" />
          <circle cx="10" cy="4" r="1.1" fill="currentColor" />
          <circle cx="6" cy="8" r="1.1" fill="currentColor" />
          <circle cx="10" cy="8" r="1.1" fill="currentColor" />
          <circle cx="6" cy="12" r="1.1" fill="currentColor" />
          <circle cx="10" cy="12" r="1.1" fill="currentColor" />
        </svg>
      </button>
      {lane === 'send' && (
        <span className={`queue-order${position === 0 && !held ? ' is-next' : ''}`} aria-hidden="true">
          {position + 1}
        </span>
      )}
      <div className="queue-main">
        <button type="button" className={`queue-text${open || !long ? '' : ' is-clamped'}`} disabled={busy}
          onClick={() => { if (long) setOpen(value => !value); }}>{label}</button>
        {long && <button type="button" className="queue-expand" disabled={busy} onClick={() => setOpen(value => !value)}>
          {open ? '收起全文' : '展开全文'}
        </button>}
        {blocked && <p className="queue-block">{blocked}</p>}
        {(badges.length > 0 || requirementSummary) && (
          <div className="queue-meta-badges">
            {badges.map(badge => (
              <span key={`${badge.kind}:${badge.label}`} className={`queue-badge is-${badge.kind}`}>
                {badge.label}
              </span>
            ))}
            {requirementSummary && <span className="queue-requirements" title={[...constraints, ...acceptance].join('\n')}>{requirementSummary}</span>}
          </div>
        )}
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
        {running && lane === 'send' && <Button className="queue-steer-action" label="插到当前这轮里做" icon={<Icon name="steer" />}
          variant="ghost" size="sm" isDisabled={busy} onClick={() => useStore.getState().steerQueueItem(index)}>插到当前这轮里做</Button>}
        {lane === 'parked' && <Button label="移回排队" variant="ghost" size="sm" isDisabled={busy} onClick={() => {
          const next = textWithoutParkedMark(text);
          if (!next) {
            useStore.getState().showNotice('这条去掉范围外标记后没有正文，没有移回。');
            return;
          }
          void useStore.getState().saveQueueItem({ index, key: queueEntryKey(entry) }, next, entry.requirements
            ? { constraints: entry.requirements.constraints || [], acceptance: entry.requirements.acceptance || [] }
            : undefined).then(ok => {
            if (ok) useStore.getState().showNotice('已移回排队。这轮结束后会按顺序发送。');
          });
        }}>移回排队</Button>}
        <Button label="改这条" variant="ghost" size="sm" isDisabled={busy} onClick={edit}>改这条</Button>
        <Button className="queue-takeback" label={takingBack ? '正在放回…' : '放回输入框'} variant="ghost" size="sm"
          isDisabled={busy || takingBack}
          onClick={() => void useStore.getState().takeBackQueueItem(index)} />
        <Button className="queue-delete" label="删除" variant="ghost" size="sm" isDisabled={busy} onClick={() => void useStore.getState().removeQueueItem(index).then(ok => {
          if (ok) useStore.getState().showNotice('已删除这一条，它不会再被发送。');
        })}>删除</Button>
        {count > 1 && <Button label="上移" variant="ghost" size="sm" isDisabled={index === 0 || busy}
          onClick={() => useStore.getState().moveQueueItem(index, index - 1)}>上移</Button>}
        {count > 1 && <Button label="下移" variant="ghost" size="sm" isDisabled={index === count - 1 || busy}
          onClick={() => useStore.getState().moveQueueItem(index, index + 1)}>下移</Button>}
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
  const canSave = !orphan && !saving && Boolean(value.trim() || spoken.files.length);
  const save = async () => {
    if (!canSave) return;
    setSaving(true);
    try {
      const nextRequirements = {
        constraints: constraintText.split('\n').map(item => item.trim()).filter(Boolean),
        acceptance: acceptanceText.split('\n').map(item => item.trim()).filter(Boolean)
      };
      if (await useStore.getState().saveQueueItem(target, joinAttachmentMarkers(value, spoken.files), nextRequirements)) finish();
    } finally { setSaving(false); }
  };
  return (
    <div id="queue-edit" className="queue-edit" onKeyDown={event => {
      if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;
      if (event.key === 'Escape') {
        event.preventDefault(); event.stopPropagation(); finish();
      } else if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
        event.preventDefault(); event.stopPropagation(); void save();
      }
    }}>
      <textarea autoFocus aria-label="编辑排队消息" disabled={saving} value={value} rows={2}
        onChange={(event) => { setValue(event.target.value); drafts.set(target.key, event.target.value); }} />
      <div className="queue-edit-meta-grid">
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
      </div>
      <div className="queue-edit-actions">
        <Button label={saving ? '正在保存…' : '保存'} size="sm" isLoading={saving} isDisabled={!canSave} onClick={() => void save()} />
        <Button label="取消" size="sm" variant="ghost" onClick={finish} />
      </div>
    </div>
  );
}
