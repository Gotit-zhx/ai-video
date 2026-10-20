const {test} = require('node:test');
const assert = require('node:assert/strict');
const {requestOriginalExit} = require('./original-sdk-exit.cjs');

test('dispatches only the original exit action once and preserves unrelated clients', () => {
  const calls = [];
  const service = {
    clientID: new Map([['existing', {sid:'keep'}]]),
    offlineCache: {existing:['keep']},
    handleEvent(_name, socket) {
      const cid = socket.handshake.query.cid;
      this.clientID.set(cid, {sid:socket.id});
      this.offlineCache[cid] = [];
      socket.on('message', input => {
        calls.push(input);
        socket.emit('message', {...input, busId:'wrong', data:{code:0}});
        socket.emit('message', {...input, data:{code:0}});
      });
    },
  };
  const state = {};
  const cleanup = requestOriginalExit(service, state);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].act, '_sdk_exit');
  assert.equal(calls[0].actType, 'message');
  assert.deepEqual(calls[0].data, {});
  assert.equal(state.replyReceived, true);
  assert.equal(state.replyCode, 0);
  assert.equal(state.sdkShutdownRequests, 1);
  assert.deepEqual([...service.clientID], [['existing', {sid:'keep'}]]);
  assert.deepEqual(service.offlineCache, {existing:['keep']});
  cleanup();
  assert.equal(calls.length, 1);
});

test('missing reply is not fabricated as success and cleanup never repeats exit', () => {
  const calls = [], state = {};
  const service = {
    clientID: new Map(), offlineCache: {},
    handleEvent(_name, socket) {
      this.clientID.set(socket.handshake.query.cid, {sid:socket.id});
      socket.on('message', input => calls.push(input));
    },
  };
  const cleanup = requestOriginalExit(service, state);
  assert.equal(state.replyReceived, false);
  assert.equal(state.replyCode, undefined);
  assert.equal(calls.length, 1);
  cleanup();cleanup();
  assert.equal(calls.length, 1);
  assert.equal(service.clientID.size, 0);
});

test('registration failure preserves a replacement registration and does not dispatch', () => {
  const service = {
    clientID:new Map(),
    handleEvent(_name, socket) {
      this.clientID.set(socket.handshake.query.cid, {sid:'replacement'});
      throw Error('fixture');
    },
  };
  const state = {};
  assert.throws(() => requestOriginalExit(service, state), /request failed/);
  assert.equal(state.sdkShutdownRequests, undefined);
  assert.equal(service.clientID.size, 1);
});
