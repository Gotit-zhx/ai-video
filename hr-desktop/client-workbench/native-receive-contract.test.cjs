const test = require('node:test');
const assert = require('node:assert/strict');
const {install} = require('./native-receive-contract.cjs');

function fixture(t, {loggedIn = false, throws = false, clientMid = false} = {}) {
  globalThis.$socket = {clientID: new Map(), handleEvent(_name, socket) {
    this.clientID.set(socket.handshake.query.cid, {sid: socket.id});
    socket.on('message', request => socket.emit('message', {...request, data: {code: 0, data: loggedIn}}));
  }};
  t.after(() => {delete globalThis.$socket;});
  let dispatchCalls = 0, receiverCalls = 0;
  class Receiver {receiveMessage() {receiverCalls++;}}
  const receiver = new Receiver();
  class Sender {
    constructor() {throw Error('Original sender constructor must not run');}
    dispatchMessage(protocol) {
      dispatchCalls++;
      assert.equal(Object.hasOwn(this, 'userId'), true);
      assert.equal(this.userId, 101);
      if (throws) throw Error('Offline dispatch failure');
      receiver.receiveMessage(protocol.messages.map(message => ({
        fromId: message.from.uid, toId: message.to.uid, mid: message.mid, time: message.time,
        isSelf: message.from.uid === this.userId, status: 1, newSubmit: false,
        type: 'text', text: message.body.text, ...(clientMid ? {clientMid: message.cmid} : {}),
      })));
    }
  }
  const bindings = {receiver, sender: Object.create(Sender.prototype)};
  Object.defineProperty(bindings, 'identity', {get() {throw Error('Current identity must not be read');}});
  return {bindings, dispatchCalls: () => dispatchCalls, receiverCalls: () => receiverCalls};
}
test('normalizer check requires explicit anonymous isolation', async () => {
  await assert.rejects(install({}), /Anonymous isolated/);
});
for (const clientMid of [false, true]) {
  test(`observes original normalization with clientMid ${clientMid ? 'present' : 'missing'} and restores receiver`, async t => {
    const f = fixture(t, {clientMid});
    const report = await install(f.bindings, {anonymousIsolated: true});
    assert.equal(report.ok, true);
    assert.equal(report.clientMidPreserved, clientMid);
    assert.equal(f.dispatchCalls(), 1);assert.equal(f.receiverCalls(), 0);
    assert.equal(Object.hasOwn(f.bindings.receiver, 'receiveMessage'), false);
    f.bindings.receiver.receiveMessage([]);
    assert.equal(f.receiverCalls(), 1);
  });
}
test('normalizer failure restores receiver without replay', async t => {
  const f = fixture(t, {throws: true});
  await assert.rejects(install(f.bindings, {anonymousIsolated: true}));
  assert.equal(f.dispatchCalls(), 1);assert.equal(f.receiverCalls(), 0);
  assert.equal(Object.hasOwn(f.bindings.receiver, 'receiveMessage'), false);
});
test('signed-in process is left untouched', async t => {
  const f = fixture(t, {loggedIn: true});
  assert.equal((await install(f.bindings, {anonymousIsolated: true})).ok, false);
  assert.equal(f.dispatchCalls(), 0);assert.equal(f.receiverCalls(), 0);
});
