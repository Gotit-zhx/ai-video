import { streamRecruitment } from "./recruitment-stream.js";
import { RecruitmentOperationStore } from "./recruitment-operation-store.js";
import { jsonrepair } from "jsonrepair";

// Public diagnostics are authored here, never copied from a provider response.
export const recruitmentErrors = {
  HR_AI_CONNECTION: "驱动模型连接暂未恢复，已保留分析进度",
  HR_AI_TIMEOUT: "驱动模型持续响应超时，已保留分析进度",
  HR_AI_INVALID_STREAM: "驱动模型连接暂未恢复，已保留分析进度",
  HR_AI_TRUNCATED: "分批分析仍未完整返回，已保留分析进度",
  HR_AI_TOOLS: "驱动模型返回了不支持的工具调用",
  HR_AI_INVALID_JSON: "驱动模型暂未返回完整分析，已保留分析进度",
  HR_AI_OVERSIZED: "驱动模型返回内容过长，请精简岗位内容后重试",
  HR_AI_PROTOCOL: "当前驱动模型协议暂不支持招聘分析",
  HR_AI_CONTENT_FILTER: "驱动模型未接受本次内容，请调整岗位描述",
};

function failure(code) {
  return Object.assign(new Error(recruitmentErrors[code]), { code });
}

const jsonOutputSupport = new Map();
const supportsJsonOutput = id => /^(?:openai\/)?(?:gpt-[3-9]|grok-|deepseek-(?:chat|reasoner|v\d))/i.test(id);

// Providers may surround JSON with a preface, fences or a separate reasoning
// block. Accept one complete root only; never repair an unfinished container
// into a successful candidate list or choose between competing results.
export function parseRecruitmentResult(raw) {
  const text = String(raw).replace(/<think\b[^>]*>[\s\S]*?<\/think>/gi, "").trim();
  if (/<\/?think\b/i.test(text)) throw failure("HR_AI_INVALID_JSON");
  const roots = [];
  let start = -1, stack = [], quote = "", escaped = false, missingValue = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (start < 0) {
      if (char === "{" || char === "[") { start = i; stack = [char]; }
      continue;
    }
    if (quote) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === quote) quote = "";
      continue;
    }
    if (char === '"' || char === "'") { quote = char; missingValue = false; }
    else if (char === "{" || char === "[") { stack.push(char); missingValue = false; }
    else if (char === "}" || char === "]") {
      if (missingValue || stack.pop() !== (char === "}" ? "{" : "["))
        throw failure("HR_AI_INVALID_JSON");
      if (!stack.length) { roots.push(text.slice(start, i + 1)); start = -1; }
    } else if (char === ":") missingValue = true;
    else if (!/\s/.test(char)) missingValue = false;
  }
  if (start >= 0 || roots.length !== 1) throw failure("HR_AI_INVALID_JSON");
  let result;
  try { result = JSON.parse(jsonrepair(roots[0])); } catch { throw failure("HR_AI_INVALID_JSON"); }
  if (!result || Array.isArray(result) || typeof result !== "object") throw failure("HR_AI_INVALID_JSON");
  return result;
}

export function recruitmentModel(config) {
  if (config?.apiFormat !== "OPENAI_COMPATIBLE") throw failure("HR_AI_PROTOCOL");
  const id = String(config.modelId || "").trim();
  const url = new URL(String(config.baseUrl || ""));
  if (!id || !config.apiKey || !["http:", "https:"].includes(url.protocol))
    throw failure("HR_AI_CONNECTION");
  const endpoint = /\/chat\/completions\/?$/.test(url.pathname);
  url.pathname = url.pathname.replace(/\/+$/, "").replace(/\/chat\/completions$/, "") || (endpoint ? "/" : "/v1");
  url.search = ""; url.hash = "";
  const gpt = /^(?:openai\/)?(?:gpt-[5-9]|o[134])(?:[.-]|$)/i.test(id);
  return {
    id, name: id, api: "openai-completions", provider: "heyi-recruitment",
    baseUrl: url.toString().replace(/\/$/, ""), reasoning: gpt, input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 128000, maxTokens: 10000,
    compat: {
      supportsStore: false, supportsDeveloperRole: false,
      supportsUsageInStreaming: false, supportsReasoningEffort: gpt,
      maxTokensField: gpt ? "max_completion_tokens" : "max_tokens",
    },
  };
}

