import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import { requestChatCompletion, ChatCompletionError } from "./chat-completion-transport.js";

function imagePart(part) {
  return { type: "image_url", image_url: { url: `data:${part.mimeType};base64,${part.data}` } };
}

export function watchMessages(context) {
  const messages = [{ role: "system", content: context.systemPrompt || "" }];
  let pendingImages = [];
  for (const message of context.messages || []) {
    if (message.role !== "toolResult" && pendingImages.length) {
      messages.push({ role: "user", content: pendingImages });
      pendingImages = [];
    }
    const content = Array.isArray(message.content) ? message.content : [{ type: "text", text: String(message.content || "") }];
    const text = content.filter(part => part.type === "text").map(part => part.text).join("\n");
    if (message.role === "user") {
      messages.push({ role: "user", content: content.filter(part => ["text", "image"].includes(part.type))
        .map(part => part.type === "image" ? imagePart(part) : part) });
    } else if (message.role === "assistant") {
      const calls = content.filter(part => part.type === "toolCall").map(part => ({
        id: part.id, type: "function", function: { name: part.name, arguments: JSON.stringify(part.arguments) }
      }));
      messages.push({ role: "assistant", content: text || null, ...(calls.length ? { tool_calls: calls } : {}) });
    } else if (message.role === "toolResult") {
      messages.push({ role: "tool", tool_call_id: message.toolCallId, content: text });
      // Complete every tool receipt before attaching the associated visual
      // evidence, as compatible gateways commonly accept tool content as text.
      for (const part of content.filter(part => part.type === "image")) {
        pendingImages.push({ type: "text", text: `${message.toolName}: ${text}` }, imagePart(part));
      }
    }
  }
  if (pendingImages.length) messages.push({ role: "user", content: pendingImages });
  return messages;
}

export function createWatchModelTransport({ config, apiKey, deadline, dispatcher, onFailure, onActivity, maxCalls = 16, idleTimeoutMs = 120_000, maxTokens = 6500 }) {
  let calls = 0;
  return {
    streamSimple(model, context, options = {}) {
      const stream = createAssistantMessageEventStream();
      const assistant = { role: "assistant", content: [{ type: "text", text: "" }],
        api: model.api, provider: model.provider, model: model.id, timestamp: Date.now(), stopReason: "stop",
        usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } };
      void (async () => {
        const idleController = new AbortController();
        let idleTimer;
        const resetIdle = () => {
          clearTimeout(idleTimer);
          idleTimer = setTimeout(() => idleController.abort(), idleTimeoutMs);
        };
        resetIdle();
        try {
          if (++calls > maxCalls || Date.now() >= deadline) {
            throw new ChatCompletionError("watch 视频分析超过调用次数或总时间限制", "WATCH_BUDGET_EXCEEDED", false);
          }
          stream.push({ type: "start", partial: assistant });
          const request = () => requestChatCompletion({ config, apiKey, maxTokens, messages: watchMessages(context),
            tools: (context.tools || []).map(tool => ({ type: "function", function: {
              name: tool.name, description: tool.description, parameters: tool.parameters
            } })), allowToolCalls: true, timeoutMs: deadline - Date.now(), dispatcher, requireStreaming: true, allowMissingDone: true, reasoningEffort: "low",
            signal: options.signal ? AbortSignal.any([options.signal, idleController.signal]) : idleController.signal,
            onActivity: activity => {
              if (activity === "TEXT_STREAMING" || activity === "TOOL_STREAMING" || activity === "REASONING") resetIdle();
              onActivity?.(activity);
            },
            onTextDelta: delta => {
              assistant.content[0].text += delta;
              stream.push({ type: "text_delta", contentIndex: 0, delta, partial: assistant });
            } });
          let result;
          for (let attempt = 0; ; attempt++) {
            try { result = await request(); break; }
            catch (error) {
              if (attempt || assistant.content[0].text || calls >= maxCalls || Date.now() >= deadline
                  || idleController.signal.aborted || options.signal?.aborted
                  || !['CHAT_INCOMPLETE_RESPONSE', 'CHAT_NETWORK_INTERRUPTED'].includes(error.failureCode)) throw error;
              calls++;
              resetIdle();
              onActivity?.('RETRYING_RESPONSE');
              console.info(`[watch] retrying incomplete empty provider turn once code=${error.failureCode}`);
            }
          }
          if (!assistant.content[0].text) assistant.content[0].text = result.reply;
          for (const call of result.toolCalls) {
            let args;
            try { args = JSON.parse(call.function.arguments); } catch {
              throw new ChatCompletionError("watch 工具参数不是完整 JSON", "CHAT_INVALID_TOOL_CALL", false);
            }
            if (!args || typeof args !== "object" || Array.isArray(args)) {
              throw new ChatCompletionError("watch 工具参数无效", "CHAT_INVALID_TOOL_CALL", false);
            }
            assistant.content.push({ type: "toolCall", id: call.id, name: call.function.name, arguments: args });
          }
          assistant.stopReason = result.toolCalls.length ? "toolUse" : "stop";
          assistant.usage.input = Number(result.usage.prompt_tokens || 0);
          assistant.usage.output = Number(result.usage.completion_tokens || 0);
          assistant.usage.totalTokens = Number(result.usage.total_tokens || assistant.usage.input + assistant.usage.output);
          stream.push({ type: "done", reason: assistant.stopReason, message: assistant });
        } catch (error) {
          if (idleController.signal.aborted) error = new ChatCompletionError(
            "上游连续 120 秒未返回推理活动、正文或读图指令，已停止等待，请检查模型接口后重试", "WATCH_UPSTREAM_IDLE", false);
          // Do not replay an entire paid analysis after an ambiguous provider failure.
          error.retryable = false;
          onFailure?.(error);
          assistant.stopReason = "error";
          assistant.errorMessage = error.message;
          stream.push({ type: "error", reason: "error", error: assistant });
        } finally { clearTimeout(idleTimer); stream.end(); }
      })();
      return stream;
    }
  };
}
