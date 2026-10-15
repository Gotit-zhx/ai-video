const test = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const {Readable} = require('node:stream');
const {downloadResume, resumeURL} = require('./native-download.cjs');
const url = 'https://docdownload.zhipin.com/wflow/zpgeek/download/download4boss/geek-202?id=resume-303';

function fixture(overrides = {}) {
  const request = new EventEmitter(), calls = {headers: {}, aborted: 0, count: 0};
  const response = Readable.from([Buffer.from('%PDF-1.7\nfixture')]);
  response.statusCode = 200;response.headers = {};
  request.setHeader = (key, value) => {calls.headers[key] = value;};
  request.abort = () => {calls.aborted++;request.emit('abort');};
  request.end = () => queueMicrotask(() => request.emit('response', response));
  const options = {net: {request: input => {calls.count++;calls.options = input;return request;}},
    session: {}, url, checkAccount: () => {}, timeout: 100,
    credentials: async () => ({token: 'fixture-only', zpToken: 'fixture-zp', userAgent: 'fixture-agent'}),
    ...overrides};
  return {request, response, calls, options};
}
test('attachment URLs accept only the fixed document host, path and validated parameters', () => {
  assert.equal(resumeURL(url), url);
  for (const value of [url.replace('https:', 'http:'), url.replace('docdownload.', 'other.'),
    url.replace('/geek-202', '/wrong/geek-202'), url + '#fragment', url + '&id=duplicate',
    url + '&headers=bad', url.replace('id=resume-303', 'd=only'), url.replace('geek-202', '%2Fetc'),
    url.replace('geek-202', '%5Cfile'), url.replace('https://', 'https://user:pass@')]) {
    assert.throws(() => resumeURL(value), {code: 'INVALID_ATTACHMENT'});
  }
});
test('native download makes one request and preserves actual bytes, status and native identity', async () => {
  const {options, calls, response} = fixture();
  response.headers['Content-Disposition'] = ["attachment; filename*=UTF-8''resume.pdf"];
  response.headers['set-cookie'] = ['private=not-forwarded'];
  const result = await downloadResume(options);
  assert.equal(result.status, 200);
  assert.equal(result.body.toString(), '%PDF-1.7\nfixture');
  assert.equal(calls.count, 1);
  assert.equal(calls.options.redirect, 'manual');
  assert.equal(calls.options.session, options.session);
  assert.equal(calls.headers.Cookie, 'wt2=fixture-only');
  assert.equal(calls.headers['User-Agent'], 'fixture-agent');
  assert.equal(result.contentDisposition, "attachment; filename*=UTF-8''resume.pdf");
  assert.equal(result.headers, undefined);
});
test('invalid attachment metadata is dropped without losing valid file bytes', async () => {
  for (const value of ['bad\r\nX-Injected: 1', 'x'.repeat(8193), 'attachment; filename="简历.pdf"']) {
    const {options, response} = fixture();
    response.headers['content-disposition'] = value;
    const result = await downloadResume(options);
    assert.equal(result.contentDisposition, undefined);
    assert.equal(result.body.toString(), '%PDF-1.7\nfixture');
  }
});
test('redirects report their status, never follow and never expose the signed destination', async () => {
  const {options, request, calls} = fixture();
  request.end = () => queueMicrotask(() => request.emit('redirect', 302, 'GET', 'https://secret.invalid'));
  assert.deepEqual(await downloadResume(options), {status: 302, body: Buffer.alloc(0)});
  assert.equal(calls.count, 1);
  assert.equal(calls.aborted, 1);
});
test('incomplete data, oversize, timeout and cancellation stop without retries', async () => {
  for (const kind of ['oversize', 'interrupted', 'truncated', 'timeout', 'cancelled']) {
    const {options, response, request, calls} = fixture({timeout: 5});
    let code;
    if (kind === 'oversize') {options.maxBytes = 3;code = 'ATTACHMENT_TOO_LARGE';}
    if (kind === 'interrupted') {
      request.end = () => queueMicrotask(() => {
        request.emit('response', response);response.emit('aborted');
      });code = 'DOWNLOAD_FAILED';
    }
    if (kind === 'truncated') {response.headers['Content-Length'] = ['99'];code = 'DOWNLOAD_FAILED';}
    if (kind === 'timeout') {request.end = () => {};code = 'DOWNLOAD_TIMEOUT';}
    if (kind === 'cancelled') {
      const controller = new AbortController();options.signal = controller.signal;
      request.end = () => controller.abort();code = 'REQUESTS_PAUSED';
    }
    await assert.rejects(downloadResume(options), {code});
    assert.equal(calls.count, 1);assert.equal(calls.aborted, 1);
  }
});
test('account change before transport or while reading blocks the attachment', async () => {
  const before = fixture({checkAccount: () => {throw Object.assign(Error(), {code: 'SESSION_MISMATCH'});}});
  await assert.rejects(downloadResume(before.options), {code: 'SESSION_MISMATCH'});
  assert.equal(before.calls.count, 0);
  let changed = false;
  const during = fixture({checkAccount: () => {
    if (changed) throw Object.assign(Error(), {code: 'SESSION_MISMATCH'});
  }});
  during.request.end = () => queueMicrotask(() => {
    changed = true;during.request.emit('response', during.response);
  });
  await assert.rejects(downloadResume(during.options), {code: 'SESSION_MISMATCH'});
  assert.equal(during.calls.count, 1);
});
test('credential waits are cancelled or timed out before a late download can start', async () => {
  for (const code of ['REQUESTS_PAUSED', 'DOWNLOAD_TIMEOUT']) {
    let release;
    const controller = new AbortController();
    const {options, calls} = fixture({signal: controller.signal, timeout: 35,
      credentials: () => new Promise(resolve => {release = resolve;})});
    const pending = downloadResume(options);
    await new Promise(resolve => setImmediate(resolve));
    if (code === 'REQUESTS_PAUSED') controller.abort();
    await assert.rejects(pending, {code});
    release({token: 'late-fixture', userAgent: 'offline'});
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(calls.count, 0);
    assert.equal(calls.aborted, 0);
  }
});
test('an already cancelled download and rejected credentials never open a transport', async () => {
  const controller = new AbortController();controller.abort();
  let reads = 0;
  const {options, calls} = fixture({signal: controller.signal, credentials: async () => {reads++;}});
  await assert.rejects(downloadResume(options), {code: 'REQUESTS_PAUSED'});
  assert.equal(reads, 0);
  for (const reason of [null, undefined, Error('fixture-private-data')]) {
    await assert.rejects(downloadResume({...options, signal: undefined,
      credentials: () => Promise.reject(reason)}), value =>
      value.code === 'DOWNLOAD_FAILED' && !value.message.includes('fixture-private-data'));
  }
  assert.equal(calls.count, 0);
});
