import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { STYLE_GUIDE_TYPE, STYLE_MATCH_TYPE, POSE_ONLY_POLICY, POSE_ONLY_MATCH_PROMPT, STYLE_IDLE_TIMEOUT_MS, STYLE_FALLBACK_IDLE_TIMEOUT_MS, STYLE_GUIDE_PROMPT, STYLE_MATCH_PROMPT,
  validateStyleGuide, validateStyleMatch, runCommerceModelDetailStyleAnalysis } from '../src/commerce-model-detail-style.js';

const guide = () => ({ shotType: 'SEATED_MODEL', composition: '人物居中半身坐姿', pose: '咖啡桌旁坐着，身体略向右',
  setting: '街边咖啡馆、圆桌和椅子', lighting: '自然侧光，温暖柔和', hasVisiblePrint: true,
  regions: [{ x: .3, y: .3, width: .3, height: .4 }, { x: .12, y: .42, width: .12, height: .13 }], complete: true });
const match = () => ({ matchesShotType: true, matchesPose: true, matchesSetting: true, reason: '保留咖啡馆坐姿与中景，新商品穿着自然' });
const input = type => ({ model: { apiKey: 'test', modelId: 'vision' }, image: { mimeType: 'image/jpeg', data: 'AA==' }, context: { analysisType: type } });

test('guide request keeps original photograph role, idle-only timeout and one read-only model call', async () => {
  const calls = [];
  const result = await runCommerceModelDetailStyleAnalysis(input(STYLE_GUIDE_TYPE), async (...args) => { calls.push(args); return JSON.stringify(guide()); }, JSON.parse);
  assert.deepEqual(result.result, guide()); assert.equal(calls.length, 1);
  assert.equal(calls[0][2], STYLE_GUIDE_PROMPT); assert.equal(calls[0][6], undefined);
  assert.equal(STYLE_IDLE_TIMEOUT_MS, 120_000); assert.equal(STYLE_FALLBACK_IDLE_TIMEOUT_MS, 90_000); assert.equal(JSON.parse(calls[0][3]).imageRole, 'ORIGINAL_STYLE');
  assert.deepEqual(calls[0][7], { reasoningEffort: 'low', activityTimeoutOnly: true, idleTimeoutMs: 120_000, fallbackIdleTimeoutMs: 90_000, fallbackOnPartialResponse: true });
  assert.deepEqual(calls[0][4], [{ type: 'image', mimeType: 'image/jpeg', data: 'AA==' }]);
});

test('sleeve print and separate lettering remain independent normalized regions', () => {
  const result = validateStyleGuide(guide()); assert.equal(result.regions.length, 2);
  assert.notEqual(result.regions, guide().regions);
  assert.equal(result.shotType, 'SEATED_MODEL');
  const plain = { ...guide(), hasVisiblePrint: false, regions: [] }; assert.deepEqual(validateStyleGuide(plain), plain);
  const overlap = { ...guide(), regions: Array.from({ length: 8 }, () => ({ x: .1, y: .1, width: .5, height: .5 })) };
  assert.equal(validateStyleGuide(overlap).regions.length, 8, 'union area is checked instead of incorrectly summing overlap');
});

test('only explicit print macro permits full-frame removal while retaining close-up guidance', () => {
  const macro = { ...guide(), shotType: 'PRINT_MACRO', regions: [{ x: 0, y: 0, width: 1, height: 1 }] };
  assert.deepEqual(validateStyleGuide(macro), macro);
  assert.throws(() => validateStyleGuide({ ...macro, shotType: 'STANDING_MODEL' }));
  assert.throws(() => validateStyleGuide({ ...guide(), shotType: 'PRINT_MACRO' }));
  assert.throws(() => validateStyleGuide({ ...macro, hasVisiblePrint: false, regions: [] }));
});

test('malformed coordinates, wide masks and missing certainty cannot silently blur the scene', () => {
  for (const regions of [null, [], Array.from({ length: 9 }, () => ({ x: .1, y: .1, width: .1, height: .1 })),
    [{ x: -.01, y: 0, width: .1, height: .1 }], [{ x: .9, y: .9, width: .2, height: .2 }],
    [{ x: 0, y: 0, width: 0, height: .1 }], [{ x: 0, y: 0, width: '0.1', height: .1 }],
    [{ x: NaN, y: 0, width: .1, height: .1 }], [{ x: 0, y: 0, width: Infinity, height: .1 }],
    [{ x: 0, y: 0, width: .8, height: .8 }],
    [{ x: 0, y: 0, width: .5, height: .8 }, { x: .5, y: 0, width: .5, height: .8 }]]) {
    assert.throws(() => validateStyleGuide({ ...guide(), regions }));
  }
  for (const invalid of [{ ...guide(), complete: false }, { ...guide(), complete: 'true' }, { ...guide(), hasVisiblePrint: 'true' },
    { ...guide(), shotType: 'UNKNOWN' }, { ...guide(), lighting: '' }, { ...guide(), pose: 'x'.repeat(181) }, { ...guide(), extra: true }])
    assert.throws(() => validateStyleGuide(invalid));
});

