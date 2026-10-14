const {app, BrowserWindow, session} = require('electron');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {NativeRPC} = require('./native-rpc.cjs');
const {LocalBridge} = require('./src/bridge.cjs');

async function run() {
  if (!process.env.HEYI_WORKBENCH_TEST_DATA || !process.env.HEYI_WORKBENCH_TEST_OUTPUT) {
    throw Error('Disposable test profile and output required');
  }
  await app.whenReady();
  session.defaultSession.webRequest.onBeforeRequest((details, callback) => {
    callback({cancel: /^https?:/.test(details.url)});
  });
  const window = new BrowserWindow({show: false, webPreferences: {
    nodeIntegration: false, contextIsolation: true, sandbox: true,
  }});
  await window.loadURL('data:text/html,<title>赫逸工作台 · 离线验证</title>');
  const rpc = await new NativeRPC({adapter: {
    user: () => ({userId: '101'}), loggedIn: () => true,
    snapshot: async () => ({accountId: '101', userAgent: 'offline', cookies: []}),
    sendText: async sent => queueMicrotask(() => rpc.receive([{
      fromId: '101', toId: '202', isSelf: true, mid: '303', time: Date.now(),
      clientMid: sent.options.clientMid, text: sent.content, type: 'text', status: 1,
    }])),
  }, endpoints: []}).start();
  const receipt = await rpc.sendText('101', {friendId: '202'}, 'offline fixture');
  assert.equal(receipt.messageId, '303');
  const worker = await new Promise((resolve, reject) => {
    const child = spawn(path.join(process.resourcesPath, 'heyi-runtime', 'heyi-boss'), [], {
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
  await bridge.close();await rpc.close();window.destroy();
  fs.writeFileSync(process.env.HEYI_WORKBENCH_TEST_OUTPUT, JSON.stringify({
    ok: true, electron: process.versions.electron, node: process.versions.node,
    profile: app.getPath('userData'), accountDataUsed: false,
    actualBossNetworkUsed: false, receiptFixture: true, loopbackBridge: true,
    packagedNativeWorker: true, sdkSourceGuard: true,
  }, null, 2));
  app.exit(0);
}
module.exports = {run};
