import test from "node:test";
import assert from "node:assert/strict";
import { callImageModelWithCopyrightFallback } from "../src/image-model-fallback.js";
import { supportsNativeTransparentPng } from "../src/image-output-capabilities.js";

const GROK = "grok-imagine-image-2.0";
const PRIMARY = "gpt-image-2";
const route = () => ({
  modelId: GROK,
  baseUrl: "https://images.example.invalid/v1",
  apiKey: "fallback-secret-never-log",
  apiFormat: "OPENAI_COMPATIBLE"
});
const config = () => ({
  modelId: PRIMARY,
  baseUrl: "https://images.example.invalid/v1",
  apiKey: "test-secret-never-log",
  apiFormat: "OPENAI_COMPATIBLE",
  copyrightFallbackModels: [route()]
});
const rejection = (details = {}) => Object.assign(new Error("Image rejected"), {
  failureKind: "COPYRIGHT", requestState: "REJECTED", providerStatus: 400,
  safeRetryCount: 0, retrySafe: false, ...details
});
const completed = (modelId = PRIMARY) => ({
  artifact: { modelId, dataBase64: "aW1hZ2U=", mimeType: "image/png" },
  usage: { images: 1 },
  requestMeta: { requestState: "COMPLETED", safeRetryCount: 0 }
});

test("strict transparent routing and wire support use the same confirmed capability list", () => {
  for (const modelId of ["gpt-image-1", "gpt-image-1-mini", "gpt-image-1.5", PRIMARY, "gpt-image-2-2026-04-21"])
    assert.equal(supportsNativeTransparentPng({ ...config(), modelId }), true);
  for (const modelId of [GROK, "unknown-image-model", "GPT-IMAGE-2", "gpt-image-2-custom", ""])
    assert.equal(supportsNativeTransparentPng({ ...config(), modelId }), false);
  assert.equal(supportsNativeTransparentPng({ ...config(), apiFormat: "OTHER" }), false);
});

test("strict transparent requests reject unsupported primary routes without even entering a provider", async () => {
  for (const modelId of [GROK, "unknown-image-model"]) {
    let calls = 0;
    await assert.rejects(callImageModelWithCopyrightFallback({ ...config(), modelId }, { background: "transparent" }, [], async () => {
      calls++; return completed();
    }), error => {
      assert.equal(error.failureCode, "NATIVE_TRANSPARENT_PNG_UNSUPPORTED");
      assert.equal(error.requestState, "REJECTED");assert.equal(error.failureKind, "PROVIDER");
      assert.equal(error.providerStatus, undefined);assert.equal(error.networkRetryCount, 0);
      return true;
    });
    assert.equal(calls, 0);
  }
});

for (const [name, details] of [
  ["copyright refusal", {}],
  ["policy refusal", { failureKind: "POLICY", providerStatus: 422 }],
  ["accepted-or-unknown", { failureKind: "NETWORK", requestState: "ACCEPTED_OR_UNKNOWN", retryAuthorized: true, providerStatus: undefined }],
  ["response timeout", { failureKind: "NETWORK_TIMEOUT", requestState: "RESPONSE_TIMEOUT", retryAuthorized: true, providerStatus: undefined }],
  ["received result download failure", { failureKind: "NETWORK", requestState: "COMPLETED_RESPONSE_RECEIVED", providerStatus: undefined }]
]) test(`strict transparency preserves the primary ${name} without an unsupported backup`, async () => {
  const error = rejection({ ...details, requestId: "original-paid-request", networkRetryCount: 0 });
  const original = { failureKind: error.failureKind, requestState: error.requestState, providerStatus: error.providerStatus };
  const progress = [];let calls = 0;
  await assert.rejects(callImageModelWithCopyrightFallback(config(), { background: "transparent", onProgress: (...event) => progress.push(event) }, [], async (_, params) => {
    calls++;assert.equal(params.maxNetworkRetries, 0);assert.equal(params.maxProviderRetries, 0);throw error;
  }), actual => actual === error);
  assert.equal(calls, 1);assert.equal(error.imageModelFallback, undefined);
  for (const [key, value] of Object.entries(original)) assert.equal(error[key], value);
  assert.equal(error.requestId, "original-paid-request");assert.equal(error.imageOutputRequirement.code, "NATIVE_TRANSPARENT_PNG_REQUIRED");
  assert.deepEqual(progress, []);
});