async function analyzeRecruitmentOnce(body, emit, { signal, stream = streamRecruitment, maxTokens = 10000 } = {}) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) abort();
  let timedOut = false, chars = 0, done, last = 0;
  const timer = setTimeout(() => { timedOut = true; abort(); }, 300000);
  const started = Date.now();
  try {
    const model = recruitmentModel(body.model);
    if (typeof body.systemPrompt !== "string" || !body.input || typeof body.input !== "object")
      throw failure("HR_AI_CONNECTION");
    emit({ event: "progress", data: { phase: "connecting", receivedChars: 0 } });
    // Ask the provider to enforce JSON while still using the selected Pi SDK
    // model. Negotiate only an explicitly rejected parameter. The outer
    // recovery loop owns retries and preserves every completed group.
    const capabilityKey = model.baseUrl + "\n" + model.id;
    let jsonOutput = supportsJsonOutput(model.id) && jsonOutputSupport.get(capabilityKey) !== false;
    const context = {
      systemPrompt: body.systemPrompt,
      messages: [{ role: "user", content: [{ type: "text", text: JSON.stringify(body.input) }], timestamp: Date.now() }],
    };
    for (let attempt = 0; attempt < 2; attempt++) {
    let negotiate = false;
    const events = stream(model, context, {
      apiKey: body.model.apiKey, maxTokens, maxRetries: 0,
      timeoutMs: 300000, signal: controller.signal,
      ...(model.reasoning ? { reasoning: "low" } : {}),
      onPayload: payload => jsonOutput ? { ...payload, response_format: { type: "json_object" } } : payload,
      onResponse: () => emit({ event: "progress", data: { phase: "generating", receivedChars: 0 } }),
    });
    for await (const event of events) {
      if (event.type?.startsWith("toolcall_")) throw failure("HR_AI_TOOLS");
      if (event.type === "text_delta") {
        chars += String(event.delta || "").length;
        if (chars > 200000) throw failure("HR_AI_OVERSIZED");
        if (Date.now() - last >= 500) {
          emit({ event: "progress", data: { phase: "generating", receivedChars: chars, elapsedMs: Date.now() - started } });
          last = Date.now();
        }
      }
      if (event.type === "error") {
        if (timedOut) throw failure("HR_AI_TIMEOUT");
        const text = String(event.error?.errorMessage || "");
        const transport = events.transportDiagnostics || {};
        if (transport.finishReason === "content_filter") throw failure("HR_AI_CONTENT_FILTER");
        // Only categorical diagnostics; never log provider text or HR evidence.
        console.warn("[recruitment] " + JSON.stringify({
          category: transport.failure || (/without finish_reason/i.test(text) ? "missing_finish_reason" : "sdk_error"),
          httpStatus: transport.upstreamStatus || transport.httpStatus || 0,
          receivedChars: chars, done: transport.done === true,
        }));
        // Extract only a numeric HTTP status, never the upstream message.
        const status = String(transport.upstreamStatus || text.match(/^\s*(?:HTTP\s*)?([45]\d\d)\b/i)?.[1] || "");
        if (attempt === 0 && jsonOutput && chars === 0 && ["400", "422"].includes(status)
            && /response_format/i.test(text) && /unsupported|not supported|unknown|unrecognized|unexpected/i.test(text)) {
          jsonOutputSupport.set(capabilityKey, false); jsonOutput = false; negotiate = true;
          break;
        }
        if (status) throw Object.assign(new Error(
          `驱动模型服务返回错误（HTTP ${status}），本次未自动重复调用`), { code: "HR_AI_UPSTREAM_HTTP", upstreamStatus: Number(status) });
        if (transport.failure === "oversized") throw failure("HR_AI_OVERSIZED");
        throw failure("HR_AI_INVALID_STREAM");
      }
      if (event.type === "done") done = event.message;
    }
    if (negotiate) continue;
    if (events.transportDiagnostics?.compatibility) console.info("[recruitment] " + JSON.stringify({
      compatibility: events.transportDiagnostics.compatibility, receivedChars: chars,
    }));
    break;
    }
    if (!done || done.stopReason === "error" || done.stopReason === "aborted") throw failure("HR_AI_INVALID_STREAM");
    if (done.stopReason === "length") throw failure("HR_AI_TRUNCATED");
    if (done.stopReason === "toolUse" || done.content.some(block => block.type === "toolCall")) throw failure("HR_AI_TOOLS");
    const text = done.content.filter(block => block.type === "text").map(block => block.text).join("")
      .trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
    if (text.length > 200000) throw failure("HR_AI_OVERSIZED");
    emit({ event: "progress", data: { phase: "validating", receivedChars: text.length } });
    const result = parseRecruitmentResult(text);
    emit({ event: "result", data: result });
  } catch (error) {
    const code = Object.hasOwn(recruitmentErrors, error?.code) || error?.code === "HR_AI_UPSTREAM_HTTP"
      ? error.code : timedOut ? "HR_AI_TIMEOUT" : "HR_AI_CONNECTION";
    emit({ event: "error", data: { code, message: recruitmentErrors[code] || error.message,
      ...(code === "HR_AI_UPSTREAM_HTTP" ? { upstreamStatus: error.upstreamStatus } : {}) } });
  } finally {
    clearTimeout(timer); abort(); signal?.removeEventListener("abort", abort);
  }
}

