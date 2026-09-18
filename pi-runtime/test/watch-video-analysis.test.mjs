import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import vm from "node:vm";
import path from "node:path";
import os from "node:os";
import { once } from "node:events";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { mkdtemp, readFile, rm, stat, realpath, writeFile, symlink, access } from "node:fs/promises";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { AgentHarness, InMemorySessionRepo, loadSkills } from "@earendil-works/pi-agent-core";
import { NodeExecutionEnv } from "@earendil-works/pi-agent-core/node";
import { Type } from "typebox";
import { jsonrepair } from "jsonrepair";
import { createWatchModelTransport } from "../src/watch-model-transport.js";

const execFileAsync = promisify(execFile);
const source = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
const section = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start) + start.length));
const skills = process.env.WATCH_SKILLS_DIR || "/app/skills";

function runtime(root, overrides = {}) {
  const progress = new Map();
  const c = vm.createContext({ console, path, os, process, Date, randomUUID, mkdtemp, readFile, rm, stat,
    realpath, execFileAsync, AgentHarness, InMemorySessionRepo, loadSkills, NodeExecutionEnv, Type,
    createWatchModelTransport, jsonrepair, VIDEO_SCRIPT_STORAGE_ROOT: root, WATCH_SKILLS_DIR: skills,
    WATCH_SCRIPT_PATH: path.join(skills, "watch/scripts/watch.py"), VIDEO_SCRIPT_DISPATCHER: undefined,
    VIDEO_SCRIPT_ANALYSIS_PROMPT: "Only nine valid storyboard shots. No narration without audio evidence.",
    videoScriptAnalysisProgress: progress, ...overrides });
  vm.runInContext(section("function modelFrom(", "function recoverableStreamFailure(")
    + section("function messageText(", "function addTokenUsage(")
    + section("function extractJsonObject(", "async function handleVideoScriptAnalysis("), c);
  return { c, progress };
}

async function fixture(t, { staticVideo = false } = {}) {
  const root = await mkdtemp(path.join(os.tmpdir(), "watch-test-root-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const video = path.join(root, "fixture.mp4");
  await execFileAsync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
    staticVideo ? "color=blue:s=160x240:r=10" : "testsrc2=size=160x240:rate=10", "-t", "10", "-c:v", "libx264", "-y", video]);
  return { root, video };
}

function send(response, content, finish = "stop") {
  response.writeHead(200, { "Content-Type": "text/event-stream" });
  response.end(`data: ${JSON.stringify({ choices: [{ delta: { content }, finish_reason: finish }] })}\n\n`
    + "data: [DONE]\n\n");
}

async function provider(t, handler) {
  const calls = [];
  const server = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const payload = JSON.parse(Buffer.concat(chunks).toString()); calls.push(payload);
    try { await handler(payload, res, calls.length); } catch (error) { res.destroy(error); }
  });
  server.listen(0, "127.0.0.1"); await once(server, "listening");
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)); });
  return { calls, config: { modelId: "configured-driver", apiKey: "fake-only",
    baseUrl: `http://127.0.0.1:${server.address().port}/v1` } };
}

function parseFrames(payload) {
  const text = payload.messages.filter(m => m.role === "user").map(m => Array.isArray(m.content)
    ? m.content.filter(p => p.type === "text").map(p => p.text).join("\n") : m.content).join("\n");
  const jsonLine = text.split("\n").find(line => line.startsWith('[{"imagePosition":'));
  return JSON.parse(jsonLine);
}

function result(frames, count = 9) {
  return JSON.stringify({ title: "watch 测试", durationSeconds: 10, shots: Array.from({ length: count }, (_, i) => ({
    sequence: i + 1, startSecond: i * 10 / count, endSecond: (i + 1) * 10 / count,
    narration: "", imagePrompt: { sourceTimestampSeconds: frames[Math.round(i * (frames.length - 1) / (count - 1))].timestampSeconds,
      sourceDurationSeconds: 10 }
  })) });
}

function readFrames(response, frames) {
  response.writeHead(200, { "Content-Type": "text/event-stream" });
  response.end(`data: ${JSON.stringify({ choices: [{ delta: { content: "开始读取真实关键帧。", tool_calls: frames.map((f, i) => ({
    index: i, id: `read-${i}`, type: "function", function: { name: "read", arguments: JSON.stringify({ path: f.path }) }
  })) }, finish_reason: "tool_calls" }] })}\n\n` + "data: [DONE]\n\n");
}

