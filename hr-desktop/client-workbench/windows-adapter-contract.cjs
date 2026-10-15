// Anonymous, OS-firewalled original runtime only. Never provide a fake current
// identity or cookie. Original text encoding is stopped at its send boundary.
const {WindowsAdapter} = require('./windows-adapter.cjs');
const {decodeNativeProtocol} = require('./native-protocol.cjs');
const endpoint = '/wapi/zpjob/job/data/list';
const content = 'Own offline SDK contract; no network';

async function install(bindings, config = {}) {
  if (config.anonymousIsolated !== true) throw Error('Anonymous isolated adapter check required');
  let adapter, timer, stage = 'anonymous-login', sdkCalls = 0, loginReads = 0;
  const changes = [];
  const report = {ok: false, mode: 'offline-original-windows-adapter-contract',
    currentIdentityModified: false, rawValuesExported: false, candidateMessagesSent: 0};
  const replace = (object, name, hook) => {
    const previous = Object.getOwnPropertyDescriptor(object, name);
    Object.defineProperty(object, name, {value: hook, configurable: true, writable: true});
    changes.push({object, name, hook, previous});
  };
  const restore = () => {
    for (const {object, name, hook, previous} of changes.reverse()) {
      if (Object.getOwnPropertyDescriptor(object, name)?.value !== hook) continue;
      if (previous) Object.defineProperty(object, name, previous);
      else delete object[name];
    }
  };
  let operationSettled = true;
  try {
    const sdk = bindings.sdk, getter = sdk._sdk_getLoginStatus;
    replace(sdk, '_sdk_getLoginStatus', function(...args) {
      loginReads++;return Reflect.apply(getter, this, args);
    });
    if (await sdk._sdk_getLoginStatus() !== false) throw Error();
    const electron = require('electron');
    stage = 'install-adapter';
    adapter = new WindowsAdapter({bindings, net: electron.net, session: electron.session.defaultSession,
      endpoints: [endpoint], show() {}, nativeWindow: () => null, allContents: () => [],
      timeout: 1500, sessionTimeout: 1000});
    stage = 'signed-out-business-actions';
    const outcomes = [];
    for (const operation of [
      () => adapter.verifyAccount('101'),
      () => adapter.request({url: endpoint}, () => {}),
      () => adapter.sendText({content, options: {uid: '202', clientMid: '303'}}),
    ]) {
      try {await operation();outcomes.push(false);}
      catch (error) {outcomes.push(error.code === 'AUTH_REQUIRED');}
    }
    report.signedOutActionsRejected = outcomes.every(Boolean);
    report.adapterInstalled = true;adapter.close();adapter = null;
    stage = 'original-text-sdk';
    let interceptedSends = 0, interceptedHTTP = 0, packetMatches = false;
    replace(bindings.sender, 'send', function(packet) {
      interceptedSends++;
      try {
        const protocol = decodeNativeProtocol(packet), message = protocol.messages[0];
        packetMatches = protocol.type === 1 && protocol.messages.length === 1 &&
          message.to.uid === '202' && message.cmid === '303' &&
          message.body.type === 1 && message.body.text === content;
      } catch {}
      // Preserve the original SDK's own completion behavior; there is no
      // fabricated delivery receipt or network sender invocation.
    });
    for (const client of bindings.httpClients) replace(client, 'send', function() {
      interceptedHTTP++;
      return Promise.reject(Object.assign(Error('Offline send boundary'), {code: 'REQUESTS_PAUSED'}));
    });
    sdkCalls++;operationSettled = false;
    const completion = Promise.resolve().then(() => Reflect.apply(
      sdk._sdk_chatSocket_sendTextMessage, sdk, [{content, options: {uid: '202',
        encryptUid: 'offline-not-an-account', clientMid: '303'}}])).then(
      () => 'settled', () => 'rejected').finally(() => {
      operationSettled = true;restore();
    });
    const outcome = await Promise.race([completion, new Promise(resolve => {
      timer = setTimeout(() => resolve('timed-out'), 1500);
    })]);
    report.textSDKOutcome = outcome;
    report.interceptedNativeSends = interceptedSends;
    report.textPacketMatches = packetMatches;
    report.interceptedHTTPCalls = interceptedHTTP;
    report.ok = report.signedOutActionsRejected && outcome === 'settled' &&
      interceptedSends === 1 && packetMatches && interceptedHTTP === 0;
  } catch {
    report.ok = false;report.diagnostic = {stage};
  } finally {
    clearTimeout(timer);adapter?.close();
    // Keep the sink installed while an unexpected delayed original SDK call
    // is still pending. It must not regain the real transport underneath it.
    if (operationSettled) restore();
    report.originalTextSDKCalls = sdkCalls;report.originalLoginReads = loginReads;
    report.hooksRestored = operationSettled;
  }
  return report;
}
module.exports = {install};