const recoveryCodes = new Set(["HR_AI_INVALID_STREAM", "HR_AI_CONNECTION", "HR_AI_TIMEOUT", "HR_AI_INVALID_JSON"]);
function recoverable(error) {
  return recoveryCodes.has(error.code) || error.code === "HR_AI_UPSTREAM_HTTP"
    && (error.upstreamStatus === 429 || error.upstreamStatus === 408 || error.upstreamStatus >= 500);
}
async function delay(ms, signal) {
  if (signal.aborted) throw failure("HR_AI_TIMEOUT");
  await new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(failure("HR_AI_TIMEOUT")); };
    const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, ms);
    signal.addEventListener("abort", abort, { once: true });
  });
}

export async function analyzeRecruitment(body, emit, {
  signal, stream = streamRecruitment, retryDelaysMs = [1000, 3000, 8000], checkpoint,
} = {}) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) abort();
  const timer = setTimeout(abort, 300000);
  const groups = checkpoint || { results: {}, splits: {}, save: async () => {} };
  groups.splits ||= {};
  try {
    recruitmentModel(body.model);
    if (typeof body.systemPrompt !== "string" || !body.input || typeof body.input !== "object")
      throw failure("HR_AI_PROTOCOL");
    const candidates = body.mode === "sourcing" && Array.isArray(body.input.candidates)
      ? body.input.candidates : null;
    let completed = 0;
    async function group(input, key) {
      if (controller.signal.aborted) throw failure("HR_AI_TIMEOUT");
      if (groups.results[key]) { completed += input.candidates?.length || 1; return groups.results[key]; }
      async function split() {
        const middle = groups.splits[key];
        const left = await group({ ...input, candidates: input.candidates.slice(0, middle) }, key + ".0");
        const right = await group({ ...input, candidates: input.candidates.slice(middle) }, key + ".1");
        const merged = { candidates: [...left.candidates, ...right.candidates],
          limitations: [...new Set([...(left.limitations || []), ...(right.limitations || [])])] };
        groups.results[key] = merged; await groups.save(); return merged;
      }
      if (groups.splits[key]) return split();
      let maxTokens = 10000;
      for (let attempt = 0; ; attempt++) {
        let result, error;
        await analyzeRecruitmentOnce({ ...body, input }, event => {
          if (event.event === "result") result = event.data;
          else if (event.event === "error") error = event.data;
          else emit({ ...event, data: { ...event.data, completedCandidates: completed,
            totalCandidates: candidates?.length || 0, recoveryAttempt: attempt } });
        }, { signal: controller.signal, stream, maxTokens });
        if (!error && candidates && (!Array.isArray(result?.candidates)
            || result.candidates.length !== input.candidates.length
            || new Set(result.candidates.map(row => row?.id)).size !== input.candidates.length
            || result.candidates.some(row => !input.candidates.some(value => value.id === row.id))))
          error = { code: "HR_AI_INVALID_JSON", message: recruitmentErrors.HR_AI_INVALID_JSON };
        if (!error && result) {
          groups.results[key] = result;
          await groups.save();
          completed += input.candidates?.length || 1;
          return result;
        }
        error ||= { code: "HR_AI_INVALID_STREAM", message: recruitmentErrors.HR_AI_INVALID_STREAM };
        if (controller.signal.aborted) throw failure("HR_AI_TIMEOUT");
        if (error.code === "HR_AI_TRUNCATED" && input.candidates?.length > 1) {
          groups.splits[key] = Math.ceil(input.candidates.length / 2);
          await groups.save();
          emit({ event: "progress", data: { phase: "generating", completedCandidates: completed,
            totalCandidates: candidates.length, recoveryAttempt: attempt + 1, recovery: "splitting" } });
          return split();
        }
        if ((!recoverable(error) && error.code !== "HR_AI_TRUNCATED") || attempt >= retryDelaysMs.length)
          throw Object.assign(new Error(error.message), error);
        if (error.code === "HR_AI_TRUNCATED") maxTokens = 20000;
        emit({ event: "progress", data: { phase: "connecting", completedCandidates: completed,
          totalCandidates: candidates?.length || 0, recoveryAttempt: attempt + 1, recovery: "reconnecting" } });
        console.info("[recruitment] " + JSON.stringify({ recovery: error.code, attempt: attempt + 1,
          completedCandidates: completed, groupSize: input.candidates?.length || 0 }));
        await delay(retryDelaysMs[attempt], controller.signal);
      }
    }
    let result;
    if (candidates) {
      result = { candidates: [], limitations: [] };
      // Keep output safely below provider limits before the first request.
      for (let i = 0; i < candidates.length; i += 10) {
        const part = await group({ ...body.input, candidates: candidates.slice(i, i + 10) }, String(i / 10));
        result.candidates.push(...part.candidates);
        result.limitations.push(...(part.limitations || []));
      }
      result.limitations = [...new Set(result.limitations)];
    } else result = await group(body.input, "all");
    emit({ event: "result", data: result });
  } catch (error) {
    const code = Object.hasOwn(recruitmentErrors, error?.code) || error?.code === "HR_AI_UPSTREAM_HTTP"
      ? error.code : "HR_AI_CONNECTION";
    emit({ event: "error", data: { code, message: recruitmentErrors[code] || error.message,
      ...(code === "HR_AI_UPSTREAM_HTTP" ? { upstreamStatus: error.upstreamStatus } : {}) } });
  } finally {
    clearTimeout(timer); abort(); signal?.removeEventListener("abort", abort);
  }
}

