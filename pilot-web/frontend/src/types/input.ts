import type { MessageAttachment } from './runtime.js';
import type { VoiceLimitsBudget } from './server.js';

export interface DraftAttachment extends MessageAttachment {
  id: string;
  name: string;
  mimeType: string;
  size: number;
  dataBase64: string;
}
export interface AttachmentMarker { name: string; mimeType: string; kind: 'inline' | 'saved'; lines: number; raw: string; path?: string }
export type MessagePart = { type: 'text'; text: string } | { type: 'image'; image: MessageAttachment } | { type: 'files'; files: MessageAttachment[] };
export interface TransferData {
  files?: ArrayLike<File>;
  items?: ArrayLike<{ kind: string; getAsFile?: () => File | null; webkitGetAsEntry?: () => { isDirectory: boolean; name?: string } | null }>;
  types?: ArrayLike<string>;
  getData?: (format: string) => string;
}
/** 拖放条目上浏览器额外提供的文件系统入口，用来分辨文件夹与文件。 */
export interface TransferItem {
  kind: string;
  webkitGetAsEntry?: () => { isDirectory: boolean; name?: string } | null;
}
interface CommandFields {
  name: string;
  label: string;
  hint: string;
  aliases: string[];
}
export interface BuiltinCommand extends CommandFields { kind?: 'builtin'; prompt: (args: string) => string; skill?: never }
export interface SkillCommand extends CommandFields { kind: 'skill'; prompt: null; skill: string }
export type QuickCommand = BuiltinCommand | SkillCommand;
export type VoicePhase = 'idle' | 'loading' | 'requesting' | 'recording' | 'finishing' | 'transcribing' | 'error';
export interface VoiceState { phase: VoicePhase; elapsed?: number; maxSeconds?: number; error?: string; retry?: boolean }
export interface VoiceEnvironment {
  AudioContext?: typeof AudioContext;
  webkitAudioContext?: typeof AudioContext;
  OfflineAudioContext?: typeof OfflineAudioContext;
  webkitOfflineAudioContext?: typeof OfflineAudioContext;
  MediaRecorder?: typeof MediaRecorder;
  navigator?: { mediaDevices?: Pick<MediaDevices, 'getUserMedia'> };
  isSecureContext?: boolean;
}
export interface VoiceOptions {
  environment?: VoiceEnvironment;
  loadLimits: (signal: AbortSignal) => Promise<VoiceLimitsBudget | null | undefined>;
  transcribe: (audio: Blob, signal: AbortSignal) => Promise<{ text?: string | null } | null>;
  onState: (state: VoiceState) => void;
  onText: (text: string) => void;
}
export interface PhaseRow {
  type: string;
  detail?: string | null;
  reply?: { text?: string; live?: boolean };
  tools?: { status?: string; name?: string; args?: string }[];
}
export interface RunPhaseInput {
  running?: boolean;
  cancelling?: boolean;
  pausing?: boolean;
  queueHeld?: boolean;
  pendingApprovals?: Record<string, unknown>;
  thread?: { rows: PhaseRow[] };
  lastOutcome?: string;
}

/** 从对话选中文字保存想法时的预填内容与来源。 */
export interface NoteSeed {
  text: string;
  source: { sequence?: number | null; excerpt?: string; role?: string | null };
}
