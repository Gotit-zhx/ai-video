import { readFileSync } from "node:fs";
import http from "node:http";
import { once } from "node:events";
import vm from "node:vm";
import { randomUUID } from "node:crypto";
import test from "node:test";
import assert from "node:assert/strict";
import { VideoScriptSession, VIDEO_SCRIPT_REQUEST_TIMEOUT_MS, VIDEO_SCRIPT_TOTAL_TIMEOUT_MS } from "../src/video-script-session.js";

const source = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
function section(start, end) {
  const a = source.indexOf(start);
  const b = source.indexOf(end, a + start.length);
  assert.ok(a >= 0 && b > a, start);
  return source.slice(a, b);
}

async function mockModel(t, respond) {
  const calls = [];
  const server = http.createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const payload = JSON.parse(Buffer.concat(chunks).toString());
    calls.push({ url: request.url, headers: request.headers, payload });
    respond(response, calls.length, payload);
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => {
    server.closeAllConnections();
    return new Promise(resolve => server.close(resolve));
  });
  return {
    calls,
    config: {
      modelId: "gpt-5.5", baseUrl: `http://127.0.0.1:${server.address().port}/v1`,
      apiKey: "fake-http-test-only"
    }
  };
}

function complete(response, content, options = {}) {
  response.writeHead(200, { "Content-Type": "text/event-stream" });
  response.end(`data: ${JSON.stringify({ choices: [{ delta: { content }, finish_reason: "stop", ...options }] })}\n\n`
    + `data: ${JSON.stringify({ choices: [], usage: { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 } })}\n\n`
    + "data: [DONE]\n\n");
}
const storyboard = count => JSON.stringify({
  title: "测试视频", shots: Array.from({ length: count }, (_, i) => ({
    sequence: i + 1, sceneDescription: `动作 ${i + 1}`,
    imagePrompt: { sourceTimestampSeconds: i, sourceDurationSeconds: 9 }
  }))
});
const frame = { type: "image", data: "ZmFrZS1pbWFnZQ==", mimeType: "image/jpeg" };
function session(config, overrides = {}) {
  return new VideoScriptSession({ config, apiKey: config.apiKey, systemPrompt: "测试系统提示", ...overrides });
}

test("full completions endpoint and non-GPT token options are preserved", async t => {
  const mock = await mockModel(t, response => complete(response, [{ type: "text", text: storyboard(9) }]));
  const s = session({ ...mock.config, baseUrl: `${mock.config.baseUrl}/chat/completions/`, modelId: "custom-vision" });
  await s.prompt("test", [frame]);
  assert.equal(mock.calls[0].url, "/v1/chat/completions");
  assert.equal(mock.calls[0].payload.max_tokens, 6500);
  assert.equal(mock.calls[0].payload.reasoning_effort, undefined);
  assert.equal(s.state.messages.at(-1).content[0].text, storyboard(9));
});

test("response header wait and body read both obey the request deadline", async t => {
  for (const sendHeaders of [false, true]) {
    const mock = await mockModel(t, response => {
      if (sendHeaders) {
        response.writeHead(200, { "Content-Type": "application/json" });
        response.write('{"choices":[');
      }
    });
    const s = session(mock.config, { timeoutMs: 100 });
    await assert.rejects(s.prompt("test", [frame]), /请求超时/);
    assert.equal(mock.calls.length, 1);
    assert.equal(s.state.messages.length, 0);
  }
});

test("connection interruption is surfaced without retrying", async t => {
  const mock = await mockModel(t, response => {
    response.writeHead(200, { "Content-Type": "application/json", "Content-Length": "99999" });
    response.write('{"choices":[');
    response.socket.destroy();
  });
  await assert.rejects(session(mock.config).prompt("test", [frame]), /响应中断/);
  assert.equal(mock.calls.length, 1);
});

test("expired total budget prevents further model requests", async t => {
  const mock = await mockModel(t, response => complete(response, storyboard(9)));
  const s = session(mock.config, { totalTimeoutMs: 0 });
  await assert.rejects(s.prompt("test", [frame]), /超过总时间限制/);
  assert.equal(mock.calls.length, 0);
});

