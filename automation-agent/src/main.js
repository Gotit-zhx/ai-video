const { app, BrowserWindow, dialog, ipcMain, Menu, nativeImage, shell, Tray } = require("electron");
const fs = require("node:fs/promises");
const http = require("node:http");
const path = require("node:path");
const crypto = require("node:crypto");
const { spawn } = require("node:child_process");
const { FeikuaClient } = require("./core/feikua-client");
const { BrowserActClient } = require("./core/browser-act-client");
const { AccountPoolManager, normalizePoolConfig } = require("./core/account-pool-manager");
const { runLocalSkill } = require("./core/local-skill-runner");
const { compressTaskOutput } = require("./core/artifact-archiver");
const { heartbeatDelay } = require("./core/heartbeat-cadence");

const DEFAULT_CONFIG = {
  platformUrl: "http://localhost:8080",
  agentName: "",
  runMode: "unpaired",
  agentId: "",
  organizationCode: "heyi",
  acceptJobs: true,
  browserAct: {
    enabled: true,
    executorPreference: "AUTO",
    browserId: "",
    proxyPolicy: "DIRECT",
    requiredRegion: "",
    accountPool: {
      enabled: true,
      autoStart: false,
      poolSize: 5,
      startUrl: "https://gemini.google.com/app",
      healthCheckUrl: "https://myaccount.google.com/",
      accounts: []
    }
  }
};

let mainWindow;
let tray;
let quitting = false;
let poolShutdownStarted = false;
let pickerServer;
let accountMutationQueue = Promise.resolve();
const accountSyncStates = new Map();
let browserActSessionPolling = false;
let lastBrowserActSessionSignature = "";
let heartbeatTimer = null;
let heartbeatInFlight = false;
const pendingAccountAdds = new Map();
const pendingProfileImports = new Map();
let localProfileCache = { checkedAt: 0, profiles: [], pending: null };
const feikuaClient = new FeikuaClient();
const browserActClient = new BrowserActClient();
const accountPoolManager = new AccountPoolManager(browserActClient, { onEvent: addRuntimeEvent });
const AGENT_TOKEN = process.env.HEYI_AGENT_TOKEN || "heyi-local-agent-dev-token";
const BROWSER_ACT_WEBSITE = "https://www.browseract.com/";
const GOOGLE_ACCOUNT_URL = "https://myaccount.google.com/";
const GEMINI_URL = "https://gemini.google.com/app";
const runtimeState = {
  tasks: [],
  events: [],
  lastHeartbeatAt: 0,
  polling: false,
  browserAct: null
};

function enqueueAccountMutation(task) {
  const running = accountMutationQueue.then(task, task);
  accountMutationQueue = running.catch(() => {});
  return running;
}

function configPath() {
  return path.join(app.getPath("userData"), "agent-config.json");
}

function logDirectory() {
  return path.join(app.getPath("userData"), "logs");
}

async function readConfig() {
  try {
    const content = await fs.readFile(configPath(), "utf8");
    return { ...DEFAULT_CONFIG, ...JSON.parse(content) };
  } catch {
    return { ...DEFAULT_CONFIG };
  }
}

async function writeConfig(next) {
  const browserAct = {
    ...DEFAULT_CONFIG.browserAct,
    ...(next.browserAct || {}),
    enabled: next.browserAct?.enabled !== false,
    executorPreference: ["AUTO", "FEIKUA", "BROWSER_ACT"].includes(
      String(next.browserAct?.executorPreference || "AUTO").toUpperCase()
    ) ? String(next.browserAct?.executorPreference || "AUTO").toUpperCase() : "AUTO",
    browserId: String(next.browserAct?.browserId || "").trim().slice(0, 120),
    proxyPolicy: String(next.browserAct?.proxyPolicy || "DIRECT").toUpperCase() === "PRECONFIGURED"
      ? "PRECONFIGURED" : "DIRECT",
    requiredRegion: String(next.browserAct?.requiredRegion || "").trim().toUpperCase().slice(0, 20),
    accountPool: normalizePoolConfig(next.browserAct?.accountPool)
  };
  const safeConfig = {
    ...DEFAULT_CONFIG,
    ...next,
    platformUrl: normalizePlatformUrl(next.platformUrl),
    agentName: String(next.agentName || "").trim().slice(0, 60),
    browserAct
  };
  await fs.mkdir(path.dirname(configPath()), { recursive: true });
  await fs.writeFile(configPath(), JSON.stringify(safeConfig, null, 2), "utf8");
  return safeConfig;
}

async function syncChromeAccountsIntoConfig(config, profiles = []) {
  const pool = normalizePoolConfig(config.browserAct?.accountPool);
  const accounts = pool.accounts.map(account => ({ ...account }));
  let changed = false;
  for (const profile of profiles) {
    const profileId = String(profile.id || "").trim();
    const email = String(profile.accountEmail || "").trim().toLowerCase();
    if (!profileId || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) continue;
    const label = String(profile.name || email).trim().slice(0, 120);
    const existing = accounts.find(account =>
      account.profileId === profileId || (!account.profileId && account.email === email)
    );
    if (existing) {
      if (existing.profileId !== profileId || existing.email !== email || (!existing.label && label)) {
        existing.profileId = profileId;
        existing.email = email;
        if (!existing.label) existing.label = label;
        changed = true;
      }
      continue;
    }
    const idBase = `chrome-${profileId}`.replace(/[^A-Za-z0-9._-]/g, "-").slice(0, 72);
    let id = idBase;
    let suffix = 2;
    while (accounts.some(account => account.id === id)) id = `${idBase.slice(0, 68)}-${suffix++}`;
    accounts.push({
      id,
      label,
      email,
      browserId: "",
      profileId,
      aliases: [],
      enabled: true
    });
    changed = true;
    addRuntimeEvent(`已自动添加 Chrome 账号：${email}`, "success");
  }
  if (!changed) return config;
  return writeConfig({
    ...config,
    browserAct: {
      ...config.browserAct,
      accountPool: { ...pool, accounts }
    }
  });
}

async function migrateToBrowserActAccounts(config) {
  const pool = normalizePoolConfig(config.browserAct?.accountPool);
  const accounts = pool.accounts
    .filter(account => account.email && account.browserId);
  const startUrl = pool.startUrl === pool.healthCheckUrl ? GEMINI_URL : pool.startUrl;
  const changed = accounts.length !== pool.accounts.length || startUrl !== pool.startUrl;
  if (!changed) return config;
  return writeConfig({
    ...config,
    browserAct: {
      ...config.browserAct,
      accountPool: { ...pool, startUrl, accounts }
    }
  });
}

async function bindLocalProfileMetadata(config, profiles = []) {
  const pool = normalizePoolConfig(config.browserAct?.accountPool);
  let changed = false;
  const accounts = pool.accounts.map(account => {
    const profile = profiles.find(item =>
      (account.profileId && item.id === account.profileId && item.accountEmail === account.email)
      || item.accountEmail === account.email
    );
    if (!profile) return account;
    const next = {
      ...account,
      profileId: profile.id,
      profileName: profile.name,
      profileDirectory: path.basename(profile.path || ""),
      sourceCookieUpdatedAtMs: Math.max(0, Number(profile.cookieUpdatedAtMs) || 0)
    };
    if (
      next.profileId !== account.profileId
      || next.profileName !== account.profileName
      || next.profileDirectory !== account.profileDirectory
      || next.sourceCookieUpdatedAtMs !== account.sourceCookieUpdatedAtMs
    ) changed = true;
    return next;
  });
  if (!changed) return config;
  return writeConfig({
    ...config,
    browserAct: {
      ...config.browserAct,
      accountPool: { ...pool, accounts }
    }
  });
}

async function prepareAccountPoolConfig(config, forceProfiles = true) {
  const profiles = await scanLocalGoogleProfiles(forceProfiles).catch(() => []);
  const next = await bindLocalProfileMetadata(config, profiles);
  accountPoolManager.syncLocalProfiles(next.browserAct?.accountPool, profiles);
  return { config: next, profiles };
}

async function saveVerifiedAccountBaselines(config, profiles = [], poolSnapshot = {}) {
  const verifiedIds = new Set(
    (poolSnapshot.active || [])
      .filter(session => session.status !== "CONTROLLED")
      .map(session => session.accountId)
  );
  if (!verifiedIds.size) return config;
  const pool = normalizePoolConfig(config.browserAct?.accountPool);
  let changed = false;
  const accounts = pool.accounts.map(account => {
    if (!verifiedIds.has(account.id) || account.lastImportedCookieUpdatedAtMs > 0) return account;
    const profile = profiles.find(item =>
      (account.profileId && item.id === account.profileId && item.accountEmail === account.email)
      || item.accountEmail === account.email
    );
    if (!profile) return account;
    const session = (poolSnapshot.active || []).find(item => item.accountId === account.id);
    changed = true;
    return {
      ...account,
      profileId: profile.id,
      profileName: profile.name,
      profileDirectory: path.basename(profile.path || ""),
      sourceCookieUpdatedAtMs: Number(profile.cookieUpdatedAtMs || 0),
      lastImportedCookieUpdatedAtMs: Number(profile.cookieUpdatedAtMs || 0),
      lastVerifiedAt: session?.verifiedAt || new Date().toISOString()
    };
  });
  if (!changed) return config;
  return writeConfig({
    ...config,
    browserAct: {
      ...config.browserAct,
      accountPool: { ...pool, accounts }
    }
  });
}

function normalizePlatformUrl(value) {
  const input = String(value || "").trim();
  const parsed = new URL(input || DEFAULT_CONFIG.platformUrl);
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new Error("平台地址必须以 http:// 或 https:// 开头");
  }
  return parsed.origin;
}

async function requestJson(url, options = {}, timeoutMs = 4000) {
  const response = await fetch(url, {
    ...options,
    signal: AbortSignal.timeout(timeoutMs),
    headers: {
      Accept: "application/json",
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(options.headers || {})
    }
  });
  const body = await response.text();
  let data;
  try {
    data = body ? JSON.parse(body) : {};
  } catch {
    data = { message: body };
  }
  if (!response.ok) {
    throw new Error(data.message || `请求失败（${response.status}）`);
  }
  return data;
}

async function agentRequest(config, pathname, options = {}, timeoutMs = 8000) {
  return requestJson(`${normalizePlatformUrl(config.platformUrl)}${pathname}`, {
    ...options,
    headers: {
      "X-Agent-Token": AGENT_TOKEN,
      ...(options.headers || {})
    }
  }, timeoutMs);
}

function safeTaskFileName(value) {
  return String(value || "task-file")
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_")
    .slice(0, 180) || "task-file";
}

async function prepareTaskWorkspace(config, command) {
  const workspace = path.join(app.getPath("userData"), "tasks", safeTaskFileName(command.id));
  const inputRoot = path.join(workspace, "input");
  const outputRoot = path.join(workspace, "output");
  await fs.mkdir(inputRoot, { recursive: true });
  await fs.mkdir(outputRoot, { recursive: true });
  const parameters = { ...(command.input?.parameters || {}) };
  for (const [name, value] of Object.entries(parameters)) {
    const match = String(value || "").match(/^heyi-attachment:([a-f0-9]{32}):(.+)$/);
    if (!match) continue;
    const response = await fetch(
      `${normalizePlatformUrl(config.platformUrl)}/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/attachments/${match[1]}`,
      {
        headers: { "X-Agent-Token": AGENT_TOKEN },
        signal: AbortSignal.timeout(120000)
      }
    );
    if (!response.ok) {
      throw new Error(`任务输入文件下载失败（${response.status}）`);
    }
    const target = path.join(inputRoot, `${name}-${safeTaskFileName(match[2])}`);
    await fs.writeFile(target, Buffer.from(await response.arrayBuffer()));
    parameters[name] = target;
  }
  parameters.outputRoot = outputRoot;
  command.input = { ...(command.input || {}), parameters };
  return { workspace, outputRoot };
}

async function uploadTaskArtifact(config, command, outputRoot, workspace) {
  const archivePath = path.join(workspace, `月账单导出-${command.id}.zip`);
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      await compressTaskOutput(outputRoot, archivePath);
      const form = new FormData();
      form.append(
        "file",
        new Blob([await fs.readFile(archivePath)], { type: "application/zip" }),
        path.basename(archivePath)
      );
      const response = await fetch(
        `${normalizePlatformUrl(config.platformUrl)}/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/artifacts`,
        {
          method: "POST",
          headers: { "X-Agent-Token": AGENT_TOKEN },
          body: form,
          signal: AbortSignal.timeout(180000)
        }
      );
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.message || "任务结果上传失败");
      return {
        ...result.data,
        downloadUrl: `/api/v1/automations/attachments/${result.data.id}`
      };
    } catch (error) {
      lastError = error;
      if (attempt < 3) {
        await new Promise(resolve => setTimeout(resolve, attempt * 1500));
      }
    }
  }
  throw lastError || new Error("任务结果打包上传失败");
}

