import React, { useEffect, useRef, useState, type RefObject } from 'react';
import { Button } from '@astryxdesign/core/Button';
import type { QuickCommand } from '../../types/input.js';
import type { ComposerInputHandle } from './useComposerAttachments.js';
import { api } from '../../api.js';
import { useStore } from '../../store.js';
import { rankFileMatches } from '../../lib/mention.js';
import { filterQuickCommands, expandQuickCommand } from '../../lib/commands.js';

export interface ReferenceMenu {
  items: string[];
  message?: string;
  loading?: boolean;
}

export type SuggestionItem = string | QuickCommand;

const EMPTY_ITEMS: readonly SuggestionItem[] = [];

export interface UseComposerSuggestionsOptions {
  inputRef: RefObject<ComposerInputHandle | null>;
  sessionId: string | null;
  sessionEpoch: number;
  workspace: string;
}

function commandDisplayName(command: QuickCommand): string {
  return command.name.startsWith('/') ? command.name : '/' + command.name;
}

function suggestionAriaLabel(item: SuggestionItem): string {
  if (typeof item === 'string') return item;
  const desc = item.hint || item.label;
  return desc ? `${item.name} · ${desc}` : item.name;
}

function referenceEmptyText(refMenu: ReferenceMenu): string {
  if (refMenu.loading) return '正在读取文件…';
  return refMenu.message || '没有匹配的文件';
}

/**
 * 管理输入区 `@` 文件引用与 `/` 快捷指令候选菜单的数据拉取、过滤与键盘导航。
 */
export function useComposerSuggestions({
  inputRef,
  sessionId,
  sessionEpoch,
  workspace
}: UseComposerSuggestionsOptions) {
  const referenceRequest = useRef(0);
  const [refMenu, setRefMenu] = useState<ReferenceMenu | null>(null);
  const [slashMenu, setSlashMenu] = useState<{ items: QuickCommand[]; query: string } | null>(null);
  const [menuIndex, setMenuIndex] = useState(0);

  async function openRefMenu(query: string) {
    const state = useStore.getState();
    const owner = state.sessionId;
    const activeWorkspace = state.workspace || state.draftSession?.workspace;
    const key = owner || activeWorkspace ? `${owner || 'draft'}:${activeWorkspace || ''}` : '';
    const request = ++referenceRequest.current;
    setSlashMenu(null);
    setMenuIndex(0);
    if (!key) {
      setRefMenu({ items: [], message: '先选择工作区，再引用文件' });
      return;
    }
    setRefMenu({ items: [], loading: true });
    try {
      let items = state.fileIndex.key === key ? state.fileIndex.items : null;
      if (!items) {
        const data = owner ? await api.filesFlat(owner) : await api.workspaceFiles(activeWorkspace || '');
        items = data?.files || (Array.isArray(data) ? data : []);
        if (request !== referenceRequest.current) return;
        useStore.setState({ fileIndex: { items, key, sessionId: owner, loading: false } });
      }
      if (request === referenceRequest.current) setRefMenu({ items: rankFileMatches(items, query).slice(0, 8) });
    } catch {
      if (request === referenceRequest.current) setRefMenu({ items: [], message: '读取文件失败，请重新输入 @ 重试' });
    }
  }

  function onInput(event: React.FormEvent<HTMLDivElement>) {
    if ((event.nativeEvent as InputEvent).isComposing) return;
    const textBefore = inputRef.current?.textBeforeCaret?.() || '';
    referenceRequest.current += 1;
    const atMatch = textBefore.match(/(^|\s)@([^\s@\uFFFC]*)$/);
    if (atMatch) {
      void openRefMenu(atMatch[2]);
      return;
    }
    const slashMatch = textBefore.match(/(^|\s)\/([\w-]*)$/);
    if (slashMatch) {
      // 签名是 filterQuickCommands(query, commands)：参数写反会让命令菜单永远是空的
      const commands = filterQuickCommands(slashMatch[2], useStore.getState().quickCommands || []);
      setSlashMenu(commands.length ? { items: commands, query: slashMatch[2] } : null);
      setRefMenu(null);
      setMenuIndex(0);
      return;
    }
    setRefMenu(null);
    setSlashMenu(null);
  }

  function pickRef(path: string) {
    inputRef.current?.insertMention(path);
    inputRef.current?.focus();
    referenceRequest.current += 1;
    setRefMenu(null);
  }

  function pickSlash(command: QuickCommand) {
    const expansion = 'skill' in command
      ? `${commandDisplayName(command)} `
      : expandQuickCommand(command, '');
    if (expansion) inputRef.current?.replaceCommand(expansion);
    setSlashMenu(null);
    inputRef.current?.focus();
  }

  function clearSuggestions() {
    referenceRequest.current += 1;
    setRefMenu(null);
    setSlashMenu(null);
  }

  useEffect(() => {
    clearSuggestions();
  }, [sessionId, sessionEpoch, workspace]);

  const items: readonly SuggestionItem[] = refMenu?.items || slashMenu?.items || EMPTY_ITEMS;
  const open = Boolean(refMenu || slashMenu);

  function handleKeyDown(event: React.KeyboardEvent<HTMLDivElement>): boolean {
    if (!open) return false;
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      clearSuggestions();
      return true;
    }
    if (refMenu?.loading && event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      return true;
    }
    if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && items.length) {
      event.preventDefault();
      const delta = event.key === 'ArrowDown' ? 1 : -1;
      setMenuIndex((index) => (index + delta + items.length) % items.length);
      return true;
    }
    if ((event.key === 'Enter' || event.key === 'Tab') && !event.shiftKey && items.length) {
      event.preventDefault();
      const item = items[Math.min(menuIndex, items.length - 1)];
      if (typeof item === 'string') pickRef(item);
      else pickSlash(item);
      return true;
    }
    return false;
  }

  return {
    open,
    refMenu,
    items,
    menuIndex,
    setMenuIndex,
    onInput,
    clearSuggestions,
    handleKeyDown,
    pickRef,
    pickSlash
  };
}

