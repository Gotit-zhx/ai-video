const test = require('node:test');
const assert = require('node:assert/strict');
const {NativeRequestBroker, preparedCredentials} = require('./native-request-broker.cjs');

const endpoint = '/wapi/offline/fixture';
const reply = (statusCode = 200, body = {code: 0}) => ({heyiNativeHttp: 1, statusCode, body});
const failure = code => Object.assign(Error('fixture'), {code});
function fixture(t, options = {}) {
  let nativeCalls = 0, builderCalls = 0, sequence = 0;
  const prepared = [];
  const client = {send(input) {nativeCalls++;assert.equal(this, client);return input;}};
  const original = Object.getOwnPropertyDescriptor(client, 'send');
  const build = async input => {
    builderCalls++;
    if (options.before) await options.before(input);
    const config = {...input, url: 'https://www.zhipin.com' + input.url, headers: {
      Cookie: 'wt2=private-fixture-token', 'User-Agent': 'original-client-agent', Traceid: String(++sequence),
    }};
    try {
      const result = await client.send(config);
      if (options.after) await options.after(config, result);
      return result.body;
    } catch (error) {
      if (options.retry) return client.send(config);
      if (options.swallow) return null;
      throw error;
    }
  };
  const broker = new NativeRequestBroker({clients: [client],
    builder: options.builder || build, endpoints: [endpoint], timeout: options.timeout || 100,
    transport: async (input, check, signal) => {
      prepared.push(input);
      if (options.transport) return options.transport(input, check, signal);
      return reply();
    }});
  t.after(() => broker.close());
  return {broker, client, original, prepared, nativeCalls: () => nativeCalls,
    builderCalls: () => builderCalls};
}
const input = {url: endpoint, method: 'POST', data: {name: '中文', tags: ['one', 'two']}};