test("strict artwork overrides caller retry counts while ordinary photo options stay unchanged", async () => {
  await callImageModelWithCopyrightFallback(config(), {
    background: "transparent", maxNetworkRetries: 9, maxProviderRetries: 9
  }, [], async (_, params) => {
    assert.equal(params.maxNetworkRetries, 0);assert.equal(params.maxProviderRetries, 0);
    return completed();
  });
  await callImageModelWithCopyrightFallback({ ...config(), copyrightFallbackModels: [] }, {}, [], async (_, params) => {
    assert.equal(Object.hasOwn(params, "maxProviderRetries"), false);
    assert.equal(Object.hasOwn(params, "maxNetworkRetries"), false);
    return completed();
  });
});

test("only a detail grid opts into two network recoveries on the configured model",async()=>{
 for(const [params,networkRetries] of [
  [{productMockup:true,detailGrid:true,maxNetworkRetries:99,maxProviderRetries:99},2],
  [{detailGrid:true},2],
  [{productMockup:true},0],
  [{productMockup:true,detailGrid:false},0],
  [{productMockup:true,detailGrid:'true'},0],
  [{productMockup:true,detailGrid:true,background:'transparent'},0]
 ]){
  let calls=0;const selected=config(),references=[];
  await callImageModelWithCopyrightFallback(selected,params,references,async(actual,options,images)=>{
   calls++;assert.equal(actual,selected);assert.equal(images,references);assert.equal(options.maxNetworkRetries,networkRetries);assert.equal(options.maxProviderRetries,0);return completed();
  });assert.equal(calls,1);
 }
});

for(const [name,details] of [
 ['network',{failureKind:'NETWORK',requestState:'ACCEPTED_OR_UNKNOWN',retryAuthorized:true,networkRetryCount:2}],
 ['copyright',{}]
])test(`detail grid never switches model after ${name} failure`,async()=>{
 let calls=0;const error=rejection(details);
 await assert.rejects(callImageModelWithCopyrightFallback(config(),{productMockup:true,detailGrid:true},[],async(_,params)=>{
  calls++;assert.equal(params.maxNetworkRetries,2);assert.equal(params.maxProviderRetries,0);throw error;
 }),actual=>actual===error);
 assert.equal(calls,1);assert.equal(error.imageModelFallback,undefined);
});

test("successful primary response is returned unchanged without fallback metadata", async () => {
  const primaryConfig = config();
  const expected = completed();
  let calls = 0;
  const result = await callImageModelWithCopyrightFallback(primaryConfig, { prompt: "Original prompt" }, [], async (actualConfig) => {
    calls += 1;
    assert.equal(actualConfig, primaryConfig);
    return expected;
  });
  assert.equal(result, expected);
  assert.equal(calls, 1);
  assert.equal(result.requestMeta.fallback, undefined);
});

