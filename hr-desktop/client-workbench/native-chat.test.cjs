const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {prepareChatPage, commitTextPage, waitChatPage, cancelChatPage, NativeChat} = require('./native-chat.cjs');

function fixture(t, options = {}) {
  const calls = [], timers = new Map();
  const args = {token: 'fixture-token', account: '101', friendId: '202', job: 'job-one',
    clientMid: '300', content: '已约好的面试信息', friend: {friendId: '202', encryptJobId: 'job-one'}};
  const conversation = {friendId: '202', encryptJobId: 'job-one',
    toPositionId: 'job-one', securityId: 'fixture-security'};
  const service = {bossId: '101', conversation, get: key => conversation[key],
    sendMessage(content, type, opts) {calls.push({content, type, opts});},
    onMessageDelivered() {return 'original-result';}};
  const original = service.onMessageDelivered, originalSend = service.sendMessage;
  const editor = {conversation$: conversation, $refs: {editbox: {
    textContent: options.draft || '', querySelector: () => null}},
    $root: {$children: [{$options: {name: 'dialog-nofit'}, source: 'conversation',
      getConversationService: () => service}]}};
  const context = vm.createContext({window: {iBossRoot: {user: {get: () => options.account || '101'}}},
    document: {querySelectorAll() {return [];}, querySelector(selector) {
      return selector === '.boss-chat-editor-input' ? {parentElement: {__vue__: editor}} :
        {__vue__: {$options: {name: 'geek-list'}, geekClick() {calls.push('select');}}};
    }}, setTimeout(fn, ms) {const id = {};timers.set(id, {fn, ms});return id;},
    clearTimeout(id) {timers.delete(id);}});
  const run = (fn, input = args) => vm.runInContext(`(${fn.toString()})(${JSON.stringify(input)})`, context);
  const delivered = extra => service.onMessageDelivered([{fromId: '101', toId: '202',
    mid: '400', cmid: '300', type: 'text', text: args.content, ...extra}]);
  t.after(() => run(cancelChatPage, args.token));
  return {args, service, original, originalSend, editor, conversation, calls, timers, context, run, delivered};
}

test('text uses the active conversation service once and only its real delivered callback confirms', async t => {
  const f = fixture(t);
  assert.equal((await f.run(prepareChatPage)).ok, true);
  assert.equal(f.run(commitTextPage).ok, true);
  assert.equal(f.calls.length, 1);
  assert.equal(f.calls[0].opts.clientMid, '300');
  let confirmed = false;
  const receipt = f.run(waitChatPage).then(result => {confirmed = true;return result;});
  await Promise.resolve();
  assert.equal(confirmed, false, 'the sender returning does not acknowledge a message');
  assert.equal(f.delivered(), 'original-result');
  const result = await receipt;
  assert.equal(result.receipt.mid, '400');
  assert.equal(result.receipt.clientMid, '300');
  assert.equal(result.receipt.transport, 'native-chat-delivery');
  assert.equal(f.run(commitTextPage).ok, false, 'a click cannot dispatch twice');
  f.run(cancelChatPage, f.args.token);
  assert.equal(f.service.onMessageDelivered, f.original);
  assert.equal(f.timers.size, 0);
});

test('historical, other account, other candidate, other clientMid and provisional messages cannot acknowledge', async t => {
  const f = fixture(t);
  await f.run(prepareChatPage);
  f.delivered(); // Arrival before commit cannot confirm.
  f.run(commitTextPage);
  let confirmed = false;
  const receipt = f.run(waitChatPage).then(result => {confirmed = true;return result;});
  for (const extra of [{fromId: '999'}, {toId: '999'}, {cmid: '299'},
    {newSubmit: true}, {mid: '300'}, {text: '另一条消息'}]) f.delivered(extra);
  await Promise.resolve();
  assert.equal(confirmed, false);
  f.delivered();
  assert.equal((await receipt).ok, true);
});

test('account, job, draft and changed conversation are checked before any text write', async t => {
  for (const opts of [{account: '999'}, {draft: '用户正在输入的内容'}, {job: 'other'}]) {
    const f = fixture(t, opts);
    if (opts.job) f.conversation.encryptJobId = opts.job;
    const result = await f.run(prepareChatPage);
    assert.equal(result.ok, false);
    assert.equal(f.calls.length, 0);
  }
  const f = fixture(t);
  await f.run(prepareChatPage);
  f.conversation.securityId = 'changed';
  assert.equal(f.run(commitTextPage).ok, false);
  assert.equal(f.calls.length, 0);
});

test('blocked native sender and timeout stay unsuccessful without retry', async t => {
  for (const blocked of [true, false]) {
    const f = fixture(t);
    await f.run(prepareChatPage);
    f.run(commitTextPage);
    const receipt = f.run(waitChatPage);
    if (blocked) f.calls[0].opts.onSendBlock();
    else [...f.timers.values()].find(timer => timer.ms === 9500).fn();
    assert.equal((await receipt).code, blocked ? 'NATIVE_SEND_BLOCKED' : 'SEND_UNCONFIRMED');
    assert.equal(f.calls.length, 1);
  }
});

test('cancellation releases listeners and prevents a prepared send', async t => {
  const f = fixture(t);
  await f.run(prepareChatPage);
  const receipt = f.run(waitChatPage);
  f.run(cancelChatPage, f.args.token);
  assert.equal((await receipt).code, 'REQUESTS_PAUSED');
  assert.equal(f.run(commitTextPage).ok, false);
  assert.equal(f.service.onMessageDelivered, f.original);
  assert.equal(f.calls.length, 0);
});

test('resume request uses the original component sender and the same exact receipt', async t => {
  const f = fixture(t);
  f.args.resume = true;
  await f.run(prepareChatPage);
  f.service.sendMessage(f.args.content, 'text', {bgSource: 7});
  assert.equal(f.calls[0].opts.bgSource, 7);
  assert.equal(f.calls[0].opts.clientMid, f.args.clientMid);
  const receipt = f.run(waitChatPage);
  f.delivered();
  assert.equal((await receipt).ok, true);
  assert.throws(() => f.service.sendMessage(f.args.content, 'text', {}), /未重复发送/);
  f.run(cancelChatPage, f.args.token);
  assert.equal(f.service.sendMessage, f.originalSend);
});

test('native wrapper rejects missing job and serializes text with resume actions', async () => {
  const native = new NativeChat({nativeWindow: () => null, allContents: () => []});
  await assert.rejects(native.perform({friend: {uid: '202'}}), {code: 'JOB_CONVERSATION_MISMATCH'});
  native.busy = true;
  await assert.rejects(native.perform({}), {code: 'ACCOUNT_BUSY'});
});
