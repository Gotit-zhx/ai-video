const test = require('node:test');
const assert = require('node:assert/strict');
const contract = require('./native-startup-contract.cjs');

function fixture(t) {
  t.after(() => contract.close());
  const sentinel = Error('private-original-error');
  class Identity {
    get userInfo() {throw sentinel;}
    get userId() {return this;}
    get avatar() {return 'private-avatar';}
  }
  class Authentication {
    get() {return this;}
    getAuthCookieHeader() {return Promise.resolve('private-cookie');}
    setLoginCookies() {}
    clearLoginCookies() {}
  }
  const pending = Promise.resolve(false);
  const sdk = {_sdk_getLoginStatus() {return pending;}, _sdk_error() {throw sentinel;}};
  const http = {send(input) {assert.equal(this, http);return input;}};
  const bindings = {sdk, identity: new Identity(), authentication: new Authentication(),
    network: {}, receiver: {}, sender: {}, messageFactoryPrototype: {}, httpClients: [http],
    requestBuilder: async function fixtureBuilder(_input) {assert.fail('must not execute');}};
  const original = Object.getOwnPropertyDescriptor(sdk, '_sdk_getLoginStatus');
  return {bindings, sentinel, pending, original};
}
const config = phase => ({phase, anonymousIsolated: true});

test('startup binding checks references without invoking original methods or exporting originals', t => {
  const {bindings, original} = fixture(t);
  assert.deepEqual(contract.install(bindings, config('arm')), {ok: true, observerArmed: true});
  const result = contract.install({...bindings, httpClients: [...bindings.httpClients]}, config('verify'));
  assert.deepEqual(result, {ok: true, sameOriginalInstances: true, requestBuilderCaptured: true,
    originalMethodsInvoked: 0, sdkMethodsInvoked: 0, authenticationMethodsInvoked: 0,
    identityGettersInvoked: 0, originalHTTPCalls: 0, observedCalls: [], rawValuesExported: false});
  assert.deepEqual(Object.getOwnPropertyDescriptor(bindings.sdk, '_sdk_getLoginStatus'), original);
  assert.equal(Object.hasOwn(bindings.identity, 'userInfo'), false);
  assert.equal(Object.hasOwn(bindings.authentication, 'getAuthCookieHeader'), false);
});
test('the observer preserves this, Promise identity and original exceptions while counting calls', t => {
  const {bindings, pending, sentinel} = fixture(t);
  contract.install(bindings, config('arm'));
  assert.equal(bindings.sdk._sdk_getLoginStatus(), pending);
  assert.throws(() => bindings.sdk._sdk_error(), error => error === sentinel);
  assert.equal(bindings.authentication.get(), bindings.authentication);
  assert.equal(bindings.identity.userId, bindings.identity);
  assert.throws(() => bindings.identity.userInfo, error => error === sentinel);
  const input = {};assert.equal(bindings.httpClients[0].send(input), input);
  const result = contract.install(bindings, config('verify'));
  assert.equal(result.ok, false);assert.equal(result.originalMethodsInvoked, 6);
  assert.equal(result.sdkMethodsInvoked, 2);assert.equal(result.identityGettersInvoked, 2);
  assert.equal(result.authenticationMethodsInvoked, 1);assert.equal(result.originalHTTPCalls, 1);
  assert.equal(result.observedCalls.length, 6);
  assert.deepEqual(result.observedCalls.map(call => call.method),
    ['_sdk_getLoginStatus', '_sdk_error', 'get', 'userId', 'userInfo', 'send']);
  assert.equal(JSON.stringify(result.observedCalls).includes('private'), false);
});
test('a different instance or missing builder fails the cold binding check', t => {
  const {bindings} = fixture(t);
  contract.install(bindings, config('arm'));
  const result = contract.install({...bindings, sender: {}, requestBuilder: undefined}, config('verify'));
  assert.equal(result.ok, false);assert.equal(result.sameOriginalInstances, false);
  assert.equal(result.requestBuilderCaptured, false);
});
test('a partial observer failure restores already changed properties', t => {
  const {bindings, original} = fixture(t);
  Object.defineProperty(bindings.authentication, 'get', {value() {}, configurable: false});
  assert.throws(() => contract.install(bindings, config('arm')));
  assert.deepEqual(Object.getOwnPropertyDescriptor(bindings.sdk, '_sdk_getLoginStatus'), original);
  assert.throws(() => contract.install(bindings, config('verify')));
  assert.throws(() => contract.install(bindings, {phase: 'arm'}));
});
