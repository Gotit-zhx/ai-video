import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";
import { callImageModelWithCopyrightFallback } from "../src/image-model-fallback.js";
import { supportsNativeTransparentPng } from "../src/image-output-capabilities.js";

const source = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
const start = source.indexOf("function imageApiUsage(");
const end = source.indexOf("function publicErrorMessage(", start);
assert.ok(start >= 0 && end > start, "load the actual image HTTP request/response functions");
const serverFunctions = source.slice(start, end);
const GROK = "grok-imagine-image-2.0";
const PRIMARY = "gpt-image-2";
const PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jG9sAAAAASUVORK5CYII=";
const OUTPUT_BYTES = Buffer.from(PNG_BASE64, "base64");
const modelConfig = () => ({
  modelId: PRIMARY,
  baseUrl: "https://images.unit.invalid/v1",
  apiFormat: "OPENAI_COMPATIBLE",
  apiKey: "unit-primary-key",
  copyrightFallbackModels: [{
    modelId: GROK,
    baseUrl: "https://images.unit.invalid/v1/",
    apiFormat: "OPENAI_COMPATIBLE",
    apiKey: "unit-fallback-key"
  }]
});
const inputImages = [{ mimeType: "image/png", dataBase64: PNG_BASE64 }];
const response = (status, payload) => new Response(JSON.stringify(payload), {
  status, headers: { "content-type": "application/json" }
});
const generatedResponse = () => response(200, {
  data: [{ b64_json: PNG_BASE64, revised_prompt: "Provider output prompt" }],
  usage: { input_tokens: 10, output_tokens: 20, total_tokens: 30 }
});

test("explicit input fidelity is preserved for GPT image edits and configured models", async () => {
  for (const modelId of ["gpt-image-2", "gpt-image-2-2026-04-21", "gpt-image-1.5", "custom-image-model"]) {
    const h = harness(() => generatedResponse());
    await h.call({ ...modelConfig(), modelId }, {
      mode: "edit", prompt: "Preserve the reference.", size: "1024x1024", inputFidelity: "high"
    }, inputImages);
    assert.equal(h.calls[0].fields.input_fidelity, "high");
    assert.equal(h.calls.length, 1);
  }
});

function harness(reply) {
  const calls = [];
  const context = vm.createContext({
    Buffer, FormData, Blob, URL, randomUUID,
    callImageModelWithCopyrightFallback, supportsNativeTransparentPng,
    AbortSignal: { timeout: () => undefined },
    setTimeout: (fn) => fn(),
    IMAGE_FETCH_DISPATCHER: {}, IMAGE_RESPONSE_TIMEOUT_MS: 900_000,
    IMAGE_CONCURRENCY_RETRY_DELAYS_MS: [0, 0, 0, 0],
    IMAGE_NETWORK_RETRY_DELAYS_MS: [0, 0],
    IMAGE_POLICY_RETRY_DELAYS_MS: [0, 0, 0],
    fetch: async (url, options = {}) => {
      // Serialize and parse actual Request multipart bytes; no network request is made.
      const wireRequest = new Request(url, { method: options.method || "GET", headers: options.headers, body: options.body });
      const contentType = wireRequest.headers.get("content-type") || "";
      const call = {
        url, method: wireRequest.method,
        authorization: wireRequest.headers.get("authorization"),
        geminiKey: wireRequest.headers.get("x-goog-api-key"),
        idempotencyKey: wireRequest.headers.get("idempotency-key"), contentType
      };
      if (contentType.startsWith("multipart/form-data")) {
        const parsed = await wireRequest.formData();
        call.fields = Object.fromEntries([...parsed.entries()].filter(([, value]) => typeof value === "string"));
        call.images = [];
        for (const [field, value] of parsed.entries()) {
          if (typeof value !== "string") call.images.push({ field, fileName: value.name, mimeType: value.type, bytes: Buffer.from(await value.arrayBuffer()) });
        }
      } else if (options.body) {
        call.json = await wireRequest.json();
      }
      calls.push(call);
      return reply(call, calls.length);
    }
  });
  vm.runInContext(serverFunctions, context);
  return { calls, call: (config, params, images = inputImages) => context.callImageModel(config, params, images) };
}

