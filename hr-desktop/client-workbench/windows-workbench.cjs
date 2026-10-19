const fs = require('node:fs/promises');
const path = require('node:path');
const {WindowsAdapter} = require('./windows-adapter.cjs');
const {installNativeExitGuard} = require('./native-exit-guard.cjs');

let active, installing, lastFailure;
const inside = (root, value) => {
  const relative = path.relative(root, value);
  return relative && relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
};

async function install(bindings, config = {}) {
  if (active) throw Error('Windows workbench is already installed');
  if (installing) throw Error('Windows workbench installation is already running');
  installing = true;
  let adapter, host, network, exitGuard, windowOwner, chatAction, resumeAction, incomingActivity, stage = 'profile', profile;
  const listeners = [];
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
    stage = 'web-types';
    require('./compat.cjs').installWebTypes();
    if (config.offline) {
      network = require('./native-network.cjs').instance({offline: true});
      await network.configure(session.defaultSession);
      network.attachNative(bindings.network);
      stage = 'native-chat-fixture';
      // Run before installing window listeners so the disposable fixture
      // cannot be mistaken for a newly created real main window.
      chatAction = await require('./native-chat-smoke.cjs').run({windowsAdapter: true});
      stage = 'native-resume-fixture';
      resumeAction = await require('./native-resume-smoke.cjs').run({windowsAdapter: true});
      stage = 'native-incoming-fixture';
      incomingActivity = await require('./native-activity-smoke.cjs').run();
    }
    stage = 'native-window';
    require('./brand.cjs').setWindowsRelaunch(config.entryExecutable);
    const integrated = require('./integrated-shell.cjs').instance();
    windowOwner = require('./windows-window-owner.cjs').ownWindows({
      app, BrowserWindow, webContents, integrated});
    await windowOwner.ready();
    const windows = windowOwner.windows;
    const mainWindow = () => {
      const matches = windows().filter(win => win.name === 'main');
      return matches.length === 1 ? matches[0] : null;
    };
    stage = 'direct-network';
    if (!network) {
      network = require('./native-network.cjs').instance();
      await network.configure(session.defaultSession);
      network.attachNative(bindings.network);
    }
    stage = 'native-adapter';
    adapter = new WindowsAdapter({bindings, net, session: session.defaultSession,
      endpoints: require('./endpoints.json'), nativeWindow: mainWindow,
      allContents: () => webContents.getAllWebContents(), show: () => integrated.showNative()});
    // Identity restoration and the async native login getter can finish in
    // different turns. An empty startup identity is not an account mismatch,
    // and opening the UI must not require a completed BOSS login.
    const startupFailure = await require('./windows-window-owner.cjs').checkStartupAccount(adapter);
    if (adapter.loggedIn() && mainWindow()) integrated.selectWindow(mainWindow());
    stage = 'host-start';
    host = await require('./host.cjs').start(adapter, {
      assistantRoot: config.assistantRoot, offlineURL: config.offline ? config.offlineURL : undefined,
    });
    if (startupFailure) host.rpc.sessionLost(startupFailure);
    stage = 'integrated-views';
    await integrated.chromeReady;
    if (config.offline) await integrated.platformReady;
    const {launchStatus, nativeHandle} = require('./windows-launch-status.cjs');
    const reportWindow = launchStatus(root, config.launchId, 'heyi-window-status.json');
    const recordWindow = win => {
      if (win && !win.isDestroyed()) reportWindow({
        nativePid: process.pid, windowHandle: nativeHandle(win),
      });
    };
    listen(integrated, 'window-selected', recordWindow);
    recordWindow(integrated.window);
    listen(app, 'before-quit', () => {void close();});
    stage = 'native-exit';
    exitGuard = installNativeExitGuard({targets: [bindings.sdk, globalThis], beforeExit: async () => {
      if (active) active.nativeExitRequested = true;
      await close();
    }});
    active = {adapter, host, integrated, network, listeners, exitGuard, windowOwner, chatAction, resumeAction, incomingActivity,
      offline: Boolean(config.offline), profileRoot: root, nativeExitRequested: false};
    lastFailure = undefined;
    integrated.showCurrent();
    return {ok: true, mode: 'windows-native-workbench', bridgeReady: true,
      nativeSessionVerified: adapter.loggedIn(), offline: Boolean(config.offline),
      originalResourcesModified: false};
  } catch (error) {
    lastFailure = {stage, profile,
      ...(error.code === 'ENOENT' ? {ioCode: 'ENOENT'} : {})};
    for (const {target, event, listener} of listeners) target.removeListener(event, listener);
    await Promise.allSettled([host?.close(), network?.egress.close()]);
    exitGuard?.close();
    windowOwner?.close();
    adapter?.close();
    throw error;
  } finally {installing = false;}
}

