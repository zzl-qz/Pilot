import { useEffect, useMemo } from 'react';
import { useStore } from '../../store.js';

/* ---------- 能力清单 ---------- */

const NO_TASKS: never[] = [];

const PERMISSION_LABELS: Record<string, string> = {
  AUTO: '完全放行',
  AUTO_EDIT: '自动改文件',
  ASK: '每次确认',
  READ_ONLY: '只读'
};

export function CapabilitiesPanel() {
  const sessionId = useStore((state) => state.sessionId);
  const data = useStore((state) => state.capabilities);
  const state = useStore((state) => state.capabilitiesState);
  const skills = useStore((state) => state.skills || NO_TASKS);

  useEffect(() => {
    if (sessionId) {
      useStore.getState().loadCapabilities();
    }
  }, [sessionId]);

  // 可用的排前面：不可用的一多，最该看到的东西会被挤到列表底部
  const tools = useMemo(() => (Array.isArray(data?.tools) ? [...data.tools] : [])
    .sort((a, b) => Number(b.available) - Number(a.available) || String(a.name).localeCompare(String(b.name))), [data]);
  const servers = Array.isArray(data?.mcpServers) ? data.mcpServers : [];
  const localCapabilities = Array.isArray(data?.localCapabilities) ? data.localCapabilities : [];
  const available = tools.filter((tool) => tool.available).length;

  if (!sessionId) {
    return <p className="panel-empty">开始一个任务后，这里会列出当前可用的工具、技能与 MCP 服务器。</p>;
  }

  return (
    <div id="panel-capabilities" className="panel">
      <div className="panel-toolbar">
        <span className="commands-state">
          {state === 'loading' ? '读取中…' : `${available} / ${tools.length} 个工具可用`}
        </span>
        <button type="button" className="btn" onClick={() => useStore.getState().loadCapabilities()}>刷新</button>
      </div>
      <div className="cap-scroll">
        <section className="cap-section">
          <h3>运行环境</h3>
          <dl className="cap-kv">
            <div><dt>模型</dt><dd>{data?.model || '—'}</dd></div>
            <div><dt>权限</dt><dd>{PERMISSION_LABELS[data?.permissionMode || ''] || data?.permissionMode || '—'}</dd></div>
            <div><dt>工作区</dt><dd className="cap-path" title={data?.workspace || ''}>{data?.workspace || '—'}</dd></div>
          </dl>
        </section>
        <section className="cap-section">
          <h3>工具<span className="cap-count">{tools.length}</span></h3>
          {tools.length ? (
            <ul className="cap-list">
              {tools.map((tool) => (
                <li key={tool.name} className={`cap-item${tool.available ? '' : ' is-off'}`}>
                  <div className="cap-item-head">
                    <code className="cap-name">{tool.name}</code>
                    <span className={`cap-tag${tool.readOnly ? ' is-readonly' : ''}`}>{tool.readOnly ? '只读' : '可写'}</span>
                    {!tool.available && <span className="cap-tag is-off">不可用</span>}
                  </div>
                  {tool.description && <p className="cap-desc" title={tool.description}>{tool.description}</p>}
                  {!tool.available && tool.reason && <p className="cap-reason">{tool.reason}</p>}
                </li>
              ))}
            </ul>
          ) : <p className="panel-empty">没有注册任何工具。</p>}
        </section>
        <section className="cap-section">
          <h3>本机资源<span className="cap-count">{localCapabilities.filter((item) => item.available).length} / {localCapabilities.length}</span></h3>
          <p className="cap-desc">只检查已存在的程序，不会启动、下载或安装。需要补充环境时，先确认任务是否有可用替代方案。</p>
          {localCapabilities.length ? (
            <ul className="cap-list">
              {localCapabilities.map((item) => (
                <li key={item.id} className={`cap-item${item.available ? '' : ' is-off'}`}>
                  <div className="cap-item-head">
                    <span className="cap-name">{item.name}</span>
                    <span className={`cap-tag${item.available ? ' is-on' : ' is-off'}`}>{item.available ? '已发现' : '未发现'}</span>
                  </div>
                  <p className="cap-desc">{item.purpose}</p>
                  {item.available ? (
                    <p className="cap-desc cap-mono" title={item.executable}>{item.executable}</p>
                  ) : (
                    <p className="cap-reason">{item.preparationAdvice}</p>
                  )}
                </li>
              ))}
            </ul>
          ) : <p className="panel-empty">本机资源清单尚未加载。</p>}
        </section>
        <section className="cap-section">
          <h3>MCP 服务器<span className="cap-count">{servers.length}</span></h3>
          {servers.length ? (
            <ul className="cap-list">
              {servers.map((server) => (
                <li key={server.name} className="cap-item">
                  <div className="cap-item-head">
                    <code className="cap-name">{server.name}</code>
                    <span className={`cap-tag${server.running ? ' is-on' : ''}`}>{server.running ? '运行中' : (server.usable ? '未启动' : '不可用')}</span>
                    {server.running && <span className="cap-tag is-readonly">{server.tools?.length || 0} 个工具</span>}
                  </div>
                  {server.command && <p className="cap-desc cap-mono">{server.command}</p>}
                  {server.unsupported && <p className="cap-reason">{server.unsupported}</p>}
                  {server.failure && <p className="cap-reason">上次启动失败：{server.failure}</p>}
                </li>
              ))}
            </ul>
          ) : (
            <p className="panel-empty">没有配置 MCP 服务器。在工作区放一个 .mcp.json 就能接上外部工具。</p>
          )}
        </section>
        <section className="cap-section">
          <h3>技能<span className="cap-count">{skills.length}</span></h3>
          {skills.length ? (
            <ul className="cap-list">
              {skills.map((skill) => (
                <li key={skill.name} className="cap-item">
                  <div className="cap-item-head">
                    <code className="cap-name">/{skill.command || skill.name}</code>
                    {skill.source && <span className="cap-tag">{skill.source === 'project' ? '项目级' : '用户级'}</span>}
                  </div>
                  {skill.description && <p className="cap-desc" title={skill.description}>{skill.description}</p>}
                </li>
              ))}
            </ul>
          ) : <p className="panel-empty">还没有安装技能。在设置 → 技能里可以从 git 仓库安装。</p>}
        </section>
        {state === 'error' && <p className="panel-empty">读取能力清单失败，右上角「刷新」再试一次。</p>}
      </div>
    </div>
  );
}
