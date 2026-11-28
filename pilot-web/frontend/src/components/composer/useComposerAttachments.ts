import { useEffect, useRef, useState, type RefObject } from 'react';
import type { DraftAttachment } from '../../types/input.js';
import type { createComposerInput } from '../../lib/composer-input.js';
import { api } from '../../api.js';
import { useStore } from '../../store.js';
import { errorInfo } from '../../lib/errors.js';
import { readTransferFiles, hasFileTransferHint, readTransferFilePaths, transferDirectoryNames } from '../../lib/clipboard.js';
import { readAttachmentFile, readDesktopClipboardFiles } from '../../lib/attachments/input.js';
import { checkIncomingFile, requireAttachmentLimits } from '../../lib/attachments/attachments.js';
import { isModalBlockingShortcuts } from '../../lib/interaction.js';

export type ComposerInputHandle = ReturnType<typeof createComposerInput>;

export interface AttachmentSource {
  name: string;
  size: number;
  mimeType?: string;
  load: () => Promise<DraftAttachment>;
}

export interface UseComposerAttachmentsOptions {
  flowRef: RefObject<HTMLDivElement | null>;
  inputRef: RefObject<ComposerInputHandle | null>;
  draftFilesRef: RefObject<Map<string, DraftAttachment>>;
  submittingRef: RefObject<boolean>;
}

function createDraftOwnerGuard() {
  const { sessionId: ownerSession, sessionEpoch: ownerEpoch } = useStore.getState();
  return () => {
    const current = useStore.getState();
    return current.sessionId === ownerSession && current.sessionEpoch === ownerEpoch;
  };
}

async function attachmentLimits() {
  const settings = useStore.getState().settingsData || await api.settings();
  return requireAttachmentLimits(settings?.attachmentLimits);
}

/**
 * 负责输入框的附件生命周期：窗口拖放、粘贴、串行预算校验队列与桌面端剪贴板文件读取。
 */