const BANANA = "gemini-nano-banana-2.1";
const bananaConfig = () => ({ modelId: BANANA, apiFormat: "OPENAI_COMPATIBLE", apiKey: "banana-unit-key",
  baseUrl: `https://images.unit.invalid/v1beta/models/${BANANA}:generateContent`, copyrightFallbackModels: [] });
const bananaPayload = () => ({ candidates: [{ finishReason: "STOP", content: { parts: [
  { text: "Generated image" }, { inlineData: { mimeType: "image/png", data: PNG_BASE64 } }
] } }], usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 20, totalTokenCount: 30 } });

for (const count of [0, 1, 2, 4]) test(`Banana native request keeps ${count} ordered references and original output bytes`, async () => {
  const h = harness(() => response(200, bananaPayload()));
  const originals = Array.from({ length: count }, (_, i) => ({ mimeType: "image/jpeg",
    dataBase64: Buffer.concat([OUTPUT_BYTES, Buffer.from([i])]).toString("base64") }));
  const result = await h.call(bananaConfig(), { mode: count ? "edit" : "generate", prompt: "Exact prompt",
    size: "1024x1024", quality: "high", inputFidelity: "high", streamImages: true, singleProviderAttempt: true }, originals);
  assert.equal(h.calls.length, 1);
  const call = h.calls[0], wire = call.json;
  assert.equal(call.url, bananaConfig().baseUrl);
  assert.equal(call.authorization, null); assert.equal(call.geminiKey, "banana-unit-key");
  assert.equal(call.contentType, "application/json");
  assert.deepEqual(wire.generationConfig, { responseModalities: ["TEXT", "IMAGE"], imageConfig: { aspectRatio: "1:1" } });
  assert.equal(wire.contents[0].parts[0].text, "Exact prompt");
  assert.equal(wire.contents[0].parts.length, count + 1);
  originals.forEach((reference, index) => assert.deepEqual(wire.contents[0].parts[index + 1],
    { inlineData: { mimeType: "image/png", data: reference.dataBase64 } }));
  for (const field of ["model", "size", "quality", "input_fidelity", "stream", "image", "images"])
    assert.equal(Object.hasOwn(wire, field), false);
  assert.equal(result.artifact.dataBase64, PNG_BASE64);
  assert.equal(result.artifact.modelId, BANANA);
  assert.equal(result.usage.totalTokens, 30);
  assert.equal(JSON.stringify(result).includes("banana-unit-key"), false);
});

for (const [size, imageConfig] of [
  ["2160x3840", { aspectRatio: "9:16", imageSize: "4K" }],
  ["2048x2048", { aspectRatio: "1:1", imageSize: "2K" }],
  ["1024x1536", { aspectRatio: "2:3" }]
]) test(`Banana maps ${size} without downgrading video resolution`, async () => {
  const h = harness(() => response(200, bananaPayload()));
  await h.call(bananaConfig(), { mode: "edit", prompt: "Video reference", size });
  assert.deepEqual(h.calls[0].json.generationConfig.imageConfig, imageConfig);
});

test("Banana roots normalize to native endpoints and mismatched models fail before a POST", async () => {
  for (const base of ["https://images.unit.invalid", "https://images.unit.invalid/v1beta/"]) {
    const h = harness(() => response(200, bananaPayload()));
    await h.call({ ...bananaConfig(), baseUrl: base }, { mode: "generate", prompt: "Single image" }, []);
    assert.equal(h.calls[0].url, bananaConfig().baseUrl);
  }
  const h = harness(() => { throw new Error("Must not submit"); });
  await assert.rejects(h.call({ ...bananaConfig(), modelId: "gemini-wrong-model" },
    { mode: "edit", prompt: "Single image" }), /模型名称不一致/);
  assert.equal(h.calls.length, 0);
});

