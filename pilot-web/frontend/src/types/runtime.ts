/** 请求报告对应 RequestReport / ModelUsage / ContextComposition，旧记录允许缺字段。 */
export type Numeric = number | string | null;
export interface CacheMetrics {
  observedCacheRequests?: Numeric;
  observedCacheInputTokens?: Numeric;
  cacheReadObserved?: boolean;
  inputTokens?: Numeric;
  outputTokens?: Numeric;
  reasoningTokens?: Numeric;
  cachedInputTokens?: Numeric;
  cacheReadTokens?: Numeric;
}
export interface ContextPart { category?: string; label?: string; tokens?: Numeric }
export interface ContextStatus {
  messages?: number;
  usedTokens?: Numeric;
  limitTokens?: Numeric;
  percent?: Numeric;
  estimated?: boolean;
  canCompact?: boolean;
  segments?: { role?: string; messages?: Numeric; chars?: Numeric }[];
}
export interface RequestReport extends CacheMetrics {
  id?: string;
  purpose?: string;
  model?: string;
  provider?: string;
  status?: string;
  startedAt?: number;
  durationMs?: number;
  firstTokenMs?: number | null;
  usage?: CacheMetrics & { cacheWriteTokens?: Numeric; cacheWriteObserved?: boolean; exact?: boolean };
  prefix?: { strategy?: string; fingerprint?: string; unchangedMessages?: number; change?: string };
  context?: { parts?: ContextPart[]; totalTokens?: Numeric; attachments?: Numeric; estimated?: boolean };
  cost?: { total?: Numeric; currency?: string; input?: Numeric; cachedInput?: Numeric; cacheWrite?: Numeric; output?: Numeric; source?: string; note?: string } | null;
}
export interface RequestGroup {
  purpose: string;
  reports: RequestReport[];
  failed: number;
  pricedRequests: number;
  costs: Record<string, number>;
}
export interface AgentSummary {
  id: string;
  status?: string;
  latest?: string;
  conclusion?: string;
}
export interface ManifestFacts { changed?: string[]; checks?: string[]; openSteps?: string[]; hasManifest?: boolean }
export interface CommandLog {
  text: string;
  offset: number;
  trimmed: number;
  truncated: boolean;
  eof: boolean;
}
export interface CommandLogChunk {
  text?: string;
  offset?: number;
  nextOffset?: number;
  truncated?: boolean;
  eof?: boolean;
}
export interface MessageAttachment {
  filename?: string;
  id?: string;
  name?: string;
  mimeType?: string;
  size?: number;
  url?: string;
  path?: string;
  dataBase64?: string;
  kind?: string;
  raw?: string;
  lines?: number;
}
export interface QueueEntry { text?: string; content?: string; attachments?: MessageAttachment[]; imagePositions?: number[] }
export interface QueueResponse { items?: (string | QueueEntry)[]; entries?: QueueEntry[]; revision?: string }
