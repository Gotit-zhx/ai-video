// Run the original normalizer on a disposable sender object. Intercept the
// receiver only for this synchronous call; never insert into the original
// message store, change the current account or send a network packet.
const {readOriginalSDK} = require('./original-sdk-read.cjs');

async function install(bindings, config = {}) {
  if (config.anonymousIsolated !== true) throw Error('Anonymous isolated runtime required');
  const loggedIn = await readOriginalSDK(globalThis.$socket, '_sdk_getLoginStatus', 1000);
  const report = {mode: 'offline-original-message-normalization-contract', ok: false,
    loginStatusReads: 1, originalDispatchCalls: 0, originalReceiverCalls: 0,
    receiverIntercepts: 0, candidateMessagesSent: 0, currentIdentityRead: false,
    currentIdentityModified: false, rawMessagesExported: false};
  if (loggedIn !== false) return {...report, reason: 'anonymous-login-required'};
  const prototype = Object.getPrototypeOf(bindings.sender);
  const dispatch = Object.getOwnPropertyDescriptor(prototype, 'dispatchMessage')?.value;
  if (typeof dispatch !== 'function') return {...report, reason: 'original-dispatch-required'};
  const receiver = bindings.receiver, previous = Object.getOwnPropertyDescriptor(receiver, 'receiveMessage');
  if (previous && !previous.configurable) return {...report, reason: 'receiver-intercept-unavailable'};
  let captured;
  const intercept = function(messages) {
    report.receiverIntercepts++;
    if (report.receiverIntercepts === 1) captured = messages;
  };
  const sender = Object.create(prototype);
  Object.assign(sender, {userId: 101, maxMsgId: 1000, isPresence: 0,
    updateMaxMsgId() {throw Error('Offline normalizer must not update the original store');}});
  try {
    Object.defineProperty(receiver, 'receiveMessage', {configurable: true, writable: true, value: intercept});
    report.originalDispatchCalls++;
    const result = Reflect.apply(dispatch, sender, [{type: 1, messages: [{
      from: {uid: 101, source: 0, name: 'Offline fixture'},
      to: {uid: 202, source: 0}, mid: 303, cmid: 404, time: 1700000000000,
      status: 0, newSubmit: false, body: {type: 1, templateId: 1, text: 'Offline normalization only'},
    }]}]);
    if (result && typeof result.then === 'function') {
      return {...report, reason: 'asynchronous-original-dispatch-unverified'};
    }
    const message = Array.isArray(captured) && captured.length === 1 ? captured[0] : null;
    report.normalizedFieldsMatch = Boolean(message &&
      String(message.fromId) === '101' && String(message.toId) === '202' &&
      String(message.mid) === '303' && Number(message.time) === 1700000000000 &&
      message.isSelf === true && message.status === 1 && message.newSubmit !== true &&
      message.type === 'text' && message.text === 'Offline normalization only');
    report.clientMidPreserved = Boolean(message && String(message.clientMid || '') === '404');
    report.ok = report.receiverIntercepts === 1 && report.normalizedFieldsMatch;
    return {...report, reason: 'original-normalizer-checked'};
  } finally {
    if (Object.getOwnPropertyDescriptor(receiver, 'receiveMessage')?.value === intercept) {
      if (previous) Object.defineProperty(receiver, 'receiveMessage', previous);
      else delete receiver.receiveMessage;
    }
  }
}
module.exports = {install};
