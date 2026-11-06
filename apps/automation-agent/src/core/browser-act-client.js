const { spawn } = require("node:child_process");
const path = require("node:path");

const SAFE_ID = /^[A-Za-z0-9._-]{1,120}$/;
const SAFE_SESSION = /^[A-Za-z0-9_-]{1,80}$/;

function parseJsonOutput(output) {
  const lines = String(output || "").trim().split(/\r?\n/).filter(Boolean);
  for (let index = lines.length - 1; index >= 0; index -= 1) {
    try { return JSON.parse(lines[index]); } catch {}
  }
  throw new Error("Browser Act 没有返回可识别的结果");
}

function googleEmails(text) {
  return [...new Set(
    (String(text || "").match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi) || [])
      .map(value => value.toLowerCase())
  )];
}

function activeGoogleEmail(text) {
  const source = String(text || "");
  const accountLabel = source.match(
    /aria-label=Google (?:账号|帐户|Account)[：:]\s*[\s\S]{0,240}?\(([A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,})\)/i
  );
  if (accountLabel?.[1]) return accountLabel[1].toLowerCase();
  const observed = googleEmails(source);
  return observed.length === 1 ? observed[0] : "";
}

class BrowserActClient {
  constructor(options = {}) {
    this.executable = options.executable || process.env.BROWSER_ACT_BIN || "browser-act";
    this.timeoutMs = options.timeoutMs || 45000;
    this.openSessions = new Map();
  }

  async probe() {
    try {
      const [versionResult, browserResult, sessionResult] = await Promise.all([
        this.run(["-v"], 10000, false),
        this.run(["--format", "json", "browser", "list"], 20000, false),
        this.run(["--format", "json", "session", "list"], 20000, false)
      ]);
      const data = parseJsonOutput(browserResult.stdout);
      const sessionData = parseJsonOutput(sessionResult.stdout);
      const browsers = Array.isArray(data.browsers) ? data.browsers.map(browser => ({
        id: String(browser.id || ""),
        name: String(browser.name || browser.id || "未命名浏览器"),
        type: String(browser.type || "unknown"),
        source: String(browser.source || "unknown"),
        state: String(browser.state || "idle"),
        proxyType: browser.proxy_type || null,
        proxyConfigured: Boolean(
          browser.proxy_type || browser.dynamic_proxy
          || browser.custom_proxy || browser.static_proxy_id
        )
      })) : [];
      const localProfiles = [];
      const sessions = Array.isArray(sessionData.sessions)
        ? sessionData.sessions.map(session => ({
            sessionId: String(session.session_name || ""),
            browserId: String(session.browser_id || ""),
            browserType: String(session.browser_type || "unknown"),
            headed: session.browser_headed !== false,
            url: String(session.url || ""),
            title: String(session.title || ""),
            createdAtMs: Number(session.created_at_ms || 0),
            updatedAtMs: Number(session.updated_at_ms || 0)
          })).filter(session => session.sessionId && session.browserId)
        : [];
      return {
        state: "online",
        label: "Browser Act 可用",
        version: String(versionResult.stdout || "").trim().replace(/^browser-act\s*/i, ""),
        browserCount: browsers.length,
        browsers,
        localProfiles,
        sessions
      };
    } catch (error) {
      return {
        state: "offline",
        label: "Browser Act 未就绪",
        version: null,
        browserCount: 0,
        browsers: [],
        localProfiles: [],
        sessions: [],
        detail: String(error.message || "未找到 Browser Act 命令").slice(0, 300)
      };
    }
  }

  async startWebAuthorization() {
    const data = parseJsonOutput((await this.run([
      "--format", "json", "auth", "login"
    ], 30000)).stdout);
    const url = String(data.quick_url || "").trim();
    let parsed;
    try {
      parsed = new URL(url);
    } catch {
      throw new Error("BrowserAct 没有返回有效的网页授权地址");
    }
    if (!["http:", "https:"].includes(parsed.protocol)) {
      throw new Error("BrowserAct 返回了不安全的网页授权地址");
    }
    return {
      ok: data.ok !== false,
      url: parsed.toString(),
      remainingMinutes: Number(data.remaining_minutes || 0) || null
    };
  }

  async pollWebAuthorization() {
    const data = parseJsonOutput((await this.run([
      "--format", "json", "auth", "poll"
    ], 30000)).stdout);
    return {
      ok: data.ok !== false,
      status: String(data.status || "pending").trim().toLowerCase(),
      message: String(data.message || "").trim().slice(0, 300),
      remainingMinutes: Number(data.remaining_minutes || 0) || null,
      warning: String(data.warning || "").trim().slice(0, 300)
    };
  }

