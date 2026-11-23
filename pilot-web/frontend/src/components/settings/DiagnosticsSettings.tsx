import type { ApiResults } from "../../types/api.js";
import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { errorInfo } from "../../lib/errors.js";
import { statusFromCode, verificationStatusLabel } from "../../lib/verification/products.js";
import { commandStatusLabel } from "../../lib/command-tasks.js";
import { verificationLabel } from "../../lib/verification/run.js";
import { writeClipboard } from "../../lib/clipboard.js";
import { useStore } from "../../store.js";
import { api } from "../../api.js";
import { formatCount } from "./UsageSettings.js";

type DiagnosticsSnapshot = {
  maintenance: ApiResults['maintenanceStatus'] | null;
  metrics: ApiResults['metrics'] | null;
  recovery: ApiResults['recovery'] | null;
  diagnostics: ApiResults['diagnostics'] | null;
  validation: ApiResults['validation'] | null;
};

const SESSION_STATUS_LABELS: Record<string, string> = {
  CREATED: '已创建',
  RUNNING: '运行中',
  WAITING_APPROVAL: '等待批准',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  PAUSED: '已暂停',
  INTERRUPTED: '已中断',
  MISSING: '会话不存在'
};

function sessionStatusLabel(status: string | null | undefined) {
  const key = String(status || '').trim().toUpperCase();
  return SESSION_STATUS_LABELS[key] || status || '未知';
}

