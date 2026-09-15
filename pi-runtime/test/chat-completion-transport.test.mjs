import http from "node:http";
import { once } from "node:events";
import test from "node:test";
import assert from "node:assert/strict";
import { chatCompletionEndpoint, requestChatCompletion } from "../src/chat-completion-transport.js";

const messages = [{ role: "system", content: "只输出 JSON，保留九个分镜" }, {
  role: "user", content: [
    { type: "text", text: "图片顺序不能改变" },
    { type: "image_url", image_url: { url: "data:image/jpeg;base64,ZmFrZTE=" } },
    { type: "image_url", image_url: { url: "data:image/png;base64,ZmFrZTI=" } }
  ]
}];
async function upstream(t, handler) {
  const calls = [];
  const server = http.createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const payload = JSON.parse(Buffer.concat(chunks).toString());
    calls.push(payload);
    handler(response, payload, calls.length);
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => {
    server.closeAllConnections();
    return new Promise(resolve => server.close(resolve));
  });
  const config = { baseUrl: `http://127.0.0.1:${server.address().port}/v1`, modelId: "gpt-5.5" };
  return {
    calls, config,
    request: overrides => requestChatCompletion({
      config, apiKey: "fake-test-only", messages, maxTokens: 6500, timeoutMs: 5_000, ...overrides
    })
  };
}
function rejected(response, status, param, message = `Unsupported parameter: '${param}'`, code = "unsupported_parameter") {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify({ error: { code, param, message } }));
}
function success(response, content = '{"ok":true}', finish_reason = "stop") {
  response.writeHead(200, { "Content-Type": "application/json" });
  response.end(JSON.stringify({ choices: [{ finish_reason, message: { content } }] }));
}

test("base URL supports origin, version base, full endpoint and API query parameters", () => {
  for (const input of ["https://example.invalid", "https://example.invalid/", "https://example.invalid/v1/", "https://example.invalid/v1/chat/completions/"]) {
    assert.equal(chatCompletionEndpoint(input), "https://example.invalid/v1/chat/completions");
  }
  assert.equal(chatCompletionEndpoint("https://example.invalid/gateway/v1/?api-version=test"),
    "https://example.invalid/gateway/v1/chat/completions?api-version=test");
  assert.throws(() => chatCompletionEndpoint("file:///tmp/local"), /地址无效/);
  assert.throws(() => chatCompletionEndpoint("https://example.invalid/v1/responses"), /Chat Completions/);
});

test("explicit 400/422 token negotiation is bounded and cached without changing model or images", async t => {
  for (const status of [400, 422]) {
    const u = await upstream(t, (response, payload) => {
      if (payload.max_tokens) rejected(response, status, "max_tokens");
      else success(response);
    });
    const first = await u.request();
    assert.equal(first.compatibilityRequests, 1);
    assert.equal(u.calls.length, 2);
    for (const payload of u.calls) {
      assert.equal(payload.model, "gpt-5.5");
      assert.equal(payload.stream, false);
      assert.equal(payload.reasoning_effort, undefined);
      assert.deepEqual(payload.messages, messages);
      assert.equal(payload.max_completion_tokens || payload.max_tokens, 6500);
    }
    const cached = await u.request({ maxTokens: 1800 });
    assert.equal(cached.compatibilityRequests, 0);
    assert.equal(u.calls.length, 3);
    assert.equal(u.calls[2].max_completion_tokens, 1800);
    assert.equal(u.calls[2].reasoning_effort, undefined);
  }
});

test("GPT-5 and unfamiliar models start with the same minimal max_tokens contract", async t => {
  const u = await upstream(t, response => success(response));
  for (const modelId of ["gpt-5.5", "gpt-5.6", "new-vision-driver"]) {
    await u.request({ config: { ...u.config, modelId }, maxTokens: 1800 });
  }
  assert.equal(u.calls.length, 3);
  for (const payload of u.calls) {
    assert.equal(payload.max_tokens, 1800);
    assert.equal(payload.max_completion_tokens, undefined);
    assert.equal(payload.reasoning_effort, undefined);
  }
});

test("unknown model names negotiate token field from an explicit error instead of relying on name patterns", async t => {
  const u = await upstream(t, (response, payload) => payload.max_tokens
    ? rejected(response, 400, undefined, "Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead.")
    : success(response));
  await u.request({ config: { ...u.config, modelId: "new-driver-vision" } });
  assert.equal(u.calls.length, 2);
  assert.equal(u.calls[1].max_completion_tokens, 6500);
  assert.equal(u.calls[1].model, "new-driver-vision");
});