test("Banana reuses a saved native response without another paid POST", async () => {
  const h = harness(() => { throw new Error("Must not resubmit"); });
  const operation = { forRoute: async () => ({ cachedResponse: {
    payload: bananaPayload(), safeRetryCount: 0, networkRetryCount: 0, requestId: "saved" } }) };
  const result = await h.call(bananaConfig(), { mode: "edit", prompt: "Saved output", operation });
  assert.equal(result.artifact.dataBase64, PNG_BASE64); assert.equal(h.calls.length, 0);
});

test("Banana ignores thought images and cannot report text-only or invalid output as success", async () => {
  for (const parts of [[{ text: "No image" }], [{ thought: true, inlineData: { data: PNG_BASE64 } }],
    [{ inlineData: { data: "bm90LWFuLWltYWdl" } }]]) {
    const h = harness(() => response(200, { candidates: [{ finishReason: "STOP", content: { parts } }] }));
    await assert.rejects(h.call(bananaConfig(), { mode: "edit", prompt: "Single image", singleProviderAttempt: true }),
      error => error.requestState === "COMPLETED_RESPONSE_RECEIVED" && error.failureCode.startsWith("GEMINI_IMAGE_"));
    assert.equal(h.calls.length, 1);
  }
});

test("Banana native rejection retains upstream status without switching models", async () => {
  const h = harness(() => response(400, { error: { message: "Unsupported imageSize" } }));
  await assert.rejects(h.call(bananaConfig(), { mode: "edit", prompt: "Single image", singleProviderAttempt: true }),
    error => error.providerStatus === 400 && error.requestState === "REJECTED");
  assert.equal(h.calls.length, 1);
});

test('dedicated product scene uses Grok low quality with ordered JSON references and no GPT-only fields', async () => {
  const h = harness(() => generatedResponse());
  const originals = [inputImages[0], { mimeType: 'image/png', dataBase64: Buffer.concat([OUTPUT_BYTES, Buffer.from([1])]).toString('base64') }];
  await h.call({ ...modelConfig(), modelId: GROK, copyrightFallbackModels: [] },
    { mode: 'edit', prompt: 'Complete this empty background scene', size: '1024x1024', quality: 'low', inputFidelity: 'high', productMockup: true }, originals);
  assert.equal(h.calls.length, 1);
  const wire = h.calls[0].json;
  assert.equal(wire.response_format, 'b64_json');assert.equal(wire.quality, 'low');assert.equal(wire.aspect_ratio, '1:1');assert.equal(wire.model, GROK);
  for (const key of ['size', 'input_fidelity', 'background', 'output_format', 'productMockup']) assert.equal(Object.hasOwn(wire, key), false);
  assert.equal(wire.images.length, 2);
  wire.images.forEach((image, i) => assert.equal(image.url.split(',')[1], originals[i].dataBase64));
});

for (const [name, reply] of [
  ['unknown socket', () => { throw Object.assign(new TypeError('fetch failed'), { cause: { code: 'UND_ERR_SOCKET' } }); }],
  ['response timeout', () => { throw Object.assign(new Error('headers timeout'), { code: 'UND_ERR_HEADERS_TIMEOUT' }); }],
  ['concurrency refusal', () => response(429, { error: { message: 'Too many concurrent requests' } })],
  ['copyright refusal', () => response(400, { error: { message: 'copyright restriction' } })]
]) test(`dedicated product scene stops after one POST on ${name}, even with backup and larger caller retry counts`, async () => {
  const h = harness(reply);
  await assert.rejects(h.call(modelConfig(), { mode: 'edit', prompt: 'Complete empty scene', size: '1024x1024',
    productMockup: true, maxProviderRetries: 9, maxNetworkRetries: 9 }), error => {
    assert.equal(error.networkRetryCount, 0);assert.equal(error.imageModelFallback, undefined);return true;
  });
  assert.equal(h.calls.length, 1);
});