test('one original builder prepares fresh headers; real non-success HTTP status is retained', async t => {
  const f = fixture(t, {transport: async () => reply(429, {code: 88})});
  assert.deepEqual(await f.broker.request({...input, headers: {Cookie: 'must-not-override'}}, () => {}),
    reply(429, {code: 88}));
  assert.equal(f.builderCalls(), 1);assert.equal(f.nativeCalls(), 0);
  assert.equal(f.prepared[0].headers.Cookie, 'wt2=private-fixture-token');
  assert.equal(f.prepared[0].headers.Traceid, '1');
  assert.deepEqual(f.prepared[0].data, input.data);
  await f.broker.request(input, () => {});
  assert.equal(f.prepared[1].headers.Traceid, '2');
});
test('original traffic outside the addon context keeps its original method and this value', t => {
  const f = fixture(t), marker = {};
  assert.equal(f.client.send(marker), marker);
  assert.equal(f.nativeCalls(), 1);assert.equal(f.prepared.length, 0);
});
test('interleaved original preparations keep separate request and header contexts', async t => {
  let release;
  const first = new Promise(resolve => {release = resolve;});
  const f = fixture(t, {before: input => input.data.marker === 'first' ? first : undefined});
  const pending = f.broker.request({...input, data: {marker: 'first'}}, () => {});
  await f.broker.request({...input, data: {marker: 'second'}}, () => {});
  release();await pending;
  assert.deepEqual(f.prepared.map(config => [config.data.marker, config.headers.Traceid]),
    [['second', '1'], ['first', '2']]);
  assert.equal(f.nativeCalls(), 0);
});
test('a timed-out original preparation cannot start a late send, including after close', async t => {
  let release, entered;
  const started = new Promise(resolve => {entered = resolve;});
  const delayed = new Promise(resolve => {release = resolve;});
  const f = fixture(t, {timeout: 20, swallow: true, before: async () => {entered();await delayed;}});
  const pending = f.broker.request(input, () => {});
  await started;
  await assert.rejects(pending, {code: 'NATIVE_HTTP_TIMEOUT'});
  f.broker.close();
  assert.equal(f.broker.detached, false);
  assert.notEqual(f.client.send, f.original.value);
  release();await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.broker.detached, true);
  assert.deepEqual(Object.getOwnPropertyDescriptor(f.client, 'send'), f.original);
  assert.equal(f.prepared.length, 0);assert.equal(f.nativeCalls(), 0);
});
test('cancellation aborts active transport and retains an actual account risk reason', async t => {
  let risk = false, entered, transportSignal;
  const ready = new Promise(resolve => {entered = resolve;});
  const f = fixture(t, {swallow: true, transport: async (_input, _check, signal) => {
    transportSignal = signal;entered();
    return new Promise((_resolve, reject) => signal.addEventListener('abort',
      () => reject(failure('REQUESTS_PAUSED')), {once: true}));
  }});
  const controller = new AbortController();
  const pending = f.broker.request(input, () => {if (risk) throw failure('ACCOUNT_RISK');}, controller.signal);
  await ready;risk = true;controller.abort();
  await assert.rejects(pending, {code: 'ACCOUNT_RISK'});
  assert.equal(transportSignal.aborted, true);assert.equal(f.nativeCalls(), 0);
});
test('account changes during native preparation prevent any transport', async t => {
  let account = '101', release;
  const f = fixture(t, {before: () => new Promise(resolve => {release = resolve;})});
  const pending = f.broker.request(input, () => {if (account !== '101') throw failure('SESSION_MISMATCH');});
  await new Promise(resolve => setImmediate(resolve));
  account = '999';release();
  await assert.rejects(pending, {code: 'SESSION_MISMATCH'});
  assert.equal(f.prepared.length, 0);assert.equal(f.nativeCalls(), 0);
});
test('an original retry cannot perform a second transport', async t => {
  let calls = 0;
  const f = fixture(t, {retry: true, transport: async () => {calls++;throw failure('NATIVE_HTTP_FAILED');}});
  await assert.rejects(f.broker.request(input, () => {}), {code: 'UNEXPECTED_NATIVE_RETRY'});
  assert.equal(calls, 1);assert.equal(f.builderCalls(), 1);assert.equal(f.nativeCalls(), 0);
});
test('a swallowed transport failure remains a failed request; original exceptions cannot report success', async t => {
  const f = fixture(t, {swallow: true, transport: async () => {throw failure('NATIVE_HTTP_FAILED');}});
  await assert.rejects(f.broker.request(input, () => {}), {code: 'NATIVE_HTTP_FAILED'});
  const second = fixture(t, {after: () => {throw Error('private-original-failure');}});
  await assert.rejects(second.broker.request(input, () => {}), error =>
    error.code === 'NATIVE_HTTP_FAILED' && !error.message.includes('private-original-failure'));
});
test('unreviewed or mismatched prepared URLs are rejected without transport', async t => {
  const f = fixture(t);
  for (const url of ['https://other.invalid' + endpoint, '/wapi/unreviewed', endpoint + '#fragment']) {
    await assert.rejects(f.broker.request({...input, url}, () => {}), {code: 'UNSUPPORTED_OPERATION'});
  }
  assert.equal(f.builderCalls(), 0);
  const g = fixture(t);
  g.broker.builder = async () => g.client.send({url: 'https://other.invalid' + endpoint, method: 'post'});
  await assert.rejects(g.broker.request(input, () => {}), {code: 'UNSUPPORTED_OPERATION'});
  assert.equal(g.prepared.length, 0);assert.equal(g.nativeCalls(), 0);
});
test('pre-cancelled calls and closed brokers do not invoke the original builder', async t => {
  const f = fixture(t), controller = new AbortController();controller.abort();
  await assert.rejects(f.broker.request(input, () => {}, controller.signal), {code: 'REQUESTS_PAUSED'});
  f.broker.close();
  await assert.rejects(f.broker.request(input, () => {}), {code: 'REQUESTS_PAUSED'});
  assert.equal(f.builderCalls(), 0);assert.equal(f.nativeCalls(), 0);
});
test('original authentication fields are validated without evaluating accessors or accepting duplicates', () => {
  const headers = {Cookie: 'wt2=private', 'User-Agent': 'native', Traceid: 'native-trace'};
  assert.deepEqual(preparedCredentials({headers}), {cookieHeader: 'wt2=private', userAgent: 'native',
    traceId: 'native-trace', zpToken: undefined});
  assert.throws(() => preparedCredentials({headers: {...headers, cookie: 'wt2=other'}}), {code: 'AUTH_REQUIRED'});
  const getters = {...headers};
  Object.defineProperty(getters, 'Cookie', {enumerable: true, get() {assert.fail('must not read');}});
  assert.throws(() => preparedCredentials({headers: getters}), {code: 'AUTH_REQUIRED'});
});