test("cached compatibility is isolated across provider endpoints, model IDs and credentials", async t => {
  const handler = (response, payload) => payload.max_tokens
    ? rejected(response, 400, "max_tokens") : success(response);
  const a = await upstream(t, handler);
  const b = await upstream(t, handler);
  await a.request();
  await a.request();
  assert.equal(a.calls.length, 3);
  await b.request();
  assert.equal(b.calls.length, 2);
  await a.request({ config: { ...a.config, modelId: "gpt-5.6" } });
  assert.equal(a.calls.length, 5);
  await a.request({ apiKey: "other-fake-key" });
  assert.equal(a.calls.length, 7);
});

test("inconsistent upstream token parameter rejections terminate without cycling or dropping output limits", async t => {
  const u = await upstream(t, (response, payload) => rejected(response, 400,
    payload.max_tokens ? "max_tokens" : "max_completion_tokens"));
  await assert.rejects(u.request(), { failureCode: "CHAT_PARAMETERS_REJECTED", retryable: false });
  assert.equal(u.calls.length, 2);
  assert.ok(u.calls.every(call => call.max_tokens === 6500 || call.max_completion_tokens === 6500));
});

test("unsupported values and unrelated bad requests do not trigger parameter guessing", async t => {
  for (const [code, message] of [["unsupported_value", "Unsupported value 6500; supported range is 1 to 4096"],
    ["invalid_request_error", "Invalid image content"]]) {
    const u = await upstream(t, response => rejected(response, 400, "max_tokens", message, code));
    await assert.rejects(u.request(), { failureCode: "CHAT_PARAMETERS_REJECTED", retryable: false });
    assert.equal(u.calls.length, 1);
  }
});

test("5xx responses never negotiate parameters even when their body says unsupported", async t => {
  const u = await upstream(t, response => rejected(response, 502, "max_tokens"));
  await assert.rejects(u.request(), { failureCode: "CHAT_UPSTREAM_FAILED", retryable: true, upstreamStatus: 502 });
  assert.equal(u.calls.length, 1);
});

test("unavailable model is a permanent error even when provider uses 503", async t => {
  const u = await upstream(t, response => rejected(response, 503, "model", "model not found", "model_not_found"));
  await assert.rejects(u.request(), { failureCode: "CHAT_MODEL_UNAVAILABLE", retryable: false, upstreamStatus: 503 });
  assert.equal(u.calls.length, 1);
});

test("explicit image rejection never falls back to text-only analysis", async t => {
  const u = await upstream(t, response => rejected(response, 400, "messages", "Image input is not supported by this model"));
  await assert.rejects(u.request(), { failureCode: "CHAT_VISION_UNSUPPORTED", retryable: false });
  assert.equal(u.calls.length, 1);
  assert.deepEqual(u.calls[0].messages, messages);
});

test("authentication failures are permanent and rate limits remain retryable for the caller", async t => {
  for (const [status, failureCode, retryable] of [[401, "CHAT_AUTHENTICATION_FAILED", false], [429, "CHAT_RATE_LIMITED", true]]) {
    const u = await upstream(t, response => rejected(response, status, "reasoning_effort"));
    await assert.rejects(u.request(), { failureCode, retryable, upstreamStatus: status });
    assert.equal(u.calls.length, 1);
  }
});

test("text arrays are combined and incomplete or length-limited results remain failures", async t => {
  const a = await upstream(t, response => success(response, [{ type: "text", text: "one" }, "two"]));
  assert.equal((await a.request()).reply, "onetwo");
  for (const [reason, failureCode, retryable] of [["length", "CHAT_OUTPUT_TRUNCATED", false], [null, "CHAT_INCOMPLETE_RESPONSE", true]]) {
    const u = await upstream(t, response => success(response, '{"ok":true}', reason));
    await assert.rejects(u.request(), { failureCode, retryable });
    assert.equal(u.calls.length, 1);
  }
});

test("negotiation shares one timeout budget instead of granting each attempt another full deadline", async t => {
  const u = await upstream(t, (response, payload) => {
    if (payload.max_tokens) {
      const timer = setTimeout(() => rejected(response, 400, "max_tokens"), 60);
      timer.unref();
    }
  });
  const signals = [];
  await assert.rejects(u.request({ timeoutMs: 150, fetchImpl: (url, options) => {
    signals.push(options.signal);
    return fetch(url, options);
  } }), { failureCode: "CHAT_TIMEOUT", retryable: true });
  assert.equal(u.calls.length, 2);
  assert.equal(signals[0], signals[1]);
  assert.equal(signals[0].aborted, true);
});