test("watch-only path invokes installed skill, reads every real frame, streams two assistant messages and cleans up", async t => {
  const { root, video } = await fixture(t);
  let frames, work;
  const mock = await provider(t, (payload, response, call) => {
    assert.equal(payload.model, "configured-driver");
    assert.equal(payload.max_tokens, 6500);
    assert.equal(payload.reasoning_effort, "low");
    if (call === 1) {
      frames = parseFrames(payload); work = path.dirname(path.dirname(frames[0].path));
      assert.match(JSON.stringify(payload.messages), /<skill name=\\"watch\\"/);
      assert.deepEqual(payload.tools.map(tool => tool.function.name), ["read"]);
      readFrames(response, frames);
    } else {
      const tools = payload.messages.filter(m => m.role === "tool");
      assert.equal(tools.length, frames.length);
      const images = payload.messages.flatMap(m => Array.isArray(m.content) ? m.content : []).filter(p => p.type === "image_url");
      assert.equal(images.length, frames.length);
      send(response, result(frames));
    }
  });
  const p = runtime(root);
  const response = await p.c.runVideoScriptAnalysis({ model: mock.config, sourceVideoPath: video,
    progressId: "watch-test", context: { durationSeconds: 10, sourceDurationSeconds: 10 } });
  assert.equal(mock.calls.length, 2, "No unconditional second full analysis pass");
  assert.equal(response.analysisMode, "PI_WATCH_SKILL");
  assert.equal(response.watchFrameCount, frames.length);
  assert.equal(response.actionStateAudited, false);
  assert.equal(response.result.shots.length, 9);
  assert.equal(response.analysisOutput, `开始读取真实关键帧。\n\n${result(frames)}`);
  assert.equal(response.analysisOutputCharacters, "开始读取真实关键帧。".length + result(frames).length);
  assert.equal(p.progress.get("watch-test").stage, "RESULT_READY");
  await assert.rejects(access(work));
});

test("real watch dedup sparse input preserves exact timestamps and accepts repeated evidence", async t => {
  const { root, video } = await fixture(t, { staticVideo: true });
  const p = runtime(root);
  const watch = await p.c.preprocessVideoWithWatch(video, null);
  assert.ok(watch.frames.some(frame => frame.motion?.timestamps.length >= 2));
  for (const frame of watch.frames.filter(frame => frame.motion)) {
    assert.equal(frame.motion.timestamps[0], frame.timestampSeconds);
    assert.ok(frame.motion.timestamps.at(-1) - frame.timestampSeconds <= 0.401);
    assert.notEqual(frame.motion.path, frame.path);
    await access(frame.motion.path);
  }
  t.after(() => rm(watch.workDirectory, { recursive: true, force: true }));
  assert.ok(watch.frameCount >= 1 && watch.frameCount < 9);
  assert.ok(watch.frames.every(frame => Number.isFinite(frame.timestampSeconds)));
  const storyboard = JSON.parse(result(watch.frames));
  assert.equal(p.c.watchStoryboardIssues(storyboard, watch, { durationSeconds: 10, sourceDurationSeconds: 10 }).length, 0);
  storyboard.shots[0].imagePrompt.sourceTimestampSeconds = 0.123456789;
  assert.ok(p.c.watchStoryboardIssues(storyboard, watch, { durationSeconds: 10, sourceDurationSeconds: 10 }).length);
});

test("source paths and frame reads reject traversal and symlink escape", async t => {
  const { root, video } = await fixture(t);
  const outside = await mkdtemp(path.join(os.tmpdir(), "watch-outside-"));
  t.after(() => rm(outside, { recursive: true, force: true }));
  await writeFile(path.join(outside, "secret.mp4"), "secret");
  await symlink(path.join(outside, "secret.mp4"), path.join(root, "escape.mp4"));
  const p = runtime(root);
  await assert.rejects(p.c.validatedSourceVideoPath(path.join(root, "escape.mp4")), /未授权/);
  await assert.rejects(p.c.validatedSourceVideoPath(path.join(outside, "secret.mp4")), /授权/);
  assert.equal(await p.c.validatedSourceVideoPath(video), await realpath(video));
  const link = path.join(root, "frame.jpg"); await symlink(path.join(outside, "secret.mp4"), link);
  const read = p.c.createRestrictedFrameReadTool(root, [{ path: link }], new Set(), Date.now() + 10000);
  await assert.rejects(read.execute("x", { path: link }), /目录之外/);
  await assert.rejects(read.execute("x", { path: video }), /目录之外/);
});

