const test = require('node:test');
const assert = require('node:assert/strict');
const {WindowsAdapter} = require('./windows-adapter.cjs');
const {NativeRPC} = require('./native-rpc.cjs');
const endpoint = '/wapi/offline/fixture';

function fixture(t, {transport, nonEnumerable = false} = {}) {
  let signedIn = true, account = '101';
  const counts = {login: 0, cookie: 0, builder: 0, http: 0, sdkText: 0, send: 0, clear: 0};
  const sent = [];
  const http = {send(input) {counts.http++;assert.equal(this, http);return Promise.resolve(input);}};
  const sender = {send() {counts.send++;}, dispatchMessage() {}};
  const receiver = {receiveMessage() {}};
  const authentication = {getAuthCookieHeader() {counts.cookie++;return Promise.resolve('wt2=fixture-private');},
    clearLoginCookies() {counts.clear++;assert.equal(this, authentication);signedIn = false;return 'original-clear-result';}};
  const sdk = {_sdk_getLoginStatus() {counts.login++;return Promise.resolve(signedIn);},
    _sdk_chatSocket_sendTextMessage(input) {counts.sdkText++;sent.push(input);sender.send(Buffer.from([0]));return Promise.resolve();}};
  const nativeLogout = {_sdk_logout(event) {
    assert.equal(this, nativeLogout);
    authentication.clearLoginCookies();
    return event;
  }};
  const bindings = {httpClients: [http], sender, receiver, authentication, sdk,
    identity: {get userInfo() {return {userId: account};}},
    requestBuilder: async input => {
      counts.builder++;
      const cookie = await authentication.getAuthCookieHeader();
      try {return (await http.send({...input, url: 'https://www.zhipin.com' + input.url,
        headers: {Cookie: cookie, 'User-Agent': 'native-fixture-UA', Traceid: String(counts.builder)}})).body;}
      catch {return null;}
    }};
  if (nonEnumerable) for (const name of Object.keys(bindings)) {
    Object.defineProperty(bindings, name, {enumerable: false});
  }
  const original = {http: http.send, send: sender.send, clear: authentication.clearLoginCookies,
    logout: nativeLogout._sdk_logout};
  const adapter = new WindowsAdapter({bindings, net: {}, session: {}, endpoints: [endpoint],
    show() {}, nativeWindow: () => null, allContents: () => [],
    logoutTargets: [nativeLogout],
    transport: transport || (async () => ({heyiNativeHttp: 1, statusCode: 429, body: {code: 88}})),
    timeout: 100, sessionTimeout: 100});
  t.after(() => adapter.close());
  return {adapter, counts, bindings, original, sent, nativeLogout,
    login: value => {signedIn = value;}, account: value => {account = value;}};
}
const text = {content: 'Own offline fixture', options: {uid: '202', clientMid: '303'}};

