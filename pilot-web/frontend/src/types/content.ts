export interface HistoryRange { start: number; end: number }
export interface MarkdownBlock extends HistoryRange { node: Node; source?: string }
export interface SimpleEvent { type?: string; sequence?: number; payload?: { input?: string; text?: string } }
export interface SideMessage { role: 'user' | 'assistant'; text: string }
export interface SideTranscript { messages: SideMessage[]; live: string }
export interface SideChatTab { id: string; parentId?: string | null; title: string; workspace?: string | null; projectId?: string | null }
export interface SideChatsState { items: SideChatTab[]; activeId: string | null }
export interface ThreadMetrics { scrollHeight?: number; scrollTop?: number; clientHeight?: number }
export interface HistoryLoadState { hasMore?: boolean; loading?: boolean; eventCount?: number }
export interface AutoHistoryLoad extends HistoryLoadState { scrollTop?: number; distanceFromBottom?: number; viewportHeight?: number }
