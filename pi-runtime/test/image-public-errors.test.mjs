import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import vm from "node:vm";
import { callImageModelWithCopyrightFallback } from "../src/image-model-fallback.js";
import { supportsNativeTransparentPng } from "../src/image-output-capabilities.js";

const source = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
const helperStart = source.indexOf("function imageApiUsage(");
const helperEnd = source.indexOf("function createSession(", helperStart);
const serverStart = source.indexOf("const server = http.createServer(");
assert.ok(helperStart >= 0 && helperEnd > helperStart && serverStart > helperEnd);
const plain = value => JSON.parse(JSON.stringify(value));
const PRIMARY = "gpt-image-2", GROK = "grok-imagine-image-2.0";
const SECRET = "unit-private-provider-key-never-echo";
const IMAGE = "data:image/png;base64,private-reference-bytes-never-echo";

// Exercise the real HTTP catch/serialization path without a socket, a live
// provider, or the runtime's startup imports. All provider responses are local.
function harness(fail, reply, imageOptions = {}) {
  let handler;
  const calls = [];
  const context = vm.createContext({
    Buffer, Blob, FormData, URL, randomUUID, callImageModelWithCopyrightFallback, supportsNativeTransparentPng,
    PORT: 0, console: { error() {}, log() {} },
    AbortSignal: { timeout: () => undefined }, setTimeout: callback => callback(),
    IMAGE_FETCH_DISPATCHER: {}, IMAGE_RESPONSE_TIMEOUT_MS: 900_000,
    IMAGE_CONCURRENCY_RETRY_DELAYS_MS: [0, 0, 0, 0], IMAGE_NETWORK_RETRY_DELAYS_MS: [0, 0], IMAGE_POLICY_RETRY_DELAYS_MS: [0, 0, 0],
    http: { createServer: callback => { handler = callback; return { listen() {} }; } },
    json: (response, status, body) => { response.status = status; response.body = plain(body); },
    handlePlatformImageGeneration: async () => {
      if (fail) throw fail;
      const config = { modelId: PRIMARY, baseUrl: "https://provider.unit.invalid/v1", apiKey: SECRET, apiFormat: "OPENAI_COMPATIBLE",
        copyrightFallbackModels: [{ modelId: GROK, baseUrl: "https://provider.unit.invalid/v1", apiKey: SECRET, apiFormat: "OPENAI_COMPATIBLE" }] };
      return context.callImageModel(config, { mode: "generate", prompt: "Private test prompt", size: "1024x1024", ...imageOptions }, []);
    },
    fetch: async (url, options) => {
      const body = JSON.parse(options.body);calls.push({ model: body.model, key: options.headers["Idempotency-Key"] });
      return reply(body.model, calls.length);
    }
  });
  vm.runInContext(source.slice(helperStart, helperEnd) + "\n" + source.slice(serverStart), context);
  return { calls, async request() { const response = {}; await handler({ method: "POST", url: "/v1/images/generate" }, response); return response; } };
}

test("strict transparency refusal serializes the real primary reason and explains skipped Grok", async () => {
  const h = harness(null, () => new Response(JSON.stringify({ error: { message: `copyright restriction ${SECRET}` } }),
    { status: 400, headers: { "content-type": "application/json" } }), { background: "transparent", outputFormat: "png" });
  const { body } = await h.request();
  assert.deepEqual(h.calls.map(call => call.model), [PRIMARY]);
  assert.equal(body.requestState, "REJECTED");assert.equal(body.failureKind, "COPYRIGHT");assert.equal(body.providerStatus, 400);
  assert.equal(body.networkRetryCount, 0);assert.equal(body.imageModelFallback, undefined);
  assert.equal(body.imageOutputRequirement.code, "NATIVE_TRANSPARENT_PNG_REQUIRED");
  assert.equal(body.imageOutputRequirement.fallbackSkipped, true);
  assert.match(body.message, /版权限制/);assert.match(body.message, /原生透明 PNG/);assert.match(body.message, /跳过备用调用/);
  assert.ok(!JSON.stringify(body).includes(SECRET));assert.ok(!JSON.stringify(body).includes("Private test prompt"));
});

test("strict transparency unknown result remains unknown in the public response", async () => {
  const error = Object.assign(new Error("socket interrupted"), {
    requestState: "ACCEPTED_OR_UNKNOWN", failureKind: "NETWORK", failureCode: "UND_ERR_SOCKET", networkRetryCount: 0,
    imageOutputRequirement: { code: "NATIVE_TRANSPARENT_PNG_REQUIRED", background: "transparent", outputFormat: "png", fallbackSkipped: true }
  });
  const { body } = await harness(error).request();
  assert.equal(body.requestState, "ACCEPTED_OR_UNKNOWN");assert.equal(body.failureKind, "NETWORK");
  assert.match(body.message, /结果仍未知/);assert.match(body.message, /不会自动重新提交/);
  assert.doesNotMatch(body.message, /未生成图片|拒绝本次请求|本轮处理已结束/);
});

