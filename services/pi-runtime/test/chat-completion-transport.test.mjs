import http from "node:http";
import { once } from "node:events";
import test from "node:test";
import assert from "node:assert/strict";
import { chatCompletionEndpoint, requestChatCompletion } from "../src/chat-completion-transport.js";

test("tool arguments preserve deltas and accept complete monotonic gateway snapshots", async t => {
  for (const fragments of [
    ['{"path":"', '/fixture.jpg', '"}'],
    ['{"path":"', '{"path":"/fixture.jpg', '{"path":"/fixture.jpg"}'],
    ['{"path":"/fixture.jpg"}', '{"path":"/fixture.jpg"}'],
    [{ path: "/fixture.jpg" }]
  ]) {
    const mock = await upstream(t, response => {
      response.writeHead(200, { "Content-Type": "text/event-stream" });
      fragments.forEach(argumentsValue => response.write(`data: ${JSON.stringify({ choices: [{
        delta: { tool_calls: [{ index: 0, id: "read-1", type: "function",
          function: { name: "read", arguments: argumentsValue } }] }
      }] })}\n\n`));
      response.end('data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}\n\n' + "data: [DONE]\n\n");
    });
    const result = await mock.request({ allowToolCalls: true, tools: [{
      type: "function", function: { name: "read", parameters: { type: "object" } }
    }] });
    assert.equal(result.toolCalls[0].id, "read-1");
    assert.equal(result.toolCalls[0].function.name, "read");
    assert.deepEqual(JSON.parse(result.toolCalls[0].function.arguments), { path: "/fixture.jpg" });
    assert.equal(mock.calls.length, 1);
  }
});

test("tool snapshots never repair truncated, conflicting, or repeated text fragments", async t => {
  for (const [fragments, expected] of [
    [['{"path":"', "a", "a", '"}'], '{"path":"aa"}'],
    [['{"path":"/one"}', '{"path":"/two"}'], '{"path":"/one"}{"path":"/two"}'],
    [['{"path":"', '{"path":"/unfinished'], '{"path":"{"path":"/unfinished']
  ]) {
    const mock = await upstream(t, response => {
      response.writeHead(200, { "Content-Type": "text/event-stream" });
      fragments.forEach((argumentsValue, i) => response.write(`data: ${JSON.stringify({ choices: [{
        delta: { tool_calls: [{ index: 0, ...(i ? {} : { id: "read-1", type: "function" }),
          function: { ...(i ? {} : { name: "read" }), arguments: argumentsValue } }] }
      }] })}\n\n`));
      response.end('data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}\n\n' + "data: [DONE]\n\n");
    });
    const result = await mock.request({ allowToolCalls: true, tools: [{
      type: "function", function: { name: "read", parameters: { type: "object" } }
    }] });
    assert.equal(result.toolCalls[0].function.arguments, expected);
    assert.equal(mock.calls.length, 1);
  }
});

test("tool-capable SSE rejects sparse indexes and duplicate tool call IDs before execution", async t => {
  const call = (index, id) => ({ index, id, type: "function", function: { name: "read", arguments: '{"path":"/fixture.jpg"}' } });
  for (const toolCalls of [[call(1, "read-1")], [call(0, "same-id"), call(1, "same-id")]]) {
    const mock = await upstream(t, response => {
      response.writeHead(200, { "Content-Type": "text/event-stream" });
      response.end(`data: ${JSON.stringify({ choices: [{ delta: { tool_calls: toolCalls }, finish_reason: "tool_calls" }] })}\n\n` + "data: [DONE]\n\n");
    });
    await assert.rejects(mock.request({ allowToolCalls: true, tools: [{ type: "function", function: {
      name: "read", parameters: { type: "object", properties: { path: { type: "string" } } }
    } }] }), { failureCode: "CHAT_INVALID_TOOL_CALL" });
  }
});

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
      assert.equal(payload.stream, true);
      assert.equal(payload.reasoning_effort, undefined);
      assert.equal(payload.stream_options, undefined);
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

function sse(response, events) {
  response.writeHead(200, { "Content-Type": "text/event-stream; charset=utf-8" });
  response.end(events);
}
const event = (text, finish = null) => `data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: text }, finish_reason: finish }] })}\n\n`;

