const test = require('node:test');
const assert = require('node:assert/strict');
const {installMessageObservers} = require('./native-messages.cjs');

function fixture(t, overrides = {}) {
  let account = '101', sends = 0, receives = 0;
  const observed = [], forwarded = [], originalMessages = [];
  const receiver = {receiveMessage(messages) {receives++;originalMessages.push(messages);return 'original-result';}};
  const sender = {send(input) {sends++;return input;}, dispatchMessage(protocol) {
    return receiver.receiveMessage(protocol.messages.map(raw => ({
      isSelf: true, fromId: raw.from.uid, toId: raw.to.uid, mid: raw.mid, time: raw.time,
      type: 'text', text: raw.body.text, status: 1,
    })));
  }};
  const observer = installMessageObservers({sender, receiver, account: () => account,
    observeSend: packet => observed.push(packet), receive: messages => forwarded.push(messages), ...overrides});
  t.after(() => observer.close());
  const packet = () => ({type: 1, messages: [{from: {uid: '101'}, to: {uid: '202'},
    mid: '303', cmid: '404', time: 1700000000000, body: {type: 1, text: 'offline'}}]});
  return {sender, receiver, observer, observed, forwarded, originalMessages, packet,
    account: value => {account = value;}, sends: () => sends, receives: () => receives};
}
test('links only the addon copy; original receiver arguments and return value stay unchanged', t => {
  const f = fixture(t), raw = f.packet(), before = structuredClone(raw);
  assert.equal(f.sender.dispatchMessage(raw), 'original-result');
  assert.deepEqual(raw, before);
  assert.equal(f.receives(), 1);
  assert.equal(f.originalMessages[0][0].clientMid, undefined);
  assert.equal(f.forwarded[0][0].clientMid, '404');
  assert.notEqual(f.forwarded[0], f.originalMessages[0]);
  assert.notEqual(f.forwarded[0][0], f.originalMessages[0][0]);
});
test('correlation is discarded outside the exact synchronous dispatch stack', t => {
  const f = fixture(t);
  f.sender.dispatchMessage(f.packet());
  f.receiver.receiveMessage([{...f.forwarded[0][0]}]);
  assert.equal(f.forwarded[1][0].clientMid, undefined);
  f.account('');
  f.sender.dispatchMessage(f.packet());
  assert.equal(f.forwarded.length, 2);
});
test('duplicate packets cannot supply an ambiguous clientMid', t => {
  const f = fixture(t), raw = f.packet();
  raw.messages.push({...raw.messages[0], cmid: '405'});
  f.sender.dispatchMessage(raw);
  assert.equal(f.forwarded[0].every(message => message.clientMid === undefined), true);
});
test('receiver mutations, account changes and wrong correlation fields do not confirm', t => {
  for (const mutate of [
    message => {message.fromId = '999';}, message => {message.toId = '999';},
    message => {message.mid = '999';}, message => {message.text = 'other';},
    message => {message.time++;}, message => {message.clientMid = '999';},
  ]) {
    const f = fixture(t);
    const original = f.receiver.receiveMessage;
    f.observer.close();
    f.receiver.receiveMessage = messages => {mutate(messages[0]);return original(messages);};
    const observer = installMessageObservers({sender: f.sender, receiver: f.receiver,
      account: () => '101', observeSend() {}, receive: messages => f.forwarded.push(messages)});
    t.after(() => observer.close());
    f.sender.dispatchMessage(f.packet());
    assert.equal(f.forwarded[0][0].clientMid, undefined);
  }
  let account = '101', deliveries = 0;
  const receiver = {receiveMessage() {account = '999';}};
  const sender = {send() {}, dispatchMessage() {return receiver.receiveMessage([]);}};
  const observer = installMessageObservers({sender, receiver,
    account: () => account, observeSend() {}, receive() {deliveries++;}});
  t.after(() => observer.close());
  sender.dispatchMessage({type: 1, messages: []});
  assert.equal(deliveries, 0);
});
test('outgoing byte observation never changes the original send or its exact return value', t => {
  const f = fixture(t);
  const bytes = Buffer.from([8, 1, 26, 24, 10, 2, 8, 101, 18, 3, 8, 202, 1,
    32, 175, 2, 50, 7, 8, 1, 26, 3, 97, 98, 99, 88, 148, 3]);
  assert.equal(f.sender.send(bytes), bytes);
  assert.equal(f.sends(), 1);assert.equal(f.observed.length, 1);
  assert.equal(f.observed[0].messages[0].cmid, '404');
  const malformed = Buffer.from([255]);
  assert.equal(f.sender.send(malformed), malformed);
  assert.equal(f.sends(), 2);assert.equal(f.observed.length, 1);
});
test('observer errors never suppress or repeat original native methods', t => {
  const f = fixture(t, {receive() {throw Error('addon receiver');}, observeSend() {throw Error('addon send');}});
  assert.equal(f.sender.dispatchMessage(f.packet()), 'original-result');
  const bytes = Buffer.from([8, 2]);
  assert.equal(f.sender.send(bytes), bytes);
  assert.equal(f.receives(), 1);assert.equal(f.sends(), 1);
});
test('original exceptions propagate once; receiver failures do not call addon delivery', t => {
  const f = fixture(t);
  f.observer.close();
  let calls = 0;
  f.receiver.receiveMessage = () => {calls++;throw Error('original-failure');};
  const observer = installMessageObservers({sender: f.sender, receiver: f.receiver,
    account: () => '101', observeSend() {}, receive() {assert.fail('must not run');}});
  t.after(() => observer.close());
  assert.throws(() => f.sender.dispatchMessage(f.packet()), /original-failure/);
  assert.equal(calls, 1);
});
test('duplicate installation is rejected and detach restores descriptors without replacing another hook', t => {
  const f = fixture(t), original = Object.getOwnPropertyDescriptor(f.sender, 'send');
  assert.throws(() => installMessageObservers({sender: f.sender, receiver: f.receiver,
    account() {}, observeSend() {}, receive() {}}), /Unique/);
  const replacement = () => 'external';
  f.sender.send = replacement;
  f.observer.close();
  assert.equal(f.sender.send, replacement);
  assert.notEqual(original.value, replacement);
  assert.equal(f.sender.dispatchMessage(f.packet()), 'original-result');
  assert.equal(f.forwarded.length, 0);
});
test('partial installation rolls back and old detach cannot remove a newer installation', t => {
  const sender = {send() {}, dispatchMessage() {}}, receiver = {};
  const originalSend = sender.send;
  Object.defineProperty(receiver, 'receiveMessage', {configurable: false, value() {}});
  assert.throws(() => installMessageObservers({sender, receiver,
    account() {}, observeSend() {}, receive() {}}), /cannot be attached/);
  assert.equal(sender.send, originalSend);
  const f = fixture(t);
  f.observer.close();
  const observer = installMessageObservers({sender: f.sender, receiver: f.receiver,
    account: () => '101', observeSend() {}, receive() {}});
  t.after(() => observer.close());
  f.observer.close();
  assert.throws(() => installMessageObservers({sender: f.sender, receiver: f.receiver,
    account() {}, observeSend() {}, receive() {}}), /Unique/);
});
