// Invoke only in the already verified anonymous, OS-firewalled process. The
// real original builder prepares headers; every transport below is a failure
// sink. No fake account, cookie, successful business reply or network is used.
const assert = require('node:assert/strict');
const {NativeRequestBroker} = require('./native-request-broker.cjs');
const endpoint = '/wapi/zpjob/job/data/list';
const offlineFailure = {heyiNativeHttp: 1, statusCode: 503,
  body: {code: -1, message: 'Offline broker fixture; transport not invoked'}};
const tick = () => new Promise(resolve => setTimeout(resolve, 10));
async function run({builder, clients, authentication, anonymousIsolated}) {
  if (anonymousIsolated !== true) throw Error('Anonymous isolated broker check required');
  const changes = [], brokers = [];
  const authPrevious = Object.getOwnPropertyDescriptor(authentication, 'getAuthCookieHeader');
  const authOriginal = authentication.getAuthCookieHeader;
  let fallbackCalls = 0, transportCalls = 0, release, authEntered = false, authReads = 0;
  let stage = 'attach-failure-sink';
  const traces = [];
  try {
    for (const client of clients) {
      const previous = Object.getOwnPropertyDescriptor(client, 'send');
      const hook = () => {
        fallbackCalls++;
        return Promise.resolve({status: false, statusCode: 503, body: offlineFailure.body});
      };
      Object.defineProperty(client, 'send', {value: hook, configurable: true, writable: true});
      changes.push({client, previous, hook});
    }
    const transport = async input => {
      transportCalls++;
      const headers = Object.getOwnPropertyDescriptors(input.headers || {});
      const value = name => headers[Object.keys(headers).find(key => key.toLowerCase() === name)]?.value;
      assert.equal(typeof value('cookie'), 'string');
      assert.equal(typeof value('user-agent'), 'string');
      assert.match(value('user-agent'), /^[\x20-\x7e]+$/);
      assert.equal(typeof value('traceid'), 'string');
      assert.ok(value('traceid').length > 0);
      traces.push(value('traceid'));
      return offlineFailure;
    };
    const broker = new NativeRequestBroker({builder, clients, endpoints: [endpoint], transport, timeout: 1500});
    brokers.push(broker);
    for (const marker of ['one', 'two']) {
      stage = 'original-builder-' + marker;
      assert.deepEqual(await broker.request({url: endpoint, method: 'GET',
        data: {heyiOfflineBroker: marker}}, () => {}), offlineFailure);
    }
    stage = 'distinct-original-traces';
    assert.notEqual(traces[0], traces[1]);
    broker.close();
    const firstDeadline = Date.now() + 1500;
    while (!broker.detached && Date.now() < firstDeadline) await tick();
    assert.equal(broker.detached, true);
    const delayed = function(...args) {
      authEntered = true;authReads++;
      return new Promise((resolve, reject) => {
        let released = false;
        release = () => {
          if (released) return;
          released = true;
          return Promise.resolve().then(() => Reflect.apply(authOriginal, this, args)).then(resolve, reject);
        };
      });
    };
    Object.defineProperty(authentication, 'getAuthCookieHeader',
      {value: delayed, configurable: true, writable: true});
    stage = 'credential-deadline';
    const second = new NativeRequestBroker({builder, clients, endpoints: [endpoint], transport, timeout: 40});
    brokers.push(second);
    const pending = second.request({url: endpoint, method: 'GET', data: {heyiOfflineBroker: 'late'}}, () => {});
    await assert.rejects(pending, {code: 'NATIVE_HTTP_TIMEOUT'});
    assert.equal(authEntered, true);assert.equal(authReads, 1);
    second.close();
    stage = 'retain-hook-for-late-builder';
    assert.equal(second.detached, false);
    release();
    stage = 'late-builder-detaches';
    const deadline = Date.now() + 1500;
    while (!second.detached && Date.now() < deadline) await tick();
    assert.equal(second.detached, true);
    assert.equal(transportCalls, 2);assert.equal(fallbackCalls, 0);
    return {ok: true, originalBuilderCalls: 3, realPreparedHeaders: true,
      originalTraceIdsDistinct: true, actualNonSuccessStatusPreserved: true,
      credentialWaitDeadline: true, lateSendAfterCloseRejected: true,
      originalHTTPCalls: 0, fallbackIntercepts: fallbackCalls, ownTransportCalls: transportCalls,
      candidateMessagesSent: 0, rawHeadersExported: false};
  } catch (error) {
    return {ok: false, diagnostic: {stage,
      code: ['NATIVE_HTTP_TIMEOUT', 'NATIVE_HTTP_FAILED', 'UNEXPECTED_NATIVE_RETRY',
        'UNSUPPORTED_OPERATION', 'INVALID_NATIVE_RESPONSE', 'ERR_ASSERTION'].includes(error?.code) ?
        error.code : 'CHECK_INCOMPLETE'},
      originalHTTPCalls: 0, fallbackIntercepts: fallbackCalls, ownTransportCalls: transportCalls,
      authDelayEntered: authEntered, authReads, candidateMessagesSent: 0, rawHeadersExported: false};
  } finally {
    release?.();
    for (const broker of brokers) broker.close();
    const deadline = Date.now() + 1500;
    while (brokers.some(broker => !broker.detached) && Date.now() < deadline) await tick();
    if (authPrevious) Object.defineProperty(authentication, 'getAuthCookieHeader', authPrevious);
    else delete authentication.getAuthCookieHeader;
    // If a builder still awaits credentials, retain the blocking sink rather
    // than restoring any original network path underneath that late call.
    if (brokers.every(broker => broker.detached)) {
      for (const {client, previous, hook} of changes) {
        if (Object.getOwnPropertyDescriptor(client, 'send')?.value !== hook) continue;
        if (previous) Object.defineProperty(client, 'send', previous);
        else delete client.send;
      }
    }
  }
}
module.exports = {run};
