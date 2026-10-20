const assert = require('node:assert/strict');
const http = require('node:http');
const {session} = require('electron');
const {DirectEgress} = require('./src/direct-network.cjs');
const {LocalBridge} = require('./src/bridge.cjs');
const {ORIGIN} = require('./src/core.cjs');

async function run() {
  const sockets = new Set();
  let calls = 0;
  let posted;
  const server = http.createServer((req, res) => {
    calls++;
    if (req.url === '/empty') {res.writeHead(204);res.end();return;}
    if (req.url === '/no-headers') return;
    if (req.url === '/post') {
      let body = '';
      req.on('data', chunk => {body += chunk;});
      req.on('end', () => {
        posted = {body, headers: req.headers};
        res.writeHead(200, {'Content-Type': 'application/json'});
        res.end('{"success":true,"data":{"fixture":true}}');
      });
      return;
    }
    if (req.url === '/failed') {
      res.writeHead(503, {'Content-Type': 'application/json'});
      res.end('{"success":false,"message":"fixture unavailable"}');
      return;
    }
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.write('{"success":true,');
    if (req.url === '/stalled') return;
    res.end('"data":{"fixture":true}}');
  });
  server.on('connection', socket => {sockets.add(socket);socket.once('close', () => sockets.delete(socket));});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const target = session.fromPartition('heyi-offline-egress-fixture');
  target.webRequest.onBeforeRequest((details, callback) => callback({
    cancel: !details.url.startsWith(`http://127.0.0.1:${server.address().port}/`),
  }));
  const egress = new DirectEgress('unused-offline-fixture');
  const base = `http://127.0.0.1:${server.address().port}`;
  let bridge;
  try {
    const response = await egress.fetch(target, base + '/post', {
      method: 'POST', body: JSON.stringify({ticket: 'offline-fixture'}),
      headers: {'Content-Type': 'application/json', Origin: ORIGIN,
        'Proxy-Authorization': 'Bearer must-not-reach-origin'},
      signal: AbortSignal.timeout(3000),
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {success: true, data: {fixture: true}});
    assert.equal(posted.body, JSON.stringify({ticket: 'offline-fixture'}));
    assert.equal(posted.headers['content-type'], 'application/json');
    assert.equal(posted.headers.origin, ORIGIN);
    assert.equal(posted.headers['proxy-authorization'], undefined);
    const empty = await egress.fetch(target, base + '/empty', {signal: AbortSignal.timeout(3000)});
    assert.equal(empty.status, 204);assert.equal(await empty.text(), '');
    const stalled = await egress.fetch(target, base + '/stalled', {
      signal: AbortSignal.timeout(250),
    });
    await assert.rejects(stalled.json(), /请求已停止/);
    await assert.rejects(egress.fetch(target, base + '/no-headers', {
      signal: AbortSignal.timeout(250),
    }), /请求已停止/);
    const failed = await egress.fetch(target, base + '/failed', {signal: AbortSignal.timeout(3000)});
    assert.equal(failed.status, 503);
    assert.equal(failed.ok, false);
    assert.deepEqual(await failed.json(), {success: false, message: 'fixture unavailable'});
    const alreadyAborted = new AbortController();
    alreadyAborted.abort();
    await assert.rejects(egress.fetch(target, base + '/never-requested', {
      signal: alreadyAborted.signal,
    }), error => error.name === 'AbortError');
    let pairingAttempts = 0;
    bridge = new LocalBridge({
      engine: {checkOwner: async () => {}},
      pair: async () => {
        const route = pairingAttempts++ === 0 ? '/stalled' : '/json';
        const response = await egress.fetch(target, base + route, {
          signal: AbortSignal.timeout(route === '/stalled' ? 250 : 3000),
        });
        await response.json();
        return {expiresAt: Math.floor(Date.now() / 1000) + 60};
      },
    });
    await bridge.start(0);
    const pair = () => fetch(`http://127.0.0.1:${bridge.port}/pair`, {
      method: 'POST', headers: {Origin: ORIGIN, 'Content-Type': 'application/json'},
      body: JSON.stringify({ticket: 'a'.repeat(43)}),
    });
    assert.equal((await pair()).status, 409);
    assert.equal(bridge.pairing, false);
    const renewed = await pair();
    assert.equal(renewed.status, 200);
    assert.equal((await renewed.json()).success, true);
    assert.equal(bridge.pairing, false);
    assert.equal(calls, 7);
    return {actualElectronResponse: true, jsonBody: true, requestHeadersAndBody: true,
      proxyCredentialsExcluded: true, emptyBody: true, nonSuccessStatusPreserved: true,
      abortedBeforeRequest: true, stalledHeadersAborted: true, stalledBodyAborted: true,
      pairingLockReleased: true, pairingAfterFailure: true,
      networkRequests: calls, actualBossNetworkUsed: false};
  } finally {
    await bridge?.close();
    target.webRequest.onBeforeRequest(null);
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => server.close(resolve));
  }
}
module.exports = {run};
