import { useEffect, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { SegmentedControl, SegmentedControlItem } from "@astryxdesign/core/SegmentedControl";
import { TextInput } from "@astryxdesign/core/TextInput";
import { useStore } from "../../store.js";
import { isDndActive, playAlertSound, type AlertSoundKind } from "../../lib/sound.js";
import { platformModifier } from "../../lib/interaction.js";
import { resolveTheme } from "../../lib/theme.js";
import type { ComposerSendKey, FloatingAlertMode, SoundVolumeLevel } from "../../types/store.js";

const SOUND_VOLUMES: ReadonlyArray<{ value: SoundVolumeLevel; label: string }> = [
  { value: 'gentle', label: '轻柔' },
  { value: 'standard', label: '标准' },
  { value: 'crisp', label: '清脆' }
];

const FLOATING_MODES: ReadonlyArray<{ value: FloatingAlertMode; label: string }> = [
  { value: 'background', label: '切出窗口时弹出' },
  { value: 'always', label: '始终弹出' },
  { value: 'off', label: '关闭' }
];

const SEND_KEYS: ReadonlyArray<{ value: ComposerSendKey; label: string }> = [
  { value: 'enter', label: 'Enter 发送' },
  { value: 'mod-enter', label: `${platformModifier()}+Enter 发送` }
];

const SOUND_SCENARIOS: ReadonlyArray<{
  key: 'soundOnDone' | 'soundOnApproval' | 'soundOnQuestion' | 'soundOnError';
  kind: AlertSoundKind;
  title: string;
  desc: string;
}> = [
  { key: 'soundOnDone', kind: 'done', title: '任务完成提示音', desc: '类似 Codex 的柔和双音谐波，表示本轮执行结束。' },
  { key: 'soundOnApproval', kind: 'approval', title: '等待授权提示音', desc: '需要批准命令、文件写入或 MCP 工具时提醒你回来确认。' },
  { key: 'soundOnQuestion', kind: 'question', title: '等待选择提示音', desc: '智能体发起 ask_user 提问时发出清晰问询声。' },
  { key: 'soundOnError', kind: 'error', title: '运行失败提示音', desc: '任务中止、网络或工具异常时提示检查结果。' }
];

export function AlertSettings() {
  const prefs = useStore((state) => state.alertPrefs);
  const updateAlertPrefs = useStore((state) => state.updateAlertPrefs);
  const notifyEnabled = useStore((state) => state.notifyEnabled);
  const toggleNotify = useStore((state) => state.toggleNotify);
  const themeMode = useStore((state) => state.ui.themeMode);
  const sessionId = useStore((state) => state.sessionId);
  const sessionTitle = useStore((state) => state.sessionTitle);
  const [feedback, setFeedback] = useState('');
  const [shortcutDraft, setShortcutDraft] = useState(() => prefs.globalShortcut || '');

  useEffect(() => {
    setShortcutDraft(prefs.globalShortcut || '');
  }, [prefs.globalShortcut]);

  const desktop = typeof window !== 'undefined' ? window.pilotDesktop : undefined;
  const hasDesktopAlerts = Boolean(desktop?.showFloatingAlert);
  const notificationSupported = typeof Notification !== 'undefined';
  const permission = notificationSupported ? Notification.permission : 'unsupported';
  const dndActive = isDndActive(prefs);
  const dndStatus = !dndActive
    ? '未开启'
    : prefs.dndUntil === -1
      ? '已开启（手动关闭前保持静音与隐身）'
      : `已开启（剩余约 ${Math.max(1, Math.ceil(((prefs.dndUntil || 0) - Date.now()) / 60000))} 分钟）`;

  const previewSound = async (kind: AlertSoundKind) => {
    const ok = await playAlertSound(kind, { prefs, force: true });
    setFeedback(ok ? '' : '当前环境无法播放音频，请先点击页面或检查系统音量设置。');
  };

  const previewFloating = async () => {
    if (!desktop?.showFloatingAlert) return;
    const theme = resolveTheme(themeMode) === 'dark' ? 'dark' : 'light';
    const result = await desktop.showFloatingAlert({
      id: `preview:${Date.now()}`,
      kind: 'done',
      sessionId: sessionId || '',
      sessionTitle: sessionTitle || '当前工作台',
      title: '提醒悬浮窗预览',
      body: '当任务需要授权、选择或完成时，将在主窗口外独立浮出该提醒。',
      categoryLabel: '测试预览',
      autoCloseMs: 6000,
      theme
    });
    if (!result?.ok) {
      setFeedback(result?.error || '悬浮窗预览失败，请确认桌面窗口处于运行状态。');
    } else {
      setFeedback('已在屏幕角落弹出测试悬浮窗。');
    }
  };

  const applyGlobalShortcut = async (nextAccelerator: string) => {
    const value = nextAccelerator.trim();
    if (!desktop?.setGlobalShortcut) {
      setFeedback('全局呼出快捷键仅在桌面客户端中可用；网页端可使用标签页快捷键。');
      return;
    }
    const res = await desktop.setGlobalShortcut(value);
    if (!res?.ok) {
      setFeedback(res?.error || '注册全局快捷键失败，请更换其他组合。');
      return;
    }
    updateAlertPrefs({ globalShortcut: res.accelerator || '' });
    setShortcutDraft(res.accelerator || '');
    setFeedback(res.accelerator ? `已绑定全局呼出快捷键：${res.accelerator}` : '已清除全局呼出快捷键。');
  };

  const testNativeNotification = async () => {
    if (desktop?.showNativeNotification) {
      const result = await desktop.showNativeNotification({
        id: `test:${Date.now()}`,
        title: 'Pilot 测试通知',
        body: '跨平台系统通知与窗口焦点联动正常。',
        sessionId: sessionId || ''
      });
      setFeedback(result?.ok ? '已发送系统通知。' : result?.error || '系统通知被平台限制或未授权。');
      return;
    }
    if (!notificationSupported) {
      setFeedback('当前浏览器不支持 Notification 接口，可使用页面提示音或收件箱 Webhook。');
      return;
    }
    try {
      const granted = permission === 'granted' ? 'granted' : await Notification.requestPermission();
      if (granted !== 'granted') {
        setFeedback('浏览器未授予通知权限，请在地址栏权限设置中开启。');
        return;
      }
      new Notification('Pilot 测试通知', { body: '网页版提醒与标签页状态已就绪。', silent: true });
      setFeedback('已触发浏览器系统通知。');
    } catch {
      setFeedback('触发浏览器通知失败，请检查系统专注模式或权限设置。');
    }
  };

  return (
    <div className="alert-settings-list">
      <section className="settings-group">
        <div className="settings-group-heading">
          <h3>输入与发送习惯</h3>
          <p>按你的写字节奏选择回车方式；分析 / 执行模式也可用 Shift+Tab 一键切换。</p>
        </div>
        <div className="settings-row">
          <div>
            <h4>消息发送快捷键</h4>
            <p>长需求建议用「{platformModifier()}+Enter 发送」，单独按 Enter 更安心换行。</p>
          </div>
          <SegmentedControl
            id="alert-send-key"
            label="消息发送快捷键"
            value={prefs.sendKey}
            onChange={(value) => updateAlertPrefs({ sendKey: value as ComposerSendKey })}
          >
            {SEND_KEYS.map((item) => (
              <SegmentedControlItem key={item.value} value={item.value} label={item.label} />
            ))}
          </SegmentedControl>
        </div>
      </section>

      <section className="settings-group">
        <div className="settings-group-heading">
          <h3>勿扰模式</h3>
          <p>开会或录屏期间一键暂停提示音、悬浮弹窗与系统通知（{dndStatus}）。</p>
        </div>
        <div className="settings-row">
          <div>
            <h4>快速开关勿扰</h4>
            <p>状态栏与系统托盘会保留真实待办数量，关闭勿扰后恢复实时提示。</p>
          </div>
          <div className="alert-sound-actions">
            <Button size="sm" variant={!dndActive ? 'primary' : 'ghost'} label="正常提醒"
              onClick={() => updateAlertPrefs({ dndUntil: null })} />
            <Button size="sm" variant="ghost" label="30 分钟"
              onClick={() => updateAlertPrefs({ dndUntil: Date.now() + 30 * 60_000 })} />
            <Button size="sm" variant="ghost" label="2 小时"
              onClick={() => updateAlertPrefs({ dndUntil: Date.now() + 120 * 60_000 })} />
            <Button size="sm" variant={prefs.dndUntil === -1 ? 'primary' : 'ghost'} label="一直勿扰"
              onClick={() => updateAlertPrefs({ dndUntil: -1 })} />
          </div>
        </div>
      </section>

      <section className="settings-group">
        <div className="settings-group-heading">
          <h3>提示音（Web Audio 合成）</h3>
          <p>内置跨平台轻音色提示，无需额外音频文件，Windows / macOS / Linux 与网页端均可用。</p>
        </div>
        <div className="settings-row">
          <div>
            <h4>启用任务提示音</h4>
            <p>任务完成、等待选择、等待授权或出错时发出轻微音频提示。</p>
          </div>
          <Button
            size="sm"
            variant={prefs.soundEnabled ? 'primary' : 'ghost'}
            label={prefs.soundEnabled ? '已开启' : '已关闭'}
            aria-pressed={prefs.soundEnabled}
            onClick={() => updateAlertPrefs({ soundEnabled: !prefs.soundEnabled })}
          />
        </div>
        <div className="settings-row">
          <div>
            <h4>提示音量</h4>
            <p>按工作环境选择更柔和或更明显的音量档位。</p>
          </div>
          <SegmentedControl
            id="alert-sound-volume"
            label="提示音量"
            value={prefs.soundVolume}
            onChange={(value) => updateAlertPrefs({ soundVolume: value as SoundVolumeLevel })}
          >
            {SOUND_VOLUMES.map((item) => (
              <SegmentedControlItem key={item.value} value={item.value} label={item.label} />
            ))}
          </SegmentedControl>
        </div>
        <div className="settings-row">
          <div>
            <h4>仅在后台窗口播放</h4>
            <p>开启后，当前正停留在 Pilot 窗口内时不响铃，只在切到其他应用或后台标签时提示。</p>
          </div>
          <Button
            size="sm"
            variant={prefs.soundBackgroundOnly ? 'primary' : 'ghost'}
            label={prefs.soundBackgroundOnly ? '仅后台响铃' : '前后台都提示'}
            aria-pressed={prefs.soundBackgroundOnly}
            onClick={() => updateAlertPrefs({ soundBackgroundOnly: !prefs.soundBackgroundOnly })}
          />
        </div>
        <div className="alert-sound-grid">
          {SOUND_SCENARIOS.map((item) => {
            const enabled = prefs[item.key];
            return (
              <div key={item.key} className="alert-sound-row">
                <div className="alert-sound-meta">
                  <strong>{item.title}</strong>
                  <span>{item.desc}</span>
                </div>
                <div className="alert-sound-actions">
                  <Button size="sm" variant="ghost" label="试听" onClick={() => void previewSound(item.kind)} />
                  <Button
                    size="sm"
                    variant={enabled ? 'primary' : 'ghost'}
                    label={enabled ? '开启' : '关闭'}
                    aria-pressed={enabled}
                    onClick={() => updateAlertPrefs({ [item.key]: !enabled })}
                  />
                </div>
              </div>
            );
          })}
        </div>
      </section>

      <section className="settings-group">
        <div className="settings-group-heading">
          <h3>独立悬浮提醒与桌面常驻（不受限于主窗口）</h3>
          <p>在桌面客户端中，遇到审批或提问可直接浮出交互卡片，并支持托盘常驻、防休眠与全局呼出。</p>
        </div>
        {hasDesktopAlerts ? (
          <>
            <div className="settings-row">
              <div>
                <h4>悬浮窗弹出时机</h4>
                <p>默认在你切换到其他应用或最小化主窗口时弹出，避免遮挡当前输入。</p>
              </div>
              <SegmentedControl
                id="alert-floating-mode"
                label="悬浮窗模式"
                value={prefs.floatingMode}
                onChange={(value) => updateAlertPrefs({ floatingMode: value as FloatingAlertMode })}
              >
                {FLOATING_MODES.map((item) => (
                  <SegmentedControlItem key={item.value} value={item.value} label={item.label} />
                ))}
              </SegmentedControl>
            </div>
            <div className="settings-row">
              <div>
                <h4>测试桌面悬浮窗</h4>
                <p>检查 macOS 右上角或 Windows / Linux 右下角浮窗显示效果，弹出不抢占当前输入焦点。</p>
              </div>
              <Button size="sm" variant="ghost" label="预览悬浮窗" onClick={() => void previewFloating()} />
            </div>
            <div className="settings-row">
              <div>
                <h4>任务栏 / 程序坞徽标闪烁</h4>
                <p>存在待处理事项时更新 macOS Dock 数量角标，或在 Windows / Linux 闪烁任务栏图标。</p>
              </div>
              <Button
                size="sm"
                variant={prefs.taskbarAttention ? 'primary' : 'ghost'}
                label={prefs.taskbarAttention ? '已开启' : '已关闭'}
                aria-pressed={prefs.taskbarAttention}
                onClick={() => updateAlertPrefs({ taskbarAttention: !prefs.taskbarAttention })}
              />
            </div>
            <div className="settings-row">
              <div>
                <h4>关闭窗口时隐藏到系统托盘</h4>
                <p>开启后点窗口关闭按钮会收进托盘继续运行，彻底退出请通过托盘菜单选择。</p>
              </div>
              <Button
                size="sm"
                variant={prefs.minimizeToTray ? 'primary' : 'ghost'}
                label={prefs.minimizeToTray ? '已开启' : '已关闭'}
                aria-pressed={prefs.minimizeToTray}
                onClick={() => updateAlertPrefs({ minimizeToTray: !prefs.minimizeToTray })}
              />
            </div>
            <div className="settings-row">
              <div>
                <h4>运行期间防止系统休眠</h4>
                <p>有任务在执行时阻止电脑休眠（允许熄屏），全部完成后自动释放锁。</p>
              </div>
              <Button
                size="sm"
                variant={prefs.keepAwake ? 'primary' : 'ghost'}
                label={prefs.keepAwake ? '已开启' : '已关闭'}
                aria-pressed={prefs.keepAwake}
                onClick={() => updateAlertPrefs({ keepAwake: !prefs.keepAwake })}
              />
            </div>
            <div className="settings-row">
              <div>
                <h4>全局呼出快捷键</h4>
                <p>如 Alt+Space 或 CommandOrControl+Shift+P，从任何应用一键唤起主窗并聚焦输入框。</p>
              </div>
              <div className="alert-sound-actions">
                <TextInput
                  label="全局呼出快捷键"
                  isLabelHidden
                  size="sm"
                  placeholder="例如 CommandOrControl+Shift+P"
                  value={shortcutDraft}
                  onChange={setShortcutDraft}
                />
                <Button size="sm" variant="primary" label="应用" onClick={() => void applyGlobalShortcut(shortcutDraft)} />
                {prefs.globalShortcut && (
                  <Button size="sm" variant="ghost" label="清除" onClick={() => void applyGlobalShortcut('')} />
                )}
              </div>
            </div>
          </>
        ) : (
          <div className="alert-env-note" role="note">
            <strong>当前正在浏览器标签页中使用</strong>
            <p>
              浏览器受系统沙箱限制，无法创建脱离浏览器窗口的置顶悬浮窗或注册系统托盘/全局热键；当前已自动启用：
              页面提示音、标签页标题计数徽标、跨会话顶栏横幅、浏览器系统通知与收件箱 Webhook。如需脱离窗口的一键审批卡片，请在桌面版中使用。
            </p>
          </div>
        )}
      </section>

      <section className="settings-group">
        <div className="settings-group-heading">
          <h3>系统通知与多端投递</h3>
          <p>
            当前系统通知权限：
            {permission === 'granted' ? '已授权' : permission === 'denied' ? '已被拒绝' : permission === 'default' ? '待授权' : '桌面桥/受限环境'}
          </p>
        </div>
        <div className="settings-row">
          <div>
            <h4>后台系统通知</h4>
            <p>窗口位于后台时发送系统原生横幅通知（点击可聚焦并跳回对应会话）。</p>
          </div>
          <div className="alert-sound-actions">
            {hasDesktopAlerts ? (
              <Button
                size="sm"
                variant={prefs.desktopNotifyOnBackground ? 'primary' : 'ghost'}
                label={prefs.desktopNotifyOnBackground ? '已开启' : '已关闭'}
                aria-pressed={prefs.desktopNotifyOnBackground}
                onClick={() => updateAlertPrefs({ desktopNotifyOnBackground: !prefs.desktopNotifyOnBackground })}
              />
            ) : (
              <Button
                size="sm"
                variant={notifyEnabled ? 'primary' : 'ghost'}
                label={notifyEnabled ? '已开启' : '已关闭'}
                aria-pressed={notifyEnabled}
                onClick={() => void toggleNotify()}
              />
            )}
            <Button size="sm" variant="ghost" label="发送测试通知" onClick={() => void testNativeNotification()} />
          </div>
        </div>
        <div className="settings-row">
          <div>
            <h4>收件箱与 Webhook 投递</h4>
            <p>配置断线后补发桌面通知，或推送到飞书 / 钉钉 / 自定义 Webhook。</p>
          </div>
          <Button
            size="sm"
            variant="ghost"
            label="打开投递与收件箱设置"
            onClick={() => useStore.getState().setUi({ settingsOpen: false, notificationsOpen: true })}
          />
        </div>
      </section>

      {feedback && <p className="settings-status" role="status">{feedback}</p>}
    </div>
  );
}