test("unsupported native transparency reports an explicit no-call capability stop without inventing provider status", async () => {
  const error = Object.assign(new Error("private model configuration"), {
    requestState: "REJECTED", failureKind: "PROVIDER", failureCode: "NATIVE_TRANSPARENT_PNG_UNSUPPORTED", networkRetryCount: 0,
    imageOutputRequirement: { code: "NATIVE_TRANSPARENT_PNG_REQUIRED", background: "transparent", outputFormat: "png", fallbackSkipped: false }
  });
  const { body } = await harness(error).request();
  assert.equal(body.failureKind, "PROVIDER");assert.equal(body.requestState, "REJECTED");assert.equal(body.providerStatus, undefined);
  assert.match(body.message, /无法满足无底图要求/);assert.match(body.message, /未发起模型调用/);
  assert.doesNotMatch(body.message, /private/);
});

test("final primary copyright then Grok policy rejection returns provider 400 under outer 502 and ends its retry message", async () => {
  const h = harness(null, model => new Response(JSON.stringify({ error: {
    message: `${model === PRIMARY ? "Copyrighted character cannot be generated" : "Input contains content prohibited by safety policy"}; ${SECRET}; ${IMAGE}`
  } }), { status: 400, headers: { "content-type": "application/json" } }));
  const { status, body } = await h.request();
  assert.equal(status, 502);assert.equal(body.providerStatus, 400);assert.equal(body.failureKind, "POLICY");
  assert.equal(body.requestState, "REJECTED");assert.equal(body.safeRetryCount, 3);
  assert.deepEqual(h.calls.map(call => call.model), [PRIMARY, GROK, GROK, GROK, GROK]);
  assert.equal(new Set(h.calls.slice(1).map(call => call.key)).size, 1);
  assert.deepEqual(body.imageModelFallback, { reason: "COPYRIGHT", fromModelId: PRIMARY, toModelId: GROK });
  assert.match(body.message, /已切换备用模型 grok-imagine-image-2\.0/);
  assert.match(body.message, /拒绝本次请求，未生成图片/);assert.match(body.message, /已重试 3 次/);assert.match(body.message, /本轮处理已结束/);
  assert.doesNotMatch(body.message, /将继续|后台自动重试|可能误判/);
  assert.ok(!JSON.stringify(body).includes(SECRET));assert.ok(!JSON.stringify(body).includes(IMAGE));assert.ok(!JSON.stringify(body).includes("Private test prompt"));
});

for (const kind of ["COPYRIGHT", "POLICY"]) {
  test(`final ${kind} refusal without a retry is an explicit finished non-generation`, async () => {
    const error = Object.assign(new Error(`${kind === "COPYRIGHT" ? "copyright" : "content policy"}: ${SECRET}; ${IMAGE}`), {
      failureKind: kind, requestState: "REJECTED", providerStatus: 403, retrySafe: false, safeRetryCount: 0,
      apiKey: SECRET, rawResponse: IMAGE
    });
    const { body } = await harness(error).request();
    assert.equal(body.providerStatus, 403);assert.equal(body.requestState, "REJECTED");assert.equal(body.safeRetryCount, 0);
    assert.match(body.message, /拒绝本次请求，未生成图片/);assert.match(body.message, /本轮处理已结束/);assert.doesNotMatch(body.message, /将继续|后台|重试 0 次/);
    assert.ok(!JSON.stringify(body).includes(SECRET));assert.ok(!JSON.stringify(body).includes(IMAGE));
  });
}

test("only integer provider HTTP error statuses are copied into the public payload", async () => {
  for (const providerStatus of [400, 403, 422, 499, 500, 599, 200, 399, 600, -1, 400.5, NaN, Infinity, "400", "400 private", null, undefined]) {
    const { body } = await harness(Object.assign(new Error("policy refusal"), { failureKind: "POLICY", requestState: "REJECTED", providerStatus })).request();
    const allowed = Number.isInteger(providerStatus) && providerStatus >= 400 && providerStatus <= 599;
    assert.equal(Object.hasOwn(body, "providerStatus"), allowed, `providerStatus ${String(providerStatus)}`);
    if (allowed) assert.equal(body.providerStatus, providerStatus);
  }
});

test("unresolved network failures remain accepted-or-unknown and do not claim refusal or non-generation", async () => {
  for (const providerStatus of [undefined, 502]) {
    const error = Object.assign(new TypeError(`fetch failed ${SECRET}`), {
      failureKind: "NETWORK", requestState: "ACCEPTED_OR_UNKNOWN", providerStatus,
      retrySafe: false, retryAuthorized: true, safeRetryCount: 1, networkRetryCount: 1,
      requestId: "pending-network-request", failureCode: "UND_ERR_SOCKET",
      cause: Object.assign(new Error("socket disconnected"), { code: "UND_ERR_SOCKET" })
    });
    const { status, body } = await harness(error).request();
    assert.equal(status, 502);assert.equal(body.requestState, "ACCEPTED_OR_UNKNOWN");assert.equal(body.failureKind, "NETWORK");
    assert.equal(body.retrySafe, false);assert.equal(body.retryAuthorized, true);assert.equal(body.networkRetryCount, 1);assert.equal(body.requestId, "pending-network-request");
    assert.match(body.message, /连接中途断开/);assert.match(body.message, /已保留请求记录/);assert.doesNotMatch(body.message, /未生成图片|拒绝本次请求|本轮处理已结束/);
    assert.ok(!JSON.stringify(body).includes(SECRET));
  }
});
