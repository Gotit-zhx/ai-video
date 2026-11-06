const assert = require('node:assert/strict');
const http = require('node:http');
const {net, session} = require('electron');
const {nativeJSONRequest} = require('./native-http.cjs');
const {NativeRPC} = require('./native-rpc.cjs');

async function run() {
  const sockets = new Set();
  const received = [];
  const server = http.createServer((req, res) => {
    received.push({url: req.url, headers: req.headers});
    if (req.url.includes('mode=redirect')) {
      res.writeHead(302, {Location: '/must-not-follow?private=fixture'});res.end();return;
    }
    if (req.url.includes('mode=stalled')) return;
    if (req.url.includes('mode=truncated')) {
      res.writeHead(200, {'Content-Length': 999});res.write('partial');
      setImmediate(() => res.destroy());return;
    }
    if (req.url.includes('mode=oversize')) {
      res.write('123');res.end('456789');return;
    }
    let body = '';
    req.on('data', chunk => {body += chunk;});
    req.on('end', () => {
      received[received.length - 1].body = body;
      res.writeHead(req.url.includes('mode=limited') ? 429 : 200,
        {'Content-Type': 'application/json'});
      res.end('{"code":0,"fixture":true}');
    });
  });
  server.on('connection', socket => {sockets.add(socket);socket.on('close', () => sockets.delete(socket));});
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const target = session.fromPartition('heyi-offline-native-http-fixture');
  target.webRequest.onBeforeRequest((details, callback) =>
    callback({cancel: !details.url.startsWith(base + '/')}));
  await target.cookies.set({url: base, name: 'unexpected-session-cookie', value: 'must-not-send'});
  let transports = 0, rpc;
  const endpoints = new Set(['/wapi/offline/test']);
  const options = {
    net: {request(input) {
      transports++;
      const url = new URL(input.url);
      assert.equal(url.origin, 'https://www.zhipin.com');
      return net.request({...input, url: base + url.pathname + url.search});
    }},
    session: target, url: '/wapi/offline/test', endpoints, timeout: 3000,
    credentials: async () => ({cookieHeader: 'wt2=offline-fixture-only', userAgent: 'offline-fixture'}),
    checkAccount() {},
  };
  try {
    const data = {query: '设计师 & A+B', ids: ['202', '303']};
    const get = await nativeJSONRequest({...options, data});
    assert.deepEqual(get, {heyiNativeHttp: 1, statusCode: 200, body: {code: 0, fixture: true}});
    const query = new URL(received[0].url, base).searchParams;
    assert.equal(query.get('query'), data.query);assert.deepEqual(query.getAll('ids'), data.ids);
    await nativeJSONRequest({...options, method: 'POST', data});
    assert.deepEqual(new URLSearchParams(received[1].body).getAll('ids'), data.ids);
    assert.equal(new URLSearchParams(received[1].body).get('query'), data.query);
    for (const input of received) assert.equal(input.headers.cookie, 'wt2=offline-fixture-only');
    const limited = await nativeJSONRequest({...options, data: {mode: 'limited'}});
    assert.equal(limited.statusCode, 429);
    const redirectsBefore = transports;
    assert.deepEqual(await nativeJSONRequest({...options, data: {mode: 'redirect'}}),
      {heyiNativeHttp: 1, statusCode: 302, body: null});
    assert.equal(transports, redirectsBefore + 1);
    assert.equal(received.some(input => input.url.startsWith('/must-not-follow')), false);
    for (const [mode, code] of [['truncated', 'NATIVE_HTTP_FAILED'],
      ['oversize', 'NATIVE_RESPONSE_TOO_LARGE'], ['stalled', 'NATIVE_HTTP_TIMEOUT']]) {
      await assert.rejects(nativeJSONRequest({...options, data: {mode},
        maxBytes: mode === 'oversize' ? 6 : 2000,
        timeout: mode === 'stalled' ? 250 : 3000}), {code});
    }
    let release;
    const before = transports;
    const waiting = nativeJSONRequest({...options, timeout: 35,
      credentials: () => new Promise(resolve => {release = resolve;})});
    await assert.rejects(waiting, {code: 'NATIVE_HTTP_TIMEOUT'});
    release({cookieHeader: 'wt2=offline-fixture-only', userAgent: 'offline-fixture'});
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(transports, before);
    rpc = new NativeRPC({endpoints, adapter: {
      user: () => ({userId: '101'}), loggedIn: () => true,
      request: (input, checkAccount, signal) =>
        nativeJSONRequest({...options, ...input, checkAccount, signal}),
    }});
    let credentialsReady;
    const ready = new Promise(resolve => {credentialsReady = resolve;});
    options.credentials = async () => {
      credentialsReady();await new Promise(resolve => setImmediate(resolve));
      return {cookieHeader: 'wt2=offline-fixture-only', userAgent: 'offline-fixture'};
    };
    const pending = rpc.dispatch('/http', {accountId: '101', method: 'GET',
      url: 'https://www.zhipin.com/wapi/offline/test?mode=stalled'});
    await ready;rpc.sessionLost('ACCOUNT_RISK');
    await assert.rejects(pending, {code: 'ACCOUNT_RISK'});
    assert.equal(transports, before);
    assert.equal(rpc.httpRequests.size, 0);
    assert.equal(transports, 7);assert.equal(received.length, 7);
    return {actualElectronRequest: true, sessionCookieExcluded: true,
      chineseAndRepeatedFields: true, nonSuccessStatusPreserved: true,
      redirectNotFollowed: true, truncatedResponseRejected: true,
      oversizedResponseRejected: true, stalledResponseCancelled: true,
      lateCredentialsNotSent: true, nativeRpcRiskCancelsRequest: true,
      networkRequests: transports, actualBossNetworkUsed: false};
  } finally {
    await rpc?.close();
    target.webRequest.onBeforeRequest(null);
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => server.close(resolve));
  }
}
module.exports = {run};
