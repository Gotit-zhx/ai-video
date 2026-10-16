const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const {NativeResume, isChatURL, preparePage} = require('./native-resume.cjs');
const {NativeRPC, RESUME_REQUEST_TEXT} = require('./native-rpc.cjs');
const {decodeNativeProtocol} = require('./native-protocol.cjs');

const friend = {friendId: '202', encryptJobId: 'job-a', securityId: 'fixture'};

function pageFixture({job = 'job-a'} = {}) {
  const calls = {select: 0, invoke: 0};
  const conversation = {friendId: '202', encryptJobId: job, securityId: 'fixture'};
  const visible = {getClientRects: () => [{}], getAttribute: () => null};
  const component = {$options: {name: 'ExchangeResume'}, type: 4, $el: visible,
    handleExChange() {calls.invoke++;}};
  const editor = {conversation$: conversation, $children: [component]};
  const list = {$options: {name: 'geek-list'}, geekClick: () => calls.select++};
  const context = vm.createContext({window: {}, Date, setTimeout,
    document: {
      querySelector(selector) {
        return selector === '.chat-user' ? {__vue__: list} : {parentElement: {__vue__: editor}};
      },
    }});
  const host = {};
  const frame = {url: 'https://www.zhipin.com/web/client/index',
    executeJavaScript: code => Promise.resolve(vm.runInContext(code, context))};
  const guest = {getType: () => 'webview', isDestroyed: () => false,
    hostWebContents: host, mainFrame: {framesInSubtree: [frame]}};
  const win = {name: 'main', isDestroyed: () => false, webContents: host};
  const native = new NativeResume({nativeWindow: () => win, allContents: () => [guest]});
  const prepare = () => frame.executeJavaScript(`(${preparePage.toString()})(${JSON.stringify({
    friend, friendId:'202', job:'job-a', token:'fixture',
  })})`);
  return {calls, frame, guest, win, native, prepare, conversation, component, editor};
}

test('resume action accepts only original same-origin chat pages', () => {
  assert.equal(isChatURL('https://www.zhipin.com/web/client/index'), true);
  for (const url of ['https://www.zhipin.com:8443/web/client/index',
    'https://www.zhipin.com.evil.invalid/web/client/index',
    'http://www.zhipin.com/web/client/index', 'https://hoyee.org.cn/web/client/index',
    'https://user@www.zhipin.com/web/client/index', 'file:///web/client/index']) {
    assert.equal(isChatURL(url), false);
  }
});
test('resume action refuses unrelated and ambiguous webviews', () => {
  const f = pageFixture();
  f.guest.hostWebContents = {};
  assert.throws(() => f.native.frame(), {code: 'NATIVE_CHAT_UNAVAILABLE'});
  f.guest.hostWebContents = f.win.webContents;
  f.guest.mainFrame.framesInSubtree.push(f.frame);
  assert.throws(() => f.native.frame(), {code: 'NATIVE_CHAT_UNAVAILABLE'});
});
test('preparation locates the original component without sending or reopening the selected candidate', async () => {
  const f = pageFixture();
  assert.equal((await f.prepare()).ok, true);
  assert.deepEqual(f.calls, {select: 0, invoke: 0});
});
test('global toolbars cannot make the selected editor ambiguous or supply its missing entry', async () => {
  const f = pageFixture();
  const other = {...f.component};
  f.editor.$root = {$children: [f.editor, {$children: [other]}]};
  assert.equal((await f.prepare()).ok, true);
  f.editor.$children = [];
  assert.equal((await f.prepare()).code, 'NATIVE_CHAT_UNAVAILABLE');
  assert.deepEqual(f.calls, {select: 0, invoke: 0});
});
test('wrong job cannot invoke or confirm the native resume component', async () => {
  const f = pageFixture({job: 'other-job'});
  assert.equal((await f.prepare()).code, 'JOB_CONVERSATION_MISMATCH');
  assert.deepEqual(f.calls, {select: 0, invoke: 0});
});
test('resume requests use the current encrypted position even when list aliases are absent', async () => {
  const f = pageFixture();
  delete f.conversation.encryptJobId;
  f.conversation.toPositionId = 'job-a';
  assert.equal((await f.prepare()).ok, true);
  assert.equal(f.calls.invoke, 0);
});
test('an incomplete conversation waits for its position without invoking a premature resume request', async () => {
  const f = pageFixture();
  delete f.conversation.encryptJobId;
  const pending = f.prepare();
  assert.equal(f.calls.invoke, 0);
  f.conversation.toPositionId = 'job-a';
  assert.equal((await pending).ok, true);
  assert.equal(f.calls.invoke, 0);
});
test('contradictory position aliases prevent preparing an exchange', async () => {
  const f = pageFixture();
  f.conversation.toPositionId = 'other-job';
  assert.equal((await f.prepare()).code, 'JOB_CONVERSATION_MISMATCH');
  assert.equal(f.calls.invoke, 0);
});
test('a different exchange type cannot be prepared as a resume action', async () => {
  const f = pageFixture();f.component.type = 1;
  assert.equal((await f.prepare()).code, 'NATIVE_CHAT_UNAVAILABLE');
  assert.equal(f.calls.invoke, 0);
});

