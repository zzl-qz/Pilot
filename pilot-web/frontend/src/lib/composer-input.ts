import type { DraftAttachment } from '../types/input.js';
interface SerializedDraft { text: string; positions: number[]; query: boolean }
import { readTransferFiles, hasFileTransferHint } from './clipboard.js';
import { fileBadge, formatBytes, isImageAttachment } from './attachments/attachments.js';

/**
 * 输入框的编辑器：一个 contenteditable 放正文和 @ 引用，附件单独排在输入框下方的 rail 里。
 *
 * 附件以前和正文混排在 contenteditable 里，缩略图会占掉正文横向空间、也不容易整体浏览。
 * 现在附件芯片放在 `rail`（输入框外、toolbar 之上），正文只留文字和 `@路径` 引用；
 * 序列化时附件不产生文字，引用还原成 `@路径`，发给服务端的仍然是普通文本。
 *
 * DOM 是唯一的真相：草稿里有哪些附件，就看 rail 里有哪些芯片，不再另存一份会漂移的数组。
 */
export function createComposerInput(root: HTMLElement, { rail, onChange = () => {}, onRemove = () => {}, onPreview = () => {} }: {
  rail?: HTMLElement | null;
  onChange?: () => void;
  onRemove?: (id: string) => void;
  onPreview?: (attachment: { name: string; src: string }) => void;
} = {}) {
  // 没有传 rail 时退回混排，保证已有调用方和测试仍能工作
  const tray = rail || root;
  // 不能用 plaintext-only：它会在第一次键入时把 DOM 压平成纯文本，芯片就变成了一串字。
  // 用普通可编辑，粘贴时自己剥掉格式（见下面的 paste 处理）。
  root.contentEditable = 'true';
  const listeners = new AbortController();

  function walk(parent: Node, out: SerializedDraft) {
    for (const child of parent.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) {
        out.text += (child.nodeValue || '').replace(/\u00a0/g, ' ');
        continue;
      }
      if (child.nodeType !== Node.ELEMENT_NODE) {
        continue;
      }
      const node = child as HTMLElement;
      const chip = node.dataset && node.dataset.chip;
      if (chip === 'attachment') {
        if (node.dataset.kind === 'image') {
          out.positions.push(out.text.length);
        }
        continue;
      }
      if (chip === 'mention') {
        // 判断「光标前是不是 @查询」时，引用整体按一个占位符算，免得把已选的路径当成正在输入的查询
        out.text += out.query ? '\uFFFC' : `@${node.dataset.path}`;
        continue;
      }
      if (node.tagName === 'BR') {
        // 末尾的 <br> 是浏览器放的行占位，不是用户敲的换行
        if (node.nextSibling) {
          out.text += '\n';
        }
        continue;
      }
      if (/^(DIV|P)$/.test(node.tagName) && out.text && !out.text.endsWith('\n')) {
        out.text += '\n';
      }
      walk(node, out);
    }
  }

  function serialize(container: Node, query = false) {
    const out: SerializedDraft = { text: '', positions: [], query };
    walk(container, out);
    return out;
  }

  function refresh() {
    const empty = !root.querySelector('[data-chip]') && !tray.querySelector('[data-chip="attachment"]') && serialize(root).text === '';
    if (empty && root.firstChild) {
      // 删空后浏览器常留一个 <br>，会让占位提示不再显示
      root.replaceChildren();
    }
    root.dataset.empty = String(empty && !tray.querySelector('[data-chip="attachment"]'));
    if (tray !== root) tray.hidden = !tray.querySelector('[data-chip="attachment"]');
  }

  function selectionRange() {
    const selection = window.getSelection()!;
    if (!selection || !selection.rangeCount) {
      return null;
    }
    const range = selection.getRangeAt(0);
    return root.contains(range.startContainer) && root.contains(range.endContainer) ? range : null;
  }

  function placeCaret(node: Node, offset: number) {
    const range = document.createRange();
    range.setStart(node, offset);
    range!.collapse(true);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range!);
  }

  function focusEnd() {
    root.focus();
    const range = document.createRange();
    range.selectNodeContents(root);
    range.collapse(false);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range!);
  }

  // 只替换光标前的触发词，保留两边文字、附件和已有引用；兼容浏览器拆开的文字节点。
  function selectTrigger(marker: string) {
    const caret = selectionRange();
    if (!caret || !caret.collapsed) return false;
    const probe = document.createRange();
    probe.selectNodeContents(root);
    probe.setEnd(caret.endContainer, caret.endOffset);
    const text = serialize(probe.cloneContents(), true).text;
    const match = text.match(marker === '@' ? /(?:^|\s)(@[^\s@\uFFFC]*)$/ : /(?:^|\s)(\/[^\s/\uFFFC]*)$/);
    if (!match) return false;
    const target = text.length - match[1].length;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      if (node.parentElement!.closest('[data-chip]')) continue;
      const prefix = document.createRange();
      prefix.selectNodeContents(root);
      prefix.setEnd(node, 0);
      const start = serialize(prefix.cloneContents(), true).text.length;
      if (target >= start && target < start + (node as Text).length) {
        const range = caret.cloneRange();
        range.setStart(node, target - start);
        const selection = window.getSelection()!;
        selection.removeAllRanges();
        selection.addRange(range!);
        return true;
      }
    }
    return false;
  }

  /** 把节点放到光标处（光标不在输入框里就追加到末尾），并把光标移到它们后面。 */
  function insertAtCaret(nodes: Node[]) {
    const fragment = document.createDocumentFragment();
    nodes.forEach((node) => fragment.append(node));
    const last = nodes[nodes.length - 1];
    let range = selectionRange();
    if (!range) {
      focusEnd();
      range = selectionRange();
    }
    range!.deleteContents();
    range!.insertNode(fragment);
    if (last.nodeType === Node.TEXT_NODE) {
      placeCaret(last, last.nodeValue!.length);
    } else {
      range!.setStartAfter(last);
      range!.collapse(true);
      const selection = window.getSelection()!;
      selection.removeAllRanges();
      selection.addRange(range!);
    }
  }

  function chipRoot(kind: string, attributes: Record<string, string>) {
    const chip = document.createElement('span');
    chip.className = 'flow-chip';
    chip.contentEditable = 'false';
    chip.dataset.chip = kind;
    Object.entries(attributes).forEach(([key, value]) => {
      chip.dataset[key] = value;
    });
    return chip;
  }

  function badge(name: string, mimeType = '') {
    const info = fileBadge(name, mimeType);
    const node = document.createElement('span');
    node.className = `chip-badge ft-${info.tone}`;
    node.textContent = info.label;
    return node;
  }

  function attachmentChip(item: DraftAttachment) {
    const isImage = isImageAttachment(item) && item.dataBase64;
    const chip = chipRoot('attachment', { attachmentId: item.id, kind: isImage ? 'image' : 'file' });
    chip.classList.add(isImage ? 'is-image' : 'is-file');
    chip.title = isImage ? `${item.name} · 点击查看大图` : [item.name, formatBytes(item.size)].filter(Boolean).join(' · ');
    if (isImage) {
      // 缩略图本身就是查看入口：rail 里空间大，点一下直接在弹层里看原图
      const thumb = document.createElement('img');
      thumb.className = 'chip-thumb';
      thumb.src = item.dataBase64;
      thumb.alt = item.name;
      thumb.dataset.preview = 'image';
      chip.append(thumb);
      chip.dataset.previewable = 'true';
    } else {
      chip.append(badge(item.name, item.mimeType));
    }
    const name = document.createElement('span');
    name.className = 'chip-name';
    name.textContent = item.name;
    chip.append(name);
    if (item.size && !isImage) {
      const meta = document.createElement('span');
      meta.className = 'chip-meta';
      meta.textContent = formatBytes(item.size);
      chip.append(meta);
    }
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'chip-remove';
    remove.tabIndex = -1;
    remove.setAttribute('aria-label', `移除 ${item.name}`);
    remove.textContent = '×';
    chip.append(remove);
    return chip;
  }

  function mentionChip(path: string) {
    const chip = chipRoot('mention', { path });
    chip.classList.add('is-mention');
    chip.title = path;
    const name = document.createElement('span');
    name.className = 'chip-name';
    name.textContent = path.split(/[\\/]/).at(-1) || path;
    chip.append(badge(path), name);
    return chip;
  }

  // 附件芯片在 rail 里，事件挂在 rail 上；未传 rail 时 tray === root，行为不变
  tray.addEventListener('click', (event) => {
    const target = event.target instanceof Element ? event.target : null;
    if (!target) return;
    const button = target.closest('.chip-remove');
    if (button) {
      event.preventDefault();
      const chip = button.closest<HTMLElement>('.flow-chip');
      if (!chip) return;
      // 插入芯片时顺带放了一个分隔空格，一起拿掉，免得反复加减后留下一串空格
      const gap = chip.nextSibling;
      if (gap && gap.nodeType === Node.TEXT_NODE && gap.nodeValue === ' ') {
        gap.remove();
      }
      const id = chip.dataset.attachmentId;
      chip.remove();
      refresh();
      if (id) onRemove(id);
      onChange();
      return;
    }
    // 图片缩略图：点芯片看大图，不再需要先下载到本机
    const tile = target.closest<HTMLElement>('[data-previewable="true"]');
    const thumb = tile?.querySelector('img');
    if (tile && thumb) {
      event.preventDefault();
      onPreview({ name: thumb.alt || '', src: thumb.src });
    }
  }, { signal: listeners.signal });

  root.addEventListener('input', () => {
    refresh();
    onChange();
  }, { signal: listeners.signal });

  root.addEventListener('paste', (event) => {
    const data = event.clipboardData;
    if (!data || readTransferFiles(data).length || hasFileTransferHint(data)) {
      return;
    }
    // 文件交给外面的粘贴处理；文字一律按纯文本插入，不把网页的样式和结构带进输入框
    const text = data.getData('text/plain');
    if (!text) {
      return;
    }
    event.preventDefault();
    document.execCommand('insertText', false, text.replace(/\r\n?/g, '\n'));
  }, { signal: listeners.signal });

  root.addEventListener('drop', (event) => {
    const data = event.dataTransfer;
    if (!data || readTransferFiles(data).length || hasFileTransferHint(data)) {
      // 文件交给 composer 的窗口级处理；这里不能 preventDefault
      return;
    }
    // 从网页拖图片/富文本进来时 contenteditable 会塞入外部 DOM；只接受纯文本
    event.preventDefault();
    const text = data.getData('text/plain');
    if (!text) {
      return;
    }
    root.focus();
    document.execCommand('insertText', false, text.replace(/\r\n?/g, '\n'));
  }, { signal: listeners.signal });

  refresh();

  return {
    root,
    destroy() { listeners.abort(); },
    notifyChange() { refresh(); onChange(); },

    getText() {
      return serialize(root).text;
    },

    /** 正文 + 图片位置。附件都不在正文里，所以位置恒为空（服务端会把附件排在正文后）。 */
    getDraft() {
      const { text } = serialize(root);
      return { text, imagePositions: [] as number[] };
    },

    attachmentIds() {
      return [...tray.querySelectorAll<HTMLElement>('[data-chip="attachment"]')].map((chip) => chip.dataset.attachmentId!);
    },

    hasChips() {
      return Boolean(root.querySelector('[data-chip]') || tray.querySelector('[data-chip="attachment"]'));
    },

    /** 整个草稿替换成纯文字，芯片一并清掉。 */
    setText(text: unknown) {
      root.replaceChildren(...(text ? [document.createTextNode(String(text))] : []));
      tray.querySelectorAll('[data-chip="attachment"]').forEach((chip) => chip.remove());
      refresh();
      onChange();
    },

    /**
     * 恢复草稿：正文回到输入框，附件按保存顺序回到 rail。
     * `imagePositions` 只为兼容旧草稿保留，附件不再嵌在正文里。
     */
    restoreDraft(text: unknown, files: readonly DraftAttachment[], _imagePositions: readonly number[]) {
      const value = String(text || '');
      const list = Array.from(files || []);
      root.replaceChildren(...(value ? [document.createTextNode(value)] : []));
      this.restoreAttachments(list);
    },

    /** 只换 rail 里的附件，不动正文（切会话时正文是 innerHTML，里头还有 @ 引用芯片）。 */
    restoreAttachments(files: readonly DraftAttachment[]) {
      tray.querySelectorAll('[data-chip="attachment"]').forEach((chip) => chip.remove());
      Array.from(files || []).forEach((item) => tray.append(attachmentChip(item)));
      refresh();
      onChange();
    },

    /** 在草稿末尾追加文字，已有的芯片保持不动。 */
    appendText(text: unknown) {
      if (!text) {
        return;
      }
      root.append(document.createTextNode(String(text)));
      refresh();
      onChange();
    },

    focus() {
      root.focus();
    },

    /** 拖放到输入框时把光标放到鼠标落点；落点不在输入框内则放到末尾。 */
    placeCaretAtPoint(x: number, y: number) {
      const doc = root.ownerDocument as Document & {
        caretRangeFromPoint?: (x: number, y: number) => Range | null;
        caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null;
      };
      let range: Range | null = null;
      try {
        range = doc.caretRangeFromPoint?.(x, y) || null;
        if (!range && doc.caretPositionFromPoint) {
          const position = doc.caretPositionFromPoint(x, y);
          if (position) {
            range = doc.createRange();
            range.setStart(position.offsetNode, position.offset);
            range.collapse(true);
          }
        }
      } catch { /* 旧 WebView 没有 caret API，退回末尾 */ }
      if (range && root.contains(range.startContainer)) {
        // 落点如果在附件芯片内部，插到芯片后面，不能 deleteContents 把缩略图/名称删掉。
        const start = range.startContainer instanceof Element ? range.startContainer : range.startContainer.parentElement;
        const chip = start?.closest?.('[data-chip]');
        if (chip && root.contains(chip)) {
          range.setStartAfter(chip);
          range.collapse(true);
        }
        const selection = window.getSelection();
        selection?.removeAllRanges();
        selection?.addRange(range);
        root.focus();
        return;
      }
      focusEnd();
    },

    focusEnd,

    /** 光标之前的文字，引用芯片折成占位符。输入框没有焦点时按「光标在末尾」算。 */
    textBeforeCaret() {
      const range = selectionRange();
      if (!range) {
        return serialize(root, true).text;
      }
      const probe = document.createRange();
      probe.selectNodeContents(root);
      probe.setEnd(range.endContainer, range.endOffset);
      return serialize(probe.cloneContents(), true).text;
    },

    textAfterCaret() {
      const range = selectionRange();
      if (!range) {
        return '';
      }
      const probe = document.createRange();
      probe.selectNodeContents(root);
      probe.setStart(range.endContainer, range.endOffset);
      return serialize(probe.cloneContents()).text;
    },

    /** 光标是否在第一行 / 最后一行：上下键翻历史前用来判断，多行文字里不能抢方向键。 */
    caretOnEdgeLine(edge: string) {
      const before = this.textBeforeCaret();
      const after = this.textAfterCaret();
      return edge === 'first' ? !before.includes('\n') : !after.includes('\n');
    },

    insertAttachments(items: readonly DraftAttachment[]) {
      if (!items.length) return;
      items.forEach((item) => tray.append(attachmentChip(item)));
      refresh();
      onChange();
    },

    /**
     * 选中一个 @ 文件：把光标前正在输入的 `@查询` 换成引用芯片。
     * 找不到那段查询（光标不在文字节点里）就直接在光标处插入。
     */
    insertMention(path: string) {
      const chip = mentionChip(path);
      const gap = document.createTextNode(' ');
      selectTrigger('@');
      insertAtCaret([chip, gap]);
      refresh();
      onChange();
    },

    replaceCommand(text: string) {
      if (!selectTrigger('/')) return false;
      insertAtCaret([document.createTextNode(text)]);
      refresh();
      onChange();
      return true;
    },

    /** 把整个草稿节点摘下来保存（编辑排队消息期间腾出输入框）；之后用 restore 原样放回。 */
    detach() {
      const nodes = [...root.childNodes];
      root.replaceChildren();
      refresh();
      return nodes;
    },

    restore(nodes: readonly Node[] | null | undefined) {
      root.replaceChildren(...(nodes || []));
      refresh();
    }
  };
}