export function useComposerAttachments({
  flowRef,
  inputRef,
  draftFilesRef,
  submittingRef
}: UseComposerAttachmentsOptions) {
  const fileQueueRef = useRef(Promise.resolve());
  const pendingFilesRef = useRef(0);
  const [pendingFiles, setPendingFiles] = useState(0);
  const [dragging, setDragging] = useState(false);

  function currentDraftFiles(input: ComposerInputHandle | null = inputRef.current): DraftAttachment[] {
    return (input?.attachmentIds() || [])
      .map((id) => draftFilesRef.current.get(id))
      .filter((item): item is DraftAttachment => Boolean(item));
  }

  function adjustPendingFiles(delta: number) {
    pendingFilesRef.current += delta;
    setPendingFiles(pendingFilesRef.current);
  }

  /** 所有入口（选择器、拖放、粘贴、桌面剪贴板路径）都经过同一条队列，保证预算和顺序一致。 */
  function queueAttachments(sources: AttachmentSource[]) {
    if (!sources.length) return;
    if (submittingRef.current) {
      useStore.getState().showNotice('正在发送当前消息，附件将在发送完成后才能继续添加。');
      return;
    }
    const ownsDraft = createDraftOwnerGuard();
    adjustPendingFiles(sources.length);
    fileQueueRef.current = fileQueueRef.current.then(async () => {
      for (const source of sources) {
        try {
          if (!ownsDraft()) continue;
          const existing = currentDraftFiles();
          const limits = await attachmentLimits();
          if (!ownsDraft()) continue;
          const result = checkIncomingFile(existing, source, limits);
          if (!result.ok) {
            if (!result.silent) useStore.getState().showNotice(result.reason);
            continue;
          }
          const item = await source.load();
          if (!ownsDraft() || !inputRef.current) continue;
          // 按「同名 + 同大小 + 同内容」去重：同一张截图粘贴两次会被忽略，
          // 不同文件即使内容完全相同（例如两个都叫 hello 的 txt）也各自保留。
          const duplicate = currentDraftFiles().some((current) =>
            current.name === item.name && current.size === item.size && current.dataBase64 === item.dataBase64
          );
          if (duplicate) continue;
          draftFilesRef.current.set(item.id, item);
          inputRef.current.insertAttachments([item]);
          inputRef.current.focus();
        } catch (error) {
          useStore.getState().showNotice('读取附件失败：' + errorInfo(error).message);
        } finally {
          adjustPendingFiles(-1);
        }
      }
    });
  }

  function addFiles(fileList: ArrayLike<File> | null) {
    const files = Array.from(fileList || []);
    queueAttachments(files.map((file) => ({
      name: file.name || '未命名文件',
      size: file.size,
      mimeType: file.type,
      load: () => readAttachmentFile(file)
    })));
  }

  function addDesktopAttachments(items: DraftAttachment[]) {
    queueAttachments(items.map((item) => ({
      name: item.name,
      size: item.size,
      mimeType: item.mimeType,
      load: async () => item
    })));
  }

  /**
   * 系统文件管理器只把文件路径放进剪贴板时（尤其桌面端 Cmd/Ctrl+V），
   * 浏览器剪贴板事件里没有 File，需要主进程按路径读取内容。
   */
  async function addClipboardAttachments() {
    if (submittingRef.current) return;
    const ownsDraft = createDraftOwnerGuard();
    adjustPendingFiles(1);
    try {
      const items = await readDesktopClipboardFiles(await attachmentLimits());
      if (!ownsDraft()) return;
      if (!items.length) throw new Error('系统剪贴板中没有可读取的文件。');
      addDesktopAttachments(items);
    } catch (error) {
      useStore.getState().showNotice('读取剪贴板文件失败：' + errorInfo(error).message);
    } finally {
      adjustPendingFiles(-1);
    }
  }

  async function receiveFiles(
    event: { preventDefault: () => void },
    transfer: DataTransfer,
    source: 'paste' | 'drop'
  ) {
    const files = readTransferFiles(transfer);
    if (files.length) {
      event.preventDefault();
      addFiles(files);
      return;
    }
    if (!hasFileTransferHint(transfer)) return;
    event.preventDefault();
    if (source === 'paste' && window.pilotDesktop?.readClipboardFiles) {
      await addClipboardAttachments();
      return;
    }
    if (readTransferFilePaths(transfer).length) {
      useStore.getState().showNotice('当前环境未提供文件内容，只能拿到文件路径；请使用桌面版，或把文件直接拖入输入框。');
      return;
    }
    useStore.getState().showNotice('当前环境未提供文件内容，请拖入文件或使用“添加文件或图片”。');
  }

  /*
   * 文件拖到窗口任意位置都算「拖进输入框」：浏览器默认会用文件替换整页，
   * 只绑在输入框上则拖到侧栏或空白处会静默失败，用户看到的就是「拖了没反应」。
   * 落点在输入框内时按鼠标位置插入，落在别处则追加到草稿末尾。
   */
  useEffect(() => {
    const fileDrag = (event: DragEvent) => hasFileTransferHint(event.dataTransfer);
    const enter = (event: DragEvent) => {
      if (!fileDrag(event)) return;
      event.preventDefault();
      setDragging(true);
    };
    const over = (event: DragEvent) => {
      if (!fileDrag(event)) return;
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
    };
    const leave = (event: DragEvent) => {
      // relatedTarget 为 null 表示真正离开窗口；在元素间移动不算
      if (!event.relatedTarget && !fileDrag(event)) return;
      if (!event.relatedTarget) setDragging(false);
    };
    const drop = (event: DragEvent) => {
      setDragging(false);
      if (!fileDrag(event)) return;
      event.preventDefault();
      // 弹窗打开时不把文件加进背后的输入框，避免用户以为拖进了当前表单
      if (isModalBlockingShortcuts()) return;
      const folders = transferDirectoryNames(event.dataTransfer);
      if (folders.length) {
        const suffix = folders.length > 3 ? ' 等' : '';
        useStore.getState().showNotice(
          `不能直接添加文件夹（${folders.slice(0, 3).join('、')}${suffix}）；请压缩后再拖入，或拖入其中的文件。`
        );
        return;
      }
      const target = event.target;
      if (flowRef.current && target instanceof Node && flowRef.current.contains(target)) {
        inputRef.current?.placeCaretAtPoint(event.clientX, event.clientY);
      } else {
        inputRef.current?.focusEnd();
      }
      if (event.dataTransfer) void receiveFiles(event, event.dataTransfer, 'drop');
    };
    window.addEventListener('dragenter', enter);
    window.addEventListener('dragover', over);
    window.addEventListener('dragleave', leave);
    window.addEventListener('drop', drop);
    return () => {
      window.removeEventListener('dragenter', enter);
      window.removeEventListener('dragover', over);
      window.removeEventListener('dragleave', leave);
      window.removeEventListener('drop', drop);
    };
  }, []);

  return {
    dragging,
    pendingFiles,
    pendingFilesRef,
    currentDraftFiles,
    queueAttachments,
    addFiles,
    addClipboardAttachments,
    receiveFiles
  };
}
