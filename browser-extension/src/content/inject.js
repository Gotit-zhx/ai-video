(() => {
  "use strict";

  const common = globalThis.HeyiCollector;
  const adapter = common.adapters?.find(candidate => candidate.matchesPage());
  if (!adapter) return;

  const MESSAGE_GET_STATE = "HEYI_GET_STATE";
  const MESSAGE_CAPTURE = "HEYI_CAPTURE_PRODUCT";
  const MESSAGE_OPEN_DETAIL = "HEYI_OPEN_PRODUCT_DETAIL";
  const MESSAGE_TAKE_PENDING = "HEYI_TAKE_PENDING_CAPTURE";
  const cardControls = new WeakMap();
  const controlsByUrl = new Map();
  const captureState = new Map();
  let detailControl = null;
  let blockedNotice = null;
  let scheduled = false;
  let lastUrl = location.href;
  let authenticated = null;

  void initialize();
  scanPage();

  const observer = new MutationObserver(scheduleScan);
  observer.observe(document.documentElement, { childList: true, subtree: true });
  window.addEventListener("scroll", scheduleScan, { passive: true });
  window.setInterval(() => {
    if (location.href !== lastUrl) {
      lastUrl = location.href;
      detailControl?.remove();
      detailControl = null;
      scheduleScan();
    }
  }, 1_000);

  function scheduleScan() {
    if (scheduled) return;
    scheduled = true;
    window.setTimeout(() => {
      scheduled = false;
      scanPage();
    }, 240);
  }

  function scanPage() {
    cleanupControlRegistry();
    const blockedReason = adapter.blockedReason?.() || "";
    if (blockedReason) {
      clearCaptureControls();
      ensureBlockedNotice(blockedReason);
      return;
    }
    blockedNotice?.remove();
    blockedNotice = null;
    if (adapter.isDetailUrl(location.href)) ensureDetailControl();
    else if (detailControl) {
      detailControl.remove();
      detailControl = null;
    }

    const anchors = adapter.productAnchors(document).slice(0, 320);
    for (const anchor of anchors) {
      if (!nearViewport(anchor) || anchor.closest("[data-heyi-collector-host]")) continue;
      const productUrl = anchor.href;
      const card = common.productCardAncestor(anchor);
      if (!card || card === document.body || card === document.documentElement) continue;
      const previous = cardControls.get(card);
      if (previous?.productUrl === productUrl && previous.host.isConnected) continue;
      previous?.remove();
      const control = createControl("CARD", productUrl, card);
      cardControls.set(card, control);
    }
  }

  function ensureDetailControl() {
    if (detailControl?.host.isConnected && detailControl.productUrl === location.href) return;
    detailControl?.remove();
    detailControl = createControl("DETAIL", location.href, document.body);
  }

  function createControl(pageType, productUrl, container) {
    const host = document.createElement("span");
    host.dataset.heyiCollectorHost = pageType.toLowerCase();
    host.setAttribute("aria-label", "赫逸商品采集");
    if (pageType === "DETAIL") {
      host.style.cssText = "all:initial;position:fixed;right:18px;top:44%;z-index:2147483646;pointer-events:auto;";
    } else {
      if (getComputedStyle(container).position === "static" && !container.style.position) {
        container.style.position = "relative";
      }
      host.style.cssText = "all:initial;position:absolute;right:8px;top:8px;z-index:2147483000;pointer-events:auto;";
    }

    const shadow = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = `
      :host { all: initial; }
      button {
        all: initial;
        box-sizing: border-box;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: ${pageType === "DETAIL" ? "112px" : "78px"};
        height: ${pageType === "DETAIL" ? "38px" : "30px"};
        padding: 0 ${pageType === "DETAIL" ? "16px" : "10px"};
        border: 1px solid rgba(255,255,255,.46);
        border-radius: 999px;
        color: #fff;
        background: linear-gradient(135deg,#7657ff,#4f8cff 56%,#25bca4);
        box-shadow: 0 8px 24px rgba(43,55,112,.28);
        font: 600 ${pageType === "DETAIL" ? "14px" : "12px"}/1 -apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif;
        letter-spacing: .01em;
        cursor: pointer;
        user-select: none;
        white-space: nowrap;
        transition: transform .16s ease, filter .16s ease, opacity .16s ease;
      }
      button:hover { transform: translateY(-1px); filter: brightness(1.05); }
      button:focus-visible { outline: 2px solid #fff; outline-offset: 2px; }
      button[disabled] { cursor: default; opacity: .86; transform: none; }
      button[data-state="success"] { background: #16845b; }
      button[data-state="error"] { background: #bd3f4d; }
      button[data-state="auth"] { background: #555e73; }
      button[data-state="loading"] { background: #4c66bb; }
      button[data-state="incomplete"] { background: #a56a12; }
    `;
    const button = document.createElement("button");
    button.type = "button";
    shadow.append(style, button);
    shadow.addEventListener("pointerdown", event => event.stopPropagation(), true);

    const control = {
      host,
      button,
      pageType,
      productUrl,
      remove() {
        unregisterControl(control);
        host.remove();
      },
      render(state = captureState.get(productUrl) || "idle", message = "") {
        const labels = {
          idle: authenticated === false ? "登录后采集" : "一键采集",
          loading: "采集中…",
          success: "已采集",
          error: "采集失败 · 重试",
          auth: "请先登录",
          permission: "无采集权限",
          incomplete: "SKU待确认 · 重试"
        };
        button.textContent = labels[state] || labels.idle;
        button.dataset.state = state === "permission" ? "error" : state;
        button.disabled = state === "loading" || state === "success" || state === "permission";
        button.title = message || (state === "auth" ? "请点击浏览器工具栏中的赫逸插件完成登录配对" : "采集当前商品到赫逸平台");
      }
    };
    registerControl(control);
    control.render();
    button.addEventListener("click", event => {
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      void runCapture(control, container);
    });
    container.appendChild(host);
    return control;
  }

  async function runCapture(control, root) {
    if (captureState.get(control.productUrl) === "loading" || captureState.get(control.productUrl) === "success") return;
    const blockedReason = adapter.blockedReason?.() || "";
    if (blockedReason) {
      clearCaptureControls();
      ensureBlockedNotice(blockedReason);
      return;
    }
    if (!(await ensureAuthenticated())) {
      setUrlState(control.productUrl, "auth", "请先点击浏览器工具栏中的赫逸插件完成登录配对");
      return;
    }
    setUrlState(control.productUrl, "loading");
    let payload;
    try {
      if (control.pageType === "CARD") {
        const opened = await chrome.runtime.sendMessage({ type: MESSAGE_OPEN_DETAIL, productUrl: control.productUrl });
        if (!opened?.ok) {
          const error = new Error(opened?.error?.message || "无法打开商品详情页");
          error.details = opened?.error || {};
          throw error;
        }
        location.assign(opened.data?.url || control.productUrl);
        return;
      }
      const extractionRoot = control.pageType === "DETAIL"
        ? document.querySelector("main") || document.body
        : root;
      payload = await adapter.extract(extractionRoot, control.productUrl, control.pageType);
      if (!payload?.title) throw new Error("未识别到商品标题，请进入商品详情页后重试");
      const response = await chrome.runtime.sendMessage({ type: MESSAGE_CAPTURE, payload });
      if (!response?.ok) {
        const error = new Error(response?.error?.message || "采集请求失败");
        error.details = response?.error || {};
        throw error;
      }
      authenticated = true;
      const serverMarkedIncomplete = response.data?.completeness === "NEEDS_ENRICHMENT"
        || response.data?.skuCompleteness?.complete === false
        || response.data?.missingFields?.includes?.("SKU_INCOMPLETE");
      if (payload.skuCompleteness?.complete === false || serverMarkedIncomplete) {
        setUrlState(
          control.productUrl,
          "incomplete",
          skuIncompleteMessage(response.data?.skuCompleteness || payload.skuCompleteness, response.data?.message)
        );
      } else {
        setUrlState(control.productUrl, "success", response.data?.message || "商品已提交到平台");
      }
    } catch (error) {
      const details = error?.details || {};
      if (isIdempotentDuplicateConflict(details)) {
        if (payload?.skuCompleteness?.complete === false) {
          setUrlState(control.productUrl, "incomplete", "现有草稿仍缺完整 SKU；请确认规格已加载后再重试");
        } else {
          setUrlState(control.productUrl, "success", "该完整商品已经提交过");
        }
      } else if (details.kind === "AUTH" || details.status === 401) {
        authenticated = false;
        setUrlState(control.productUrl, "auth", details.message || "请先在插件中登录并配对");
      } else if (details.kind === "PERMISSION" || details.status === 403) {
        setUrlState(control.productUrl, "permission", details.message || "当前账号没有商品采集权限");
      } else {
        setUrlState(control.productUrl, "error", error?.message || "采集失败，请重试");
      }
    }
  }

  function isIdempotentDuplicateConflict(details) {
    if (details?.status !== 409) return false;
    const code = String(details?.code || "").trim().toUpperCase();
    return [
      "IDEMPOTENT_REPLAY",
      "IDEMPOTENCY_REPLAY",
      "IDEMPOTENT_DUPLICATE",
      "IDEMPOTENCY_DUPLICATE"
    ].includes(code);
  }

  async function readAuthState() {
    try {
      const response = await chrome.runtime.sendMessage({ type: MESSAGE_GET_STATE, refresh: false });
      authenticated = Boolean(response?.ok && response.data?.authenticated);
      for (const controls of controlsByUrl.values()) {
        for (const control of controls) control.render();
      }
    } catch {
      authenticated = false;
    }
  }

  async function initialize() {
    await readAuthState();
    if (!authenticated) return;
    await globalThis.HeyiCaptureBridge?.activate?.().catch(() => false);
    await resumePendingCapture();
  }

  async function ensureAuthenticated() {
    try {
      const response = await chrome.runtime.sendMessage({ type: MESSAGE_GET_STATE, refresh: false });
      authenticated = Boolean(response?.ok && response.data?.authenticated);
      if (authenticated) await globalThis.HeyiCaptureBridge?.activate?.().catch(() => false);
      return authenticated;
    } catch {
      authenticated = false;
      return false;
    }
  }

  async function resumePendingCapture() {
    if (!adapter.isDetailUrl(location.href)) return;
    try {
      const response = await chrome.runtime.sendMessage({ type: MESSAGE_TAKE_PENDING });
      if (!response?.ok || !response.data?.pending) return;
      ensureDetailControl();
      if (detailControl) await runCapture(detailControl, document.body);
    } catch {
      // A pending card capture is a convenience; the detail button remains usable.
    }
  }

  function skuIncompleteMessage(completeness, fallback) {
    const expected = Number(completeness?.expectedCount);
    const captured = Number(completeness?.capturedCount);
    if (Number.isInteger(expected) && expected > captured) {
      return `已保存草稿，但仅取得 ${captured}/${expected} 个真实 SKU；请完成页面验证并确认规格已加载后重试`;
    }
    return fallback
      ? `${fallback}；SKU 数据未能验证完整，请在平台审核`
      : "已保存草稿，但 SKU 数据未能验证完整；请完成页面验证并确认规格已加载后重试";
  }

  function registerControl(control) {
    const controls = controlsByUrl.get(control.productUrl) || new Set();
    controls.add(control);
    controlsByUrl.set(control.productUrl, controls);
  }

  function unregisterControl(control) {
    const controls = controlsByUrl.get(control.productUrl);
    if (!controls) return;
    controls.delete(control);
    if (controls.size === 0) controlsByUrl.delete(control.productUrl);
  }

  function cleanupControlRegistry() {
    for (const [url, controls] of controlsByUrl) {
      for (const control of controls) {
        if (!control.host.isConnected) controls.delete(control);
      }
      if (controls.size === 0) controlsByUrl.delete(url);
    }
  }

  function clearCaptureControls() {
    const controls = [...controlsByUrl.values()].flatMap(values => [...values]);
    for (const control of controls) control.remove();
    detailControl = null;
    captureState.clear();
  }

  function ensureBlockedNotice(message) {
    if (blockedNotice?.isConnected) return;
    const host = document.createElement("span");
    host.dataset.heyiCollectorHost = "blocked";
    host.style.cssText = "all:initial;position:fixed;right:18px;top:44%;z-index:2147483646;pointer-events:none;";
    const shadow = host.attachShadow({ mode: "closed" });
    const notice = document.createElement("div");
    notice.setAttribute("role", "status");
    notice.textContent = message;
    notice.style.cssText = [
      "box-sizing:border-box", "max-width:220px", "padding:11px 14px", "border:1px solid rgba(255,255,255,.55)",
      "border-radius:12px", "color:#fff", "background:#555e73", "box-shadow:0 8px 24px rgba(35,43,68,.28)",
      "font:600 13px/1.45 -apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif"
    ].join(";");
    shadow.appendChild(notice);
    document.body.appendChild(host);
    blockedNotice = host;
  }

  function setUrlState(url, state, message = "") {
    captureState.set(url, state);
    const controls = controlsByUrl.get(url) || [];
    for (const control of controls) control.render(state, message);
  }

  function nearViewport(element) {
    const rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 && rect.bottom >= -900 && rect.top <= innerHeight + 1_800;
  }
})();