test("SSE preserves UTF-8 and CRLF split across network chunks, multi-line data, comments and usage", async t => {
  const u = await upstream(t, (response) => {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    const bytes = Buffer.from(': heartbeat\r\nevent: message\r\ndata: {"choices":\r\ndata: [{"index":0,"delta":{"content":"双袖完整"},"finish_reason":null}]}\r\n\r\n'
      + event("", "stop") + 'data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}\n\n'
      + 'data: [DONE]\r\n\r\n');
    let offset = 0;
    const send = () => {
      if (response.destroyed) return;
      if (offset >= bytes.length) { response.end(); return; }
      response.write(bytes.subarray(offset, ++offset));
      setImmediate(send);
    };
    send();
  });
  const result = await u.request();
  assert.equal(result.reply, "双袖完整");
  assert.equal(result.usage.total_tokens, 30);
  assert.equal(u.calls[0].stream, true);
  assert.equal(u.calls[0].stream_options, undefined);
  assert.equal(u.calls[0].reasoning_effort, undefined);
});

test("SSE requires both an explicit finish reason and DONE, even if partial text parses as JSON", async t => {
  for (const wire of [event('{"ok":true}', "stop"), event('{"ok":true}') + 'data: [DONE]\n\n']) {
    const u = await upstream(t, response => sse(response, wire));
    await assert.rejects(u.request(), { failureCode: "CHAT_INCOMPLETE_RESPONSE", retryable: true });
    assert.equal(u.calls.length, 1);
  }
});

test("SSE length termination is rejected instead of accepting a truncated result", async t => {
  const u = await upstream(t, response => sse(response, event('{"ok":true}', "length") + 'data: [DONE]\n\n'));
  await assert.rejects(u.request(), { failureCode: "CHAT_OUTPUT_TRUNCATED", retryable: false });
});

test("SSE error events interrupt completion and do not trigger compatibility guesses", async t => {
  const u = await upstream(t, response => sse(response, event("partial")
    + 'event: error\ndata: {"error":{"code":"upstream_error","message":"provider unavailable"}}\n\n'));
  await assert.rejects(u.request(), { failureCode: "CHAT_UPSTREAM_FAILED", retryable: true });
  assert.equal(u.calls.length, 1);
});

test("SSE named error event is recognized when the payload has no error envelope", async t => {
  const u = await upstream(t, response => sse(response,
    'event: error\ndata: {"code":"model_not_found","message":"model not found"}\n\n'));
  await assert.rejects(u.request(), { failureCode: "CHAT_MODEL_UNAVAILABLE", retryable: false });
});

test("SSE malformed JSON events fail even when a later event claims completion", async t => {
  const u = await upstream(t, response => sse(response, 'data: {bad}\n\n' + event("valid", "stop") + 'data: [DONE]\n\n'));
  await assert.rejects(u.request(), { failureCode: "CHAT_INVALID_RESPONSE" });
});

test("SSE body idle time is bounded after receiving partial output", async t => {
  const u = await upstream(t, response => {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.write(event("partial"));
  });
  await assert.rejects(u.request({ timeoutMs: 100 }), { failureCode: "CHAT_TIMEOUT", retryable: true });
  assert.equal(u.calls.length, 1);
});

test("SSE can complete after token-field negotiation while retaining all images and the original budget", async t => {
  const u = await upstream(t, (response, payload) => payload.max_tokens
    ? rejected(response, 400, "max_tokens")
    : sse(response, event('{"complete":true}', "stop") + 'data: [DONE]\n\n'));
  const result = await u.request();
  assert.equal(result.reply, '{"complete":true}');
  assert.equal(u.calls.length, 2);
  for (const call of u.calls) {
    assert.deepEqual(call.messages, messages);
    assert.equal(call.max_tokens || call.max_completion_tokens, 6500);
    assert.equal(call.stream_options, undefined);
    assert.equal(call.reasoning_effort, undefined);
  }
});

test("text is delivered before upstream finishes and buffered responses are identified", async t => {
  let release;
  const released = new Promise(resolve => { release = resolve; });
  const activity = [], text = [];
  const mock = await upstream(t, response => {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.write('data: {"choices":[{"delta":{"content":"first"}}]}\n\n');
    released.then(() => response.end('data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'));
  });
  let arrived;
  const first = new Promise(resolve => { arrived = resolve; });
  const pending = mock.request({ onActivity: value => activity.push(value), onTextDelta: value => { text.push(value); arrived(); } });
  try {
    await first;
    assert.deepEqual(text, ["first"]);
    assert.deepEqual(activity, ["WAITING_RESPONSE", "STREAM_CONNECTED", "TEXT_STREAMING"]);
  } finally { release(); }
  await pending;
  const buffered = await upstream(t, response => success(response));
  const states = [];
  await buffered.request({ onActivity: value => states.push(value) });
  assert.deepEqual(states, ["WAITING_RESPONSE", "BUFFERED_RESPONSE"]);
});

