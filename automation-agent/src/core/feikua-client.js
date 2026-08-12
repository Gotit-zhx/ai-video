const http = require("node:http");
const WebSocket = require("ws");

class FeikuaClient {
  constructor(baseUrl = "http://127.0.0.1:10039") {
    this.baseUrl = new URL(baseUrl);
  }

  async listStores() {
    const result = await this.#request("/api/external/webdriver/stores");
    const stores = result?.data?.stores;
    if (!Array.isArray(stores)) {
      throw new Error("飞跨没有返回店铺列表");
    }
    return stores;
  }

  async startStore(shopId) {
    if (!shopId) throw new Error("启动飞跨店铺需要 shopId");
    const result = await this.#request(
      "/api/external/webdriver/stores/start",
      "POST",
      { shop_id: String(shopId) },
      120_000
    );
    const port = Number(result?.data?.debugger_port);
    if (!Number.isInteger(port) || port <= 0) {
      throw new Error("飞跨启动成功，但没有返回有效的动态 CDP 端口");
    }
    await this.checkCdp(port);
    return {
      shopId: String(shopId),
      debuggerPort: port,
      coreVersion: String(result?.data?.core_version || "")
    };
  }

  async stopStore(shopId) {
    if (!shopId) throw new Error("停止飞跨店铺需要 shopId");
    await this.#request(
      "/api/external/webdriver/stores/stop",
      "POST",
      { shop_id: String(shopId) },
      30_000
    );
  }

  async checkCdp(port) {
    const numericPort = Number(port);
    if (!Number.isInteger(numericPort) || numericPort <= 0) {
      throw new Error("CDP 端口无效");
    }
    return this.#requestAbsolute(`http://127.0.0.1:${numericPort}/json/version`, "GET", null, 10_000, false);
  }

  async openStorePage(port) {
    const numericPort = Number(port);
    const version = await this.checkCdp(numericPort);
    const webSocketUrl = String(version?.webSocketDebuggerUrl || "");
    if (!webSocketUrl.startsWith("ws://")) {
      throw new Error("飞跨没有返回可连接的浏览器调试地址");
    }

    return this.#withCdp(webSocketUrl, async (send) => {
      const findTargets = async () => (await send("Target.getTargets")).targetInfos || [];
      let targets = await findTargets();
      let storePage = targets.find((target) =>
        target.type === "page" && /^https?:\/\//.test(target.url || "")
      );
      if (storePage) {
        return {
          opened: true,
          reused: true,
          pageTitle: String(storePage.title || ""),
          pageUrl: String(storePage.url || "")
        };
      }

      const detectionPage = targets.find((target) =>
        String(target.url || "").startsWith("chrome-extension://pmpgabbifgjgoefihngfbgppglodojjf/")
      );
      if (!detectionPage) {
        throw new Error("没有找到飞跨的“打开店铺”页面");
      }

      const attached = await send("Target.attachToTarget", {
        targetId: detectionPage.targetId,
        flatten: true
      });
      const evaluation = await send("Runtime.evaluate", {
        expression: `(() => {
          const button = document.querySelector('#open-store');
          if (!button) return { clicked: false };
          button.click();
          return { clicked: true };
        })()`,
        returnByValue: true
      }, attached.sessionId);
      if (!evaluation?.result?.value?.clicked) {
        throw new Error("飞跨页面中没有找到“打开店铺”按钮");
      }

      const deadline = Date.now() + 45_000;
      while (Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 750));
        targets = await findTargets();
        storePage = targets.find((target) =>
          target.type === "page" && /^https?:\/\//.test(target.url || "")
        );
        if (storePage) {
          return {
            opened: true,
            reused: false,
            pageTitle: String(storePage.title || ""),
            pageUrl: String(storePage.url || "")
          };
        }
      }
      throw new Error("店铺浏览器已启动，但等待真实店铺页面超时");
    });
  }

  async executeBrowserAction(action = {}) {
    const type = String(action.type || "").toUpperCase();
    if (type === "WAIT") {
      const seconds = Math.max(1, Math.min(15, Number(action.seconds || 2)));
      await new Promise(resolve => setTimeout(resolve, seconds * 1000));
      return { type, waitedSeconds: seconds };
    }
    const stores = await this.listStores();
    const requested = String(action.storeName || "").trim().toLocaleLowerCase();
    const running = stores.filter(store =>
      Number(store.debugger_port) > 0
      && (["running", "started"].includes(String(store.status || "").toLowerCase())
        || Boolean(store.is_running || store.running))
    );
    const matches = requested
      ? running.filter(store =>
          String(store.shop_name || "").trim().toLocaleLowerCase() === requested
          || String(store.shop_id || "").toLocaleLowerCase() === requested
        )
      : running;
    if (matches.length !== 1) {
      throw new Error(requested
        ? `没有找到唯一运行中的店铺：${action.storeName}`
        : `当前运行 ${matches.length} 个店铺，Agent 必须指定精确店铺名称`);
    }
    const store = matches[0];
    const port = Number(store.debugger_port);
    const version = await this.checkCdp(port);
    const webSocketUrl = String(version?.webSocketDebuggerUrl || "");
    return this.#withCdp(webSocketUrl, async send => {
      const targets = (await send("Target.getTargets")).targetInfos || [];
      const page = targets.find(target =>
        target.type === "page" && /^https?:\/\//.test(target.url || "")
      );
      if (!page) throw new Error("运行中的飞跨店铺没有可操作网页");
      const attached = await send("Target.attachToTarget", {
        targetId: page.targetId,
        flatten: true
      });
      const sessionId = attached.sessionId;
      if (type === "OBSERVE") {
        const observed = await send("Runtime.evaluate", {
          expression: `(() => {
            const visible = el => {
              const r = el.getBoundingClientRect();
              const s = getComputedStyle(el);
              return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
            };
            const nodes = [...document.querySelectorAll('button,a,input,textarea,select,[role="button"],[contenteditable="true"]')]
              .filter(visible).slice(0, 120);
            const elements = nodes.map((el, index) => {
              let ref = el.getAttribute('data-heyi-agent-ref');
              if (!ref) {
                ref = 'h' + Math.random().toString(36).slice(2, 10);
                el.setAttribute('data-heyi-agent-ref', ref);
              }
              return {
                ref,
                tag: el.tagName.toLowerCase(),
                type: String(el.getAttribute('type') || ''),
                text: String(el.innerText || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || '').trim().slice(0, 180),
                disabled: Boolean(el.disabled || el.getAttribute('aria-disabled') === 'true')
              };
            });
            return {
              title: document.title,
              url: location.href,
              text: String(document.body?.innerText || '').slice(0, 12000),
              elements
            };
          })()`,
          returnByValue: true
        }, sessionId);
        const result = observed?.result?.value || {};
        if (action.includeScreenshot) {
          const shot = await send("Page.captureScreenshot", {
            format: "jpeg",
            quality: 55,
            captureBeyondViewport: false
          }, sessionId);
          result.screenshot = shot.data || "";
          result.screenshotMimeType = "image/jpeg";
        }
        return {
          type,
          storeName: String(store.shop_name || ""),
          shopId: String(store.shop_id || ""),
          ...result
        };
      }
      const ref = String(action.elementRef || "");
      if (!/^h[a-z0-9]{8}$/.test(ref)) throw new Error("页面元素引用无效或已经过期");
      const inspected = await send("Runtime.evaluate", {
        expression: `(() => {
          const el = document.querySelector('[data-heyi-agent-ref="${ref}"]');
          if (!el) return { found: false };
          return {
            found: true,
            tag: el.tagName.toLowerCase(),
            type: String(el.getAttribute('type') || ''),
            text: String(el.innerText || el.value || el.getAttribute('aria-label') || '').trim().slice(0, 180)
          };
        })()`,
        returnByValue: true
      }, sessionId);
      const info = inspected?.result?.value || {};
      if (!info.found) throw new Error("页面已变化，请让 Agent 重新观察后再操作");
      if (type === "CLICK") {
        await send("Runtime.evaluate", {
          expression: `document.querySelector('[data-heyi-agent-ref="${ref}"]').click()`,
          returnByValue: true
        }, sessionId);
        return { type, storeName: String(store.shop_name || ""), elementRef: ref, clicked: true, element: info };
      }
      if (type === "TYPE") {
        const value = JSON.stringify(String(action.text || "").slice(0, 2000));
        await send("Runtime.evaluate", {
          expression: `(() => {
            const el = document.querySelector('[data-heyi-agent-ref="${ref}"]');
            el.focus();
            if (${action.clearFirst !== false}) el.value = '';
            el.value += ${value};
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            return true;
          })()`,
          returnByValue: true
        }, sessionId);
        return { type, storeName: String(store.shop_name || ""), elementRef: ref, typed: true, element: info };
      }
      throw new Error(`不支持的浏览器动作：${type}`);
    });
  }

  async #request(pathname, method = "GET", payload = null, timeoutMs = 30_000) {
    const result = await this.#requestAbsolute(
      new URL(pathname, this.baseUrl).toString(),
      method,
      payload,
      timeoutMs,
      true
    );
    if (result?.code !== 0) {
      throw new Error(result?.message || `飞跨操作失败（code=${result?.code ?? "unknown"}）`);
    }
    return result;
  }

  #requestAbsolute(url, method, payload, timeoutMs, expectFeikuaEnvelope) {
    return new Promise((resolve, reject) => {
      const target = new URL(url);
      const body = payload ? JSON.stringify(payload) : "";
      const request = http.request({
        hostname: target.hostname,
        port: target.port,
        path: `${target.pathname}${target.search}`,
        method,
        timeout: timeoutMs,
        headers: body
          ? {
              "Content-Type": "application/json",
              "Content-Length": Buffer.byteLength(body)
            }
          : {}
      }, (response) => {
        let content = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          content += chunk;
        });
        response.on("end", () => {
          if ((response.statusCode || 500) >= 400) {
            reject(new Error(`飞跨本地接口返回 HTTP ${response.statusCode}`));
            return;
          }
          try {
            const result = content ? JSON.parse(content) : {};
            if (expectFeikuaEnvelope && typeof result?.code !== "number") {
              reject(new Error("飞跨本地接口返回格式不正确"));
              return;
            }
            resolve(result);
          } catch {
            reject(new Error("飞跨本地接口没有返回有效 JSON"));
          }
        });
      });
      request.on("timeout", () => request.destroy(new Error("连接飞跨超时")));
      request.on("error", reject);
      if (body) request.write(body);
      request.end();
    });
  }

  #withCdp(webSocketUrl, callback) {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(webSocketUrl, {
        origin: null,
        handshakeTimeout: 10_000
      });
      let sequence = 0;
      const pending = new Map();

      const closeWithError = (error) => {
        for (const item of pending.values()) item.reject(error);
        pending.clear();
        reject(error);
      };

      socket.on("error", closeWithError);
      socket.on("close", () => {
        if (pending.size) closeWithError(new Error("飞跨浏览器调试连接已关闭"));
      });
      socket.on("message", (content) => {
        let message;
        try {
          message = JSON.parse(content.toString());
        } catch {
          return;
        }
        if (!message.id || !pending.has(message.id)) return;
        const item = pending.get(message.id);
        pending.delete(message.id);
        if (message.error) item.reject(new Error(message.error.message || "CDP 操作失败"));
        else item.resolve(message.result || {});
      });
      socket.on("open", async () => {
        const send = (method, params = {}, sessionId) => new Promise((commandResolve, commandReject) => {
          const id = ++sequence;
          pending.set(id, { resolve: commandResolve, reject: commandReject });
          socket.send(JSON.stringify({
            id,
            method,
            params,
            ...(sessionId ? { sessionId } : {})
          }));
        });
        try {
          const result = await callback(send);
          socket.close();
          resolve(result);
        } catch (error) {
          socket.close();
          reject(error);
        }
      });
    });
  }
}

module.exports = { FeikuaClient };
