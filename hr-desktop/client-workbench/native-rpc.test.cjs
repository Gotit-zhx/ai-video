const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {NativeRPC} = require('./native-rpc.cjs');

function fixture(t, overrides = {}) {
  let account = '101', loggedIn = true;
  const calls = [];
  const adapter = {
    user: () => ({userId: account}), loggedIn: () => loggedIn,
    snapshot: async () => ({accountId: account, cookies: [], userAgent: 'offline'}),
    request: async value => {calls.push(value);return {code: 0};},
    sendText: async value => {calls.push(value);},
    ...overrides,
  };
  const rpc = new NativeRPC({adapter, endpoints: ['/wapi/offline/test'], sendTimeout: 35});
  t.after(() => rpc.close());
  return {rpc, calls, account: value => {account = value;}, login: value => {loggedIn = value;}};
}
function receipt(sent, fields = {}) {
  return {isSelf: true, fromId: '101', toId: '202', mid: '303',
    clientMid: sent.options.clientMid, text: sent.content, time: Date.now(),
    type: 'text', status: 1, ...fields};
}
function request(rpc, {secret = rpc.secret, origin, host} = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(rpc.url + '/snapshot', {method: 'POST', headers: {
      Host: host || `127.0.0.1:${rpc.port}`, Authorization: 'Bearer ' + secret,
      ...(origin ? {Origin: origin} : {}),
    }}, res => {
      let text = '';res.on('data', x => {text += x;});
      res.on('end', () => resolve({status: res.statusCode, value: JSON.parse(text)}));
    });
    req.on('error', reject);req.end(JSON.stringify({accountId: '101'}));
  });
}
test('native bridge authenticates worker and rejects browser origins and rebinding', async t => {
  const {rpc} = fixture(t);await rpc.start();
  assert.equal((await request(rpc)).status, 200);
  for (const headers of [{secret: ''}, {origin: 'https://hoyee.org.cn'}, {host: 'localhost'}]) {
    assert.equal((await request(rpc, headers)).status, 403);
  }
});
test('wrong or logged-out account performs no write', async t => {
  const {rpc, calls, login} = fixture(t);
  await assert.rejects(rpc.sendText('999', {friendId: '202'}, 'hello'), {code: 'SESSION_MISMATCH'});
  login(false);
  await assert.rejects(rpc.sendText('101', {friendId: '202'}, 'hello'), {code: 'AUTH_REQUIRED'});
  assert.equal(calls.length, 0);
});
test('only incoming messages addressed to the current account signal activity', t => {
  const {rpc} = fixture(t), activity = [];
  rpc.on('activity', x => activity.push(x));
  rpc.receive([{isSelf: false, fromId: '202', toId: '101', mid: '303', time: 1700000000},
    {isSelf: false, fromId: '202', toId: '999', mid: '304', time: 1700000000000}]);
  assert.deepEqual(activity, [{friend: '202', mid: '303', at: 1700000000, job: ''}]);
  rpc.sessionLost();rpc.receive([{isSelf: false, fromId: '202', toId: '101', mid: '305', time: Date.now()}]);
  assert.equal(activity.length, 1);
});
test('real matching receipt succeeds; same text alone, stale and local echoes do not', async t => {
  const {rpc, calls} = fixture(t);
  const task = rpc.sendText('101', {friendId: '202', groupId: '666', encryptUid: 'friend'}, 'hello');
  const sent = calls[0];
  assert.equal(sent.options.groupId, undefined);
  for (const fields of [{toId: '999'}, {clientMid: '777'}, {time: 1},
    {newSubmit: true}, {status: 0}, {status: 2}, {type: 'dialog'}]) rpc.receive([receipt(sent, fields)]);
  assert.equal(rpc.pendingSends.size, 1);
  rpc.receive([receipt(sent)]);
  assert.deepEqual(await task, {friendId: '202', messageId: '303', confirmedBy: 'client-message-receipt'});
  assert.equal(rpc.pendingSends.size, 0);
});
test('transport invocation without a receipt stays unconfirmed and is never retried', async t => {
  const {rpc, calls} = fixture(t);
  await assert.rejects(rpc.sendText('101', {friendId: '202'}, 'hello'), {code: 'SEND_UNCONFIRMED'});
  assert.equal(calls.length, 1);assert.equal(rpc.pendingSends.size, 0);
});
test('logout interrupts pending receipt and stops subsequent actions', async t => {
  const {rpc} = fixture(t);
  const task = rpc.sendText('101', {friendId: '202'}, 'hello');
  rpc.sessionLost();
  await assert.rejects(task, {code: 'AUTH_EXPIRED'});
  await assert.rejects(rpc.dispatch('/snapshot', {accountId: '101'}), {code: 'AUTH_EXPIRED'});
});
test('native risk and duplicate-login holds retain their actual stop code', async t => {
  const {rpc, login} = fixture(t);
  rpc.sessionLost('ACCOUNT_RISK');
  rpc.sessionLost();
  login(false);
  await assert.rejects(rpc.dispatch('/snapshot', {accountId: '101'}), {code: 'ACCOUNT_RISK'});
  rpc.sessionLost('SESSION_MISMATCH');
  await assert.rejects(rpc.sendText('101', {friendId: '202'}, 'hello'), {code: 'SESSION_MISMATCH'});
});
test('account change before receipt cannot confirm the previous account write', async t => {
  const {rpc, account, calls} = fixture(t);
  const task = rpc.sendText('101', {friendId: '202'}, 'hello');
  account('999');rpc.receive([receipt(calls[0])]);
  await assert.rejects(task, {code: 'SEND_UNCONFIRMED'});
});
test('HTTP bridge allows only known same-origin routes and omits credential or identity overrides', async t => {
  const {rpc, calls} = fixture(t);
  for (const url of ['https://other.invalid/wapi/offline/test',
    'https://www.zhipin.com/wapi/unknown', 'https://www.zhipin.com/wapi/offline/test#x']) {
    await assert.rejects(rpc.dispatch('/http', {accountId: '101', url, method: 'GET'}),
      {code: 'UNSUPPORTED_OPERATION'});
  }
  await rpc.dispatch('/http', {accountId: '101', method: 'POST',
    url: 'https://www.zhipin.com/wapi/offline/test?wt2=bad',
    data: {zp_token: 'bad', origin: 'bad', clientInfo: {}, friendId: '202'}});
  assert.deepEqual(calls, [{url: '/wapi/offline/test', method: 'post', data: {friendId: '202'}}]);
});
test('account changing during an HTTP read prevents returning another account data', async t => {
  let finish;
  const {rpc, account} = fixture(t, {request: () => new Promise(resolve => {finish = resolve;})});
  const task = rpc.dispatch('/http', {accountId: '101', method: 'GET',
    url: 'https://www.zhipin.com/wapi/offline/test'});
  account('999');finish({code: 0});
  await assert.rejects(task, {code: 'SESSION_MISMATCH'});
});
test('only an actual completed same-account native login can recover ordinary expiry', async t => {
  const {rpc} = fixture(t, {snapshot: async () => ({accountId: '101', userAgent: 'fixture',
    cookies: [{name: 'wt2', domain: '.zhipin.com', value: 'fixture-token'}]})});
  rpc.sessionLost();
  await assert.rejects(rpc.refreshSession('101'), {code: 'AUTH_EXPIRED'});
  rpc.loginEstablished();
  assert.equal((await rpc.refreshSession('101')).accountId, '101');
  assert.equal(rpc.sessionFailure, null);
});
test('re-login cannot clear risk or duplicate-login stops, or bind another account', async t => {
  for (const code of ['ACCOUNT_RISK', 'SECURITY_VERIFICATION_REQUIRED', 'SESSION_MISMATCH']) {
    const {rpc} = fixture(t);
    rpc.sessionLost(code);rpc.loginEstablished();
    await assert.rejects(rpc.refreshSession('101'), {code});
    assert.equal(rpc.sessionFailure, code);
  }
  const {rpc, account} = fixture(t);
  rpc.sessionLost();account('999');rpc.loginEstablished();
  await assert.rejects(rpc.refreshSession('999'), {code: 'AUTH_EXPIRED'});
});
test('a second logout during session verification cannot be cleared by the earlier read', async t => {
  let finish;
  const {rpc} = fixture(t, {snapshot: () => new Promise(resolve => {finish = resolve;})});
  rpc.sessionLost();rpc.loginEstablished();
  const task = rpc.refreshSession('101');
  rpc.sessionLost('ACCOUNT_RISK');
  finish({accountId: '101', userAgent: 'offline', cookies: []});
  await assert.rejects(task, {code: 'REQUESTS_PAUSED'});
  assert.equal(rpc.sessionFailure, 'ACCOUNT_RISK');
});
test('private loopback download returns raw bytes and real failure status without redirects', async t => {
  const {rpc} = fixture(t, {download: async () => ({status: 200, body: Buffer.from('%PDF-fixture')})});
  await rpc.start();
  const url = 'https://docdownload.zhipin.com/wflow/zpgeek/download/download4boss/fixture?id=fixture';
  const options = {method: 'POST', body: JSON.stringify({accountId: '101', url}),
    headers: {Authorization: 'Bearer ' + rpc.secret}};
  const response = await fetch(rpc.url + '/download', options);
  assert.equal(response.headers.get('x-heyi-native-download'), '1');
  assert.equal(await response.text(), '%PDF-fixture');
  rpc.adapter.download = async () => ({status: 429, body: Buffer.from('limit')});
  const limited = await fetch(rpc.url + '/download', options);
  assert.equal(limited.status, 429);
  assert.equal(limited.headers.get('location'), null);
});
test('logout aborts an active download; account change cannot return received bytes', async t => {
  let finish, signal;
  const {rpc, account} = fixture(t, {download: (_input, _check, value) => {
    signal = value;return new Promise(resolve => {finish = resolve;});
  }});
  const url = 'https://docdownload.zhipin.com/wflow/zpgeek/download/download4boss/fixture?id=fixture';
  const task = rpc.dispatch('/download', {accountId: '101', url});
  account('999');
  finish({status: 200, body: Buffer.from('%PDF-fixture')});
  await assert.rejects(task, {code: 'SESSION_MISMATCH'});
  account('101');
  const second = rpc.dispatch('/download', {accountId: '101', url});
  rpc.sessionLost('ACCOUNT_RISK');
  assert.equal(signal.aborted, true);
  finish({status: 200, body: Buffer.from('%PDF-fixture')});
  await assert.rejects(second, {code: 'ACCOUNT_RISK'});
});
