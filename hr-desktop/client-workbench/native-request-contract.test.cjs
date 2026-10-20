const test = require('node:test');
const assert = require('node:assert/strict');
const {install} = require('./native-request-contract.cjs');

function fixture(t, {loggedIn = false, throws = false, delay = false} = {}) {
  globalThis.$socket = {clientID: new Map(), handleEvent(_name, socket) {
    this.clientID.set(socket.handshake.query.cid, {sid: socket.id});
    socket.on('message', request => socket.emit('message', {...request, data: {code: 0, data: loggedIn}}));
  }};
  t.after(() => {delete globalThis.$socket;});
  let originalHTTPCalls = 0, sdkCalls = 0, release;
  class Client {send() {originalHTTPCalls++;throw Error('Original transport must not be used');}}
  const client = new Client();
  const sdk = {_sdk_getUserInfo: async function(refresh) {
    sdkCalls++;assert.equal(this, sdk);assert.equal(refresh, true);
    if (delay) await new Promise(resolve => {release = resolve;});
    if (throws) throw Error('private-failure');
    return client.send({url: 'https://www.zhipin.com/wapi/zpuser/wap/getUserInfo.json',
      headers: {Cookie: 'private-cookie', 'User-Agent': 'Original Windows fixture',
        zp_token: 'private-zp', Traceid: 'private-trace'}});
  }};
  return {bindings: {sdk, httpClients: [client]}, client,
    originalHTTPCalls: () => originalHTTPCalls, sdkCalls: () => sdkCalls, release: () => release()};
}
test('header contract requires explicit anonymous isolation', async () => {
  await assert.rejects(install({}), /Anonymous isolated/);
});
test('intercepts exactly one SDK read before transport, exports metadata only, and restores', async t => {
  const f = fixture(t);
  const report = await install(f.bindings, {anonymousIsolated: true});
  assert.equal(report.ok, true);assert.equal(report.hooksRestored, true);
  assert.equal(report.originalSDKCalls, 1);assert.equal(report.originalHTTPCalls, 0);
  assert.equal(report.userInfoIntercepts, 1);assert.equal(f.sdkCalls(), 1);
  assert.equal(f.originalHTTPCalls(), 0);assert.equal(Object.hasOwn(f.client, 'send'), false);
  for (const secret of ['private-cookie', 'private-zp', 'private-trace']) {
    assert.equal(JSON.stringify(report).includes(secret), false);
  }
});
test('signed-in accounts invoke no SDK or HTTP method', async t => {
  const f = fixture(t, {loggedIn: true});
  assert.equal((await install(f.bindings, {anonymousIsolated: true})).ok, false);
  assert.equal(f.sdkCalls(), 0);assert.equal(f.originalHTTPCalls(), 0);
});
test('SDK rejection restores hooks and does not replay the read', async t => {
  const f = fixture(t, {throws: true});
  const report = await install(f.bindings, {anonymousIsolated: true});
  assert.equal(report.ok, false);assert.equal(report.hooksRestored, true);
  assert.equal(f.sdkCalls(), 1);assert.equal(f.originalHTTPCalls(), 0);
  assert.equal(Object.hasOwn(f.client, 'send'), false);
});
test('late credential preparation stays intercepted after the deadline until the SDK settles', async t => {
  const f = fixture(t, {delay: true});
  const report = await install(f.bindings, {anonymousIsolated: true});
  assert.equal(report.ok, false);assert.equal(report.hooksRestored, false);
  assert.equal(Object.hasOwn(f.client, 'send'), true);
  f.release();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(Object.hasOwn(f.client, 'send'), false);
  assert.equal(f.originalHTTPCalls(), 0);assert.equal(f.sdkCalls(), 1);
});