test('style comparison preserves strict independent verdicts and fixed left/right order', async () => {
  const calls = []; const failed = { ...match(), matchesPose: false, reason: '原图坐姿被改成站姿' };
  const result = await runCommerceModelDetailStyleAnalysis(input(STYLE_MATCH_TYPE), async (...args) => { calls.push(args); return JSON.stringify(failed); }, JSON.parse);
  assert.deepEqual(result.result, failed); assert.equal(calls[0][2], STYLE_MATCH_PROMPT);
  assert.equal(calls[0][6], undefined); assert.deepEqual(calls[0][7], { reasoningEffort: 'low', activityTimeoutOnly: true, idleTimeoutMs: 120_000, fallbackIdleTimeoutMs: 90_000, fallbackOnPartialResponse: true });
  assert.deepEqual(JSON.parse(calls[0][3]).comparisonOrder, ['LEFT: ORIGINAL', 'RIGHT: OUTPUT']);
  for (const key of ['matchesShotType', 'matchesPose', 'matchesSetting']) {
    for (const bad of ['true', 1, null]) assert.throws(() => validateStyleMatch({ ...match(), [key]: bad }));
    const missing = match(); delete missing[key]; assert.throws(() => validateStyleMatch(missing));
  }
  assert.throws(() => validateStyleMatch({ ...match(), passed: true }));
  assert.throws(() => validateStyleMatch({ ...match(), reason: '' }));
});

test('prompts preserve seated, standing and worn close-up differences without retaining old garment designs', () => {
  for (const rule of ['咖啡馆坐姿', '街头站姿', '印花局部近景', '不写旧服装图案', '袖子', '不框整个人', '0.65', '不能猜测'])
    assert.ok(STYLE_GUIDE_PROMPT.includes(rule), rule);
  for (const rule of ['不要求同一个人', '新商品图案', 'PRINT_MACRO', '不能变成全身站姿', '不强制凭空复刻不存在的姿态', '近景并让服装展示占主导'])
    assert.ok(STYLE_MATCH_PROMPT.includes(rule), rule);
});

test('invalid inputs and malformed responses stop without retrying the model internally', async () => {
  const invalid = { statusCode: 422, failureCode: 'COMMERCE_MODEL_DETAIL_STYLE_INVALID' };
  for (const image of [null, { mimeType: 'image/jpeg', data: 'AB==' }, { mimeType: 'text/plain', data: 'AA==' }])
    await assert.rejects(runCommerceModelDetailStyleAnalysis({ ...input(STYLE_GUIDE_TYPE), image }, () => assert.fail('model must not run'), JSON.parse), invalid);
  for (const reply of ['broken', '{}', JSON.stringify({ ...guide(), complete: false })]) {
    let calls = 0;
    await assert.rejects(runCommerceModelDetailStyleAnalysis(input(STYLE_GUIDE_TYPE), async () => { calls++; return reply; }, JSON.parse), invalid);
    assert.equal(calls, 1);
  }
  const error = Error('network disconnected'); let calls = 0;
  await assert.rejects(runCommerceModelDetailStyleAnalysis(input(STYLE_MATCH_TYPE), async () => { calls++; throw error; }, JSON.parse), error);
  assert.equal(calls, 1);
});

test('local geometric and verdict validation failures return 422 instead of a retryable upstream failure', () => {
  const invalid = { statusCode: 422, failureCode: 'COMMERCE_MODEL_DETAIL_STYLE_INVALID' };
  assert.throws(() => validateStyleGuide({ ...guide(), regions: [{ x: 0, y: 0, width: 1, height: 1 }] }), invalid);
  assert.throws(() => validateStyleGuide({ ...guide(), regions: [{ x: NaN, y: 0, width: .1, height: .1 }] }), invalid);
  assert.throws(() => validateStyleMatch({ ...match(), matchesPose: 'true' }), invalid);
  assert.equal(validateStyleMatch({ ...match(), matchesPose: false }).matchesPose, false);
});

