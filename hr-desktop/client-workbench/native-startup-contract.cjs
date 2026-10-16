// Disposable, OS-firewalled startup check. Observe calls without changing
// their arguments, this, return values or exceptions. Never export originals.
const {AsyncLocalStorage} = require('node:async_hooks');
const bindingScope = new AsyncLocalStorage();
let active;

function observeBindingCall(callback) {
  if (!active?.scopedObservation || typeof callback !== 'function') {
    throw Error('Scoped startup observer must be armed');
  }
  active.bindingScopeEntries++;
  return bindingScope.run(active.scopeToken, callback);
}

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
    const state = {bindings, changes: [], calls: {sdk: 0, authentication: 0, identity: 0, http: 0},
      bindingCalls: {sdk: 0, authentication: 0, identity: 0, http: 0},
      scopedObservation: config.scopedObservation === true, scopeToken: {},
      bindingScopeEntries: 0, observedCalls: []};
    active = state;
    const observe = (object, name, kind, accessor = false) => {
      const previous = Object.getOwnPropertyDescriptor(object, name);
      const descriptor = previous || Object.getOwnPropertyDescriptor(Object.getPrototypeOf(object), name);
      const original = accessor ? descriptor?.get : descriptor?.value;
      if (typeof original !== 'function' || previous?.configurable === false) throw Error();
      const hook = function(...args) {
        state.calls[kind]++;
        const bindingCall = state.scopedObservation && bindingScope.getStore() === state.scopeToken;
        if (bindingCall) state.bindingCalls[kind]++;
        if (state.observedCalls.length < 30) {
          // Fixed source categories and numeric offsets only. Preserve the
          // zero-call binding gate while identifying concurrent runtime work;
          // never serialize full stacks, paths, arguments or return values.
          const frames = String(new Error().stack || '').split('\n').slice(2, 7).map(line => {
            const native = line.match(/[\\/]main\.jsc:(\d{1,8}):(\d{1,8})/);
            if (native) return {source: 'original-main', line: Number(native[1]), column: Number(native[2])};
            if (/[\\/]native-bindings\.cjs:/.test(line)) return {source: 'binding-controller'};
            if (/node:/.test(line)) return {source: 'node-runtime'};
            return {source: 'unclassified'};
          });
          state.observedCalls.push({kind, method: name, frames,
            ...(state.scopedObservation ? {origin: bindingCall ? 'binding' : 'unscoped-runtime'} : {})});
        }
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
    const bindingMethodsInvoked = Object.values(state.bindingCalls).reduce((sum, count) => sum + count, 0);
    const noBindingSideEffects = state.scopedObservation ?
      state.bindingScopeEntries > 0 && bindingMethodsInvoked === 0 : originalMethodsInvoked === 0;
    return {ok: sameBindings && requestBuilderCaptured && noBindingSideEffects,
      sameOriginalInstances: sameBindings, requestBuilderCaptured,
      originalMethodsInvoked, sdkMethodsInvoked: state.calls.sdk,
      authenticationMethodsInvoked: state.calls.authentication,
      identityGettersInvoked: state.calls.identity, originalHTTPCalls: state.calls.http,
      ...(state.scopedObservation ? {scopedObservation: true, bindingScopeEntries: state.bindingScopeEntries,
        bindingMethodsInvoked, bindingSDKCalls: state.bindingCalls.sdk,
        bindingAuthenticationCalls: state.bindingCalls.authentication,
        bindingIdentityGetters: state.bindingCalls.identity, bindingHTTPCalls: state.bindingCalls.http,
        unscopedRuntimeMethodsInvoked: originalMethodsInvoked - bindingMethodsInvoked} : {}),
      observedCalls: state.observedCalls, rawValuesExported: false};
  } finally {close();}
}

module.exports = {install, close, observeBindingCall};