test("reasoning activity is reported without exposing it as visible model text", async t => {
  const mock = await upstream(t, response => {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.end('data: {"choices":[{"delta":{"reasoning_content":"private computation"}}]}\n\ndata: {"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n');
  });
  const activity=[],text=[];
  await mock.request({reasoningEffort:"low",onActivity:v=>activity.push(v),onTextDelta:v=>text.push(v)});
  assert.equal(mock.calls[0].reasoning_effort,"low");
  assert.ok(activity.includes("REASONING"));
  assert.deepEqual(text,["answer"]);
});

test("an explicitly unsupported reasoning hint falls back without changing model or images", async t => {
  const mock=await upstream(t,(response,payload)=>payload.reasoning_effort
    ? rejected(response,400,"reasoning_effort") : success(response));
  await mock.request({reasoningEffort:"low"});
  assert.equal(mock.calls.length,2);
  assert.equal(mock.calls[0].reasoning_effort,"low");
  assert.equal(mock.calls[1].reasoning_effort,undefined);
  assert.deepEqual(mock.calls[1].messages,messages);
  assert.equal(mock.calls[1].model,mock.calls[0].model);
});

test('compatible clean EOF is accepted only after an explicit successful finish', async t => {
  for(const finish of ['stop',null,'length']) {
    const mock=await upstream(t,response=>{
      response.writeHead(200,{'Content-Type':'text/event-stream'});
      response.end(`data: ${JSON.stringify({choices:[{delta:{content:'{"ok":true}'},finish_reason:finish}]})}\n\n`);
    });
    if(finish==='stop')assert.equal((await mock.request({allowMissingDone:true})).reply,'{"ok":true}');
    else await assert.rejects(mock.request({allowMissingDone:true}));
  }
});

test('driver fallback keeps prompt and images, uses separate credentials and stays bounded', async () => {
  const calls = [];
  const config = { baseUrl: 'https://primary.test', modelId: 'primary', fallbackModels: [
    { baseUrl: 'https://backup.test', modelId: 'grok-4.6', apiKey: 'backup-key' }
  ] };
  const result = await requestChatCompletion({ config, apiKey: 'primary-key', messages,
    fetchImpl: async (url, options) => {
      calls.push({ url, auth: options.headers.Authorization, body: JSON.parse(options.body) });
      return calls.length === 1 ? new Response('{"error":{"message":"maintenance"}}', { status: 403 })
        : new Response(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content: 'OK' } }] }));
    } });
  assert.equal(result.reply, 'OK'); assert.equal(result.fallbackUsed, true);
  assert.equal(calls.length, 2); assert.equal(calls[1].auth, 'Bearer backup-key');
  assert.deepEqual(calls[1].body.messages, messages); assert.equal(calls[1].body.model, 'grok-4.6');
});

test('never switches on content filtering, caller cancellation, or already displayed partial text', async () => {
  for (const mode of ['filter', 'cancel', 'partial']) {
    let calls = 0;
    const controller = new AbortController();
    await assert.rejects(requestChatCompletion({
      config: { baseUrl: 'https://primary.test', modelId: 'p', fallbackModels: [{ baseUrl: 'https://b.test', modelId: 'b', apiKey: 'b' }] },
      apiKey: 'p', messages, signal: controller.signal, onTextDelta: () => {},
      fetchImpl: async () => {
        calls++;
        if (mode === 'cancel') { controller.abort(); throw new DOMException('aborted', 'AbortError'); }
        if (mode === 'partial') return new Response('data: {"choices":[{"delta":{"content":"partial"}}]}\n\n', { headers: { 'content-type': 'text/event-stream' } });
        return new Response(JSON.stringify({ choices: [{ finish_reason: 'content_filter', message: { content: '' } }] }));
      }
    }));
    assert.equal(calls, 1, mode);
  }
});


