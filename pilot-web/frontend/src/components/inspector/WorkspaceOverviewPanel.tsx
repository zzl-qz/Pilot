import { useEffect, useState } from 'react';
import { api } from '../../api.js';
import { useStore } from '../../store.js';
import type { ApiResults } from '../../types/api.js';

/**
 * 工作区看板：只读汇总同一目录下所有会话在做什么、谁改着同一批文件、谁还在等仲裁。
 *
 * <p>这里故意不提供接受或驳回：写操作仍回到各自会话做，避免从旁观视角误批别人的改动。
 */
export function WorkspaceOverviewPanel() {
  const workspace = useStore((state) => state.workspace);
  const sessionId = useStore((state) => state.sessionId);
  const [data, setData] = useState<ApiResults['workspaceOverview'] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [epoch, setEpoch] = useState(0);

  useEffect(() => {
    if (!workspace) {
      setData(null);
      setError('');
      return;
    }
    let alive = true;
    setLoading(true);
    api.workspaceOverview(workspace).then((result) => {
      if (!alive) return;
      setData(result);
      setError('');
    }).catch((reason: unknown) => {
      if (!alive) return;
      setData(null);
      setError(reason instanceof Error ? reason.message : String(reason));
    }).finally(() => {
      if (alive) setLoading(false);
    });
    return () => { alive = false; };
  }, [workspace, epoch]);

  if (!workspace) {
    return <p className="overview-empty">先选定一个工作区目录。</p>;
  }

  const sessions = data?.sessions || [];
  const owners = data?.fileOwners || [];

  return (
    <div className="overview-panel" data-testid="workspace-overview">
      <div className="overview-bar">
        <span>{loading ? '读取中…' : `${sessions.length} 个会话`}</span>
        <button type="button" className="overview-refresh" onClick={() => setEpoch((value) => value + 1)} disabled={loading}>刷新</button>
      </div>
      {error && <p className="overview-error" role="alert">看板读取失败：{error}</p>}
      {!error && owners.length > 0 && (
        <section className="overview-conflicts">
          <h4>同一批文件被多个会话改着</h4>
          <ul>
            {owners.map((owner) => (
              <li key={owner.path}><code>{owner.path}</code><span>{owner.sessions.length} 个会话</span></li>
            ))}
          </ul>
        </section>
      )}
      {!error && !loading && sessions.length === 0 && <p className="overview-empty">这个目录下还没有会话。</p>}
      <ul className="overview-sessions">
        {sessions.map((session) => (
          <li key={session.sessionId} className={session.sessionId === sessionId ? 'is-current' : ''}>
            <div className="overview-head">
              <strong>{session.title || session.sessionId}</strong>
              {session.running && <span className="overview-tag">运行中</span>}
              {session.sessionId === sessionId && <span className="overview-tag">当前</span>}
            </div>
            {[
              session.pendingFiles.length > 0
                ? `待接受改动 ${session.pendingFiles.length} 个：${session.pendingFiles.join('、')}` : '',
              session.runningCommands.length > 0
                ? `后台命令：${session.runningCommands.map((item) => item.command).join('、')}` : '',
              session.arbitrations.length > 0
                ? `等待人工决定 ${session.arbitrations.length} 项（${session.arbitrations.map((item) => item.kind).join('、')}）` : '',
            ].filter(Boolean).map((text) => <p key={text} className="overview-line">{text}</p>)}
            {session.tasks.length > 0 && (
              <ul className="overview-tasks">
                {session.tasks.map((task) => (
                  <li key={task.taskId}>{task.title || task.taskId}：{task.status}</li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