test("watch failure cleans temporary files and never falls back to body.frames", async t => {
  const { root, video } = await fixture(t);
  let work;
  const p = runtime(root, { execFileAsync: async (_cmd, args) => { work = args[3]; throw new Error("watch extraction failed"); } });
  await assert.rejects(p.c.runVideoScriptAnalysis({ model: { apiKey: "fake" }, sourceVideoPath: video,
    progressId: "fail", frames: [{ mimeType: "image/jpeg", data: "fallback-not-allowed" }] }), /watch extraction failed/);
  await assert.rejects(access(work));
  assert.equal(p.progress.get("fail").stage, "FAILED");
});

test("missing complete termination from tool-capable provider is rejected", async t => {
  const { root, video } = await fixture(t);
  let work;
  const mock = await provider(t, (payload, response) => {
    const frames = parseFrames(payload); work = path.dirname(path.dirname(frames[0].path));
    send(response, result(frames), "length");
  });
  const p = runtime(root);
  await assert.rejects(p.c.runVideoScriptAnalysis({ model: mock.config, sourceVideoPath: video,
    progressId: "incomplete", context: { durationSeconds: 10, sourceDurationSeconds: 10 } }), /未正常结束/);
  assert.equal(mock.calls.length, 1);
  await assert.rejects(access(work));
});

test("all frames are mandatory even when the provider returns valid JSON without reading", async t => {
  const { root, video } = await fixture(t);
  let work, frames;
  const mock = await provider(t, (payload, response) => {
    frames ||= parseFrames(payload); work = path.dirname(path.dirname(frames[0].path));
    send(response, result(frames));
  });
  const p = runtime(root);
  await assert.rejects(p.c.runVideoScriptAnalysis({ model: mock.config, sourceVideoPath: video,
    progressId: "unread", context: { durationSeconds: 10, sourceDurationSeconds: 10 } }), /未读取全部关键帧/);
  assert.equal(mock.calls.length, 2, "One bounded missing-frame correction");
  await assert.rejects(access(work));
});

for (const correction of ["json", "count", "narration", "timestamp"]) {
  test(`watch ${correction} correction stays in the same skill image history`, async t => {
    const { root, video } = await fixture(t);
    let frames;
    const mock = await provider(t, (payload, response, call) => {
      if (call === 1) { frames = parseFrames(payload); readFrames(response, frames); return; }
      assert.equal(payload.messages.filter(m => m.role === "tool").length, frames.length);
      const storyboard = JSON.parse(result(frames, correction === "count" && call === 2 ? 8 : 9));
      if (correction === "narration") storyboard.shots[0].narration = call === 2 ? "旁白：" + "真实口播".repeat(20) : "旁白：真实口播";
      if (correction === "timestamp" && call === 2) storyboard.shots[0].imagePrompt.sourceTimestampSeconds = 0.123456789;
      send(response, correction === "json" && call === 2 ? "invalid JSON" : JSON.stringify(storyboard));
    });
    const p = runtime(root);
    const response = await p.c.runVideoScriptAnalysis({ model: mock.config, sourceVideoPath: video,
      progressId: correction, context: { durationSeconds: 10, sourceDurationSeconds: 10,
        ...(correction === "narration" ? { hasReliableAudioTranscript: true,
          audioTranscript: { fullText: "真实口播", segments: [{ startSecond: 0, endSecond: 10, text: "真实口播" }] } } : {}) } });
    assert.equal(mock.calls.length, 3);
    assert.equal(response.result.shots.length, 9);
    assert.equal(response.actionStateAudited, false);
    assert.equal(response.narrationSemanticallyCondensed, correction === "narration");
  });
}

