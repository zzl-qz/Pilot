import type { CommandTask } from '../../types/store.js';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../../store.js';
import { writeClipboard } from '../../lib/clipboard.js';
import { isCommandActive, commandStatusLabel, stripAnsi } from '../../lib/command-tasks.js';

/* ---------- 命令面板 ---------- */

const NO_TASKS: never[] = [];

export function CommandsPanel() {
  // 任务列表必须是数组：早先 store 把日志合并函数用在了任务上，这里会直接 TypeError 白屏
  const tasks = useStore((state) => state.commandTasks || NO_TASKS);
  const detail = useStore((state) => state.commandDetail);
  const log = useStore((state) => state.commandLog);
  const logRef = useRef<HTMLPreElement>(null);
  const [follow, setFollow] = useState(true);
  const active = tasks.filter((task) => isCommandActive(task));
  const sessionId = useStore((state) => state.sessionId);
  const selectedId = detail?.taskId || detail?.id;

  // 打开面板先主动拉一次：只靠轮询的话，第一次进来会有空白
  useEffect(() => {
    if (sessionId) {
      useStore.getState().loadCommandTasks();
      useStore.getState().loadBrowserStatus();
    }
  }, [sessionId]);

  /*
   * 进来先替你选一条：优先运行中的，其次最近一条。
   * 否则面板要等你点一下才有内容，Codex 那边是直接给最新终端的输出。
   */
  useEffect(() => {
    if (detail || !tasks.length) {
      return;
    }
    const target = tasks.find((task) => isCommandActive(task)) || tasks[0];
    useStore.getState().loadCommandLog(target);
  }, [tasks, detail]);

  /*
   * 只有真的有命令在跑才在这里加班轮询（1.5s）；空闲时交给 store 的 5s 全局轮询，
   * 避免面板开着就双份请求。
   */
  useEffect(() => {
    if (!sessionId || !active.length) {
      return undefined;
    }
    const timer = setInterval(async () => {
      await useStore.getState().loadCommandTasks();
      const current = useStore.getState().commandDetail;
      if (!current) {
        return;
      }
      const taskId = current.taskId || current.id;
      // 用刷新后的任务对象再去拉日志：否则「停止」按钮和状态会停在旧值
      const fresh = (useStore.getState().commandTasks || NO_TASKS)
        .find((task) => (task.taskId || task.id) === taskId);
      useStore.getState().loadCommandLog(fresh || current);
    }, 1500);
    return () => clearInterval(timer);
  }, [sessionId, active.length]);

  useEffect(() => {
    if (follow && logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [log, follow]);

  // 终端颜色码在 <pre> 里只会变成 `[32m` 这种噪音，展示前统一剥掉
  const logText = useMemo(() => stripAnsi(log?.text || ''), [log]);

  const copyLog = async () => {
    try {
      if (!await writeClipboard(logText)) throw new Error('clipboard unavailable');
      useStore.getState().showNotice('已复制命令输出');
    } catch {
      useStore.getState().showNotice('复制失败，请手动选择输出');
    }
  };

  return (
    <div id="panel-commands" className="panel">
      <div className="panel-toolbar">
        <span id="commands-state" className="commands-state">{active.length ? `${active.length} 个命令运行中` : '没有运行中的命令'}</span>
        <button type="button" className="btn" onClick={() => useStore.getState().loadCommandTasks()}>刷新</button>
      </div>
      <div id="commands-list" className="commands-list">
        {tasks.map((task) => {
          const taskId = task.taskId || task.id;
          const running = isCommandActive(task);
          return (
            <button
              key={taskId}
              type="button"
              className={`command-item${selectedId === taskId ? ' is-active' : ''}`}
              title={task.command || ''}
              onClick={() => useStore.getState().loadCommandLog(task)}
            >
              <span className={`command-status is-${String(task.status || 'run').toLowerCase()}`} />
              <span className="command-name">{task.command || taskId}</span>
              <span className="command-row-meta">
                {task.exitCode != null && <span className={`command-exit${task.exitCode ? ' is-bad' : ''}`}>exit {task.exitCode}</span>}
                <span className="command-duration">{durationLabel(task, running)}</span>
              </span>
              <span className="command-state">{commandStatusLabel(task)}</span>
            </button>
          );
        })}
        {!tasks.length && (
          <div id="commands-empty" className="commands-empty">
            <p>还没有命令任务</p>
            <p className="commands-empty-hint">
              智能体跑构建、测试或脚本时会在这里生成任务；命令在后台继续运行，随时可以回来看输出或停止。
            </p>
          </div>
        )}
      </div>
      {detail && (
        <div id="command-detail" className="command-detail">
          <div className="command-detail-head">
            <p id="command-title" className="command-title">{detail.command || detail.taskId}</p>
            {isCommandActive(detail) && (
              <button
                type="button"
                className="btn btn-danger"
                title="停止这个命令"
                onClick={() => useStore.getState().stopCommand(detail)}
              >停止</button>
            )}
            <button type="button" className="btn" onClick={copyLog}>复制输出</button>
          </div>
          <dl className="command-facts">
            <div><dt>状态</dt><dd>{commandStatusLabel(detail)}</dd></div>
            {detail.workdir && <div><dt>目录</dt><dd className="command-fact-path" title={detail.workdir}>{detail.workdir}</dd></div>}
            <div><dt>耗时</dt><dd>{durationLabel(detail, isCommandActive(detail)) || '—'}</dd></div>
            {detail.exitCode != null && <div><dt>退出码</dt><dd>{detail.exitCode}</dd></div>}
            {detail.pid != null && <div><dt>PID</dt><dd>{detail.pid}</dd></div>}
          </dl>
          {detail.reason && <p className="command-reason">{detail.reason}</p>}
          {log?.truncated && <p className="command-log-note">输出超过上限，已截断。</p>}
          <div className="command-log-head">
            <span className="command-log-note">{follow ? '自动跟随最新输出' : '已暂停跟随'}</span>
            <button type="button" className={`btn${follow ? ' is-on' : ''}`} onClick={() => setFollow((value) => !value)}>
              {follow ? '暂停跟随' : '跟随最新'}
            </button>
          </div>
          <pre
            id="command-log"
            className={`command-log${logText ? '' : ' is-empty'}`}
            ref={logRef}
            onScroll={(event) => {
              const el = event.currentTarget;
              const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
              if (follow && !atBottom) {
                setFollow(false);
              }
            }}
          >{logText || '这条命令没有留下输出。'}</pre>
        </div>
      )}
    </div>
  );
}

/** 命令耗时：运行中的任务算到现在，结束的按 startedAt→finishedAt。 */
function durationLabel(task: CommandTask | null | undefined, running: boolean) {
  const start = parseTimestamp(task?.startedAt);
  if (!start) {
    return '';
  }
  const end = parseTimestamp(task?.finishedAt) || (running ? Date.now() : 0);
  if (!end || end < start) {
    return '';
  }
  const ms = end - start;
  if (ms < 1000) {
    return `${ms}ms`;
  }
  if (ms < 60000) {
    return `${(ms / 1000).toFixed(1)}s`;
  }
  const minutes = Math.floor(ms / 60000);
  const seconds = Math.round((ms % 60000) / 1000);
  return `${minutes}m ${seconds}s`;
}

function parseTimestamp(value: string | number | null | undefined) {
  if (value == null || value === '') {
    return 0;
  }
  if (typeof value === 'number') {
    return value;
  }
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : 0;
}
