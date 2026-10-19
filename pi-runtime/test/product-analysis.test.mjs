import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  GARMENT_TOPOLOGY_VALIDATION_MAX_TOKENS,
  GARMENT_TOPOLOGY_VALIDATION_PROMPT
} from '../src/garment-topology-validation.js';
import { requestChatCompletion } from '../src/chat-completion-transport.js';
import { POSTER_STYLE, POSTER_REVIEW } from '../src/product-main-image-style.js';

const source = readFileSync(new URL('../src/server.js', import.meta.url), 'utf8');
function section(start, end) {
  const a = source.indexOf(start);
  const b = source.indexOf(end, a + start.length);
  assert.ok(a >= 0 && b > a);
  return source.slice(a, b);
}
function harness(ok = true) {
  const calls = [], timeouts = [], agents = [], posterCalls = [];
  const context = vm.createContext({
    GARMENT_TOPOLOGY_VALIDATION_MAX_TOKENS,
    GARMENT_TOPOLOGY_VALIDATION_PROMPT,
    POSTER_STYLE,
    POSTER_REVIEW,
    runPosterAnalysis: async (...args) => {
      posterCalls.push(args);
      return { poster: true };
    },
    console: { info() {}, warn() {} },
    UndiciAgent: class { constructor(options) { agents.push(options); } },
    extractJsonObject: JSON.parse,
    requestChatCompletion: options => {
      timeouts.push(options.timeoutMs);
      return requestChatCompletion({ ...options, fetchImpl: async (url, request) => {
        calls.push({ url, ...request, payload: JSON.parse(request.body) });
        return { ok, status: ok ? 200 : 502, text: async () => JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content: '{"valid":true}' } }] }) };
      } });
    }
  });
  vm.runInContext(
    section('const PRODUCT_ANALYSIS_TIMEOUT_MS', 'const IMAGE_RESPONSE_TIMEOUT_MS') +
    section('const PRODUCT_FUNCTION_ANALYSIS_PROMPT', 'const MANUAL_SCRIPT') +
    section('function imageApiUsage(', 'function imageEndpoint(') +
    section('async function requestAnalysisChat(', 'function modelSignature(') +
    section('async function runProductFunctionAnalysis(', 'async function handleProductFunctionAnalysis('), context);
  return { context, calls, timeouts, agents, posterCalls };
}
const input = type => ({
  model: { modelId: 'gpt-5.4', baseUrl: 'https://unit.invalid/v1', apiKey: 'fake-test-only' },
  context: { analysisType: type }, image: { mimeType: 'image/jpeg', data: 'AA==' },
  candidateImage: { mimeType: 'image/png', data: 'AA==' }
});

test('poster extraction and review dispatch to their dedicated analyzer', async () => {
  for (const type of [POSTER_STYLE, POSTER_REVIEW]) {
    const h = harness();
    const body = input(type);
    assert.deepEqual(await h.context.runProductFunctionAnalysis(body), { poster: true });
    assert.equal(h.posterCalls.length, 1);
    assert.equal(h.posterCalls[0][0], body);
    assert.equal(typeof h.posterCalls[0][1], 'function');
    assert.equal(h.posterCalls[0][2], JSON.parse);
    assert.equal(h.calls.length, 0);
  }
});
test('topology is bounded to 180 seconds and retains its structure output budget', async () => {
  const h = harness();
  await h.context.runProductFunctionAnalysis(input('GARMENT_TOPOLOGY_V2'));
  assert.equal(h.calls.length, 1);
  assert.deepEqual(h.timeouts, [180_000]);
  assert.equal(h.agents[0].headersTimeout, 190_000);
  assert.equal(h.agents[0].bodyTimeout, 190_000);
  assert.ok(h.calls[0].dispatcher);
  assert.equal(h.calls[0].payload.max_tokens, 1800);
  assert.equal(h.calls[0].payload.reasoning_effort, undefined);
  const prompt = h.calls[0].payload.messages[0].content;
  assert.ok(prompt.length < 1700);
  assert.ok(prompt.includes("必须是非空字符串数组"));
  assert.ok(prompt.includes("不能省略字段、用 null 或空数组代替"));
  for (const key of ['visibleSleeveCount', 'seamConnections', 'supportElements', 'necklinePath',
    'wearingSidePolicy', 'viewpointProjectionRule', 'fixedStructures', 'forbiddenChanges', 'confidence']) {
    assert.ok(prompt.includes('"' + key + '"'), key);
  }
});
test('validation retains both images and stops waiting after 90 seconds', async () => {
  const h = harness();
  await h.context.runProductFunctionAnalysis(input('GARMENT_TOPOLOGY_VALIDATION_V1'));
  assert.deepEqual(h.timeouts, [90_000]);
  assert.equal(h.calls[0].payload.max_tokens, GARMENT_TOPOLOGY_VALIDATION_MAX_TOKENS);
  assert.equal(h.calls[0].payload.messages[1].content.filter(x => x.type === 'image_url').length, 2);
  const prompt = h.calls[0].payload.messages[0].content;
  for (const rule of ['15%', '第 07、08、09 格', 'violationCount', '额外手掌', '身体侧']) assert.ok(prompt.includes(rule), rule);
});
test('upstream failure is surfaced without adding a duplicate runtime retry', async () => {
  const h = harness(false);
  await assert.rejects(h.context.runProductFunctionAnalysis(input('GARMENT_TOPOLOGY_V2')), /502/);
  assert.equal(h.calls.length, 1);
});
test('daily-use product analysis uses the same transport and keeps its image and 1400-token budget', async () => {
  const h = harness();
  await h.context.runProductFunctionAnalysis(input('PRODUCT_FUNCTION_V1'));
  assert.deepEqual(h.timeouts, [120_000]);
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].payload.max_tokens, 1400);
  assert.equal(h.calls[0].payload.stream, true);
  assert.equal(h.calls[0].payload.stream_options, undefined);
  assert.equal(h.calls[0].payload.reasoning_effort, undefined);
  assert.equal(h.calls[0].payload.messages[1].content.filter(x => x.type === 'image_url').length, 1);
});
