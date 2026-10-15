const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {EventEmitter, once} = require('node:events');
const {nativeJSONRequest} = require('./native-http.cjs');

const route = '/wapi/offline/test';
const auth = {cookieHeader: 'wt2=fixture-only; device=fixture', token: 'fixture-only',
  userAgent: 'offline-fixture', zpToken: 'fixture-zp', traceId: 'fixture-trace'};
const nextTurn = () => new Promise(resolve => setImmediate(resolve));
const error = code => Object.assign(Error('fixture-private-data'), {code});

// Exercise real HTTP parsing, encoding, truncation and connection cleanup.
// Only this fixture maps the already-validated canonical URL to loopback.
async function fixture(t, handler = (_req, res) => res.end('{"code":0}')) {
  const calls = {options: [], received: [], aborted: 0};
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    calls.received.push({method: req.method, url: req.url, headers: req.headers,
      body: Buffer.concat(chunks).toString()});
    handler(req, res);
  });
  const sockets = new Set();
  server.on('connection', socket => {
    sockets.add(socket);socket.on('close', () => sockets.delete(socket));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(async () => {
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => server.close(resolve));
  });
  const net = {request(options) {
    calls.options.push(options);
    const url = new URL(options.url);
    const request = new EventEmitter();
    const outgoing = http.request({hostname: '127.0.0.1', port: server.address().port,
      method: options.method, path: url.pathname + url.search, agent: false}, response => {
      if ([301, 302, 303, 307, 308].includes(response.statusCode)) {
        request.emit('redirect', response.statusCode, 'GET', response.headers.location);
        response.resume();
      } else request.emit('response', response);
    });
    outgoing.on('error', value => request.emit('error', value));
    request.setHeader = (...args) => outgoing.setHeader(...args);
    request.end = (...args) => outgoing.end(...args);
    request.abort = () => {
      calls.aborted++;outgoing.destroy();request.emit('abort');
    };
    return request;
  }};
  return {calls, options: {net, session: {}, url: route, endpoints: new Set([route]),
    credentials: async () => ({...auth}), checkAccount() {}, timeout: 2000}};
}

test('native HTTP preserves Chinese and repeated form fields with the original session headers', async t => {
  const {calls, options} = await fixture(t);
  const data = {query: '设计师 & A+B', ids: ['202', '303'], nested: {name: '中文'}, empty: null};
  assert.deepEqual(await nativeJSONRequest({...options, url: route + '?page=2', data}),
    {heyiNativeHttp: 1, statusCode: 200, body: {code: 0}});
  assert.deepEqual(await nativeJSONRequest({...options, method: 'post', data}),
    {heyiNativeHttp: 1, statusCode: 200, body: {code: 0}});
  assert.equal(calls.received.length, 2);
  for (const [index, received] of calls.received.entries()) {
    const values = index === 0 ? new URL(received.url, 'http://fixture').searchParams :
      new URLSearchParams(received.body);
    assert.equal(values.get('query'), data.query);
    assert.deepEqual(values.getAll('ids'), data.ids);
    assert.deepEqual(JSON.parse(values.get('nested')), data.nested);
    assert.equal(values.get('empty'), '');
    assert.equal(received.headers.cookie, auth.cookieHeader);
    assert.equal(received.headers['user-agent'], auth.userAgent);
    assert.equal(received.headers.zp_token, auth.zpToken);
    assert.equal(received.headers.traceid, auth.traceId);
    assert.equal(calls.options[index].session, options.session);
    assert.equal(calls.options[index].useSessionCookies, false);
    assert.equal(calls.options[index].redirect, 'manual');
  }
  assert.equal(new URL(calls.received[0].url, 'http://fixture').searchParams.get('page'), '2');
  assert.match(calls.received[1].headers['content-type'], /^application\/x-www-form-urlencoded/);
});

test('actual rejection statuses and non-JSON bodies are preserved without retries', async t => {
  let status = 401;
  const {calls, options} = await fixture(t, (_req, res) => {
    res.writeHead(status);res.end(status === 401 ? '{"code":37}' : 'rate limit');
  });
  assert.deepEqual(await nativeJSONRequest(options),
    {heyiNativeHttp: 1, statusCode: 401, body: {code: 37}});
  status = 429;
  assert.deepEqual(await nativeJSONRequest(options),
    {heyiNativeHttp: 1, statusCode: 429, body: 'rate limit'});
  assert.equal(calls.options.length, 2);
  assert.equal(calls.received.length, 2);
});

test('redirects never request the destination or expose its signed URL', async t => {
  const {calls, options} = await fixture(t, (_req, res) => {
    res.writeHead(302, {Location: 'https://private.invalid?token=fixture-secret'});res.end();
  });
  assert.deepEqual(await nativeJSONRequest(options),
    {heyiNativeHttp: 1, statusCode: 302, body: null});
  await nextTurn();
  assert.equal(calls.options.length, 1);
  assert.equal(calls.received.length, 1);
  assert.equal(calls.aborted, 1);
});