function addRuntimeEvent(message, level = "info") {
  runtimeState.events.unshift({
    time: new Date().toLocaleTimeString("zh-CN", { hour12: false }),
    level,
    message
  });
  runtimeState.events = runtimeState.events.slice(0, 30);
  mainWindow?.webContents.send("agent:runtime-update");
}

async function probePlatform(platformUrl) {
  const startedAt = Date.now();
  try {
    const data = await requestJson(`${normalizePlatformUrl(platformUrl)}/actuator/health`);
    return {
      state: String(data.status).toUpperCase() === "UP" ? "online" : "warning",
      label: String(data.status).toUpperCase() === "UP" ? "平台在线" : "平台需检查",
      latencyMs: Date.now() - startedAt
    };
  } catch (error) {
    return { state: "offline", label: "无法连接平台", detail: error.message };
  }
}

async function probeFeikua() {
  try {
    const stores = await feikuaClient.listStores();
    return {
      state: "online",
      label: "飞跨已连接",
      storeCount: stores.length,
      stores: stores.map((store) => ({
        id: String(store.shop_id || ""),
        name: String(store.shop_name || "未命名店铺"),
        running: Boolean(store.is_running || store.running)
      }))
    };
  } catch (error) {
    return {
      state: "offline",
      label: "飞跨未连接",
      storeCount: 0,
      stores: [],
      detail: "请先启动并登录飞跨客户端"
    };
  }
}

async function probeBrowserAct(config) {
  if (config.browserAct?.enabled === false) {
    return {
      state: "warning", label: "谷歌浏览器已停用", browserCount: 0,
      browsers: [], selectedBrowser: null
    };
  }
  const result = await browserActClient.probe();
  const selectedBrowser = result.browsers?.find(browser =>
    browser.id === config.browserAct?.browserId
  ) || null;
  if (result.state === "online" && config.browserAct?.browserId && !selectedBrowser) {
    return { ...result, state: "warning", label: "浏览器实例需重新选择", selectedBrowser };
  }
  return { ...result, selectedBrowser };
}

function commandBrowserRuntime(command, config) {
  let runtime = command.input?.runtime || {};
  if (typeof runtime === "string") {
    try { runtime = JSON.parse(runtime); } catch { runtime = {}; }
  }
  const configured = config.browserAct || {};
  const preferredEngine = String(configured.executorPreference || "AUTO").toUpperCase();
  return {
    ...runtime,
    browserEngine: runtime.browserEngine
      || (preferredEngine === "AUTO" ? undefined : preferredEngine),
    browserId: runtime.browserId || configured.browserId,
    proxyPolicy: runtime.proxyPolicy || configured.proxyPolicy || "DIRECT",
    requiredRegion: runtime.requiredRegion || configured.requiredRegion || "",
    configuredRegion: configured.requiredRegion || "",
    sessionId: `heyi_${String(command.id || crypto.randomUUID()).replace(/[^A-Za-z0-9_-]/g, "_")}`,
    headed: true
  };
}

async function executeModelBrowserAction(config, command, action) {
  const runtime = commandBrowserRuntime(command, config);
  if (String(runtime.browserEngine || "FEIKUA").toUpperCase() !== "BROWSER_ACT") {
    return feikuaClient.executeBrowserAction(action || {});
  }
  const probe = await browserActClient.probe();
  browserActClient.validateRuntime(runtime, probe.browsers || []);
  return browserActClient.executeBrowserAction(action || {}, runtime);
}

async function runBrowserActPlan(config, command) {
  const runtime = commandBrowserRuntime(command, config);
  const probe = await browserActClient.probe();
  const browser = browserActClient.validateRuntime(runtime, probe.browsers || []);
  const actions = command.input?.parameters?.browserActions;
  if (!Array.isArray(actions) || actions.length === 0) {
    throw new Error("该任务尚未生成谷歌浏览器动作计划");
  }
  if (actions.length > 120) throw new Error("单次浏览器动作超过 120 步安全上限");
  const steps = [];
  try {
    for (let index = 0; index < actions.length; index += 1) {
      const action = actions[index] || {};
      addRuntimeEvent(`谷歌浏览器正在执行第 ${index + 1}/${actions.length} 步`);
      const result = await browserActClient.executeBrowserAction(action, runtime);
      steps.push({
        index: index + 1,
        action: String(action.kind || action.action || "observe"),
        ok: true,
        result
      });
    }
    return {
      browserEngine: "BROWSER_ACT",
      browser: { id: browser.id, name: browser.name, type: browser.type },
      proxy: {
        policy: runtime.proxyPolicy,
        requiredRegion: runtime.requiredRegion || "",
        configured: browser.proxyConfigured
      },
      completedSteps: steps.length,
      steps,
      completedAt: new Date().toISOString()
    };
  } finally {
    if (runtime.keepSession !== true) {
      await browserActClient.closeSession(runtime.sessionId).catch(() => {});
    }
  }
}

function compactBrowserState(value = {}) {
  const state = value?.state && typeof value.state === "object" ? value.state : value;
  const compact = {
    url: String(state?.url || "").slice(0, 2000),
    title: String(state?.title || "").slice(0, 500),
    text: String(state?.text || "").slice(0, 30000)
  };
  if (Array.isArray(state?.elements)) compact.elements = state.elements.slice(0, 300);
  return compact;
}

function pageTaskMode(instruction = "") {
  const text = String(instruction || "").trim();
  const analysisIntent = /(分析|读取|查看.{0,8}(?:信息|内容|状态)|总结|概括|识别.{0,8}(?:信息|内容)|页面信息|有哪些内容)/i.test(text);
  const mutationIntent = /(点击|输入|填写|选择|切换|上传|提交|创建|删除|发布|下载|关闭|登录|跳转|进入.{0,8}(?:页面|栏目|菜单))/i.test(text);
  return analysisIntent && !mutationIntent ? "READ_ONLY_ANALYSIS" : "INTERACTIVE_ACTION";
}

function pageTaskRequiresAction(instruction = "") {
  return /(点击|输入|填写|选择|切换|上传|提交|创建|删除|发布|下载|关闭|登录|跳转|打开|进入|滚动|刷新|后退|前进|按下|搜索)/i
    .test(String(instruction || ""));
}

function browserActionKind(action = {}) {
  return String(action.type || action.action || action.kind || "").trim().toUpperCase();
}

function compactToolOutput(value) {
  if (value == null) return "";
  if (["string", "number", "boolean"].includes(typeof value)) {
    return String(value).slice(0, 8000);
  }
  try {
    const clone = { ...value };
    delete clone.state;
    delete clone.screenshot;
    delete clone.screenshotBase64;
    delete clone.screenshot_base64;
    return JSON.stringify(clone).slice(0, 8000);
  } catch {
    return "";
  }
}

function browserScreenshotPayload(value = {}) {
  const source = value?.screenshot && typeof value.screenshot === "object"
    ? value.screenshot : value;
  let data = String(
    source?.data || source?.base64 || source?.screenshotBase64
    || source?.screenshot_base64 || source?.image || ""
  ).trim();
  let mimeType = String(source?.mimeType || source?.mime_type || "image/png");
  const dataUrl = data.match(/^data:([^;]+);base64,(.+)$/s);
  if (dataUrl) {
    mimeType = dataUrl[1];
    data = dataUrl[2];
  }
  return data ? { screenshot: data, screenshotMimeType: mimeType } : {};
}

function browserStateFingerprint(value = {}) {
  const state = compactBrowserState(value);
  const material = JSON.stringify({
    url: state.url,
    title: state.title,
    text: state.text.slice(0, 20000),
    elements: Array.isArray(state.elements) ? state.elements.slice(0, 300) : []
  });
  return crypto.createHash("sha256").update(material).digest("hex").slice(0, 20);
}

function browserActionTarget(action = {}) {
  return String(
    action.elementRef ?? action.index ?? action.url ?? action.keys
    ?? action.requestId ?? action.filter ?? action.direction ?? ""
  ).slice(0, 300);
}

function normalizeEvidenceText(value) {
  return String(value || "").toLowerCase().replace(/\s+/g, "").trim();
}

function normalizedAnalysisResult(advice = {}, finalState = {}) {
  const value = advice.resultData && typeof advice.resultData === "object"
    ? advice.resultData : {};
  const keyPoints = Array.isArray(value.keyPoints)
    ? value.keyPoints.map(item => String(item || "").trim()).filter(Boolean).slice(0, 20)
    : [];
  const warnings = Array.isArray(value.warnings)
    ? value.warnings.map(item => String(item || "").trim()).filter(Boolean).slice(0, 10)
    : [];
  return {
    overview: String(value.overview || advice.userMessage || "页面信息已读取").trim().slice(0, 3000),
    keyPoints,
    warnings,
    conclusion: String(value.conclusion || advice.reason || advice.userMessage || "页面分析完成").trim().slice(0, 3000),
    url: String(finalState.url || "").slice(0, 2000),
    title: String(finalState.title || "").slice(0, 500)
  };
}

