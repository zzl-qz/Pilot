export const QUICK_COMMANDS = [
  {
    name: '/test',
    label: '运行测试',
    hint: '运行项目测试并汇总失败',
    aliases: ['test', 'tests'],
    prompt: (args) => `运行项目测试${args ? `（${args}）` : ''}，汇总失败用例与原因，不要改动代码。`
  },
  {
    name: '/fix',
    label: '修复问题',
    hint: '定位问题、修改代码并验证',
    aliases: ['fix'],
    prompt: (args) => `读取最近一次失败的测试或编译错误，${args ? `重点处理：${args}；` : ''}定位根因并直接修改代码，改完重新运行验证。`
  },
  {
    name: '/run',
    label: '运行指定验证',
    hint: '描述要运行的模块或验证目标',
    aliases: ['run'],
    prompt: (args) => args
      ? `在当前项目中运行以下验证并整理结果：${args}`
      : '运行当前项目测试，并把失败点整理出来。'
  },
  {
    name: '/review',
    label: '审阅改动',
    hint: '按风险审阅未提交改动',
    aliases: ['review'],
    prompt: (args) => `审阅当前工作区未提交的改动${args ? `，重点关注：${args}` : ''}，按风险从高到低列出问题与建议。`
  },
  {
    name: '/explain',
    label: '解释代码',
    hint: '解释文件、模块或调用链',
    aliases: ['explain'],
    prompt: (args) => `解释当前工作区中的代码${args ? `，重点说明：${args}` : ''}，涵盖核心逻辑、调用链和潜在风险。`
  }
];

export const COMMAND_PALETTE_ACTIONS = [
  { label: '新任务（选择工作区）', hint: 'Ctrl N', keywords: ['任务', '工作区', 'workspace', 'new'], action: 'new-session' },
  { label: '切换活动/变更面板', hint: 'Ctrl B', keywords: ['活动', '变更', '面板', 'activity', 'changes'], action: 'toggle-activity' },
  { label: '刷新变更', hint: '', keywords: ['变更', '刷新', 'diff'], action: 'refresh-changes' },
  { label: '复制会话 ID', hint: '', keywords: ['会话', '复制', 'id', 'session'], action: 'copy-session-id' },
  { label: '清空当前视图', hint: '', keywords: ['清空', '重置', '视图', 'reset'], action: 'reset-view' },
  { label: '重新连接事件流', hint: '', keywords: ['重连', '事件', '连接', 'reconnect'], action: 'reconnect' }
];

export function filterQuickCommands(query) {
  const needle = String(query || '').trim().toLowerCase();
  return QUICK_COMMANDS.filter((command) => {
    const fields = [command.name, command.label, command.hint, ...command.aliases];
    return !needle || fields.some((field) => field.toLowerCase().includes(needle));
  });
}

export function filterPaletteActions(query) {
  const needle = String(query || '').trim().toLowerCase();
  return COMMAND_PALETTE_ACTIONS.filter((command) => {
    const fields = [command.label, command.hint, ...command.keywords];
    return !needle || fields.some((field) => field.toLowerCase().includes(needle));
  });
}

export function expandQuickCommand(command, args = '') {
  return command.prompt(String(args).trim());
}
