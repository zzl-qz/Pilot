import type * as S from "../../types/server.js";
import { useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { TextInput } from "@astryxdesign/core/TextInput";
import { CheckboxInput } from "@astryxdesign/core/CheckboxInput";
import { errorInfo } from "../../lib/errors.js";
import { emptySkillsHint, parseInstallSource, summarizeInstallResults, gitUrlProblem } from "../../lib/skills.js";
import { useStore } from "../../store.js";
import { api } from "../../api.js";

export function SkillsSettings() {
  const skills = useStore(state => state.skills);
  const meta = useStore(state => state.skillsMeta);
  const [installing, setInstalling] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let live = true;
    setLoading(true);
    void useStore.getState().loadSkills().finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [refresh]);

  return <section className="settings-group">
    <div className="settings-group-heading settings-inline-actions">
      <h3>已安装技能</h3>
      <Button id="skills-install-open" label={installing ? '收起安装' : '安装技能…'} aria-expanded={installing}
        aria-controls={installing ? 'skills-install' : undefined} onClick={() => setInstalling(open => !open)} />
    </div>
    {loading && <p className="settings-status" role="status">正在读取技能…</p>}
    {!loading && !meta && <div className="settings-inline-actions">
      <p className="settings-status" role="alert">暂时无法读取技能清单。</p>
      <Button label="重新加载技能" onClick={() => setRefresh(count => count + 1)} />
    </div>}
    <ul className="skills-list" id="settings-skills-list">
      {skills.map(skill => <li key={skill.name || skill.id}>
        <b>/{skill.command || skill.name}</b>
        <span className="skill-source">{skill.source === 'project' ? '项目级' : '用户级'}</span>
        <span className="skill-desc">{skill.description || ''}</span>
      </li>)}
      {meta && !loading && !skills.length && <li className="panel-empty">{emptySkillsHint(meta.projectRoot)}也可以从 git 仓库或本机目录安装。</li>}
    </ul>
    {!!meta?.invalid?.length && <p className="settings-status">有 {meta.invalid.length} 个目录缺少 SKILL.md 或名称不合法，已跳过。</p>}
    {meta?.projectRoot && <p className="settings-status">项目级目录 {meta.projectRoot}</p>}
    {installing && <SkillsInstall onDone={() => { void useStore.getState().loadSkills(); }} />}
  </section>;
}

