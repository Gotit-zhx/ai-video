import { test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { analyzeRecruitment, recruitmentModel, parseRecruitmentResult } from "../src/recruitment-analysis.js";

async function fixture(fn) {
  const server = http.createServer(fn);
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  return { url: `http://127.0.0.1:${server.address().port}`, close: () => new Promise(resolve => server.close(resolve)) };
}
function chunk(choice) { return `data: ${JSON.stringify({ choices: [choice] })}\n\n`; }
const body = (modelId, baseUrl) => ({
  model: { modelId, baseUrl, apiFormat: "OPENAI_COMPATIBLE", apiKey: "fixture-secret",
    fallbackModels: [{ modelId: "must-not-call" }] },
  systemPrompt: "Return JSON only.", input: { position: "Engineer" },
});

for (const id of ["gpt-5.5", "grok-4.6", "deepseek-chat"]) {
  test(`actual pi SDK: ${id} uses one selected model, normalized URL, no tools`, async () => {
    let calls = 0, payload, route;
    const f = await fixture(async (req, res) => {
      calls++; route = req.url;
      let raw = ""; for await (const part of req) raw += part;
      payload = JSON.parse(raw);
      assert.equal(req.headers.authorization, "Bearer fixture-secret");
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.end(chunk({ index: 0, delta: { content: '{"summary":' }, finish_reason: null })
        + chunk({ index: 0, delta: { content: '"complete"}' }, finish_reason: null })
        + chunk({ index: 0, delta: {}, finish_reason: "stop" }) + "data: [DONE]\n\n");
    });
    try {
      const events = [];
      await analyzeRecruitment(body(id, id === "grok-4.6" ? f.url : f.url + "/v1/chat/completions"), e => events.push(e));
      assert.equal(calls, 1); assert.equal(route, "/v1/chat/completions");
      assert.equal(payload.model, id); assert.equal(payload.stream, true);
      assert.equal(payload.tools, undefined); assert.equal(payload.stream_options, undefined);
      assert.equal(payload[id === "gpt-5.5" ? "max_completion_tokens" : "max_tokens"], 10000);
      assert.equal(payload.reasoning_effort, id === "gpt-5.5" ? "low" : undefined);
      assert.deepEqual(payload.response_format, { type: "json_object" });
      assert.deepEqual(events.at(-1), { event: "result", data: { summary: "complete" } });
      assert.ok(events.some(e => e.data.phase === "validating"));
      assert.ok(!JSON.stringify(events).includes("fixture-secret"));
    } finally { await f.close(); }
  });
}

test("actual SDK provider 502 is redacted and never retried or switched", async () => {
  let calls = 0;
  const f = await fixture((req, res) => {
    calls++; req.resume(); res.writeHead(502, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: { message: "fixture-secret private-url" } }));
  });
  try {
    const events = [];
    await analyzeRecruitment(body("gpt-5.5", f.url + "/v1"), e => events.push(e));
    assert.equal(calls, 1);
    assert.equal(events.at(-1).event, "error");
    assert.equal(events.at(-1).data.code, "HR_AI_UPSTREAM_HTTP");
    assert.equal(events.at(-1).data.upstreamStatus, 502);
    assert.ok(!JSON.stringify(events).includes("fixture-secret"));
  } finally { await f.close(); }
});

test("only an explicit JSON parameter rejection is negotiated once on the same model", async () => {
  let calls=0;
  const f=await fixture(async(req,res)=>{
    calls++;let raw="";for await(const part of req)raw+=part;const payload=JSON.parse(raw);
    assert.equal(payload.model,"gpt-5.5");
    if(payload.response_format){
      res.writeHead(400,{"Content-Type":"application/json"});
      res.end(JSON.stringify({error:{message:"Unsupported parameter: response_format",param:"response_format"}}));return;
    }
    res.writeHead(200,{"Content-Type":"text/event-stream"});
    res.end(chunk({index:0,delta:{content:'{"ok":true}'},finish_reason:"stop"})+"data: [DONE]\n\n");
  });
  try {
    const events=[];
    await analyzeRecruitment(body("gpt-5.5",f.url),e=>events.push(e));
    assert.equal(calls,2);assert.equal(events.at(-1).event,"result");
    await analyzeRecruitment(body("gpt-5.5",f.url),()=>{});
    assert.equal(calls,3,"The rejected capability is remembered without another rejected request");
  } finally {await f.close();}
});

test("actual SDK truncation and broken stream cannot publish successful JSON", async () => {
  for (const finish of ["length", null]) {
    const f = await fixture((req, res) => {
      req.resume(); res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.end(chunk({ index: 0, delta: { content: '{"valid":"json"}' }, finish_reason: finish }) + "data: [DONE]\n\n");
    });
    try {
      const events = [];
      await analyzeRecruitment(body("grok-4.6", f.url), e => events.push(e));
      assert.equal(events.at(-1).event, "error");
      assert.equal(events.at(-1).data.code, finish ? "HR_AI_TRUNCATED" : "HR_AI_INVALID_STREAM");
      assert.ok(!events.some(e => e.event === "result"));
    } finally { await f.close(); }
  }
});

test("fenced JSON is normalized but missing results and tool calls stay errors", async () => {
  assert.equal(recruitmentModel(body("grok-4.6", "https://example.invalid/chat/completions").model).baseUrl,
    "https://example.invalid");
  for (const [message, code] of [
    [{ stopReason: "stop", content: [{ type: "text", text: "```json\n{\"a\":1}\n```" }] }, null],
    [{ stopReason: "toolUse", content: [{ type: "toolCall", name: "send" }] }, "HR_AI_TOOLS"],
    [{ stopReason: "stop", content: [{ type: "text", text: "not json" }] }, "HR_AI_INVALID_JSON"],
  ]) {
    const events = [];
    async function* mockStream() { yield { type: "done", message }; }
    await analyzeRecruitment(body("gpt-5.5", "https://example.invalid"), e => events.push(e), { stream: mockStream });
    assert.equal(events.at(-1).event, code ? "error" : "result");
    if (code) assert.equal(events.at(-1).data.code, code);
  }
  assert.throws(() => recruitmentModel({ apiFormat: "OTHER" }), /协议/);
});

test("actual SDK accepts a complete result surrounded by reasoning and explanation", async () => {
  const f = await fixture((req, res) => {
    req.resume(); res.writeHead(200, { "Content-Type": "text/event-stream" });
    res.end(chunk({ index: 0, delta: { content:
      '<think>Draft {"candidate": "do not publish"}</think>\n分析结果如下：\n```json\n{"candidates":[{"id":"c1","evidence":"uses {braces}"}],}\n```\n以上仅供参考。'
    }, finish_reason: "stop" }) + "data: [DONE]\n\n");
  });
  try {
    const events = [];
    await analyzeRecruitment(body("deepseek-chat", f.url), e => events.push(e));
    assert.deepEqual(events.at(-1), { event: "result", data: { candidates: [{ id: "c1", evidence: "uses {braces}" }] } });
  } finally { await f.close(); }
});

test("JSON normalization rejects incomplete, ambiguous or missing values", () => {
  for (const text of [
    '{"candidates":[{"id":"c1"}]', '{"a":1} {"a":2}', '[{"a":1}]',
    '<think>unfinished {"a":1}', '{"a":}', '{"a":"unterminated}',
  ]) assert.throws(() => parseRecruitmentResult(text), { code: "HR_AI_INVALID_JSON" });
  assert.deepEqual(parseRecruitmentResult("结果：{summary:'complete', candidates:[],}"), { summary: "complete", candidates: [] });
});