test('invalid routes, methods, limits, parameters and credentials make zero requests', async t => {
  const {calls, options} = await fixture(t);
  for (const patch of [
    {url: 'https://other.invalid' + route}, {url: 'http://www.zhipin.com' + route},
    {url: 'https://name:pass@www.zhipin.com' + route}, {url: route + '#private'},
    {url: '/wapi/unknown'}, {method: 'DELETE'}, {timeout: 0},
    {maxBytes: 20 * 1024 * 1024 + 1}, {data: []}, {data: {query: 'x'.repeat(65537)}},
  ]) await assert.rejects(nativeJSONRequest({...options, ...patch}));
  for (const value of [
    {...auth, cookieHeader: ''}, {...auth, cookieHeader: 'wt2='},
    {...auth, cookieHeader: 'wt2=one; wt2=two'}, {...auth, token: 'other'},
    {...auth, cookieHeader: 'wt2=fixture-only\r\nX-Bad: 1'},
    {...auth, userAgent: ''}, {...auth, zpToken: 'bad\nheader'},
  ]) {
    // An absent cookie header may use the original token, so remove that
    // fallback when explicitly checking missing authentication.
    if (value.cookieHeader === '') delete value.token;
    await assert.rejects(nativeJSONRequest({...options, credentials: async () => value}));
  }
  assert.equal(calls.options.length, 0);
  assert.equal(calls.received.length, 0);
});

test('cancelled or timed-out credential reads cannot issue a late request', async t => {
  const {calls, options} = await fixture(t);
  for (const code of ['REQUESTS_PAUSED', 'NATIVE_HTTP_TIMEOUT']) {
    let release;
    const controller = new AbortController();
    const pending = nativeJSONRequest({...options, signal: controller.signal, timeout: 35,
      credentials: () => new Promise(resolve => {release = resolve;})});
    await nextTurn();
    if (code === 'REQUESTS_PAUSED') controller.abort();
    await assert.rejects(pending, {code});
    release({...auth});
    await nextTurn();
  }
  const controller = new AbortController();controller.abort();
  let credentialReads = 0;
  await assert.rejects(nativeJSONRequest({...options, signal: controller.signal,
    credentials: async () => {credentialReads++;return auth;}}), {code: 'REQUESTS_PAUSED'});
  assert.equal(credentialReads, 0);
  assert.equal(calls.options.length, 0);
});

test('account changes before transport and while the real response arrives stop the read', async t => {
  let valid = true;
  const {calls, options} = await fixture(t, (_req, res) => {
    valid = false;res.end('{"private":"must-not-return"}');
  });
  const checkAccount = () => {if (!valid) throw error('SESSION_MISMATCH');};
  valid = false;
  await assert.rejects(nativeJSONRequest({...options, checkAccount}), {code: 'SESSION_MISMATCH'});
  assert.equal(calls.options.length, 0);
  valid = true;
  await assert.rejects(nativeJSONRequest({...options, checkAccount}), {code: 'SESSION_MISMATCH'});
  assert.equal(calls.options.length, 1);
  assert.equal(calls.aborted, 1);
});

test('truncated, oversized, stalled and cancelled real responses stop after one request each', async t => {
  for (const mode of ['declared', 'streamed', 'truncated', 'stalled', 'cancelled']) {
    await t.test(mode, async t => {
      const controller = new AbortController();
      const {calls, options} = await fixture(t, (_req, res) => {
        if (mode === 'declared') {res.writeHead(200, {'Content-Length': 999});res.end('small');}
        if (mode === 'streamed') {res.write('123');res.end('456789');}
        if (mode === 'truncated') {
          res.writeHead(200, {'Content-Length': 999});res.write('small');
          setImmediate(() => res.destroy());
        }
        if (mode === 'cancelled') controller.abort();
      });
      const code = ['declared', 'streamed'].includes(mode) ? 'NATIVE_RESPONSE_TOO_LARGE' :
        mode === 'truncated' ? 'NATIVE_HTTP_FAILED' :
          mode === 'stalled' ? 'NATIVE_HTTP_TIMEOUT' : 'REQUESTS_PAUSED';
      await assert.rejects(nativeJSONRequest({...options, signal: controller.signal,
        maxBytes: ['declared', 'streamed'].includes(mode) ? 6 : 2000,
        timeout: mode === 'stalled' ? 50 : 2000}), {code});
      assert.equal(calls.options.length, 1);
      assert.equal(calls.aborted, 1);
    });
  }
});

test('proxy authentication is single-use; handler failures reject without exposing credentials', async () => {
  for (const mode of ['proxy', 'origin', 'throw', 'callback-throw', 'bad-redirect']) {
    const request = new EventEmitter();
    const replies = [];
    request.abort = () => request.emit('abort');
    request.setHeader = () => {};
    request.end = () => {
      if (mode === 'bad-redirect') return request.emit('redirect', 200);
      request.emit('login', {isProxy: mode === 'proxy'}, (...args) => {
        replies.push(args);
        if (mode === 'callback-throw') throw Error('fixture-secret');
      });
      if (!['throw', 'callback-throw'].includes(mode)) request.emit('redirect', 302);
    };
    const pending = nativeJSONRequest({net: {request: () => request}, session: {}, url: route,
      endpoints: new Set([route]), credentials: async () => auth, checkAccount() {},
      authenticate(info, callback) {
        if (mode === 'throw') throw Error('fixture-secret');
        if (!info.isProxy) return false;
        callback('heyi', 'fixture-relay');callback('heyi', 'must-not-repeat');return true;
      }});
    if (['throw', 'callback-throw', 'bad-redirect'].includes(mode)) {
      await assert.rejects(pending, value => {
        assert.equal(value.message.includes('fixture-secret'), false);
        return value.code === (mode === 'bad-redirect' ? 'INVALID_NATIVE_RESPONSE' : 'NATIVE_HTTP_FAILED');
      });
    } else assert.equal((await pending).statusCode, 302);
    assert.deepEqual(replies, mode === 'proxy' ? [['heyi', 'fixture-relay']] :
      ['origin', 'callback-throw'].includes(mode) ? [[]] : []);
  }
});