/** 预览生成的令牌只用于当时的来源；编辑地址后须重新预览。 */
function SkillsInstall({ onDone }: { onDone: () => void }) {
  const sessionId = useStore(state => state.sessionId);
  const meta = useStore(state => state.skillsMeta);
  const [source, setSource] = useState('');
  const [token, setToken] = useState('');
  const [candidates, setCandidates] = useState<S.SkillsControllerCandidatePayload[]>([]);
  const [picked, setPicked] = useState(() => new Set<string>());
  const [target, setTarget] = useState('project');
  const [busy, setBusy] = useState('');
  const [hint, setHint] = useState('填写 git 地址或本机目录，先查看内容。');
  const [hasError, setHasError] = useState(false);
  const [conflicts, setConflicts] = useState<string[]>([]);
  const candidateList = useRef<HTMLDivElement>(null);
  useEffect(() => { candidateList.current?.firstElementChild?.scrollIntoView({ block: 'nearest' }); }, [candidates]);

  function resetPreview() {
    setToken('');
    setCandidates([]);
    setPicked(new Set());
    setConflicts([]);
  }
  function changeSource(value: string) {
    setSource(value);
    resetPreview();
    setHasError(false);
    setHint('选择来源后，先查看内容。');
  }
  async function preview() {
    if (busy) return;
    resetPreview();
    const parsed = parseInstallSource(source);
    const problem = parsed?.type === 'git' ? gitUrlProblem(parsed.value) : '';
    if (!parsed || problem) {
      setHasError(true);
      setHint(problem || '请输入 http(s):// 开头的 git 地址，或本机技能目录的绝对路径（如 D:\\skills\\review）。');
      return;
    }
    setBusy('preview');
    setHasError(false);
    setHint(parsed.type === 'git' ? '正在下载并识别技能，最长约两分钟…' : '正在读取目录…');
    try {
      const data = await api.skillsPreview({ source: parsed.value, sessionId: sessionId || undefined });
      const list = Array.isArray(data?.candidates) ? data.candidates : [];
      if (list.some(item => item.valid) && !data?.token) throw new Error('预览凭证缺失，请重试。');
      setToken(data?.token || '');
      setCandidates(list);
      setPicked(new Set(list.filter(item => item.valid).map(item => item.name)));
      setHint(list.length ? `找到 ${list.length} 个技能，勾选后安装。` : (data?.message || '来源里没有找到可安装的技能。'));
    } catch (error) {
      setHasError(true);
      setHint('查看内容失败。' + errorInfo(error).message);
    } finally {
      setBusy('');
    }
  }

  async function install(names: string[], overwrite = false) {
    if (!token || !names.length || busy) return;
    setBusy(overwrite ? 'overwrite' : 'install');
    setHasError(false);
    setHint(overwrite ? '正在覆盖安装…' : '正在安装…');
    try {
      const data = await api.skillsInstall({ token, skills: names, target, sessionId: sessionId || undefined, ...(overwrite ? { overwrite: true } : {}) });
      const { installed, conflicts: conflicted, errors: failed } = summarizeInstallResults(data?.results || []);
      setConflicts(conflicted);
      const parts = [];
      if (installed.length) {
        parts.push(`已安装 ${installed.join('、')}`);
        setPicked(current => new Set([...current].filter(name => !installed.includes(name))));
        onDone();
      }
      if (conflicted.length) parts.push(`发现同名技能 ${conflicted.join('、')}`);
      if (failed.length) parts.push(`安装失败 ${failed.map(item => `${item.name}（${item.reason}）`).join('、')}`);
      setHasError(Boolean(failed.length));
      setHint(parts.length ? parts.join('。') + '。' : '没有执行任何安装。');
    } catch (error) {
      setHasError(true);
      setHint('安装失败。' + errorInfo(error).message);
    } finally {
      setBusy('');
    }
  }

  return <div className="skills-install settings-form" id="skills-install">
    <div className="settings-source-row">
      <TextInput id="skills-install-source" label="来源（技能链接、仓库或本机目录）" size="lg" width="100%" value={source}
        placeholder={'https://github.com/… 或 D:\\skills\\review'} isDisabled={Boolean(busy)} hasSpellCheck={false}
        onChange={changeSource} onEnter={() => void preview()} />
      <Button label="浏览…" isDisabled={Boolean(busy)} onClick={() => useStore.getState().setUi({
        picker: { title: '选择技能目录', onPick: changeSource }
      })} />
      <Button id="skills-install-preview" label={busy === 'preview' ? '读取中…' : '查看内容'} isDisabled={Boolean(busy)} onClick={preview} />
    </div>
    {!!candidates.length && <div ref={candidateList} className="skills-install-candidates" id="skills-install-candidates">
      {candidates.map(item => <CheckboxInput key={item.name} label={item.command ? `${item.name} · /${item.command}` : item.name}
        description={item.valid ? item.description || undefined : `无效（${item.reason || '校验未通过'}）`}
        isDisabled={!item.valid || Boolean(busy)} value={picked.has(item.name)}
        onChange={checked => setPicked(current => {
          const next = new Set(current);
          if (checked) next.add(item.name);
          else next.delete(item.name);
          return next;
        })} />)}
    </div>}
    <fieldset className="skills-install-target" id="skills-install-target" disabled={Boolean(busy)}>
      <legend>安装位置</legend>
      {[['project', '装到项目', meta?.projectRoot], ['user', '装到用户目录', meta?.userRoot]].map(([value, label, root]) => (
        <label key={value} title={root || ''}>
          <input type="radio" name="skills-target" checked={target === value} onChange={() => { setTarget(value); setConflicts([]); }} />
          {label}
        </label>
      ))}
    </fieldset>
    <div className="skills-install-actions">
      <Button id="skills-install-confirm" variant="primary" label={`安装选中的 ${picked.size} 个`}
        isDisabled={!token || !picked.size || Boolean(busy)} onClick={() => install([...picked])} />
      {!!conflicts.length && <Button id="skills-install-overwrite" variant="ghost" className="settings-danger" label="覆盖安装冲突项"
        isDisabled={Boolean(busy)} onClick={() => useStore.getState().askConfirm({
          title: '覆盖安装冲突技能', message: `覆盖会替换目标目录里的 ${conflicts.join('、')}。`, confirmText: '覆盖安装', danger: true,
          onConfirm: () => install(conflicts, true)
        })} />}
    </div>
    <p id="skills-install-hint" className="settings-status" role={hasError ? 'alert' : 'status'}>{hint}</p>
  </div>;
}