test("visible model text arrives before the watch response completes", async t => {
  const { root, video } = await fixture(t);
  let frames, release, resolvePartial;
  const partial = new Promise(resolve => { resolvePartial = resolve; });
  const p = runtime(root);
  const originalSet = p.progress.set.bind(p.progress);
  p.progress.set = (key, value) => { originalSet(key, value); if (value.output?.includes('{"title"')) resolvePartial(value); return p.progress; };
  const mock = await provider(t, (payload, response, call) => {
    if (call === 1) { frames = parseFrames(payload); readFrames(response, frames); return; }
    const text = result(frames);
    response.writeHead(200, { "Content-Type": "text/event-stream" });
    response.write(`data: ${JSON.stringify({ choices: [{ delta: { content: text.slice(0, 40) } }] })}\n\n`);
    release = () => response.end(`data: ${JSON.stringify({ choices: [{ delta: { content: text.slice(40) }, finish_reason: "stop" }] })}\n\n` + "data: [DONE]\n\n");
  });
  let complete = false;
  const pending = p.c.runVideoScriptAnalysis({ model: mock.config, sourceVideoPath: video, progressId: "stream",
    context: { durationSeconds: 10, sourceDurationSeconds: 10 } }).finally(() => { complete = true; });
  const snapshot = await partial;
  assert.equal(complete, false);
  assert.equal(snapshot.done, false);
  assert.match(snapshot.output, /^开始读取真实关键帧。\n\n\{"title"/);
  release(); await pending;
});

test("watch provider turns obey one deadline and a bounded tool-call budget", async t => {
  const mock = await provider(t, (_payload, _response) => {});
  const errors = [];
  const model = { id: "configured-driver", api: "openai-completions", provider: "test" };
  const models = createWatchModelTransport({ config: mock.config, apiKey: mock.config.apiKey,
    deadline: Date.now() + 100, onFailure: error => errors.push(error), maxCalls: 1 });
  const response = await models.streamSimple(model, { messages: [], tools: [] }).result();
  assert.equal(response.stopReason, "error");
  assert.equal(errors[0].failureCode, "CHAT_TIMEOUT");
  const next = await models.streamSimple(model, { messages: [], tools: [] }).result();
  assert.equal(next.stopReason, "error");
  assert.equal(errors[1].failureCode, "WATCH_BUDGET_EXCEEDED");
  assert.equal(mock.calls.length, 1);
});

test("watch idle and non-streaming failures stop without replaying paid analysis", async t => {
  for (const buffered of [false, true]) {
    const mock = await provider(t, (_payload, response) => {
      if (buffered) {
        response.writeHead(200, { "Content-Type": "application/json" });
        response.flushHeaders();
      }
    });
    let failure;
    const models = createWatchModelTransport({ config: mock.config, apiKey: mock.config.apiKey,
      deadline: Date.now() + 5000, idleTimeoutMs: 100, onFailure: error => { failure = error; } });
    const answer = await models.streamSimple({ id: "configured-driver", api: "openai-completions", provider: "test" },
      { messages: [], tools: [] }).result();
    assert.equal(answer.stopReason, "error");
    assert.equal(failure.failureCode, buffered ? "CHAT_STREAM_REQUIRED" : "WATCH_UPSTREAM_IDLE");
    assert.equal(failure.retryable, false);
    assert.equal(mock.calls.length, 1);
  }
});

test("watch reasoning heartbeats keep the live request alive until visible text arrives", async t => {
  const mock=await provider(t,(_payload,response)=>{
    response.writeHead(200,{"Content-Type":"text/event-stream"});
    const timer=setInterval(()=>response.write('data: {"choices":[{"delta":{"reasoning_content":"working"}}]}\n\n'),30);
    setTimeout(()=>{clearInterval(timer);response.end('data: {"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n');},240);
  });
  const models=createWatchModelTransport({config:mock.config,apiKey:mock.config.apiKey,deadline:Date.now()+3000,idleTimeoutMs:100});
  const result=await models.streamSimple({id:"configured-driver",api:"openai-completions",provider:"test"},{messages:[],tools:[]}).result();
  assert.equal(result.stopReason,"stop");
  assert.equal(result.content[0].text,"done");
});

test("parallel watch batches overlap, execute concurrently and synthesize from every real frame", async t => {
  const {root,video}=await fixture(t);
  let active=0,peak=0,evidenceDone=0,finalFrames;
  const mock=await provider(t,(payload,response)=>{
    const frames=parseFrames(payload);
    const batch=JSON.stringify(payload.messages).includes('模式：Evidence batch。');
    if(!payload.messages.some(m=>m.role==='tool')){readFrames(response,frames);return;}
    if(batch){
      assert.equal(payload.max_tokens,Math.min(6500,1600+frames.length*550));
      active++;peak=Math.max(peak,active);
      setTimeout(()=>{active--;evidenceDone++;send(response,JSON.stringify({frames:frames.map(f=>({timestampSeconds:f.timestampSeconds,usableForStoryboard:f.timestampSeconds < 8,observation:'单人手持当前任务商品。',subjectCount:1,state:'held',orientation:'front',contact:'一只手托住下缘',action:'抬起至身前',camera:'近景，相机运动不确定',cut:'uncertain',uncertainty:'手别不可判定'}))}));},100);
    }else{
      assert.equal(evidenceDone,3);
      finalFrames=frames;
      assert.equal(payload.messages.flatMap(m=>Array.isArray(m.content)?m.content:[]).filter(x=>x.type==='image_url').length,frames.length);
      send(response,result(frames));
    }
  });
  const budgets=[];
  const p=runtime(root,{WATCH_PARALLEL_ANALYSIS:true,createWatchModelTransport: options => {
    budgets.push(options.deadline-Date.now());
    return createWatchModelTransport(options);
  }});
  const answer=await p.c.runVideoScriptAnalysis({model:mock.config,sourceVideoPath:video,progressId:'parallel',context:{durationSeconds:10,sourceDurationSeconds:10}});
  assert.equal(peak,3);
  assert.ok(budgets.at(-1)>600_000 && budgets.at(-1)<=900_000, "synthesis retains remaining global budget instead of a four-minute cutoff");
  assert.ok(answer.excludedWatchFrameTimestampsSeconds.length > 0);
  assert.ok(finalFrames.every(frame => frame.timestampSeconds < 8));
  assert.ok(answer.result.shots.every(shot => shot.imagePrompt.sourceTimestampSeconds < 8));
  assert.equal(answer.parallelEvidenceBatches,3);
  assert.equal(answer.result.shots.length,9);
  assert.ok(answer.analysisOutput.includes('observation'));
  assert.equal(mock.calls.length,8);
  const batches=p.c.watchEvidenceBatches(finalFrames);
  assert.ok(batches[0].some(a=>batches[1].some(b=>a.path===b.path)));
  assert.equal(new Set(batches.flat().map(f=>f.path)).size,finalFrames.length);
});

test("invalid parallel evidence fails without synthesizing or reporting success", async t => {
  const {root,video}=await fixture(t);let synthesis=0;
  const mock=await provider(t,(payload,response)=>{
    const frames=parseFrames(payload);
    if(!JSON.stringify(payload.messages).includes('模式：Evidence batch。'))synthesis++;
    if(!payload.messages.some(m=>m.role==='tool')){readFrames(response,frames);return;}
    send(response,JSON.stringify({frames:[{timestampSeconds:-1,observation:'unverified'}]}));
  });
  const p=runtime(root,{WATCH_PARALLEL_ANALYSIS:true});
  await assert.rejects(p.c.runVideoScriptAnalysis({model:mock.config,sourceVideoPath:video,progressId:'bad-evidence'}),e=>e.retryable===false&&/证据/.test(e.message));
  assert.equal(synthesis,0);
  assert.equal(p.progress.get('bad-evidence').stage,'FAILED');
});

function detailedEvidence(frames, overrides={}) {
  return {frames:frames.map(frame=>({timestampSeconds:frame.timestampSeconds,usableForStoryboard:true,
    observation:'人物接触当前商品',subjectCount:1,state:'held',orientation:'front',contact:'画面一侧的手托住底部',
    action:'当前姿态为低位手持',camera:'近景，无法确认相机运动',cut:'uncertain',uncertainty:'手别不可判定',...overrides}))};
}

test('detailed evidence rejects missing fields and distinguishes unknown from genuine disagreement', async t=>{
  const {root}=await fixture(t);const p=runtime(root);const frames=[{timestampSeconds:0}];
  const good=detailedEvidence(frames);
  good.frames[0].cut="continuous";
  assert.equal(p.c.validateDetailedWatchEvidence(good,frames),good);
  assert.equal(good.frames[0].cut,"uncertain");
  const missing=detailedEvidence(frames);delete missing.frames[0].contact;
  assert.throws(()=>p.c.validateDetailedWatchEvidence(missing,frames),/字段/);
  assert.equal(p.c.watchEvidenceConflicts([good,detailedEvidence(frames,{state:'uncertain',subjectCount:null})]).length,0);
  assert.deepEqual(Array.from(p.c.watchEvidenceConflicts([good,detailedEvidence(frames,{state:'worn'})])),[0]);
  assert.deepEqual(Array.from(p.c.watchEvidenceConflicts([good,detailedEvidence(frames,{usableForStoryboard:false})])),[0]);
});

test('only conflicting overlapping frames trigger one bounded image review before synthesis',async t=>{
  const {root,video}=await fixture(t);let reviews=0;
  const mock=await provider(t,(payload,response)=>{
    const frames=parseFrames(payload);
    const text=payload.messages.filter(m=>m.role==='user').flatMap(m=>Array.isArray(m.content)?m.content:[]).map(x=>x.text||'').join('\n');
    if(!payload.messages.some(m=>m.role==='tool')){readFrames(response,frames);return;}
    if(text.includes('模式：Evidence batch。')){
      send(response,JSON.stringify(detailedEvidence(frames,{state:frames[0].timestampSeconds===0?'held':'worn'})));
    }else if(text.includes('模式：Evidence review。')){
      reviews++;send(response,JSON.stringify(detailedEvidence(frames,{state:'uncertain',uncertainty:'遮挡，无法确认手持或穿着'})));
    }else{
      assert.equal(reviews,1);assert.ok(text.includes('"state":"uncertain"'));
      send(response,result(frames));
    }
  });
  const p=runtime(root,{WATCH_PARALLEL_ANALYSIS:true});
  const answer=await p.c.runVideoScriptAnalysis({model:mock.config,sourceVideoPath:video,progressId:'detail-conflict',context:{durationSeconds:10,sourceDurationSeconds:10}});
  assert.equal(answer.result.shots.length,9);assert.equal(answer.evidenceDetailVersion,2);
  assert.ok(answer.reviewedEvidenceConflicts>0);assert.equal(reviews,1);assert.equal(mock.calls.length,10);
});

test('watch retries only an empty interrupted turn once, without replaying visible text',async t=>{
  for(const partial of [false,true]) {
    let calls=0;
    const mock=await provider(t,(_payload,response)=>{
      calls++;
      if(calls===1){response.writeHead(200,{'Content-Type':'text/event-stream'});response.end(partial?'data: {"choices":[{"delta":{"content":"partial"}}]}\n\n':'');}
      else send(response,'complete');
    });
    const models=createWatchModelTransport({config:mock.config,apiKey:mock.config.apiKey,deadline:Date.now()+3000});
    const answer=await models.streamSimple({id:'configured-driver',api:'openai-completions',provider:'test'},{messages:[],tools:[]}).result();
    assert.equal(calls,partial?1:2);assert.equal(answer.stopReason,partial?'error':'stop');
  }
});


test('hard-cut grounding preserves observed contact and removes fabricated inter-shot motion',async t=>{
  const {root}=await fixture(t);const p=runtime(root);
  const e=detailedEvidence([{timestampSeconds:2}],{cut:'cut',observation:'一只手托住边缘',contact:'握法不可见'});
  const result={shots:[{motionDescription:'从远处向镜头推动',transitionDescription:'连续',imagePrompt:{sourceTimestampSeconds:2}},
    {motionDescription:'已观察的抬起动作',imagePrompt:{sourceTimestampSeconds:3}}]};
  assert.equal(p.c.groundWatchCutTransitions(result,{batches:[e]}),1);
  assert.ok(result.shots[0].motionDescription.includes('握法不可见'));
  assert.ok(!result.shots[0].motionDescription.includes('向镜头推动'));
  assert.equal(result.shots[0].transitionDescription,'硬切');
  assert.equal(result.shots[1].motionDescription,'已观察的抬起动作');
  assert.deepEqual(Array.from(p.c.watchEvidenceConflicts([e,detailedEvidence([{timestampSeconds:2}],{cut:'continuous'})])),[2]);
});
