import type { DesktopBridge, DesktopBrowserState, BrowserRecent } from '../../types/desktop.js';
import { errorInfo } from '../../lib/errors.js';
import React, { useEffect, useRef, useState } from 'react';
import { useStore } from '../../store.js';
import { api } from '../../api.js';
import { userPageUrl } from '../../lib/browser-mirror.js';

/* ---------- 内置浏览器 ---------- */

/**
 * 右侧「浏览器」页签：一个真正能用的内置浏览器。
 *
 * 本机页面直接嵌进面板，由浏览器引擎自己排版和滚动。
 * 页面禁止被嵌入时才退回截图，点击和滚轮仍转发给同一个 Playwright 页面。
 */
/** 原生页面不吃 CSS 裁剪。按祖先的 overflow 把矩形收到真正看得见的那一块。 */

function visibleStageRect(element: HTMLElement) {
  const rect = element.getBoundingClientRect();
  let top = rect.top;
  let left = rect.left;
  let right = rect.right;
  let bottom = rect.bottom;
  let node = element.parentElement;
  while (node && node !== document.documentElement) {
    const style = getComputedStyle(node);
    const clipsX = /hidden|clip|scroll|auto/.test(style.overflowX);
    const clipsY = /hidden|clip|scroll|auto/.test(style.overflowY);
    if (clipsX || clipsY) {
      const box = node.getBoundingClientRect();
      if (clipsX) {
        left = Math.max(left, box.left);
        right = Math.min(right, box.right);
      }
      if (clipsY) {
        top = Math.max(top, box.top);
        bottom = Math.min(bottom, box.bottom);
      }
    }
    node = node.parentElement;
  }
  for (const host of [document.getElementById('activity'), element.closest('#panel-browser')]) {
    if (!host) continue;
    const box = host.getBoundingClientRect();
    left = Math.max(left, box.left);
    top = Math.max(top, box.top);
    right = Math.min(right, box.right);
    bottom = Math.min(bottom, box.bottom);
  }
  left = Math.max(0, left);
  top = Math.max(0, top);
  right = Math.min(window.innerWidth, right);
  bottom = Math.min(window.innerHeight, bottom);
  return {
    x: left,
    y: top,
    width: Math.max(0, right - left),
    height: Math.max(0, bottom - top)
  };
}