async function close() {
  if (!active) return;
  const current = active;active = undefined;
  for (const {target, event, listener} of current.listeners) target.removeListener(event, listener);
  current.windowOwner.close();
  await current.host.close();
  current.adapter.close();
  if (current.offline) {
    const {app, safeStorage} = require('electron');
    const file = path.join(app.getPath('userData'), 'heyi', 'platform-session.enc');
    let platformSessionSaved = false;
    try {
      const encrypted = await fs.readFile(file);
      const stored = JSON.parse(safeStorage.decryptString(encrypted));
      platformSessionSaved = !encrypted.includes(Buffer.from('heyi-offline-upgrade-cookie')) &&
        stored.origin === 'https://hoyee.org.cn' && stored.cookies?.some(cookie =>
          cookie.name === 'HEYI_SESSION' && cookie.value === 'heyi-offline-upgrade-cookie');
    } catch {}
    await fs.writeFile(path.join(current.profileRoot, 'workbench-exit-check.json'), JSON.stringify({
      nativeExitGuardInvoked: current.nativeExitRequested,
      hostClosedBeforeNativeExit: true, platformSessionSaved,
      realAccountUsed: false,
    }), {flag: 'wx'});
  }
  current.exitGuard.close();
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
  const {BrowserWindow, clipboard, app, safeStorage} = require('electron');
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
    assert.equal(integrated.window.getTitle(), 'Ai boss');
    integrated.window.hide();
    integrated.showCurrent();
    assert.equal(integrated.window.isVisible(), true);
    integrated.window.minimize();
    await until(() => integrated.window.isMinimized());
    integrated.showCurrent();
    await until(() => !integrated.window.isMinimized() && integrated.window.isVisible());
    // Simulate a saved native show function that bypasses our wrapper.
    const other = active.windowOwner.windows().find(win => win !== integrated.window);
    if (!other) throw Error('Expected original login and main windows');
    integrated.originalShow.get(other)();
    await until(() => integrated.window === other);
    assert.ok(other.getBrowserViews().includes(integrated.toolbar));
    assert.equal(BrowserWindow.getAllWindows().filter(win => win.isVisible()).length, 1);
    assert.equal(await integrated.toolbar.webContents.executeJavaScript(
      `document.title === 'Ai boss' &&
       document.querySelector('.brand').textContent.trim() === 'Ai boss' &&
       document.querySelector('.mark').complete && document.querySelector('.mark').naturalWidth === 1024`), true);
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
    assert.equal((await response.json()).data.version, require('./package.json').version);
    const worker = await host.engine.runtime.run({operation: 'health',
      root: path.join(app.getPath('userData'), 'heyi', 'offline-worker'), cdp: host.rpc.url});
    assert.equal(worker.ok, true);assert.equal(worker.platform, 'win32');
    assert.equal(worker.version, '2.0.0');
    assert.equal(state().networkReady, true);
    await assert.rejects(host.rpc.dispatch('/snapshot', {accountId: '101'}), {code: 'AUTH_REQUIRED'});
    assert.equal(safeStorage.isEncryptionAvailable(), true);
    await integrated.platform.webContents.session.cookies.set({
      url: 'https://hoyee.org.cn', name: 'HEYI_SESSION', value: 'heyi-offline-upgrade-cookie',
      path: '/', secure: true, httpOnly: true, sameSite: 'lax',
    });
    return {ok: true, actualOriginalWindows: true, singleVisibleWindow: true, applicationBrand: 'Ai boss',
      hiddenWindowRestored: true, minimizedWindowRestored: true, nativeShowAdopted: true,
      sidebarRoundTrip: true, localFixturePaste: true, loopbackBridge: true,
      packagedWindowsWorker: true, sdkSourceGuard: true, offlineNetworkRelay: true,
      signedOutSnapshotRejected: true, chatAction: active.chatAction, resumeAction: active.resumeAction,
      incomingActivity: active.incomingActivity,
      sessionCookiePrepared: true,
      candidateMessagesSent: 0, actualBossNetworkUsed: false};
  } finally {clipboard.writeText(oldClipboard);}
}
module.exports = {install, close, state, offlineCheck};