export interface ComposerSuggestionsProps {
  open: boolean;
  refMenu: ReferenceMenu | null;
  items: readonly SuggestionItem[];
  menuIndex: number;
  onHoverIndex: (index: number) => void;
  onPickRef: (path: string) => void;
  onPickSlash: (command: QuickCommand) => void;
}

export function ComposerSuggestions({
  open,
  refMenu,
  items,
  menuIndex,
  onHoverIndex,
  onPickRef,
  onPickSlash
}: ComposerSuggestionsProps) {
  const menuRef = useRef<HTMLDivElement>(null);

  /* 方向键只滚动候选列表，保持消息区和输入框的位置。 */
  useEffect(() => {
    if (!open) return;
    const menu = menuRef.current;
    const item = menu?.querySelector('[aria-selected="true"]');
    if (!menu || !item) return;
    const bounds = menu.getBoundingClientRect();
    const option = item.getBoundingClientRect();
    if (option.top < bounds.top) menu.scrollTop += option.top - bounds.top;
    if (option.bottom > bounds.bottom) menu.scrollTop += option.bottom - bounds.bottom;
  }, [menuIndex, open, refMenu, items]);

  if (!open) return null;

  if (refMenu && !refMenu.items.length) {
    return (
      <div className="composer-suggestions composer-flyout" ref={menuRef}>
        <div className="ref-empty" role="status">{referenceEmptyText(refMenu)}</div>
      </div>
    );
  }

  return (
    <div className="composer-suggestions composer-flyout" ref={menuRef}>
      <div id="composer-suggestions" role="listbox" aria-label={refMenu ? '文件引用' : '快捷指令'}>
        {items.map((item, index) => (
          <Button
            key={typeof item === 'string' ? item : item.name}
            id={'composer-option-' + index}
            label={suggestionAriaLabel(item)}
            className="ref-item"
            size="sm"
            variant="ghost"
            width="100%"
            role="option"
            aria-selected={index === menuIndex}
            tabIndex={-1}
            onMouseDown={(event) => event.preventDefault()}
            onMouseEnter={() => onHoverIndex(index)}
            onClick={() => (typeof item === 'string' ? onPickRef(item) : onPickSlash(item))}
          >
            <span className="composer-option-label">
              {typeof item === 'string' ? item : (
                <><strong>{commandDisplayName(item)}</strong> · {item.hint || item.label}</>
              )}
            </span>
          </Button>
        ))}
      </div>
    </div>
  );
}