for (const providerStatus of [400, 403, 422]) {
  test(`explicit copyright rejection ${providerStatus} switches once with the fallback key and unmodified inputs`, async () => {
    const primaryConfig = Object.freeze({ ...config(), copyrightFallbackModels: Object.freeze([Object.freeze(route())]) });
    const images = Object.freeze([Object.freeze({ fileName: "shirt.png", bytes: Buffer.from("unaltered-image"), mimeType: "image/png" })]);
    const referenceOptions = Object.freeze({ preserve: true });
    const params = Object.freeze({ prompt: "  Preserve the print exactly. 原始描述  ", mode: "edit", size: "1024x1024", quality: "high", inputFidelity: "high", referenceOptions });
    const calls = [];
    const expected = completed(PRIMARY);
    const result = await callImageModelWithCopyrightFallback(primaryConfig, params, images, async (actualConfig, actualParams, actualImages) => {
      calls.push(actualConfig);
      assert.equal(actualImages, images);
      assert.equal(actualParams.prompt, params.prompt);
      assert.equal(actualParams.referenceOptions, referenceOptions);
      for (const key of ["mode", "size", "quality", "inputFidelity"]) assert.equal(actualParams[key], params[key]);
      if (calls.length === 1) throw rejection({ providerStatus });
      assert.deepEqual(actualConfig, { ...primaryConfig, modelId: GROK, apiKey: route().apiKey, copyrightFallbackModels: [] });
      return expected;
    });
    assert.equal(calls.length, 2);
    assert.equal(result.artifact.modelId, GROK);
    assert.equal(result.artifact.dataBase64, expected.artifact.dataBase64);
    assert.equal(result.usage, expected.usage);
    assert.deepEqual(result.requestMeta.fallback, { reason: "COPYRIGHT", fromModelId: PRIMARY, toModelId: GROK });
    assert.equal(JSON.stringify(result.requestMeta).includes(primaryConfig.apiKey), false);
    assert.equal(primaryConfig.modelId, PRIMARY);
    assert.deepEqual(primaryConfig.copyrightFallbackModels, [route()]);
    assert.equal(primaryConfig.apiKey, "test-secret-never-log");
    assert.equal(JSON.stringify(result.requestMeta).includes(route().apiKey), false);
    assert.equal(expected.artifact.modelId, PRIMARY, "does not mutate the single-model result");
  });
}

test("deferred result retains its URL and identifies the actual fallback model", async () => {
  let calls = 0;
  const result = await callImageModelWithCopyrightFallback(config(), { prompt: "same", deferDownload: true }, [], async () => {
    if (++calls === 1) throw rejection();
    return { result: { modelId: PRIMARY, url: "https://images.example.invalid/result.png" }, requestMeta: { requestState: "COMPLETED_RESPONSE_RECEIVED", safeRetryCount: 2 } };
  });
  assert.equal(result.result.modelId, GROK);
  assert.equal(result.result.url, "https://images.example.invalid/result.png");
  assert.equal(result.requestMeta.requestState, "COMPLETED_RESPONSE_RECEIVED");
  assert.equal(result.requestMeta.safeRetryCount, 2);
});

const noFallbackErrors = [
  ["response timeout", { failureKind: "NETWORK_TIMEOUT", requestState: "RESPONSE_TIMEOUT" }],
  ["request state unknown", { requestState: "ACCEPTED_OR_UNKNOWN" }],
  ["not sent", { requestState: "NOT_SENT" }],
  ["result download failure", { requestState: "COMPLETED_RESPONSE_RECEIVED", resultDownloadRetryCount: 3 }],
  ["content safety rejection", { failureKind: "POLICY" }],
  ["network failure", { failureKind: "NETWORK" }],
  ["generic provider rejection", { failureKind: "PROVIDER" }],
  ["authentication failure", { providerStatus: 401 }],
  ["rate limit", { providerStatus: 429 }],
  ["server failure", { providerStatus: 500 }],
  ["status missing", { providerStatus: undefined }],
  ["status string", { providerStatus: "400" }],
  ["failure kind missing", { failureKind: undefined }],
  ["request state missing", { requestState: undefined }]
];
for (const [name, details] of noFallbackErrors) {
  test(`does not switch on ${name}`, async () => {
    const error = rejection(details);
    let calls = 0;
    await assert.rejects(callImageModelWithCopyrightFallback(config(), { prompt: "same" }, [], async () => { calls += 1; throw error; }), (actual) => actual === error);
    assert.equal(calls, 1);
    assert.equal(error.imageModelFallback, undefined);
  });
}

