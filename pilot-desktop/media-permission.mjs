/** 主界面只申请音频；外部网页、子 frame 和摄像头不借用主界面的授权。 */
export function installMicrophonePermissions(contents, appUrl, systemPreferences, platform = process.platform) {
  const origin = new URL(appUrl).origin;
  const trusted = (source, url) => {
    if (source !== contents || contents.isDestroyed()) return false;
    try { return new URL(url).origin === origin && new URL(contents.getURL()).origin === origin; }
    catch { return false; }
  };
  contents.session.setPermissionCheckHandler((source, permission, requestingOrigin, details) => {
    if (!trusted(source, requestingOrigin) || details?.isMainFrame === false) return false;
    if (permission === 'clipboard-sanitized-write') return true;
    return permission === 'media' && details?.mediaType === 'audio';
  });
  contents.session.setPermissionRequestHandler(async (source, permission, callback, details) => {
    if (!trusted(source, details?.requestingUrl) || details?.isMainFrame === false) return callback(false);
    if (permission === 'clipboard-sanitized-write') return callback(true);
    if (permission !== 'media' || !details?.mediaTypes?.length || details.mediaTypes.some(type => type !== 'audio')) return callback(false);
    try {
      const granted = platform !== 'darwin' || await systemPreferences.askForMediaAccess('microphone');
      callback(granted && trusted(source, details.requestingUrl));
    }
    catch { callback(false); }
  });
}