async function runGoogleAccountPageUnit(config, command, session, instruction) {
  const runtime = {
    browserId: session.browserId,
    sessionId: session.sessionId,
    headed: true,
    allowedDomains: []
  };
  browserActClient.adoptSession(session.sessionId, session.browserId);
  const taskMode = pageTaskMode(instruction);
  const requiresAction = pageTaskRequiresAction(instruction);
  let initialObservation;
  try {
    initialObservation = await browserActClient.executeBrowserAction({
      type: "OBSERVE",
      includeScreenshot: true
    }, runtime);
  } catch {
    initialObservation = await browserActClient.state(session.sessionId);
  }
  const initialScreenshot = browserScreenshotPayload(initialObservation);
  let lastScreenshotTurn = initialScreenshot.screenshot ? 0 : -1;
  let toolResult = {
    ok: true,
    action: "BROWSER_OBSERVE",
    result: {
      ...compactBrowserState(initialObservation),
      ...initialScreenshot
    }
  };
  const maxTurns = taskMode === "READ_ONLY_ANALYSIS" ? 2 : 20;
  const actionHistory = [];
  for (let turn = 1; turn <= maxTurns; turn += 1) {
    addRuntimeEvent(`${session.email || session.label}：Pi Agent 正在进行第 ${turn} 轮页面分析`);
    const response = await agentRequest(
      config,
      `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/recover`,
      {
        method: "POST",
        body: JSON.stringify({
          attempt: Math.min(6, turn),
          contextKey: session.accountId || session.sessionId,
          errorMessage: JSON.stringify({
            stage: "GOOGLE_ACCOUNT_PAGE_TASK",
            objective: instruction.slice(0, 800),
            accountId: session.accountId,
            accountLabel: session.label,
            accountEmail: session.email,
            taskMode,
            requiresAction,
            actionHistory: actionHistory.slice(-4).map(item => ({
              turn: item.turn,
              action: item.action,
              target: String(item.target || "").slice(0, 120),
              success: item.success,
              noProgress: Boolean(item.noProgress),
              blocked: Boolean(item.blocked),
              urlAfter: String(item.urlAfter || "").slice(0, 300),
              titleAfter: String(item.titleAfter || "").slice(0, 160),
              error: String(item.error || "").slice(0, 300)
            })),
            turn,
            maxTurns
          }),
          toolResult
        })
      },
      105000
    );
    const advice = response?.data;
    if (!advice) throw new Error("Pi Agent 没有返回页面决策");
    if (advice.requiresTool) {
      const kind = String(advice.toolAction?.type || advice.toolAction?.action || "").toLowerCase();
      const allowedKinds = [
        "observe", "state", "navigate", "click", "input", "type", "select",
        "keys", "hover", "scroll", "back", "forward", "reload", "wait",
        "screenshot", "get_title", "get_markdown", "get_html", "get_text", "get_value",
        "network_requests", "network_request"
      ];
      if (!allowedKinds.includes(kind)) {
        toolResult = {
          ok: false,
          action: advice.action,
          error: `当前页面测试入口未授权执行 ${kind || "未知"} 动作`
        };
        continue;
      }
      const beforeState = compactBrowserState(toolResult.result || {});
      const actionKind = browserActionKind(advice.toolAction);
      const actionTarget = browserActionTarget(advice.toolAction);
      const mutationKinds = new Set([
        "NAVIGATE", "CLICK", "INPUT", "TYPE", "SELECT", "KEYS", "HOVER",
        "SCROLL", "BACK", "FORWARD", "RELOAD"
      ]);
      const beforeFingerprint = browserStateFingerprint(beforeState);
      const lastAction = actionHistory.at(-1);
      if (mutationKinds.has(actionKind)
          && lastAction?.success
          && lastAction.action === actionKind
          && lastAction.target === actionTarget
          && lastAction.stateFingerprintAfter === beforeFingerprint) {
        toolResult = {
          ok: false,
          action: "DUPLICATE_ACTION_BLOCKED",
          error: "相同动作在页面没有变化时被拦截，请重新观察、滚动、查看截图或选择其他明确目标",
          result: beforeState
        };
        actionHistory.push({
          turn,
          action: actionKind,
          target: actionTarget,
          success: false,
          blocked: true,
          error: toolResult.error,
          stateFingerprintAfter: beforeFingerprint
        });
        continue;
      }
      try {
        const result = await browserActClient.executeBrowserAction(advice.toolAction || {}, runtime);
        const currentState = compactBrowserState(await browserActClient.state(session.sessionId));
        const afterFingerprint = browserStateFingerprint(currentState);
        const noProgress = mutationKinds.has(actionKind) && beforeFingerprint === afterFingerprint;
        let visualEvidence = browserScreenshotPayload(result);
        if (noProgress && !visualEvidence.screenshot) {
          const screenshotResult = await browserActClient.executeBrowserAction({
            type: "SCREENSHOT",
            full: false
          }, runtime).catch(() => null);
          visualEvidence = browserScreenshotPayload(screenshotResult || {});
        }
        if (visualEvidence.screenshot) lastScreenshotTurn = turn;
        actionHistory.push({
          turn,
          action: actionKind,
          target: actionTarget,
          success: true,
          noProgress,
          urlBefore: beforeState.url,
          urlAfter: currentState.url,
          titleAfter: currentState.title,
          stateFingerprintAfter: afterFingerprint
        });
        toolResult = {
          ok: true,
          action: advice.action,
          executedAction: actionKind,
          noProgress,
          warning: noProgress
            ? "动作已真实执行，但页面状态没有可验证变化；请结合截图换一种定位或操作策略"
            : "",
          result: {
            ...currentState,
            toolOutput: compactToolOutput(result),
            ...visualEvidence
          }
        };
      } catch (error) {
        const failureObservation = await browserActClient.executeBrowserAction({
          type: "OBSERVE",
          includeScreenshot: true
        }, runtime).catch(() => null);
        const failureScreenshot = browserScreenshotPayload(failureObservation || {});
        if (failureScreenshot.screenshot) lastScreenshotTurn = turn;
        actionHistory.push({
          turn,
          action: browserActionKind(advice.toolAction),
          target: actionTarget,
          success: false,
          error: String(error.message || "谷歌浏览器页面操作失败").slice(0, 1000),
          stateFingerprintAfter: browserStateFingerprint(failureObservation || beforeState)
        });
        toolResult = {
          ok: false,
          action: advice.action,
          error: String(error.message || "谷歌浏览器页面操作失败").slice(0, 3000),
          result: {
            ...compactBrowserState(failureObservation || beforeState),
            ...failureScreenshot
          }
        };
      }
      continue;
    }
    if (advice.action === "FINISH") {
      const finalState = compactBrowserState(await browserActClient.state(session.sessionId));
      const completionEvidence = String(advice.resultData?.completionEvidence || "").trim();
      const evidenceSource = String(advice.resultData?.evidenceSource || "PAGE_TEXT").toUpperCase();
      const evidenceQuotes = Array.isArray(advice.resultData?.evidenceQuotes)
        ? advice.resultData.evidenceQuotes.map(value => String(value || "").trim()).filter(Boolean).slice(0, 8)
        : [];
      const successfulActions = actionHistory.filter(item => item.success);
      const mutationKinds = new Set([
        "NAVIGATE", "CLICK", "INPUT", "TYPE", "SELECT", "KEYS", "HOVER",
        "SCROLL", "BACK", "FORWARD", "RELOAD"
      ]);
      const hasExecutedMutation = successfulActions.some(item => mutationKinds.has(item.action));
      const pageHasEvidence = Boolean(finalState.url && (finalState.title || finalState.text));
      const searchableEvidence = normalizeEvidenceText(
        `${finalState.url}\n${finalState.title}\n${finalState.text}`
      );
      const matchedEvidenceQuotes = evidenceQuotes.filter(quote => {
        const normalizedQuote = normalizeEvidenceText(quote);
        return normalizedQuote.length >= 2 && searchableEvidence.includes(normalizedQuote);
      });
      const latestMutationTurn = successfulActions
        .filter(item => mutationKinds.has(item.action))
        .reduce((latest, item) => Math.max(latest, Number(item.turn || 0)), 0);
      const verifiedVisualEvidence = evidenceSource === "VISUAL_SCREENSHOT"
        && lastScreenshotTurn >= latestMutationTurn;
      const hasVerifiedEvidence = matchedEvidenceQuotes.length > 0 || verifiedVisualEvidence;
      if ((taskMode === "INTERACTIVE_ACTION" && requiresAction && !hasExecutedMutation)
          || !completionEvidence || !pageHasEvidence || !hasVerifiedEvidence) {
        const reason = requiresAction && !hasExecutedMutation
          ? "交互目标尚无任何本机成功动作回执，不能宣告完成"
          : !completionEvidence
            ? "缺少动作后页面完成证据，必须重新观察并验证"
            : !pageHasEvidence
              ? "动作后的页面状态为空，无法确认目标完成"
              : "完成证据未能与当前页面文字或动作后的截图匹配，请重新观察后提供真实证据";
        toolResult = {
          ok: false,
          action: "FINISH_REJECTED",
          error: reason,
          result: finalState,
          actionHistory: actionHistory.slice(-10)
        };
        addRuntimeEvent(`${session.email || session.label}：${reason}`, "warning");
        continue;
      }
      return {
        accountId: session.accountId,
        label: session.label,
        email: session.email,
        success: true,
        steps: turn,
        message: advice.userMessage || "页面任务已完成",
        completionEvidence,
        evidenceSource,
        evidenceQuotes,
        matchedEvidenceQuotes,
        evidenceVerified: true,
        executedActions: successfulActions,
        analysisResult: normalizedAnalysisResult(advice, finalState),
        taskMode,
        url: finalState.url,
        title: finalState.title
      };
    }
    if (advice.action === "FAIL") {
      throw new Error(advice.userMessage || advice.reason || "Pi Agent 确认页面任务无法继续");
    }
    await browserActClient.executeBrowserAction({ type: "WAIT", seconds: 2 }, runtime).catch(() => {});
    toolResult = {
      ok: true,
      action: advice.action || "CONTINUE",
      result: compactBrowserState(await browserActClient.state(session.sessionId))
    };
  }
  throw new Error(`Pi Agent 已完成 ${maxTurns} 轮页面分析，仍没有足够证据确认任务完成`);
}

async function runGoogleAccountPageTask(config, command) {
  const instruction = String(command.input?.instruction || "").trim();
  if (!instruction || instruction.length > 1000) throw new Error("请提供有效的页面自动化目标");
  const probe = await browserActClient.probe();
  accountPoolManager.syncControlledSessions(
    config.browserAct?.accountPool, probe.sessions || []
  );
  const sessions = accountPoolManager.interactiveSessions(
    command.input?.accountSelector,
    command.input?.allActiveAccounts !== false,
    command.input?.accountSelectors
  );
  const results = [];
  for (let index = 0; index < sessions.length; index += 1) {
    const session = sessions[index];
    await agentRequest(
      config,
      `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/progress`,
      {
        method: "POST",
        body: JSON.stringify({
          percent: Math.min(95, Math.max(1, Math.round(index / sessions.length * 90))),
          message: `${session.email || session.label}：Pi Agent 正在执行页面任务`,
          eventType: "AGENT_BROWSER_TASK",
          source: "LOCAL_ASSISTANT",
          payload: { accountId: session.accountId, instruction }
        })
      }
    ).catch(() => {});
    try {
      results.push(await runGoogleAccountPageUnit(config, command, session, instruction));
    } catch (error) {
      results.push({
        accountId: session.accountId,
        label: session.label,
        email: session.email,
        success: false,
        message: String(error.message || "页面任务失败").slice(0, 2000)
      });
    }
  }
  const succeededCount = results.filter(item => item.success).length;
  const failedCount = results.length - succeededCount;
  return {
    instruction,
    requestedCount: sessions.length,
    succeededCount,
    failedCount,
    results,
    completedAt: new Date().toISOString()
  };
}

async function startGoogleAccountPoolWithRecovery(config, requestedPool) {
  const requestedStartUrl = requestedPool.startUrl;
  const requestedAccountIds = new Set(
    (requestedPool.accounts || []).map(account => String(account.id || "")).filter(Boolean)
  );
  const rebuildRequestedPool = current => {
    const currentPool = normalizePoolConfig(current.browserAct?.accountPool);
    return {
      ...currentPool,
      startUrl: requestedStartUrl
    };
  };
  const synchronize = async pool => {
    const required = accountPoolManager.snapshot(pool).localAccounts
      .filter(account => requestedAccountIds.has(account.accountId) && account.syncRequired);
    for (const account of required) {
      addRuntimeEvent(`${account.email} 登录态需要恢复，正在同步本机 Chrome 并复验`, "warning");
      await syncPoolAccountFromLocalProfile(
        account.accountId,
        account.profileId,
        { startAfter: false, startUrl: requestedStartUrl }
      );
    }
    if (required.length) {
      config = await readConfig();
      ({ config } = await prepareAccountPoolConfig(config, true));
    }
    return required.length;
  };

  let pool = rebuildRequestedPool(config);
  let probe = await browserActClient.probe();
  try {
    return {
      config,
      output: await accountPoolManager.startAccounts(
        pool,
        [...requestedAccountIds],
        probe.browsers || [],
        probe.sessions || []
      )
    };
  } catch (firstError) {
    const recovered = await synchronize(pool);
    if (!recovered) throw firstError;
    pool = rebuildRequestedPool(config);
    probe = await browserActClient.probe();
    return {
      config,
      output: await accountPoolManager.startAccounts(
        pool,
        [...requestedAccountIds],
        probe.browsers || [],
        probe.sessions || []
      )
    };
  }
}

function deviceFacts() {
  const platformNames = {
    win32: "Windows",
    darwin: "macOS",
    linux: "Linux"
  };
  return {
    platform: platformNames[process.platform] || process.platform,
    architecture: process.arch,
    hostname: require("node:os").hostname(),
    version: app.getVersion()
  };
}

async function scanLocalGoogleProfiles(force = false) {
  const now = Date.now();
  if (!force && localProfileCache.profiles.length && now - localProfileCache.checkedAt < 15000) {
    return localProfileCache.profiles;
  }
  if (localProfileCache.pending) return localProfileCache.pending;
  localProfileCache.pending = browserActClient.listProfiles()
    .then(profiles => Promise.all(profiles.map(async profile => {
      const cookieFiles = [
        path.join(profile.path, "Network", "Cookies"),
        path.join(profile.path, "Network", "Cookies-journal")
      ];
      const timestamps = await Promise.all(cookieFiles.map(async file => {
        try { return (await fs.stat(file)).mtimeMs; } catch { return 0; }
      }));
      return {
        ...profile,
        cookieUpdatedAtMs: Math.max(0, ...timestamps)
      };
    })))
    .then(profiles => {
      localProfileCache = { checkedAt: Date.now(), profiles, pending: null };
      return profiles;
    })
    .catch(error => {
      localProfileCache.pending = null;
      if (localProfileCache.profiles.length) return localProfileCache.profiles;
      throw error;
    });
  return localProfileCache.pending;
}

async function findChromeExecutable() {
  const candidates = [
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, "Google", "Chrome", "Application", "chrome.exe"),
    process.env.ProgramFiles && path.join(process.env.ProgramFiles, "Google", "Chrome", "Application", "chrome.exe"),
    process.env["ProgramFiles(x86)"] && path.join(process.env["ProgramFiles(x86)"], "Google", "Chrome", "Application", "chrome.exe")
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      await fs.access(candidate);
      return candidate;
    } catch {}
  }
  throw new Error("未找到本机 Google Chrome");
}

async function openChromeProfilePicker() {
  const executable = await findChromeExecutable();
  const child = spawn(executable, ["chrome://profile-picker/"], {
    detached: true,
    stdio: "ignore",
    windowsHide: false
  });
  child.unref();
  return { ok: true };
}

