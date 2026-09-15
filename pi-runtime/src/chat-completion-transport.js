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
  // streaming, remove the output limit, or retry 5xx by guessing parameters.
  const explicitParam = details.param || (
    details.message.match(/(?:unsupported|unrecognized|unknown)\s+(?:request\s+)?(?:parameter|argument)(?:\s+supplied)?\s*:?\s*["'`]?([a-z_]+)/i)?.[1]
    || details.message.match(/["'`]?(reasoning_effort|max_completion_tokens|max_tokens)["'`]?\s+(?:is\s+)?(?:not supported|unsupported|not allowed)/i)?.[1]
    || details.message.match(/does not support\s+(?:the\s+)?(?:parameter\s+)?["'`]?(reasoning_effort|max_completion_tokens|max_tokens)/i)?.[1]
  );
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
  return new ChatCompletionError(`模型非流式请求失败 ${status}`, "CHAT_UPSTREAM_FAILED", status >= 500 || status === 408, { upstreamStatus: status });
}

export async function requestChatCompletion({ config, apiKey, messages, maxTokens = 6500,
  timeoutMs = 180_000, dispatcher, fetchImpl = fetch }) {
  const endpoint = chatCompletionEndpoint(config.baseUrl);
  const modelId = String(config.modelId || "").trim();
  if (!modelId || !apiKey) {
    throw new ChatCompletionError("模型 ID 和 API Key 不能为空", "CHAT_INVALID_CONFIGURATION", false);
  }
  const key = `${endpoint}|${modelId}|${createHash("sha256").update(apiKey).digest("hex")}`;
  const cached = compatibilityCache.get(key);
  if (cached && cached.expiresAt <= Date.now()) compatibilityCache.delete(key);
  // Use the minimal Chat Completions contract regardless of model name. Some
  // compatible gateways accept newer token parameters but fail for real output
  // budgets. Only an explicit parameter rejection selects the alternative.
  let profile = cached?.expiresAt > Date.now() ? cached.profile : { tokenField: "max_tokens" };
  const visited = new Set();
  const signal = AbortSignal.timeout(Math.max(1, Math.floor(timeoutMs)));
  for (let attempt = 0; attempt < 2; attempt++) {
    visited.add(JSON.stringify(profile));
    let response;
    let body;
    try {
      response = await fetchImpl(endpoint, {
        method: "POST",
        headers: { "Authorization": `Bearer ${apiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: modelId, stream: false, messages,
          [profile.tokenField]: maxTokens
        }),
        ...(dispatcher ? { dispatcher } : {}), signal
      });
      body = await response.text();
    } catch (cause) {
      if (signal.aborted || cause?.name === "TimeoutError" || cause?.name === "AbortError") {
        throw new ChatCompletionError("模型非流式请求超时", "CHAT_TIMEOUT", true, { cause });
      }
      throw new ChatCompletionError("模型非流式响应中断", "CHAT_NETWORK_INTERRUPTED", true, { cause });
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
    let parsed;
    try {
      parsed = JSON.parse(body);
    } catch {
      throw new ChatCompletionError("模型非流式响应不是完整 JSON", "CHAT_INVALID_RESPONSE", true);
    }
    const choice = parsed?.choices?.[0];
    if (choice?.finish_reason !== "stop") {
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
    if (!reply) throw new ChatCompletionError("模型非流式请求没有返回有效结果", "CHAT_EMPTY_RESPONSE", true);
    if (compatibilityCache.size >= CACHE_LIMIT && !compatibilityCache.has(key)) {
      compatibilityCache.delete(compatibilityCache.keys().next().value);
    }
    compatibilityCache.set(key, { profile, expiresAt: Date.now() + CACHE_TTL_MS });
    return { reply, usage: parsed.usage || {}, transport: "non-streaming", compatibilityRequests: attempt };
  }
}