for (const imageCount of [1, 2, 3, 4]) {
  test(`copyright fallback uses exclusive Grok JSON fields and preserves ${imageCount} reference image(s)`, async () => {
    const h = harness((_, count) => count === 1
      ? response(400, { error: { message: "Copyrighted character cannot be generated" } })
      : generatedResponse());
    const progress = [];
    const config = modelConfig();
    // Distinct byte payloads make order/drop regressions observable, including
    // the third placement guide and fourth independent artwork reference.
    const originals = Array.from({ length: imageCount }, (_, i) => ({ mimeType: "image/png",
      dataBase64: Buffer.concat([OUTPUT_BYTES, Buffer.from([i])]).toString("base64") }));
    const prompt = "Keep the uploaded product and placement guide unchanged.";
    const result = await h.call(config, { mode: "edit", prompt, size: "1024x1024", quality: "high", inputFidelity: "high", onProgress: (...event) => progress.push(event) }, originals);
    assert.equal(h.calls.length, 2);
    for (const call of h.calls) {
      assert.equal(call.url, "https://images.unit.invalid/v1/images/edits");
      assert.equal(call.method, "POST");
    }
    const [primary, fallback] = h.calls;
    assert.match(primary.contentType, /^multipart\/form-data; boundary=/);
    assert.deepEqual(primary.fields, { model: PRIMARY, prompt, size: "1024x1024", quality: "high", input_fidelity: "high" });
    assert.equal(primary.images.length, imageCount);
    primary.images.forEach((file, index) => {
      assert.equal(file.field, imageCount === 1 ? "image" : "image[]");
      assert.equal(file.fileName, `reference-${index + 1}.png`);
      assert.equal(file.mimeType, "image/png");
      assert.deepEqual(file.bytes, Buffer.from(originals[index].dataBase64, "base64"));
    });
    assert.equal(fallback.contentType, "application/json");
    assert.equal(Object.hasOwn(fallback.json, "image"), imageCount === 1);
    assert.equal(Object.hasOwn(fallback.json, "images"), imageCount > 1);
    assert.equal(Object.hasOwn(fallback.json, "image[]"), false);
    assert.equal(Object.hasOwn(fallback.json, "input_fidelity"), false);
    assert.equal(Object.hasOwn(fallback.json, "size"), false);
    const encoded = imageCount === 1 ? [fallback.json.image] : fallback.json.images;
    assert.equal(encoded.length, imageCount);
    encoded.forEach((image, index) => {
      assert.equal(image.type, "image_url");
      assert.ok(image.url.startsWith("data:image/png;base64,"));
      assert.deepEqual(Buffer.from(image.url.split(",")[1], "base64"), Buffer.from(originals[index].dataBase64, "base64"));
    });
    assert.equal(fallback.json.response_format, "b64_json");
    assert.equal(fallback.json.model, GROK);
    assert.equal(fallback.json.prompt, prompt);
    assert.equal(fallback.json.aspect_ratio, "1:1");
    assert.equal(fallback.json.quality, "medium");
    assert.equal(primary.authorization, "Bearer unit-primary-key");
    assert.equal(fallback.authorization, "Bearer unit-fallback-key");
    assert.notEqual(primary.idempotencyKey, fallback.idempotencyKey);
    assert.equal(config.apiKey, "unit-primary-key");
    assert.equal(config.modelId, PRIMARY);
    assert.equal(result.artifact.modelId, GROK);
    assert.equal(result.artifact.prompt, prompt);
    assert.equal(result.artifact.dataBase64, PNG_BASE64);
    assert.equal(result.requestMeta.safeRetryCount, 0);
    assert.deepEqual(result.requestMeta.fallback, { reason: "COPYRIGHT", fromModelId: PRIMARY, toModelId: GROK });
    assert.deepEqual(progress, [["GENERATING", 1], ["SWITCHING_MODEL", 1], ["GENERATING", 2], ["PROCESSING_RESPONSE", 2]]);
    for (const key of ["unit-primary-key", "unit-fallback-key"]) {
      assert.equal(JSON.stringify(result).includes(key), false);
      assert.equal(JSON.stringify(progress).includes(key), false);
    }
  });
}

