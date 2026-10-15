const assert = require('node:assert/strict');
const path = require('node:path');
const {BrowserWindow, session} = require('electron');
const {LocalBridge} = require('./src/bridge.cjs');
const {DirectEgress} = require('./src/direct-network.cjs');

async function run() {
  const origin = 'https://hoyee.org.cn';
  const target = session.fromPartition('heyi-offline-platform-connection');
  const bridge = new LocalBridge({
    engine: {
      checkOwner: async () => {},
      route: async () => new Response(JSON.stringify({success: true, data: {fixture: true}}),
        {headers: {'Content-Type': 'application/json'}}),
    },
    authorize: async () => {},
    pair: async () => ({expiresAt: Math.floor(Date.now() / 1000) + 300}),
  });
  const egress = new DirectEgress(path.join(process.resourcesPath, 'heyi-runtime', 'heyi-boss'));
  const win = new BrowserWindow({show: false, webPreferences: {
    session: target, nodeIntegration: false, contextIsolation: true, sandbox: true,
    webSecurity: true,
  }});
  let requests = 0;
  try {
    await bridge.start(0);
    await egress.start(true);
    await egress.configure(target);
    const base = `http://127.0.0.1:${bridge.port}`;
    assert.equal(await target.resolveProxy(base), 'DIRECT');
    await new Promise((resolve, reject) => target.protocol.interceptBufferProtocol(
      'https', (request, callback) => {
        requests++;
        callback(request.url === origin + '/connection-fixture'
          ? {statusCode: 200, mimeType: 'text/html',
            data: Buffer.from('<!doctype html><title>Offline platform connection</title>'),
            headers: {'Content-Security-Policy': `default-src 'self'; connect-src 'self' ${base}`}}
          : {error: -10});
      }, error => error ? reject(error) : resolve()));
    await win.loadURL(origin + '/connection-fixture');
    const result = await win.webContents.executeJavaScript(`(async () => {
      const base = ${JSON.stringify(base)};
      try {
        const health = await fetch(base + '/health', {credentials: 'omit',
          signal: AbortSignal.timeout(4000)}).then(r => r.json());
        const paired = await fetch(base + '/pair', {method: 'POST', credentials: 'omit',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({ticket: 'x'.repeat(43)}),
          signal: AbortSignal.timeout(4000)}).then(r => r.json());
        const status = await fetch(base + '/api/v1/hr/login/status', {credentials: 'omit',
          headers: {Authorization: 'Bearer ' + paired.data.token},
          signal: AbortSignal.timeout(4000)}).then(r => r.json());
        return {health: health.data.app === 'heyi-recruiter',
          paired: paired.success === true, status: status.data.fixture === true};
      } catch (error) {return {error: error.name + ': ' + error.message};}
    })()`);
    assert.deepEqual(result, {health: true, paired: true, status: true});
    return {httpsPageLoopbackHealth: true, pairingPreflight: true,
      authorizedRequestPreflight: true, physicalProxyLoopbackBypass: true,
      externalRequests: 0, fixturePages: requests};
  } finally {
    win.destroy();
    target.protocol.uninterceptProtocol('https');
    await bridge.close();
    await egress.close();
  }
}
module.exports = {run};
