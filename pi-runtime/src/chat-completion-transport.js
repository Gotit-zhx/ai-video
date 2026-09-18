import { createHash } from "node:crypto";

const compatibilityCache = new Map();
const CACHE_TTL_MS = 30 * 60_000;
const CACHE_LIMIT = 128;

export class ChatCompletionError extends Error {
  constructor(message, failureCode, retryable, { upstreamStatus, cause } = {}) {
    super(message, { cause });
    this.chatTransport = true;
    this.failureCode = failureCode;
    this.retryable = retryable;
    this.statusCode = 502;
    if (upstreamStatus !== undefined) this.upstreamStatus = upstreamStatus;
  }
}

export function chatCompletionEndpoint(baseUrl) {
  let url;
  try {
    url = new URL(String(baseUrl || "").trim());
  } catch {
    throw new ChatCompletionError("模型 API 地址无效", "CHAT_INVALID_CONFIGURATION", false);
  }
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) {
    throw new ChatCompletionError("模型 API 地址无效", "CHAT_INVALID_CONFIGURATION", false);
  }
  let path = url.pathname.replace(/\/+$/, "");
  if (/\/(?:responses|messages)$/i.test(path)) {
    throw new ChatCompletionError("当前分析接口需要 Chat Completions API 地址", "CHAT_INVALID_CONFIGURATION", false);
  }
  if (!/\/chat\/completions$/i.test(path)) path = `${path || "/v1"}/chat/completions`;
  url.pathname = path;
  url.hash = "";
  return url.toString();
}

function errorDetails(body) {
  try {
    const payload = JSON.parse(body);
    const error = payload.error || payload;
    return {
      code: String(error.code || error.type || ""),
      param: String(error.param || ""),
      message: String(error.message || "")
    };
  } catch {
    return { code: "", param: "", message: String(body || "") };
  }
}

