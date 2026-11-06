import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { analyzeRecruitment } from "../src/recruitment-analysis.js";
import { RecruitmentOperationStore } from "../src/recruitment-operation-store.js";

const body = n => ({
  mode: "sourcing", model: { modelId: "gpt-5.5", baseUrl: "https://unit.invalid/v1",
    apiFormat: "OPENAI_COMPATIBLE", apiKey: "fixture-secret" },
  systemPrompt: "JSON only", input: { candidates: Array.from({ length: n },
    (_, i) => ({ id: "c" + (i + 1), evidence: "unit evidence" })) },
});
const done = ids => ({ type: "done", message: { stopReason: "stop", content: [
  { type: "text", text: JSON.stringify({ candidates: ids.map(id => ({ id })), limitations: ["unit"] }) },
] } });
const ids = context => JSON.parse(context.messages[0].content[0].text).candidates.map(row => row.id);

test("an interrupted batch reconnects automatically before reporting success", async () => {
  let calls = 0;
  async function* stream(_model, context) {
    calls++;
    if (calls === 1) yield { type: "error", error: { errorMessage: "Stream ended without finish_reason" } };
    else yield done(ids(context));
  }
  const events = [];
  await analyzeRecruitment(body(5), event => events.push(event), { stream, retryDelaysMs: [0] });
  assert.equal(calls, 2); assert.equal(events.at(-1).event, "result");
  assert.equal(events.at(-1).data.candidates.length, 5);
  assert.ok(events.some(event => event.data.recovery === "reconnecting"));
  assert.ok(!events.some(event => event.event === "error"));
});

test("truncated batches split automatically; completed halves never run again", async () => {
  const calls = [];
  async function* stream(_model, context) {
    const current = ids(context); calls.push(current);
    if (current.length > 2) yield { type: "done", message: { stopReason: "length", content: [] } };
    else yield done(current);
  }
  const events = [];
  await analyzeRecruitment(body(5), event => events.push(event), { stream, retryDelaysMs: [0] });
  assert.equal(events.at(-1).event, "result");
  assert.deepEqual(events.at(-1).data.candidates.map(row => row.id), ["c1", "c2", "c3", "c4", "c5"]);
  assert.equal(calls.filter(group => group.join() === "c4,c5").length, 1);
  assert.ok(events.some(event => event.data.recovery === "splitting"));
});

test("a restart retains completed groups and results without storing credentials or inputs", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "hr-recovery-"));
  const id = "a".repeat(64), request = body(12), calls = [];
  async function* failed(_model, context) {
    const current = ids(context); calls.push(current);
    if (current[0] === "c11") yield { type: "error", error: { errorMessage: "connection failed" } };
    else yield done(current);
  }
  async function invoke(store, stream) {
    return store.execute(id, request, async checkpoint => {
      let terminal;
      await analyzeRecruitment(request, event => {
        if (event.event !== "progress") terminal = event;
      }, { stream, retryDelaysMs: [], checkpoint });
      if (terminal.event === "error") throw Object.assign(new Error(terminal.data.message), terminal.data);
      return terminal.data;
    });
  }
  try {
    await assert.rejects(invoke(new RecruitmentOperationStore(dir), failed));
    const saved = await readFile(path.join(dir, id + ".json"), "utf8");
    assert.ok(!saved.includes("fixture-secret")); assert.ok(!saved.includes("unit evidence"));
    async function* resumed(_model, context) { const current = ids(context); calls.push(current); yield done(current); }
    const store = new RecruitmentOperationStore(dir);
    const result = await invoke(store, resumed);
    assert.equal(result.candidates.length, 12);
    assert.equal(calls.filter(group => group[0] === "c1").length, 1);
    assert.equal(calls.filter(group => group[0] === "c11").length, 2);
    assert.deepEqual(await invoke(new RecruitmentOperationStore(dir), resumed), result);
    assert.equal(calls.length, 3, "A completed request is served without another model call");
    await assert.rejects(new RecruitmentOperationStore(dir).execute(id, body(11), () => {
      throw new Error("must not call");
    }), /input changed/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test("permanent model authorization errors stop and cancellation never reconnects", async () => {
  let calls = 0;
  async function* forbidden() { calls++; yield { type: "error", error: { errorMessage: "403 forbidden" } }; }
  const events = [];
  await analyzeRecruitment(body(1), event => events.push(event), { stream: forbidden, retryDelaysMs: [0] });
  assert.equal(calls, 1); assert.equal(events.at(-1).data.upstreamStatus, 403);
  const controller = new AbortController(); controller.abort();
  await analyzeRecruitment(body(1), () => {}, { stream: forbidden, signal: controller.signal, retryDelaysMs: [0] });
  assert.equal(calls, 1);
});

test("restarting a split batch resumes its unfinished half without replaying its parent", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "hr-split-recovery-"));
  const request = body(4), calls = [], id = "b".repeat(64);
  let disconnected = true;
  async function* stream(_model, context) {
    const current = ids(context); calls.push(current);
    if (current.length === 4) yield { type: "done", message: { stopReason: "length", content: [] } };
    else if (disconnected && current[0] === "c3")
      yield { type: "error", error: { errorMessage: "connection failed" } };
    else yield done(current);
  }
  const invoke = store => store.execute(id, request, async checkpoint => {
    let terminal;
    await analyzeRecruitment(request, event => {
      if (event.event !== "progress") terminal = event;
    }, { stream, retryDelaysMs: [], checkpoint });
    if (terminal.event === "error") throw Object.assign(new Error(terminal.data.message), terminal.data);
    return terminal.data;
  });
  try {
    await assert.rejects(invoke(new RecruitmentOperationStore(dir)));
    disconnected = false;
    const result = await invoke(new RecruitmentOperationStore(dir));
    assert.equal(result.candidates.length, 4);
    assert.deepEqual(calls, [["c1","c2","c3","c4"], ["c1","c2"], ["c3","c4"], ["c3","c4"]]);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
