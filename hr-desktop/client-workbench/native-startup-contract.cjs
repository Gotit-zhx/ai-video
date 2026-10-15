// Disposable, OS-firewalled startup check. Observe calls without changing
// their arguments, this, return values or exceptions. Never export originals.
let active;

function close() {
  if (!active) return;
  const state = active;active = undefined;
  for (const {object, name, previous, hook} of state.changes.reverse()) {
    const current = Object.getOwnPropertyDescriptor(object, name);
    if (current?.value !== hook && current?.get !== hook) continue;
    if (previous) Object.defineProperty(object, name, previous);
    else delete object[name];
  }
}

function install(bindings, config = {}) {
  if (config.anonymousIsolated !== true) throw Error('Disposable isolated startup check required');
  if (config.phase === 'arm') {
    if (active) throw Error('Startup observer already armed');
    const state = {bindings, changes: [], calls: {sdk: 0, authentication: 0, identity: 0, http: 0}};
    active = state;
    const observe = (object, name, kind, accessor = false) => {
      const previous = Object.getOwnPropertyDescriptor(object, name);
      const descriptor = previous || Object.getOwnPropertyDescriptor(Object.getPrototypeOf(object), name);
      const original = accessor ? descriptor?.get : descriptor?.value;
      if (typeof original !== 'function' || previous?.configurable === false) throw Error();
      const hook = function(...args) {
        state.calls[kind]++;
        return Reflect.apply(original, this, args);
      };
      Object.defineProperty(object, name, accessor ?
        {get: hook, set: descriptor.set, enumerable: descriptor.enumerable, configurable: true} :
        {value: hook, writable: true, configurable: true, enumerable: descriptor.enumerable});
      state.changes.push({object, name, previous, hook});
    };
    try {
      const methods = Object.keys(Object.getOwnPropertyDescriptors(bindings.sdk))
        .filter(name => /^_sdk_[A-Za-z0-9_]+$/.test(name));
      if (!methods.length || methods.length > 200) throw Error();
      for (const name of methods) observe(bindings.sdk, name, 'sdk');
      for (const name of ['getAuthCookieHeader', 'get', 'setLoginCookies', 'clearLoginCookies']) {
        observe(bindings.authentication, name, 'authentication');
      }
      for (const name of ['userInfo', 'userId', 'avatar']) observe(bindings.identity, name, 'identity', true);
      if (!Array.isArray(bindings.httpClients) || !bindings.httpClients.length) throw Error();
      for (const client of bindings.httpClients) observe(client, 'send', 'http');
      return {ok: true, observerArmed: true};
    } catch {
      close();throw Error('Startup observer could not be armed');
    }
  }
  if (config.phase !== 'verify' || !active) throw Error('Startup observer not armed');
  const state = active;
  try {
    const sameBindings = ['sdk', 'authentication', 'identity', 'network', 'receiver', 'sender',
      'messageFactoryPrototype'].every(key => bindings[key] === state.bindings[key]) &&
      bindings.httpClients.length === state.bindings.httpClients.length &&
      bindings.httpClients.every(client => state.bindings.httpClients.includes(client));
    const requestBuilderCaptured = typeof bindings.requestBuilder === 'function' &&
      require('node:util').types.isAsyncFunction(bindings.requestBuilder) &&
      bindings.requestBuilder.length === 1;
    const originalMethodsInvoked = Object.values(state.calls).reduce((sum, count) => sum + count, 0);
    return {ok: sameBindings && requestBuilderCaptured && originalMethodsInvoked === 0,
      sameOriginalInstances: sameBindings, requestBuilderCaptured,
      originalMethodsInvoked, sdkMethodsInvoked: state.calls.sdk,
      authenticationMethodsInvoked: state.calls.authentication,
      identityGettersInvoked: state.calls.identity, originalHTTPCalls: state.calls.http,
      rawValuesExported: false};
  } finally {close();}
}

module.exports = {install, close};
