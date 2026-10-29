// 技能命令的纯逻辑：把 /api/skills 的清单映射成快捷指令、与内置指令合并、发送时解析技能调用。
// 纯函数模块，不碰 DOM 与网络；app.js 负责拉取与渲染。

/**
 * 把技能清单（/api/skills 的 available 项）映射成快捷指令形状。
 *
 * <p>技能命令与内置 QUICK_COMMANDS 同构（name/label/hint/aliases），多出：
 * skill（技能名，发送时作为 skill 字段）、kind: 'skill'（菜单打徽标）、prompt: null
 * （技能没有本地展开模板，发送时由后端注入指引）。
 */
export function toSkillCommands(available) {
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
export function mergeCommands(builtin, skillCommands) {
  const merged = new Map();
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
export function resolveSkillCommand(text, skillCommands) {
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
export function skillEntry(skill) {
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
export function emptySkillsHint(projectRoot) {
  const where = projectRoot && projectRoot !== '-'
    ? `在 ${projectRoot} 下`
    : '在技能目录下';
  return `还没有可用技能：${where}建一个目录，里面放 SKILL.md（frontmatter 至少要有一句 description），保存后下次运行即生效。`;
}
