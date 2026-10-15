// Explicitly injected by the disposable Windows CI probe. Never used in the app.
const fs = require('node:fs');
const path = require('node:path');
const {readOriginalSDK, waitForOriginalDispatcher} = require('./original-sdk-read.cjs');

module.exports = function observe(electron, config) {
  const {app, BrowserWindow, webContents} = electron;
  const root = fs.realpathSync(config.root);
  const output = path.resolve(config.output);
  const insideRoot = value => {
    const relative = path.relative(root, path.resolve(value));
    return relative && relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative);
  };
  const attachedAfterReady = app.isReady();
  if (process.platform !== 'win32' || !insideRoot(output)) {
    throw Error('Observer requires a new Windows test directory');
  }
  if (attachedAfterReady && (!insideRoot(app.getPath('userData')) || !insideRoot(app.getPath('appData')))) {
    throw Error('Original runtime did not use disposable profile paths');
  }
  const userData = attachedAfterReady ? app.getPath('userData') : path.join(root, 'profile');
  const appData = attachedAfterReady ? app.getPath('appData') : path.join(root, 'app-data');
  fs.mkdirSync(userData, {recursive: true});
  fs.mkdirSync(appData, {recursive: true});
  if (!attachedAfterReady) {
    app.setPath('appData', appData);
    app.setPath('userData', userData);
    app.disableHardwareAcceleration();
  }
  let ready = false, attemptedRequests = 0, finished = false, sdkReadRequests = 0, lastReport;
  let sdkRead = {ok: false, reason: 'not-requested'};
  const shutdown = {method: 'app.quit', requested: false, callReturned: false, events: [],
    exitCalls: [], exceptions: [], unhandledRejections: []};
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
  const descriptorValue = name => Object.getOwnPropertyDescriptor(globalThis, name)?.value;
  const namespace = name => {
    const object = descriptorValue(name);
    if (!object || !['object', 'function'].includes(typeof object)) return {name, type: typeof object};
    return {name, type: typeof object, own: members(object),
      prototype: members(Object.getPrototypeOf(object))};
  };
  function persist() {
    if (lastReport) fs.writeFileSync(output, JSON.stringify(lastReport, null, 2), {mode: 0o600});
  }
  // Observe the original exit arguments without changing, suppressing or
  // replacing the requested exit. This disposable probe has no real account.
  for (const [target, object] of [['app', app], ['process', process]]) {
    const original = object.exit;
    object.exit = function(...args) {
      shutdown.exitCalls.push({target,
        code: typeof args[0] === 'number' ? args[0] : null});
      persist();
      return Reflect.apply(original, this, args);
    };
  }
  process.on('uncaughtExceptionMonitor', (error, origin) => {
    if (shutdown.exceptions.length >= 5) return;
    shutdown.exceptions.push({origin,
      name: typeof error?.name === 'string' &&
        /^[A-Za-z][A-Za-z0-9_]{0,79}$/.test(error.name) ? error.name : 'unknown',
      code: typeof error?.code === 'string' &&
        /^[A-Z][A-Z0-9_]{0,79}$/.test(error.code) ? error.code : null});
    persist();
  });
  // Adding an unhandledRejection listener can change Node's failure behavior.
  // Observe the existing event dispatch instead and forward it unchanged.
  const originalProcessEmit = process.emit;
  process.emit = function(event, ...args) {
    if (event === 'unhandledRejection' && shutdown.unhandledRejections.length < 5) {
      const error = args[0];
      shutdown.unhandledRejections.push({
        name: typeof error?.name === 'string' &&
          /^[A-Za-z][A-Za-z0-9_]{0,79}$/.test(error.name) ? error.name : 'unknown',
        code: typeof error?.code === 'string' &&
          /^[A-Z][A-Z0-9_]{0,79}$/.test(error.code) ? error.code : null});
      persist();
    }
    return Reflect.apply(originalProcessEmit, this, [event, ...args]);
  };
  function snapshot(reason) {
    const sdk = Object.entries(Object.getOwnPropertyDescriptors(globalThis))
      .filter(([name]) => /^_sdk_[A-Za-z0-9_]+$/.test(name))
      .map(([name, entry]) => ({name, type: typeof entry.value})).sort((a, b) => a.name.localeCompare(b.name));
    const nativeModules = Object.entries(require.cache)
      .filter(([file]) => file.endsWith('.node'))
      .map(([file, entry]) => ({name: path.basename(file), members: members(entry.exports),
        exportedPrototype: members(entry.exports &&
          Object.getOwnPropertyDescriptor(entry.exports, 'prototype')?.value)}));
    const userDataIsolated = insideRoot(app.getPath('userData')) && insideRoot(app.getPath('appData'));
    lastReport = {
      schemaVersion: 1, mode: 'offline-original-runtime-observation',
      ok: ready && sdkRead.ok === true && userDataIsolated,
      reason, platform: process.platform, arch: process.arch, versions: process.versions,
      ready, userDataIsolated, attachedAfterReady, observedSdk: sdk, nativeModules,
      originalMainLoaded: Object.keys(require.cache).some(file => /[\\/]main\.jsc$/.test(file)),
      mainModuleName: path.basename(process.mainModule?.filename || ''),
      mainExports: members(process.mainModule?.exports),
      applicationMembers: {own: members(app), prototype: members(Object.getPrototypeOf(app))},
      namespaces: ['$api', '$socket', '$windowsMuster'].map(namespace),
      globalNames: Object.entries(Object.getOwnPropertyDescriptors(globalThis))
        .filter(([name]) => name.startsWith('$')).map(([name, entry]) => ({name, type: typeof entry.value})),
      windows: BrowserWindow.getAllWindows().map(window => ({
        id: window.id, visible: window.isVisible(), destroyed: window.isDestroyed(),
        contentsType: window.webContents.getType(),
      })),
      webContentsTypes: webContents.getAllWebContents().map(contents => contents.getType()),
      webRequestsCancelled: attemptedRequests, observerSdkReadRequests: sdkReadRequests,
      sdkRead, shutdown,
      candidateMessagesSent: 0, realAccountProvided: false,
      applicationBytesModified: false, originalSignatureReused: false,
    };
    persist();
    return lastReport;
  }
  function finish(reason) {
    if (finished) return;
    finished = true;
    shutdown.requested = true;
    const inspector = require('node:inspector');
    shutdown.inspectorListenerBeforeQuit = Boolean(inspector.url());
    // The parent has already received the installation result and closed its
    // websocket, long before this bounded observation finishes. Close the
    // listener only here, after asynchronous original startup has completed.
    if (config.detachInspector === true) {
      shutdown.inspectorDetachRequested = true;
      snapshot('before-inspector-detach');
      inspector.close();
      shutdown.inspectorDetached = !inspector.url();
    }
    shutdown.inspectorListenerActive = Boolean(inspector.url());
    snapshot(reason);
    // Save before and after the actual quit call. Do not call Electron getters
    // after shutdown and do not force a zero exit code to satisfy this probe.
    app.quit();
    shutdown.callReturned = true;
    persist();
  }
  // Persist before returning to the original loader; native app.exit/crashes
  // do not necessarily emit Node's process-exit event.
  snapshot('observer-attached');
  for (const event of ['will-finish-launching', 'before-quit', 'will-quit', 'window-all-closed']) {
    app.on(event, () => {
      shutdown.events.push(event);
      if (finished) persist();else snapshot(event);
    });
  }
  app.on('quit', (_event, code) => {shutdown.nativeQuitCode = code;persist();});
  process.once('exit', code => {shutdown.nodeExitCode = code;persist();});
  app.whenReady().then(async () => {
    ready = true;
    prepare(electron.session.defaultSession);
    snapshot('app-ready');
    try {
      const service = await waitForOriginalDispatcher(() => descriptorValue('$socket'));
      if (finished) return;
      sdkReadRequests++;
      try {
        const loggedIn = await readOriginalSDK(service, '_sdk_getLoginStatus');
        sdkRead = {ok: loggedIn === false, method: '_sdk_getLoginStatus',
          loggedIn, transport: 'original-in-process-dispatch'};
      } catch {
        sdkRead = {ok: false, method: '_sdk_getLoginStatus', reason: 'original-dispatch-not-verified'};
      }
    } catch {
      sdkRead = {ok: false, reason: 'original-dispatch-unavailable'};
    }
    if (finished) return;
    snapshot('sdk-read-complete');
    setTimeout(() => finish('observation-complete'), 8000);
  });
  setTimeout(() => finish('ready-deadline'), 45000).unref();
  return {observerInstalled: true, userDataIsolated: true};
};
