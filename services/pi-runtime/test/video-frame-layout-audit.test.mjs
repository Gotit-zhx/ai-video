import test from 'node:test';
import assert from 'node:assert/strict';
import { auditVideoFrame, validateVideoFrameLayout } from '../src/video-frame-layout-audit.js';

const input = () => ({ model: { baseUrl: 'https://test.invalid/v1', modelId: 'configured-vision-model', apiKey: 'test-only' },
  image: { mimeType: 'image/jpeg', data: 'AA==' } });
function stream(reply, { finish = 'stop', done = true } = {}) {
  const content = `data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: reply }, finish_reason: null }] })}\n\n`
    + `data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: finish }] })}\n\n`
    + (done ? 'data: [DONE]\n\n' : '');
  return new Response(content, { headers: { 'Content-Type': 'text/event-stream' } });
}

test('uses the configured image model, strict streaming and explicit single-scene/window distinction', async () => {
  let request;
  const result = await auditVideoFrame(input(), { fetchImpl: async (_, options) => {
    request = JSON.parse(options.body);
    return stream('{"layout":"SINGLE_SCENE","confidence":0.94,"reason":"一个连续房间，背景窗框不是分屏"}');
  } });
  assert.deepEqual(result, { layout: 'SINGLE_SCENE', confidence: 0.94, reason: '一个连续房间，背景窗框不是分屏' });
  assert.equal(request.model, 'configured-vision-model');
  assert.equal(request.stream, true);
  assert.equal(request.max_tokens, 4096);
  assert.equal(request.messages[1].content.filter(x => x.type === 'image_url').length, 1);
  assert.match(request.messages[0].content, /窗框、瓷砖、货架/);
  assert.match(request.messages[0].content, /黑帧.*UNKNOWN/);
  assert.match(request.messages[0].content, /图片中的文字.*不能更改/);
});

test('all five layouts remain distinct and UNKNOWN is not silently promoted', async () => {
  for (const layout of ['SINGLE_SCENE', 'GRID', 'SPLIT_SCREEN', 'COLLAGE', 'UNKNOWN']) {
    const result = await auditVideoFrame(input(), { fetchImpl: async () => stream(JSON.stringify({ layout, confidence: 0.5, reason: '证据有限' })) });
    assert.equal(result.layout, layout);
    assert.equal(result.confidence, 0.5);
  }
});

test('rejects malformed JSON and strict schema violations without repair calls', async () => {
  for (const reply of ['```json\n{"layout":"SINGLE_SCENE","confidence":1,"reason":"ok"}\n```', '{}',
    '{"layout":"SINGLE_SCENE","confidence":"1","reason":"ok"}',
    '{"layout":"single_scene","confidence":1,"reason":"ok"}',
    '{"layout":"SINGLE_SCENE","confidence":1.1,"reason":"ok"}',
    '{"layout":"SINGLE_SCENE","confidence":1,"reason":""}',
    '{"layout":"SINGLE_SCENE","confidence":1,"reason":"ok","passed":true}']) {
    let calls = 0;
    await assert.rejects(auditVideoFrame(input(), { fetchImpl: async () => { calls++; return stream(reply); } }),
      error => error.failureCode === 'VIDEO_FRAME_AUDIT_INVALID_SCHEMA');
    assert.equal(calls, 1);
  }
  assert.throws(() => validateVideoFrameLayout({ layout: 'SINGLE_SCENE', confidence: NaN, reason: 'ok' }));
});

test('truncation, missing DONE, and upstream errors can never return a passing result', async () => {
  for (const options of [{ finish: 'length' }, { done: false }]) {
    await assert.rejects(auditVideoFrame(input(), { fetchImpl: async () => stream('{"layout":"SINGLE_SCENE","confidence":1,"reason":"ok"}', options) }),
      error => ['CHAT_OUTPUT_TRUNCATED', 'CHAT_INCOMPLETE_RESPONSE'].includes(error.failureCode));
  }
  let calls = 0;
  await assert.rejects(auditVideoFrame(input(), { fetchImpl: async () => {
    calls++; return new Response('{"error":{"message":"unavailable"}}', { status: 503 });
  } }), error => error.failureCode === 'CHAT_UPSTREAM_FAILED');
  assert.equal(calls, 1);
});

test('missing/invalid image fails before any model request', async () => {
  for (const image of [null, { mimeType: 'image/jpeg', data: '' }, { mimeType: 'image/jpeg', data: '!!!=' },
    { mimeType: 'text/plain', data: 'AA==' }]) {
    await assert.rejects(auditVideoFrame({ ...input(), image }, { fetchImpl: async () => assert.fail('must not call upstream') }),
      error => error.failureCode === 'VIDEO_FRAME_AUDIT_INVALID_IMAGE');
  }
});