function negotiateProfile(status, details, profile) {
  if (![400, 422].includes(status)) return null;
  const rejection = `${details.code} ${details.message}`;
  if (/unsupported[_ -]value|invalid[_ -]value|supported values|supported range|must be (?:less|greater|between)/i.test(rejection)
      || !/unsupported|not supported|does not support|unrecognized|unknown (?:parameter|argument)|not (?:allowed|permitted)/i.test(rejection)) {
    return null;
  }
  // Only transport hints can change. Never drop images, change model, switch to
  // protocol, remove the output limit, or retry 5xx by guessing parameters.
  const explicitParam = details.param || (
    details.message.match(/(?:unsupported|unrecognized|unknown)\s+(?:request\s+)?(?:parameter|argument)(?:\s+supplied)?\s*:?\s*["'`]?([a-z_]+)/i)?.[1]
    || details.message.match(/["'`]?(reasoning_effort|max_completion_tokens|max_tokens)["'`]?\s+(?:is\s+)?(?:not supported|unsupported|not allowed)/i)?.[1]
    || details.message.match(/does not support\s+(?:the\s+)?(?:parameter\s+)?["'`]?(reasoning_effort|max_completion_tokens|max_tokens)/i)?.[1]
  );
  if (explicitParam === "reasoning_effort" && profile.reasoningEffort) return { ...profile, reasoningEffort: undefined };
  if (explicitParam === profile.tokenField) {
    return { ...profile, tokenField: profile.tokenField === "max_tokens" ? "max_completion_tokens" : "max_tokens" };
  }
  return null;
}

function httpError(status, details) {
  const evidence = `${details.code} ${details.message}`;
  if (status === 401 || status === 403) {
    return new ChatCompletionError("模型服务认证或访问权限失败", "CHAT_AUTHENTICATION_FAILED", false, { upstreamStatus: status });
  }
  if (/model_not_found|model[^\n]*(?:not found|does not exist|not available)|unknown model/i.test(evidence)) {
    return new ChatCompletionError("当前驱动模型在供应商处不可用，请检查模型接入配置", "CHAT_MODEL_UNAVAILABLE", false, { upstreamStatus: status });
  }
  if ([400, 422].includes(status) && /(?:vision|image)[^\n]*(?:not supported|unsupported)|(?:not support|unsupported)[^\n]*(?:vision|image)/i.test(evidence)) {
    return new ChatCompletionError("当前驱动模型或接口不支持图片输入，无法完成视觉分析", "CHAT_VISION_UNSUPPORTED", false, { upstreamStatus: status });
  }
  if (status === 429) {
    return new ChatCompletionError("模型服务请求过于频繁，请稍后重试", "CHAT_RATE_LIMITED", true, { upstreamStatus: status });
  }
  if ([400, 422].includes(status)) {
    return new ChatCompletionError(`模型服务拒绝请求参数（${status}），请检查该模型的接口兼容性`, "CHAT_PARAMETERS_REJECTED", false, { upstreamStatus: status });
  }
  return new ChatCompletionError(`模型请求失败 ${status}`, "CHAT_UPSTREAM_FAILED", status >= 500 || status === 408, { upstreamStatus: status });
}

async function readCompletionResponse(response, onTextDelta, onActivity, requireStreaming, allowMissingDone) {
  if (!/text\/event-stream/i.test(response.headers?.get("content-type") || "")) {
    onActivity?.("BUFFERED_RESPONSE");
    if (requireStreaming) {
      await response.body?.cancel().catch(() => {});
      throw new ChatCompletionError("上游未提供流式响应，请检查模型接口的流式支持", "CHAT_STREAM_REQUIRED", false);
    }
    const body = await response.text();
    try { return JSON.parse(body); }
    catch { throw new ChatCompletionError("模型响应不是完整 JSON", "CHAT_INVALID_RESPONSE", true); }
  }
  onActivity?.("STREAM_CONNECTED");
  if (!response.body) throw new ChatCompletionError("模型流式响应为空", "CHAT_EMPTY_RESPONSE", true);
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let buffer = "", data = [], eventType = "", reply = "", finishReason = null, usage = {}, done = false, bytes = 0;
  const toolCalls = [];
  const consumeEvent = () => {
    const type = eventType;
    eventType = "";
    if (!data.length) return;
    const raw = data.join("\n");
    data = [];
    if (raw === "[DONE]") { done = true; return; }
    let event;
    try { event = JSON.parse(raw); }
    catch { throw new ChatCompletionError("模型流式事件不是完整 JSON", "CHAT_INVALID_RESPONSE", true); }
    if (!event || typeof event !== "object" || Array.isArray(event)) {
      throw new ChatCompletionError("模型流式事件格式无效", "CHAT_INVALID_RESPONSE", true);
    }
    if (type === "error" || event.error || event.type === "error") throw httpError(502, errorDetails(raw));
    if (event.usage) usage = event.usage;
    const choice = event.choices?.find(item => item.index === 0) || event.choices?.[0];
    if (!choice) return;
    if (choice.delta?.reasoning_content || choice.delta?.reasoning) onActivity?.("REASONING");
    const content = choice.delta?.content;
    const delta = typeof content === "string" ? content : Array.isArray(content)
      ? content.map(part => typeof part === "string" ? part : String(part?.text || "")).join("") : "";
    if (delta) {
      onActivity?.("TEXT_STREAMING");
      reply += delta;
      onTextDelta?.(delta);
    }
    if (choice.delta?.tool_calls?.length) onActivity?.("TOOL_STREAMING");
    for (const call of choice.delta?.tool_calls || []) {
      if (!Number.isInteger(call.index) || call.index < 0 || call.index >= 64) {
        throw new ChatCompletionError("模型工具调用索引无效", "CHAT_INVALID_RESPONSE", false);
      }
      const target = toolCalls[call.index] ||= { id: "", type: "function", function: { name: "", arguments: "" } };
      if (call.id) target.id += call.id;
      if (call.function?.name) target.function.name += call.function.name;
      if (call.function?.arguments) target.function.arguments += call.function.arguments;
    }
    if (choice.finish_reason) finishReason = choice.finish_reason;
  };
  const consumeLine = line => {
    if (!line) { consumeEvent(); return; }
    if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    if (line.startsWith("event:")) eventType = line.slice(6).replace(/^ /, "");
  };
  const drainLines = () => {
    // Preserve a trailing CR until the next chunk to handle split CRLF pairs.
    while (!done) {
      const match = /\r\n|\n|\r(?!$)/.exec(buffer);
      if (!match) break;
      const line = buffer.slice(0, match.index);
      buffer = buffer.slice(match.index + match[0].length);
      consumeLine(line);
    }
  };
  try {
    while (!done) {
      const next = await reader.read();
      if (next.done) {
        buffer += decoder.decode();
        drainLines();
        if (buffer) consumeLine(buffer.replace(/\r$/, ""));
        consumeEvent();
        break;
      }
      bytes += next.value.byteLength;
      if (bytes > 16 * 1024 * 1024) {
        throw new ChatCompletionError("模型响应超过大小限制", "CHAT_INVALID_RESPONSE", false);
      }
      buffer += decoder.decode(next.value, { stream: true });
      drainLines();
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  // A clean EOF after an explicit successful finish is sufficient for opted-in
  // compatible gateways. Interrupted reads, missing finishes and length stops remain errors.
  if (!done && !(allowMissingDone && ['stop', 'tool_calls'].includes(finishReason))) {
    const error = new ChatCompletionError("模型流式响应缺少结束标记 [DONE]", "CHAT_INCOMPLETE_RESPONSE", true);
    error.streamSummary = { finishReason, replyCharacters: reply.length, toolCallCount: toolCalls.length };
    throw error;
  }
  return { choices: [{ finish_reason: finishReason, message: { content: reply, tool_calls: toolCalls } }], usage };
}

async function requestSingleChatCompletion({ config, apiKey, messages, maxTokens = 6500,
  timeoutMs = 180_000, dispatcher, fetchImpl = fetch, onTextDelta, onActivity, requireStreaming = false, allowMissingDone = false, reasoningEffort, tools, allowToolCalls = false, signal: externalSignal }) {
  const endpoint = chatCompletionEndpoint(config.baseUrl);
  const modelId = String(config.modelId || "").trim();
  if (!modelId || !apiKey) {
    throw new ChatCompletionError("模型 ID 和 API Key 不能为空", "CHAT_INVALID_CONFIGURATION", false);
  }
  const key = `${endpoint}|${modelId}|${reasoningEffort || "default"}|${createHash("sha256").update(apiKey).digest("hex")}`;
  const cached = compatibilityCache.get(key);
  if (cached && cached.expiresAt <= Date.now()) compatibilityCache.delete(key);
  // Use the minimal Chat Completions contract regardless of model name. Some
  // compatible gateways accept newer token parameters but fail for real output
  // budgets. Only an explicit parameter rejection selects the alternative.
  let profile = cached?.expiresAt > Date.now() ? cached.profile : { tokenField: "max_tokens", reasoningEffort };
  const visited = new Set();
  const timeoutSignal = AbortSignal.timeout(Math.max(1, Math.floor(timeoutMs)));
  const signal = externalSignal ? AbortSignal.any([timeoutSignal, externalSignal]) : timeoutSignal;
  for (let attempt = 0; attempt < 2; attempt++) {
    visited.add(JSON.stringify(profile));
    let response;
    let body, parsed;
    try {
      onActivity?.("WAITING_RESPONSE");
      response = await fetchImpl(endpoint, {
        method: "POST",
        headers: { "Authorization": `Bearer ${apiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: modelId, stream: true, messages,
          ...(profile.reasoningEffort ? { reasoning_effort: profile.reasoningEffort } : {}),
          ...(allowToolCalls && tools?.length ? { tools, tool_choice: "auto" } : {}),
          [profile.tokenField]: maxTokens
        }),
        ...(dispatcher ? { dispatcher } : {}), signal
      });
      if (response.ok) parsed = await readCompletionResponse(response, onTextDelta, onActivity, requireStreaming, allowMissingDone);
      else body = await response.text();
    } catch (cause) {
      if (cause?.chatTransport) throw cause;
      if (signal.aborted || cause?.name === "TimeoutError" || cause?.name === "AbortError") {
        throw new ChatCompletionError("模型请求超时", "CHAT_TIMEOUT", true, { cause });
      }
      throw new ChatCompletionError("模型响应中断", "CHAT_NETWORK_INTERRUPTED", true, { cause });
    }
    if (!response.ok) {
      const details = errorDetails(body);
      const next = negotiateProfile(response.status, details, profile);
      if (next && attempt < 1 && !visited.has(JSON.stringify(next))) {
        profile = next;
        continue;
      }
      throw httpError(response.status, details);
    }
    const choice = parsed?.choices?.[0];
    const isToolCall = allowToolCalls && choice?.finish_reason === "tool_calls";
    if (choice?.finish_reason !== "stop" && !isToolCall) {
      const reason = choice?.finish_reason;
      const failureCode = reason === "length" ? "CHAT_OUTPUT_TRUNCATED"
        : reason === "content_filter" ? "CHAT_CONTENT_FILTERED" : "CHAT_INCOMPLETE_RESPONSE";
      throw new ChatCompletionError(`模型响应未正常结束（finish_reason=${String(reason || "missing")}）`,
        failureCode, !["length", "content_filter", "tool_calls", "function_call"].includes(reason));
    }
    const content = choice?.message?.content;
    const reply = (Array.isArray(content)
      ? content.map(part => typeof part === "string" ? part : String(part?.text || "")).join("")
      : typeof content === "string" ? content : "").trim();
    const toolCalls = choice?.message?.tool_calls || [];
    if (isToolCall && (!Array.isArray(toolCalls) || !toolCalls.length || toolCalls.length > 64
        || Array.from(toolCalls).some(call => typeof call?.id !== "string" || !call.id.trim() || call.type !== "function"
          || !tools?.some(tool => tool.function.name === call.function?.name))
        || new Set(Array.from(toolCalls, call => call?.id)).size !== toolCalls.length)) {
      throw new ChatCompletionError("模型返回了未授权或不完整的工具调用", "CHAT_INVALID_TOOL_CALL", false);
    }
    if (!isToolCall && toolCalls.length) {
      throw new ChatCompletionError("工具调用缺少正确结束标记", "CHAT_INVALID_TOOL_CALL", false);
    }
    if (!reply && !isToolCall) throw new ChatCompletionError("模型请求没有返回有效结果", "CHAT_EMPTY_RESPONSE", true);
    if (compatibilityCache.size >= CACHE_LIMIT && !compatibilityCache.has(key)) {
      compatibilityCache.delete(compatibilityCache.keys().next().value);
    }
    compatibilityCache.set(key, { profile, expiresAt: Date.now() + CACHE_TTL_MS });
    return { reply, usage: parsed.usage || {}, toolCalls: isToolCall ? toolCalls : [],
      transport: "streaming", compatibilityRequests: attempt };
  }
}

// Only driver connections carry fallbackModels. Never replay image generation or
// switch providers to work around a content policy rejection.
const FALLBACK_FAILURES = new Set([
  "CHAT_AUTHENTICATION_FAILED", "CHAT_MODEL_UNAVAILABLE", "CHAT_RATE_LIMITED",
  "CHAT_UPSTREAM_FAILED", "CHAT_TIMEOUT", "CHAT_NETWORK_INTERRUPTED",
  "CHAT_INCOMPLETE_RESPONSE", "CHAT_EMPTY_RESPONSE", "CHAT_STREAM_REQUIRED"
]);
export async function requestChatCompletion(options) {
  const { config, apiKey, signal, onTextDelta, onActivity } = options;
  const seen = new Set([`${config.baseUrl}|${config.modelId}|${apiKey}`]);
  const alternatives = (Array.isArray(config.fallbackModels) ? config.fallbackModels : [])
    .filter(route => {
      if (!route?.baseUrl || !route?.modelId || !route?.apiKey) return false;
      const key = `${route.baseUrl}|${route.modelId}|${route.apiKey}`;
      if (seen.has(key)) return false;
      seen.add(key); return true;
    }).slice(0, 2);
  if (!alternatives.length) return requestSingleChatCompletion(options);
  const routes = [{ ...config, apiKey }, ...alternatives];
  const deadline = Date.now() + (options.timeoutMs ?? 180_000);
  let emittedText = false;
  for (let index = 0; index < routes.length; index++) {
    const remaining = deadline - Date.now();
    if (signal?.aborted || remaining <= 0) throw new ChatCompletionError("模型请求超时", "CHAT_TIMEOUT", true);
    const route = routes[index];
    const firstActivity = new AbortController();
    const firstActivityTimer = index < routes.length - 1
      ? setTimeout(() => firstActivity.abort(), Math.min(60_000, remaining / (routes.length - index))) : null;
    const routeSignal = signal ? AbortSignal.any([signal, firstActivity.signal]) : firstActivity.signal;
    try {
      const result = await requestSingleChatCompletion({ ...options, config: route, apiKey: route.apiKey,
        timeoutMs: remaining, signal: routeSignal,
        onActivity: activity => {
          if (["TEXT_STREAMING", "TOOL_STREAMING", "REASONING"].includes(activity)) clearTimeout(firstActivityTimer);
          if (activity === "TOOL_STREAMING") emittedText = true;
          onActivity?.(activity);
        },
        onTextDelta: delta => { clearTimeout(firstActivityTimer); emittedText = true; onTextDelta?.(delta); } });
      if (index > 0) console.info(`[driver-fallback] completed model=${String(route.modelId)} attempt=${index + 1}`);
      return { ...result, modelId: route.modelId, fallbackUsed: index > 0 };
    } catch (error) {
      if (index === routes.length - 1 || emittedText || signal?.aborted || !FALLBACK_FAILURES.has(error.failureCode)) throw error;
      onActivity?.("SWITCHING_MODEL");
    } finally {
      clearTimeout(firstActivityTimer);
    }
  }
}
