// Lives only in the verified target. Original function references, prepared
// headers and results never become controller return values.
const {types} = require('node:util');
const {readOriginalSDK} = require('./original-sdk-read.cjs');

let active;
function boundary(input) {
  const state = active;
  if (!state || state.closed) return Promise.reject(Error('Native request discovery closed'));
  state.intercepts++;
  const header = name => {
    const descriptors = Object.getOwnPropertyDescriptors(input?.headers || {});
    const keys = Object.keys(descriptors).filter(key => key.toLowerCase() === name);
    return keys.length === 1 ? descriptors[keys[0]].value : undefined;
  };
  let url;
  try {url = new URL(input?.url);} catch {}
  const headersValid = url?.origin === 'https://www.zhipin.com' && !url.username && !url.password &&
    typeof header('cookie') === 'string' && typeof header('user-agent') === 'string' &&
    header('user-agent').length > 0 && header('user-agent').length <= 4096 &&
    /^[\x20-\x7e]+$/.test(header('user-agent'));
  if (state.phase === 'sdk') {
    state.sdkBoundaryValid = headersValid && url.pathname === '/wapi/zpuser/wap/getUserInfo.json';
  } else if (state.phase === 'builder') {
    state.builderBoundaryValid = headersValid && url.pathname === '/wapi/zpjob/job/data/list' &&
      input.method?.toLowerCase() === 'post' && input.data?.heyiOfflineFixture === '中文' &&
      Array.isArray(input.data?.tags) && input.data.tags.join(',') === 'one,two';
  }
  return Promise.resolve({status: false, statusCode: 503,
    body: {code: -1, message: 'Offline request discovery; transport not invoked'}});
}
async function install(bindings, config = {}) {
  if (config.anonymousIsolated !== true || active) throw Error('Unique anonymous isolated discovery required');
  if (await readOriginalSDK(globalThis.$socket, '_sdk_getLoginStatus', 1000) !== false) {
    return {ok: false, reason: 'anonymous-login-required'};
  }
  const state = {bindings, cleanup: [], closed: false, intercepts: 0,
    sdkCalls: 0, builderCalls: 0, phase: 'prepared', verifyBroker: config.verifyBroker === true};
  active = state;
  try {
    if (!Array.isArray(bindings.httpClients) || !bindings.httpClients.length ||
        bindings.httpClients.length > 8) throw Error();
    for (const client of bindings.httpClients) {
      const previous = Object.getOwnPropertyDescriptor(client, 'send');
      if (previous && !previous.configurable) throw Error();
      Object.defineProperty(client, 'send', {configurable: true, writable: true, value: boundary});
      state.cleanup.push({client, previous});
    }
    return {ok: true, mode: 'offline-original-request-discovery-prepared', loginStatusReads: 1};
  } catch {
    close();
    throw Error('Original request boundary unavailable');
  }
}
function trigger() {
  const state = active;
  if (!state || state.closed || state.phase !== 'prepared') throw Error('Discovery cannot be replayed');
  state.phase = 'sdk';state.sdkCalls++;
  state.operationSettled = false;
  state.sdkCompletion = Promise.resolve().then(() =>
    Reflect.apply(state.bindings.sdk._sdk_getUserInfo, state.bindings.sdk, [true]))
    .then(() => {state.sdkSettled = true;}, () => {state.sdkSettled = true;})
    .finally(() => {state.operationSettled = true;});
  state.operation = state.sdkCompletion;
}
function attachBuilder(fn) {
  const state = active;
  if (!state || state.closed || state.phase !== 'sdk' || state.builder ||
      typeof fn !== 'function' || types.isProxy(fn) || !types.isAsyncFunction(fn) ||
      Object.getOwnPropertyDescriptor(fn, 'length')?.value !== 1 ||
      Object.values(state.bindings.sdk).includes(fn)) throw Error('Original request builder unavailable');
  state.builder = fn;
  return true;
}
async function verify() {
  const state = active;
  if (!state || state.closed || !state.builder || state.phase !== 'sdk') {
    throw Error('Original request builder has not been captured');
  }
  await state.sdkCompletion;
  if (state.intercepts !== 1 || !state.sdkBoundaryValid) throw Error('Original SDK boundary not verified');
  state.phase = 'builder';state.builderCalls++;
  // Own offline data only. The boundary above returns failure before transport.
  state.operationSettled = false;
  state.operation = Promise.resolve().then(() => Reflect.apply(state.builder, undefined, [{
    url: '/wapi/zpjob/job/data/list', method: 'post',
    data: {heyiOfflineFixture: '中文', tags: ['one', 'two']},
  }])).catch(() => {}).finally(() => {state.operationSettled = true;});
  await state.operation;
  const report = {mode: 'offline-original-request-builder-contract',
    ok: state.intercepts === 2 && state.sdkSettled && state.builderBoundaryValid === true,
    originalSDKCalls: state.sdkCalls, originalBuilderCalls: state.builderCalls,
    originalHTTPCalls: 0, interceptedRequests: state.intercepts,
    nativeHeadersPrepared: state.builderBoundaryValid === true,
    candidateMessagesSent: 0, rawHeadersExported: false, originalFunctionExported: false};
  const builder = state.builder, bindings = state.bindings;
  close();
  if (state.verifyBroker && report.ok) {
    report.broker = await require('./native-request-broker-smoke.cjs').run({
      builder, clients: bindings.httpClients, authentication: bindings.authentication,
      anonymousIsolated: true,
    });
    report.ok = report.broker.ok === true;
  }
  return report;
}
function close() {
  const state = active;
  if (!state || state.closed || state.closeRequested) return;
  if (state.operation && !state.operationSettled) {
    state.closeRequested = true;
    // Delayed credential preparation must still hit the blocking boundary.
    // A stalled operation retains its blocker until this disposable process
    // exits; it must never regain the original network transport.
    void state.operation.finally(() => {
      state.closeRequested = false;close();
    }).catch(() => {});
    return;
  }
  state.closed = true;
  for (const {client, previous} of state.cleanup) {
    if (Object.getOwnPropertyDescriptor(client, 'send')?.value !== boundary) continue;
    if (previous) Object.defineProperty(client, 'send', previous);
    else delete client.send;
  }
  state.builder = null;state.bindings = null;state.cleanup.length = 0;
  // Keep the closed marker: discovery on this process is never replayed.
}
module.exports = {install, boundary, trigger, attachBuilder, verify, close};
