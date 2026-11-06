import type { SkillsControllerSkillPayload as SkillPayload, SkillsControllerOutcomePayload as OutcomePayload } from '../types/server.js';
import type { QuickCommand, SkillCommand } from '../types/input.js';
// 技能命令的纯逻辑：把 /api/skills 的清单映射成快捷指令、与内置指令合并、发送时解析技能调用。
// 纯函数模块，不碰 DOM 与网络；app.js 负责拉取与渲染。

/**
 * 把技能清单（/api/skills 的 available 项）映射成快捷指令形状。
 *
 * <p>技能命令与内置 QUICK_COMMANDS 同构（name/label/hint/aliases），多出：
 * skill（技能名，发送时作为 skill 字段）、kind: 'skill'（菜单打徽标）、prompt: null
 * （技能没有本地展开模板，发送时由后端注入指引）。
 */
export function toSkillCommands(available: readonly SkillPayload[] | null | undefined): SkillCommand[] {
  return (available || [])
    .filter((skill) => skill && skill.command)
    .map((skill) => ({
      name: skill.command,
      label: skill.name,
      hint: String(skill.description || '')
        + (skill.argumentHint ? ` · 参数 ${skill.argumentHint}` : '')
        + '（技能）',
      aliases: [skill.name],
      kind: 'skill',
      skill: skill.name,
      prompt: null
    }));
}

/**
 * 内置指令与技能指令合并；同名时技能遮蔽内置——用户显式安装的技能是更强的意图。
 * 两个技能的 command 重名由后端判无效，不会同时出现在这里。
 */
export function mergeCommands(builtin: readonly QuickCommand[] | null | undefined, skillCommands: readonly QuickCommand[] | null | undefined): QuickCommand[] {
  const merged = new Map<string, QuickCommand>();
  for (const command of builtin || []) {
    merged.set(command.name, command);
  }
  for (const command of skillCommands || []) {
    merged.set(command.name, command);
  }
  return [...merged.values()];
}

/**
 * 解析输入框里的技能调用：`/命令 参数…` 命中某个技能命令时返回 { skill, args }。
 * 仅精确命中（不做前缀匹配），不带该前缀或命中的是内置指令时返回 null。
 */
export function resolveSkillCommand(text: unknown, skillCommands: readonly SkillCommand[] | null | undefined) {
  const match = /^\/([\w-]+)(?:\s+([\s\S]*))?$/.exec(String(text || '').trim());
  if (!match) {
    return null;
  }
  const command = (skillCommands || []).find((item) => item.name === '/' + match[1]);
  if (!command) {
    return null;
  }
  return { skill: command.skill, args: (match[2] || '').trim() };
}

/** 设置页单条技能的展示：{ name, meta, description }，meta 含来源与快捷命令。 */
export function skillEntry(skill: Partial<SkillPayload> | null | undefined) {
  const source = skill?.source === 'project' ? '项目级' : skill?.source === 'user' ? '用户级' : '';
  const command = skill?.command ? `快捷命令 ${skill.command}` : '';
  const meta = [source, command].filter(Boolean).join(' · ');
  return {
    name: skill?.name || '',
    meta,
    description: skill?.description || ''
  };
}

/** 设置页空态引导：告诉用户往哪个目录放什么文件。 */
export function emptySkillsHint(projectRoot: string | null | undefined) {
  const where = projectRoot && projectRoot !== '-'
    ? `在 ${projectRoot} 下`
    : '在技能目录下';
  return `还没有可用技能：${where}建一个目录，里面放 SKILL.md（frontmatter 至少要有一句 description），保存后下次运行即生效。`;
}

/**
 * 安装来源分类：http(s) → git；盘符/UNC/POSIX 绝对路径 → 本机目录；其余判非法。
 *
 * <p>严格优先：裸域名不自动补 https（提示文案教用户带协议），相对路径与 ~ 不接受——
 * 服务端对两者同样拒绝，两边口径一致比宽容更重要。
 */
export function parseInstallSource(text: unknown): { type: 'git' | 'local'; value: string } | null {
  const value = String(text || '').trim();
  if (!value) {
    return null;
  }
  if (/^https?:\/\//i.test(value)) {
    return { type: 'git', value };
  }
  if (/^[A-Za-z]:[\\/]/.test(value) || /^(\\\\|\/)/.test(value)) {
    return { type: 'local', value };
  }
  return null;
}

/**
 * git 地址形状预检：把浏览器复制来的 GitHub 网页地址（blob/tree/raw/文件路径）拦在
 * 请求之前，返回可展示的中文错误；形状没问题返回 null。
 *
 * <p>与后端 SkillInstallService.diagnoseGitUrl 同口径——前端拦一次省一个网络往返，
 * 后端仍是权威（API 是公共面，不能只靠前端）。
 */
export function gitUrlProblem(url: unknown) {
  const value = String(url || '').trim();
  if (!/^https?:\/\//i.test(value)) {
    return null;
  }
  const lower = value.toLowerCase();
  for (const marker of ['/blob/', '/tree/', '/raw/']) {
    if (lower.includes(marker)) {
      return `这是浏览器里看文件的网页地址（含 ${marker}…），不是 git 仓库地址。请改用仓库根地址，例如 https://github.com/anthropics/skills`;
    }
  }
  const trimmed = lower.endsWith('/') ? lower.slice(0, -1) : lower;
  const last = trimmed.split('/').pop() || '';
  if (last.endsWith('.md') || last.endsWith('.markdown')) {
    return `地址末段看起来是文件（${last}）而不是仓库。git 地址应指向仓库根，例如 https://github.com/用户名/仓库名`;
  }
  return null;
}

/** 安装结果三组化：成功的 / 冲突的 / 失败的，供弹窗结果行渲染。 */
export function summarizeInstallResults(results: readonly Partial<OutcomePayload>[] | null | undefined) {
  const installed = [];
  const conflicts = [];
  const errors = [];
  for (const item of results || []) {
    if (!item || !item.name) {
      continue;
    }
    if (item.status === 'installed') {
      installed.push(item.name);
    } else if (item.status === 'conflict') {
      conflicts.push(item.name);
    } else {
      errors.push({ name: item.name, reason: item.reason || '未知原因' });
    }
  }
  return { installed, conflicts, errors };
}
