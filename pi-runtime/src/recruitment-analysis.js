import { streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import { jsonrepair } from "jsonrepair";

// Public diagnostics are authored here, never copied from a provider response.
export const recruitmentErrors = {
  HR_AI_CONNECTION: "驱动模型连接失败，请检查模型服务；本次未自动重复调用",
  HR_AI_TIMEOUT: "驱动模型响应超时，请检查模型服务后手动重试",
  HR_AI_INVALID_STREAM: "驱动模型响应中断，请手动重试",
  HR_AI_TRUNCATED: "生成结果不完整，请精简需求后重新生成",
  HR_AI_TOOLS: "驱动模型返回了不支持的工具调用",
  HR_AI_INVALID_JSON: "驱动模型未返回有效 JSON，请检查模型服务后手动重试",
  HR_AI_OVERSIZED: "驱动模型返回内容过长，请精简岗位内容后重试",
  HR_AI_PROTOCOL: "当前驱动模型协议暂不支持招聘分析",
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

export async function analyzeRecruitment(body, emit, { signal, stream = streamSimple } = {}) {
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
    // model. An explicitly rejected parameter may be negotiated once; a stream
    // failure, malformed generation or successful request is never replayed.
    const capabilityKey = model.baseUrl + "\n" + model.id;
    let jsonOutput = supportsJsonOutput(model.id) && jsonOutputSupport.get(capabilityKey) !== false;
    const context = {
      systemPrompt: body.systemPrompt,
      messages: [{ role: "user", content: [{ type: "text", text: JSON.stringify(body.input) }], timestamp: Date.now() }],
    };
    for (let attempt = 0; attempt < 2; attempt++) {
    let negotiate = false;
    const events = stream(model, context, {
      apiKey: body.model.apiKey, maxTokens: 10000, maxRetries: 0,
      timeoutMs: 300000, signal: controller.signal,
      ...(model.reasoning ? { reasoning: "low" } : {}),
      onPayload: payload => jsonOutput ? { ...payload, response_format: { type: "json_object" } } : payload,
      onResponse: () => emit({ event: "progress", data: { phase: "generating", receivedChars: 0 } }),
    });
    for await (const event of events) {
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
        // Extract only a numeric HTTP status, never the upstream message.
        const status = text.match(/^\s*(?:HTTP\s*)?([45]\d\d)\b/i)?.[1];
        if (attempt === 0 && jsonOutput && chars === 0 && ["400", "422"].includes(status)
            && /response_format/i.test(text) && /unsupported|not supported|unknown|unrecognized|unexpected/i.test(text)) {
          jsonOutputSupport.set(capabilityKey, false); jsonOutput = false; negotiate = true;
          break;
        }
        if (status) throw Object.assign(new Error(
          `驱动模型服务返回错误（HTTP ${status}），本次未自动重复调用`), { code: "HR_AI_UPSTREAM_HTTP", upstreamStatus: Number(status) });
        throw failure("HR_AI_INVALID_STREAM");
      }
      if (event.type === "done") done = event.message;
    }
    if (negotiate) continue;
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

export async function handleRecruitment(request, response, body) {
  response.writeHead(200, { "Content-Type": "application/x-ndjson", "Cache-Control": "no-store" });
  const controller = new AbortController();
  response.on("close", () => controller.abort());
  await analyzeRecruitment(body, event => {
    if (!response.destroyed) response.write(JSON.stringify(event) + "\n");
  }, { signal: controller.signal });
  response.end();
}