function rpcFixture(t, action) {
  let rpc;
  const adapter = {user: () => ({userId: '101'}), loggedIn: () => true,
    requestResume: input => action(input, rpc)};
  rpc = new NativeRPC({adapter, endpoints: [], sendTimeout: 30});
  t.after(() => rpc.close());
  return rpc;
}
function observe(rpc, {cmid = '404', to = '202', from = '101', text = RESUME_REQUEST_TEXT} = {}) {
  rpc.observeNativeSend({messages: [{
    from: {uid: from}, to: {uid: to}, cmid, body: {type: 1, text},
  }]});
}
function confirm(rpc, fields = {}) {
  rpc.receive([{isSelf: true, fromId: '101', toId: '202', mid: '505',
    clientMid: '404', type: 'text', status: 1, text: RESUME_REQUEST_TEXT,
    time: Date.now(), ...fields}]);
}
test('native HTTP exchange confirmation must match the exact account, candidate, job and attempt', async t => {
  const friend = {friendId: '202', encryptJobId: 'job-a'};
  const rpc = rpcFixture(t, async input => {
    input.onReady();
    return {transport: 'native-exchange-response', account: '101', friendId: '202', job: 'job-a',
      requestId: input.clientMid, exchangeType: 4, code: 0, status: 0};
  });
  assert.deepEqual(await rpc.requestResume('101', friend),
    {friendId: '202', confirmedBy: 'native-exchange-response', exchangeType: 4});
});
test('another exchange attempt or a business error cannot finish the pending resume request', async t => {
  for (const difference of [{account: '999'}, {friendId: '999'}, {job: 'other'},
    {requestId: '999'}, {exchangeType: 1}, {code: 1013}, {status: 1}]) {
    const rpc = rpcFixture(t, async input => {
      input.onReady();
      return {transport: 'native-exchange-response', account: '101', friendId: '202', job: 'job-a',
        requestId: input.clientMid, exchangeType: 4, code: 0, status: 0, ...difference};
    });
    await assert.rejects(rpc.requestResume('101', friend), {code: 'SEND_UNCONFIRMED'});
  }
});
test('platform rate limiting during the native exchange remains a stop code', async t => {
  const rpc = rpcFixture(t, async input => {
    input.onReady();
    throw Object.assign(Error('fixture rate limit'), {code: 'RATE_LIMITED'});
  });
  await assert.rejects(rpc.requestResume('101', friend), {code: 'RATE_LIMITED'});
});
test('resume success requires the observed native clientMid and matching server receipt', async t => {
  const rpc = rpcFixture(t, async ({onReady}, rpc) => {
    onReady();
    for (const wrong of [{to: '999'}, {from: '999'}, {text: 'other'}]) observe(rpc, wrong);
    assert.equal(rpc.pendingSends.get('202').clientMid, '');
    observe(rpc);
    for (const wrong of [{clientMid: ''}, {clientMid: '999'}, {toId: '999'},
      {newSubmit: true}, {status: 0}]) confirm(rpc, wrong);
    assert.equal(rpc.pendingSends.size, 1);
    confirm(rpc);
    return {ok: true};
  });
  assert.deepEqual(await rpc.requestResume('101', friend),
    {friendId: '202', messageId: '505', confirmedBy: 'client-message-receipt', exchangeType: 4});
});
test('matching text without native send observation cannot confirm a resume request', async t => {
  let invoked = 0;
  const rpc = rpcFixture(t, async ({onReady}, rpc) => {
    invoked++;onReady();confirm(rpc, {clientMid: ''});return {ok: true};
  });
  await assert.rejects(rpc.requestResume('101', friend), {code: 'SEND_UNCONFIRMED'});
  assert.equal(invoked, 1);
});
test('original byte-array send observation links clientMid but still waits for the server receipt', async t => {
  const scalar = (field, number) => {
    const bytes = [field * 8];
    do {
      const value = number & 127;number >>>= 7;
      bytes.push(number ? value | 128 : value);
    } while (number);
    return Buffer.from(bytes);
  };
  const field = (number, value) => Buffer.concat([
    Buffer.from([number * 8 + 2, value.length]), value,
  ]);
  const packet = Buffer.concat([scalar(1, 1), field(3, Buffer.concat([
    field(1, scalar(1, 101)), field(2, scalar(1, 202)), scalar(11, 404),
    field(6, Buffer.concat([scalar(1, 1), field(3, Buffer.from(RESUME_REQUEST_TEXT))])),
  ]))]);
  let sends = 0;
  const rpc = rpcFixture(t, async ({onReady}, rpc) => {
    sends++;onReady();
    rpc.observeNativeSend(decodeNativeProtocol(packet));
    assert.equal(rpc.pendingSends.get('202').clientMid, '404');
    assert.equal(rpc.pendingSends.size, 1);
    confirm(rpc, {clientMid: '999'});
    assert.equal(rpc.pendingSends.size, 1);
    confirm(rpc);
  });
  assert.equal((await rpc.requestResume('101', friend)).confirmedBy, 'client-message-receipt');
  assert.equal(sends, 1);
});
test('an invoked but unconfirmed native action remains uncertain and is never retried', async t => {
  let invoked = 0;
  const rpc = rpcFixture(t, async ({onReady}) => {
    invoked++;onReady();
    throw Object.assign(Error('confirmation missing'), {code: 'NATIVE_CONFIRMATION_REQUIRED'});
  });
  await assert.rejects(rpc.requestResume('101', friend), {code: 'SEND_UNCONFIRMED'});
  assert.equal(invoked, 1);
});
test('logout and worker cancellation abort a resume request still preparing', async t => {
  let signal;
  const rpc = rpcFixture(t, async input => {
    signal = input.signal;
    await new Promise(resolve => input.signal.addEventListener('abort', resolve, {once: true}));
    input.checkAccount();
  });
  const task = rpc.requestResume('101', friend);
  rpc.sessionLost('ACCOUNT_RISK');
  await assert.rejects(task, {code: 'ACCOUNT_RISK'});
  assert.equal(signal.aborted, true);
  const second = rpcFixture(t, async input => {
    signal = input.signal;
    await new Promise(resolve => input.signal.addEventListener('abort', resolve, {once: true}));
  });
  const controller = new AbortController();
  const cancelled = second.requestResume('101', friend, controller.signal);
  controller.abort();
  await assert.rejects(cancelled, {code: 'REQUESTS_PAUSED'});
  assert.equal(signal.aborted, true);
});
test('same-friend text and resume operations cannot overlap', async t => {
  let input;
  const rpc = rpcFixture(t, async value => {input = value;value.onReady();});
  const task = rpc.requestResume('101', friend);
  await assert.rejects(rpc.sendText('101', friend, 'hello'), {code: 'ACCOUNT_BUSY'});
  await assert.rejects(rpc.requestResume('101', friend), {code: 'ACCOUNT_BUSY'});
  observe(rpc);confirm(rpc);
  await task;
  assert.equal(input.signal.aborted, true);
});
test('different candidates cannot switch the shared resume editor concurrently', async t => {
  let invoked = 0;
  const rpc = rpcFixture(t, async ({friend: current, onReady}, rpc) => {
    invoked++;onReady();
    if (current.friendId === '303') {
      observe(rpc, {to: '303'});
      confirm(rpc, {toId: '303'});
    }
  });
  const first = rpc.requestResume('101', friend);
  const nextFriend = {...friend, friendId: '303'};
  await assert.rejects(rpc.requestResume('101', nextFriend), {code: 'ACCOUNT_BUSY'});
  assert.equal(invoked, 1);
  observe(rpc);confirm(rpc);
  await first;
  assert.equal((await rpc.requestResume('101', nextFriend)).friendId, '303');
  assert.equal(invoked, 2);
});
