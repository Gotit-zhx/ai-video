import { AsyncLocalStorage } from "node:async_hooks";
import { streamSimple } from "@earendil-works/pi-ai/api/openai-completions";

// Pi's provider does not expose a per-request fetch override. Scope the adapter
// to recruitment with async context; every other runtime request is untouched.
const requests = new AsyncLocalStorage();
const originalFetch = globalThis.fetch;
globalThis.fetch = (...args) => {
  const state = requests.getStore();
  return state ? compatibleResponse(originalFetch(...args), state) : originalFetch(...args);
};

function textContent(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content) || content.some(part => typeof part !== "string"
      && (part?.type !== "text" || typeof part.text !== "string"))) return null;
  return content.map(part => typeof part === "string" ? part : part.text).join("");
}

function completeObject(text) {
  try {
    const value = JSON.parse(text.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, ""));
    return value != null && typeof value === "object" && !Array.isArray(value);
  } catch { return false; }
}

function reject(state, category, status) {
  state.failure = category;
  if (Number.isInteger(status) && status >= 400 && status <= 599) state.upstreamStatus = status;
  throw new Error("Recruitment transport: " + category);
}

async function compatibleResponse(promise, state) {
  const response = await promise;
  state.httpStatus = response.status;
  if (!response.ok || !response.body) return response;
  const headers = new Headers(response.headers);
  const encoder = new TextEncoder();
  if (/application\/json/i.test(headers.get("content-type") || "")) {
    const raw = await response.text();
    if (raw.length > 512000) reject(state, "oversized");
    let value;
    try { value = JSON.parse(raw); } catch { reject(state, "invalid_json_response"); }
    if (value.error) reject(state, "upstream_error", Number(value.error.status || value.error.status_code));
    const choice = value.choices?.[0], message = choice?.message;
    const content = textContent(message?.content);
    if (!choice?.finish_reason || !message || content == null) reject(state, "incomplete_json_response");
    state.compatibility = "buffered_completion";
    headers.set("content-type", "text/event-stream");
    headers.delete("content-length"); headers.delete("content-encoding");
    return new Response(`data: ${JSON.stringify({ ...value, choices: [
      { ...choice, delta: { ...message, content }, message: undefined },
    ] })}\n\ndata: [DONE]\n\n`, { status: response.status, headers });
  }
  if (!/text\/event-stream/i.test(headers.get("content-type") || "")) {
    await response.body.cancel();
    reject(state, "unsupported_content_type");
  }
  const reader = response.body.getReader(), decoder = new TextDecoder("utf-8", { fatal: true });
  let buffer = "", data = [], eventType = "", reply = "", finished = false, terminal = false, bytes = 0, tools = false;
  const stream = new ReadableStream({
    async start(controller) {
      const send = value => controller.enqueue(encoder.encode(`data: ${value}\n\n`));
      const frame = () => {
        const type = eventType; eventType = "";
        if (!data.length) return;
        const raw = data.join("\n").trim(); data = [];
        if (raw === "[DONE]") {
          state.done = true; terminal = true;
          // A gateway may omit finish_reason while explicitly completing the
          // same response. Require a complete JSON object and no tool/error;
          // never accept a prefix, an EOF or an explicit length/filter stop.
          if (!finished && !tools && completeObject(reply)) {
            send(JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: "stop" }] }));
            state.compatibility = "done_without_finish_reason";
          }
          send("[DONE]"); return;
        }
        let value;
        try { value = JSON.parse(raw); } catch { reject(state, "invalid_sse_json"); }
        if (!value || typeof value !== "object" || Array.isArray(value)) reject(state, "invalid_sse_event");
        if (type === "error" || value.type === "error" || value.error) {
          const error = value.error || value;
          reject(state, "upstream_error", Number(error.status || error.status_code || value.status));
        }
        const choice = value.choices?.[0];
        if (choice) {
          if (choice.finish_reason) { finished = true; state.finishReason = choice.finish_reason; }
          if (choice.delta?.tool_calls?.length || choice.message?.tool_calls?.length) tools = true;
          // Some compatible gateways send a single message or text-part array
          // inside SSE instead of a string delta. Normalize before Pi reads it.
          if (!choice.delta && choice.message) {
            choice.delta = choice.message; delete choice.message;
            state.compatibility = "message_inside_sse";
          }
          if (choice.delta?.content != null) {
            const content = textContent(choice.delta.content);
            if (content == null) reject(state, "invalid_content");
            if (Array.isArray(choice.delta.content)) state.compatibility = "text_parts";
            choice.delta.content = content; reply += content;
            if (reply.length > 200000) reject(state, "oversized");
          }
        }
        send(JSON.stringify(value));
      };
      const line = value => {
        if (!value) frame();
        else if (value.startsWith("data:")) data.push(value.slice(5).replace(/^ /, ""));
        else if (value.startsWith("event:")) eventType = value.slice(6).trim();
      };
      const drain = () => {
        while (!terminal) {
          const match = /\r\n|\n|\r(?!$)/.exec(buffer);
          if (!match) break;
          line(buffer.slice(0, match.index));
          buffer = buffer.slice(match.index + match[0].length);
        }
      };
      try {
        while (!terminal) {
          const next = await reader.read();
          if (next.done) {
            buffer += decoder.decode(); drain();
            if (!terminal && buffer) line(buffer.replace(/\r$/, ""));
            if (!terminal) frame();
            state.cleanEof = true; break;
          }
          bytes += next.value.byteLength;
          if (bytes > 16 * 1024 * 1024) reject(state, "oversized");
          buffer += decoder.decode(next.value, { stream: true }); drain();
        }
        state.receivedChars = reply.length;
        controller.close();
      } catch (error) {
        state.failure ||= "interrupted";
        controller.error(error);
      } finally {
        await reader.cancel().catch(() => {}); reader.releaseLock();
      }
    },
    cancel: () => reader.cancel(),
  });
  headers.delete("content-length"); headers.delete("content-encoding");
  return new Response(stream, { status: response.status, headers });
}

export function streamRecruitment(model, context, options) {
  const state = {};
  const events = requests.run(state, () => streamSimple(model, context, options));
  Object.defineProperty(events, "transportDiagnostics", { value: state });
  return events;
}