function recentHost(url: string) {
  try {
    return new URL(url).host;
  } catch {
    return String(url || '').replace(/^https?:\/\//, '');
  }
}

function RecentMark({ item }: { item: BrowserRecent }) {
  const [broken, setBroken] = useState(false);
  const mark = (item.title || item.url || '·').trim().slice(0, 1).toUpperCase();
  if (!item.favicon || broken) return <span className="browser-recent-mark">{mark}</span>;
  return <img src={item.favicon} alt="" onError={() => setBroken(true)} />;
}

function cleanLaunchNote(text?: string) {
  return String(text || '')
    .replace(/（浏览器进程自上次使用后重启过[\s\S]*?）/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export function BrowserPanel({ toolsMenuOpen }: { toolsMenuOpen: boolean }) {
  const bridge = window.pilotDesktop;
  return bridge ? <DesktopBrowserPanel desktopBridge={bridge} toolsMenuOpen={toolsMenuOpen} /> : <WebBrowserLauncher />;
}

function WebBrowserLauncher() {
  const sessionId = useStore(state => state.sessionId);
  const mirror = useStore(state => state.ui.mirror);
  const [launching, setLaunching] = useState(false);
  const [launchNote, setLaunchNote] = useState('');
  const [launchFailed, setLaunchFailed] = useState(false);
  useEffect(() => {
    if (sessionId) useStore.getState().ensureMirror();
  }, [sessionId]);
  async function openDesktopWindow() {
    setLaunching(true);
    setLaunchNote('');
    setLaunchFailed(false);
    try {
      const data = await api.openBrowserWindow();
      const message = cleanLaunchNote(data?.message);
      if (data?.ok === false) {
        setLaunchFailed(true);
        setLaunchNote(message || '没能打开浏览器窗口');
      } else {
        setLaunchNote(message || '窗口已打开。');
      }
    } catch (failure) {
      setLaunchFailed(true);
      const message = errorInfo(failure).message || '';
      setLaunchNote(/404|Not Found/.test(message)
        ? '服务还是刚才那一版。重新从 IDEA 启动一次后再点。'
        : (message || '没能打开浏览器窗口'));
    } finally {
      setLaunching(false);
    }
  }
  const externalUrl = /^https?:\/\//i.test(mirror.url || '') ? mirror.url : null;
  return (
    <div id="panel-browser" className="panel browser-panel">
      <div className="mirror-stage">
        <div className="browser-web-launch">
          <p>画面在单独的窗口里，点一下才打开。</p>
          <button type="button" className="btn btn-primary" disabled={launching} onClick={openDesktopWindow}>
            {launching ? '正在打开…' : (/已打开|窗口已/.test(launchNote) ? '显示窗口' : '打开内置浏览器')}
          </button>
          {launchNote && <p className="browser-web-note" role={launchFailed ? 'alert' : 'status'}>{launchNote}</p>}
          {externalUrl && <a className="btn" href={externalUrl} target="_blank" rel="noreferrer">在新标签页打开当前网址</a>}
          {!launchNote && mirror.headless && (
            <p className="browser-web-note">
              智能体的浏览器在后台无窗口运行；需要真实窗口共看时把 pilot.browser.headless 设为 false 重启。
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

function DesktopBrowserPanel({ desktopBridge, toolsMenuOpen }: { desktopBridge: DesktopBridge; toolsMenuOpen: boolean }) {
  const sessionId = useStore(state => state.sessionId);
  const workspace = useStore(state => state.workspace);
  const stageRef = useRef<HTMLDivElement>(null);
  const addressRef = useRef<HTMLInputElement>(null);
  const freshTabRef = useRef(false);
  const [address, setAddress] = useState('');
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState('');
  const [consoleOpen, setConsoleOpen] = useState(false);
  const [consoleText, setConsoleText] = useState('');
  const [networkLog, setNetworkLog] = useState(false);
  const [findOpen, setFindOpen] = useState(false);
  const [findText, setFindText] = useState('');
  const [desktopState, setDesktopState] = useState<DesktopBrowserState | null>(null);
  const freshTab = !desktopState?.url || desktopState.url.includes('/marker/');
  freshTabRef.current = freshTab;
  const pageLoading = Boolean(desktopState?.loading);

  useEffect(() => {
    desktopBridge.setWorkspace(workspace || '');
    if (sessionId) desktopBridge.ensureTab(sessionId);
    const stopState = desktopBridge.onState(setDesktopState);
    const stopFocus = desktopBridge.focusAddress(() => addressRef.current?.focus());
    const stopDownload = desktopBridge.onDownload?.((info) => {
      const done = info?.state === 'completed';
      useStore.getState().showNotice(done ? `已下载 ${info.name}` : `下载没有完成：${info?.name || ''}`);
    });
    const stopQuote = desktopBridge.onQuote?.((text) => {
      const api = useStore.getState().composerApi;
      if (!text || !api) return;
      const quoted = String(text).split('\n').map((line) => `> ${line}`).join('\n');
      api.appendText(`\n> 页面摘录\n${quoted}\n\n`);
      api.focus();
    });
    return () => {
      stopState();
      stopFocus();
      stopDownload?.();
      stopQuote?.();
      desktopBridge.setBounds({ visible: false, x: 0, y: 0, width: 0, height: 0 });
    };
  }, [desktopBridge, sessionId, workspace]);

  useEffect(() => {
    if (editing) return;
    const url = desktopState?.url || '';
    setAddress(!url || url.includes('/marker/') ? '' : url);
  }, [desktopState?.url, editing]);

  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) return undefined;
    const push = () => {
      const rect = visibleStageRect(stage);
      const covered = toolsMenuOpen || document.querySelector('dialog[open], [aria-modal="true"]');
      desktopBridge.setBounds({
        x: rect.x,
        y: rect.y,
        width: rect.width,
        height: rect.height,
        visible: rect.width >= 40 && rect.height >= 40 && !freshTabRef.current
          && !covered && document.visibilityState === 'visible'
      });
    };
    const observer = new ResizeObserver(push);
    observer.observe(stage);
    window.addEventListener('resize', push);
    document.addEventListener('scroll', push, true);
    const timer = setInterval(push, 400);
    push();
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', push);
      document.removeEventListener('scroll', push, true);
      clearInterval(timer);
      desktopBridge.setBounds({ visible: false, x: 0, y: 0, width: 0, height: 0 });
    };
  }, [desktopBridge, toolsMenuOpen, desktopState?.url]);

  function submitAddress(event: React.FormEvent) {
    event.preventDefault();
    const value = address.trim();
    if (!value) { addressRef.current?.focus(); return; }
    const page = userPageUrl(value);
    setEditing(false);
    if (!page) { setError('地址无效'); return; }
    setError('');
    desktopBridge.navigate(page);
  }

  async function toggleConsole(network = false) {
    if (consoleOpen && networkLog === network) { setConsoleOpen(false); return; }
    const data = await desktopBridge.logs(network);
    setNetworkLog(network);
    setConsoleText(data?.text || (network ? '没有网络记录' : '没有控制台记录'));
    setConsoleOpen(true);
  }

  useEffect(() => {
    if (!consoleOpen || !desktopBridge.onLog) return undefined;
    return desktopBridge.onLog((entry) => {
      if (Boolean(entry?.network) !== networkLog || !entry?.line) return;
      setConsoleText((text) => (text && !text.startsWith('没有') ? `${text}\n${entry.line}` : entry.line));
    });
  }, [desktopBridge, consoleOpen, networkLog]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'l') return;
      event.preventDefault();
      addressRef.current?.focus();
      addressRef.current?.select();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [desktopBridge]);

  async function openDesktopMenu() {
    const action = await desktopBridge.openMenu();
    if (action === 'find') setFindOpen(true);
    if (action === 'console') toggleConsole(false);
    if (action === 'network') toggleConsole(true);
    if (action === 'pick') {
      const result = await desktopBridge.pick();
      if (!result?.text) return;
      const quoted = result.text.split('\n').map((line) => `> ${line}`).join('\n');
      const composer = useStore.getState().composerApi;
      if (composer) {
        composer.appendText(`\n> 页面元素引用\n${quoted}\n\n`);
        composer.focus();
      }
    }
  }

  const shownUrl = !editing ? (desktopState?.url || '') : address;
  let localPage = false;
  try {
    const host = new URL(shownUrl).hostname.replace(/^\[|\]$/g, '');
    localPage = host === 'localhost' || host === '127.0.0.1' || host === '::1';
  } catch { /* 还不是完整地址 */ }

  return (
    <div id="panel-browser" className="panel browser-panel is-desktop">
      <div className="browser-tabs" role="tablist" aria-label="浏览器标签">
        {(desktopState?.tabs || []).map((tab) => (
          <div key={tab.id} className={`browser-tab${tab.id === desktopState?.activeId ? ' is-on' : ''}`}>
            <button
              type="button"
              role="tab"
              aria-selected={tab.id === desktopState?.activeId}
              className="browser-tab-label"
              title={!tab.url || tab.url.includes('/marker/') ? (tab.title || '新标签') : tab.url}
              onClick={() => desktopBridge.selectTab(tab.id)}
            >
              {tab.loading
                ? <span className="browser-tab-spin" aria-hidden="true" />
                : (tab.favicon ? <img className="browser-tab-icon" src={tab.favicon} alt="" /> : null)}
              <span className="browser-tab-label-text">{tab.title || '新标签'}</span>
            </button>
            <button
              type="button"
              className="browser-tab-close"
              aria-label={`关闭 ${tab.title || '标签'}`}
              title="关闭标签"
              onClick={() => desktopBridge.closeTab(tab.id)}
            >×</button>
          </div>
        ))}
        <button type="button" className="browser-tab-add" title="新标签" aria-label="新标签" onClick={() => desktopBridge.newTab()}>+</button>
      </div>
      <div className="browser-chrome">
      <div className="browser-bar">
        <button
          type="button"
          className="icon-btn"
          aria-label="后退"
          title={desktopState?.canGoBack ? '后退' : '没有可后退的页面'}
          disabled={!desktopState?.canGoBack}
          onClick={() => desktopBridge.back()}
        >
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
        <button
          type="button"
          className="icon-btn"
          aria-label="前进"
          title={desktopState?.canGoForward ? '前进' : '没有可前进的页面'}
          disabled={!desktopState?.canGoForward}
          onClick={() => desktopBridge.forward()}
        >
          <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M6 3.5 10.5 8 6 12.5" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </button>
        <button
          type="button"
          className="icon-btn"
          aria-label={pageLoading ? '停止加载' : '刷新页面'}
          title={pageLoading ? '停止加载' : '刷新当前页面'}
          disabled={!desktopState?.activeId}
          onClick={() => (pageLoading ? desktopBridge.stop() : desktopBridge.reload())}
        >
          {pageLoading
            ? <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.4 4.4l7.2 7.2M11.6 4.4l-7.2 7.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" /></svg>
            : <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13 8a5 5 0 1 1-1.6-3.7M13 2.6V5.4h-2.8" stroke="currentColor" strokeWidth="1.4" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>}
        </button>
        <form className={`browser-address${pageLoading ? ' is-loading' : ''}`} onSubmit={submitAddress}>
          {localPage && !editing && !freshTab && <span className="browser-address-lock">本机</span>}
          <input
            ref={addressRef}
            className="browser-address-input mono"
            aria-label="地址栏"
            spellCheck={false}
            autoComplete="off"
            placeholder="搜索或输入网址"
            value={address}
            onChange={(event) => setAddress(event.target.value)}
            onFocus={(event) => { setEditing(true); event.target.select(); }}
            onBlur={() => setEditing(false)}
          />
        </form>
        <div className="browser-menu">
          <button
            type="button"
            className="icon-btn"
            aria-label="浏览器菜单"
            title="更多"
            onClick={openDesktopMenu}
          >
            <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="3.5" cy="8" r="1.15" fill="currentColor" /><circle cx="8" cy="8" r="1.15" fill="currentColor" /><circle cx="12.5" cy="8" r="1.15" fill="currentColor" /></svg>
          </button>
        </div>
      </div>
      {findOpen && (
        <form className="browser-find" onSubmit={(event) => { event.preventDefault(); desktopBridge.find(findText); }} onKeyDown={event => {
          if (event.key !== 'Escape' || event.nativeEvent.isComposing) return;
          event.preventDefault();
          setFindOpen(false);
          setFindText('');
          desktopBridge.find('');
          document.querySelector<HTMLButtonElement>('.browser-menu button')?.focus();
        }}>
          <input
            autoFocus
            aria-label="页内查找"
            placeholder="在页面中查找"
            value={findText}
            onChange={(event) => setFindText(event.target.value)}
          />
          <button type="submit">查找</button>
          <button type="button" onClick={() => { setFindOpen(false); setFindText(''); desktopBridge.find(''); }}>关闭</button>
        </form>
      )}
      </div>
      {(desktopState?.error || error) && <p className="browser-reason is-error">{desktopState?.error || error}</p>}
      <div
        id="mirror-stage"
        className={`mirror-stage${freshTab ? ' is-fresh' : ''}`}
        ref={stageRef}
        role="application"
        aria-label="内置浏览器画面"
      >
        {freshTab
          ? (
            <div className="browser-newt">
              <form onSubmit={submitAddress}>
                <input
                  aria-label="搜索或输入网址"
                  placeholder="搜索或输入网址"
                  spellCheck={false}
                  autoComplete="off"
                  value={address}
                  onChange={(event) => setAddress(event.target.value)}
                  onFocus={(event) => { setEditing(true); event.target.select(); }}
                  onBlur={() => setEditing(false)}
                />
              </form>
              {(desktopState?.recents || []).length > 0 && (
                <div className="browser-recents">
                  <p>最近访问</p>
                  {(desktopState?.recents || []).map((item) => (
                    <button
                      key={item.url}
                      type="button"
                      className="browser-recent"
                      onClick={() => desktopBridge.navigate(item.url)}
                    >
                      <RecentMark item={item} />
                      <span className="browser-recent-copy">
                        <strong>{item.title || recentHost(item.url)}</strong>
                        <em>{recentHost(item.url)}</em>
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )
          : (
            <div className="mirror-empty">
              {desktopState?.cover && <img className="browser-cover" src={desktopState.cover} alt="" />}
            </div>
          )}
      </div>
      {consoleOpen && (
        <div className="browser-console">
          <div className="browser-console-head">
            <span>页面控制台</span>
            <button type="button" className="icon-btn" aria-label="关闭控制台" title="关闭" onClick={() => setConsoleOpen(false)}>
              <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4.4 4.4l7.2 7.2M11.6 4.4l-7.2 7.2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" /></svg>
            </button>
          </div>
          <pre className="browser-console-body">{consoleText}</pre>
        </div>
      )}
      {desktopState?.emulated && (
        <div className="browser-status">
          <p id="mirror-hint" className="mirror-hint">
            {`设备模拟 ${desktopState.emulated.width}×${desktopState.emulated.height}`}
            <button type="button" className="browser-reset" onClick={() => desktopBridge.resetEmulation()}>还原</button>
          </p>
        </div>
      )}
    </div>
  );
}
