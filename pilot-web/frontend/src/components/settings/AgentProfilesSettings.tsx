import type * as S from "../../types/server.js";
import { useEffect, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { api } from "../../api.js";
import { errorInfo } from "../../lib/errors.js";
import { useStore } from "../../store.js";
import {
  agentSourceLabel,
  createAgentDraft,
  editAgentDraft,
  overrideAgentDraft,
  summarizeAgentBoundaries,
  validateAgentProfileDraft,
  type AgentDraftState
} from "./agent-profiles-state.js";

export function AgentProfilesSettings() {
  const sessionId = useStore(state => state.sessionId);
  const storedProfiles = useStore(state => state.agentProfiles);
  const meta = useStore(state => state.agentProfilesMeta);
  const profiles = Array.isArray(storedProfiles) ? storedProfiles : [];
  const invalid = Array.isArray(meta?.invalid) ? meta.invalid : [];
  const [draft, setDraft] = useState<AgentDraftState | null>(null);
  const [previewName, setPreviewName] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [failure, setFailure] = useState(false);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let live = true;
    setLoading(true);
    void useStore.getState().loadAgentProfiles().finally(() => {
      if (live) setLoading(false);
    });
    return () => { live = false; };
  }, [sessionId, refresh]);

  function openDraft(next: AgentDraftState) {
    setDraft(next);
    setPreviewName("");
    setStatus("");
    setFailure(false);
  }

  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (busy || !draft) return;
    const trimmedName = draft.name.trim();
    const problem = validateAgentProfileDraft(trimmedName, draft.content);
    if (problem) {
      setFailure(true);
      setStatus(problem);
      return;
    }
    setBusy(true);
    setFailure(false);
    setStatus("");
    try {
      await api.saveAgentProfile(trimmedName, draft.content);
      const projectShadowNote = draft.mode === "override" && draft.source === "project"
        ? "（当前项目级同名定义仍优先于用户级生效）"
        : "";
      setStatus(`已保存用户级子代理「${trimmedName}」${projectShadowNote}。`);
      setDraft(null);
      setRefresh(value => value + 1);
    } catch (error) {
      setFailure(true);
      setStatus("保存失败。" + errorInfo(error).message);
    } finally {
      setBusy(false);
    }
  }

  async function removeConfirmed(profile: S.AgentProfilesControllerProfilePayload) {
    if (busy) return;
    setBusy(true);
    setFailure(false);
    setStatus("");
    try {
      await api.deleteAgentProfile(profile.name);
      if (draft?.name === profile.name) setDraft(null);
      if (previewName === profile.name) setPreviewName("");
      setStatus(`已删除用户级子代理「${profile.name}」。`);
      setRefresh(value => value + 1);
    } catch (error) {
      setFailure(true);
      setStatus("删除失败。" + errorInfo(error).message);
    } finally {
      setBusy(false);
    }
  }

  function requestDelete(profile: S.AgentProfilesControllerProfilePayload) {
    useStore.getState().askConfirm({
      title: `删除用户级子代理「${profile.name}」`,
      message: "删除后将移除用户目录下的该 Markdown 角色卡；若存在同名内置或项目级角色，会自动回退生效。确定删除吗？",
      confirmText: "删除",
      danger: true,
      onConfirm: () => removeConfirmed(profile)
    });
  }

  return <section className="settings-group">
    <div className="settings-group-heading settings-inline-actions">
      <h3>可用子代理角色</h3>
      <Button id="agent-profile-create" label="新建用户级角色" isDisabled={busy} onClick={() => openDraft(createAgentDraft())} />
    </div>
    {loading && !profiles.length && <p className="settings-status" role="status">正在读取子代理角色…</p>}
    <ul className="agent-profile-list" id="settings-agent-profiles-list">
      {profiles.map(profile => {
        const boundaries = summarizeAgentBoundaries(profile);
        const expanded = previewName === profile.name;
        return <li key={profile.name} className="agent-profile-item">
          <div className="agent-profile-head">
            <code className="agent-profile-name">{profile.name}</code>
            <span className={`mcp-source-tag${profile.source === "user" ? " is-user" : ""}`}>{agentSourceLabel(profile.source)}</span>
            <span className={`cap-tag${profile.writable ? "" : " is-readonly"}`}>{profile.writable ? "可写" : "只读"}</span>
            <span className="agent-profile-actions">
              {profile.source === "user" ? <>
                <Button size="sm" variant="ghost" label="编辑" isDisabled={busy} onClick={() => openDraft(editAgentDraft(profile))} />
                <Button size="sm" variant="ghost" label="删除" className="settings-danger" isDisabled={busy} onClick={() => requestDelete(profile)} />
              </> : <>
                {!!profile.content && <Button size="sm" variant="ghost" label={expanded ? "收起定义" : "查看定义"}
                  aria-expanded={expanded} onClick={() => setPreviewName(expanded ? "" : profile.name)} />}
                <Button size="sm" variant="ghost" label="创建用户级覆写" isDisabled={busy}
                  onClick={() => openDraft(overrideAgentDraft(profile))} />
              </>}
            </span>
          </div>
          {profile.description && <p className="agent-profile-desc">{profile.description}</p>}
          {!!boundaries.length && <div className="agent-profile-boundaries">
            {boundaries.map(item => <span key={item.label} className="cap-mono">{item.label}：{item.value}</span>)}
          </div>}
          {expanded && !!profile.content && <pre className="agent-profile-preview">{profile.content}</pre>}
        </li>;
      })}
      {!loading && !profiles.length && <li className="panel-empty">当前没有可用子代理角色。</li>}
    </ul>
    {!!invalid.length && <div className="agent-profile-invalid" role="alert">
      <p className="settings-status">有 {invalid.length} 个代理定义未通过校验，已跳过：</p>
      <ul>
        {invalid.map(item => <li key={`${item.name}:${item.file}`}><code>{item.name}</code>：{item.reason}</li>)}
      </ul>
    </div>}
    {meta?.projectRoot && <p className="settings-status">项目级目录（只读展示，优先于用户级）：{meta.projectRoot}</p>}
    {meta?.userRoot && <p className="settings-status">用户级目录（可在本页直接增删改）：{meta.userRoot}</p>}
    {!!status && <p id="agent-profile-status" className="settings-status" role={failure ? "alert" : "status"}>{status}</p>}
    {draft && <form id="agent-profile-form" className="settings-form agent-profile-form" onSubmit={event => void save(event)}>
      <TextInput id="agent-profile-name" label="角色名称（小写字母、数字与连字符）" size="lg" width="100%"
        value={draft.name} isDisabled={busy || draft.nameLocked} hasSpellCheck={false} autoComplete="off"
        placeholder="例如 qa-bot 或 docs-reviewer"
        onChange={value => setDraft(current => current ? { ...current, name: value } : current)} />
      {draft.mode === "override" && draft.source === "project" && (
        <p className="settings-status">提示：当前工作区存在同名项目级角色；保存后会写入用户目录，项目级定义在本工作区仍优先。</p>
      )}
      <label className="settings-native-field" htmlFor="agent-profile-content">
        <span>角色定义（Markdown frontmatter 与提示词）</span>
        <textarea id="agent-profile-content" className="agent-profile-editor" rows={14} value={draft.content}
          disabled={busy} spellCheck={false}
          onChange={event => setDraft(current => current ? { ...current, content: event.target.value } : current)} />
      </label>
      <div className="settings-form-actions">
        <Button label="取消" isDisabled={busy} onClick={() => { setDraft(null); setStatus(""); setFailure(false); }} />
        <Button id="agent-profile-save" type="submit" variant="primary" label={busy ? "保存中…" : "保存用户级角色"}
          isDisabled={busy || !draft.name.trim()} aria-busy={busy} />
      </div>
    </form>}
  </section>;
}
