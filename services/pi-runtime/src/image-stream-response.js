// Images SSE returns partial previews as well as final images. Only a completed
// event is a billable result we can persist and hand to the image pipeline.
export async function readImageResponse(response, {onActivity = () => {}, maxBytes = 128 * 1024 * 1024} = {}) {
  if (!response.ok || !/text\/event-stream/i.test(response.headers.get("content-type") || "")) {
    return {text: await response.text()};
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "", bytes = 0, eventName = "", data = [];
  function event() {
    const raw = data.join("\n");
    data = [];
    const name = eventName;
    eventName = "";
    if (!raw || raw === "[DONE]") return null;
    const value = JSON.parse(raw);
    const type = value.type || name;
    if (type === "error" || String(type).endsWith(".failed") || value.error) {
      const error = value.error || value;
      throw Object.assign(new Error(String(error.message || "图片通道返回错误")), {
        code: String(error.code || "IMAGE_STREAM_PROVIDER_ERROR"), providerResponseError: true
      });
    }
    if (type === "image_generation.completed" || type === "image_edit.completed") {
      if (!value.b64_json && !value.url) throw new Error("图片流最终事件缺少图片");
      return {data: [value], ...(value.usage ? {usage: value.usage} : {})};
    }
    if ([name, value.object].some(kind => kind === "image.generation.result" || kind === "image.edit.result")) {
      if (!Array.isArray(value.data) || !value.data.some(image => image.b64_json || image.url))
        throw new Error("图片流最终事件缺少图片");
      return value;
    }
    return null;
  }
  try {
    while (true) {
      const {value, done} = await reader.read();
      if (value) {
        bytes += value.byteLength;
        if (bytes > maxBytes) throw new Error("图片流响应超过大小上限");
        onActivity();
        buffer += decoder.decode(value, {stream: true});
      }
      if (done) buffer += decoder.decode() + "\n\n";
      let newline;
      while ((newline = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, newline).replace(/\r$/, "");
        buffer = buffer.slice(newline + 1);
        let result;
        if (!line) result = event();
        else if (line.startsWith("event:")) eventName = line.slice(6).trim();
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
        if (result) return {payload: result};
      }
      if (done) throw Object.assign(new Error("图片流已结束但没有收到最终图片，保留原请求"), {
        code: "IMAGE_STREAM_INCOMPLETE"
      });
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
