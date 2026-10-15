const fs = require('node:fs/promises');
const path = require('node:path');
const {WindowsAdapter} = require('./windows-adapter.cjs');

let active, installing, lastFailure;
const inside = (root, value) => {
  const relative = path.relative(root, value);
  return relative && relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
};

async function install(bindings, config = {}) {
  if (active) throw Error('Windows workbench is already installed');
  if (installing) throw Error('Windows workbench installation is already running');
  installing = true;
  let adapter, host, network, stage = 'profile', profile;
  const listeners = [], attached = new WeakSet();
  const listen = (target, event, listener) => {
    target.on(event, listener);listeners.push({target, event, listener});
  };
  try {
    if (process.platform !== 'win32' || !path.isAbsolute(config.profileRoot || '')) {
      throw Error('Isolated Windows launcher profile required');
    }
    const {app, net, session, BrowserWindow, webContents} = require('electron');
    await app.whenReady();
    const root = await fs.realpath(config.profileRoot);
    profile = {userDataInside: Boolean(inside(root, app.getPath('userData'))),
      appDataInside: Boolean(inside(root, app.getPath('appData')))};
    stage = 'user-data-realpath';
    profile.resolvedUserDataInside = Boolean(inside(root, await fs.realpath(app.getPath('userData'))));
    stage = 'app-data-realpath';
    profile.resolvedAppDataInside = Boolean(inside(root, await fs.realpath(app.getPath('appData'))));
    if (!Object.values(profile).every(value => value === true)) {
      throw Error('Original client did not use the launcher profile');
    }
    if (config.offline && (!process.env.HEYI_WORKBENCH_TEST_DATA ||
        process.env.HEYI_WORKBENCH_TEST_PORT !== '0' || !config.offlineURL)) {
      throw Error('Disposable offline workbench configuration required');
    }
    stage = 'direct-network';
    require('./compat.cjs').installWebTypes();
    network = require('./native-network.cjs').instance({offline: Boolean(config.offline)});
    await network.configure(session.defaultSession);
    network.attachNative(bindings.network);
    stage = 'native-window';
    const integrated = require('./integrated-shell.cjs').instance();
    const windows = () => BrowserWindow.getAllWindows().filter(win =>
      !win.isDestroyed() && ['login', 'main'].includes(win.name));
    const mainWindow = () => {
      const matches = windows().filter(win => win.name === 'main');
      return matches.length === 1 ? matches[0] : null;
    };
    const attach = win => {
      if (win.isDestroyed() || attached.has(win) || !['login', 'main'].includes(win.name)) return;
      attached.add(win);
      integrated.attachWindow(win, win.name);
      // Existing guests were created before this late-bound addon.
      for (const guest of webContents.getAllWebContents()) {
        if (!guest.isDestroyed() && guest.getType() === 'webview' && guest.hostWebContents === win.webContents) {
          integrated.enablePaste(guest);
        }
      }
      listen(win, 'close', () => {
        if (integrated.window !== win) return;
        // Preserve our views before the native owner is destroyed. Do not
        // open another native window: its original login handler owns show().
        for (const view of [integrated.platform, integrated.toolbar, integrated.sidebar]) {
          if (view && win.getBrowserViews().includes(view)) win.removeBrowserView(view);
        }
        integrated.window = null;
        const next = windows().find(other => other !== win);
        if (next) integrated.selectWindow(next);
      });
    };
    const existing = windows().sort((left, right) => Number(right.isVisible()) - Number(left.isVisible()) ||
      Number(right.name === 'login') - Number(left.name === 'login'));
    if (!existing.length || new Set(existing.map(win => win.name)).size !== existing.length) {
      throw Error('Unique original login/main windows required');
    }
    for (const win of existing) attach(win);
    listen(app, 'browser-window-created', (_event, win) => {
      setImmediate(() => attach(win));
      listen(win.webContents, 'did-finish-load', () => attach(win));
    });
    stage = 'native-adapter';
    adapter = new WindowsAdapter({bindings, net, session: session.defaultSession,
      endpoints: require('./endpoints.json'), nativeWindow: mainWindow,
      allContents: () => webContents.getAllWebContents(), show: () => integrated.showNative()});
    try {await adapter.verifyAccount(String(adapter.user()?.userId || ''));}
    catch (error) {if (error.code !== 'AUTH_REQUIRED') throw error;}
    stage = 'host-start';
    host = await require('./host.cjs').start(adapter, {
      assistantRoot: config.assistantRoot, offlineURL: config.offline ? config.offlineURL : undefined,
    });
    stage = 'integrated-views';
    await integrated.chromeReady;
    if (config.offline) await integrated.platformReady;
    listen(app, 'before-quit', () => {void close();});
    active = {adapter, host, integrated, network, listeners, offline: Boolean(config.offline)};
    lastFailure = undefined;
    return {ok: true, mode: 'windows-native-workbench', bridgeReady: true,
      nativeSessionVerified: adapter.loggedIn(), offline: Boolean(config.offline),
      originalResourcesModified: false};
  } catch (error) {
    lastFailure = {stage, profile,
      ...(error.code === 'ENOENT' ? {ioCode: 'ENOENT'} : {})};
    for (const {target, event, listener} of listeners) target.removeListener(event, listener);
    await Promise.allSettled([host?.close(), network?.egress.close()]);
    adapter?.close();
    throw error;
  } finally {installing = false;}
}

