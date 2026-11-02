/* Download one video at a time; never retain an entire batch in browser memory. */
(() => {
  async function download(file, onProgress) {
    const controller = new AbortController();
    let timeout;
    let reader;
    const keepAlive = () => {
      clearTimeout(timeout);
      timeout = setTimeout(() => controller.abort(), 120_000);
    };
    keepAlive();
    try {
      const response = await fetch(file.url, {
        credentials: "same-origin", cache: "no-store", redirect: "error", signal: controller.signal
      });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        const error = new Error(response.status === 401 ? "登录已过期，请重新登录后重试"
          : body?.message || `视频下载失败（${response.status}），请重试`);
        error.status = response.status;
        throw error;
      }
      const type = response.headers.get("Content-Type") || "";
      if (!/^(video\/|application\/octet-stream)/i.test(type)) {
        throw new Error("未收到视频文件，请重新登录或稍后重试");
      }
      const total = Number(response.headers.get("Content-Length")) || file.size || 0;
      const chunks = [];
      let received = 0;
      reader = response.body.getReader();
      while (true) {
        keepAlive();
        const { value, done } = await reader.read();
        if (done) break;
        chunks.push(value);
        received += value.byteLength;
        onProgress({ received, total });
      }
      if (!received || (total > 0 && received !== total)) {
        throw new Error("视频文件未接收完整，请重试");
      }
      clearTimeout(timeout);
      const blobUrl = URL.createObjectURL(new Blob(chunks, { type }));
      chunks.length = 0;
      const anchor = document.createElement("a");
      try {
        anchor.href = blobUrl;
        anchor.download = file.filename;
        document.body.appendChild(anchor);
        anchor.click();
        // Allow the browser to accept the file before releasing its object URL.
        await new Promise(resolve => setTimeout(resolve, 1000));
      } finally {
        anchor.remove();
        URL.revokeObjectURL(blobUrl);
      }
    } catch (error) {
      if (controller.signal.aborted) throw new Error("视频下载超时，请重试");
      if (error instanceof TypeError) throw new Error("视频传输中断，请检查网络后重试");
      throw error;
    } finally {
      clearTimeout(timeout);
      if (reader) {
        await reader.cancel().catch(() => {});
        reader.releaseLock();
      }
      controller.abort();
    }
  }

  async function run(files, { onProgress = () => {}, onAuthExpired = () => {} } = {}) {
    const result = { succeeded: [], failed: [] };
    for (let index = 0; index < files.length; index++) {
      const file = files[index];
      const update = progress => onProgress({ index: index + 1, count: files.length, file, ...progress });
      update({ received: 0, total: file.size || 0 });
      try {
        await download(file, update);
        result.succeeded.push(file.id);
      } catch (error) {
        result.failed.push({ ...file, error: error.message });
        if (error.status === 401) {
          result.failed.push(...files.slice(index + 1).map(next => ({ ...next, error: "登录已过期，尚未下载" })));
          onAuthExpired();
          break;
        }
      }
    }
    return result;
  }

  window.HeyVideoDownloads = { run };
})();
