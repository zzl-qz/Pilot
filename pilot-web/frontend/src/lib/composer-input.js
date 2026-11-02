import { readTransferFiles } from './clipboard.js';
import { fileBadge, formatBytes, isImageAttachment } from './attachments.js';

/**
 * 输入框的编辑器：一个 contenteditable，文字和「芯片」混排。
 *
 * 芯片有两种：附件（图片缩略图 / 文件徽标，带移除按钮）和 @ 文件引用。它们都是
 * contenteditable=false 的行内元素，退格一次整块删掉；序列化时附件不产生文字，
 * 引用还原成 `@路径`，所以发给服务端的仍然是普通文本，图片只多出各自的插入位置。
 *
 * DOM 是唯一的真相：草稿里有哪些附件，就看里面有哪些芯片，不再另存一份会漂移的数组。
 */
export function createComposerInput(root, { onChange = () => {}, onRemove = () => {} } = {}) {
  // 不能用 plaintext-only：它会在第一次键入时把 DOM 压平成纯文本，芯片就变成了一串字。
  // 用普通可编辑，粘贴时自己剥掉格式（见下面的 paste 处理）。
  root.contentEditable = 'true';
  const listeners = new AbortController();

  function walk(parent, out) {
    for (const node of parent.childNodes) {
      if (node.nodeType === Node.TEXT_NODE) {
        out.text += (node.nodeValue || '').replace(/\u00a0/g, ' ');
        continue;
      }
      if (node.nodeType !== Node.ELEMENT_NODE) {
        continue;
      }
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

  function serialize(container, query = false) {
    const out = { text: '', positions: [], query };
    walk(container, out);
    return out;
  }

  function refresh() {
    const empty = !root.querySelector('[data-chip]') && serialize(root).text === '';
    if (empty && root.firstChild) {
      // 删空后浏览器常留一个 <br>，会让占位提示不再显示
      root.replaceChildren();
    }
    root.dataset.empty = String(empty);
  }

  function selectionRange() {
    const selection = window.getSelection();
    if (!selection || !selection.rangeCount) {
      return null;
    }
    const range = selection.getRangeAt(0);
    return root.contains(range.startContainer) && root.contains(range.endContainer) ? range : null;
  }

  function placeCaret(node, offset) {
    const range = document.createRange();
    range.setStart(node, offset);
    range.collapse(true);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
  }

  function focusEnd() {
    root.focus();
    const range = document.createRange();
    range.selectNodeContents(root);
    range.collapse(false);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
  }

  // 只替换光标前的触发词，保留两边文字、附件和已有引用；兼容浏览器拆开的文字节点。
  function selectTrigger(marker) {
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
      if (node.parentElement.closest('[data-chip]')) continue;
      const prefix = document.createRange();
      prefix.selectNodeContents(root);
      prefix.setEnd(node, 0);
      const start = serialize(prefix.cloneContents(), true).text.length;
      if (target >= start && target < start + node.length) {
        const range = caret.cloneRange();
        range.setStart(node, target - start);
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        return true;
      }
    }
    return false;
  }

  /** 把节点放到光标处（光标不在输入框里就追加到末尾），并把光标移到它们后面。 */
  function insertAtCaret(nodes) {
    const fragment = document.createDocumentFragment();
    nodes.forEach((node) => fragment.append(node));
    const last = nodes[nodes.length - 1];
    let range = selectionRange();
    if (!range) {
      focusEnd();
      range = selectionRange();
    }
    range.deleteContents();
    range.insertNode(fragment);
    if (last.nodeType === Node.TEXT_NODE) {
      placeCaret(last, last.nodeValue.length);
    } else {
      range.setStartAfter(last);
      range.collapse(true);
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    }
  }

  function chipRoot(kind, attributes) {
    const chip = document.createElement('span');
    chip.className = 'flow-chip';
    chip.contentEditable = 'false';
    chip.dataset.chip = kind;
    Object.entries(attributes).forEach(([key, value]) => {
      chip.dataset[key] = value;
    });
    return chip;
  }

  function badge(name, mimeType) {
    const info = fileBadge(name, mimeType);
    const node = document.createElement('span');
    node.className = `chip-badge ft-${info.tone}`;
    node.textContent = info.label;
    return node;
  }

  function attachmentChip(item) {
    const isImage = isImageAttachment(item) && item.dataBase64;
    const chip = chipRoot('attachment', { attachmentId: item.id, kind: isImage ? 'image' : 'file' });
    chip.classList.add(isImage ? 'is-image' : 'is-file');
    chip.title = [item.name, formatBytes(item.size)].filter(Boolean).join(' · ');
    if (isImage) {
      const thumb = document.createElement('img');
      thumb.className = 'chip-thumb';
      thumb.src = item.dataBase64;
      thumb.alt = '';
      chip.append(thumb);
    } else {
      chip.append(badge(item.name, item.mimeType));
    }
    const name = document.createElement('span');
    name.className = 'chip-name';
    name.textContent = item.name;
    chip.append(name);
    if (item.size) {
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

  function mentionChip(path) {
    const chip = chipRoot('mention', { path });
    chip.classList.add('is-mention');
    chip.title = path;
    const name = document.createElement('span');
    name.className = 'chip-name';
    name.textContent = path.slice(path.lastIndexOf('/') + 1) || path;
    chip.append(badge(path), name);
    return chip;
  }

  root.addEventListener('click', (event) => {
    const button = event.target instanceof Element ? event.target.closest('.chip-remove') : null;
    if (!button) {
      return;
    }
    event.preventDefault();
    const chip = button.closest('.flow-chip');
    if (!chip) {
      return;
    }
    const id = chip.dataset.attachmentId;
    // 插入芯片时顺带放了一个分隔空格，一起拿掉，免得反复加减后留下一串空格
    const gap = chip.nextSibling;
    if (gap && gap.nodeType === Node.TEXT_NODE && gap.nodeValue === ' ') {
      gap.remove();
    }
    chip.remove();
    refresh();
    if (id) {
      onRemove(id);
    }
    onChange();
  }, { signal: listeners.signal });

  root.addEventListener('input', () => {
    refresh();
    onChange();
  }, { signal: listeners.signal });

  root.addEventListener('paste', (event) => {
    const data = event.clipboardData;
    if (!data || readTransferFiles(data).length) {
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

  refresh();

  return {
    root,
    destroy() { listeners.abort(); },
    notifyChange() { refresh(); onChange(); },

    getText() {
      return serialize(root).text;
    },

    /** 文字 + 每张图片在文字里的位置（文件芯片不占位置：它们的内容服务端会拼在正文后面）。 */
    getDraft() {
      const { text, positions } = serialize(root);
      return { text, imagePositions: positions };
    },

    attachmentIds() {
      return [...root.querySelectorAll('[data-chip="attachment"]')].map((chip) => chip.dataset.attachmentId);
    },

    hasChips() {
      return Boolean(root.querySelector('[data-chip]'));
    },

    /** 整个草稿替换成纯文字，芯片一并清掉。 */
    setText(text) {
      root.replaceChildren(...(text ? [document.createTextNode(String(text))] : []));
      refresh();
      onChange();
    },

    /** 在草稿末尾追加文字，已有的芯片保持不动。 */
    appendText(text) {
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
    caretOnEdgeLine(edge) {
      const before = this.textBeforeCaret();
      const after = this.textAfterCaret();
      return edge === 'first' ? !before.includes('\n') : !after.includes('\n');
    },

    insertAttachments(items) {
      const nodes = [];
      items.forEach((item) => {
        nodes.push(attachmentChip(item), document.createTextNode(' '));
      });
      if (nodes.length) {
        insertAtCaret(nodes);
        refresh();
        onChange();
      }
    },

    /**
     * 选中一个 @ 文件：把光标前正在输入的 `@查询` 换成引用芯片。
     * 找不到那段查询（光标不在文字节点里）就直接在光标处插入。
     */
    insertMention(path) {
      const chip = mentionChip(path);
      const gap = document.createTextNode(' ');
      selectTrigger('@');
      insertAtCaret([chip, gap]);
      refresh();
      onChange();
    },

    replaceCommand(text) {
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

    restore(nodes) {
      root.replaceChildren(...(nodes || []));
      refresh();
    }
  };
}
