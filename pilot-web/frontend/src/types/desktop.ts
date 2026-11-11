/** 对照 pilot-desktop/preload.cjs 和 main.mjs 的隔离桌面桥。 */
export interface DesktopAction { ok: boolean; error?: string; text?: string; url?: string }
/** 本机目录多选结果：取消时 canceled=true、paths 为空；远程服务或弹框失败时返回 code/message。 */
export interface SelectDirectoriesResult { ok: boolean; canceled?: boolean; paths?: string[]; code?: string; message?: string }
export interface BrowserRecent { url: string; title?: string; favicon?: string }
export interface BrowserEmulation { width: number; height: number }
export interface DesktopBrowserTab extends BrowserRecent { id: string; loading?: boolean; sessionId?: string | null; detached?: boolean; emulated?: BrowserEmulation | null }
export interface DesktopBrowserState {
  tabs: DesktopBrowserTab[]; activeId: string | null; url: string; canGoBack: boolean; canGoForward: boolean;
  loading: boolean; cover: string | null; emulated: BrowserEmulation | null; error: string; recents: BrowserRecent[];
}
export interface DesktopBridge {
  onBrowserOpen(callback: (url: string) => void): () => void;
  onNavigationNotice(callback: (message: string) => void): () => void;
  reloadWorkbench(): Promise<DesktopAction>;
  onWorkbenchCommand(callback: (command: string) => void): () => void;
  copyPairingCode(code: string): Promise<DesktopAction>;
  /** 旧版桌面桥没有此方法，调用前需可选判断；远程服务下返回 code=REMOTE_WORKBENCH。 */
  selectDirectories?(): Promise<SelectDirectoriesResult>;
  onMobileConnection(callback: () => void): () => void;
  openMobileConnection(): Promise<DesktopAction>;
  ensureTab(sessionId: string | null): Promise<DesktopBrowserState>;
  navigate(url: string): Promise<DesktopAction>;
  back(): Promise<DesktopAction>; forward(): Promise<DesktopAction>; reload(): Promise<DesktopAction>; stop(): Promise<DesktopAction>;
  newTab(): Promise<DesktopBrowserState>; closeTab(id: string): Promise<DesktopBrowserState>; selectTab(id: string): Promise<DesktopBrowserState>;
  detach(): Promise<DesktopBrowserState>; devtools(): Promise<DesktopAction>; logs(network: boolean): Promise<DesktopAction>;
  openMenu(): Promise<string>; find(text: string): Promise<DesktopAction>;
  onLog(callback: (entry: { network: boolean; line: string }) => void): () => void;
  onQuote(callback: (text: string) => void): () => void;
  onDownload(callback: (info: { state: string; name: string; path?: string }) => void): () => void;
  pick(): Promise<DesktopAction>; resetEmulation(): Promise<DesktopBrowserState>;
  setBounds(bounds: { x: number; y: number; width: number; height: number; visible: boolean }): void;
  setWorkspace(workspace: string): void;
  onState(callback: (state: DesktopBrowserState) => void): () => void;
  focusAddress(callback: () => void): () => void;
}