for (const quality of ["low", "medium", "auto", ""]) {
  test(`direct Grok edit preserves its supported ${quality || "default"} quality without GPT-only fields`, async () => {
    const h = harness(() => generatedResponse());
    const config = { ...modelConfig(), modelId: GROK, copyrightFallbackModels: [] };
    await h.call(config, { mode: "edit", prompt: "Product layout", size: "1024x1536", quality, inputFidelity: "high" });
    assert.equal(h.calls.length, 1);
    assert.equal(h.calls[0].json.aspect_ratio, "2:3");
    assert.equal(h.calls[0].json.quality, quality || undefined);
    assert.equal(Object.hasOwn(h.calls[0].json, "resolution"), false, "Normal previews retain the default 1k tier");
    assert.equal(Object.hasOwn(h.calls[0].json, "input_fidelity"), false);
    assert.equal(Object.hasOwn(h.calls[0].json, "images"), false);
  });
}

test("text-to-image fallback translates Grok parameters and sends one request to each model", async () => {
  const h = harness((_, count) => count === 1
    ? response(422, { message: "copyright restriction" }) : generatedResponse());
  const config = modelConfig(), prompt = "Original generation description";
  const result = await h.call(config, { mode: "generate", prompt, size: "1024x1024", quality: "high", inputFidelity: "high" }, []);
  assert.equal(h.calls.length, 2);
  for (const call of h.calls) {
    assert.equal(call.url, "https://images.unit.invalid/v1/images/generations");
    assert.equal(call.method, "POST");assert.equal(call.contentType, "application/json");
  }
  assert.deepEqual(h.calls[0].json, { model: PRIMARY, prompt, n: 1, size: "1024x1024", quality: "high" });
  assert.deepEqual(h.calls[1].json, { model: GROK, prompt, n: 1, response_format: "b64_json", aspect_ratio: "1:1", quality: "medium" });
  assert.notEqual(h.calls[0].idempotencyKey, h.calls[1].idempotencyKey);
  assert.equal(config.modelId, PRIMARY);
  assert.equal(h.calls[1].authorization, "Bearer unit-fallback-key");
  assert.equal(result.artifact.modelId, GROK);
  assert.deepEqual(result.requestMeta.fallback, { reason: "COPYRIGHT", fromModelId: PRIMARY, toModelId: GROK });
});

for (const [quality, expected] of [["low", "low"], ["medium", "medium"], ["auto", "auto"], ["high", "medium"], ["", undefined]]) {
  test(`direct Grok text-to-image keeps ${quality || "default"} quality compatible without reference or GPT fields`, async () => {
    const h = harness(() => generatedResponse());
    const config = { ...modelConfig(), modelId: GROK, copyrightFallbackModels: [] };
    await h.call(config, { mode: "generate", prompt: "One source-colored product", size: "1024x1536", quality, inputFidelity: "high" }, []);
    assert.equal(h.calls.length, 1);
    const call = h.calls[0];
    assert.equal(call.url, "https://images.unit.invalid/v1/images/generations");
    assert.equal(call.json.n, 1);assert.equal(call.json.aspect_ratio, "2:3");assert.equal(call.json.quality, expected);
    for (const field of ["image", "images", "image[]", "size", "input_fidelity", "inputFidelity", "resolution"])
      assert.equal(Object.hasOwn(call.json, field), false, `${field} must not leak into a normal Grok generation`);
  });
}

test("Grok edit and generation share 2k sizing and leave automatic dimensions unspecified", async () => {
  for (const mode of ["edit", "generate"]) for (const size of ["2048x3072", "auto"]) {
    const h = harness(() => generatedResponse());
    await h.call({ ...modelConfig(), modelId: GROK, copyrightFallbackModels: [] },
      { mode, prompt: "One product", size, quality: "low" }, mode === "edit" ? inputImages : []);
    assert.equal(h.calls.length, 1);const request = h.calls[0].json;
    assert.equal(request.quality, "low");assert.equal(Object.hasOwn(request, "size"), false);
    assert.equal(request.aspect_ratio, size === "auto" ? undefined : "2:3");
    assert.equal(request.resolution, size === "auto" ? undefined : "2k");
  }
});