test('analysis helper passes style reasoning hint while preserving defaults for other analysis callers', async () => {
  const source = readFileSync(new URL('../src/server.js', import.meta.url), 'utf8');
  const start = source.indexOf('async function requestAnalysisChat('), end = source.indexOf('async function requestVideoScriptNonStreaming(', start);
  const calls = [], dispatcher = {};
  const ctx = vm.createContext({ PRODUCT_ANALYSIS_DISPATCHER: dispatcher,
    requestChatCompletion: async options => { calls.push(options); return { reply: JSON.stringify(guide()) }; } });
  vm.runInContext(source.slice(start, end), ctx);
  const body = input(STYLE_GUIDE_TYPE);
  const frames = [{ type: 'image', mimeType: body.image.mimeType, data: body.image.data }];
  await ctx.requestAnalysisChat(body.model, body.model.apiKey, 'system', 'prompt', frames);
  assert.equal(calls[0].reasoningEffort, undefined); assert.equal(calls[0].activityTimeoutOnly, undefined); assert.equal(calls[0].idleTimeoutMs, undefined); assert.equal(calls[0].fallbackIdleTimeoutMs, undefined); assert.equal(calls[0].fallbackOnPartialResponse, undefined); assert.equal(calls[0].timeoutMs, 180_000);
  assert.equal(calls[0].maxTokens, 6500); assert.equal(calls[0].dispatcher, dispatcher);
  await runCommerceModelDetailStyleAnalysis(body, ctx.requestAnalysisChat, JSON.parse);
  assert.equal(calls[1].reasoningEffort, 'low'); assert.equal(calls[1].timeoutMs, 180_000, "legacy argument is ignored by activity-only transport");
  assert.equal(calls[1].activityTimeoutOnly, true); assert.equal(calls[1].idleTimeoutMs, 120_000); assert.equal(calls[1].fallbackIdleTimeoutMs, 90_000); assert.equal(calls[1].fallbackOnPartialResponse, true);
  assert.equal(calls[1].maxTokens, 2200); assert.equal(calls[1].config, body.model);
  assert.equal(calls[1].messages[1].content[1].image_url.url, 'data:image/jpeg;base64,AA==');
  assert.equal(calls[1].dispatcher, dispatcher);
});

test('product analysis endpoint dispatches both guide and match contracts independently', async () => {
  const source = readFileSync(new URL('../src/server.js', import.meta.url), 'utf8');
  assert.ok(source.includes('import { runCommerceModelDetailStyleAnalysis } from "./commerce-model-detail-style.js";'));
  const start = source.indexOf('async function runProductFunctionAnalysis('), end = source.indexOf('async function handleProductFunctionAnalysis(', start);
  const calls = []; const ctx = vm.createContext({ runCommerceModelDetailStyleAnalysis,
    requestAnalysisChat: async (...args) => { calls.push(args); return JSON.stringify(args[2] === STYLE_GUIDE_PROMPT ? guide() : match()); }, extractJsonObject: JSON.parse });
  vm.runInContext(source.slice(start, end), ctx);
  assert.deepEqual((await ctx.runProductFunctionAnalysis(input(STYLE_GUIDE_TYPE))).result, guide());
  assert.deepEqual((await ctx.runProductFunctionAnalysis(input(STYLE_MATCH_TYPE))).result, match());
  assert.equal(calls.length, 2);
});


test('pose-only policy uses its own contract and does not require copying clothes or advertising', async () => {
  const body = input(STYLE_MATCH_TYPE); body.context.renderPolicy = POSE_ONLY_POLICY;
  const calls = [];
  assert.deepEqual((await runCommerceModelDetailStyleAnalysis(body, async (...args) => {
    calls.push(args); return JSON.stringify(match());
  }, JSON.parse)).result, match());
  assert.equal(calls[0][2], POSE_ONLY_MATCH_PROMPT);
  const sent = JSON.parse(calls[0][3]); assert.equal(sent.context.renderPolicy, POSE_ONLY_POLICY);
  for (const rule of ['仅为原商品的姿势参考', '手持转实穿', '多人转单人', '背景和光线可变化', '不复制手持平铺']) assert.ok(POSE_ONLY_MATCH_PROMPT.includes(rule), rule);
  body.context.renderPolicy = 'UNKNOWN';
  await assert.rejects(runCommerceModelDetailStyleAnalysis(body, async () => { throw new Error('must not call'); }, JSON.parse), /不支持/);
  body.context.analysisType = STYLE_GUIDE_TYPE; body.context.renderPolicy = POSE_ONLY_POLICY;
  await assert.rejects(runCommerceModelDetailStyleAnalysis(body, async () => { throw new Error('must not call'); }, JSON.parse), /不支持/);
});
