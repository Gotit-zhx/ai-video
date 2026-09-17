import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../src/server.js', import.meta.url), 'utf8');
const functions = source.slice(source.indexOf('function imageFailureCode('), source.indexOf('async function callImageModel('));
const generated = () => ({ ok: true, status: 200, text: async () => '{"data":[{"url":"https://result.invalid/image.png"}]}' });
const interrupted = (code) => Object.assign(new TypeError('fetch failed'), { cause: Object.assign(new Error('connection interrupted'), { code }) });

function harness(reply) {
  const calls = [], events = [], stages = [];
  const context = vm.createContext({
    randomUUID: () => 'generated-request-id',
    IMAGE_FETCH_DISPATCHER: {}, IMAGE_RESPONSE_TIMEOUT_MS: 900_000,
    IMAGE_CONCURRENCY_RETRY_DELAYS_MS: [1, 2, 4, 8], IMAGE_POLICY_RETRY_DELAYS_MS: [1, 2, 4],
    IMAGE_NETWORK_RETRY_DELAYS_MS: [2000, 5000],
    AbortSignal: { timeout: () => undefined },
    setTimeout: (callback, delay) => { events.push(['sleep', delay]); callback(); },
    fetch: async (url, options) => {
      const request = new Request(url, { method: options.method, headers: options.headers, body: options.body });
      const call = { url, key: request.headers.get('idempotency-key'), authorization: request.headers.get('authorization') };
      if (request.headers.get('content-type')?.startsWith('multipart/form-data')) {
        const form = await request.formData();
        call.fields = Object.fromEntries([...form.entries()].filter(([, value]) => typeof value === 'string'));
        call.images = [];
        for (const [field, value] of form.entries()) {
          if (typeof value !== 'string') call.images.push({ field, name: value.name, bytes: Buffer.from(await value.arrayBuffer()) });
        }
      } else call.body = await request.text();
      calls.push(call); events.push(['post', calls.length]);
      return reply(call, calls.length);
    }
  });
  vm.runInContext(functions, context);
  return {
    calls, events, stages,
    request: (options = {}, factory = () => 'unchanged request') => context.requestImageModel(
      'https://images.invalid/v1/images/edits', { Authorization: 'Bearer unit-key' }, factory,
      (...stage) => stages.push(stage), {
        idempotencyKey: 'persisted-request-id',
        beforeAttempt: async value => events.push(['before', value.attempt, value.networkRetryCount, value.requestId]),
        recordNetworkRetry: async count => events.push(['record', count]),
        ...options
      }
    )
  };
}

for (const code of ['UND_ERR_SOCKET', 'ECONNRESET', 'ECONNABORTED', 'EPIPE']) {
  test(`${code} recovers with one stable key and persists each retry before another POST`, async () => {
    const h = harness((call, count) => { if (count < 3) throw interrupted(code); return generated(); });
    const result = await h.request();
    assert.equal(result.networkRetryCount, 2); assert.equal(result.requestId, 'persisted-request-id');
    assert.equal(h.calls.length, 3);
    assert.ok(h.calls.every(call => call.key === 'persisted-request-id' && call.body === 'unchanged request'));
    assert.deepEqual(h.events, [
      ['before', 1, 0, 'persisted-request-id'], ['post', 1], ['record', 1], ['sleep', 2000],
      ['before', 2, 1, 'persisted-request-id'], ['post', 2], ['record', 2], ['sleep', 5000],
      ['before', 3, 2, 'persisted-request-id'], ['post', 3]
    ]);
    assert.deepEqual(h.stages, [
      ['GENERATING', 1], ['RECOVERING_CONNECTION', 1], ['RETRY_WAIT', 1],
      ['GENERATING', 2], ['RECOVERING_CONNECTION', 2], ['RETRY_WAIT', 2], ['GENERATING', 3]
    ]);
  });
}

test('multipart connection recovery retains model, prompt, image bytes, filename, and authorization', async () => {
  const bytes = Buffer.from('original reference image bytes');
  const h = harness((call, count) => { if (count === 1) throw interrupted('UND_ERR_SOCKET'); return generated(); });
  await h.request({}, () => {
    const form = new FormData();
    form.append('model', 'gpt-image-2'); form.append('prompt', 'Remove only the overlaid watermark.');
    form.append('image', new Blob([bytes], { type: 'image/png' }), 'reference.png');
    return form;
  });
  assert.deepEqual(h.calls[0], h.calls[1]);
  assert.equal(h.calls[0].fields.model, 'gpt-image-2');
  assert.equal(h.calls[0].fields.prompt, 'Remove only the overlaid watermark.');
  assert.deepEqual(h.calls[0].images[0].bytes, bytes);
});