  async ensureSession(runtime = {}, startUrl = null) {
    const browserId = this.safeId(runtime.browserId, "浏览器实例 ID");
    const session = this.safeSession(runtime.sessionId || `heyi_${Date.now()}`);
    if (this.openSessions.get(session) !== browserId) {
      const args = ["--session", session, "--format", "json", "browser", "open", browserId];
      if (startUrl) args.push(this.validateUrl(startUrl, runtime.allowedDomains));
      if (runtime.headed !== false) args.push("--headed");
      await this.run(args, 90000);
      this.openSessions.set(session, browserId);
    } else if (startUrl) {
      await this.runSession(session, ["navigate", this.validateUrl(startUrl, runtime.allowedDomains)]);
    }
    return session;
  }

  adoptSession(sessionId, browserId) {
    const session = this.safeSession(sessionId);
    this.openSessions.set(session, this.safeId(browserId, "浏览器实例 ID"));
    return session;
  }

  async navigateSession(sessionId, url, allowedDomains = []) {
    const session = this.safeSession(sessionId);
    return this.runSessionJson(session, ["navigate", this.validateUrl(url, allowedDomains)], 45000);
  }

  async verifyGoogleAccount(sessionId, expectedEmail, options = {}) {
    const session = this.safeSession(sessionId);
    const expected = String(expectedEmail || "").trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(expected)) {
      throw new Error("Google 登录验证需要有效邮箱");
    }
    const waitTimeoutMs = Math.max(1000, Math.min(10000, Number(options.waitTimeoutMs) || 10000));
    await this.runSession(session, ["wait", "stable", "--timeout", String(waitTimeoutMs)], waitTimeoutMs + 5000)
      .catch(() => {});
    const state = await this.state(session);
    const text = String(state.text || "");
    const observedEmails = googleEmails(text);
    const activeEmail = activeGoogleEmail(text);
    let hostname = "";
    try { hostname = new URL(String(state.url || "")).hostname.toLowerCase(); } catch {}
    const signedInSurface = hostname === "myaccount.google.com"
      || /(manage your google account|sign out|退出|google (账号|帐户)[：:])/i.test(text);
    const loginSurface = hostname === "accounts.google.com"
      || /(使用 google 账号登录|sign in with google|登录 google 账号)/i.test(text);
    const ok = activeEmail === expected && signedInSurface && !loginSurface;
    return {
      ok,
      expectedEmail: expected,
      activeEmail,
      observedEmails,
      url: String(state.url || ""),
      title: String(state.title || ""),
      checkedAt: new Date().toISOString(),
      reason: ok
        ? null
        : activeEmail
          ? `当前主账号是 ${activeEmail}，不是 ${expected}`
          : observedEmails.length
            ? `页面包含多个 Google 账号，无法确认当前主账号：${observedEmails.join("、")}`
          : "未检测到有效的 Google 登录状态"
    };
  }

  // Periodic checks must never navigate away from the user's working page.
  // A successful state read proves the existing BrowserAct session is alive;
  // only an actual Google sign-in surface is treated as an expired login.
  async checkGoogleSessionHealth(sessionId) {
    const session = this.safeSession(sessionId);
    const state = await this.state(session);
    const url = String(state.url || "");
    const text = String(state.text || "");
    let hostname = "";
    try { hostname = new URL(url).hostname.toLowerCase(); } catch {}
    const authRequired = hostname === "accounts.google.com"
      || /(?:sign in with google|sign in to continue|choose an account|use your google account)/i.test(text);
    return {
      ok: !authRequired,
      alive: true,
      authRequired,
      url,
      title: String(state.title || ""),
      checkedAt: new Date().toISOString(),
      reason: authRequired ? "Google login has expired" : null
    };
  }

  async detectGoogleAccount(sessionId) {
    const session = this.safeSession(sessionId);
    await this.runSession(session, ["wait", "stable", "--timeout", "10000"], 15000)
      .catch(() => {});
    const state = await this.state(session);
    const text = String(state.text || "");
    const observedEmails = googleEmails(text);
    const activeEmail = activeGoogleEmail(text);
    let hostname = "";
    try { hostname = new URL(String(state.url || "")).hostname.toLowerCase(); } catch {}
    const signedInSurface = hostname === "myaccount.google.com"
      || /(manage your google account|sign out|退出|google (账号|帐户)[：:])/i.test(text);
    const loginSurface = hostname === "accounts.google.com"
      || /(使用 google 账号登录|sign in with google|登录 google 账号)/i.test(text);
    const email = signedInSurface && !loginSurface ? activeEmail : "";
    return {
      ok: Boolean(email),
      email,
      observedEmails,
      url: String(state.url || ""),
      title: String(state.title || ""),
      checkedAt: new Date().toISOString(),
      reason: email ? null : "尚未检测到已登录的 Google 邮箱"
    };
  }

  async importProfile(browserId, profileId) {
    const target = this.safeId(browserId, "浏览器实例 ID");
    const profile = this.safeId(profileId, "本机 Profile ID");
    let result;
    try {
      result = await this.run([
        "--format", "json", "browser", "import-profile", target, profile
      ], 120000);
    } catch (error) {
      const message = String(error?.message || error || "");
      let detail;
      try { detail = JSON.parse(message); } catch {}
      if (detail?.error_code === 230205 || detail?.error_name === "REGISTRY_BROWSER_RUNNING") {
        const activeMatch = String(detail.error || "").match(/Detected active profiles:\s*([^\.\r\n]+)/i);
        const activeProfiles = activeMatch?.[1]
          ? `（${activeMatch[1].split(",").map(item => item.trim()).filter(Boolean).join("、")}）`
          : "";
        throw new Error(`普通 Chrome 仍有账号窗口在运行${activeProfiles}。请关闭所有普通 Chrome 窗口后重试导入。`);
      }
      throw error;
    }
    try { return parseJsonOutput(result.stdout); } catch {
      return { ok: true, message: String(result.stdout || "Import successful").trim() };
    }
  }

  async listProfiles() {
    const result = await this.run([
      "--format", "json", "browser", "list-profiles"
    ], 60000);
    const data = parseJsonOutput(result.stdout);
    const localProfiles = Array.isArray(data.local_profiles) ? data.local_profiles : [];
    return localProfiles.map(profile => ({
      id: String(profile.profile_id || ""),
      name: String(profile.profile_name || "未命名 Chrome Profile"),
      accountEmail: String(profile.account_email || "").trim().toLowerCase(),
      browserName: String(profile.browser_name || "Chrome"),
      sourceType: String(profile.source_type || "local"),
      path: String(profile.path || "")
    })).filter(profile => profile.id && profile.accountEmail);
  }

  async createChromeBrowser(name, desc) {
    const safeName = String(name || "").trim().slice(0, 100);
    const safeDesc = String(desc || "").trim().slice(0, 500);
    if (!safeName) throw new Error("BrowserAct 实例名称不能为空");
    if (!safeDesc) throw new Error("BrowserAct 实例用途不能为空");
    const result = await this.run([
      "--format", "json", "browser", "create",
      "--name", safeName, "--type", "chrome", "--desc", safeDesc
    ], 120000);
    const data = parseJsonOutput(result.stdout);
    const browserId = data.browser_id || data.browserId || data.id
      || data.browser?.id || data.data?.browser_id || data.data?.browser?.id;
    if (browserId && SAFE_ID.test(String(browserId))) {
      return { ...data, browserId: String(browserId), name: safeName };
    }
    const probe = await this.probe();
    const browser = probe.browsers.find(item => item.name === safeName);
    if (!browser) throw new Error("BrowserAct 已执行创建，但没有返回新实例 ID");
    return { ...data, browserId: browser.id, name: safeName };
  }

  async deleteBrowser(browserId, objective) {
    const target = this.safeId(browserId, "BrowserAct 实例 ID");
    const purpose = String(objective || "删除不再使用的账号环境").trim().slice(0, 500);
    if (!purpose) throw new Error("删除 BrowserAct 实例需要明确用途");
    const result = await this.run([
      "--format", "json", "browser", "delete", target,
      "--objective", purpose
    ], 120000);
    try {
      return { ...parseJsonOutput(result.stdout), browserId: target };
    } catch {
      return { ok: true, browserId: target };
    }
  }

  async remoteAssist(sessionId, objective) {
    const session = this.safeSession(sessionId);
    const result = await this.runSessionJson(session, [
      "remote-assist", "--objective", String(objective || "完成 Google 账号登录").slice(0, 500)
    ], 60000);
    const url = result.remote_url || result.remoteUrl || result.assist_url
      || result.assistUrl || result.url || result.link || "";
    return { ...result, url: String(url || "") };
  }

  async executeBrowserAction(action = {}, runtime = {}) {
    const kind = String(action.kind || action.action || action.type || "observe").trim().toLowerCase();
    const session = await this.ensureSession(runtime, kind === "navigate" ? action.url : null);
    if (kind === "navigate") return this.state(session);
    if (["observe", "state"].includes(kind)) {
      const state = await this.state(session);
      if (action.includeScreenshot === true) {
        return { state, screenshot: await this.runSessionJson(session, ["screenshot"]) };
      }
      return state;
    }
    if (kind === "wait") {
      const timeout = Math.max(1000, Math.min(
        30000, Number(action.timeoutMs || Number(action.seconds || 0) * 1000 || 10000)
      ));
      const result = await this.runSessionJson(session, ["wait", "stable", "--timeout", String(timeout)]);
      return { ...result, state: await this.state(session) };
    }
    if (kind === "click") {
      const index = this.safeIndex(action.index ?? action.target ?? action.elementRef);
      if (action.sensitive === true && action.approved !== true) {
        throw new Error("该点击涉及提交、付款、发布或其他敏感动作，需要任务启动时明确授权");
      }
      const result = await this.runSessionJson(session, ["click", String(index)]);
      return { ...result, state: await this.state(session) };
    }
    if (["input", "type"].includes(kind)) {
      const index = this.safeIndex(action.index ?? action.target ?? action.elementRef);
      if ((action.secret === true || action.sensitive === true) && action.approved !== true) {
        throw new Error("敏感信息输入需要任务启动时明确授权");
      }
      const text = String(action.text ?? action.value ?? "").slice(0, 10000);
      const result = await this.runSessionJson(session, ["input", String(index), text, "--mode", "fill"]);
      return { ...result, state: await this.state(session) };
    }
    if (kind === "select") {
      const result = await this.runSessionJson(session, [
        "select", String(this.safeIndex(action.index ?? action.target ?? action.elementRef)),
        String(action.option ?? action.value ?? "").slice(0, 1000)
      ]);
      return { ...result, state: await this.state(session) };
    }
    if (kind === "hover") {
      const result = await this.runSessionJson(session, [
        "hover", String(this.safeIndex(action.index ?? action.target ?? action.elementRef))
      ]);
      return { ...result, state: await this.state(session) };
    }
    if (kind === "keys") {
      const keys = String(action.keys || action.value || "").trim().slice(0, 200);
      if (!keys) throw new Error("键盘按键不能为空");
      const result = await this.runSessionJson(session, ["keys", keys]);
      return { ...result, state: await this.state(session) };
    }
    if (kind === "scroll") {
      const direction = String(action.direction || "down").toLowerCase() === "up" ? "up" : "down";
      const amount = Math.max(100, Math.min(5000, Number(action.amount || 700)));
      const result = await this.runSessionJson(session, ["scroll", direction, "--amount", String(amount)]);
      return { ...result, state: await this.state(session) };
    }
    if (["back", "forward", "reload"].includes(kind)) {
      const result = await this.runSessionJson(session, [kind]);
      return { ...result, state: await this.state(session) };
    }
    if (kind === "upload") {
      if (action.approved !== true) throw new Error("上传本机文件需要任务启动时明确授权");
      const filePath = path.resolve(String(action.path || ""));
      return this.runSessionJson(session, [
        "upload", String(this.safeIndex(action.index ?? action.target ?? action.elementRef)), filePath
      ]);
    }
    if (kind === "screenshot") {
      const target = action.path ? path.resolve(String(action.path)) : "";
      const args = ["screenshot"];
      if (target) args.push(target);
      if (action.full === true) args.push("--full");
      const result = await this.runSessionJson(session, args);
      return { ...result, state: await this.state(session) };
    }
    if (kind === "get_title") return this.runSessionJson(session, ["get", "title"]);
    if (kind === "get_markdown") return this.runSessionJson(session, ["get", "markdown"]);
    if (kind === "get_html") return this.runSessionJson(session, ["get", "html"]);
    if (["get_text", "get_value"].includes(kind)) {
      return this.runSessionJson(session, [
        "get", kind === "get_text" ? "text" : "value",
        String(this.safeIndex(action.index ?? action.target ?? action.elementRef))
      ]);
    }
    if (kind === "network_requests") {
      const args = ["network", "requests"];
      const filter = String(action.filter || "").trim().slice(0, 500);
      const resourceTypes = String(action.resourceTypes || action.types || "").trim().slice(0, 200);
      const method = String(action.method || "").trim().toUpperCase().slice(0, 20);
      const status = String(action.status || "").trim().slice(0, 20);
      if (filter) args.push("--filter", filter);
      if (resourceTypes) args.push("--type", resourceTypes);
      if (method) args.push("--method", method);
      if (status) args.push("--status", status);
      return this.runSessionJson(session, args);
    }
    if (kind === "network_request") {
      const requestId = String(action.requestId || action.id || "").trim();
      if (!/^[A-Za-z0-9._:-]{1,200}$/.test(requestId)) {
        throw new Error("网络请求编号格式不正确");
      }
      return this.runSessionJson(session, ["network", "request", requestId]);
    }
    throw new Error(`Browser Act 动作不在允许范围内：${kind}`);
  }

  async state(session) {
    return this.runSessionJson(session, ["state"]);
  }

  async closeSession(sessionId) {
    const session = this.safeSession(sessionId);
    try {
      return parseJsonOutput((await this.run([
        "--format", "json", "session", "close", session
      ], 20000)).stdout);
    } finally {
      this.openSessions.delete(session);
    }
  }

  validateRuntime(runtime = {}, availableBrowsers = []) {
    const browserId = this.safeId(runtime.browserId, "浏览器实例 ID");
    const browser = availableBrowsers.find(item => item.id === browserId);
    if (!browser) throw new Error(`没有找到管理员配置的 Browser Act 浏览器：${browserId}`);
    const policy = String(runtime.proxyPolicy || "DIRECT").toUpperCase();
    if (policy === "PRECONFIGURED" && !browser.proxyConfigured) {
      throw new Error("当前任务要求国家线路，但所选浏览器实例还没有预配置代理");
    }
    if (policy === "PRECONFIGURED" && runtime.requiredRegion
        && String(runtime.configuredRegion || "").toUpperCase()
          !== String(runtime.requiredRegion).toUpperCase()) {
      throw new Error(
        `当前浏览器实例登记线路为 ${runtime.configuredRegion || "未设置"}，任务要求 ${runtime.requiredRegion}`
      );
    }
    if (policy !== "DIRECT" && policy !== "PRECONFIGURED") {
      throw new Error("不支持的浏览器代理策略");
    }
    return browser;
  }

  validateUrl(value, allowedDomains = []) {
    const parsed = new URL(String(value || ""));
    if (!["http:", "https:"].includes(parsed.protocol)) throw new Error("只允许访问 HTTP/HTTPS 页面");
    const domains = Array.isArray(allowedDomains)
      ? allowedDomains.map(item => String(item).trim().toLowerCase()).filter(Boolean)
      : [];
    if (domains.length && !domains.some(domain =>
      parsed.hostname.toLowerCase() === domain
      || parsed.hostname.toLowerCase().endsWith(`.${domain}`)
    )) throw new Error(`当前 Skill 无权访问域名：${parsed.hostname}`);
    return parsed.toString();
  }

  safeId(value, label = "标识") {
    const result = String(value || "").trim();
    if (!SAFE_ID.test(result)) throw new Error(`${label}格式不正确`);
    return result;
  }

  safeSession(value) {
    const result = String(value || "").trim();
    if (!SAFE_SESSION.test(result)) throw new Error("浏览器会话标识格式不正确");
    return result;
  }

  safeIndex(value) {
    const raw = String(value ?? "").trim();
    const result = /^\d+$/.test(raw)
      ? Number(raw)
      : Number(raw.match(/(\d+)$/)?.[1]);
    if (!Number.isInteger(result) || result < 0 || result > 100000) {
      throw new Error("页面元素编号无效，请重新观察页面后再操作");
    }
    return result;
  }

  async runSession(session, args, timeoutMs) {
    return this.run(["--session", this.safeSession(session), "--format", "json", ...args], timeoutMs);
  }

  async runSessionJson(session, args, timeoutMs) {
    return parseJsonOutput((await this.runSession(session, args, timeoutMs)).stdout);
  }

  run(args, timeoutMs = this.timeoutMs, requireSuccess = true) {
    return new Promise((resolve, reject) => {
      const child = spawn(this.executable, args, {
        windowsHide: true,
        shell: false,
        stdio: ["ignore", "pipe", "pipe"]
      });
      let stdout = "";
      let stderr = "";
      const timer = setTimeout(() => child.kill(), timeoutMs);
      child.stdout.on("data", chunk => { stdout = (stdout + chunk.toString("utf8")).slice(-500000); });
      child.stderr.on("data", chunk => { stderr = (stderr + chunk.toString("utf8")).slice(-20000); });
      child.on("error", error => {
        clearTimeout(timer);
        reject(new Error(`无法启动 Browser Act：${error.message}`));
      });
      child.on("close", code => {
        clearTimeout(timer);
        if (code !== 0 && requireSuccess) {
          reject(new Error((stderr || stdout || `Browser Act 执行失败（${code}）`).trim().slice(-3000)));
          return;
        }
        resolve({ code, stdout, stderr });
      });
    });
  }
}

module.exports = { BrowserActClient, parseJsonOutput };