test('adapter installation has zero business calls and restores original methods on close', t => {
  const {adapter, counts, bindings, original, nativeLogout} = fixture(t);
  assert.deepEqual(counts, {login: 0, cookie: 0, builder: 0, http: 0, sdkText: 0, send: 0, clear: 0});
  adapter.close();
  assert.equal(bindings.httpClients[0].send, original.http);
  assert.equal(bindings.sender.send, original.send);
  assert.equal(bindings.authentication.clearLoginCookies, original.clear);
  assert.equal(nativeLogout._sdk_logout, original.logout);
});
test('descriptor-only original bindings work without enumerable copies or constructor-time reads', async t => {
  const {adapter, counts, login} = fixture(t, {nonEnumerable: true});
  assert.equal(counts.login, 0);assert.equal(counts.cookie, 0);
  login(false);
  await assert.rejects(adapter.verifyAccount('101'), {code: 'AUTH_REQUIRED'});
  assert.equal(counts.login, 1);assert.equal(counts.builder, 0);
});
test('signed-out memory status blocks SDK writes and native HTTP', async t => {
  const {adapter, counts, login} = fixture(t);login(false);
  await assert.rejects(adapter.sendText(text), {code: 'AUTH_REQUIRED'});
  await assert.rejects(adapter.request({url: endpoint}, () => {}), {code: 'AUTH_REQUIRED'});
  assert.equal(counts.sdkText, 0);assert.equal(counts.builder, 0);assert.equal(counts.send, 0);
});
test('original header preparation and real failure status are used by the adapter', async t => {
  const {adapter, counts} = fixture(t);
  assert.deepEqual(await adapter.request({url: endpoint, method: 'GET'}, () => {}),
    {heyiNativeHttp: 1, statusCode: 429, body: {code: 88}});
  assert.equal(counts.builder, 1);assert.equal(counts.http, 0);
  assert.equal((await adapter.snapshot()).userAgent, 'native-fixture-UA');
});
test('an original outside request can provide UA without initiating an extra request for snapshot', async t => {
  const {adapter, counts, bindings} = fixture(t);
  await assert.rejects(adapter.snapshot(), {code: 'AUTH_REQUIRED'});
  await bindings.httpClients[0].send({url: 'https://www.zhipin.com/wapi/offline/original',
    headers: {Cookie: 'wt2=fixture-private', 'User-Agent': 'observed-original-UA'}});
  assert.equal((await adapter.snapshot()).userAgent, 'observed-original-UA');
  assert.equal(counts.builder, 0);assert.equal(counts.http, 1);
});
test('original logout retains its result and identity while pausing the adapter', async t => {
  const {adapter, bindings, counts} = fixture(t);
  await adapter.verifyAccount('101');
  const events = [];adapter.on('session-lost', code => events.push(code));
  assert.equal(bindings.authentication.clearLoginCookies(), 'original-clear-result');
  assert.equal(counts.clear, 1);assert.equal(adapter.loggedIn(), false);
  assert.deepEqual(events, ['AUTH_EXPIRED']);
  await assert.rejects(adapter.sendText(text), {code: 'AUTH_REQUIRED'});
});
test('a real risk response stops the current and subsequent actions without another native call', async t => {
  const {adapter, counts} = fixture(t, {transport: async () =>
    ({heyiNativeHttp: 1, statusCode: 200, body: {code: 70}})});
  await assert.rejects(adapter.request({url: endpoint}, () => {}), {code: 'ACCOUNT_RISK'});
  await assert.rejects(adapter.sendText(text), {code: 'ACCOUNT_RISK'});
  assert.equal(counts.builder, 1);assert.equal(counts.sdkText, 0);
});
test('a true memory getter after expiry cannot itself reopen business writes', async t => {
  const {adapter, counts} = fixture(t);
  await adapter.verifyAccount('101');
  adapter.originalSession.invalidate('AUTH_EXPIRED');
  await adapter.verifyAccount('101');
  await assert.rejects(adapter.sendText(text), {code: 'AUTH_EXPIRED'});
  await assert.rejects(adapter.request({url: endpoint}, () => {}), {code: 'AUTH_EXPIRED'});
  assert.equal(counts.sdkText, 0);assert.equal(counts.builder, 0);
});
test('a changed original account cannot use the already bound adapter', async t => {
  const {adapter, account, counts} = fixture(t);await adapter.verifyAccount('101');
  account('999');
  await assert.rejects(adapter.sendText(text), {code: 'SESSION_MISMATCH'});
  assert.equal(counts.sdkText, 0);
});
test('original text SDK completion alone cannot confirm a send through NativeRPC', async t => {
  const {adapter, counts} = fixture(t);
  const rpc = new NativeRPC({adapter, endpoints: [endpoint], sendTimeout: 25});
  t.after(() => rpc.close());
  await assert.rejects(rpc.sendText('101', {friendId: '202'}, 'Own offline text'),
    {code: 'SEND_UNCONFIRMED'});
  assert.equal(counts.sdkText, 1);assert.equal(counts.send, 1);
});
test('native logout reaches RPC and same-account memory relogin restores only ordinary expiry', async t => {
  const {adapter, bindings, login} = fixture(t);
  const rpc = new NativeRPC({adapter, endpoints: [endpoint]});
  t.after(() => rpc.close());
  await bindings.httpClients[0].send({url: 'https://www.zhipin.com/wapi/offline/original',
    headers: {Cookie: 'wt2=fixture-private', 'User-Agent': 'native-fixture-UA'}});
  await rpc.refreshSession('101');
  bindings.authentication.clearLoginCookies();
  assert.equal(rpc.sessionFailure, 'AUTH_EXPIRED');
  await assert.rejects(rpc.refreshSession('101'), {code: 'AUTH_REQUIRED'});
  login(true);
  await rpc.refreshSession('101');
  assert.equal(rpc.sessionFailure, null);
  adapter.originalSession.invalidate('ACCOUNT_RISK');
  assert.equal(rpc.sessionFailure, 'ACCOUNT_RISK');
  await assert.rejects(rpc.refreshSession('101'), {code: 'ACCOUNT_RISK'});
});
test('RPC observes native receive events once and detaches its listeners on close', async t => {
  const {adapter} = fixture(t);
  const rpc = new NativeRPC({adapter, endpoints: [endpoint]});
  t.after(() => rpc.close());
  await adapter.verifyAccount('101');
  const activities = [];
  rpc.on('activity', activity => activities.push(activity));
  const message = {isSelf: false, fromId: '202', toId: '101', mid: '303', time: 1700000000};
  adapter.emit('messages', [message]);
  assert.equal(activities.length, 1);
  await rpc.close();
  for (const event of ['messages', 'send-observed', 'session-lost', 'login-established']) {
    assert.equal(adapter.listenerCount(event), 0);
  }
  adapter.emit('messages', [message]);
  assert.equal(activities.length, 1);
});
test('an observation listener failure cannot change the original logout result', async t => {
  const {adapter, bindings, counts} = fixture(t);
  await adapter.verifyAccount('101');
  adapter.on('session-lost', () => {throw Error('own observer failure');});
  assert.equal(bindings.authentication.clearLoginCookies(), 'original-clear-result');
  assert.equal(counts.clear, 1);
  assert.equal(adapter.loggedIn(), false);
});
for (const [code, reason] of [[10, 'SESSION_MISMATCH'], [70, 'ACCOUNT_RISK'],
  [1013, 'ACCOUNT_RISK'], [1014, 'SECURITY_VERIFICATION_REQUIRED']]) {
  test(`native logout code ${code} retains ${reason} through original cookie clearing`, async t => {
    const {adapter, nativeLogout, counts, login} = fixture(t);
    const rpc = new NativeRPC({adapter, endpoints: [endpoint]});
    t.after(() => rpc.close());
    await adapter.verifyAccount('101');
    const stops = [];
    adapter.on('session-lost', reason => stops.push(reason));
    const event = {code};
    assert.equal(nativeLogout._sdk_logout(event), event);
    assert.equal(counts.clear, 1);
    assert.equal(adapter.originalSession.failure, reason);
    assert.equal(rpc.sessionFailure, reason);
    assert.deepEqual(stops, [reason]);
    login(true);
    await assert.rejects(rpc.refreshSession('101'), {code: reason});
    assert.equal(counts.sdkText, 0);
  });
}
