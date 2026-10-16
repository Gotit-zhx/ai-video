const {app, BrowserWindow, session, net} = require('electron');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');
const {spawn} = require('node:child_process');
const http = require('node:http');
const {NativeRPC} = require('./native-rpc.cjs');
const {LocalBridge} = require('./src/bridge.cjs');
const {downloadResume} = require('./native-download.cjs');
const {validateHeaderValue} = require('node:http');
const {IntegratedShell} = require('./integrated-shell.cjs');

async function run() {
  if (!process.env.HEYI_WORKBENCH_TEST_DATA || !process.env.HEYI_WORKBENCH_TEST_OUTPUT) {
    throw Error('Disposable test profile and output required');
  }
  await app.whenReady();
  process.env.HEYI_TEST_USER_DATA = app.getPath('userData');
  const userAgent = session.defaultSession.getUserAgent();
  validateHeaderValue('user-agent', userAgent);
  assert.match(userAgent, /HeyiWorkbench\//);
  session.defaultSession.webRequest.onBeforeRequest((details, callback) => {
    callback({cancel: /^https?:/.test(details.url)});
  });
  const window = new BrowserWindow({show: false, webPreferences: {
    nodeIntegration: false, contextIsolation: true, sandbox: true,
  }});
  await window.loadURL('data:text/html,<title>赫逸工作台 · 离线验证</title>');
  const rendererUserAgent = await window.webContents.executeJavaScript('navigator.userAgent');
  validateHeaderValue('user-agent', rendererUserAgent);
  assert.match(rendererUserAgent, /HeyiWorkbench\//);
  window.name = 'login';
  const integrated = new IntegratedShell();
  integrated.attachWindow(window, 'login');
  integrated.configure({session: session.defaultSession,
    url: 'data:text/html,<input id="fixture" placeholder="Offline paste target">',
    origin: 'null'});
  await Promise.all([integrated.platformReady, integrated.chromeReady]);
  integrated.setNetworkState('ready');
  assert.equal(await integrated.toolbar.webContents.executeJavaScript(
    `new Promise(resolve => setTimeout(() => resolve(
      document.querySelector('#network-label').textContent), 0))`), '网卡直连');
  integrated.setNetworkState('stopped');
  assert.equal(await integrated.toolbar.webContents.executeJavaScript(
    `new Promise(resolve => setTimeout(() => resolve(
      document.querySelector('#network-label').textContent), 0))`), '直连已断开');
  for (const [state, label] of [['connected', 'BOSS 已连接'],
    ['paused', 'BOSS 会话待确认'], ['signed-out', '等待扫码登录']]) {
    integrated.setAccountState(state);
    assert.equal(await integrated.toolbar.webContents.executeJavaScript(
      `new Promise(resolve => setTimeout(() => resolve(
        document.querySelector('#account').textContent), 0))`), label);
  }
  assert.equal(window.getBrowserViews().length, 2);
  integrated.showWorkbench();
  assert.equal(window.getBrowserViews().length, 3);
  const platformId = integrated.platform.webContents.id;
  const leftLabels = await integrated.sidebar.webContents.executeJavaScript(
    `Array.from(document.querySelectorAll('nav button')).map(e => e.innerText.trim())`);
  assert.deepEqual(leftLabels.map(text => text.split('\n').pop()), ['BOSS', '系统']);
  let prevented = false, pasteCalls = 0;
  const contents = integrated.platform.webContents;
  const nativePaste = contents.paste;
  contents.paste = () => {pasteCalls++;};
  contents.emit('before-input-event', {preventDefault() {prevented = true;}},
    {type: 'keyDown', key: 'v', meta: true, control: true, alt: false});
  contents.paste = nativePaste;
  assert.equal(prevented, true);assert.equal(pasteCalls, 1);
  const main = new BrowserWindow({show: false});
  main.name = 'main';
  integrated.attachWindow(main, 'main');
  integrated.beforeNativeClose('login', main);
  assert.equal(window.isVisible(), false);
  assert.equal(main.isVisible(), true);
  assert.equal(main.getBrowserViews().length, 3);
  assert.equal(integrated.platform.webContents.id, platformId);
  assert.equal(window.getBrowserViews().length, 0);
  window.destroy();
  const platformConnection = await require('./platform-connection-smoke.cjs').run();
  const egressResponse = await require('./native-egress-smoke.cjs').run();
  const resumeAction = await require('./native-resume-smoke.cjs').run();
  const chatAction = await require('./native-chat-smoke.cjs').run();
  const nativeHttp = await require('./native-http-smoke.cjs').run();
  const attachment = Buffer.from('%PDF-1.7\n% offline native download fixture\n%%EOF');
  const attachmentName = "attachment; filename=resume.pdf; filename*=UTF-8''%E7%AE%80%E5%8E%86.pdf";
  const attachmentURL = 'https://docdownload.zhipin.com/wflow/zpgeek/download/download4boss/fixture?id=fixture';
  const downloadSession = session.fromPartition('heyi-offline-download-probe');
  let nativeDownloads = 0;
  let nativeDownloadTransports = 0;
  // Electron 22's buffer-protocol fixture does not expose custom response
  // headers. Use a real loopback response to verify bytes AND metadata with
  // its actual net.request implementation. The test-only wrapper below
  // rewrites the fixed validated URL before transport; BOSS is never called.
  const attachmentServer = http.createServer((_request, response) => {
    nativeDownloads++;
    response.writeHead(200, {'Content-Type': 'application/pdf',
      'Content-Length': attachment.length, 'Content-Disposition': attachmentName});
    response.end(attachment);
  });
  await new Promise(resolve => attachmentServer.listen(0, '127.0.0.1', resolve));
  const attachmentFixtureURL = `http://127.0.0.1:${attachmentServer.address().port}/attachment`;
  const attachmentNet = {request: options => {
    nativeDownloadTransports++;
    assert.equal(options.url, attachmentURL);
    return net.request({...options, url: attachmentFixtureURL});
  }};
  const rpc = await new NativeRPC({adapter: {
    user: () => ({userId: '101'}), loggedIn: () => true,
    snapshot: async () => ({accountId: '101', userAgent: 'offline', cookies: []}),
    sendText: async sent => queueMicrotask(() => rpc.receive([{
      fromId: '101', toId: '202', isSelf: true, mid: '303', time: Date.now(),
      clientMid: sent.options.clientMid, text: sent.content, type: 'text', status: 1,
    }])),
    download: (input, checkAccount, signal) => downloadResume({
      net: attachmentNet, session: downloadSession, url: input.url, checkAccount, signal,
      credentials: async () => ({token: 'offline-fixture-only', userAgent: 'offline'}),
    }),
    request: async input => {
      assert.equal(input.url, '/wapi/zpjob/job/data/list');
      assert.equal(input.method, 'get');
      assert.equal(input.data.page, '1');
      return {code: 0, zpData: {data: [], hasMore: false}};
    },
  }, endpoints: require('./endpoints.json')}).start();
  const managedJobs = await rpc.dispatch('/http', {accountId: '101', method: 'GET',
    url: 'https://www.zhipin.com/wapi/zpjob/job/data/list?page=1&position=0&type=0'});
  assert.deepEqual(managedJobs, {code: 0, zpData: {data: [], hasMore: false}});
  await assert.rejects(rpc.dispatch('/http', {accountId: '101', method: 'GET',
    url: 'https://www.zhipin.com/wapi/unreviewed/operation'}), {code: 'UNSUPPORTED_OPERATION'});
  const receipt = await rpc.sendText('101', {friendId: '202'}, 'offline fixture');
  assert.equal(receipt.messageId, '303');
  const downloaded = await fetch(rpc.url + '/download', {
    method: 'POST', headers: {Authorization: 'Bearer ' + rpc.secret},
    body: JSON.stringify({accountId: '101', url: attachmentURL}),
  });
  assert.equal(downloaded.status, 200);
  assert.equal(downloaded.headers.get('x-heyi-native-download'), '1');
  assert.equal(downloaded.headers.get('content-disposition'), attachmentName);
  assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()), attachment);
  assert.equal(nativeDownloads, 1);
  let releaseCredentials;
  await assert.rejects(downloadResume({net: attachmentNet, session: downloadSession, url: attachmentURL,
    checkAccount() {}, timeout: 35,
    credentials: () => new Promise(resolve => {releaseCredentials = resolve;}),
  }), {code: 'DOWNLOAD_TIMEOUT'});
  releaseCredentials({token: 'late-offline-fixture', userAgent: 'offline'});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(nativeDownloadTransports, 1);
  await new Promise(resolve => attachmentServer.close(resolve));
  const worker = await new Promise((resolve, reject) => {
    const child = spawn(require('./runtime-paths.cjs').runtimeExecutable(), [], {
      stdio: ['pipe', 'pipe', 'ignore'],
    });
    let output = '';
    const timer = setTimeout(() => {child.kill();reject(Error('Packaged native worker timeout'));}, 15000);
    child.stdout.on('data', data => {output += data;});
    child.once('error', reject);
    child.once('close', () => {
      clearTimeout(timer);
      try {resolve(JSON.parse(output));} catch {reject(Error('Packaged worker did not return JSON'));}
    });
    child.stdin.end(JSON.stringify({
      operation: 'diagnose-local', cdp: rpc.url,
      root: path.join(app.getPath('userData'), 'fixture/accounts/owner/boss'),
      native: {url: rpc.url, secret: rpc.secret, accountId: '101'},
    }));
  });
  assert.equal(worker.ok, true);
  assert.equal(worker.sessionCount, 0);
  const bridge = new LocalBridge({engine: {}, authorize: async () => {}});
  await bridge.start(0);
  const response = await fetch(`http://127.0.0.1:${bridge.port}/health`, {
    headers: {Origin: 'https://hoyee.org.cn'},
  });
  assert.equal((await response.json()).data.version, '0.1.24');
  await bridge.close();await rpc.close();
  // Exercise the actual shared host with its packaged worker and relay too.
  // The original app is never loaded in this smoke path; the empty adapter
  // represents only a signed-out local fixture.
  const network = require('./native-network.cjs').instance({offline: true});
  const singletonShell = require('./integrated-shell.cjs').instance();
  const hostWindow = new BrowserWindow({show: false});
  hostWindow.name = 'main';
  singletonShell.attachWindow(hostWindow, 'main');
  main.destroy();
  const fixtureFile = path.join(app.getPath('userData'), 'host-fixture.html');
  fs.writeFileSync(fixtureFile, '<!doctype html><meta charset="utf-8"><input id="fixture">');
  const hostAdapter = {user: () => null, loggedIn: () => false};
  const hostOptions = {offlineURL: require('node:url').pathToFileURL(fixtureFile).href};
  const [host, sameHost] = await Promise.all([
    require('./host.cjs').start(hostAdapter, hostOptions),
    require('./host.cjs').start(hostAdapter, hostOptions),
  ]);
  assert.equal(host, sameHost);
  await Promise.all([singletonShell.chromeReady, singletonShell.platformReady]);
  const healthURL = `http://127.0.0.1:${host.bridge.port}/health`;
  assert.equal((await fetch(healthURL, {headers: {Origin: 'https://hoyee.org.cn'}})).status, 200);
  const hostWorker = await host.engine.runtime.run({
    operation: 'health', root: path.join(app.getPath('userData'), 'offline-host-worker'), cdp: host.rpc.url,
  });
  assert.equal(hostWorker.ok, true);assert.equal(hostWorker.version, '2.0.0');
  assert.equal(hostWorker.platform, process.platform);
  assert.equal(network.offline, true);
  const hostRoot = require('./shared-state.cjs').storagePaths(app).assistantRoot;
  assert.equal(hostRoot, path.join(app.getPath('userData'), 'heyi', 'offline-assistant'));
  assert.equal(fs.existsSync(path.join(hostRoot, 'device.json')), true);
  await Promise.all([host.close(), host.close()]);
  await assert.rejects(fetch(healthURL, {headers: {Origin: 'https://hoyee.org.cn'}}));
  fs.writeFileSync(process.env.HEYI_WORKBENCH_TEST_OUTPUT, JSON.stringify({
    ok: true, electron: process.versions.electron, node: process.versions.node,
    profile: app.getPath('userData'), accountDataUsed: false,
    actualBossNetworkUsed: false, receiptFixture: true, loopbackBridge: true,
    packagedNativeWorker: true, sdkSourceGuard: true,
    packagedManagedJobEndpoint: true,
    nativeAttachmentFixture: true, nativeAttachmentNamePreserved: true,
    nativeAttachmentCredentialDeadline: true,
    nativeChromiumRequestCount: nativeDownloads,
    nativeUserAgentHeaderValid: true, rendererUserAgentHeaderValid: true,
    integratedSingleWindow: true, nativeLoginTransitionFixture: true,
    leftNavigationFixture: true, pasteTargetRoutingFixture: true,
    hostStartup: {concurrentStartup: true, actualHostBridge: true,
      actualPackagedWorker: true, isolatedAssistantRecords: true, closedBridge: true},
    platformConnection, egressResponse, resumeAction, chatAction, nativeHttp,
  }, null, 2));
  app.exit(0);
}
module.exports = {run};