async function closeOrdinaryChrome(userDataPath) {
  if (process.platform !== "win32") return { closed: 0 };
  const script = String.raw`
$ErrorActionPreference = 'Stop'
$target = [IO.Path]::GetFullPath($env:HEYI_TARGET_CHROME_USER_DATA).TrimEnd('\')
$roots = Get-CimInstance Win32_Process -Filter "Name = 'chrome.exe'" | Where-Object {
  $cmd = [string]$_.CommandLine
  if ($cmd -match '--type=') { return $false }
  if ($cmd -match '\\browseract\\profiles\\') { return $false }
  if ($cmd -match '--user-data-dir(?:=|\s+)(?:"([^"]+)"|(\S+))') {
    $candidate = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
    try { return [IO.Path]::GetFullPath($candidate).TrimEnd('\') -eq $target } catch { return $false }
  }
  return $true
}
$closed = 0
foreach ($root in $roots) {
  $process = Get-Process -Id $root.ProcessId -ErrorAction SilentlyContinue
  if (-not $process) { continue }
  $process.CloseMainWindow() | Out-Null
  if (-not $process.WaitForExit(8000)) {
    Stop-Process -Id $root.ProcessId -Force -ErrorAction SilentlyContinue
  }
  $closed += 1
}
Write-Output $closed
`;
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script], {
      windowsHide: true,
      shell: false,
      env: { ...process.env, HEYI_TARGET_CHROME_USER_DATA: userDataPath },
      stdio: ["ignore", "pipe", "pipe"]
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", chunk => { stdout += chunk.toString("utf8"); });
    child.stderr.on("data", chunk => { stderr += chunk.toString("utf8"); });
    child.on("error", error => reject(new Error(`无法关闭普通 Chrome：${error.message}`)));
    child.on("close", code => {
      if (code !== 0) return reject(new Error(`无法关闭普通 Chrome：${stderr.trim() || `退出码 ${code}`}`));
      resolve({ closed: Number(String(stdout).trim()) || 0 });
    });
  });
}

async function assertChromeUserDataUnlocked(profiles, targetProfile) {
  const targetPath = String(targetProfile?.path || "");
  if (!targetPath) throw new Error("无法确定本机 Chrome Profile 路径，请重新检测");

  const userDataPath = path.dirname(targetPath);
  const knownProfiles = new Map(
    (Array.isArray(profiles) ? profiles : [])
      .filter(profile => profile?.path && path.dirname(profile.path) === userDataPath)
      .map(profile => [path.normalize(profile.path).toLowerCase(), profile])
  );
  let entries;
  try {
    entries = await fs.readdir(userDataPath, { withFileTypes: true });
  } catch (error) {
    throw new Error(`无法读取本机 Chrome 用户目录：${error.message}`);
  }

  const profilePaths = entries
    .filter(entry => entry.isDirectory() && (entry.name === "Default" || /^Profile \d+$/.test(entry.name)))
    .map(entry => path.join(userDataPath, entry.name));
  if (!profilePaths.some(profilePath => path.normalize(profilePath).toLowerCase() === path.normalize(targetPath).toLowerCase())) {
    profilePaths.push(targetPath);
  }

  const locked = [];
  for (const profilePath of profilePaths) {
    const cookiePath = path.join(profilePath, "Network", "Cookies");
    let handle;
    try {
      handle = await fs.open(cookiePath, "r");
    } catch (error) {
      if (["EBUSY", "EPERM", "EACCES"].includes(error.code)) {
        const profile = knownProfiles.get(path.normalize(profilePath).toLowerCase());
        const directoryName = path.basename(profilePath);
        locked.push(profile?.name ? `${profile.name}（${directoryName}）` : directoryName);
        continue;
      }
      const isTarget = path.normalize(profilePath).toLowerCase() === path.normalize(targetPath).toLowerCase();
      if (isTarget) throw new Error(`无法读取 ${targetProfile.name} 的 Google Cookie：${error.message}`);
    } finally {
      await handle?.close().catch(() => {});
    }
  }

  if (locked.length) {
    throw new Error(`普通 Chrome 仍在运行：${locked.join("、")}。请关闭所有普通 Chrome 窗口后重试导入。`);
  }
}

async function verifyImportedGoogleAccount(browserId, email) {
  const sessionId = `heyi_import_verify_${Date.now().toString(36)}_${crypto.randomBytes(3).toString("hex")}`;
  await browserActClient.ensureSession({
    browserId,
    sessionId,
    headed: false,
    allowedDomains: []
  }, GOOGLE_ACCOUNT_URL);
  try {
    return await browserActClient.verifyGoogleAccount(sessionId, email);
  } finally {
    await browserActClient.closeSession(sessionId).catch(() => {});
  }
}

function setAccountSyncState(accountId, stage) {
  if (stage) accountSyncStates.set(accountId, { stage, startedAt: Date.now() });
  else accountSyncStates.delete(accountId);
  mainWindow?.webContents.send("agent:runtime-update");
}

async function openTrustedChromeLogin(account) {
  const executable = await findChromeExecutable();
  const args = [];
  if (account.profileDirectory) args.push(`--profile-directory=${account.profileDirectory}`);
  args.push("https://accounts.google.com/");
  const child = spawn(executable, args, {
    detached: true,
    stdio: "ignore",
    windowsHide: false
  });
  child.unref();
  addRuntimeEvent(`已在普通 Chrome 打开 ${account.email} 的登录页面`, "warning");
}

async function syncPoolAccountFromLocalProfile(accountId, requestedProfileId = "", options = {}) {
  return enqueueAccountMutation(async () => {
    let account;
    setAccountSyncState(accountId, "正在检查本机登录态");
    try {
      let config = await migrateToBrowserActAccounts(await readConfig());
      let profiles = await scanLocalGoogleProfiles(true);
      config = await bindLocalProfileMetadata(config, profiles);
      let pool = normalizePoolConfig(config.browserAct?.accountPool);
      account = pool.accounts.find(item => item.id === String(accountId || ""));
      if (!account?.browserId) throw new Error("没有找到要同步的账号");
      const requested = String(requestedProfileId || account.profileId || "");
      const profile = profiles.find(item =>
        item.accountEmail === account.email && (!requested || item.id === requested)
      ) || profiles.find(item => item.accountEmail === account.email);
      if (!profile) {
        await openTrustedChromeLogin(account).catch(() => {});
        const error = new Error(`本机 Chrome 中没有检测到已登录的 ${account.email}，已打开普通 Chrome 登录页面`);
        error.code = "LOCAL_LOGIN_REQUIRED";
        throw error;
      }

      setAccountSyncState(account.id, "正在关闭相关 Chrome");
      await accountPoolManager.releaseAccount(account.id);
      const chromeResult = await closeOrdinaryChrome(path.dirname(profile.path));
      if (chromeResult.closed) {
        addRuntimeEvent(`已关闭 ${chromeResult.closed} 个普通 Chrome 窗口，开始同步 ${account.email}`, "warning");
      }
      await assertChromeUserDataUnlocked(profiles, profile);

      setAccountSyncState(account.id, "正在同步 Cookie");
      await browserActClient.importProfile(account.browserId, profile.id);
      await closeOrdinaryChrome(path.dirname(profile.path));

      setAccountSyncState(account.id, "正在验证 Google 邮箱");
      const verification = await verifyImportedGoogleAccount(account.browserId, account.email);
      if (!verification.ok) {
        await openTrustedChromeLogin({
          ...account,
          profileDirectory: path.basename(profile.path || "")
        }).catch(() => {});
        const error = new Error(`同步后未通过 Google 邮箱验证：${verification.reason}`);
        error.code = "LOCAL_LOGIN_REQUIRED";
        throw error;
      }

      const importedCookieUpdatedAtMs = Math.max(0, Number(profile.cookieUpdatedAtMs) || 0);
      const accounts = pool.accounts.map(item => item.id === account.id ? {
        ...item,
        profileId: profile.id,
        profileName: profile.name,
        profileDirectory: path.basename(profile.path || ""),
        sourceCookieUpdatedAtMs: importedCookieUpdatedAtMs,
        lastImportedCookieUpdatedAtMs: importedCookieUpdatedAtMs,
        lastVerifiedAt: verification.checkedAt || new Date().toISOString()
      } : item);
      config = await writeConfig({
        ...config,
        browserAct: {
          ...config.browserAct,
          accountPool: { ...pool, accounts }
        }
      });
      pool = normalizePoolConfig(config.browserAct.accountPool);
      account = pool.accounts.find(item => item.id === account.id);

      let session = null;
      if (options.startAfter !== false) {
        setAccountSyncState(account.id, "正在启动");
        const startUrl = String(options.startUrl || pool.startUrl || GEMINI_URL);
        session = await accountPoolManager.openAccount(account, { ...pool, startUrl });
      }
      addRuntimeEvent(`已同步并验证 ${account.email}`, "success");
      return { ok: true, account, session, verification, config };
    } catch (error) {
      if (account) {
        accountPoolManager.recordAuthRequired(account, {
          reason: error.code === "LOCAL_LOGIN_REQUIRED"
            ? "需要先在本机 Chrome 完成 Google 登录"
            : `同步失败：${error.message}`
        });
      }
      throw error;
    } finally {
      setAccountSyncState(accountId, "");
    }
  });
}

async function snapshot(options = {}) {
  let config = await readConfig();
  config = await migrateToBrowserActAccounts(config);
  const [platform, feikua, browserAct, localProfiles] = await Promise.all([
    probePlatform(config.platformUrl),
    probeFeikua(),
    probeBrowserAct(config),
    scanLocalGoogleProfiles(options.forceProfiles === true).catch(() => [])
  ]);
  config = await bindLocalProfileMetadata(config, localProfiles);
  accountPoolManager.syncLocalProfiles(config.browserAct?.accountPool, localProfiles);
  const availableExecutors = [feikua, browserAct].filter(item => item.state === "online");
  const browser = {
    state: availableExecutors.length ? "online" : "offline",
    label: availableExecutors.length === 2
      ? "飞跨与谷歌浏览器可用"
      : availableExecutors[0]?.label || "没有可用浏览器执行器"
  };
  runtimeState.browserAct = browserAct;
  if (browserAct.state === "online") {
    accountPoolManager.syncControlledSessions(
      config.browserAct?.accountPool, browserAct.sessions || []
    );
  }
  const poolSnapshot = accountPoolManager.snapshot(config.browserAct?.accountPool);
  const configuredEmails = new Set(
    normalizePoolConfig(config.browserAct?.accountPool).accounts.map(account => account.email).filter(Boolean)
  );
  const profileByEmail = new Map(localProfiles.map(profile => [profile.accountEmail, profile]));
  const profileById = new Map(localProfiles.map(profile => [profile.id, profile]));
  const accountById = new Map(
    normalizePoolConfig(config.browserAct?.accountPool).accounts.map(account => [account.id, account])
  );
  const publicLocalProfiles = localProfiles.map(({ path: _profilePath, ...profile }) => profile);
  const localAccounts = poolSnapshot.localAccounts.map(account => {
    const configured = accountById.get(account.accountId) || {};
    const localProfile = profileById.get(configured.profileId) || profileByEmail.get(account.email) || null;
    const syncing = accountSyncStates.get(account.accountId);
    return {
      ...account,
      localProfile: localProfile
        ? (({ path: _profilePath, ...profile }) => profile)(localProfile)
        : null,
      status: syncing ? "SYNCING" : account.status,
      syncing: Boolean(syncing),
      syncStage: syncing?.stage || "",
      sourceCookieUpdatedAtMs: Number(localProfile?.cookieUpdatedAtMs || configured.sourceCookieUpdatedAtMs || 0),
      lastImportedCookieUpdatedAtMs: Number(configured.lastImportedCookieUpdatedAtMs || 0),
      lastVerifiedAt: configured.lastVerifiedAt || ""
    };
  });
  const importableProfiles = publicLocalProfiles.filter(profile => !configuredEmails.has(profile.accountEmail));
  return {
    config,
    device: deviceFacts(),
    connections: {
      platform,
      agent: {
        state: config.runMode === "unpaired" ? "warning" : "online",
        label: config.runMode === "local" ? "本机试运行" : "执行器在线"
      },
      browser,
      feikua,
      browserAct
    },
    tasks: runtimeState.tasks,
    accountPool: {
      ...poolSnapshot,
      localAccounts,
      localProfiles: publicLocalProfiles,
      importableProfiles,
      pendingProfileImports: [...pendingProfileImports.values()].map(item => ({
        token: item.token,
        browserId: item.browserId,
        email: item.email,
        profileId: item.profileId,
        profileName: item.profileName,
        createdAt: item.createdAt
      })),
      pendingAccounts: [...pendingAccountAdds.values()].map(item => ({
        token: item.token,
        browserId: item.browserId,
        name: item.name,
        createdAt: item.createdAt
      }))
    },
    events: runtimeState.events.length
      ? runtimeState.events
      : [{
          time: new Date().toLocaleTimeString("zh-CN", { hour12: false }),
          level: "info",
          message: feikua.state === "online"
            ? `已识别 ${feikua.storeCount} 个飞跨店铺`
            : "等待飞跨客户端启动"
        }]
  };
}

