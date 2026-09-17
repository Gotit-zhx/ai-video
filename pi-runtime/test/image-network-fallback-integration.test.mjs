import test from "node:test";
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import vm from "node:vm";
import { ImageOperationStore } from "../src/image-operation-store.js";
import { callImageModelWithCopyrightFallback } from "../src/image-model-fallback.js";
import { supportsNativeTransparentPng } from "../src/image-output-capabilities.js";

const source = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
const helperStart = source.indexOf("function imageApiUsage(");
const helperEnd = source.indexOf("function publicErrorMessage(", helperStart);
const handlerStart = source.indexOf("const imageExecutionProgress = new Map();");
const handlerEnd = source.indexOf("async function handlePlatformImageDownload(", handlerStart);
assert.ok(helperStart >= 0 && helperEnd > helperStart && handlerStart >= 0 && handlerEnd > handlerStart);
const PRIMARY = "gpt-image-2";
const GROK = "grok-imagine-image-2.0";
const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jG9sAAAAASUVORK5CYII=";
const model = () => ({ modelId: PRIMARY, baseUrl: "https://images.example.invalid/v1", apiFormat: "OPENAI_COMPATIBLE",
  apiKey: "primary-unit-only", copyrightFallbackModels: [{ modelId: GROK,
    baseUrl: "https://images.example.invalid/v1/", apiFormat: "OPENAI_COMPATIBLE", apiKey: "backup-unit-only" }] });
const input = (id = "network-integration-001") => ({ progressRequestId: id, model: model(),
  prompt: "去掉叠加水印，保持衣服的印花和颜色。", size: "1024x1024", quality: "high", inputFidelity: "high",
  references: [{ mimeType: "image/png", dataBase64: PNG }, { mimeType: "image/png", dataBase64: PNG }] });
