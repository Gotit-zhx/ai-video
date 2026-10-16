const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {installActivityPage, drainActivityPage, closeActivityPage} = require('./native-activity.cjs');

function fixture(t) {
  let account = '101', subscribed, unsubscribed = 0;
  const calls = [], stored = {sentinel: true};
  const store = {add(...args) {calls.push({owner: this, args});return stored;}};
  const original = store.add;
  const root = {user: {get: () => account}, messageStore: store,
    chat: {lastBossUnreadMsg$: {subscribe(callback) {
      subscribed = callback;
      callback({}); // Original BehaviorSubject replays its current value.
      return {unsubscribe() {unsubscribed++;}};
    }}}};
  const context = vm.createContext({window: {iBossRoot: root}, setTimeout, clearTimeout});
  const run = fn => vm.runInContext(`(${fn.toString()})('fixture-token')`, context);
  t.after(() => run(closeActivityPage));
  const incoming = (mid = '303') => ({from: {uid: '202'}, to: {uid: '101'}, mid,
    time: Date.now(), body: {text: 'Private text that must not leave the frame', type: 1}});
  return {run, store, original, stored, calls, root, context, incoming,
    setAccount: value => {account = value;}, unread: row => subscribed(row),
    unsubscribed: () => unsubscribed};
}
test('current page incoming activity reaches the waiter without changing native calls or exporting content', async t => {
  const f = fixture(t);
  assert.equal(f.run(installActivityPage).ok, true);
  const waiting = f.run(drainActivityPage), incoming = f.incoming();
  assert.equal(f.store.add([incoming], 'extra'), f.stored);
  assert.equal(f.calls.length, 1);assert.equal(f.calls[0].owner, f.store);
  assert.equal(f.calls[0].args[0][0], incoming);
  const result = await waiting;
  assert.equal(result.ok, true);assert.equal(result.hints.length, 1);
  assert.deepEqual(Object.keys(result.hints[0]).sort(), ['fromId', 'isSelf', 'mid', 'time', 'toId']);
  assert.equal(result.hints[0].fromId, '202');assert.equal(result.hints[0].isSelf, false);
  f.run(closeActivityPage);
  assert.equal(f.store.add, f.original);assert.equal(f.unsubscribed(), 1);
});
test('page store and unread events deduplicate while preserving different candidates and messages', async t => {
  const f = fixture(t);f.run(installActivityPage);
  f.store.add([f.incoming('301'), f.incoming('302')]);
  f.unread({fromId: '202', toId: '101', mid: '301', time: Date.now(), isSelf: false});
  f.store.add({...f.incoming('301'), from: {uid: '203'}});
  const result = await f.run(drainActivityPage);
  assert.equal(result.hints.length, 3);
  assert.deepEqual(Array.from(result.hints, h => h.fromId+':'+h.mid), ['202:301','202:302','203:301']);
});
test('outgoing, provisional, unsafe identifiers and other accounts never produce incoming hints', async t => {
  const f = fixture(t);f.run(installActivityPage);
  f.store.add([
    {...f.incoming(), from: {uid: '101'}, to: {uid: '202'}},
    {...f.incoming(), to: {uid: '999'}},
    {...f.incoming(), isSelf: true}, {...f.incoming(), newSubmit: true},
    {...f.incoming(), typeSource: 'newSubmit'}, {...f.incoming(), mid: 1e20},
    {...f.incoming(), time: Date.now()+3600000}, {...f.incoming(), time: 'bad'},
    f.incoming('999'),
  ]);
  const result = await f.run(drainActivityPage);
  assert.deepEqual(Array.from(result.hints, h => h.mid), ['999']);
});
test('account change, root replacement and close cancel pending observation and restore native ownership', async t => {
  for (const change of ['account', 'root', 'close']) {
    const f = fixture(t);f.run(installActivityPage);
    const waiting = f.run(drainActivityPage);
    if (change === 'account') {f.setAccount('999');await f.run(drainActivityPage);}
    else if (change === 'root') {f.context.window.iBossRoot = {...f.root};await f.run(drainActivityPage);}
    else f.run(closeActivityPage);
    assert.equal((await waiting).ok, false);
    assert.equal(f.store.add, f.original);
    assert.equal(f.unsubscribed(), 1);
  }
});
test('observer installs once, preserves a later owner, and never swallows an original store failure', t => {
  const f = fixture(t);f.run(installActivityPage);
  const hook = f.store.add;
  f.run(installActivityPage);assert.equal(f.store.add, hook);
  const later = () => {};
  f.store.add = later;f.run(closeActivityPage);assert.equal(f.store.add, later);
  const g = fixture(t);
  g.store.add = () => {throw Error('native failure');};
  g.run(installActivityPage);
  assert.throws(() => g.store.add(g.incoming()), /native failure/);
});
test('already loaded recent incoming rows wake collection without replaying old or outgoing rows', async t => {
  const f = fixture(t);
  let sample, update, removed = 0;
  const row = (friend, mid, lastIsSelf, lastTS = Date.now()) =>
    ({friendId: friend, friendSource: 0, lastMsgId: mid, lastIsSelf, lastTS});
  const list = {$options: {name: 'geek-list'},
    list$: [row('202', '601', false), row('203', '602', true), row('204', '603', false, Date.now()-7200000),
      {...row('205', '605', false, Date.now()-7200000), lastText: '对方想发送附件简历给您，您是否同意'}],
    $watch(read, callback) {
      sample = read;update = callback;callback(read());
      return () => {removed++;};
    }};
  f.context.document = {querySelector: () => ({__vue__: list})};
  f.run(installActivityPage);
  let result = await f.run(drainActivityPage);
  assert.deepEqual(Array.from(result.hints, h => [h.fromId, h.mid]), [['202', '601'], ['205', '605']]);
  assert.equal(result.hints[1].resumePending, true);
  update(sample());
  list.list$.push(row('202', '604', false));update(sample());
  result = await f.run(drainActivityPage);
  assert.deepEqual(Array.from(result.hints, h => h.mid), ['604']);
  f.run(closeActivityPage);assert.equal(removed, 1);
});
test('an already rendered pending resume offer survives the one-hour activity window without a network read', async t => {
  const f = fixture(t);
  const pending = {fromId: '202', toId: '101', isSelf: false, mid: '701',
    time: Date.now()-7200000, type: 'dialog', dialog: {type: 2, operated: false}};
  const rows = [pending, {...pending, mid: '702', dialog: {type: 2, operated: true}},
    {...pending, mid: '703', fromId: '101', toId: '202', isSelf: true}];
  f.context.document = {querySelector: () => null,
    querySelectorAll: () => rows.map(message => ({__vue__: {$props: {message}}}))};
  f.run(installActivityPage);
  const result = await f.run(drainActivityPage);
  assert.equal(result.hints.length, 1);
  assert.equal(result.hints[0].mid, '701');
  assert.equal(result.hints[0].resumePending, true);
  assert.equal(f.calls.length, 0, 'reading rendered messages never invokes native storage or network');
});