test("a direct Grok text-to-image copyright rejection does not restart or switch routes", async () => {
  const h = harness(() => response(400, { error: { message: "copyright restriction" } }));
  await assert.rejects(h.call({ ...modelConfig(), modelId: GROK },
    { mode: "generate", prompt: "Original description", size: "1024x1024", quality: "low" }, []),
    error => error.requestState === "REJECTED" && error.failureKind === "COPYRIGHT");
  assert.equal(h.calls.length, 1);assert.equal(h.calls[0].json.quality, "low");
});

for (const [status, message, expectedKind, expectedCalls] of [
  [400, "Input contains content prohibited by safety policy", "POLICY", 4],
  [401, "Invalid API key; copyright service unavailable", "PROVIDER", 1],
  [400, "Unsupported image dimensions", "PROVIDER", 1],
  [500, "Upstream copyright service temporarily unavailable", "PROVIDER", 1]
]) {
  test(`actual non-copyright rejection ${status}/${expectedKind} never calls the alternate model`, async () => {
    const h = harness(() => response(status, { error: { message } }));
    await assert.rejects(h.call(modelConfig(), { mode: "edit", prompt: "Original prompt" }), (error) => {
      assert.equal(error.failureKind, expectedKind);
      assert.equal(error.requestState, "REJECTED");
      assert.equal(error.imageModelFallback, undefined);
      return true;
    });
    assert.equal(h.calls.length, expectedCalls);
    assert.ok(h.calls.every((call) => call.fields.model === PRIMARY && call.authorization === "Bearer unit-primary-key"));
  });
}

test("a completed generation with an HTTP result-download failure never sends a fallback generation", async () => {
  const h = harness((call) => call.method === "POST"
    ? response(200, { data: [{ url: "https://results.unit.invalid/missing.png" }] })
    : new Response("missing", { status: 404 }));
  await assert.rejects(h.call(modelConfig(), { mode: "edit", prompt: "Original prompt" }), (error) => {
    assert.equal(error.requestState, "COMPLETED_RESPONSE_RECEIVED");
    assert.equal(error.imageModelFallback, undefined);
    return true;
  });
  assert.equal(h.calls.filter((call) => call.method === "POST").length, 1);
  assert.equal(h.calls.filter((call) => call.method === "GET").length, 1);
});

test('transparent artwork sends PNG alpha options on GPT edits and preserves references',async()=>{
 const h=harness(()=>generatedResponse());
 await h.call(modelConfig(),{mode:'edit',prompt:'isolated artwork, transparent canvas',background:'transparent',outputFormat:'png',size:'1024x1024'});
 assert.equal(h.calls[0].fields.background,'transparent');assert.equal(h.calls[0].fields.output_format,'png');assert.equal(h.calls[0].images.length,1);
});
test('transparent artwork sends PNG alpha options on GPT generation',async()=>{
 const h=harness(()=>generatedResponse());
 await h.call(modelConfig(),{mode:'generate',prompt:'isolated artwork',background:'transparent',outputFormat:'png',size:'1024x1024'},[]);
 assert.equal(h.calls[0].json.background,'transparent');assert.equal(h.calls[0].json.output_format,'png');
});
test('strict transparent output retains the GPT refusal and does not spend a Grok call',async()=>{
 const h=harness((_,n)=>n===1?response(400,{error:{message:'Copyrighted character cannot be generated'}}):generatedResponse());
 await assert.rejects(h.call(modelConfig(),{mode:'edit',prompt:'isolated artwork on transparent canvas',background:'transparent',outputFormat:'png',size:'1024x1024'}), error=>{
  assert.equal(error.failureKind,'COPYRIGHT');assert.equal(error.requestState,'REJECTED');assert.equal(error.providerStatus,400);
  assert.equal(error.imageOutputRequirement.fallbackSkipped,true);assert.equal(error.imageModelFallback,undefined);return true;
 });
 assert.equal(h.calls.length,1);assert.equal(h.calls[0].fields.background,'transparent');
});
for(const modelId of ['grok-imagine-image-2.0','unknown-image-model'])test(`strict transparent output rejects ${modelId} before any provider call`,async()=>{
 const h=harness(()=>generatedResponse());
 await assert.rejects(h.call({...modelConfig(),modelId},{mode:'edit',prompt:'isolated artwork',background:'transparent',size:'1024x1024'}),error=>{
  assert.equal(error.failureCode,'NATIVE_TRANSPARENT_PNG_UNSUPPORTED');assert.equal(error.requestState,'REJECTED');
  assert.equal(error.failureKind,'PROVIDER');assert.equal(error.providerStatus,undefined);return true;
 });
 assert.equal(h.calls.length,0);
});
test('strict transparent output does not resend a timed out primary with or without configured fallback',async()=>{
 for(const copyrightFallbackModels of [modelConfig().copyrightFallbackModels,[]]){
  const h=harness(()=>{throw Object.assign(new TypeError('fetch failed'),{cause:Object.assign(new Error('socket closed'),{code:'UND_ERR_SOCKET'})});});
  await assert.rejects(h.call({...modelConfig(),copyrightFallbackModels},{mode:'edit',prompt:'isolated artwork',background:'transparent',size:'1024x1024'}),error=>{
   assert.equal(error.requestState,'ACCEPTED_OR_UNKNOWN');assert.equal(error.failureKind,'NETWORK');
   assert.equal(error.networkRetryCount,0);assert.equal(error.imageModelFallback,undefined);return true;
  });
  assert.equal(h.calls.length,1);
 }
});