const invalidRoutes = [
  ["absent", undefined],
  ["null", null],
  ["empty", []],
  ["model ID string", GROK],
  ["model ID list", [GROK]],
  ["unapproved model", [{ ...route(), modelId: "another-image-model" }]],
  ["different case", [{ ...route(), modelId: "GROK-IMAGINE-IMAGE-2.0" }]],
  ["leading whitespace in model", [{ ...route(), modelId: ` ${GROK}` }]],
  ["trailing whitespace in model", [{ ...route(), modelId: `${GROK} ` }]],
  ["different origin", [{ ...route(), baseUrl: "https://other.example.invalid/v1" }]],
  ["different URL path", [{ ...route(), baseUrl: "https://images.example.invalid/v2" }]],
  ["different protocol", [{ ...route(), baseUrl: "http://images.example.invalid/v1" }]],
  ["missing URL", [{ ...route(), baseUrl: undefined }]],
  ["different format", [{ ...route(), apiFormat: "OTHER" }]],
  ["missing format", [{ ...route(), apiFormat: undefined }]],
  ["empty key", [{ ...route(), apiKey: "" }]],
  ["whitespace key", [{ ...route(), apiKey: "  " }]],
  ["missing key", [{ ...route(), apiKey: undefined }]],
  ["null route", [null]],
  ["incomplete route", [{ modelId: GROK }]]
];
for (const [name, copyrightFallbackModels] of invalidRoutes) {
  test(`does not switch without an authorized complete route: ${name}`, async () => {
    const error = rejection();
    let calls = 0;
    await assert.rejects(callImageModelWithCopyrightFallback({ ...config(), copyrightFallbackModels }, { prompt: "same" }, [], async () => { calls += 1; throw error; }), (actual) => actual === error);
    assert.equal(calls, 1);
  });
}

test("legacy model ID list alone cannot authorize or reuse the primary key", async () => {
  let calls = 0;
  const error = rejection();
  const primaryConfig = { ...config(), copyrightFallbackModels: undefined, copyrightFallbackModelIds: [GROK] };
  await assert.rejects(callImageModelWithCopyrightFallback(primaryConfig, { prompt: "same" }, [], async () => { calls += 1; throw error; }), (actual) => actual === error);
  assert.equal(calls, 1);
});

test("duplicate or unknown routes cannot add calls or select an unapproved model", async () => {
  const attempted = [];
  const error = rejection();
  const routes = [{ ...route(), modelId: "unapproved-before" }, { ...route(), apiKey: "" }, route(), route(), { ...route(), modelId: "unapproved-after" }];
  await assert.rejects(callImageModelWithCopyrightFallback({ ...config(), copyrightFallbackModels: routes }, { prompt: "same" }, [], async (actualConfig) => {
    attempted.push(actualConfig.modelId);
    if (actualConfig.modelId === GROK) {
      assert.deepEqual(actualConfig.copyrightFallbackModels, []);
      assert.equal(actualConfig.apiKey, route().apiKey);
    }
    throw error;
  }), (actual) => actual === error);
  assert.deepEqual(attempted, [PRIMARY, GROK]);
});

test("matching provider URLs may differ only by trailing slashes", async () => {
  const attempted = [];
  const primaryConfig = { ...config(), baseUrl: config().baseUrl + "/", copyrightFallbackModels: [{ ...route(), baseUrl: route().baseUrl + "///" }] };
  await callImageModelWithCopyrightFallback(primaryConfig, { prompt: "same" }, [], async (actualConfig) => {
    attempted.push(actualConfig);
    if (attempted.length === 1) throw rejection();
    return completed(GROK);
  });
  assert.equal(attempted.length, 2);
  assert.equal(attempted[1].baseUrl, primaryConfig.baseUrl);
  assert.equal(attempted[1].apiKey, route().apiKey);
});

test("a primary Grok model never switches to itself", async () => {
  let calls = 0;
  const error = rejection();
  await assert.rejects(callImageModelWithCopyrightFallback({ ...config(), modelId: GROK }, { prompt: "same" }, [], async () => { calls += 1; throw error; }), (actual) => actual === error);
  assert.equal(calls, 1);
});