test('disconnect while reading a successful HTTP response body is recovered', async () => {
  const h = harness((call, count) => count === 1
    ? { ok: true, status: 200, text: async () => { throw interrupted('UND_ERR_SOCKET'); } } : generated());
  const result = await h.request();
  assert.equal(h.calls.length, 2); assert.equal(result.networkRetryCount, 1);
});

for (const error of [interrupted('UND_ERR_HEADERS_TIMEOUT'), interrupted('UND_ERR_BODY_TIMEOUT'),
  interrupted('ETIMEDOUT'), new DOMException('The operation was aborted due to timeout', 'TimeoutError')]) {
  test(`${error.cause?.code || error.name} stops after two authorized retries without claiming retry safety`, async () => {
    const h = harness(() => { throw error; });
    await assert.rejects(h.request(), failure => {
      assert.equal(failure.requestState, 'RESPONSE_TIMEOUT');
      assert.equal(failure.failureKind, 'NETWORK_TIMEOUT');
      assert.equal(failure.retrySafe, false); assert.equal(failure.retryAuthorized, true);
      assert.equal(failure.networkRetryCount, 2); assert.equal(failure.requestId, 'persisted-request-id');
      return true;
    });
    assert.equal(h.calls.length, 3);
  });
}

test('exhausted socket recovery is still accepted-or-unknown, never a claim of no charge', async () => {
  const h = harness(() => { throw interrupted('UND_ERR_SOCKET'); });
  await assert.rejects(h.request(), failure => {
    assert.equal(failure.requestState, 'ACCEPTED_OR_UNKNOWN');
    assert.equal(failure.retrySafe, false); assert.equal(failure.retryAuthorized, true);
    assert.equal(failure.networkRetryCount, 2); return true;
  });
  assert.equal(h.calls.length, 3);
});

test('persisted network retry count is retained across process recovery', async () => {
  const h = harness(() => { throw interrupted('ECONNRESET'); });
  await assert.rejects(h.request({ networkRetryCount: 1 }), failure => failure.networkRetryCount === 2);
  assert.equal(h.calls.length, 2);
  assert.deepEqual(h.events.filter(event => event[0] === 'record'), [['record', 2]]);
  const exhausted = harness(() => { throw interrupted('EPIPE'); });
  await assert.rejects(exhausted.request({ networkRetryCount: 2 }), failure => failure.networkRetryCount === 2);
  assert.equal(exhausted.calls.length, 1);
  assert.equal(exhausted.events.filter(event => event[0] === 'record').length, 0);
});

test('failure to persist a retry reservation prevents sleep and the next POST', async () => {
  const h = harness(() => { throw interrupted('UND_ERR_SOCKET'); });
  await assert.rejects(h.request({ recordNetworkRetry: async () => { throw Error('disk full'); } }), /disk full/);
  assert.equal(h.calls.length, 1);
  assert.equal(h.events.filter(event => event[0] === 'sleep').length, 0);
});

test('beforeAttempt failure prevents a POST and is not misclassified as a transport retry', async () => {
  const h = harness(() => generated());
  await assert.rejects(h.request({ beforeAttempt: async () => { throw Error('operation already completed'); } }), /already completed/);
  assert.equal(h.calls.length, 0);
  assert.equal(h.events.filter(event => event[0] === 'record').length, 0);
});

test('definitely-unsent connection failures retain retrySafe=true', async () => {
  const h = harness(() => { throw interrupted('ENOTFOUND'); });
  await assert.rejects(h.request(), failure => {
    assert.equal(failure.requestState, 'NOT_SENT'); assert.equal(failure.retrySafe, true);
    assert.equal(failure.networkRetryCount, 2); return true;
  });
  assert.equal(h.calls.length, 3);
});

test('an unrelated error and explicit copyright rejection never trigger network recovery', async () => {
  const unexpected = harness(() => { throw Error('invalid request body'); });
  await assert.rejects(unexpected.request(), failure => failure.retryAuthorized === false && failure.networkRetryCount === 0);
  assert.equal(unexpected.calls.length, 1);
  const copyright = harness(() => ({ ok: false, status: 400, text: async () => '{"error":{"message":"copyright restriction"}}' }));
  await assert.rejects(copyright.request(), failure => failure.failureKind === 'COPYRIGHT' && failure.networkRetryCount === 0);
  assert.equal(copyright.calls.length, 1);
});

test('a generated key remains stable when no persisted operation key was supplied', async () => {
  const h = harness((call, count) => { if (count === 1) throw interrupted('UND_ERR_SOCKET'); return generated(); });
  const result = await h.request({ idempotencyKey: undefined });
  assert.equal(result.requestId, 'generated-request-id');
  assert.ok(h.calls.every(call => call.key === result.requestId));
});