for (const [status, payload, kind] of [
  [400, { error: { message: 'Input contains content prohibited by safety policy' } }, 'POLICY'],
  [429, { error: { message: 'Too many concurrent image requests' } }, 'CONCURRENCY'],
  [503, { error: { message: 'Service temporarily overloaded' } }, 'CONCURRENCY'],
  [500, { error: { message: 'Request rejected', accepted: false, charged: false } }, 'PROVIDER']
]) for (const mode of ['edit', 'generate']) {
  test(`strict transparent ${mode} spends exactly one POST for ${status}/${kind}, with no hidden provider retry`, async () => {
    for (const copyrightFallbackModels of [modelConfig().copyrightFallbackModels, []]) {
      const h = harness((_, count) => count === 1 ? response(status, payload) : generatedResponse());
      const progress = [];
      await assert.rejects(h.call({ ...modelConfig(), copyrightFallbackModels }, {
        mode, prompt: 'Isolated artwork', background: 'transparent', outputFormat: 'png',
        maxNetworkRetries: 9, maxProviderRetries: 9,
        onProgress: (...event) => progress.push(event)
      }, mode === 'edit' ? inputImages : []), error => {
        assert.equal(error.requestState, 'REJECTED');assert.equal(error.failureKind, kind);
        assert.equal(error.providerStatus, status);assert.equal(error.retrySafe, true);
        assert.equal(error.safeRetryCount, 0);assert.equal(error.networkRetryCount, 0);
        assert.equal(error.imageModelFallback, undefined);return true;
      });
      assert.equal(h.calls.length, 1);
      assert.equal((h.calls[0].fields || h.calls[0].json).model, PRIMARY);
      assert.deepEqual(progress, [['GENERATING', 1]]);
    }
  });
}

for (const [status, payload, expectedCalls] of [
  [400, { error: { message: 'Input contains content prohibited by safety policy' } }, 4],
  [429, { error: { message: 'Too many concurrent image requests' } }, 5],
  [500, { error: { message: 'Request rejected', accepted: false, charged: false } }, 2]
]) test(`ordinary product photos retain their ${expectedCalls} bounded POSTs for HTTP ${status}`, async () => {
  const h = harness(() => response(status, payload));
  await assert.rejects(h.call({ ...modelConfig(), copyrightFallbackModels: [] }, {
    mode: 'edit', prompt: 'Preserve the garment and its colors'
  }), error => error.requestState === 'REJECTED' && error.safeRetryCount === expectedCalls - 1);
  assert.equal(h.calls.length, expectedCalls);
  assert.ok(h.calls.every(call => !Object.hasOwn(call.fields, 'background')));
});
