// Explicitly injected by the disposable Windows CI probe. Never used in the app.
const fs = require('node:fs');
const path = require('node:path');

module.exports = function observe(electron, config) {
  const {app, BrowserWindow, webContents} = electron;
  const root = fs.realpathSync(config.root);
  const output = path.resolve(config.output);
  if (process.platform !== 'win32' || app.isReady() ||
      !path.relative(root, output) || path.relative(root, output).startsWith('..')) {
    throw Error('Observer requires a new Windows test directory before ready');
  }
  const userData = path.join(root, 'profile');
  const appData = path.join(root, 'app-data');
  fs.mkdirSync(userData, {recursive: true});
  fs.mkdirSync(appData, {recursive: true});
  app.setPath('appData', appData);
  app.setPath('userData', userData);
  app.setName('HeyiNativeWindowsProbe');
  app.disableHardwareAcceleration();
  let ready = false, attemptedRequests = 0, finished = false;
  const sessions = new WeakSet();
  const prepare = session => {
    if (sessions.has(session)) return;
    sessions.add(session);
    // OS firewall is the primary boundary. This additional boundary cancels
    // web-page traffic before it reaches the network, including login/QR.
    session.webRequest.onBeforeRequest({urls: ['*://*/*']}, (_details, callback) => {
      attemptedRequests++;callback({cancel: true});
    });
  };
  app.on('web-contents-created', (_event, contents) => prepare(contents.session));
  const members = object => {
    if (!object || !['function', 'object'].includes(typeof object)) return [];
    return Object.entries(Object.getOwnPropertyDescriptors(object))
      .filter(([name]) => !/token|cookie|password|secret|credential|key/i.test(name))
      .map(([name, entry]) => ({name: name.slice(0, 100),
        type: Object.hasOwn(entry, 'value') ? typeof entry.value : 'accessor'})).slice(0, 180);
  };
  function snapshot(reason) {
    const sdk = Object.entries(Object.getOwnPropertyDescriptors(globalThis))
      .filter(([name]) => /^_sdk_[A-Za-z0-9_]+$/.test(name))
      .map(([name, entry]) => ({name, type: typeof entry.value})).sort((a, b) => a.name.localeCompare(b.name));
    const nativeModules = Object.entries(require.cache)
      .filter(([file]) => file.endsWith('.node'))
      .map(([file, entry]) => ({name: path.basename(file), members: members(entry.exports)}));
    const userDataIsolated = path.resolve(app.getPath('userData')) === path.resolve(userData);
    const report = {
      schemaVersion: 1, mode: 'offline-original-runtime-observation',
      ok: ready && sdk.length > 0 && userDataIsolated,
      reason, platform: process.platform, arch: process.arch, versions: process.versions,
      ready, userDataIsolated, observedSdk: sdk, nativeModules,
      windows: BrowserWindow.getAllWindows().map(window => ({
        id: window.id, visible: window.isVisible(), destroyed: window.isDestroyed(),
        contentsType: window.webContents.getType(),
      })),
      webContentsTypes: webContents.getAllWebContents().map(contents => contents.getType()),
      webRequestsCancelled: attemptedRequests, sdkMethodsInvoked: 0,
      candidateMessagesSent: 0, realProfileRead: false,
      applicationBytesModified: false, originalSignatureReused: false,
    };
    fs.writeFileSync(output, JSON.stringify(report, null, 2), {mode: 0o600});
    return report;
  }
  function finish(reason) {
    if (finished) return;
    finished = true;
    snapshot(reason);
    app.quit();
    setTimeout(() => app.exit(0), 2000).unref();
  }
  process.once('exit', () => {if (!finished) snapshot('process-exit');});
  app.whenReady().then(() => {
    ready = true;
    prepare(electron.session.defaultSession);
    snapshot('app-ready');
    setTimeout(() => finish('observation-complete'), 8000);
  });
  setTimeout(() => finish('ready-deadline'), 20000).unref();
  return {observerInstalled: true, userDataIsolated: true};
};