test("remaining total budget bounds a request even when its individual timeout is larger", async t => {
  const mock = await mockModel(t, () => {});
  const s = session(mock.config, { timeoutMs: 10_000, totalTimeoutMs: 100 });
  await assert.rejects(s.prompt("test", [frame]), /请求超时/);
  await assert.rejects(s.prompt("next pass"), /超过总时间限制/);
  assert.equal(mock.calls.length, 1);
});

test("a first video pass can use the 900-second budget and subsequent passes only receive remaining time", async t => {
  assert.equal(VIDEO_SCRIPT_TOTAL_TIMEOUT_MS, 900_000);
  assert.equal(VIDEO_SCRIPT_REQUEST_TIMEOUT_MS, 900_000);
  const originalTimeout = AbortSignal.timeout.bind(AbortSignal);
  const limits = [];
  t.mock.method(AbortSignal, "timeout", milliseconds => {
    limits.push(milliseconds);
    return originalTimeout(milliseconds);
  });
  const mock = await mockModel(t, response => complete(response, storyboard(9)));
  const s = session(mock.config);
  await s.prompt("first", [frame]);
  assert.ok(limits[0] > 300_000 && limits[0] <= 900_000);
  s.deadline = Date.now() + 500;
  await s.prompt("audit");
  assert.ok(limits[1] > 0 && limits[1] <= 500);
  assert.equal(mock.calls.length, 2);
});

test("output is bounded, preserves whitespace, and excludes reasoning and tool arguments", () => {
  const progress = new Map();
  const context = vm.createContext({ randomUUID, videoScriptAnalysisProgress: progress });
  vm.runInContext(section("function generatedAssistantCharacters(", "function emptyTokenUsage("), context);
  const reporter = context.createVideoScriptProgressReporter("bounded-output");
  reporter.beginPass("AI_ANALYZING", 58, 84);
  const content = "  " + "真实文字".repeat(10_000) + " \n";
  const event = { type: "message_update", message: { role: "assistant", content: [
    { type: "thinking", thinking: "private reasoning" },
    { type: "toolCall", arguments: "private arguments" },
    { type: "text", text: content }
  ] } };
  reporter.handleEvent(event);
  reporter.handleEvent({ ...event, type: "message_end" });
  assert.equal(progress.get("bounded-output").output, content.slice(-32_000));
  assert.equal(progress.get("bounded-output").outputCharacters, content.length);
  reporter.completePass(85, "AI_RESPONSE_RECEIVED");
  reporter.beginPass("VERIFYING_ACTION_STATES", 90, 93);
  reporter.handleEvent({ type: "message_update", message: { role: "assistant", content: [
    { type: "text", text: "审核输出" }
  ] } });
  const last = progress.get("bounded-output");
  assert.equal(last.output, `${content}\n\n审核输出`.slice(-32_000));
  assert.equal(last.outputCharacters, content.length + 4);
  assert.doesNotMatch(last.output, /private/);
  const retried = context.createVideoScriptProgressReporter("bounded-output");
  retried.beginPass("AI_ANALYZING", 58, 84);
  retried.handleEvent({ type: "message_update", message: { role: "assistant", content: [
    { type: "text", text: "重试返回" }
  ] } });
  const retryStatus = progress.get("bounded-output");
  assert.equal(retryStatus.output, `${last.output}\n\n重试返回`.slice(-32_000));
  assert.equal(retryStatus.outputCharacters, last.outputCharacters + 4);
  assert.ok(retryStatus.progress >= last.progress);
  // A late event from the previous HTTP attempt cannot overwrite the new one.
  reporter.handleEvent({ type: "message_update", message: { role: "assistant", content: [
    { type: "text", text: "较早请求的迟到文字" }
  ] } });
  assert.equal(progress.get("bounded-output").output, retryStatus.output);
});

test("partial text progress never commits an incomplete assistant to conversation history", async t => {
  const mock = await mockModel(t, response => {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.end('data: {"choices":[{"delta":{"content":"partial"}}]}\n\n');
  });
  const s = session(mock.config);
  const events = [];
  s.subscribe(event => events.push(event.type));
  await assert.rejects(s.prompt("test", [frame]), { failureCode: "CHAT_INCOMPLETE_RESPONSE" });
  assert.deepEqual(events, ["message_update"]);
  assert.equal(s.state.messages.length, 0);
  assert.equal(s.messages.length, 1);
});


