// Adopt the native login/main windows even when names/load/show arrive late.
// This never creates or logs into another BOSS window.
function ownWindows({app, BrowserWindow, webContents, integrated}) {
  const observed = new WeakSet(), attached = new WeakSet(), listeners = [];
  let closed = false;
  const listen = (target, event, listener) => {
    target.on(event, listener);listeners.push({target, event, listener});
  };
  const windows = () => BrowserWindow.getAllWindows().filter(win =>
    !win.isDestroyed() && ['login', 'main'].includes(win.name));
  const attach = win => {
    if (closed || win.isDestroyed() || attached.has(win) || !['login', 'main'].includes(win.name)) return;
    attached.add(win);
    integrated.attachWindow(win, win.name);
    for (const guest of webContents.getAllWebContents()) {
      if (!guest.isDestroyed() && guest.getType() === 'webview' && guest.hostWebContents === win.webContents) {
        integrated.enablePaste(guest);
      }
    }
    listen(win, 'close', () => {
      if (integrated.window !== win) return;
      for (const view of [integrated.platform, integrated.toolbar, integrated.sidebar]) {
        if (view && win.getBrowserViews().includes(view)) win.removeBrowserView(view);
      }
      integrated.window = null;
      const next = windows().find(other => other !== win);
      if (next) integrated.selectWindow(next);
    });
  };
  const observe = win => {
    if (closed || win.isDestroyed() || observed.has(win)) return;
    observed.add(win);
    listen(win, 'show', () => {
      attach(win);
      if (attached.has(win)) integrated.selectWindow(win);
    });
    listen(win, 'ready-to-show', () => attach(win));
    listen(win.webContents, 'did-finish-load', () => attach(win));
    // browser-window-created can precede the vendor assigning its name.
    setImmediate(() => attach(win));
    attach(win);
  };
  listen(app, 'browser-window-created', (_event, win) => observe(win));
  const scan = () => {
    BrowserWindow.getAllWindows()
      .sort((left, right) => Number(right.isVisible()) - Number(left.isVisible()) ||
        Number(right.name === 'login') - Number(left.name === 'login'))
      .forEach(observe);
    windows().forEach(attach);
  };
  scan();
  return {
    windows,
    async ready(timeout = 30000) {
      const deadline = Date.now() + timeout;
      while (!integrated.window || integrated.window.isDestroyed()) {
        if (closed || Date.now() >= deadline) throw Error('Original window is not ready');
        scan();
        await new Promise(resolve => setTimeout(resolve, 50));
      }
    },
    close() {
      closed = true;
      for (const {target, event, listener} of listeners) target.removeListener(event, listener);
    },
  };
}
async function checkStartupAccount(adapter) {
  const account = String(adapter.user()?.userId || '');
  if (!/^[1-9]\d{0,19}$/.test(account)) return null;
  try {await adapter.verifyAccount(account);}
  catch (error) {
    if (['AUTH_EXPIRED', 'SESSION_MISMATCH', 'ACCOUNT_RISK',
      'SECURITY_VERIFICATION_REQUIRED'].includes(error.code)) return error.code;
    if (!['AUTH_REQUIRED', 'SESSION_PREPARING'].includes(error.code)) throw error;
  }
  return null;
}
module.exports = {ownWindows, checkStartupAccount};