async function close() {
  if (!active) return;
  const current = active;active = undefined;
  for (const {target, event, listener} of current.listeners) target.removeListener(event, listener);
  await current.host.close();
  current.adapter.close();
}

function state() {
  if (!active) return {installed: false, ...(lastFailure ? {diagnostic: lastFailure} : {})};
  const {adapter, host, integrated, network} = active;
  return {installed: true, nativeSessionVerified: adapter.loggedIn(),
    bridgePort: host.bridge.port, shell: integrated.state(),
    networkReady: !network.stopped && Boolean(host.egress.info)};
}
async function offlineCheck() {
  if (!active?.offline || !process.env.HEYI_WORKBENCH_TEST_DATA ||
      process.env.HEYI_WORKBENCH_TEST_PORT !== '0') throw Error('Isolated workbench check required');
  const assert = require('node:assert/strict');
  const {BrowserWindow, clipboard, app} = require('electron');
  const {integrated, host} = active;
  await Promise.all([integrated.platformReady, integrated.chromeReady]);
  const oldClipboard = clipboard.readText();
  const until = async check => {
    const end = Date.now() + 3000;
    while (!await check()) {
      if (Date.now() >= end) throw Error('Offline workbench UI did not settle');
      await new Promise(resolve => setTimeout(resolve, 20));
    }
  };
  try {
    integrated.showWorkbench();
    assert.equal(BrowserWindow.getAllWindows().filter(win => win.isVisible()).length, 1);
    const labels = await integrated.sidebar.webContents.executeJavaScript(
      'Array.from(document.querySelectorAll("nav button")).map(node=>node.innerText.trim().split("\\n").pop())');
    assert.deepEqual(labels, ['BOSS', '系统']);
    for (const mode of ['boss', 'workbench']) {
      await integrated.sidebar.webContents.executeJavaScript(
        `document.querySelector('[data-action="${mode}"]').click()`);
      await until(() => integrated.mode === mode);
    }
    clipboard.writeText('赫逸工作台安装包粘贴测试');
    await integrated.platform.webContents.executeJavaScript('document.querySelector("#fixture").focus()');
    integrated.platform.webContents.paste();
    await until(async () => await integrated.platform.webContents.executeJavaScript(
      'document.querySelector("#fixture").value') === '赫逸工作台安装包粘贴测试');
    const response = await fetch(`http://127.0.0.1:${host.bridge.port}/health`,
      {headers: {Origin: 'https://hoyee.org.cn'}});
    assert.equal(response.status, 200);
    assert.equal((await response.json()).data.version, '0.1.24');
    const worker = await host.engine.runtime.run({operation: 'health',
      root: path.join(app.getPath('userData'), 'heyi', 'offline-worker'), cdp: host.rpc.url});
    assert.equal(worker.ok, true);assert.equal(worker.platform, 'win32');
    assert.equal(worker.version, '2.0.0');
    assert.equal(state().networkReady, true);
    await assert.rejects(host.rpc.dispatch('/snapshot', {accountId: '101'}), {code: 'AUTH_REQUIRED'});
    return {ok: true, actualOriginalWindows: true, singleVisibleWindow: true,
      sidebarRoundTrip: true, localFixturePaste: true, loopbackBridge: true,
      packagedWindowsWorker: true, sdkSourceGuard: true, offlineNetworkRelay: true,
      signedOutSnapshotRejected: true, candidateMessagesSent: 0, actualBossNetworkUsed: false};
  } finally {clipboard.writeText(oldClipboard);}
}
module.exports = {install, close, state, offlineCheck};
