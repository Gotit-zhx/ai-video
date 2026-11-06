const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readOriginalSDK, waitForOriginalDispatcher} = require('./original-sdk-read.cjs');

function fixture(reply) {
  const service = {
    clientID: new Map([['unrelated-client', {sid: 'unrelated-socket'}]]),
    offlineCache: {'unrelated-client': ['preserved']}, calls: [],
    handleEvent(namespace, socket, target) {
      assert.equal(namespace, 'main');
      const cid = socket.handshake.query.cid;
      this.clientID.set(cid, {sid: socket.id});
      this.offlineCache[cid] = [];
      target.emit('broadcast', 'private fixture connection');
      socket.on('message', input => {
        this.calls.push(input);
        reply?.(input, socket, this, cid);
      });
    },
  };
  return service;
}
function clean(service) {
  assert.deepEqual([...service.clientID], [['unrelated-client', {sid: 'unrelated-socket'}]]);
  assert.deepEqual(service.offlineCache, {'unrelated-client': ['preserved']});
}
test('memory getter uses the original handler once and cleans up its registration', async () => {
  const service = fixture((input, socket) => {
    socket.emit('message', {...input, data: {code: 0, data: false}});
    socket.emit('message', {...input, data: {code: 0, data: true}});
  });
  assert.equal(await readOriginalSDK(service, '_sdk_getLoginStatus'), false);
  assert.equal(service.calls.length, 1);
  assert.equal(service.calls[0].actType, 'message');
  clean(service);
});
test('cannot call login, storage, arbitrary HTTP or candidate communication', async () => {
  const service = fixture();
  for (const method of ['_sdk_login', '_sdk_getUserInfo', '_sdk_getStorage',
    '_sdk_chatSocket_sendTextMessage', 'zpuserGetUserInfo', '__proto__']) {
    await assert.rejects(readOriginalSDK(service, method), /not permitted/);
  }
  assert.equal(service.calls.length, 0);clean(service);
});
test('unrelated messages cannot satisfy a read and timeout never repeats it', async () => {
  const service = fixture((input, socket) => {
    socket.emit('message', {...input, busId: 'wrong', data: {code: 0, data: true}});
    socket.emit('message', {...input, act: '_sdk_login', data: {code: 0, data: true}});
  });
  await assert.rejects(readOriginalSDK(service, '_sdk_getLoginStatus', 20), /timed out; no replay/);
  assert.equal(service.calls.length, 1);clean(service);
});
test('rejections and malformed success data fail instead of reporting logged in', async () => {
  for (const data of [{code: -1, msg: 'fixture'}, {code: 0}, {code: 0, data: 'false'}]) {
    const service = fixture((input, socket) => socket.emit('message', {...input, data}));
    await assert.rejects(readOriginalSDK(service, '_sdk_getLoginStatus'), /rejected|not a boolean/);
    clean(service);
  }
});
test('registration errors clean up and a different registration is never deleted', async () => {
  const service = fixture();
  service.handleEvent = function(_name, socket) {
    const cid = socket.handshake.query.cid;
    this.clientID.set(cid, {sid: 'replacement'});
    throw Error('fixture');
  };
  await assert.rejects(readOriginalSDK(service, '_sdk_getLoginStatus'), /dispatch failed/);
  assert.equal([...service.clientID.values()].filter(v => v.sid === 'replacement').length, 1);
  assert.equal(service.clientID.get('unrelated-client').sid, 'unrelated-socket');
});
test('a late original service is awaited before one SDK call, without replay', async t => {
  const service = fixture((input, socket) =>
    socket.emit('message', {...input, data: {code: 0, data: false}}));
  let available;
  const timer = setTimeout(() => {available = service;}, 30);
  t.after(() => clearTimeout(timer));
  const found = await waitForOriginalDispatcher(() => available, 1000, 5);
  assert.equal(found, service);
  assert.equal(service.calls.length, 0);
  assert.equal(await readOriginalSDK(found, '_sdk_getLoginStatus'), false);
  assert.equal(service.calls.length, 1);clean(service);
});
test('missing or partial original service expires without invoking any SDK', async () => {
  let calls = 0;
  await assert.rejects(waitForOriginalDispatcher(() => ({
    handleEvent() {calls++;}, clientID: {},
  }), 20, 5), /did not become ready/);
  assert.equal(calls, 0);
});
