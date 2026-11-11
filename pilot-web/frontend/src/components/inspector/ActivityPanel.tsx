import type { TimelineItem } from '../../types/thread.js';
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../../store.js';
import { formatTime } from '../../thread-utils.js';

export function Filters() {
  const current = useStore((state) => state.ui.filters);
  const timeline = useStore((state) => state.timeline);
  const options = [['all', '全部'], ['tool', '工具'], ['approval', '审批'], ['run', '运行']];

  // 每个筛选后面跟条数：不用切来切去试，就知道哪一类有事发生
  const counts = useMemo(() => {
    const map: Record<string, number> = { all: timeline.length, tool: 0, approval: 0, run: 0 };
    timeline.forEach((item) => {
      if (map[item.kind] != null) {
        map[item.kind] += 1;
      }
    });
    return map;
  }, [timeline]);

  return (
    <div id="filters" className="filters" role="tablist" aria-label="事件过滤">
      {options.map(([value, label]) => (
        <button
          key={value}
          type="button"
          className={`filter${current === value ? ' is-on' : ''}`}
          role="tab"
          aria-selected={current === value}
          title={`只看${label}`}
          onClick={() => useStore.getState().setFilters(value)}
        >
          {label}
          <span className="filter-count">{counts[value]}</span>
        </button>
      ))}
    </div>
  );
}

/* ---------- 活动时间线 ---------- */

export function ActivityPanel() {
  const timeline = useStore((state) => state.timeline);
  const filter = useStore((state) => state.ui.filters);
  const [query, setQuery] = useState('');
  const [openKeys, setOpenKeys] = useState(() => new Set<string>());
  const listRef = useRef<HTMLOListElement>(null);
  const stickRef = useRef(true);

  const byKind = filter === 'all' ? timeline : timeline.filter((item) => item.kind === filter);
  const keyword = query.trim().toLowerCase();
  const items = keyword ? byKind.filter((item) => matchesActivity(item, keyword)) : byKind;

  const toggle = useCallback((key: string) => setOpenKeys((current) => {
    const next = new Set(current);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    return next;
  }), []);

  // 新事件只在用户本来就在底部时才跟随，向上翻记录时不要被拽回去
  const itemCount = items.length;
  useEffect(() => {
    const list = listRef.current;
    if (list && stickRef.current) {
      list.scrollTop = list.scrollHeight;
    }
  }, [itemCount]);

  if (!timeline.length) {
    return <p id="timeline-empty" className="panel-empty">还没有活动。发一条消息试试。</p>;
  }

  /*
   * run.usage 一轮会刷几十条，连续出现时折成一行「×N」，
   * 否则时间线里全是用量事件，工具调用反而被冲走。
   */
  const groups: { type: string; count: number; firstSequence: number; lastSequence: number; item: TimelineItem }[] = [];
  for (const item of items) {
    const last = groups[groups.length - 1];
    if (last && item.type === 'run.usage' && last.type === 'run.usage') {
      last.count += 1;
      last.lastSequence = item.sequence;
      continue;
    }
    groups.push({ type: item.type, count: 1, firstSequence: item.sequence, lastSequence: item.sequence, item });
  }

  return (
    <div className="activity-panel">
      <div className="activity-search">
        <input
          className="activity-search-input"
          placeholder="搜索事件类型 / 标题 / 详情"
          aria-label="搜索活动"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <span className="activity-search-count">
          {keyword ? `${items.length} / ${byKind.length}` : `${byKind.length} 条`}
        </span>
      </div>
      {!items.length && <p className="panel-empty">没有匹配的活动。</p>}
      <ol
        id="timeline"
        className="timeline"
        ref={listRef}
        onScroll={(event) => {
          const el = event.currentTarget;
          stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
        }}
      >
        {groups.map((group) => (group.count > 1
          ? (
            <li key={group.item.key} className="event event-group">
              <div className="event-head">
                <span className="event-seq">#{group.firstSequence}–#{group.lastSequence}</span>
                <span className="event-type">{group.type}</span>
                <span className="event-status">×{group.count}</span>
              </div>
            </li>
          )
          : (
            <ActivityItem
              key={group.item.key}
              item={group.item}
              open={openKeys.has(group.item.key)}
              onToggle={toggle}
            />
          )))}
      </ol>
    </div>
  );
}

function matchesActivity(item: TimelineItem, keyword: string) {
  return [item.type, item.title, item.detail, item.foot, item.status]
    .some((value) => String(value || '').toLowerCase().includes(keyword));
}

/**
 * 每条活动单独 memo：流式增量只改最后一张卡，其余几百条不该跟着每帧重渲染。
 * 点卡片展开完整内容（默认两行截断），长脚本才不需要整屏滚动。
 */
const ActivityItem = memo(function ActivityItem({ item, open, onToggle }: { item: TimelineItem; open: boolean; onToggle: (key: string) => void }) {
  return (
    <li
      className={`event tone-${item.tone}${open ? ' is-open' : ''}`}
      data-kind={item.kind}
      onClick={() => onToggle(item.key)}
    >
      <div className="event-head">
        <span className="event-seq">#{item.sequence}</span>
        <span className="event-type">{item.type}</span>
        <span className="event-status">{item.status}</span>
        <span className="event-time">{formatTime(item.timestamp)}</span>
      </div>
      {item.title && <p className="event-title">{item.title}</p>}
      {item.detail && <p className="event-detail">{item.detail}</p>}
      {item.foot && <p className="event-foot">{item.foot}</p>}
      {item.actions && item.callId && <ApprovalInline callId={item.callId} />}
    </li>
  );
});

function ApprovalInline({ callId }: { callId: string }) {
  const pending = useStore((state) => state.pendingApprovals[callId]);
  if (!pending) {
    return null;
  }
  return (
    <div className="approval-actions" onClick={(event) => event.stopPropagation()}>
      <button type="button" className="btn btn-primary" onClick={() => useStore.getState().resolveApproval(callId, true)}>批准</button>
      <button type="button" className="btn" onClick={() => useStore.getState().resolveApproval(callId, false)}>拒绝</button>
    </div>
  );
}
