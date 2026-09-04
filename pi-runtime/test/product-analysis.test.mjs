import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../src/server.js', import.meta.url), 'utf8');
function section(start, end) {
  const a = source.indexOf(start);
  const b = source.indexOf(end, a + start.length);
  assert.ok(a >= 0 && b > a);
  return source.slice(a, b);
}
function harness(ok = true) {
  const calls = [], timeouts = [], agents = [];
  const context = vm.createContext({
    console: { info() {}, warn() {} },
    UndiciAgent: class { constructor(options) { agents.push(options); } },
    AbortSignal: { timeout(ms) { timeouts.push(ms); return { timeout: ms }; } },
    extractJsonObject: JSON.parse,
    fetch: async (url, options) => {
      calls.push({ url, ...options, payload: JSON.parse(options.body) });
      return { ok, status: ok ? 200 : 502, text: async () => JSON.stringify({ choices: [{ message: { content: '{"valid":true}' } }] }) };
    }
  });
  vm.runInContext(
    section('const PRODUCT_ANALYSIS_TIMEOUT_MS', 'const IMAGE_RESPONSE_TIMEOUT_MS') +
    section('const GARMENT_TOPOLOGY_ANALYSIS_PROMPT', 'const MANUAL_SCRIPT') +
    section('async function requestNonStreamingChat(', 'function modelSignature(') +
    section('async function runProductFunctionAnalysis(', 'async function handleProductFunctionAnalysis('), context);
  return { context, calls, timeouts, agents };
}
const input = type => ({
  model: { modelId: 'gpt-5.4', baseUrl: 'https://unit.invalid/v1', apiKey: 'fake-test-only' },
  context: { analysisType: type }, image: { mimeType: 'image/jpeg', data: 'AA==' },
  candidateImage: { mimeType: 'image/png', data: 'AA==' }
});
test('topology uses 500 seconds, explicit transport limits and reduced output budget', async () => {
  const h = harness();
  await h.context.runProductFunctionAnalysis(input('GARMENT_TOPOLOGY_V2'));
  assert.equal(h.calls.length, 1);
  assert.deepEqual(h.timeouts, [500_000]);
  assert.equal(h.agents[0].headersTimeout, 510_000);
  assert.equal(h.agents[0].bodyTimeout, 510_000);
  assert.ok(h.calls[0].dispatcher);
  assert.equal(h.calls[0].payload.max_completion_tokens, 1800);
  assert.equal(h.calls[0].payload.reasoning_effort, 'low');
  const prompt = h.calls[0].payload.messages[0].content;
  assert.ok(prompt.length < 1500);
  for (const key of ['visibleSleeveCount', 'seamConnections', 'supportElements', 'necklinePath',
    'wearingSidePolicy', 'viewpointProjectionRule', 'fixedStructures', 'forbiddenChanges', 'confidence']) {
    assert.ok(prompt.includes('"' + key + '"'), key);
  }
});
test('validation retains two images and compact response with the same timeout', async () => {
  const h = harness();
  await h.context.runProductFunctionAnalysis(input('GARMENT_TOPOLOGY_VALIDATION_V1'));
  assert.deepEqual(h.timeouts, [500_000]);
  assert.equal(h.calls[0].payload.max_completion_tokens, 400);
  assert.equal(h.calls[0].payload.messages[1].content.filter(x => x.type === 'image_url').length, 2);
  const prompt = h.calls[0].payload.messages[0].content;
  for (const rule of ['15%', '07–09', 'violationCount', '多手', '身体侧']) assert.ok(prompt.includes(rule), rule);
});
test('upstream failure is surfaced without adding a duplicate runtime retry', async () => {
  const h = harness(false);
  await assert.rejects(h.context.runProductFunctionAnalysis(input('GARMENT_TOPOLOGY_V2')), /502/);
  assert.equal(h.calls.length, 1);
});
