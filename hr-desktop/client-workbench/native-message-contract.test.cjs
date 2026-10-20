const test = require('node:test');
const assert = require('node:assert/strict');
const {install} = require('./native-message-contract.cjs');

function scalar(field, value) {
  value = BigInt(value);
  const bytes = [field * 8];
  do {let byte = Number(value & 127n);value >>= 7n;if (value) byte |= 128;bytes.push(byte);} while (value);
  return Buffer.from(bytes);
}
function field(number, value) {
  const length = scalar(1, value.length).subarray(1);
  return Buffer.concat([Buffer.from([number * 8 + 2]), length, value]);
}
function setup(t, {loggedIn = false, writer = false, wrongClientMid = false} = {}) {
  let sdkReads = 0, builds = 0;
  globalThis.$socket = {clientID: new Map(), handleEvent(_name, socket) {
    this.clientID.set(socket.handshake.query.cid, {sid: socket.id});
    socket.on('message', request => {
      assert.equal(request.act, '_sdk_getLoginStatus');sdkReads++;
      socket.emit('message', {...request, data: {code: 0, data: loggedIn}});
    });
  }};
  t.after(() => {delete globalThis.$socket;});
  class Factory {
    constructor() {throw Error('Original constructor must not be used');}
    getProtoMsg(input) {
      builds++;
      assert.equal(Object.getPrototypeOf(this), Factory.prototype);
      assert.equal(Object.hasOwn(this, 'userInfo'), true);
      const packet = Buffer.concat([scalar(1, 1), field(3, Buffer.concat([
        field(1, scalar(1, this.userInfo.userId)), field(2, scalar(1, input.options.uid)),
        scalar(4, input.options.clientMid), scalar(5, this.nowTime),
        field(6, Buffer.concat([scalar(1, 1), field(3, Buffer.from(input.content))])),
        scalar(11, wrongClientMid ? '404' : input.options.clientMid),
      ]))]);
      return writer ? {finish: () => packet} : packet;
    }
  }
  const bindings = {messageFactoryPrototype: Factory.prototype};
  for (const name of ['identity', 'authentication', 'sender', 'receiver', 'sdk']) {
    Object.defineProperty(bindings, name, {get() {throw Error('Business reference must not be accessed');}});
  }
  return {bindings, reads: () => sdkReads, builds: () => builds};
}

test('requires an explicit offline anonymous process before any read', async () => {
  await assert.rejects(install({}), /Anonymous isolated/);
});
for (const writer of [false, true]) {
  test(`checks ${writer ? 'writer' : 'byte'} output on an owned identity without calling sender`, async t => {
    const fixture = setup(t, {writer});
    const report = await install(fixture.bindings, {anonymousIsolated: true});
    assert.equal(report.ok, true);
    assert.equal(report.packetShape, writer ? 'writer' : 'byte-view');
    assert.equal(report.candidateMessagesSent, 0);
    assert.equal(report.originalFactoryCalls, 1);
    assert.equal(fixture.reads(), 1);
    assert.equal(fixture.builds(), 1);
    assert.equal(globalThis.$socket.clientID.size, 0);
    assert.equal(JSON.stringify(report).includes('303'), false);
  });
}
test('a signed-in process does not invoke the encoder', async t => {
  const fixture = setup(t, {loggedIn: true});
  const report = await install(fixture.bindings, {anonymousIsolated: true});
  assert.equal(report.ok, false);
  assert.equal(fixture.builds(), 0);
});
test('incorrect original correlation encoding remains a failed check', async t => {
  const fixture = setup(t, {wrongClientMid: true});
  const report = await install(fixture.bindings, {anonymousIsolated: true});
  assert.equal(report.ok, false);
  assert.equal(fixture.builds(), 1);
});