test('fallback startup deadline does not interrupt an actively streaming primary', async t => {
  const primary=await upstream(t,response=>{
    response.writeHead(200,{'Content-Type':'text/event-stream'});
    response.write('data: {"choices":[{"delta":{"content":"start"}}]}\n\n');
    setTimeout(()=>response.end('data: {"choices":[{"delta":{"content":" end"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'),650);
  });
  const backup=await upstream(t,response=>success(response));
  const result=await primary.request({timeoutMs:1000,config:{...primary.config,fallbackModels:[{...backup.config,apiKey:'backup'}]}});
  assert.equal(result.reply,'start end');assert.equal(primary.calls.length,1);assert.equal(backup.calls.length,0);
});

test('silent primary still switches to backup within the shared deadline', async t => {
  const primary=await upstream(t,()=>{});
  const backup=await upstream(t,response=>success(response));
  const result=await primary.request({timeoutMs:1000,config:{...primary.config,fallbackModels:[{...backup.config,apiKey:'backup'}]}});
  assert.equal(result.fallbackUsed,true);assert.equal(primary.calls.length,1);assert.equal(backup.calls.length,1);
});

const idleRouteConfig = { baseUrl: 'https://idle-primary.test', modelId: 'idle-primary', fallbackModels: [
  { baseUrl: 'https://idle-backup.test', modelId: 'grok-4.6', apiKey: 'backup-only-test' }
] };
const idleRequest = overrides => requestChatCompletion({
  config: idleRouteConfig, apiKey: 'primary-only-test', messages, timeoutMs: 40,
  activityTimeoutOnly: true, idleTimeoutMs: 80, fallbackIdleTimeoutMs: 60,
  fallbackOnPartialResponse: true, ...overrides
});
const sseDelta = delta => `data: ${JSON.stringify({ choices: [{ delta }] })}\n\n`;
const completeResponse = content => new Response(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content } }] }));
function timedSse(signal, { initial = '', repeat = '', intervalMs = 15, completeMs, onCleanup } = {}) {
  let interval, finish, abort, closed = false;
  const cleanup = () => {
    if (closed) return;
    closed = true; clearInterval(interval); clearTimeout(finish);
    signal.removeEventListener('abort', abort); onCleanup?.();
  };
  return new Response(new ReadableStream({
    start(controller) {
      const send = value => { if (!closed) controller.enqueue(new TextEncoder().encode(value)); };
      abort = () => { if (!closed) { cleanup(); controller.error(signal.reason); } };
      signal.addEventListener('abort', abort, { once: true });
      if (signal.aborted) { abort(); return; }
      if (initial) send(initial);
      if (repeat) interval = setInterval(() => send(repeat), intervalMs);
      if (completeMs != null) finish = setTimeout(() => {
        send('data: {"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n');
        cleanup(); controller.close();
      }, completeMs);
    },
    cancel() { cleanup(); }
  }), { headers: { 'content-type': 'text/event-stream' } });
}

test('style idle-only routing waits for active reasoning or text to finish beyond fixed wall budgets', async () => {
  for (const delta of [{ reasoning_content: 'thinking' }, { content: 'part ' }]) {
    let calls = 0, cleaned = 0;
    const started = Date.now();
    const result = await idleRequest({ fetchImpl: async (_url, { signal }) => {
      calls++;
      return timedSse(signal, { initial: sseDelta(delta), repeat: sseDelta(delta), completeMs: 240, onCleanup: () => cleaned++ });
    } });
    assert.ok(Date.now() - started >= 200, 'complete response, rather than an old hard deadline, decides completion');
    assert.equal(calls, 1); assert.equal(cleaned, 1); assert.equal(result.fallbackUsed, false);
    assert.ok(result.reply.endsWith('done'));
  }
});

test('style idle routing discards stalled internal JSON and preserves full prompt and images for Grok', async () => {
  const calls = [], activities = []; let cleaned = 0;
  const result = await idleRequest({ onActivity: value => activities.push(value), fetchImpl: async (url, options) => {
    calls.push({ url, body: JSON.parse(options.body), auth: options.headers.Authorization });
    return calls.length === 1
      ? timedSse(options.signal, { initial: sseDelta({ content: '{"unfinished":' }), onCleanup: () => cleaned++ })
      : completeResponse('{"complete":true}');
  } });
  assert.equal(result.reply, '{"complete":true}'); assert.equal(result.modelId, 'grok-4.6');
  assert.equal(result.fallbackUsed, true); assert.equal(calls.length, 2); assert.equal(cleaned, 1);
  assert.deepEqual(calls[1].body.messages, messages); assert.equal(calls[1].auth, 'Bearer backup-only-test');
  assert.ok(activities.includes('SWITCHING_MODEL'));
});

