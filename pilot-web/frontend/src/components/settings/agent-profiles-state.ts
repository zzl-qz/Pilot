import type * as S from '../../types/server.js';

export const AGENT_NAME_PATTERN = /^[a-z][a-z0-9-]{0,63}$/;
export const MAX_AGENT_PROMPT_LINES = 300;
export const EMPTY_AGENT_PROFILE_TEMPLATE =
  '---\ndescription: 自定义子代理说明\ntools: [read_file, search_text, glob_files]\nfiles: []\ndeny-files: []\ncommands: []\ndeny-commands: []\nwritable: false\n---\n请在此编写该子代理的角色定位与工作原则。\n';

export type AgentDraftMode = 'create' | 'edit' | 'override';

export interface AgentDraftState {
  mode: AgentDraftMode;
  name: string;
  content: string;
  nameLocked: boolean;
  source?: S.AgentProfilesControllerProfilePayload['source'];
}

export function agentSourceLabel(source: string): string {
  if (source === 'project') return '项目级';
  if (source === 'user') return '用户级';
  return '内置';
}

export function createAgentDraft(): AgentDraftState {
  return { mode: 'create', name: '', content: EMPTY_AGENT_PROFILE_TEMPLATE, nameLocked: false };
}

export function editAgentDraft(profile: S.AgentProfilesControllerProfilePayload): AgentDraftState {
  return {
    mode: 'edit',
    name: profile.name,
    content: profile.content || EMPTY_AGENT_PROFILE_TEMPLATE,
    nameLocked: true,
    source: profile.source
  };
}

export function overrideAgentDraft(profile: S.AgentProfilesControllerProfilePayload): AgentDraftState {
  return {
    mode: 'override',
    name: profile.name,
    content: profile.content || EMPTY_AGENT_PROFILE_TEMPLATE,
    nameLocked: true,
    source: profile.source
  };
}

export function summarizeAgentBoundaries(profile: S.AgentProfilesControllerProfilePayload): { label: string; value: string }[] {
  const items: { label: string; value: string }[] = [];
  if (profile.tools?.length) items.push({ label: '工具', value: profile.tools.join('、') });
  if (profile.files?.length) items.push({ label: '文件白名单', value: profile.files.join('、') });
  if (profile.denyFiles?.length) items.push({ label: '文件黑名单', value: profile.denyFiles.join('、') });
  if (profile.commands?.length) items.push({ label: '命令白名单', value: profile.commands.join('、') });
  if (profile.denyCommands?.length) items.push({ label: '命令黑名单', value: profile.denyCommands.join('、') });
  return items;
}

export function validateAgentProfileDraft(rawName: string, rawContent: string): string {
  const name = rawName.trim();
  if (!name) return '请先填写角色名称。';
  if (!AGENT_NAME_PATTERN.test(name)) return '角色名称须以小写字母开头，仅含小写字母、数字和连字符（最多 64 位）。';
  const lines = (rawContent || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
  if (lines[0]?.trim() !== '---') return '角色定义必须以 --- 开头声明 frontmatter。';
  const endIndex = lines.findIndex((line, index) => index > 0 && line.trim() === '---');
  if (endIndex < 0) return '角色定义缺少结束分隔符 ---。';
  const header = lines.slice(1, endIndex);
  const hasDescription = header.some(line => /^description\s*:\s*\S/.test(line.trim()));
  if (!hasDescription) return 'frontmatter 中必须填写非空的 description 说明。';
  const bodyLines = lines.slice(endIndex + 1);
  const body = bodyLines.join('\n').trim();
  if (!body) return '角色提示词正文不能为空。';
  if (body.split('\n').length > MAX_AGENT_PROMPT_LINES) {
    return `角色提示词超过 ${MAX_AGENT_PROMPT_LINES} 行上限。`;
  }
  return '';
}
