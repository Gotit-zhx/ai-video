const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {prepareExchangePage, commitExchangePage} = require('./native-exchange.cjs');

function fixture(t) {
  const sent = [], args = {token: 'test-token', account: '101', friendId: '202', job: 'job-one', clientMid: '303'};
  let valid = true, result, calls = 0, opens = 0;
  class XHR {
    constructor() {this.listeners = new Set();}
    open(method, url) {this.responseURL = new URL(url, 'https://www.zhipin.com').href;return 'opened';}
    send(body) {sent.push({xhr: this, body});return 'sent';}
    addEventListener(name, listener) {assert.equal(name, 'loadend');this.listeners.add(listener);}
    removeEventListener(_name, listener) {this.listeners.delete(listener);}
    respond(data, status = 200) {
      this.status = status;this.responseText = JSON.stringify(data);
      for (const listener of this.listeners) listener();
    }
  }
  const originalOpen = XHR.prototype.open, originalSend = XHR.prototype.send;
  const conversation = {securityId: 'security-one', toPositionId: 'job-one', requestResume: 0};
  const component = {type: 4, isCanNormalChart: true, isExchangeResumeBlueMsg: false,
    $el: {getClientRects: () => [{}], getAttribute: () => null},
    showTip: false, handleCheckOpen() {opens++;this.showTip = true;},
    handleExChange() {calls++;}};
  const state = {token: args.token, invoked: false, valid: () => valid,
    service: {conversation}, cleanups: [], finish(value) {result ??= value;state.outcome ??= value;}};
  const resume = {token: args.token, component, securityId: conversation.securityId,
    editor: {conversation$: conversation}, matchesJob: value => value.toPositionId === args.job};
  const context = vm.createContext({window: {__heyiChatAction: state, __heyiResumeAction: resume,
    XMLHttpRequest: XHR}, location: {href: 'https://www.zhipin.com/web/client/index'},
    URL, URLSearchParams, setTimeout, clearTimeout});
  const run = fn => vm.runInContext(`(${fn.toString()})(${JSON.stringify(args)})`, context);
  const cleanup = () => {for (const clean of state.cleanups.reverse()) clean();};
  t.after(cleanup);
  const request = (body = 'type=4&securityId=security-one', route = '/wapi/zpchat/exchange/request') => {
    const xhr = new XHR();
    assert.equal(xhr.open('POST', route), 'opened');assert.equal(xhr.send(body), 'sent');
    return xhr;
  };
  return {args, state, component, conversation, sent, XHR, run, request, cleanup,
    originalOpen, originalSend, result: () => result, calls: () => calls, opens: () => opens,
    invalidate: () => {valid = false;}};
}
test('the native confirmation runs once and needs both the exact exchange response and native state update', async t => {
  const f = fixture(t);
  assert.equal(f.run(prepareExchangePage).ok, true);
  assert.equal((await f.run(commitExchangePage)).ok, true);
  assert.equal(f.calls(), 1);
  assert.equal(f.opens(), 1);
  assert.equal((await f.run(commitExchangePage)).ok, false);
  const check = f.request(undefined, '/wapi/zpchat/exchange/test');
  check.respond({code: 0, zpData: {status: 0, type: 4}});
  assert.equal(f.result(), undefined, 'preflight alone cannot confirm');
  const request = f.request();
  request.respond({code: 0, zpData: {status: 0, type: 4}});
  assert.equal(f.result(), undefined, 'native conversation must update too');
  f.conversation.requestResume = 1;
  await new Promise(resolve => setTimeout(resolve, 60));
  assert.equal(f.result().receipt.transport, 'native-exchange-response');
  assert.equal(f.result().receipt.requestId, '303');
  assert.equal(f.result().receipt.friendId, '202');
  assert.equal(f.result().receipt.mid, undefined, 'no invented message delivery');
  f.cleanup();
  assert.equal(f.XHR.prototype.open, f.originalOpen);assert.equal(f.XHR.prototype.send, f.originalSend);
});
test('the native opening check must allow confirmation, including numeric native eligibility', async t => {
  const f = fixture(t);f.component.isCanNormalChart = 1;
  assert.equal(f.run(prepareExchangePage).ok, true);
  assert.equal((await f.run(commitExchangePage)).ok, true);
  assert.equal(f.opens(), 1);assert.equal(f.calls(), 1);
  const blocked = fixture(t);blocked.run(prepareExchangePage);
  blocked.component.handleCheckOpen = () => {blocked.component.isCanNormalChart = false;};
  assert.equal((await blocked.run(commitExchangePage)).ok, false);
  assert.equal(blocked.calls(), 0, 'opening restrictions cannot be skipped');
});
test('opening risk and rate limits preserve their real stop reason and cannot confirm', async t => {
  for (const [platform, http, expected] of [
    [1013, 200, 'ACCOUNT_RISK'], [9, 200, 'RATE_LIMITED'], [0, 429, 'RATE_LIMITED'],
    [1014, 200, 'SECURITY_VERIFICATION_REQUIRED'], [0, 401, 'AUTH_EXPIRED'],
  ]) {
    const f = fixture(t);f.run(prepareExchangePage);
    f.component.handleCheckOpen = () => f.request(undefined, '/wapi/zpchat/exchange/test')
      .respond({code: platform, zpData: {status: 0, type: 4}}, http);
    const result = await f.run(commitExchangePage);
    assert.equal(result.code, expected);assert.equal(f.calls(), 0);
    assert.equal(f.sent.length, 1);
  }
});
test('other candidates, duplicate parameters, other origins and other exchange types cannot acknowledge', async t => {
  for (const [body, route] of [
    ['type=4&securityId=other'], ['type=1&securityId=security-one'],
    ['type=4&type=1&securityId=security-one'],
    ['type=4&securityId=security-one', 'https://other.invalid/wapi/zpchat/exchange/request'],
  ]) {
    const f = fixture(t);f.run(prepareExchangePage);await f.run(commitExchangePage);
    f.conversation.requestResume = 1;
    f.request(body, route).respond({code: 0, zpData: {status: 0, type: 4}});
    assert.equal(f.result(), undefined);
  }
});
test('HTTP success without platform success, malformed response or later account changes cannot pass', async t => {
  for (const kind of ['denied', 'business-denied', 'bad-json', 'http-error', 'changed']) {
    const f = fixture(t);f.run(prepareExchangePage);await f.run(commitExchangePage);
    const xhr = f.request();f.conversation.requestResume = 1;
    if (kind === 'changed') f.invalidate();
    if (kind === 'bad-json') {xhr.status = 200;xhr.responseText = 'broken';for (const listener of xhr.listeners) listener();}
    else xhr.respond({code: kind === 'denied' ? 1013 : 0,
      zpData: {status: kind === 'business-denied' ? 1 : 0, type: 4}}, kind === 'http-error' ? 500 : 200);
    assert.notEqual(f.result()?.ok, true);
  }
});
test('disabled, already requested, resume offer, changed job and changed security never invoke confirmation', async t => {
  for (const kind of ['disabled', 'pending', 'offer', 'job', 'security']) {
    const f = fixture(t);assert.equal(f.run(prepareExchangePage).ok, true);
    if (kind === 'disabled') f.component.isCanNormalChart = false;
    if (kind === 'pending') f.conversation.requestResume = 1;
    if (kind === 'offer') f.component.isExchangeResumeBlueMsg = {mid: 'old'};
    if (kind === 'job') f.conversation.toPositionId = 'other';
    if (kind === 'security') f.conversation.securityId = 'other';
    assert.equal((await f.run(commitExchangePage)).ok, false);assert.equal(f.calls(), 0);
  }
});
test('multiple native exchange requests stay unconfirmed and observation never originates or retries a request', async t => {
  const f = fixture(t);f.run(prepareExchangePage);await f.run(commitExchangePage);
  assert.equal(f.sent.length, 0);
  const first = f.request(), second = f.request();
  f.conversation.requestResume = 1;
  for (const xhr of [first, second]) xhr.respond({code: 0, zpData: {status: 0, type: 4}});
  assert.equal(f.result().code, 'SEND_UNCONFIRMED');assert.equal(f.sent.length, 2);
});