async function accountPoolHealthTick() {
  const config = await readConfig();
  if (accountPoolManager.snapshot(config.browserAct?.accountPool).activeCount === 0) return;
  const probe = await browserActClient.probe();
  if (probe.state !== "online") return;
  await accountPoolManager.healthCheck(
    config.browserAct?.accountPool, probe.browsers || []
  );
}

function browserActSessionSignature(sessions = []) {
  return sessions
    .map(session => `${session.sessionId}:${session.browserId}:${session.updatedAtMs || 0}`)
    .sort()
    .join("|");
}

async function browserActSessionTick() {
  if (browserActSessionPolling) return;
  browserActSessionPolling = true;
  try {
    const config = await readConfig();
    const probe = await browserActClient.probe();
    if (probe.state !== "online") return;
    const sessions = probe.sessions || [];
    accountPoolManager.syncControlledSessions(config.browserAct?.accountPool, sessions);
    const signature = browserActSessionSignature(sessions);
    if (signature !== lastBrowserActSessionSignature) {
      lastBrowserActSessionSignature = signature;
      mainWindow?.webContents.send("agent:runtime-update");
    }
  } catch {
    // Connection status is rendered by the regular snapshot probe.
  } finally {
    browserActSessionPolling = false;
  }
}

async function sendHeartbeat(config) {
  await agentRequest(config, "/api/v1/automation-agent/heartbeat", {
    method: "POST",
    body: JSON.stringify({
      agentId: config.agentId,
      organizationCode: config.organizationCode || "heyi",
      name: config.agentName || `${require("node:os").hostname()} 执行器`,
      osName: `${deviceFacts().platform} ${deviceFacts().architecture}`,
      version: app.getVersion(),
      acceptingJobs: config.acceptJobs
    })
  });
  runtimeState.lastHeartbeatAt = Date.now();
}

async function executeAutomationCommand(config, command) {
  const commandTitles = {
    FEIKUA_INSPECT: "检查飞跨环境",
    FEIKUA_OPEN_STORE: "打开飞跨店铺",
    YUE_BILL_EXPORT: "月账单导出",
    GOOGLE_ACCOUNT_POOL_START: "启动 Google 账号",
    GOOGLE_ACCOUNT_CLOSE: "关闭账号并自动补位",
    GOOGLE_ACCOUNT_COMMAND: "执行账号池指令",
    GOOGLE_ACCOUNT_PAGE_TASK: "执行 Google 页面任务"
  };
  runtimeState.tasks = [{
    id: command.id,
    title: commandTitles[command.commandType] || command.commandType,
    status: "RUNNING",
    startedAt: new Date().toISOString()
  }];
  scheduleHeartbeat(0);
  mainWindow?.webContents.send("agent:runtime-update");
  addRuntimeEvent(`收到平台任务：${commandTitles[command.commandType] || command.commandType}`);
  let success = false;
  let output = {};
  let errorMessage = null;
  let controlAction = null;
  let controlPoll = null;
  try {
    if (command.commandType === "FEIKUA_INSPECT") {
      const stores = await feikuaClient.listStores();
      output = {
        connected: true,
        storeCount: stores.length,
        runningCount: stores.filter((store) =>
          ["running", "started"].includes(String(store.status || "").toLowerCase())
          || Boolean(store.is_running || store.running)
        ).length,
        checkedAt: new Date().toISOString()
      };
      addRuntimeEvent(`飞跨检查完成：识别 ${stores.length} 个店铺环境`, "success");
    } else if (command.commandType === "FEIKUA_OPEN_STORE") {
      const requestedName = String(command.input?.storeName || "").trim();
      if (!requestedName || requestedName.length > 160) {
        throw new Error("请提供有效的飞跨店铺名称");
      }
      const stores = await feikuaClient.listStores();
      const matches = stores.filter((store) =>
        String(store.shop_name || "").trim().toLocaleLowerCase() === requestedName.toLocaleLowerCase()
      );
      if (matches.length === 0) {
        throw new Error(`没有找到名称完全匹配的店铺：${requestedName}`);
      }
      if (matches.length > 1) {
        throw new Error(`存在多个同名店铺，请使用店铺 ID 调试：${requestedName}`);
      }
      const store = matches[0];
      const status = String(store.status || "").toLowerCase();
      let started;
      if (["running", "started"].includes(status) && Number(store.debugger_port) > 0) {
        started = {
          shopId: String(store.shop_id),
          debuggerPort: Number(store.debugger_port),
          coreVersion: String(store.core_version || "")
        };
      } else if (["running", "started"].includes(status)) {
        throw new Error("该店铺已经运行，但飞跨未返回当前调试端口，请先在飞跨中关闭后重试");
      } else {
        started = await feikuaClient.startStore(store.shop_id);
      }
      const page = await feikuaClient.openStorePage(started.debuggerPort);
      output = {
        opened: true,
        storeName: String(store.shop_name),
        shopId: String(store.shop_id),
        debuggerPort: started.debuggerPort,
        coreVersion: started.coreVersion,
        pageTitle: page.pageTitle,
        pageUrl: page.pageUrl,
        reused: page.reused,
        openedAt: new Date().toISOString()
      };
      addRuntimeEvent(`店铺已打开：${store.shop_name}`, "success");
    } else if (command.commandType === "GOOGLE_ACCOUNT_POOL_START") {
      let prepared;
      ({ config, profiles: prepared } = await prepareAccountPoolConfig(config, true));
      const configuredPool = normalizePoolConfig(config.browserAct?.accountPool);
      const requestedSelectors = [
        ...(Array.isArray(command.input?.accountSelectors) ? command.input.accountSelectors : []),
        ...(command.input?.accountSelector ? [command.input.accountSelector] : [])
      ].map(value => String(value || "").trim()).filter(Boolean)
        .filter((value, index, items) =>
          items.findIndex(item => item.toLocaleLowerCase() === value.toLocaleLowerCase()) === index
        );
      const selectedAccounts = [];
      if (requestedSelectors.length) {
        const activeSessionsByAccountId = new Map(
          accountPoolManager.snapshot(configuredPool).localAccounts
            .map(account => [account.accountId, account])
        );
        for (const accountSelector of requestedSelectors) {
          const normalizedSelector = accountSelector.toLocaleLowerCase();
          const matches = configuredPool.accounts.filter(account => [
            account.id,
            account.label,
            account.email,
            account.profileId,
            activeSessionsByAccountId.get(account.id)?.sessionId,
            ...(account.aliases || [])
          ].some(value => String(value || "").trim().toLocaleLowerCase() === normalizedSelector));
          if (!matches.length) throw new Error(`已配置账号中没有找到：${accountSelector}`);
          if (matches.length > 1) throw new Error(`账号目标不唯一，请使用邮箱或账号 ID：${accountSelector}`);
          if (selectedAccounts.some(account => account.id === matches[0].id)) {
            throw new Error(`同一个账号被重复指定：${accountSelector}`);
          }
          selectedAccounts.push(matches[0]);
        }
      }
      const requestedCount = selectedAccounts.length ? selectedAccounts.length : Math.max(1, Math.min(
        configuredPool.accounts.length || 10,
        Number(command.input?.count) || configuredPool.poolSize
      ));
      let targetUrl = String(command.input?.targetUrl || configuredPool.startUrl || GEMINI_URL).trim();
      const parsedTarget = new URL(targetUrl);
      if (!["http:", "https:"].includes(parsedTarget.protocol)) {
        throw new Error("目标地址只允许 HTTP 或 HTTPS");
      }
      targetUrl = parsedTarget.toString();
      const requestedPool = {
        ...configuredPool,
        accounts: selectedAccounts.length
          ? selectedAccounts
          : configuredPool.accounts.slice(0, requestedCount),
        poolSize: requestedCount,
        startUrl: targetUrl
      };
      ({ config, output } = await startGoogleAccountPoolWithRecovery(config, requestedPool));
      output = { ...output, requestedCount, targetUrl };
      config = await saveVerifiedAccountBaselines(config, prepared, output);
      addRuntimeEvent(
        `Google 账号池已启动：${output.activeCount}/${output.poolSize}，已进入 ${targetUrl}`,
        "success"
      );
      const afterStartInstruction = String(command.input?.afterStartInstruction || "").trim();
      if (afterStartInstruction) {
        const pageTask = await runGoogleAccountPageTask(config, {
          ...command,
          input: {
            instruction: afterStartInstruction,
            accountSelector: requestedPool.accounts.length === 1 ? requestedPool.accounts[0].email : "",
            accountSelectors: requestedPool.accounts.map(account => account.email),
            allActiveAccounts: false
          }
        });
        output = { ...output, pageTask };
        if (pageTask.failedCount > 0) {
          const failures = pageTask.results
            .filter(item => !item.success)
            .map(item => `${item.email || item.label}：${item.message}`)
            .join("；");
          throw new Error(
            `账号池已启动，但页面任务完成 ${pageTask.succeededCount}/${pageTask.requestedCount}；${failures}`
          );
        }
      }
    } else if (command.commandType === "GOOGLE_ACCOUNT_CLOSE") {
      const selector = command.input?.sessionId || command.input?.email
        || command.input?.accountId || command.input?.accountName;
      const probe = await browserActClient.probe();
      accountPoolManager.syncControlledSessions(
        config.browserAct?.accountPool, probe.sessions || []
      );
      output = await accountPoolManager.closeAndBackfill(
        selector, config.browserAct?.accountPool, probe.browsers || []
      );
      addRuntimeEvent(
        output.replacement
          ? `${output.closed.email} 已关闭，${output.replacement.email} 已自动补位`
          : `${output.closed.email} 已关闭，等待可用账号补位`,
        output.replacement ? "success" : "warning"
      );
    } else if (command.commandType === "GOOGLE_ACCOUNT_COMMAND") {
      const probe = await browserActClient.probe();
      accountPoolManager.syncControlledSessions(
        config.browserAct?.accountPool, probe.sessions || []
      );
      output = await accountPoolManager.handleInstruction(
        command.input?.instruction, config.browserAct?.accountPool, probe.browsers || []
      );
    } else if (command.commandType === "GOOGLE_ACCOUNT_PAGE_TASK") {
      output = await runGoogleAccountPageTask(config, command);
      if (output.failedCount > 0) {
        const failures = output.results
          .filter(item => !item.success)
          .map(item => `${item.email || item.label}：${item.message}`)
          .join("；");
        throw new Error(`页面任务完成 ${output.succeededCount}/${output.requestedCount} 个账号；${failures}`);
      }
      addRuntimeEvent(
        `Google 页面任务完成：${output.succeededCount}/${output.requestedCount} 个账号`,
        "success"
      );
    } else if (command.commandType === "YUE_BILL_EXPORT") {
      const taskFiles = await prepareTaskWorkspace(config, command);
      let lastProgressAt = 0;
      let latestProgress = 5;
      const controlController = new AbortController();
      controlPoll = setInterval(async () => {
        try {
          const response = await agentRequest(
            config,
            `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/control`
          );
          if (response?.data?.action && !controlController.signal.aborted) {
            controlAction = response.data.action;
            addRuntimeEvent(`收到任务控制：${controlAction}`, "warning");
            controlController.abort();
          }
        } catch {
          // A temporary control-channel failure must not stop the local Skill.
        }
      }, 1000);
      const workingCommand = structuredClone(command);
      const reportProgress = (
        percent,
        message,
        eventType = "SKILL_OUTPUT",
        source = "SKILL",
        payload = null
      ) => {
        latestProgress = Math.max(latestProgress, Number(percent) || 0);
        addRuntimeEvent(message);
        if (Date.now() - lastProgressAt < 3000 && percent < 95
            && eventType === "SKILL_OUTPUT") return;
        lastProgressAt = Date.now();
        agentRequest(
          config,
          `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/progress`,
          {
            method: "POST",
            body: JSON.stringify({ percent, message, eventType, source, payload })
          }
        ).catch(() => {});
      };
      const reportUnitResult = unit => {
        const resultName = unit.outcome === "SUCCESS"
          ? "执行成功"
          : unit.outcome === "NEEDS_ATTENTION"
            ? "需要关注"
            : "执行失败";
        return agentRequest(
          config,
          `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/progress`,
          {
            method: "POST",
            body: JSON.stringify({
              percent: Math.min(94, latestProgress),
              message: `${unit.unitName}：${resultName}`,
              eventType: "EXECUTION_UNIT_RESULT",
              source: "LOCAL_ASSISTANT",
              payload: unit
            })
          }
        ).catch(() => {});
      };
      const reportUnitUpdate = unit => agentRequest(
        config,
        `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/progress`,
        {
          method: "POST",
          body: JSON.stringify({
            percent: Math.min(94, latestProgress),
            message: `${unit.unitName}：本次尝试已结束，模型正在复核并等待最终恢复结果`,
            eventType: "EXECUTION_UNIT_UPDATE",
            source: "LOCAL_ASSISTANT",
            payload: unit
          })
        }
      ).catch(() => {});
      const requestTerminalRecovery = async unit => {
        let toolResult = null;
        let advice = null;
        for (let agentTurn = 1; agentTurn <= 8; agentTurn += 1) {
          const response = await agentRequest(
            config,
            `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/recover`,
            {
              method: "POST",
              body: JSON.stringify({
                attempt: Math.max(
                  1, Math.min(6, Number(unit.recoveryAttempts || 0) + 1)
                ),
                errorMessage: JSON.stringify({
                  stage: "TERMINAL_CANDIDATE_REVIEW",
                  unitId: unit.unitId,
                  unitName: unit.unitName,
                  outcome: unit.outcome,
                  status: unit.status,
                  terminalCode: unit.terminalCode,
                  message: unit.message,
                  officialWarehouse: unit.officialWarehouse,
                  verifiedFileCount: unit.verifiedFileCount
                }).slice(0, 3800),
                toolResult
              })
            },
            105000
          );
          advice = response?.data;
          if (!advice?.requiresTool) return advice;
          addRuntimeEvent(
            advice.userMessage || `${unit.unitName}：Pi Agent 正在观察页面并辅助恢复`
          );
          try {
            toolResult = {
              ok: true,
              action: advice.action,
              result: await executeModelBrowserAction(
                config, workingCommand, advice.toolAction || {}
              )
            };
          } catch (toolError) {
            toolResult = {
              ok: false,
              action: advice.action,
              error: String(toolError.message || "浏览器恢复工具执行失败").slice(0, 3000)
            };
          }
        }
        return {
          continueExecution: false,
          action: "FAIL",
          userMessage: `${unit.unitName}：模型已完成 8 轮受控复核，仍无法形成可靠恢复方案`
        };
      };
      let recoveryAttempt = 0;
      while (true) {
        try {
          output = await runLocalSkill(workingCommand, {
            signal: controlController.signal,
            getControlAction: () => controlAction,
            onProgress: reportProgress,
            onUnitResult: reportUnitResult,
            onUnitUpdate: reportUnitUpdate,
            onTerminalCandidate: requestTerminalRecovery
          });
          break;
        } catch (error) {
          if (error.controlAction || controlController.signal.aborted) throw error;
          recoveryAttempt += 1;
          const failure = String(error.message || "Skill 执行异常").slice(-3800);
          reportProgress(
            Math.min(94, latestProgress),
            `第 ${recoveryAttempt} 次执行异常，模型正在分析恢复方案`,
            "RECOVERY_REQUESTED",
            "LOCAL_ASSISTANT"
          );
          let advice;
          let toolResult = null;
          try {
            for (let agentTurn = 1; agentTurn <= 8; agentTurn += 1) {
              const response = await agentRequest(
                config,
                `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/recover`,
                {
                  method: "POST",
                  body: JSON.stringify({
                    attempt: Math.min(6, recoveryAttempt),
                    errorMessage: failure,
                    toolResult
                  })
                },
                105000
              );
              advice = response?.data;
              if (!advice?.requiresTool) break;
              addRuntimeEvent(advice.userMessage || "Pi Agent 正在调用浏览器工具");
              try {
                toolResult = {
                  ok: true,
                  action: advice.action,
                  result: await executeModelBrowserAction(
                    config, workingCommand, advice.toolAction || {}
                  )
                };
              } catch (toolError) {
                toolResult = {
                  ok: false,
                  action: advice.action,
                  error: String(toolError.message || "浏览器工具执行失败").slice(0, 3000)
                };
              }
              reportProgress(
                Math.min(94, latestProgress),
                `Pi Agent 浏览器工具已返回：${advice.action}`,
                "AGENT_TOOL_RESULT",
                "LOCAL_ASSISTANT"
              );
            }
            if (advice?.requiresTool) {
              throw new Error("Pi Agent 连续浏览器工具调用达到 8 次上限");
            }
          } catch (diagnosisError) {
            advice = recoveryAttempt <= 2
              ? {
                  continueExecution: true,
                  action: "RETRY_RESUME",
                  delaySeconds: 3,
                  userMessage: "模型诊断暂时未返回，先按断点恢复策略继续重试"
                }
              : {
                  continueExecution: false,
                  action: "FAIL",
                  userMessage: `模型诊断不可用：${diagnosisError.message}`
                };
          }
          addRuntimeEvent(advice?.userMessage || "模型已返回恢复方案", "warning");
          if (!advice?.continueExecution || recoveryAttempt >= 6) {
            throw new Error(
              `${advice?.userMessage || "已达到自动恢复上限"}；最后错误：${failure}`
            );
          }
          if (advice.action === "REDUCE_CONCURRENCY") {
            const current = Number(
              workingCommand.input?.parameters?.maxConcurrency || 3
            );
            const next = Math.max(
              1,
              Math.min(current - 1, Number(advice.maxConcurrency || current - 1))
            );
            workingCommand.input.parameters.maxConcurrency = next;
            workingCommand.input.parameters.maxLiveStoreSessions = next;
            addRuntimeEvent(`模型已将并发调整为 ${next}，从断点继续`, "warning");
          }
          const delayMs = Math.max(
            0, Math.min(30000, Number(advice.delaySeconds || 0) * 1000)
          );
          if (delayMs > 0) {
            await new Promise((resolve) => setTimeout(resolve, delayMs));
          }
          reportProgress(
            Math.min(94, latestProgress),
            `正在执行第 ${recoveryAttempt + 1} 次自愈恢复`,
            "RECOVERY_APPLIED",
            "LOCAL_ASSISTANT"
          );
        }
      }
      clearInterval(controlPoll);
      controlPoll = null;
      const skillResult = output;
      let artifact = null;
      let delivery;
      try {
        artifact = await uploadTaskArtifact(
          config, command, taskFiles.outputRoot, taskFiles.workspace
        );
        delivery = {
          status: "SUCCEEDED",
          message: "结果文件已打包并上传，可在页面下载",
          attempts: 1
        };
      } catch (deliveryError) {
        const message = String(
          deliveryError.message || "结果文件打包上传失败"
        ).slice(0, 2000);
        delivery = {
          status: "FAILED",
          message,
          attempts: 3,
          localResultsPreserved: true
        };
        reportProgress(
          99,
          `业务执行已完成，但结果文件交付失败：${message}`,
          "DELIVERY_ERROR",
          "LOCAL_ASSISTANT",
          delivery
        );
      }
      output = {
        completedAt: skillResult.completedAt,
        exitCode: skillResult.exitCode,
        businessResult: skillResult.businessResult,
        delivery,
        ...(artifact ? { artifact } : {})
      };
      addRuntimeEvent(
        delivery.status === "SUCCEEDED"
          ? "月账单导出完成，结果文件已上传"
          : "月账单业务执行完成，结果文件打包失败，本地结果已保留",
        delivery.status === "SUCCEEDED" ? "success" : "warning"
      );
    } else if (String(commandBrowserRuntime(command, config).browserEngine || "").toUpperCase() === "BROWSER_ACT") {
      output = await runBrowserActPlan(config, command);
      addRuntimeEvent(
        `谷歌浏览器自动化完成：${output.completedSteps} 个动作`,
        "success"
      );
    } else {
      throw new Error("当前版本不允许执行这个自动化动作");
    }
    success = true;
  } catch (error) {
    if (error.controlAction) {
      controlAction = error.controlAction;
      addRuntimeEvent(error.message, "warning");
    } else {
      errorMessage = error.message || "飞跨自动化执行失败";
      addRuntimeEvent(errorMessage, "error");
    }
  } finally {
    if (controlPoll) clearInterval(controlPoll);
  }

  if (controlAction) {
    await agentRequest(
      config,
      `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/control/ack`,
      {
        method: "POST",
        body: JSON.stringify({ action: controlAction })
      }
    );
    runtimeState.tasks = [];
    scheduleHeartbeat();
    mainWindow?.webContents.send("agent:runtime-update");
    return;
  }

  await agentRequest(
    config,
    `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/${encodeURIComponent(command.id)}/complete`,
    {
      method: "POST",
      body: JSON.stringify({ success, output, errorMessage })
    }
  );
  const deliverySucceeded = output?.delivery?.status !== "FAILED";
  if (!success || deliverySucceeded) {
    await fs.rm(
      path.join(app.getPath("userData"), "tasks", safeTaskFileName(command.id)),
      { recursive: true, force: true }
    ).catch(() => {});
  }
  runtimeState.tasks = [];
  scheduleHeartbeat();
  mainWindow?.webContents.send("agent:runtime-update");
}

