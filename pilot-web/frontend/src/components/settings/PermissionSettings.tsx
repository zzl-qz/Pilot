import type * as S from "../../types/server.js";
import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { errorInfo } from "../../lib/errors.js";
import { PERMISSION_MODES, permissionLabel } from "../../lib/permission.js";
import { useStore } from "../../store.js";
import { api } from "../../api.js";
import { Icon } from "../Icon.jsx";

export function PermissionSettings() {
  const mode = useStore(state => state.permissionMode);
  const workspace = useStore(state => state.workspace);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  async function choose(next: string) {
    if (next === mode || busy) return;
    setBusy(true);
    setNotice('');
    try {
      await useStore.getState().savePermissionMode(next);
      setNotice(useStore.getState().ui.notice?.text || '');
    } finally { setBusy(false); }
  }
  return (
    <section className="settings-group">
      <div className="settings-group-heading">
        <h3>权限模式</h3>
        <p>当前为 {permissionLabel(mode)}。切换后即时保存。</p>
      </div>
      <div className="settings-choices" id="permission-options" role="group" aria-label="权限模式" aria-busy={busy}>
        {PERMISSION_MODES.map(item => <button key={item.mode} type="button" aria-pressed={mode === item.mode}
          disabled={busy} className="settings-choice" onClick={() => void choose(item.mode)}>
          <span><strong>{item.label}</strong><span>{item.hint}</span></span>
          {mode === item.mode && <Icon name="check" />}
        </button>)}
      </div>
      <p id="settings-permission-hint" className="settings-status">请仅在可信工作区使用「完全放行」。</p>
      {notice && <p className="settings-status" role={notice.startsWith('保存权限模式失败') ? 'alert' : 'status'}>{notice}</p>}
      <PermissionRuleEditor workspace={workspace} />
    </section>
  );
}

/** 三类规则的展示顺序：拒绝先于询问，询问先于放行，和判定顺序一致。 */
const RULE_FIELDS = [
  { key: 'deny', label: '拒绝', hint: '命中即拦下，优先于询问和放行' },
  { key: 'ask', label: '每次询问', hint: '命中后弹出批准，由你决定这一次' },
  { key: 'allow', label: '放行', hint: '命中后不再弹批准' }
] as const;

type RuleKey = typeof RULE_FIELDS[number]['key'];
type RuleDraft = Record<RuleKey, string>;

function ruleText(rules: readonly string[] | undefined) {
  return (rules || []).join('\n');
}

function toDraft(rules: S.PermissionRuleSet | null | undefined): RuleDraft {
  return { allow: ruleText(rules?.allow), deny: ruleText(rules?.deny), ask: ruleText(rules?.ask) };
}

/** 一行一条规则；空行忽略，避免手写时多敲的回车变成一条空规则。 */
function splitRules(text: string) {
  return text.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
}

function PermissionRuleEditor({ workspace }: { workspace: string }) {
  const [view, setView] = useState<S.PermissionRulesControllerRulesResponse | null>(null);
  const [draft, setDraft] = useState<RuleDraft | null>(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<{ message: string; error?: boolean } | null>(null);
  const sequence = useRef(0);

  const load = useCallback(async () => {
    const request = ++sequence.current;
    if (!workspace) { setView(null); setDraft(null); return; }
    try {
      const data = await api.permissionRules(workspace);
      if (request !== sequence.current) return;
      setView(data);
      setDraft(data ? toDraft(data) : null);
    } catch (failure) {
      if (request === sequence.current) setStatus({ message: errorInfo(failure).message || '无法读取权限规则。', error: true });
    }
  }, [workspace]);
  useEffect(() => { void load(); return () => { sequence.current++; }; }, [load]);

  function update(key: RuleKey, value: string) {
    setDraft(current => (current ? { ...current, [key]: value } : current));
  }

  async function save() {
    if (!workspace || !draft || busy) return;
    setBusy(true);
    setStatus(null);
    try {
      const data = await api.savePermissionRules({
        workspace, allow: splitRules(draft.allow), deny: splitRules(draft.deny), ask: splitRules(draft.ask)
      });
      setView(data);
      setDraft(toDraft(data));
      setStatus({ message: '已写入 ' + (data?.file || workspace) + '，对后续工具调用立即生效。' });
    } catch (failure) {
      setStatus({ message: '保存规则失败：' + (errorInfo(failure).message || '无法连接服务'), error: true });
    } finally { setBusy(false); }
  }

  const inherited = view?.inherited;
  const inheritedCount = inherited ? inherited.allow.length + inherited.deny.length + inherited.ask.length : 0;
  return <div className="permission-rules">
    <div className="settings-group-heading">
      <h3>命令与路径规则</h3>
      <p>每行一条，规则同时命中时按「拒绝 → 询问 → 放行」判定；三类都空着就只看上面的权限模式。</p>
    </div>
    {!workspace && <p className="settings-status">先选定工作区目录，规则会写进该目录下的 .pilot/settings.json。</p>}
    {view && <p className="settings-status">当前规则文件 {view.file}</p>}
    {!!view?.loadIssues.length && <p className="settings-status" role="alert">规则未生效：{view.loadIssues.join('；')}</p>}
    {draft && <div className="permission-rule-grid">
      {RULE_FIELDS.map(field => <label key={field.key} className="permission-rule-field">
        <span className="mcp-env-label">{field.label}</span>
        <textarea className="permission-rule-input" value={draft[field.key]} spellCheck={false} disabled={busy}
          aria-label={field.label + '规则'} placeholder={field.hint}
          onChange={event => update(field.key, event.target.value)} />
      </label>)}
    </div>}
    <div className="settings-form-actions">
      <Button label={busy ? '正在保存…' : '保存规则'} isDisabled={busy || !draft} onClick={() => void save()} />
      <Button label="重新读取" variant="ghost" isDisabled={busy || !workspace} onClick={() => void load()} />
    </div>
    {inheritedCount > 0 && inherited && <div className="permission-inherited">
      <p className="mcp-env-label">用户级 ~/.pilot/settings.json 另有 {inheritedCount} 条规则同样生效（只读）</p>
      <ul className="permission-inherited-list">
        {RULE_FIELDS.filter(field => inherited[field.key].length > 0).map(field => <li key={field.key}>
          <b>{field.label}</b>{inherited[field.key].join('、')}
        </li>)}
      </ul>
    </div>}
    {status && <p className="settings-status" role={status.error ? 'alert' : 'status'}>{status.message}</p>}
  </div>;
}