const disconnected = () => Object.assign(new TypeError("fetch failed"), { cause: Object.assign(new Error("socket disconnected"), { code: "UND_ERR_SOCKET" }) });
const generated = (url) => new Response(JSON.stringify({ data: [url ? { url } : { b64_json: PNG }] }), {
  status: 200, headers: { "content-type": "application/json" }
});
const plain = value => JSON.parse(JSON.stringify(value));
const recordPath = (dir, id) => path.join(dir, `${createHash("sha256").update(id).digest("hex")}.json`);
const recordFor = async (dir, id) => JSON.parse(await readFile(recordPath(dir, id), "utf8"));
async function fixture(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(), "image-network-integration-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}

function harness(dir, reply, store = new ImageOperationStore(dir)) {
  const calls = [];
  const context = vm.createContext({
    Buffer, Blob, FormData, URL, randomUUID, callImageModelWithCopyrightFallback, supportsNativeTransparentPng,
    imageOperationStore: store, INTERNAL_TOKEN: "internal-unit-only",
    AbortSignal: { timeout: () => undefined }, setTimeout: callback => callback(),
    IMAGE_FETCH_DISPATCHER: {}, IMAGE_RESPONSE_TIMEOUT_MS: 900_000,
    IMAGE_CONCURRENCY_RETRY_DELAYS_MS: [0, 0, 0, 0], IMAGE_NETWORK_RETRY_DELAYS_MS: [0, 0], IMAGE_POLICY_RETRY_DELAYS_MS: [0, 0, 0],
    readBody: async request => request.body,
    json: (response, status, body) => { response.status = status; response.body = body; },
    fetch: async (url, options = {}) => {
      // Serialize the actual multipart request locally. The provider is always mocked.
      const wire = new Request(url, { method: options.method || "GET", headers: options.headers, body: options.body });
      const call = { url, method: wire.method, key: wire.headers.get("idempotency-key"), authorization: wire.headers.get("authorization") };
      if (wire.headers.get("content-type")?.startsWith("multipart/form-data")) {
        const form = await wire.formData();
        call.fields = Object.fromEntries([...form.entries()].filter(([, value]) => typeof value === "string"));
        call.images = [];
        for (const [field, value] of form.entries()) {
          if (typeof value !== "string") call.images.push({ field, name: value.name, type: value.type, bytes: Buffer.from(await value.arrayBuffer()) });
        }
      } else if (options.body) {
        call.fields = await wire.json();
        const references = call.fields.images || (call.fields.image ? [call.fields.image] : []);
        call.images = references.map(image => ({ field: call.fields.images ? "images" : "image", type: image.url.slice(5, image.url.indexOf(";")),
          bytes: Buffer.from(image.url.slice(image.url.indexOf(",") + 1), "base64") }));
      }
      if (call.method === "POST") {
        const records = await Promise.all((await readdir(dir)).filter(file => file.endsWith(".json"))
          .map(async file => JSON.parse(await readFile(path.join(dir, file), "utf8"))));
        const record = records.find(value => Object.values(value.routes || {}).some(route => route.idempotencyKey === call.key));
        assert.ok(record, "Operation checkpoint must exist on disk before every model POST");
        const route = Object.values(record.routes).find(value => value.idempotencyKey === call.key);
        assert.equal(record.status, "RUNNING"); assert.equal(route.pending, true); assert.ok(route.attempts >= 1);
        call.persistedAttempts = route.attempts;
      }
      calls.push(call);
      return reply(call, calls.length);
    }
  });
  vm.runInContext(source.slice(helperStart, helperEnd) + "\n" + source.slice(handlerStart, handlerEnd), context);
  return {
    calls, store,
    async platform(body) {
      const response = {};
      await context.handlePlatformImageGeneration({ headers: { "x-internal-token": "internal-unit-only" }, body }, response);
      return response;
    },
    progress(id) { context.progressLookupId = id; return plain(vm.runInContext("imageExecutionProgress.get(progressLookupId)", context)); },
    eager(body) {
      return store.execute(body.progressRequestId, body, operation => context.callImageModel(body.model,
        { mode: "edit", prompt: body.prompt, size: body.size, quality: body.quality, inputFidelity: body.inputFidelity, productMockup: body.productMockup, operation }, body.references));
    }
  };
}

test('dedicated product scene unknown submission remains one paid POST through reattach and restart', async t => {
  const dir = await fixture(t);
  const body = { ...input('product-scene-unknown'), productMockup: true,
    model: { ...model(), modelId: GROK, copyrightFallbackModels: [] } };
  const h = harness(dir, () => { throw disconnected(); });
  for (let attempt = 0; attempt < 2; attempt++)
    await assert.rejects(h.platform(body), error => error.requestState === 'ACCEPTED_OR_UNKNOWN' && error.networkRetryCount === 0);
  assert.equal(h.calls.length, 1);
  const restarted = harness(dir, () => { throw Error('must not submit again'); });
  await assert.rejects(restarted.platform(body), error => error.requestState === 'ACCEPTED_OR_UNKNOWN');
  assert.equal(restarted.calls.length, 0);
});

test('dedicated product scene successful result replays without generating again', async t => {
  const dir = await fixture(t);
  const body = { ...input('product-scene-result'), productMockup: true };
  const h = harness(dir, () => generated('https://result.example.invalid/scene.png'));
  const first = await h.platform(body), second = await h.platform(body);
  assert.deepEqual(plain(second.body), plain(first.body));assert.equal(h.calls.length, 1);
  const restarted = harness(dir, () => { throw Error('cached result must not submit'); });
  assert.deepEqual(plain((await restarted.platform(body)).body), plain(first.body));assert.equal(restarted.calls.length, 0);
});

test('dedicated product scene download recovery only retries saved result GET after restart', async t => {
  const dir = await fixture(t);
  const body = { ...input('product-scene-download'), productMockup: true,
    model: { ...model(), modelId: GROK, copyrightFallbackModels: [] } };
  const url = 'https://results.example.invalid/scene.png';
  const h = harness(dir, call => call.method === 'POST' ? generated(url) : new Response('missing', { status: 404 }));
  await assert.rejects(h.eager(body), error => error.requestState === 'COMPLETED_RESPONSE_RECEIVED');
  assert.equal(h.calls.filter(call => call.method === 'POST').length, 1);
  assert.equal((await recordFor(dir, body.progressRequestId)).status, 'RECOVERABLE');
  const restarted = harness(dir, call => {
    assert.equal(call.method, 'GET');assert.equal(call.url, url);
    return new Response(Buffer.from(PNG, 'base64'), { status: 200, headers: { 'content-type': 'image/png' } });
  });
  const result = await restarted.eager(body);
  assert.equal(result.artifact.modelId, GROK);assert.equal(restarted.calls.length, 1);
});

test("strict transparent primary refusal is replayed durably with its reason and no Grok charge", async t => {
  const dir = await fixture(t);
  const body = { ...input("strict-alpha-refusal"), background: "transparent", outputFormat: "png" };
  const h = harness(dir, () => new Response(JSON.stringify({ error: { message: "copyright restriction" } }),
    { status: 400, headers: { "content-type": "application/json" } }));
  const refused = error => {
    assert.equal(error.requestState, "REJECTED");assert.equal(error.failureKind, "COPYRIGHT");
    assert.equal(error.providerStatus, 400);assert.equal(error.imageOutputRequirement.fallbackSkipped, true);
    assert.equal(error.imageModelFallback, undefined);return true;
  };
  await assert.rejects(h.platform(body), refused);
  assert.equal(h.calls.length, 1);assert.equal(h.calls[0].fields.model, PRIMARY);
  const restarted = harness(dir, () => { throw new Error("Replay must never call a model"); });
  await assert.rejects(restarted.platform(body), refused);
  assert.equal(restarted.calls.length, 0);
  const saved = await recordFor(dir, body.progressRequestId);
  assert.equal(saved.error.imageOutputRequirement.code, "NATIVE_TRANSPARENT_PNG_REQUIRED");
});

test("strict transparent unknown submission stays unknown across runtime restart without re-posting", async t => {
  const dir = await fixture(t);
  const body = { ...input("strict-alpha-unknown"), background: "transparent", outputFormat: "png" };
  const h = harness(dir, () => { throw disconnected(); });
  const unknown = error => {
    assert.equal(error.requestState, "ACCEPTED_OR_UNKNOWN");assert.equal(error.failureKind, "NETWORK");
    assert.equal(error.networkRetryCount, 0);assert.equal(error.imageModelFallback, undefined);
    assert.equal(error.imageOutputRequirement.code, "NATIVE_TRANSPARENT_PNG_REQUIRED");return true;
  };
  await assert.rejects(h.platform(body), unknown);
  assert.equal(h.calls.length, 1);
  const restarted = harness(dir, () => { throw new Error("Unknown request cannot be retried"); });
  await assert.rejects(restarted.platform(body), unknown);
  assert.equal(restarted.calls.length, 0);
});

for (const [status, payload, kind] of [
  [400, { error: { message: 'Input contains content prohibited by safety policy' } }, 'POLICY'],
  [429, { error: { message: 'Too many concurrent image requests' } }, 'CONCURRENCY'],
  [500, { error: { message: 'Request rejected', accepted: false, charged: false } }, 'PROVIDER']
]) test(`strict ${kind} rejection is one durable attempt even when the provider permits retry`, async t => {
  const dir = await fixture(t);
  const body = { ...input(`strict-durable-${kind}`), background: 'transparent', outputFormat: 'png' };
  const h = harness(dir, () => new Response(JSON.stringify(payload), { status }));
  const refused = error => {
    assert.equal(error.requestState, 'REJECTED');assert.equal(error.failureKind, kind);
    assert.equal(error.providerStatus, status);assert.equal(error.retrySafe, true);
    assert.equal(error.safeRetryCount, 0);assert.equal(error.networkRetryCount, 0);
    assert.equal(error.imageModelFallback, undefined);return true;
  };
  await assert.rejects(h.platform(body), refused);
  assert.equal(h.calls.length, 1);assert.equal(h.calls[0].persistedAttempts, 1);
  const saved = await recordFor(dir, body.progressRequestId);
  assert.equal(saved.status, 'FAILED');assert.equal(Object.keys(saved.routes).length, 1);
  assert.equal(Object.values(saved.routes)[0].attempts, 1);
  assert.equal(Object.values(saved.routes)[0].error.safeRetryCount, 0);
  const restarted = harness(dir, () => { throw Error('A new generation requires a new Java job ID'); });
  await assert.rejects(restarted.platform(body), refused);
  assert.equal(restarted.calls.length, 0);
});

test("first disconnected primary switches directly to Grok, retries Grok once, and persists unchanged inputs before every POST", async t => {
  const dir = await fixture(t);
  const body = input();
  const h = harness(dir, (_call, count) => { if (count < 3) throw disconnected(); return generated(); });
  const result = await h.platform(body);
  assert.equal(result.status, 200);
  assert.deepEqual(h.calls.map(call => call.fields.model), [PRIMARY, GROK, GROK]);
  assert.deepEqual(h.calls.map(call => call.persistedAttempts), [1, 1, 2]);
  assert.notEqual(h.calls[0].key, h.calls[1].key);
  assert.equal(h.calls[1].key, h.calls[2].key);
  assert.deepEqual(h.calls.map(call => call.authorization), ["Bearer primary-unit-only", "Bearer backup-unit-only", "Bearer backup-unit-only"]);
  for (const call of h.calls) {
    assert.equal(call.url, "https://images.example.invalid/v1/images/edits");
    if (call.fields.model === PRIMARY) {
      assert.deepEqual(call.fields, { model: PRIMARY, prompt: body.prompt, size: body.size, quality: body.quality, input_fidelity: body.inputFidelity });
      call.images.forEach((image, index) => assert.deepEqual(image,
        { field: "image[]", name: `reference-${index + 1}.png`, type: "image/png", bytes: Buffer.from(PNG, "base64") }));
    } else {
      assert.equal(call.fields.model, GROK); assert.equal(call.fields.prompt, body.prompt);
      assert.equal(call.fields.aspect_ratio, "1:1"); assert.equal(call.fields.quality, "medium");
      assert.equal(Object.hasOwn(call.fields, "image"), false);
      assert.equal(Object.hasOwn(call.fields, "image[]"), false);
      assert.equal(Object.hasOwn(call.fields, "input_fidelity"), false);
      call.images.forEach(image => assert.deepEqual(image,
        { field: "images", type: "image/png", bytes: Buffer.from(PNG, "base64") }));
    }
    assert.equal(call.images.length, 2);
  }
  assert.equal(result.body.artifact.modelId, GROK);
  assert.equal(result.body.artifact.dataBase64, PNG);
  assert.deepEqual(plain(result.body.requestMeta.fallback), { reason: "NETWORK", fromModelId: PRIMARY, toModelId: GROK });
  assert.equal(h.progress(body.progressRequestId).stage, "RESPONSE_RECEIVED");
  assert.equal(h.progress(body.progressRequestId).attempts, 3);
  const saved = await readFile(recordPath(dir, body.progressRequestId), "utf8");
  assert.equal(saved.includes(body.model.apiKey), false);
  assert.equal(saved.includes(body.model.copyrightFallbackModels[0].apiKey), false);
  const restarted = harness(dir, () => { throw Error("cached operation must not contact the provider"); });
  assert.deepEqual(plain((await restarted.platform(body)).body), plain(result.body));
  assert.equal(restarted.calls.length, 0);
  assert.equal(restarted.progress(body.progressRequestId).stage, "RESPONSE_RECEIVED");
});

test("three disconnected POSTs exhaust the whole primary-to-Grok operation even after restart", async t => {
  const dir = await fixture(t);
  const body = input();
  const h = harness(dir, () => { throw disconnected(); });
  await assert.rejects(h.platform(body), error => error.retrySafe === false && error.retryAuthorized === true
    && error.imageModelFallback?.reason === "NETWORK" && error.networkRetryCount === 1);
  assert.deepEqual(h.calls.map(call => call.fields.model), [PRIMARY, GROK, GROK]);
  const restarted = harness(dir, () => { throw Error("exhausted operation must not send again"); });
  await assert.rejects(restarted.platform(body), /fetch failed/);
  assert.equal(restarted.calls.length, 0);
});

test("concurrent handler calls join the same operation without resetting progress or letting a conflict mark it failed", async t => {
  const dir = await fixture(t);
  const body = input();
  let release, started;
  const gate = new Promise(resolve => { release = resolve; });
  const entered = new Promise(resolve => { started = resolve; });
  const h = harness(dir, async () => { started(); await gate; return generated(); });
  const first = h.platform(body);
  await entered;
  assert.equal(h.progress(body.progressRequestId).stage, "GENERATING");
  const second = h.platform(plain(body));
  await new Promise(setImmediate);
  assert.equal(h.progress(body.progressRequestId).stage, "GENERATING");
  await assert.rejects(h.platform({ ...body, prompt: "Different source instruction" }), error => error.status === 409);
  assert.equal(h.progress(body.progressRequestId).stage, "GENERATING");
  release();
  const responses = await Promise.all([first, second]);
  assert.deepEqual(plain(responses[0]), plain(responses[1]));
  assert.equal(h.calls.length, 1);
  assert.equal(h.progress(body.progressRequestId).stage, "RESPONSE_RECEIVED");
});

test("canonical key ordering and credential rotation reuse output, while changed references are rejected", async t => {
  const dir = await fixture(t);
  const body = input();
  const h = harness(dir, () => generated());
  const result = await h.platform(body);
  const reorder = value => Array.isArray(value) ? value.map(reorder) : value && typeof value === "object"
    ? Object.fromEntries(Object.entries(value).reverse().map(([key, child]) => [key, reorder(child)])) : value;
  const reordered = reorder(body);
  reordered.model.apiKey = "rotated-primary-unit-only";
  reordered.model.copyrightFallbackModels[0].apiKey = "rotated-backup-unit-only";
  const restarted = harness(dir, () => { throw Error("input ordering must not send a new model request"); });
  assert.deepEqual(plain((await restarted.platform(reordered)).body), plain(result.body));
  await assert.rejects(restarted.platform({ ...body, references: body.references.slice(0, 1) }), error => error.status === 409);
  assert.equal(restarted.calls.length, 0);
});

for (const useBackup of [false, true]) {
  test(`a ${useBackup ? "Grok" : "primary"} result-download failure reuses its received payload after restart without model switching`, async t => {
    const dir = await fixture(t);
    const body = input();
    const resultUrl = "https://results.example.invalid/received.png";
    const h = harness(dir, (call, count) => {
      if (useBackup && count === 1) throw disconnected();
      return call.method === "POST" ? generated(resultUrl) : new Response("temporarily missing", { status: 404 });
    });
    await assert.rejects(h.eager(body), error => error.requestState === "COMPLETED_RESPONSE_RECEIVED");
    assert.deepEqual(h.calls.filter(call => call.method === "POST").map(call => call.fields.model), useBackup ? [PRIMARY, GROK] : [PRIMARY]);
    assert.equal((await recordFor(dir, body.progressRequestId)).status, "RECOVERABLE");
    const restarted = harness(dir, call => {
      assert.equal(call.method, "GET"); assert.equal(call.url, resultUrl);
      return new Response(Buffer.from(PNG, "base64"), { headers: { "content-type": "image/png" } });
    });
    const recovered = await restarted.eager(body);
    assert.equal(recovered.artifact.modelId, useBackup ? GROK : PRIMARY);
    assert.equal(recovered.artifact.dataBase64, PNG);
    assert.equal(restarted.calls.length, 1);
  });
}

for (const interruptedStage of [1, 2, 3]) {
  test(`restart after POST ${interruptedStage} preserves the remaining primary-one/Grok-two transport budget`, async t => {
    const dir = await fixture(t);
    const body = input();
    const seed = new ImageOperationStore(dir);
    await seed.execute(body.progressRequestId, body, async operation => {
      const primary = await operation.forRoute(body.model, { maxNetworkRetries: 0 });
      await primary.beforeAttempt();
      if (interruptedStage === 1) return {};
      await primary.recordFailure(Object.assign(disconnected(), { requestState: "ACCEPTED_OR_UNKNOWN", failureKind: "NETWORK", retryAuthorized: true, retrySafe: false, safeRetryCount: 0, networkRetryCount: 0 }));
      const grok = await operation.forRoute({ ...body.model, modelId: GROK }, { maxNetworkRetries: 1 });
      await grok.beforeAttempt();
      if (interruptedStage === 3) { await grok.recordNetworkRetry(1); await grok.beforeAttempt(); }
      return {};
    });
    // Simulate process death after the durable pre-POST checkpoint, before its response was stored.
    const record = await recordFor(dir, body.progressRequestId);
    record.status = "RUNNING"; delete record.result;
    await seed.write(recordPath(dir, body.progressRequestId), record);
    const resumed = harness(dir, () => { throw disconnected(); });
    await assert.rejects(resumed.platform(body), error => error.failureKind === "NETWORK");
    assert.equal(resumed.calls.length, 3 - interruptedStage);
    assert.ok(resumed.calls.every(call => call.fields.model === GROK));
    const terminal = harness(dir, () => { throw Error("restart cannot replenish the network retry budget"); });
    await assert.rejects(terminal.platform(body));
    assert.equal(terminal.calls.length, 0);
  });
}

test("failure to write the pre-POST checkpoint blocks model submission and fallback", async t => {
  const dir = await fixture(t);
  const body = input();
  const store = new ImageOperationStore(dir);
  const write = store.write.bind(store);
  let writes = 0;
  store.write = async (...args) => {
    if (++writes === 2) throw Object.assign(Error("checkpoint disk full"), { code: "ENOSPC" });
    return write(...args);
  };
  const h = harness(dir, () => { throw Error("checkpoint failure must not send a POST"); }, store);
  await assert.rejects(h.platform(body), /checkpoint disk full/);
  assert.equal(h.calls.length, 0);
});

test("failure to persist a Grok retry reservation prevents the third model POST", async t => {
  const dir = await fixture(t);
  const body = input();
  const store = new ImageOperationStore(dir);
  const write = store.write.bind(store);
  store.write = async (file, value) => {
    if (Object.values(value.routes).some(route => route.networkRetryCount === 1 && !route.pending)) {
      throw Object.assign(Error("retry reservation disk full"), { code: "ENOSPC" });
    }
    return write(file, value);
  };
  const h = harness(dir, () => { throw disconnected(); }, store);
  await assert.rejects(h.platform(body), /retry reservation disk full/);
  assert.deepEqual(h.calls.map(call => call.fields.model), [PRIMARY, GROK]);
});

test("a received response survives transient checkpoint failure and is recovered without another POST", async t => {
  const dir = await fixture(t);
  const body = input();
  const store = new ImageOperationStore(dir);
  const write = store.write.bind(store);
  let failed = false;
  store.write = async (file, value) => {
    if (!failed && value.status === "RUNNING" && Object.values(value.routes).some(route => route.response)) {
      failed = true; throw Object.assign(Error("result checkpoint temporarily unavailable"), { code: "ENOSPC" });
    }
    return write(file, value);
  };
  const h = harness(dir, () => generated("https://results.example.invalid/saved.png"), store);
  await assert.rejects(h.platform(body), /result checkpoint temporarily unavailable/);
  assert.equal(h.calls.length, 1);
  assert.equal((await recordFor(dir, body.progressRequestId)).status, "RECOVERABLE");
  const restarted = harness(dir, () => { throw Error("received result must be reused"); });
  const recovered = await restarted.platform(body);
  assert.equal(recovered.body.result.url, "https://results.example.invalid/saved.png");
  assert.equal(restarted.calls.length, 0);
});

for (const status of [502, 504]) {
  test(`HTTP ${status} gateway interruptions use the same primary-to-Grok budget and retain unknown-charge metadata`, async t => {
    const dir = await fixture(t);
    const body = input();
    const h = harness(dir, () => new Response(JSON.stringify({ error: { type: "server_error", message: "upstream connection reset before response headers" } }), { status }));
    await assert.rejects(h.platform(body), error => {
      assert.equal(error.failureCode, `IMAGE_GATEWAY_HTTP_${status}`);
      assert.equal(error.providerStatus, status);
      assert.equal(error.failureKind, status === 504 ? "NETWORK_TIMEOUT" : "NETWORK");
      assert.equal(error.requestState, status === 504 ? "RESPONSE_TIMEOUT" : "ACCEPTED_OR_UNKNOWN");
      assert.equal(error.retrySafe, false); assert.equal(error.retryAuthorized, true);
      assert.equal(error.networkRetryCount, 1); assert.equal(error.imageModelFallback.reason, "NETWORK");
      return true;
    });
    assert.deepEqual(h.calls.map(call => call.fields.model), [PRIMARY, GROK, GROK]);
    assert.equal(h.calls[1].key, h.calls[2].key);
    assert.equal(h.progress(body.progressRequestId).stage, "FAILED");
    assert.equal(h.progress(body.progressRequestId).attempts, 3);
    const record = await recordFor(dir, body.progressRequestId);
    assert.equal(record.status, "FAILED");
    for (const route of Object.values(record.routes)) {
      assert.equal(route.error.failureCode, `IMAGE_GATEWAY_HTTP_${status}`);
      assert.equal(route.error.providerStatus, status); assert.equal(route.error.retrySafe, false);
      assert.equal(route.response, undefined);
    }
    const restarted = harness(dir, () => { throw Error("Gateway retry exhaustion must survive restart"); });
    await assert.rejects(restarted.platform(body), error => error.failureCode === `IMAGE_GATEWAY_HTTP_${status}`);
    assert.equal(restarted.calls.length, 0);
  });
}

test("HTML gateway failure then a normally-framed truncated JSON body recover on Grok without caching unusable payloads", async t => {
  const dir = await fixture(t);
  const body = input();
  const h = harness(dir, (_call, count) => count === 1
    ? new Response("<html>Bad Gateway</html>", { status: 502 })
    : count === 2 ? new Response('{"data":[{"b64_json":"truncated', { status: 200 }) : generated());
  const result = await h.platform(body);
  assert.deepEqual(h.calls.map(call => call.fields.model), [PRIMARY, GROK, GROK]);
  assert.equal(result.body.artifact.modelId, GROK);
  assert.equal(result.body.artifact.dataBase64, PNG);
  assert.equal(h.progress(body.progressRequestId).attempts, 3);
  const record = await recordFor(dir, body.progressRequestId);
  const primary = Object.values(record.routes).find(route => route.error);
  const backup = Object.values(record.routes).find(route => route.response);
  assert.equal(primary.error.failureCode, "IMAGE_GATEWAY_HTTP_502");
  assert.equal(primary.response, undefined);
  assert.equal(backup.attempts, 2); assert.equal(backup.networkRetryCount, 1);
  assert.equal(backup.response.payload.data[0].b64_json, PNG);
  const restarted = harness(dir, () => { throw Error("Successful fallback should replay the saved image"); });
  assert.deepEqual(plain((await restarted.platform(body)).body), plain(result.body));
  assert.equal(restarted.calls.length, 0);
});

for (const responseBody of ['{"data":[', ""]) {
  test(`unparseable 2xx JSON ${responseBody ? "truncated" : "empty"} exhausts only the network budget and is never saved as a successful response`, async t => {
    const dir = await fixture(t);
    const body = input();
    const h = harness(dir, () => new Response(responseBody, { status: 200 }));
    await assert.rejects(h.platform(body), error => error.failureCode === "IMAGE_RESPONSE_INVALID_JSON"
      && error.requestState === "ACCEPTED_OR_UNKNOWN" && error.retrySafe === false && error.retryAuthorized === true);
    assert.deepEqual(h.calls.map(call => call.fields.model), [PRIMARY, GROK, GROK]);
    const record = await recordFor(dir, body.progressRequestId);
    assert.equal(record.status, "FAILED");
    assert.ok(Object.values(record.routes).every(route => route.response === undefined));
    const restarted = harness(dir, () => { throw Error("Malformed-response recovery budget must not reset"); });
    await assert.rejects(restarted.platform(body), error => error.failureCode === "IMAGE_RESPONSE_INVALID_JSON");
    assert.equal(restarted.calls.length, 0);
  });
}

for (const [status, payload, expectedKind, expectedCalls] of [
  [502, { error: { type: "invalid_request_error", code: "invalid_image", message: "Unsupported image dimensions" } }, "PROVIDER", 1],
  [504, { error: { type: "authentication_error", message: "Invalid API key" } }, "PROVIDER", 1],
  [502, { error: { message: "Input contains content prohibited by safety policy" } }, "POLICY", 4],
  [504, { message: "Copyrighted character cannot be generated" }, "PROVIDER", 1]
]) {
  test(`explicit ${expectedKind} refusal carried by HTTP ${status} does not become a network model switch`, async t => {
    const dir = await fixture(t);
    const body = input();
    const h = harness(dir, () => new Response(JSON.stringify(payload), { status }));
    await assert.rejects(h.platform(body), error => error.requestState === "REJECTED" && error.failureKind === expectedKind
      && error.imageModelFallback === undefined);
    assert.equal(h.calls.length, expectedCalls);
    assert.ok(h.calls.every(call => call.fields.model === PRIMARY));
  });
}

test("valid JSON with no image remains a provider-result problem and does not trigger transport fallback", async t => {
  const dir = await fixture(t);
  const body = input();
  const h = harness(dir, () => new Response('{"data":[]}', { status: 200 }));
  await assert.rejects(h.platform(body), error => error.requestState === "COMPLETED_RESPONSE_RECEIVED"
    && error.imageModelFallback === undefined);
  assert.equal(h.calls.length, 1);
  const record = await recordFor(dir, body.progressRequestId);
  assert.equal(record.status, "RECOVERABLE");
  assert.deepEqual(Object.values(record.routes)[0].response.payload, { data: [] });
});