test("fallback copyright failure is terminal and preserves the fallback error details", async () => {
  const secondaryError = rejection({ providerStatus: 422, safeRetryCount: 3, failureCode: "SECONDARY_REJECTION" });
  const progress = [];
  let calls = 0;
  await assert.rejects(callImageModelWithCopyrightFallback(config(), { prompt: "same", onProgress: (...event) => progress.push(event) }, [], async () => {
    if (++calls === 1) throw rejection();
    throw secondaryError;
  }), (actual) => actual === secondaryError);
  assert.equal(calls, 2);
  assert.equal(secondaryError.failureKind, "COPYRIGHT");
  assert.equal(secondaryError.requestState, "REJECTED");
  assert.equal(secondaryError.providerStatus, 422);
  assert.equal(secondaryError.safeRetryCount, 3);
  assert.equal(secondaryError.failureCode, "SECONDARY_REJECTION");
  assert.deepEqual(secondaryError.imageModelFallback, { reason: "COPYRIGHT", fromModelId: PRIMARY, toModelId: GROK });
  assert.equal(JSON.stringify(secondaryError.imageModelFallback).includes(config().apiKey), false);
  assert.equal(JSON.stringify(secondaryError.imageModelFallback).includes(route().apiKey), false);
  assert.deepEqual(progress, [["SWITCHING_MODEL", 1]]);
});

test("fallback download failure keeps its completed request state and download retry count", async () => {
  const error = rejection({ failureKind: "NETWORK", requestState: "COMPLETED_RESPONSE_RECEIVED", safeRetryCount: 2, resultDownloadRetryCount: 3 });
  let calls = 0;
  await assert.rejects(callImageModelWithCopyrightFallback(config(), { prompt: "same" }, [], async () => {
    if (++calls === 1) throw rejection();
    throw error;
  }), (actual) => actual === error);
  assert.equal(calls, 2);
  assert.equal(error.requestState, "COMPLETED_RESPONSE_RECEIVED");
  assert.equal(error.failureKind, "NETWORK");
  assert.equal(error.safeRetryCount, 2);
  assert.equal(error.resultDownloadRetryCount, 3);
  assert.equal(error.imageModelFallback.toModelId, GROK);
});

test("progress counts generation attempts across models while safeRetryCount stays per model", async () => {
  const stages = [];
  let calls = 0;
  const result = await callImageModelWithCopyrightFallback(config(), { prompt: "same", onProgress: (...event) => stages.push(event) }, [], async (_, params) => {
    params.onProgress("GENERATING", 1);
    params.onProgress("RETRY_WAIT", 1);
    params.onProgress("GENERATING", 2);
    if (++calls === 1) throw rejection({ safeRetryCount: 1 });
    params.onProgress("PROCESSING_RESPONSE", 2);
    return { ...completed(GROK), requestMeta: { requestState: "COMPLETED", safeRetryCount: 1 } };
  });
  assert.deepEqual(stages, [
    ["GENERATING", 1], ["RETRY_WAIT", 1], ["GENERATING", 2], ["SWITCHING_MODEL", 2],
    ["GENERATING", 3], ["RETRY_WAIT", 3], ["GENERATING", 4], ["PROCESSING_RESPONSE", 4]
  ]);
  assert.equal(result.requestMeta.safeRetryCount, 1);
  assert.equal(JSON.stringify(stages).includes(config().apiKey), false);
  assert.equal(JSON.stringify(stages).includes(route().apiKey), false);
  assert.equal(JSON.stringify(stages).includes("same"), false);
});

test("an error's completed retry count offsets fallback progress if no primary progress was reported", async () => {
  const stages = [];
  let calls = 0;
  const result = await callImageModelWithCopyrightFallback(config(), { prompt: "same", onProgress: (...event) => stages.push(event) }, [], async (_, params) => {
    if (++calls === 1) throw rejection({ safeRetryCount: 2 });
    params.onProgress("GENERATING", 1);
    params.onProgress("PROCESSING_RESPONSE");
    return completed(GROK);
  });
  assert.deepEqual(stages, [["SWITCHING_MODEL", 3], ["GENERATING", 4], ["PROCESSING_RESPONSE", 4]]);
  assert.equal(result.requestMeta.safeRetryCount, 0);
});
