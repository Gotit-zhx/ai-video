// Anonymous probe only. Enter the original registration function once so its
// public debugger scopeChain can be observed. Never dispatch a message or SDK.
const {randomUUID} = require('node:crypto');

function registerForInspection(service) {
  if (!service || typeof service.handleEvent !== 'function' ||
      typeof service.clientID?.get !== 'function' || typeof service.clientID?.delete !== 'function') {
    throw Error('Original registration service is unavailable');
  }
  const cid = 'heyi-scope-registration-' + randomUUID(), sid = 'heyi-local-' + randomUUID();
  let messageHandlerBound = false;
  const socket = {
    id: sid, handshake: {query: {cid, windowHref: 'heyi-local-scope-inspection'}},
    on(name, callback) {
      if (name === 'message' && typeof callback === 'function') messageHandlerBound = true;
      return this;
    },
    emit() {},
  };
  try {
    service.handleEvent('main', socket, {emit() {}});
    return {messageHandlerBound};
  } finally {
    if (service.clientID.get(cid)?.sid === sid) {
      service.clientID.delete(cid);
      if (Object.hasOwn(service.offlineCache || {}, cid)) delete service.offlineCache[cid];
    }
  }
}

module.exports = {registerForInspection};
