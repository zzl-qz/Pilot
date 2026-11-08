import type { MessageAttachment } from './runtime.js';

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
  items?: ArrayLike<{ kind: string; getAsFile?: () => File | null }>;
  types?: ArrayLike<string>;
  getData?: (format: string) => string;
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
export type VoicePhase = 'idle' | 'requesting' | 'recording' | 'finishing' | 'transcribing' | 'error';
export interface VoiceState { phase: VoicePhase; elapsed?: number; error?: string; retry?: boolean }
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