test('SSE keepalive comments and empty deltas never postpone the style idle timeout', async () => {
  let calls = 0, cleaned = 0;
  const result = await idleRequest({ fetchImpl: async (_url, { signal }) => ++calls === 1
    ? timedSse(signal, { repeat: ': heartbeat\n\n' + sseDelta({}), onCleanup: () => cleaned++ })
    : completeResponse('backup') });
  assert.equal(result.reply, 'backup'); assert.equal(calls, 2); assert.equal(cleaned, 1);
});

test('style fallback never replays displayed text, tool-capable text, or emitted tool calls', async () => {
  for (const mode of ['displayed', 'tool-capable-text', 'tool-output']) {
    let calls = 0, cleaned = 0; const displayed = [];
    const delta = mode === 'tool-output'
      ? { tool_calls: [{ index: 0, id: 'read-1', type: 'function', function: { name: 'read', arguments: '{' } }] }
      : { content: 'already started' };
    await assert.rejects(idleRequest({
      ...(mode === 'displayed' ? { onTextDelta: value => displayed.push(value) } : {}),
      allowToolCalls: mode === 'tool-capable-text',
      fetchImpl: async (_url, { signal }) => {
        calls++; return timedSse(signal, { initial: sseDelta(delta), onCleanup: () => cleaned++ });
      }
    }), { failureCode: 'CHAT_TIMEOUT' });
    assert.equal(calls, 1, mode); assert.equal(cleaned, 1, mode);
    if (mode === 'displayed') assert.deepEqual(displayed, ['already started']);
  }
});

test('backup uses its own idle window and can finish active output beyond the old overall deadline', async () => {
  let calls = 0;
  const result = await idleRequest({ fetchImpl: async (_url, { signal }) => ++calls === 1
    ? new Response('{"error":{"message":"temporarily unavailable"}}', { status: 502 })
    : timedSse(signal, { repeat: sseDelta({ reasoning: 'thinking' }), completeMs: 200 }) });
  assert.equal(result.reply, 'done'); assert.equal(calls, 2); assert.equal(result.fallbackUsed, true);
  calls = 0;
  const started = Date.now();
  await assert.rejects(idleRequest({ idleTimeoutMs: 600, fallbackIdleTimeoutMs: 70,
    fetchImpl: async (_url, { signal }) => ++calls === 1
      ? new Response('{"error":{"message":"temporarily unavailable"}}', { status: 502 })
      : timedSse(signal) }), { failureCode: 'CHAT_TIMEOUT' });
  assert.equal(calls, 2); assert.ok(Date.now() - started < 450, 'backup uses 70 ms rather than the primary 600 ms idle window');
});

test('without a backup style still waits for progress but terminates a silent connection', async () => {
  const config = { ...idleRouteConfig, fallbackModels: [] };
  const result = await idleRequest({ config, fetchImpl: async (_url, { signal }) =>
    timedSse(signal, { repeat: sseDelta({ reasoning_content: 'thinking' }), completeMs: 200 }) });
  assert.equal(result.reply, 'done');
  await assert.rejects(idleRequest({ config, fetchImpl: async (_url, { signal }) => timedSse(signal) }), { failureCode: 'CHAT_TIMEOUT' });
});

test('style idle-only mode disables the default earlier first-activity cutoff', async () => {
  let calls = 0;
  const result = await idleRequest({ idleTimeoutMs: 200, fetchImpl: async (_url, { signal }) => {
    calls++; return timedSse(signal, { completeMs: 110 });
  } });
  assert.equal(calls, 1); assert.equal(result.reply, 'done'); assert.equal(result.fallbackUsed, false);
});

test('style cancellation and content filtering remain terminal with no fallback', async () => {
  for (const mode of ['cancel', 'filter']) {
    let calls = 0;
    const controller = new AbortController();
    const operation = idleRequest({ signal: controller.signal, fetchImpl: async (_url, { signal }) => {
      calls++;
      return mode === 'cancel' ? timedSse(signal, { repeat: sseDelta({ reasoning_content: 'thinking' }) })
        : new Response(JSON.stringify({ choices: [{ finish_reason: 'content_filter', message: { content: '' } }] }));
    } });
    if (mode === 'cancel') controller.abort();
    await assert.rejects(operation, { failureCode: mode === 'cancel' ? 'CHAT_TIMEOUT' : 'CHAT_CONTENT_FILTERED' });
    assert.equal(calls, 1);
  }
});
