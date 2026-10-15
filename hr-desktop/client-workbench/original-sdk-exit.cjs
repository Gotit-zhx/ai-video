// Disposable Windows probe only. Request the same fixed exit action used by
// the original renderer, rather than bypassing its shutdown path with app.quit.
const {randomUUID} = require('node:crypto');

function requestOriginalExit(service, state, changed = () => {}) {
  if (!service || typeof service.handleEvent !== 'function' ||
      typeof service.clientID?.get !== 'function' || typeof service.clientID?.delete !== 'function') {
    throw Error('Original shutdown dispatcher is unavailable');
  }
  const cid = 'heyi-shutdown-' + randomUUID(), sid = 'heyi-local-' + randomUUID();
  const busId = randomUUID(), handlers = new Map();
  let timer, cleaned = false;
  function cleanup() {
    if (cleaned) return;
    cleaned = true;clearTimeout(timer);handlers.clear();
    if (service.clientID.get(cid)?.sid === sid) {
      service.clientID.delete(cid);
      if (Object.hasOwn(service.offlineCache || {}, cid)) delete service.offlineCache[cid];
    }
    state.registrationReleased = true;changed();
  }
  const socket = {
    id: sid, handshake: {query: {cid, windowHref: 'heyi-local-shutdown-probe'}},
    on(name, callback) {handlers.set(name, callback);return this;},
    emit(name, reply) {
      if (name !== 'message' || reply?.busId !== busId || reply?.act !== '_sdk_exit') return;
      state.replyReceived = true;
      state.replyCode = Number.isInteger(reply?.data?.code) ? reply.data.code : null;
      changed();cleanup();
    },
  };
  try {
    service.handleEvent('main', socket, {emit() {}});
    const dispatch = handlers.get('message');
    if (typeof dispatch !== 'function') throw Error('Original shutdown handler is unavailable');
    state.sdkShutdownRequests = 1;state.replyReceived = false;
    changed();
    dispatch({busId, actType: 'message', act: '_sdk_exit', data: {}});
    state.dispatchReturned = true;changed();
    // Exit can terminate the process before any response. The parent must
    // verify the actual process outcome; this timer never forces an exit.
    if (!cleaned) timer = setTimeout(cleanup, 5000).unref();
    return cleanup;
  } catch {
    cleanup();
    throw Error('Original shutdown request failed');
  }
}

module.exports = {requestOriginalExit};