async function automationTick() {
  if (runtimeState.polling) return;
  runtimeState.polling = true;
  try {
    const config = await readConfig();
    if (config.runMode === "unpaired" || !config.agentId) return;
    if (!config.acceptJobs || runtimeState.tasks.length) return;
    const response = await agentRequest(
      config,
      `/api/v1/automation-agent/${encodeURIComponent(config.agentId)}/commands/next`
    );
    if (response?.data) {
      await executeAutomationCommand(config, response.data);
    }
  } catch (error) {
    if (!runtimeState.events[0]?.message.includes("平台任务通道")) {
      addRuntimeEvent(`平台任务通道未连接：${error.message}`, "warning");
    }
  } finally {
    runtimeState.polling = false;
  }
}

async function heartbeatTick() {
  if (heartbeatInFlight) return;
  heartbeatInFlight = true;
  try {
    const config = await readConfig();
    if (config.runMode !== "unpaired" && config.agentId) {
      await sendHeartbeat(config);
    }
  } catch {
    // The polling loop renders the connection warning.
  } finally {
    heartbeatInFlight = false;
  }
}

function scheduleHeartbeat(delayMs = heartbeatDelay(runtimeState.tasks)) {
  if (heartbeatTimer) clearTimeout(heartbeatTimer);
  heartbeatTimer = setTimeout(async () => {
    heartbeatTimer = null;
    await heartbeatTick();
    scheduleHeartbeat();
  }, Math.max(0, Number(delayMs) || 0));
  heartbeatTimer.unref?.();
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1120,
    height: 760,
    minWidth: 920,
    minHeight: 640,
    show: false,
    backgroundColor: "#edf2f7",
    title: "赫逸自动化助手",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
  mainWindow.once("ready-to-show", () => mainWindow.show());
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith("https://")) shell.openExternal(url);
    return { action: "deny" };
  });
  mainWindow.webContents.on("will-navigate", (event) => event.preventDefault());
  mainWindow.on("close", (event) => {
    if (!quitting) {
      event.preventDefault();
      mainWindow.hide();
    }
  });
}

