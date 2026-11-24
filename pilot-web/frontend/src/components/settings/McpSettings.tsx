import type * as S from "../../types/server.js";
import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { SegmentedControl, SegmentedControlItem } from "@astryxdesign/core/SegmentedControl";
import { errorInfo } from "../../lib/errors.js";
import { MCP_TRUST_LABELS, mcpSourceLabel } from "../../lib/mcp.js";
import { useStore } from "../../store.js";
import { api } from "../../api.js";

/* ---------- MCP 服务器与信任管理 ---------- */

type McpProbeState = { loading: boolean; ok: boolean; error: string; running: boolean;
  tools: S.McpConfigControllerProbeTool[] };

export function McpSettings() {
  const sessionId = useStore(state => state.sessionId);
  const [servers, setServers] = useState<S.McpConfigControllerServerPayload[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [hint, setHint] = useState('');
  const [hasError, setHasError] = useState(false);
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState('');
  const [probes, setProbes] = useState<Record<string, McpProbeState>>({});
  const sequence = useRef(0);

  const load = useCallback(async () => {
    if (!sessionId) return;
    const request = ++sequence.current;
    setLoading(true);
    setError('');
    try {
      const data = await api.mcpServers(sessionId);
      if (request !== sequence.current) return;
      setServers(Array.isArray(data) ? data : []);
    } catch (failure) {
      if (request === sequence.current) setError(errorInfo(failure).message || '无法读取 MCP 服务器列表。');
    } finally {
      if (request === sequence.current) setLoading(false);
    }
  }, [sessionId]);
  useEffect(() => {
    if (sessionId) void load();
    else { setServers(null); setLoading(false); }
    return () => { sequence.current++; };
  }, [load, sessionId]);

  /** 测试连接：真正拉起进程并读工具清单。用户点按钮即授权这次启动；信任需另行确认。 */
  async function probe(server: string) {
    if (!sessionId || busy) return;
    setBusy(server);
    setHasError(false);
    setProbes(current => ({ ...current, [server]: { loading: true, ok: false, error: '', running: false, tools: [] } }));
    try {
      const data = await api.mcpProbeServer(sessionId, server);
      if (data) {
        setProbes(current => ({ ...current, [server]: { loading: false, ok: data.ok, error: data.error, running: data.running, tools: data.tools || [] } }));
        setHint(data.ok
          ? `${server} 连接成功，声明 ${data.tools?.length || 0} 个工具${data.running ? '' : '（进程未保持运行）'}。`
          : `${server} 连接失败：${data.error}`);
        setHasError(!data.ok);
      }
      await load();  // 启动状态可能变化
    } catch (failure) {
      const reason = errorInfo(failure).message || '';
      setHasError(true);
      setHint('测试失败。' + reason);
      setProbes(current => ({ ...current, [server]: { loading: false, ok: false, error: reason, running: false, tools: [] } }));
    } finally {
      setBusy('');
    }
  }

  async function trust(server: S.McpConfigControllerServerPayload) {
    if (!sessionId || busy) return;
    setBusy(server.name);
    setHint('正在记住信任…');
    setHasError(false);
    try {
      await api.mcpTrust(sessionId, { action: 'trust', server: server.name, configHash: server.configHash });
      setHint(server.trustState === 'STALE'
        ? `已按当前命令重新信任 ${server.name}；首次启动后钉住工具定义。`
        : `已信任 ${server.name}；首次启动后钉住工具定义，声明只读的工具不再逐次询问。`);
      await load();
      void useStore.getState().loadCapabilities();
    } catch (failure) {
      setHasError(true);
      setHint('信任失败。' + errorInfo(failure).message);
    } finally {
      setBusy('');
    }
  }

  async function remove(server: string) {
    if (!sessionId || busy) return;
    setBusy(server);
    setHint('正在删除…');
    setHasError(false);
    try {
      await api.mcpDeleteServer(sessionId, server);
      setHint(`已从用户级配置删除 ${server}，它的信任记录也已撤销。`);
      await load();
      void useStore.getState().loadCapabilities();
    } catch (failure) {
      setHasError(true);
      setHint('删除失败。' + errorInfo(failure).message);
    } finally {
      setBusy('');
    }
  }

  async function revoke(server: string) {
    if (!sessionId || busy) return;
    setBusy(server);
    setHint('正在撤销信任…');
    setHasError(false);
    try {
      await api.mcpTrust(sessionId, { action: 'revoke', server });
      setHint(`已撤销 ${server} 的信任；它的启动与调用会回到逐次审批。`);
      await load();
      void useStore.getState().loadCapabilities();
    } catch (failure) {
      setHasError(true);
      setHint('撤销失败。' + errorInfo(failure).message);
    } finally {
      setBusy('');
    }
  }

  return <section className="settings-group" aria-labelledby="mcp-heading">
    <div className="settings-group-heading" id="mcp-heading">
      <h3>MCP 服务器</h3>
      <p>添加后先用「测试连接」验证，测试通过再决定是否信任；未信任时模型每次调用都会请求批准。</p>
    </div>
    {!sessionId && <p className="settings-status" role="status">开始一个对话后即可管理当前工作区的 MCP 服务器。</p>}
    {sessionId && <div className="settings-inline-actions">
      <Button id="mcp-add-open" variant="primary" label={adding ? '收起表单' : '添加服务器…'} aria-expanded={adding}
        aria-controls={adding ? 'mcp-add-form' : undefined} onClick={() => setAdding(open => !open)} />
      <Button label="刷新" isDisabled={loading || Boolean(busy)} onClick={() => void load()} />
    </div>}
    {sessionId && loading && <p className="settings-status" role="status">正在读取 MCP 服务器…</p>}
    {sessionId && !loading && error && <div className="settings-inline-actions">
      <p className="settings-status" role="alert">读取失败。{error}</p>
      <Button label="重新加载" onClick={() => void load()} />
    </div>}
    {sessionId && !loading && servers && <ul className="mcp-server-list" id="settings-mcp-list" aria-busy={loading}>
      {servers.map(server => {
        const probeState = probes[server.name];
        return <li key={server.name} className="mcp-server-item" data-source={server.source}>
          <div className="mcp-server-head">
            <code className="mcp-server-name">{server.name}</code>
            <span className={`mcp-source-tag is-${server.source}`}>{mcpSourceLabel(server.source)}</span>
            <span className={`cap-tag${server.running ? ' is-on' : ' is-off'}`}>
              {server.running ? `运行中 · ${server.toolCount} 个工具` : (server.usable ? '未启动' : '不可用')}
            </span>
            {server.usable && <span className={`cap-tag${server.trustState === 'STALE' ? ' is-stale' : server.trustState !== 'UNTRUSTED' ? ' is-trusted' : ' is-warn'}`}>
              {MCP_TRUST_LABELS[server.trustState] || server.trustState}
            </span>}
            <span className="mcp-server-actions">
              {server.usable && <Button
                label={probeState?.loading || busy === server.name ? '测试中…' : '测试连接'} size="sm" variant="ghost"
                isDisabled={Boolean(busy)} onClick={() => void probe(server.name)} />}
              {server.usable && (server.trustState === 'UNTRUSTED' || server.trustState === 'STALE') && <Button
                label={busy === server.name ? '处理中…' : server.trustState === 'STALE' ? '重新信任' : '信任此服务器'}
                size="sm" variant="ghost"
                isDisabled={Boolean(busy)} onClick={() => void trust(server)} />}
              {server.trustState !== 'UNTRUSTED' && <Button
                label={busy === server.name ? '处理中…' : '撤销信任'} size="sm" variant="ghost" className="settings-danger"
                isDisabled={Boolean(busy)} onClick={() => void revoke(server.name)} />}
              {server.source === 'user' && <Button
                label={busy === server.name ? '处理中…' : '删除'} size="sm" variant="ghost"
                isDisabled={Boolean(busy)}
                onClick={() => useStore.getState().askConfirm({
                  title: '删除 MCP 服务器', message: `从用户级配置删除 ${server.name}，并撤销它的信任记录；已启动的连接会在下次启动时停用。`, confirmText: '删除', danger: true,
                  onConfirm: () => void remove(server.name)
                })} />}
            </span>
          </div>
          {server.command && <p className="cap-desc cap-mono" title={server.command}>{server.command}</p>}
          {!server.usable && server.unsupported && <p className="cap-reason">{server.unsupported}</p>}
          {server.usable && !server.running && server.failure && <p className="cap-reason">上次启动失败：{server.failure}</p>}
          {server.trustNote && <p className="cap-reason">{server.trustNote}</p>}
          {probeState && !probeState.loading && !probeState.ok && <p className="cap-reason">测试连接失败：{probeState.error}</p>}
          {probeState?.ok && probeState.tools.length > 0 && <details className="mcp-probe-tools">
            <summary>已声明 {probeState.tools.length} 个工具</summary>
            <ul className="mcp-tool-list">
              {probeState.tools.map(tool => <li key={tool.name}>
                <code>{tool.name}</code>
                {tool.readOnly && <span className="cap-tag">只读</span>}
                {tool.description && <span className="mcp-tool-desc">{tool.description}</span>}
              </li>)}
            </ul>
          </details>}
        </li>;
      })}
      {!servers.length && <li className="panel-empty">还没有配置 MCP 服务器。点「添加服务器」接入外部工具，或在项目根放一个 .mcp.json。</li>}
    </ul>}
    {sessionId && adding && <McpAddServer onDone={(message) => { setAdding(false); if (message) { setHint(message); setHasError(false); } void load(); }} />}
    {hint && <p className="settings-status" role={hasError ? 'alert' : 'status'}>{hint}</p>}
  </section>;
}

const MCP_IMPORT_PLACEHOLDER = `{
  "local-fs": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "."]
  },
  "remote-docs": {
    "type": "http",
    "url": "https://example.com/mcp"
  }
}`;

/** 添加表单：双模式。「表单」按字段填写（支持本地 stdio 与远程 HTTP / SSE）；「粘贴 JSON」直接导入现成配置。 */
function McpAddServer({ onDone }: { onDone: (message?: string) => void }) {
  const [mode, setMode] = useState<'form' | 'json'>('form');
  return <div className="mcp-add-wrap" id="mcp-add-form">
    <SegmentedControl id="mcp-add-mode" label="添加方式" value={mode} onChange={value => setMode(value as 'form' | 'json')}>
      <SegmentedControlItem value="form" label="表单填写" />
      <SegmentedControlItem value="json" label="粘贴 JSON" />
    </SegmentedControl>
    {mode === 'form' ? <McpAddForm onDone={onDone} /> : <McpImportForm onDone={onDone} />}
  </div>;
}

/** 表单模式：字段逐项填写，支持本地命令（stdio）与远程 Streamable HTTP / SSE，解析与校验在后端。 */
function McpAddForm({ onDone }: { onDone: (message?: string) => void }) {
  const sessionId = useStore(state => state.sessionId);
  const [transport, setTransport] = useState<'stdio' | 'http' | 'sse'>('stdio');
  const [name, setName] = useState('');
  const [command, setCommand] = useState('');
  const [args, setArgs] = useState('');
  const [url, setUrl] = useState('');
  const [envRows, setEnvRows] = useState<Array<{ key: string; value: string }>>([]);
  const [headerRows, setHeaderRows] = useState<Array<{ key: string; value: string }>>([]);
  const [busy, setBusy] = useState(false);
  const [hint, setHint] = useState('选择本地命令（stdio）或远程 HTTP / SSE 地址后保存。');
  const [hasError, setHasError] = useState(false);
  const isRemote = transport !== 'stdio';

  async function submit() {
    if (!sessionId || busy) return;
    setBusy(true);
    setHasError(false);
    setHint('正在保存…');
    try {
      let created;
      if (isRemote) {
        const headers: Record<string, string> = {};
        for (const row of headerRows) {
          if (row.key.trim() && row.value) headers[row.key.trim()] = row.value;
        }
        created = await api.mcpAddServer(sessionId, {
          name: name.trim(),
          transport,
          url: url.trim(),
          headers
        });
      } else {
        const env: Record<string, string> = {};
        for (const row of envRows) {
          if (row.key.trim() && row.value) env[row.key.trim()] = row.value;
        }
        created = await api.mcpAddServer(sessionId, { name: name.trim(), transport: 'stdio', command: command.trim(), args, env });
      }
      setName(''); setCommand(''); setArgs(''); setUrl(''); setEnvRows([]); setHeaderRows([]);
      onDone(`已保存 ${created?.name || name.trim()}；先点「测试连接」验证，或在对话中首次使用时批准。`);
    } catch (failure) {
      setHasError(true);
      setHint('保存失败。' + errorInfo(failure).message);
    } finally {
      setBusy(false);
    }
  }

  const canSubmit = Boolean(name.trim() && (isRemote ? url.trim() : command.trim()) && !busy);

  return <form className="settings-form mcp-add-form" aria-label="添加 MCP 服务器" onSubmit={event => {
    event.preventDefault();
    if (canSubmit) void submit();
  }} onKeyDown={event => {
    if (event.key === 'Enter' && (event.nativeEvent.isComposing || event.keyCode === 229)) event.preventDefault();
  }}>
    <SegmentedControl id="mcp-add-transport" label="传输方式" value={transport}
      onChange={value => setTransport(value as 'stdio' | 'http' | 'sse')}>
      <SegmentedControlItem value="stdio" label="本地命令 (stdio)" />
      <SegmentedControlItem value="http" label="远程 Streamable HTTP" />
      <SegmentedControlItem value="sse" label="远程 SSE" />
    </SegmentedControl>
    {!isRemote ? <>
      <div className="mcp-add-fields">
        <TextInput id="mcp-add-name" label="名称" value={name} placeholder="filesystem" isDisabled={busy}
          hasSpellCheck={false} onChange={setName} />
        <TextInput id="mcp-add-command" label="命令" value={command} placeholder="npx / node / uvx" isDisabled={busy}
          hasSpellCheck={false} onChange={setCommand} />
      </div>
      <TextInput id="mcp-add-args" label="参数（空格分隔，含空格的用引号包住；也可以填 JSON 数组）" width="100%"
        value={args} placeholder={'-y @modelcontextprotocol/server-filesystem "D:\\my dir"'} isDisabled={busy}
        hasSpellCheck={false} onChange={setArgs} />
      <div className="mcp-env-editor">
        <p className="mcp-env-label">环境变量（可选）</p>
        {envRows.map((row, index) => <div key={index} className="mcp-env-row">
          <TextInput label={`名称（变量 ${index + 1}）`} value={row.key} isDisabled={busy} hasSpellCheck={false}
            onChange={value => setEnvRows(rows => rows.map((item, i) => i === index ? { ...item, key: value } : item))} />
          <TextInput label={`值（变量 ${index + 1}）`} value={row.value} isDisabled={busy}
            onChange={value => setEnvRows(rows => rows.map((item, i) => i === index ? { ...item, value } : item))} />
          <Button type="button" label={`移除变量 ${index + 1}`} variant="ghost" isDisabled={busy}
            onClick={() => setEnvRows(rows => rows.filter((_, i) => i !== index))} />
        </div>)}
        <Button type="button" label="添加变量" variant="ghost" isDisabled={busy}
          onClick={() => setEnvRows(rows => [...rows, { key: '', value: '' }])} />
      </div>
    </> : <>
      <div className="mcp-add-fields">
        <TextInput id="mcp-add-name" label="名称" value={name} placeholder="remote-docs" isDisabled={busy}
          hasSpellCheck={false} onChange={setName} />
        <TextInput id="mcp-add-url" label="服务器 URL" value={url} inputMode="url"
          placeholder={transport === 'sse' ? 'https://example.com/sse' : 'https://example.com/mcp'} isDisabled={busy}
          hasSpellCheck={false} onChange={setUrl} />
      </div>
      <div className="mcp-env-editor">
        <p className="mcp-env-label">自定义请求头（可选，公开或免鉴权服务可留空）</p>
        {headerRows.map((row, index) => <div key={index} className="mcp-env-row">
          <TextInput label={`Header 名（${index + 1}）`} value={row.key} placeholder="Authorization" isDisabled={busy} hasSpellCheck={false}
            onChange={value => setHeaderRows(rows => rows.map((item, i) => i === index ? { ...item, key: value } : item))} />
          <TextInput label={`Header 值（${index + 1}）`} value={row.value} type="password" isDisabled={busy}
            onChange={value => setHeaderRows(rows => rows.map((item, i) => i === index ? { ...item, value } : item))} />
          <Button type="button" label={`移除请求头 ${index + 1}`} variant="ghost" isDisabled={busy}
            onClick={() => setHeaderRows(rows => rows.filter((_, i) => i !== index))} />
        </div>)}
        <Button type="button" label="添加请求头" variant="ghost" isDisabled={busy}
          onClick={() => setHeaderRows(rows => [...rows, { key: '', value: '' }])} />
      </div>
    </>}
    <div className="settings-inline-actions">
      <Button id="mcp-add-submit" variant="primary" label={busy ? '保存中…' : '保存服务器'}
        isDisabled={!canSubmit} onClick={() => void submit()} />
    </div>
    <p className="settings-status" role={hasError ? 'alert' : 'status'}>{hint}</p>
  </form>;
}

/** 粘贴 JSON 模式：支持 {"服务器名": {…}} 或 {"mcpServers": {"服务器名": {…}}}，可以一次导入多个。 */
function McpImportForm({ onDone }: { onDone: (message?: string) => void }) {
  const sessionId = useStore(state => state.sessionId);
  const [config, setConfig] = useState('');
  const [busy, setBusy] = useState(false);
  const [hint, setHint] = useState('把现成配置直接粘贴进来；与用户级同名的条目会被覆盖。');
  const [hasError, setHasError] = useState(false);

  async function submit(confirmed = false) {
    if (!sessionId || busy) return;
    setBusy(true);
    setHasError(false);
    setHint('正在导入…');
    try {
      const result = await api.mcpImportServers(sessionId, { config, confirmOverwrite: confirmed });
      const imported = result?.imported || [];
      const overwritten = result?.overwritten || [];
      const parts = [`已导入 ${imported.join('、')}`];
      if (overwritten.length) parts.push(`其中覆盖了同名项 ${overwritten.join('、')}`);
      setConfig('');
      onDone(parts.join('，') + '；首次启动时仍会请求批准。');
    } catch (failure) {
      const message = errorInfo(failure).message || '';
      if (!confirmed && message.includes('覆盖同名')) {
        setBusy(false);
        setHint('等待确认是否覆盖同名配置…');
        useStore.getState().askConfirm({
          title: '覆盖同名 MCP 配置',
          message,
          confirmText: '覆盖并导入',
          danger: true,
          onConfirm: () => void submit(true)
        });
        return;
      }
      setHasError(true);
      setHint('导入失败。' + message);
    } finally {
      setBusy(false);
    }
  }

  return <form className="settings-form mcp-add-form" id="mcp-import-form" onSubmit={event => {
    event.preventDefault();
    if (config.trim() && !busy) void submit();
  }}>
    <label className="mcp-env-label" htmlFor="mcp-import-config">完整配置</label>
    <textarea id="mcp-import-config" className="mcp-json-input" value={config} placeholder={MCP_IMPORT_PLACEHOLDER}
      spellCheck={false} aria-label="MCP 完整配置 JSON" disabled={busy} rows={8}
      onChange={event => setConfig(event.target.value)} />
    <p className="mcp-import-hint">支持直接粘贴 {"{"}"服务器名": {"{"}…{"}"}{"}"} 或 {"{"}"mcpServers": {"{"}"服务器名": {"{"}…{"}"}{"}"}{"}"}；支持本地 stdio（command/args/env）与远程 HTTP / SSE（url/headers/type），pilotPermissions 等本地授权字段会被拒绝。</p>
    <div className="settings-inline-actions">
      <Button id="mcp-import-submit" variant="primary" label={busy ? '导入中…' : '导入'}
        isDisabled={busy || !config.trim()} onClick={() => void submit()} />
    </div>
    <p className="settings-status" role={hasError ? 'alert' : 'status'}>{hint}</p>
  </form>;
}
