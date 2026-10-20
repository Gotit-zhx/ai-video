const {decodeNativeProtocol} = require('./native-protocol.cjs');

const installed = new WeakSet();
const id = value => (typeof value !== 'number' || Number.isSafeInteger(value)) &&
  /^[1-9]\d{0,29}$/.test(String(value || '')) ? String(value) : '';
const sameTime = (left, right) => {
  left = Number(left);right = Number(right);
  return Number.isSafeInteger(left) && left > 0 && left === right;
};
function method(object, name) {
  for (let target = object; target; target = Object.getPrototypeOf(target)) {
    const descriptor = Object.getOwnPropertyDescriptor(target, name);
    if (descriptor) {
      if (typeof descriptor.value !== 'function') break;
      return descriptor.value;
    }
  }
  throw Error('Original native message method unavailable');
}

// Observe the original call chain. No polling, subscriptions, sends or message
// store writes are added. Correlation lives only on the synchronous dispatch
// stack; it is never carried into another account, later packet or retry.
function installMessageObservers({sender, receiver, account, observeSend, receive}) {
  if (!sender || !receiver || typeof account !== 'function' ||
      typeof observeSend !== 'function' || typeof receive !== 'function' ||
      installed.has(sender) || installed.has(receiver)) {
    throw Error('Unique native message observers required');
  }
  const originals = {send: method(sender, 'send'), dispatch: method(sender, 'dispatchMessage'),
    receive: method(receiver, 'receiveMessage')};
  const scope = [], changes = [];
  let closed = false;
  const current = () => {
    if (closed) return '';
    try {return id(account());} catch {return '';}
  };
  const link = (message, frame) => {
    const copy = {...message};
    if (copy.isSelf !== true || copy.type !== 'text') return copy;
    delete copy.clientMid;
    if (!frame || frame.account !== current() || frame.protocol?.type !== 1 ||
        !Array.isArray(frame.protocol.messages) || frame.protocol.messages.length > 128) return copy;
    const matches = frame.protocol.messages.filter(raw =>
      id(raw?.from?.uid) === frame.account && id(raw?.from?.uid) === id(copy.fromId) &&
      id(raw?.to?.uid) && id(raw.to.uid) === id(copy.toId) &&
      id(raw.mid) && id(raw.mid) === id(copy.mid) &&
      sameTime(raw.time, copy.time) && raw.body?.type === 1 &&
      typeof raw.body.text === 'string' && raw.body.text.length <= 16384 &&
      raw.body.text === copy.text && id(raw.cmid));
    if (matches.length === 1 &&
        (!message.clientMid || id(message.clientMid) === id(matches[0].cmid))) {
      copy.clientMid = id(matches[0].cmid);
    }
    return copy;
  };
  const hooks = {
    send: function(...args) {
      const own = current();
      if (own) try {
        const protocol = decodeNativeProtocol(args[0]);
        if (current() === own) observeSend(protocol);
      } catch {}
      return Reflect.apply(originals.send, this, args);
    },
    dispatch: function(...args) {
      scope.push({account: current(), protocol: args[0]});
      try {return Reflect.apply(originals.dispatch, this, args);}
      finally {scope.pop();}
    },
    receive: function(...args) {
      const own = current();
      const result = Reflect.apply(originals.receive, this, args);
      if (own && current() === own && Array.isArray(args[0]) && args[0].length <= 128) {
        try {receive(args[0].map(message => link(message, scope[scope.length - 1])));} catch {}
      }
      return result;
    },
  };
  const restore = () => {
    if (closed) return;
    closed = true;
    for (const {object, name, previous, hook} of changes.reverse()) {
      if (Object.getOwnPropertyDescriptor(object, name)?.value !== hook) continue;
      if (previous) Object.defineProperty(object, name, previous);
      else delete object[name];
    }
    installed.delete(sender);installed.delete(receiver);scope.length = 0;
  };
  try {
    for (const [object, name, hook] of [[sender, 'send', hooks.send],
      [sender, 'dispatchMessage', hooks.dispatch], [receiver, 'receiveMessage', hooks.receive]]) {
      const previous = Object.getOwnPropertyDescriptor(object, name);
      if (previous && !previous.configurable) throw Error('Original message observer cannot be attached');
      Object.defineProperty(object, name, {configurable: true, writable: true, value: hook});
      changes.push({object, name, previous, hook});
    }
    installed.add(sender);installed.add(receiver);
    return {close: restore};
  } catch (error) {restore();throw error;}
}
module.exports = {installMessageObservers};
