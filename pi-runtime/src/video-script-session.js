import { chatCompletionEndpoint, requestChatCompletion } from "./chat-completion-transport.js";

// Video analysis does not use tools. The shared transport requires a complete
// response and explicit termination before accepting a storyboard or its audit.
export const VIDEO_SCRIPT_TOTAL_TIMEOUT_MS = 900_000;
// A long first analysis may use the whole budget. Every later pass receives
// only the remaining time; the session never renews its absolute deadline.
export const VIDEO_SCRIPT_REQUEST_TIMEOUT_MS = VIDEO_SCRIPT_TOTAL_TIMEOUT_MS;

export class VideoScriptSession {
  constructor({ config, apiKey, systemPrompt, dispatcher,
    timeoutMs = VIDEO_SCRIPT_REQUEST_TIMEOUT_MS,
    totalTimeoutMs = VIDEO_SCRIPT_TOTAL_TIMEOUT_MS }) {
    this.config = { ...config, baseUrl: chatCompletionEndpoint(config.baseUrl) };
    const modelId = String(config.modelId || "").trim();
    if (!modelId) throw new Error("模型 ID 不能为空");
    this.modelId = modelId;
    this.apiKey = apiKey;
    this.dispatcher = dispatcher;
    this.timeoutMs = timeoutMs;
    this.deadline = Date.now() + totalTimeoutMs;
    this.messages = [{ role: "system", content: systemPrompt }];
    this.state = { messages: [] };
    this.listeners = new Set();
  }

  subscribe(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async prompt(text, frames = []) {
    const remainingMs = this.deadline - Date.now();
    if (remainingMs <= 0) throw new Error("视频脚本分析超过总时间限制");
    const user = {
      role: "user",
      content: [
        { type: "text", text },
        ...frames.map(frame => ({
          type: "image_url",
          image_url: { url: `data:${frame.mimeType};base64,${frame.data}` }
        }))
      ]
    };
    const messages = [...this.messages, user];
    let partialReply = "";
    const { reply, usage } = await requestChatCompletion({
      config: this.config,
      apiKey: this.apiKey,
      messages,
      maxTokens: 6500,
      timeoutMs: Math.min(this.timeoutMs, remainingMs),
      dispatcher: this.dispatcher,
      onTextDelta: delta => {
        partialReply += delta;
        const message = { role: "assistant", content: [{ type: "text", text: partialReply }] };
        for (const listener of this.listeners) listener({ type: "message_update", message });
      }
    });
    const input = Number(usage.prompt_tokens || 0);
    const output = Number(usage.completion_tokens || 0);
    const cacheRead = Number(usage.prompt_tokens_details?.cached_tokens || 0);
    const assistant = {
      role: "assistant",
      content: [{ type: "text", text: reply }],
      stopReason: "stop",
      usage: {
        input: Math.max(0, input - cacheRead), output, cacheRead, cacheWrite: 0,
        reasoning: Number(usage.completion_tokens_details?.reasoning_tokens || 0),
        totalTokens: Number(usage.total_tokens || input + output)
      }
    };
    this.messages.push(user, { role: "assistant", content: reply });
    this.state.messages.push({ role: "user", content: [{ type: "text", text }] }, assistant);
    for (const listener of this.listeners) listener({ type: "message_end", message: assistant });
  }
}