export function DiagnosticsSettings() {
  const sessionId = useStore((state) => state.sessionId);
  const [data, setData] = useState<DiagnosticsSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState('');
  const [notice, setNotice] = useState<{ message: string; error?: boolean } | null>(null);
  const [exportNotice, setExportNotice] = useState<{ message: string; error?: boolean } | null>(null);
  const [includeSession, setIncludeSession] = useState(true);
  const [error, setError] = useState('');
  const sequence = useRef(0);

  const load = useCallback(async () => {
    const request = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const [maintenance, metrics, recovery, diagnostics, validation] = await Promise.all([
        api.maintenanceStatus(),
        api.metrics(),
        api.recovery(),
        sessionId ? api.diagnostics(sessionId) : Promise.resolve(null),
        sessionId ? api.validation(sessionId) : Promise.resolve(null)
      ]);
      if (request === sequence.current) setData({ maintenance, metrics, recovery, diagnostics, validation });
    } catch (err) {
      if (request === sequence.current) setError(errorInfo(err).message || '无法读取诊断信息。');
    } finally {
      if (request === sequence.current) setLoading(false);
    }
  }, [sessionId]);

  useEffect(() => {
    setData(null);
    void load();
    return () => { sequence.current++; };
  }, [load]);

  const run = async (kind: 'backup' | 'verify' | 'purge') => {
    if (busy) return;
    setBusy(kind);
    setNotice(null);
    try {
      if (kind === 'backup') {
        const report = await api.backupNow();
        setNotice(report?.ok
          ? { message: `已备份 ${report.files} 个文件到 ${report.directory}。${report.message}` }
          : { message: report?.message || '备份未完成。', error: true });
      } else if (kind === 'verify') {
        const report = await api.verifyBackup();
        setNotice(report?.valid
          ? { message: `${report.message}（${report.checkedFiles} 个文件）` }
          : { message: `${report?.message || '备份校验失败'}：${report?.failures?.join('；') || '没有可校验的备份'}`, error: true });
      } else {
        const report = await api.purgeNow();
        if (!report) throw new Error('服务端未确认清理结果，请刷新诊断信息。');
        setNotice({ message: `已清理 ${report.events ?? 0} 条事件、${report.snapshotRuns ?? 0} 轮快照、${report.traces ?? 0} 条调用记录、${report.backups ?? 0} 份备份和 ${report.validationFiles ?? 0} 个校验文件。` });
      }
      if (useStore.getState().sessionId === sessionId) await load();
    } catch (err) {
      setNotice({ message: '操作失败。' + errorInfo(err).message, error: true });
    } finally {
      setBusy('');
    }
  };

  const exportBundle = async () => {
    if (busy) return;
    setBusy('export');
    setExportNotice(null);
    try {
      const targetSession = sessionId && includeSession ? sessionId : null;
      const { blob, filename } = await api.exportDiagnostics(targetSession);
      if (typeof URL === 'undefined' || typeof URL.createObjectURL !== 'function') {
        throw new Error('当前环境不支持浏览器文件下载，请改用桌面客户端或直接请求 /api/diagnostics/export。');
      }
      const objectUrl = URL.createObjectURL(blob);
      try {
        const link = document.createElement('a');
        link.href = objectUrl;
        link.download = filename;
        link.rel = 'noopener';
        document.body.appendChild(link);
        link.click();
        link.remove();
      } finally {
        window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
      }
      setExportNotice({ message: `已导出脱敏诊断包：${filename}` });
    } catch (err) {
      setExportNotice({ message: errorInfo(err).message || '导出诊断包失败，请稍后重试。', error: true });
    } finally {
      setBusy('');
    }
  };

  const maintenance = data?.maintenance;
  const metrics = data?.metrics;
  const recovery = data?.recovery;
  const diagnostics = data?.diagnostics;
  const validation = data?.validation;
  const counters = Object.entries(metrics?.metrics?.counters || {}).sort((a, b) => b[1] - a[1]).slice(0, 8);

  return (
    <div id="settings-diagnostics" className="settings-diagnostics">
      <div className="settings-inline-actions">
        {loading && <p className="settings-status" role="status">正在读取诊断信息…</p>}
        {error && <p className="settings-status" role="alert">读取诊断失败。{error}{data && ' 当前显示上次读取的信息。'}</p>}
        <Button label={error ? '重新加载诊断' : '刷新诊断'} isDisabled={loading || Boolean(busy)} onClick={() => void load()} />
      </div>
      <section className="settings-group">
        <h3>脱敏诊断包</h3>
        <p className="chip-hint">
          自动剔除 API 密钥、令牌与密码等敏感信息，包含版本环境、启动恢复状态
          {sessionId && includeSession ? '以及当前对话最近 200 条脱敏事件与失败记录' : ''}，用于排障反馈。
        </p>
        <div className="settings-inline-actions">
          <Button id="export-diagnostics-btn" variant="primary"
            label={busy === 'export' ? '正在导出…' : '导出脱敏诊断包'}
            isDisabled={Boolean(busy)} onClick={() => void exportBundle()} />
          {sessionId && (
            <label className="settings-check">
              <input type="checkbox" checked={includeSession} disabled={Boolean(busy)}
                onChange={(event) => setIncludeSession(event.target.checked)} />
              <span>包含当前对话记录</span>
            </label>
          )}
        </div>
        {exportNotice && (
          <p className={exportNotice.error ? 'chip-hint is-error' : 'chip-hint'} role={exportNotice.error ? 'alert' : 'status'}>
            {exportNotice.message}
          </p>
        )}
      </section>
      {data && <>
        <section className="settings-group">
          <h3>存储与备份</h3>
          {maintenance ? (
            <>
              <dl className="diag-grid">
                <div><dt>数据目录</dt><dd className="diag-path" title={maintenance.backup?.dataDir}>{maintenance.backup?.dataDir || '—'}</dd></div>
                <div><dt>数据库</dt><dd>{formatBytes(maintenance.backup?.databaseBytes)}</dd></div>
                <div><dt>备份</dt><dd>{maintenance.backup?.backups ?? 0} 份</dd></div>
                <div><dt>上次备份</dt><dd>{maintenance.backup?.lastBackupAt || '从未'}</dd></div>
                <div><dt>保留策略</dt><dd>{maintenance.retentionDays > 0 ? `${maintenance.retentionDays} 天` : '不自动清理'}</dd></div>
                <div><dt>执行隔离</dt><dd>{maintenance.isolated ? '已启用' : (maintenance.degraded ? '降级运行' : '未启用')}</dd></div>
              </dl>
              {Boolean(maintenance.backup?.workspaces?.length) && (
                <dl className="diag-grid">
                  {maintenance.backup?.workspaces?.map(workspace => (
                    <div key={workspace.path}>
                      <dt>{workspace.kind === 'worktree' ? 'Git worktree' : '工作区'} · {workspace.included ? '已打包' : '需单独恢复文件'}</dt>
                      <dd className="diag-path" title={workspace.path}>{workspace.path}</dd>
                    </div>
                  ))}
                </dl>
              )}
              {!maintenance.storage?.healthy && (
                <p className="chip-hint is-error">
                  存储告警：{(maintenance.storage?.issues || []).map((issue) => issue.reason || issue.area || String(issue)).join('；') || '未知问题'}
                </p>
              )}
              {maintenance.degraded && maintenance.degradedReason && (
                <p className="chip-hint">隔离降级原因：{maintenance.degradedReason}</p>
              )}
              <div className="settings-inline-actions">
                <Button label={busy === 'backup' ? '备份中…' : '立即备份'} isDisabled={Boolean(busy)} onClick={() => void run('backup')} />
                <Button label={busy === 'verify' ? '校验中…' : '校验最近备份'} variant="ghost" isDisabled={Boolean(busy)} onClick={() => void run('verify')} />
                <Button label={busy === 'purge' ? '清理中…' : '清理过期数据'} variant="ghost" className="settings-danger"
                  isDisabled={Boolean(busy)} onClick={() => useStore.getState().askConfirm({
                    title: '清理过期数据',
                    message: '按保留策略删除过期事件、快照、调用记录、备份和校验文件。',
                    confirmText: '确认清理',
                    danger: true,
                    onConfirm: () => run('purge')
                  })} />
              </div>
              {notice && <p className="settings-status" role={notice.error ? 'alert' : 'status'}>{notice.message}</p>}
            </>
          ) : <p className="chip-hint">没有维护信息。</p>}
        </section>

        <section className="settings-group">
          <h3>运行指标</h3>
          {metrics?.metrics ? (
            <>
              <dl className="diag-grid">
                <div><dt>运行轮数</dt><dd>{metrics.metrics.runs}</dd></div>
                <div><dt>平均时长</dt><dd>{formatDuration(metrics.metrics.averageRunMs)}</dd></div>
                <div><dt>统计窗口</dt><dd>{metrics.retentionDays > 0 ? `${metrics.retentionDays} 天` : '全部'}</dd></div>
              </dl>
              {counters.length > 0 && (
                <ul className="diag-counters">
                  {counters.map(([name, value]) => (
                    <li key={name}><span>{name}</span><b>{formatCount(value)}</b></li>
                  ))}
                </ul>
              )}
            </>
          ) : <p className="chip-hint">还没有运行指标。</p>}
        </section>

        <section className="settings-group">
          <h3>恢复快照</h3>
          {recovery ? (
            <p className="chip-hint">
              {recovery.recoveredAt ? `上次启动：${recovery.recoveredAt}` : '本进程还没有执行过启动恢复'} ·
              {' '}接管 {recovery.entries?.length ?? 0} 条 · {recovery.instanceId || '—'}
              {recovery.note ? ` · ${recovery.note}` : ''}
            </p>
          ) : <p className="chip-hint">没有恢复记录。</p>}
        </section>

        <section className="settings-group">
          <h3>当前对话诊断</h3>
          {!sessionId && <p className="chip-hint">打开一个对话后可以看到它的卡点、重试与校验状态。</p>}
          {sessionId && !diagnostics && <p className="settings-status">这段对话还没有诊断记录。</p>}
          {diagnostics && (
            <>
              <dl className="diag-grid">
                <div><dt>状态</dt><dd>{sessionStatusLabel(diagnostics.sessionStatus)} · {verificationLabel(diagnostics.phase) || diagnostics.phase || '—'} 第 {diagnostics.step} 步</dd></div>
                <div><dt>上次成功</dt><dd>{diagnostics.lastSuccessfulStep || '—'}</dd></div>
                <div><dt>下一步</dt><dd>{diagnostics.nextAction || '—'}</dd></div>
                <div><dt>模型请求</dt><dd>{diagnostics.modelRequests} 次</dd></div>
                <div><dt>Token</dt><dd>{formatCount(diagnostics.inputTokens)} 输入 / {formatCount(diagnostics.outputTokens)} 输出{diagnostics.usageExact ? '' : '（估算）'}</dd></div>
                <div><dt>最近工具</dt><dd>{diagnostics.lastToolSummary || '—'}</dd></div>
              </dl>
              {diagnostics.retryAttempt != null && (
                <p className="chip-hint">
                  正在重试第 {diagnostics.retryAttempt} 次{diagnostics.retryDelayMs ? `，${Math.round(diagnostics.retryDelayMs / 1000)}s 后` : ''}
                  {diagnostics.retryReason ? `：${diagnostics.retryReason}` : ''}
                </p>
              )}
              {!!(diagnostics.commands || []).length && (
                <ul className="diag-commands">
                  {diagnostics.commands.map((task) => (
                    <li key={task.taskId}>
                      <code>{task.command}</code>
                      <span>{commandStatusLabel(task.status)}{task.exitCode != null ? ` · 退出码 ${task.exitCode}` : ''}</span>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
          {validation?.report && (
            <p className={`chip-hint${validation.report.current ? '' : ' is-error'}`}>
              校验证据：{verificationStatusLabel(statusFromCode(validation.report.code, validation.report.current))}
              {validation.report.reason ? `（${validation.report.reason}）` : ''}
            </p>
          )}
        </section>

        <PluginCompatSection sessionId={sessionId} />
      </>}
    </div>
  );
}

/**
 * 升级前体检：在升级之前先看清哪些插件会变、会坏。
 *
 * <p>只读接口：不会启动 MCP 服务器，也不会执行钩子。
 */
const COMPAT_LABELS: Record<string, string> = { ok: '正常', changed: '会变', broken: '会失效', unknown: '无法判断' };
const COMPAT_KIND_LABELS: Record<string, string> = { mcp: 'MCP 服务器', skill: '技能', hook: '钩子' };

function PluginCompatSection({ sessionId }: { sessionId: string | null }) {
  const [report, setReport] = useState<ApiResults['pluginCompat'] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);

  const inspect = async () => {
    setLoading(true);
    setError('');
    try {
      setReport(await api.pluginCompat(sessionId || undefined));
    } catch (err) {
      setError(errorInfo(err).message || '无法生成升级前体检报告。');
    } finally {
      setLoading(false);
    }
  };

  const copy = async () => {
    if (!report?.markdown) return;
    try {
      await writeClipboard(report.markdown);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      setError(errorInfo(err).message || '复制失败，可以手动选择报告文本。');
    }
  };

  return (
    <section className="settings-group" id="settings-plugin-compat">
      <h3>升级前体检</h3>
      <p className="chip-hint">
        在升级之前检查当前工作区的 MCP 服务器、技能与钩子。只读：不会启动服务器，也不会执行钩子。
      </p>
      <div className="diag-actions">
        <Button label={loading ? '正在检查…' : '检查兼容性'} isDisabled={loading} onClick={() => void inspect()} />
        {report?.markdown && <Button variant="ghost" label={copied ? '已复制' : '复制报告'} onClick={() => void copy()} />}
      </div>
      {error && <p className="settings-status" role="alert">{error}</p>}
      {report && (
        <>
          <p className={`chip-hint${report.counts.broken ? ' is-error' : ''}`}>
            正常 {report.counts.ok} · 会变 {report.counts.changed} · 会失效 {report.counts.broken} · 无法判断 {report.counts.unknown}
          </p>
          {!report.items.length
            ? <p className="chip-hint">当前工作区没有需要检查的插件。</p>
            : <ul className="diag-commands">
                {report.items.map((item) => (
                  <li key={`${item.kind}:${item.name}`} data-compat-status={item.status}>
                    <code>{COMPAT_KIND_LABELS[item.kind] || item.kind} · {item.name}</code>
                    <span>{COMPAT_LABELS[item.status] || item.status} · {item.reason}</span>
                    {!!item.details.length && (
                      <span>{item.details.join('；')}</span>
                    )}
                  </li>
                ))}
              </ul>}
        </>
      )}
    </section>
  );
}

function formatBytes(value: unknown) {
  if (value == null) return '—';
  const size = Number(value) || 0;
  if (size <= 0) return '0 B';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function formatDuration(ms: unknown) {
  if (ms == null) return '—';
  const value = Number(ms) || 0;
  if (value < 1000) return `${Math.round(value)} ms`;
  if (value < 60000) return `${(value / 1000).toFixed(1)} s`;
  return `${Math.floor(value / 60000)} 分 ${Math.round((value % 60000) / 1000)} 秒`;
}
