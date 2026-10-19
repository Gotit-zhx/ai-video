const test = require('node:test');
const assert = require('node:assert/strict');
const {NativeSession, cookieFields} = require('./native-session.cjs');

function fixture(t, {timeout = 100, login, cookie} = {}) {
  let account = '101', signedIn = true, header = 'wt2=private-fixture; zp_at=private-secondary';
  const counts = {login: 0, identity: 0, cookie: 0};
  const sdk = {_sdk_getLoginStatus() {counts.login++;return login ? login() : Promise.resolve(signedIn);}};
  const identity = {get userInfo() {counts.identity++;return {userId: account};}};
  const authentication = {getAuthCookieHeader() {counts.cookie++;return cookie ? cookie() : Promise.resolve(header);}};
  const session = new NativeSession({sdk, identity, authentication, timeout});
  t.after(() => session.close());
  return {session, counts, account: value => {account = value;}, login: value => {signedIn = value;},
    header: value => {header = value;}};
}
function prepared(session, cookie = 'wt2=private-fixture', userAgent = 'original-fixture-UA') {
  session.observePrepared({url: 'https://www.zhipin.com/wapi/offline/fixture',
    headers: {Cookie: cookie, 'User-Agent': userAgent}});
}

test('construction initiates no SDK, identity or credential reads; unresolved login promises are not true', async t => {
  let release;
  const {session, counts} = fixture(t, {login: () => new Promise(resolve => {release = resolve;})});
  assert.deepEqual(counts, {login: 0, identity: 0, cookie: 0});
  assert.equal(session.loggedIn(), false);
  const task = session.verifyAccount('101');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(session.loggedIn(), false);assert.equal(counts.identity, 0);
  release(false);await assert.rejects(task, {code: 'AUTH_REQUIRED'});
  assert.equal(counts.cookie, 0);
});
test('only an explicit true memory result verifies the actual current identity', async t => {
  for (const value of [false, undefined, null, 1, {}, 'true']) {
    const {session} = fixture(t, {login: async () => value});
    await assert.rejects(session.verifyAccount('101'), {code: 'AUTH_REQUIRED'});
    assert.equal(session.loggedIn(), false);
  }
  const {session} = fixture(t);
  assert.equal(await session.verifyAccount('101'), '101');assert.equal(session.loggedIn(), true);
});
test('snapshot uses fresh original cookies and a matching passively observed original UA', async t => {
  const {session, counts} = fixture(t);
  await assert.rejects(session.snapshot('101', () => {}), {code: 'SESSION_PREPARING'});
  prepared(session);
  assert.deepEqual(await session.snapshot('101', () => {}), {
    accountId: '101', userAgent: 'original-fixture-UA',
    cookies: [{name: 'wt2', value: 'private-fixture', domain: 'www.zhipin.com', secure: true},
      {name: 'zp_at', value: 'private-secondary', domain: 'www.zhipin.com', secure: true}],
  });
  assert.equal(counts.cookie, 2);
  assert.equal(Object.hasOwn(session.observed, 'cookieHeader'), false);
  assert.equal(JSON.stringify(session.observed).includes('private-fixture'), false);
});
test('cookie rotation cannot borrow the previous native UA association', async t => {
  const {session, header} = fixture(t);prepared(session);
  await session.snapshot('101', () => {});
  header('wt2=rotated-fixture');
  await assert.rejects(session.snapshot('101', () => {}), {code: 'SESSION_PREPARING'});
  prepared(session, 'wt2=rotated-fixture', 'new-original-UA');
  assert.equal((await session.snapshot('101', () => {})).userAgent, 'new-original-UA');
});
test('passive observation rejects other origins, duplicate headers and getters without executing them', async t => {
  const {session} = fixture(t);
  const headers = {Cookie: 'wt2=private-fixture', 'User-Agent': 'native'};
  Object.defineProperty(headers, 'cookie', {get() {assert.fail('must not read');}});
  session.observePrepared({url: 'https://www.zhipin.com/wapi/offline/fixture', headers});
  session.observePrepared({url: 'https://other.invalid', headers: {Cookie: 'wt2=private-fixture', 'User-Agent': 'other'}});
  await assert.rejects(session.snapshot('101', () => {}), {code: 'SESSION_PREPARING'});
});
test('a timed-out or cancelled login getter cannot establish a late session', async t => {
  let release;
  const {session} = fixture(t, {timeout: 20, login: () => new Promise(resolve => {release = resolve;})});
  await assert.rejects(session.verifyAccount('101'), {code: 'AUTH_REQUIRED'});
  release(true);await new Promise(resolve => setImmediate(resolve));
  assert.equal(session.loggedIn(), false);
  const controller = new AbortController();controller.abort();
  await assert.rejects(session.verifyAccount('101', controller.signal), {code: 'REQUESTS_PAUSED'});
});
test('a changed account retains a mismatch hold and cannot transfer the native observations', async t => {
  const {session, account} = fixture(t);prepared(session);await session.snapshot('101', () => {});
  account('999');
  await assert.rejects(session.verifyAccount('101'), {code: 'SESSION_MISMATCH'});
  await assert.rejects(session.verifyAccount('999'), {code: 'SESSION_MISMATCH'});
  assert.equal(session.loggedIn(), false);
});
test('a risk stop cancels pending credential reads and survives true login status', async t => {
  let entered, release;
  const started = new Promise(resolve => {entered = resolve;});
  const {session} = fixture(t, {cookie: () => {entered();return new Promise(resolve => {release = resolve;});}});
  prepared(session);
  const task = session.credentials('101', () => {});await started;
  session.invalidate('ACCOUNT_RISK');
  await assert.rejects(task, {code: 'ACCOUNT_RISK'});
  release('wt2=private-fixture');await new Promise(resolve => setImmediate(resolve));
  await assert.rejects(session.verifyAccount('101'), {code: 'ACCOUNT_RISK'});
  assert.equal(session.loggedIn(), false);
});
test('ordinary expiry requires observed real sign-out or changed native credentials before login recovery', async t => {
  const {session, login} = fixture(t);prepared(session);
  const events = [];session.on('login-established', () => events.push('verified'));
  await session.snapshot('101', () => {});session.invalidate('AUTH_EXPIRED');
  await session.snapshot('101', () => {});
  assert.deepEqual(events, []);assert.equal(session.failure, 'AUTH_EXPIRED');
  login(false);await assert.rejects(session.verifyAccount('101'), {code: 'AUTH_REQUIRED'});
  login(true);await session.snapshot('101', () => {});
  assert.deepEqual(events, ['verified']);assert.equal(session.failure, null);
  session.invalidate('SECURITY_VERIFICATION_REQUIRED');
  await assert.rejects(session.snapshot('101', () => {}), {code: 'SECURITY_VERIFICATION_REQUIRED'});
});
test('cookie parsing rejects ambiguous, missing and malformed authentication', () => {
  for (const input of ['', 'wt2=', 'wt2=a; wt2=b', 'wt2=a b', 'other=a', 'wt2=a\r\nother=b']) {
    assert.throws(() => cookieFields(input), {code: 'AUTH_REQUIRED'});
  }
});