function createTray() {
  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32">
      <rect width="32" height="32" rx="8" fill="#2457D6"/>
      <path d="M8 7v18M23 7 13 16l10 9M8 16h5" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
      <circle cx="24" cy="8" r="3" fill="#45D0BE"/>
    </svg>`;
  tray = new Tray(nativeImage.createFromDataURL(`data:image/svg+xml;base64,${Buffer.from(svg).toString("base64")}`));
  tray.setToolTip("赫逸自动化助手");
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: "打开赫逸自动化助手", click: () => mainWindow.show() },
    { type: "separator" },
    {
      label: "退出",
      click: () => {
        quitting = true;
        app.quit();
      }
    }
  ]));
  tray.on("double-click", () => mainWindow.show());
}

function startLocalPickerServer() {
  pickerServer = http.createServer(async (request, response) => {
    const origin = String(request.headers.origin || "");
    const config = await readConfig();
    const configuredOrigins = String(process.env.HEYI_WEB_ORIGINS || "")
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
    const allowedOrigins = new Set([
      "http://localhost:3000",
      "http://127.0.0.1:3000",
      normalizePlatformUrl(config.platformUrl),
      ...configuredOrigins
    ]);
    if (!allowedOrigins.has(origin)) {
      response.writeHead(403, { "Content-Type": "application/json; charset=utf-8" });
      response.end(JSON.stringify({ message: "当前网页没有调用本机选择器的权限" }));
      return;
    }
    const corsHeaders = {
      "Access-Control-Allow-Origin": origin,
      "Access-Control-Allow-Methods": "GET, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Allow-Private-Network": "true",
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
      Vary: "Origin"
    };
    if (request.method === "OPTIONS") {
      response.writeHead(204, corsHeaders);
      response.end();
      return;
    }
    if (request.method === "GET" && request.url === "/v1/status") {
      const platformConnected = Boolean(
        config.agentId
        && runtimeState.lastHeartbeatAt
        && Date.now() - runtimeState.lastHeartbeatAt < 20000
      );
      response.writeHead(200, corsHeaders);
      response.end(JSON.stringify({
        agentId: config.agentId || null,
        name: config.agentName || null,
        version: app.getVersion(),
        acceptingJobs: Boolean(config.acceptJobs),
        platformConnected,
        browserAct: runtimeState.browserAct,
        lastHeartbeatAt: runtimeState.lastHeartbeatAt
          ? new Date(runtimeState.lastHeartbeatAt).toISOString()
          : null
      }));
      return;
    }
    if (request.method !== "GET" || request.url !== "/v1/picker/directory") {
      response.writeHead(404, corsHeaders);
      response.end(JSON.stringify({ message: "本机选择器接口不存在" }));
      return;
    }
    try {
      const result = await dialog.showOpenDialog(mainWindow, {
        title: "选择自动化输出文件夹",
        buttonLabel: "选择此文件夹",
        properties: ["openDirectory", "createDirectory"]
      });
      response.writeHead(200, corsHeaders);
      response.end(JSON.stringify({
        cancelled: result.canceled,
        path: result.canceled ? null : result.filePaths[0]
      }));
    } catch (error) {
      response.writeHead(500, corsHeaders);
      response.end(JSON.stringify({ message: error.message || "无法打开文件夹选择器" }));
    }
  });
  pickerServer.on("error", (error) => {
    addRuntimeEvent(`本机文件夹选择服务启动失败：${error.message}`, "warning");
  });
  pickerServer.listen(17879, "127.0.0.1");
}

function registerIpc() {
  ipcMain.handle("agent:snapshot", () => snapshot());
  ipcMain.handle("agent:probe", () => snapshot({ forceProfiles: true }));
  ipcMain.handle("agent:start-local", async (_event, settings) => {
    const current = await readConfig();
    const next = await writeConfig({
      ...current,
      platformUrl: settings.platformUrl,
      agentName: settings.agentName || `${require("node:os").hostname()} 执行器`,
      runMode: "local",
      agentId: current.agentId || crypto.randomUUID()
    });
    automationTick();
    return { ok: true, config: next };
  });
  ipcMain.handle("agent:save-settings", async (_event, settings) => {
    const current = await readConfig();
    const incomingBrowserAct = settings?.browserAct || {};
    return writeConfig({
      ...current,
      ...(settings || {}),
      browserAct: {
        ...current.browserAct,
        ...incomingBrowserAct,
        accountPool: {
          ...current.browserAct?.accountPool,
          ...(incomingBrowserAct.accountPool || {})
        }
      }
    });
  });
  ipcMain.handle("agent:browser-act-auth-start", async () => {
    const result = await browserActClient.startWebAuthorization();
    await shell.openExternal(result.url);
    addRuntimeEvent("已打开谷歌浏览器网页授权，等待用户确认", "warning");
    return result;
  });
  ipcMain.handle("agent:browser-act-auth-poll", async () => {
    const result = await browserActClient.pollWebAuthorization();
    if (["success", "completed", "authorized", "registered"].includes(result.status)) {
      localProfileCache = { checkedAt: 0, profiles: [], pending: null };
      addRuntimeEvent("谷歌浏览器网页授权已完成", "success");
    }
    return result;
  });
  ipcMain.handle("agent:browser-act-website", async () => {
    await shell.openExternal(BROWSER_ACT_WEBSITE);
    return { ok: true };
  });
  ipcMain.handle("agent:toggle-accept-jobs", async (_event, acceptJobs) => {
    const current = await readConfig();
    return writeConfig({ ...current, acceptJobs: Boolean(acceptJobs) });
  });
  ipcMain.handle("agent:reset", async () => {
    await writeConfig(DEFAULT_CONFIG);
    return { ok: true };
  });
  ipcMain.handle("agent:open-logs", async () => {
    await fs.mkdir(logDirectory(), { recursive: true });
    return shell.openPath(logDirectory());
  });
  ipcMain.handle("agent:pool-recovery-plan", async (_event, accountId) => {
    const config = await readConfig();
    const probe = await browserActClient.probe();
    return accountPoolManager.prepareRecovery(
      accountId, config.browserAct?.accountPool, probe.localProfiles || []
    );
  });
  ipcMain.handle("agent:pool-recover", async (_event, request) => {
    if (request?.confirmed !== true) throw new Error("重新导入 Profile 前必须由用户明确确认");
    const config = await readConfig();
    const probe = await browserActClient.probe();
    accountPoolManager.prepareRecovery(
      request.accountId, config.browserAct?.accountPool, probe.localProfiles || []
    );
    return accountPoolManager.recoverAccount(
      request.accountId,
      config.browserAct?.accountPool,
      probe.browsers || [],
      probe.sessions || []
    );
  });
  ipcMain.handle("agent:pool-login-assist", async (_event, accountId) => {
    const config = await readConfig();
    const probe = await browserActClient.probe();
    const result = await accountPoolManager.startLoginAssist(
      accountId, config.browserAct?.accountPool, probe.browsers || []
    );
    if (result.url) {
      const parsed = new URL(result.url);
      if (["http:", "https:"].includes(parsed.protocol)) await shell.openExternal(parsed.toString());
    }
    return result;
  });
  ipcMain.handle("agent:pool-login-complete", async (_event, accountId) => {
    const config = await readConfig();
    const probe = await browserActClient.probe();
    return accountPoolManager.finishLoginAssist(
      accountId, config.browserAct?.accountPool, probe.browsers || []
    );
  });
  ipcMain.handle("agent:pool-open-chrome-login", async (_event, accountId) => {
    const config = await readConfig();
    const account = normalizePoolConfig(config.browserAct?.accountPool).accounts
      .find(item => item.id === String(accountId || ""));
    if (account) await openTrustedChromeLogin(account);
    else await openChromeProfilePicker();
    addRuntimeEvent(
      account
        ? `等待在普通 Chrome 完成 ${account.email} 登录`
        : "已打开普通 Chrome 账号选择器，等待完成 Google 登录",
      "warning"
    );
    return { ok: true };
  });
  ipcMain.handle("agent:pool-local-profile-create-plan", async (_event, profileId) => {
    const profiles = await scanLocalGoogleProfiles(true);
    const profile = profiles.find(item => item.id === String(profileId || ""));
    if (!profile?.accountEmail) throw new Error("没有找到这个已登录的 Chrome 账号");
    const localPart = profile.accountEmail.split("@")[0].replace(/[^A-Za-z0-9-]/g, "-").slice(0, 32);
    const suffix = Date.now().toString(36).slice(-5);
    return {
      profileId: profile.id,
      profileName: profile.name,
      email: profile.accountEmail,
      name: `heyi-google-${localPart}-${suffix}`.slice(0, 80),
      type: "chrome",
      description: `赫逸自动化助手独立账号环境；将从本机 Chrome ${profile.name} 导入 ${profile.accountEmail} 的登录状态`
    };
  });
  ipcMain.handle("agent:pool-local-profile-create", async (_event, request) => {
    if (request?.confirmed !== true) throw new Error("创建谷歌浏览器账号环境前需要明确确认");
    const profiles = await scanLocalGoogleProfiles(true);
    const profile = profiles.find(item => item.id === String(request.profileId || ""));
    if (!profile?.accountEmail || profile.accountEmail !== String(request.email || "").toLowerCase()) {
      throw new Error("本机 Chrome 账号已发生变化，请重新检测");
    }
    const localPart = profile.accountEmail.split("@")[0].replace(/[^A-Za-z0-9-]/g, "-").slice(0, 32);
    const name = String(request.name || "").trim();
    if (!name.startsWith(`heyi-google-${localPart}-`) || !/^[A-Za-z0-9._-]{1,80}$/.test(name)) {
      throw new Error("谷歌浏览器环境名称无效，请重新添加");
    }
    const config = await readConfig();
    const pool = normalizePoolConfig(config.browserAct?.accountPool);
    if (pool.accounts.some(account => account.email === profile.accountEmail)) {
      throw new Error("该邮箱已在账号列表中，请使用“同步本机登录态”");
    }
    const created = await browserActClient.createChromeBrowser(name, String(request.description || "").slice(0, 500));
    const token = crypto.randomUUID();
    pendingProfileImports.set(token, {
      token,
      browserId: created.browserId,
      profileId: profile.id,
      profileName: profile.name,
      email: profile.accountEmail,
      name,
      createdAt: new Date().toISOString()
    });
    addRuntimeEvent(`已为 ${profile.accountEmail} 创建谷歌浏览器环境，等待确认导入登录态`, "warning");
    return { token, browserId: created.browserId, email: profile.accountEmail };
  });
  ipcMain.handle("agent:pool-local-profile-import", async (_event, request) => {
    if (request?.confirmed !== true) throw new Error("导入 Chrome Profile 前需要明确确认");
    const pending = pendingProfileImports.get(String(request.token || ""));
    if (!pending) throw new Error("没有等待导入的谷歌浏览器环境");
    if (pending.importing) throw new Error("这个账号正在导入，请勿重复点击");
    pending.importing = true;
    try {
      const profiles = await scanLocalGoogleProfiles(true);
      const profile = profiles.find(item => item.id === pending.profileId && item.accountEmail === pending.email);
      if (!profile) throw new Error("本机 Chrome 登录账号已发生变化，请重新检测");
      const chromeResult = await closeOrdinaryChrome(path.dirname(profile.path));
      if (chromeResult.closed) addRuntimeEvent(`已关闭 ${chromeResult.closed} 个普通 Chrome 窗口，开始导入账号`, "warning");
      await assertChromeUserDataUnlocked(profiles, profile);
      await browserActClient.importProfile(pending.browserId, pending.profileId);
      await closeOrdinaryChrome(path.dirname(profile.path));
      const verification = await verifyImportedGoogleAccount(pending.browserId, pending.email);
      if (!verification.ok) throw new Error(`导入后未通过 Google 邮箱验证：${verification.reason}`);
      let config = await migrateToBrowserActAccounts(await readConfig());
      const pool = normalizePoolConfig(config.browserAct?.accountPool);
      if (pool.accounts.some(item => item.email === pending.email || item.browserId === pending.browserId)) {
        throw new Error(`${pending.email} 已经加入账号列表，请勿重复导入`);
      }
      const account = {
        id: `google-${crypto.randomUUID().slice(0, 12)}`,
        label: pending.profileName || pending.email.split("@")[0],
        email: pending.email,
        browserId: pending.browserId,
        profileId: pending.profileId,
        profileName: profile.name,
        profileDirectory: path.basename(profile.path || ""),
        sourceCookieUpdatedAtMs: Number(profile.cookieUpdatedAtMs || 0),
        lastImportedCookieUpdatedAtMs: Number(profile.cookieUpdatedAtMs || 0),
        lastVerifiedAt: verification.checkedAt || new Date().toISOString(),
        aliases: [],
        enabled: true
      };
      const accounts = [...pool.accounts, account];
      config = await writeConfig({
        ...config,
        browserAct: {
          ...config.browserAct,
          accountPool: {
            ...pool,
            poolSize: Math.min(5, Math.max(pool.poolSize, Math.min(accounts.length, 5))),
            accounts
          }
        }
      });
      pendingProfileImports.delete(pending.token);
      addRuntimeEvent(`已从本机 Chrome 导入并添加账号：${pending.email}`, "success");
      return { ok: true, email: pending.email, browserId: pending.browserId, config };
    } catch (error) {
      pending.importing = false;
      throw error;
    }
  });
  ipcMain.handle("agent:pool-profile-import-cancel", async (_event, request) => {
    if (request?.confirmed !== true) throw new Error("删除新建谷歌浏览器环境前需要明确确认");
    const pending = pendingProfileImports.get(String(request.token || ""));
    if (!pending) throw new Error("这个待导入环境已不存在");
    const deletion = await browserActClient.deleteBrowser(
      pending.browserId,
      `取消从本机 Chrome 导入 Google 账号 ${pending.email} 的待确认环境`
    );
    if (deletion.delete_url || deletion.deleteUrl) throw new Error("请先在谷歌浏览器页面完成环境删除确认");
    pendingProfileImports.delete(pending.token);
    addRuntimeEvent(`已取消 ${pending.email} 的谷歌浏览器环境创建`, "warning");
    return { ok: true };
  });
  ipcMain.handle("agent:pool-sync-plan", async (_event, accountId) => {
    const config = await readConfig();
    const pool = normalizePoolConfig(config.browserAct?.accountPool);
    const account = pool.accounts.find(item => item.id === String(accountId || ""));
    if (!account?.browserId) throw new Error("没有找到要同步的账号");
    const profiles = await scanLocalGoogleProfiles(true);
    const profile = profiles.find(item => item.id === account.profileId && item.accountEmail === account.email)
      || profiles.find(item => item.accountEmail === account.email);
    if (!profile) throw new Error(`本机 Chrome 中没有找到已登录的 ${account.email}`);
    return {
      accountId: account.id,
      email: account.email,
      browserId: account.browserId,
      profileId: profile.id,
      profileName: profile.name,
      sourceCookieUpdatedAtMs: Number(profile.cookieUpdatedAtMs || 0),
      lastImportedCookieUpdatedAtMs: Number(account.lastImportedCookieUpdatedAtMs || 0),
      startUrl: GEMINI_URL,
      warning: "将关闭普通 Chrome、更新谷歌浏览器登录态，验证邮箱正确后启动 Gemini。"
    };
  });
  ipcMain.handle("agent:pool-sync", async (_event, request) => {
    if (request?.confirmed !== true) throw new Error("同步本机登录态前需要明确确认");
    return syncPoolAccountFromLocalProfile(
      request.accountId,
      request.profileId,
      { startAfter: true, startUrl: request.startUrl || GEMINI_URL }
    );
  });
  ipcMain.handle("agent:pool-bind-plan", async (_event, profileId) => {
    const config = await readConfig();
    const pool = normalizePoolConfig(config.browserAct?.accountPool);
    const account = pool.accounts.find(item => item.profileId === String(profileId || ""));
    if (!account?.email) throw new Error("没有找到这个 Chrome 账号");
    const localPart = account.email.split("@")[0].replace(/[^A-Za-z0-9-]/g, "-").slice(0, 32);
    const suffix = Date.now().toString(36).slice(-5);
    return {
      accountId: account.id,
      profileId: account.profileId,
      email: account.email,
      replacing: Boolean(account.browserId),
      name: `heyi-google-${localPart}-${suffix}`.slice(0, 80),
      type: "chrome"
    };
  });
  ipcMain.handle("agent:pool-bind", async (_event, request) => {
    if (request?.confirmed !== true) throw new Error("创建谷歌浏览器实例前需要明确确认");
    let config = await readConfig();
    const pool = normalizePoolConfig(config.browserAct?.accountPool);
    const account = pool.accounts.find(item =>
      item.id === String(request.accountId || "")
      && item.profileId === String(request.profileId || "")
    );
    if (!account?.email) throw new Error("待绑定账号已经发生变化，请重新点击绑定");
    const localPart = account.email.split("@")[0].replace(/[^A-Za-z0-9-]/g, "-").slice(0, 32);
    const expectedPrefix = `heyi-google-${localPart}-`;
    const name = String(request.name || "").trim();
    if (!name.startsWith(expectedPrefix) || !/^[A-Za-z0-9._-]{1,80}$/.test(name)) {
      throw new Error("谷歌浏览器实例名称无效，请重新点击绑定");
    }
    const description = `赫逸自动化助手独立账号环境；固定绑定 ${account.email}；用于智能体按邮箱执行自动化`;
    const created = await browserActClient.createChromeBrowser(name, description);
    await accountPoolManager.releaseAccount(account.id);
    const accounts = pool.accounts.map(item => item.id === account.id
      ? { ...item, browserId: created.browserId }
      : item
    );
    config = await writeConfig({
      ...config,
      browserAct: {
        ...config.browserAct,
        accountPool: { ...pool, accounts }
      }
    });
    const probe = await browserActClient.probe();
    const assist = await accountPoolManager.startLoginAssist(
      account.id, config.browserAct.accountPool, probe.browsers || []
    );
    if (assist.url) {
      const parsed = new URL(assist.url);
      if (["http:", "https:"].includes(parsed.protocol)) await shell.openExternal(parsed.toString());
    }
    return { accountId: account.id, email: account.email, browserId: created.browserId, assist };
  });
  ipcMain.handle("agent:pool-add-plan", () => {
    const suffix = Date.now().toString(36).slice(-6);
    return {
      name: `heyi-google-account-${suffix}`,
      type: "chrome",
      description: "赫逸自动化助手 Google 账号；登录后自动识别邮箱并供智能体调用"
    };
  });
  ipcMain.handle("agent:pool-add", async (_event, request) => {
    if (request?.confirmed !== true) throw new Error("创建谷歌浏览器实例前需要明确确认");
    const name = String(request.name || "").trim();
    if (!/^heyi-google-account-[A-Za-z0-9]{1,12}$/.test(name)) {
      throw new Error("添加账号请求已失效，请重新点击添加");
    }
    const description = "赫逸自动化助手 Google 账号；登录后自动识别邮箱并供智能体调用";
    const created = await browserActClient.createChromeBrowser(name, description);
    const token = crypto.randomUUID();
    const sessionId = `heyi_add_${Date.now().toString(36)}_${crypto.randomBytes(3).toString("hex")}`;
    await browserActClient.ensureSession({
      browserId: created.browserId,
      sessionId,
      headed: false,
      allowedDomains: []
    }, "https://myaccount.google.com/");
    const assist = await browserActClient.remoteAssist(
      sessionId,
      "登录 Google 账号；赫逸自动化助手将在完成后识别邮箱并添加账号"
    );
    const pending = {
      token,
      browserId: created.browserId,
      sessionId,
      name,
      url: assist.url,
      createdAt: new Date().toISOString()
    };
    pendingAccountAdds.set(token, pending);
    if (assist.url) {
      const parsed = new URL(assist.url);
      if (["http:", "https:"].includes(parsed.protocol)) await shell.openExternal(parsed.toString());
    }
    addRuntimeEvent("已创建谷歌浏览器账号环境，等待完成 Google 登录", "warning");
    return { token, browserId: created.browserId, url: assist.url };
  });
  ipcMain.handle("agent:pool-add-complete", async (_event, token) => {
    const pending = pendingAccountAdds.get(String(token || ""));
    if (!pending) throw new Error("没有等待确认的账号登录，请重新点击添加账号");
    let verificationSession = pending.sessionId;
    try {
      await browserActClient.navigateSession(verificationSession, "https://myaccount.google.com/");
    } catch {
      verificationSession = `heyi_verify_${Date.now().toString(36)}_${crypto.randomBytes(3).toString("hex")}`;
      await browserActClient.ensureSession({
        browserId: pending.browserId,
        sessionId: verificationSession,
        headed: false,
        allowedDomains: []
      }, "https://myaccount.google.com/");
    }
    const detected = await browserActClient.detectGoogleAccount(verificationSession);
    if (!detected.ok) throw new Error(detected.reason || "尚未检测到 Google 登录邮箱");
    await Promise.allSettled([
      browserActClient.closeSession(pending.sessionId),
      verificationSession !== pending.sessionId
        ? browserActClient.closeSession(verificationSession)
        : Promise.resolve()
    ]);
    pendingAccountAdds.delete(pending.token);
    let config = await migrateToBrowserActAccounts(await readConfig());
    const pool = normalizePoolConfig(config.browserAct?.accountPool);
    const existing = pool.accounts.find(account => account.email === detected.email);
    if (existing) await accountPoolManager.releaseAccount(existing.id);
    const nextAccount = existing
      ? { ...existing, label: existing.label || detected.email, browserId: pending.browserId, profileId: "", enabled: true }
      : {
          id: `google-${crypto.randomUUID().slice(0, 12)}`,
          label: detected.email.split("@")[0],
          email: detected.email,
          browserId: pending.browserId,
          profileId: "",
          aliases: [],
          enabled: true
        };
    const accounts = existing
      ? pool.accounts.map(account => account.id === existing.id ? nextAccount : account)
      : [...pool.accounts, nextAccount];
    config = await writeConfig({
      ...config,
      browserAct: {
        ...config.browserAct,
        accountPool: {
          ...pool,
          poolSize: Math.min(5, Math.max(pool.poolSize, accounts.length)),
          accounts
        }
      }
    });
    addRuntimeEvent(`谷歌浏览器账号已添加：${detected.email}`, "success");
    return { email: detected.email, browserId: pending.browserId, config };
  });
  ipcMain.handle("agent:pool-add-cancel", async (_event, request) => {
    if (request?.confirmed !== true) throw new Error("取消添加账号前需要明确确认");
    const pending = pendingAccountAdds.get(String(request.token || ""));
    if (!pending) throw new Error("这个待添加账号已不存在");
    await browserActClient.closeSession(pending.sessionId).catch(() => {});
    const deletion = await browserActClient.deleteBrowser(
      pending.browserId,
      "取消赫逸自动化助手中未完成登录的临时 Google 账号环境"
    );
    const deleteUrl = deletion.delete_url || deletion.deleteUrl || "";
    if (deleteUrl) {
      const parsed = new URL(deleteUrl);
      if (["http:", "https:"].includes(parsed.protocol)) await shell.openExternal(parsed.toString());
      throw new Error("请先在谷歌浏览器页面完成环境删除确认");
    }
    pendingAccountAdds.delete(pending.token);
    addRuntimeEvent("已取消未完成的 Google 账号添加", "warning");
    return { ok: true, browserId: pending.browserId };
  });
  ipcMain.handle("agent:pool-account-delete", async (_event, request) => {
    if (request?.confirmed !== true) throw new Error("删除账号前需要明确确认");
    return enqueueAccountMutation(async () => {
      let config = await migrateToBrowserActAccounts(await readConfig());
      const pool = normalizePoolConfig(config.browserAct?.accountPool);
      const account = pool.accounts.find(item => item.id === String(request.accountId || ""));
      if (!account?.browserId) throw new Error("账号已删除，请重新检测");
      await accountPoolManager.releaseAccount(account.id);

      let probe = await browserActClient.probe();
      const browserExists = probe.browsers.some(browser => browser.id === account.browserId);
      if (browserExists) {
        let deletion;
        try {
          deletion = await browserActClient.deleteBrowser(
            account.browserId,
            `从赫逸自动化助手移除 Google 账号 ${account.email} 及其不再使用的独立环境`
          );
        } catch (error) {
          probe = await browserActClient.probe();
          if (probe.browsers.some(browser => browser.id === account.browserId)) throw error;
        }
        const deleteUrl = deletion?.delete_url || deletion?.deleteUrl || "";
        if (deleteUrl) {
          const parsed = new URL(deleteUrl);
          if (["http:", "https:"].includes(parsed.protocol)) await shell.openExternal(parsed.toString());
          throw new Error("请在打开的页面确认删除");
        }
      }

      config = await migrateToBrowserActAccounts(await readConfig());
      const latestPool = normalizePoolConfig(config.browserAct?.accountPool);
      const accounts = latestPool.accounts.filter(item =>
        item.id !== account.id && item.browserId !== account.browserId
      );
      config = await writeConfig({
        ...config,
        browserAct: {
          ...config.browserAct,
          browserId: config.browserAct.browserId === account.browserId
            ? accounts[0]?.browserId || ""
            : config.browserAct.browserId,
          accountPool: {
            ...latestPool,
            poolSize: Math.max(1, Math.min(latestPool.poolSize, accounts.length || 1)),
            accounts
          }
        }
      });
      addRuntimeEvent(`已删除账号：${account.email}`, "warning");
      return { ok: true, email: account.email, browserId: account.browserId, config };
    });
  });
}

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  registerIpc();
  createWindow();
  createTray();
  startLocalPickerServer();
  automationTick();
  scheduleHeartbeat(0);
  browserActSessionTick();
  setInterval(automationTick, 2000).unref();
  setInterval(browserActSessionTick, 5000).unref();
  setInterval(() => {
    accountPoolHealthTick().catch(error =>
      addRuntimeEvent(`账号池登录巡检失败：${error.message}`, "warning")
    );
  }, 60000).unref();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") return;
  app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
  else mainWindow.show();
});

app.on("before-quit", (event) => {
  quitting = true;
  if (heartbeatTimer) clearTimeout(heartbeatTimer);
  pickerServer?.close();
  const pool = accountPoolManager.snapshot();
  if (poolShutdownStarted || (pool.activeCount === 0 && pool.loginAssistCount === 0)) return;
  event.preventDefault();
  poolShutdownStarted = true;
  accountPoolManager.shutdown().finally(() => app.quit());
});
