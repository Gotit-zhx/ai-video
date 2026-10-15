// Disposable offline check. Exercise the original encoder on an addon-owned
// object, never its constructor, current identity, sender or business SDK.
const {decodeNativeProtocol} = require('./native-protocol.cjs');
const {readOriginalSDK} = require('./original-sdk-read.cjs');

async function install(bindings, config = {}) {
  if (config.anonymousIsolated !== true) throw Error('Anonymous isolated runtime required');
  const loggedIn = await readOriginalSDK(globalThis.$socket, '_sdk_getLoginStatus', 1000);
  const base = {mode: 'offline-original-message-encoder-contract', ok: false,
    loginStatusReads: 1, originalFactoryCalls: 0, candidateMessagesSent: 0,
    currentIdentityRead: false, currentIdentityModified: false, rawPacketsExported: false};
  if (loggedIn !== false) return {...base, reason: 'anonymous-login-required'};
  const prototype = bindings.messageFactoryPrototype;
  const method = Object.getOwnPropertyDescriptor(prototype, 'getProtoMsg')?.value;
  if (typeof method !== 'function') return {...base, reason: 'original-message-factory-required'};
  const factory = Object.create(prototype);
  Object.assign(factory, {type: 0, userInfo: {
    userId: '101', name: 'Offline fixture', tinyAvatar: '',
  }, nowTime: 1700000000000});
  const packet = Reflect.apply(method, factory, [{
    content: '离线编码验证 / offline only',
    options: {uid: '202', encryptUid: 'fixture-not-an-account', clientMid: '303'},
  }]);
  const packetShape = ArrayBuffer.isView(packet) ? 'byte-view' :
    packet && typeof packet.finish === 'function' ? 'writer' : 'unsupported';
  const decoded = decodeNativeProtocol(packet), message = decoded.messages[0];
  return {...base, originalFactoryCalls: 1, packetShape,
    ok: decoded.type === 1 && decoded.messages.length === 1 &&
      message.from.uid === '101' && message.to.uid === '202' &&
      message.mid === '303' && message.cmid === '303' && message.time === '1700000000000' &&
      message.body.type === 1 && message.body.text === '离线编码验证 / offline only',
    reason: 'original-encoder-contract-checked'};
}
module.exports = {install};
