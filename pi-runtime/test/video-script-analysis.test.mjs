import { readFileSync } from "node:fs";
import http from "node:http";
import { once } from "node:events";
import vm from "node:vm";
import { randomUUID } from "node:crypto";
import test from "node:test";
import assert from "node:assert/strict";
import { VideoScriptSession } from "../src/video-script-session.js";

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
  response.writeHead(200, { "Content-Type": "application/json" });
  response.end(JSON.stringify({
    choices: [{ message: { content }, finish_reason: "stop", ...options }],
    usage: { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 }
  }));
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
function pipeline(config, options = {}) {
  const progress = new Map();
  const context = vm.createContext({
    console: { warn() {}, info() {} }, randomUUID,
    videoScriptAnalysisProgress: progress,
    VIDEO_SCRIPT_ANALYSIS_PROMPT: "测试视频分析规则",
    VIDEO_SCRIPT_DISPATCHER: undefined,
    VideoScriptSession: class extends VideoScriptSession {
      constructor(args) { super({ ...args, ...options }); }
    },
    jsonrepair: value => value
  });
  vm.runInContext(
    section("function messageText(", "function addTokenUsage(") +
    section("function extractJsonObject(", "async function handleVideoScriptAnalysis("), context);
  return {
    progress,
    run: () => context.runVideoScriptAnalysis({
      model: config, progressId: "test-progress", context: { durationSeconds: 9 },
      frames: [{ ...frame, timestampSeconds: 0 }, { ...frame, timestampSeconds: 8 }]
    })
  };
}

test("analysis and action audit send complete HTTP requests with image history, nine shots and progress", async t => {
  const mock = await mockModel(t, response => complete(response, storyboard(9)));
  const p = pipeline(mock.config);
  const result = await p.run();
  assert.equal(result.result.shots.length, 9);
  assert.equal(result.actionStateAudited, true);
  assert.equal(result.modelId, "gpt-5.5");
  assert.equal(result.usage.modelCalls, 2);
  assert.equal(result.usage.totalTokens, 300);
  assert.equal(mock.calls.length, 2);
  for (const { url, headers, payload } of mock.calls) {
    assert.equal(url, "/v1/chat/completions");
    assert.equal(headers.authorization, "Bearer fake-http-test-only");
    assert.equal(payload.stream, false);
    assert.equal(payload.model, "gpt-5.5");
    assert.equal(payload.max_tokens, 6500);
    assert.equal(payload.reasoning_effort, undefined);
    assert.equal(payload.messages[1].content.filter(part => part.type === "image_url").length, 2);
  }
  assert.equal(mock.calls[1].payload.messages.length, 4);
  assert.match(mock.calls[1].payload.messages[3].content[0].text, /审计/);
  const status = p.progress.get("test-progress");
  assert.equal(status.stage, "RESULT_READY");
  assert.equal(status.done, true);
  assert.ok(status.outputCharacters >= storyboard(9).length * 2);
});

test("full completions endpoint and non-GPT token options are preserved", async t => {
  const mock = await mockModel(t, response => complete(response, [{ type: "text", text: storyboard(9) }]));
  const s = session({ ...mock.config, baseUrl: `${mock.config.baseUrl}/chat/completions/`, modelId: "custom-vision" });
  await s.prompt("test", [frame]);
  assert.equal(mock.calls[0].url, "/v1/chat/completions");
  assert.equal(mock.calls[0].payload.max_tokens, 6500);
  assert.equal(mock.calls[0].payload.reasoning_effort, undefined);
  assert.equal(s.state.messages.at(-1).content[0].text, storyboard(9));
});

test("a valid HTTP completion containing invalid storyboard JSON can be corrected with history", async t => {
  const mock = await mockModel(t, (response, call) => complete(response, call === 1 ? "invalid storyboard" : storyboard(9)));
  const result = await pipeline(mock.config).run();
  assert.equal(result.result.shots.length, 9);
  assert.equal(mock.calls.length, 3);
  assert.match(mock.calls[1].payload.messages.at(-1).content[0].text, /JSON 无法通过解析/);
});

test("eight shots trigger a correction before action audit", async t => {
  const mock = await mockModel(t, (response, call) => complete(response, storyboard(call === 1 ? 8 : 9)));
  const result = await pipeline(mock.config).run();
  assert.equal(result.result.shots.length, 9);
  assert.equal(mock.calls.length, 3);
  assert.match(mock.calls[1].payload.messages.at(-1).content[0].text, /恰好 9 个分镜/);
});

test("persistent wrong shot count fails and publishes FAILED instead of returning partial results", async t => {
  const mock = await mockModel(t, response => complete(response, storyboard(8)));
  const p = pipeline(mock.config);
  await assert.rejects(p.run(), /仍返回 8 个分镜/);
  assert.equal(mock.calls.length, 2);
  assert.equal(p.progress.get("test-progress").stage, "FAILED");
  assert.equal(p.progress.get("test-progress").done, true);
});

for (const reason of ["length", undefined, null, "content_filter"]) {
  test(`finish_reason=${reason} rejects even valid nine-shot content without a hidden retry`, async t => {
    const mock = await mockModel(t, response => complete(response, storyboard(9), { finish_reason: reason }));
    const p = pipeline(mock.config);
    await assert.rejects(p.run(), /响应未正常结束/);
    assert.equal(mock.calls.length, 1);
    assert.equal(p.progress.get("test-progress").stage, "FAILED");
  });
}

test("truncated HTTP JSON is rejected before storyboard JSON repair", async t => {
  const mock = await mockModel(t, response => response.end('{"choices":[{"message":{"content":"partial'));
  await assert.rejects(pipeline(mock.config).run(), /不是完整 JSON/);
  assert.equal(mock.calls.length, 1);
});

test("a proxy SSE response without finish_reason is rejected, not repaired", async t => {
  const mock = await mockModel(t, response => {
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.end('data: {"choices":[{"delta":{"content":"partial"}}]}\n\n');
  });
  await assert.rejects(pipeline(mock.config).run(), /不是完整 JSON/);
  assert.equal(mock.calls.length, 1);
});

test("HTTP upstream failure is surfaced once without exposing response body", async t => {
  const mock = await mockModel(t, response => {
    response.writeHead(503);
    response.end("sensitive-upstream-details");
  });
  await assert.rejects(pipeline(mock.config).run(), error => {
    assert.match(error.message, /503/);
    assert.doesNotMatch(error.message, /sensitive-upstream-details/);
    return true;
  });
  assert.equal(mock.calls.length, 1);
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

test("audit timeout retains the complete validated initial nine-shot result", async t => {
  const mock = await mockModel(t, (response, call) => {
    if (call === 1) complete(response, storyboard(9));
  });
  const p = pipeline(mock.config, { timeoutMs: 100 });
  const result = await p.run();
  assert.equal(result.actionStateAudited, false);
  assert.equal(result.result.shots.length, 9);
  assert.equal(mock.calls.length, 2);
  assert.equal(p.progress.get("test-progress").stage, "RESULT_READY");
});

test("an audit that cannot keep nine shots falls back to the validated initial result", async t => {
  const mock = await mockModel(t, (response, call) => complete(response, storyboard(call === 1 ? 9 : 8)));
  const result = await pipeline(mock.config).run();
  assert.equal(result.actionStateAudited, false);
  assert.equal(result.result.shots.length, 9);
  assert.equal(mock.calls.length, 3);
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