const operationStore = new RecruitmentOperationStore(
  process.env.RECRUITMENT_OPERATION_STORAGE_ROOT || "/data/image-operations/recruitment");

export async function handleRecruitment(request, response, body) {
  response.writeHead(200, { "Content-Type": "application/x-ndjson", "Cache-Control": "no-store" });
  const controller = new AbortController();
  response.on("close", () => controller.abort());
  const emit = event => {
    if (!response.destroyed) response.write(JSON.stringify(event) + "\n");
  };
  if (body.requestId) {
    try {
      const result = await operationStore.execute(body.requestId, body, async checkpoint => {
        let terminal;
        await analyzeRecruitment(body, event => {
          if (event.event === "progress") emit(event); else terminal = event;
        }, { signal: controller.signal, checkpoint });
        if (terminal?.event !== "result") throw Object.assign(new Error("Recruitment recovery pending"), terminal?.data);
        return terminal.data;
      });
      emit({ event: "result", data: result });
    } catch (error) {
      const code = Object.hasOwn(recruitmentErrors, error?.code) || error?.code === "HR_AI_UPSTREAM_HTTP"
        ? error.code : "HR_AI_CONNECTION";
      emit({ event: "error", data: { code, message: recruitmentErrors[code] || recruitmentErrors.HR_AI_CONNECTION,
        ...(code === "HR_AI_UPSTREAM_HTTP" ? { upstreamStatus: error.upstreamStatus } : {}) } });
    }
  } else await analyzeRecruitment(body, emit, { signal: controller.signal });
  response.end();
}
