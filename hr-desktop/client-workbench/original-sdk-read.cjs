// In-process access to the original renderer dispatcher. Deliberately limited
// to two memory-only getters; it cannot invoke HTTP, login, or sending actions.
const {randomUUID} = require('node:crypto');
const READS = new Set(['_sdk_getLoginStatus', '_sdk_getNetworkStatus']);

async function waitForOriginalDispatcher(lookup, timeout = 10000, interval = 100) {
  if (typeof lookup !== 'function' || !Number.isInteger(timeout) || timeout < 1 ||
      timeout > 15000 || !Number.isInteger(interval) || interval < 1 || interval > 1000) {
    throw Error('Invalid original dispatcher readiness check');
  }
  const deadline = Date.now() + timeout;
  while (true) {
    // Electron ready does not imply the original asynchronous socket service
    // has been registered. These checks read local types only, never the SDK.
    const service = lookup();
    if (service && typeof service.handleEvent === 'function' &&
        typeof service.clientID?.get === 'function' &&
        typeof service.clientID?.delete === 'function') return service;
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw Error('Original dispatcher did not become ready');
    await new Promise(resolve => setTimeout(resolve, Math.min(interval, remaining)));
  }
}

function readOriginalSDK(service, method, timeout = 5000) {
  if (!READS.has(method)) return Promise.reject(Error('Original SDK read is not permitted'));
  if (!service || typeof service.handleEvent !== 'function' ||
      typeof service.clientID?.get !== 'function' || typeof service.clientID?.delete !== 'function') {
    return Promise.reject(Error('Original renderer dispatcher is unavailable'));
  }
  if (!Number.isInteger(timeout) || timeout < 1 || timeout > 10000) {
    return Promise.reject(Error('Invalid original SDK deadline'));
  }
  const cid = 'heyi-memory-read-' + randomUUID(), sid = 'heyi-local-' + randomUUID();
  const busId = randomUUID(), handlers = new Map();
  return new Promise((resolve, reject) => {
    let settled = false;
    function finish(error, value) {
      if (settled) return;
      settled = true;clearTimeout(timer);handlers.clear();
      // Delete only the private registration created by this one call.
      if (service.clientID.get(cid)?.sid === sid) {
        service.clientID.delete(cid);
        if (Object.hasOwn(service.offlineCache || {}, cid)) delete service.offlineCache[cid];
      }
      if (error) reject(error);else resolve(value);
    }
    const timer = setTimeout(() => finish(Error('Original SDK read timed out; no replay')), timeout);
    const socket = {
      id: sid, handshake: {query: {cid, windowHref: 'heyi-local-memory-read'}},
      on(name, callback) {if (!settled) handlers.set(name, callback);return this;},
      emit(name, reply) {
        if (name !== 'message' || reply?.busId !== busId || reply?.act !== method) return;
        const body = reply.data;
        if (body?.code !== 0 || !Object.hasOwn(body, 'data')) {
          finish(Error('Original SDK read was rejected'));return;
        }
        if (method === '_sdk_getLoginStatus' && typeof body.data !== 'boolean') {
          finish(Error('Original login status is not a boolean'));return;
        }
        finish(null, body.data);
      },
    };
    try {
      // Original handleEvent registers a client and binds its real SDK handler.
      // The private namespace suppresses connection announcements. No socket
      // is opened and no subscription, API call, login or candidate send occurs.
      service.handleEvent('main', socket, {emit() {}});
      const dispatch = handlers.get('message');
      if (typeof dispatch !== 'function') throw Error('Original SDK handler is unavailable');
      dispatch({busId, actType: 'message', act: method, data: {}});
    } catch {
      finish(Error('Original SDK read dispatch failed'));
    }
  });
}
module.exports = {readOriginalSDK, waitForOriginalDispatcher};
