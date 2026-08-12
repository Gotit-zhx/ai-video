const setupView = document.querySelector("#setup-view");
const agentView = document.querySelector("#agent-view");
const setupForm = document.querySelector("#setup-form");
const setupError = document.querySelector("#setup-error");
const settingsDialog = document.querySelector("#settings-dialog");
let currentSnapshot;
let probing = false;
let refreshQueued = false;
const pendingLoginFailures = new Set();
let chromeLoginOpen = false;
let enrollmentOpen = false;
let browserActAuthSequence = 0;

function friendlyError(error) {
  const message = String(error?.message || error || "操作失败");
  const jsonStart = message.indexOf("{");
  if (jsonStart >= 0) {
    try {
      const detail = JSON.parse(message.slice(jsonStart));
      if (detail.error_code === 230201) return "账号环境已不存在，请重新检测。";
      if (detail.error_code === 230205) return "请关闭普通 Chrome 后重试。";
      if (detail.error) return String(detail.error).split(".")[0] || "操作失败，请重试。";
    } catch {}
  }
  return message
    .replace(/^Error invoking remote method '[^']+':\s*Error:\s*/i, "")
    .replace(/^Error:\s*/i, "")
    .split("\n")[0]
    .slice(0, 120);
}

function showError(element, error) {
  element.textContent = friendlyError(error);
  element.hidden = false;
}

function clearError(element) {
  element.textContent = "";
  element.hidden = true;
}

const idleButtonContent = new WeakMap();

function beginButtonBusy(button, label = "处理中…") {
  if (!button || button.disabled || button.dataset.busy === "true") return false;
  idleButtonContent.set(button, button.innerHTML);
  button.dataset.busy = "true";
  button.disabled = true;
  button.classList.add("is-loading");
  button.setAttribute("aria-busy", "true");
  button.innerHTML = `<span class="button-spinner" aria-hidden="true"></span><span>${escapeHtml(label)}</span>`;
  return true;
}

function endButtonBusy(button) {
  if (!button || button.dataset.busy !== "true") return;
  button.innerHTML = idleButtonContent.get(button) || button.textContent || "重试";
  idleButtonContent.delete(button);
  button.dataset.busy = "false";
  button.disabled = false;
  button.classList.remove("is-loading");
  button.removeAttribute("aria-busy");
}

function setConnectionNode(name, connection) {
  const node = document.querySelector(`[data-node="${name}"]`);
  if (!node || !connection) return;
  node.classList.remove("online", "warning", "offline");
  node.classList.add(connection.state);
  document.querySelector(`#${name}-state`).textContent = connection.label;
}

function renderEvents(events) {
  const list = document.querySelector("#event-list");
  list.innerHTML = "";
  for (const event of events.slice(0, 3)) {
    const item = document.createElement("li");
    const time = document.createElement("time");
    const dot = document.createElement("i");
    const message = document.createElement("span");
    time.textContent = event.time;
    message.textContent = event.message;
    item.append(time, dot, message);
    list.append(item);
  }
}

function renderAccountPoolLegacy(pool = {}) {
  const slots = document.querySelector("#pool-slots");
  const active = pool.active || [];
  const authRequired = pool.authRequired || [];
  const authByAccountId = new Map(authRequired.map(account => [account.accountId, account]));
  const localAccounts = pool.localAccounts || [];
  const pendingAccounts = pool.pendingAccounts || [];
  const pendingProfileImports = pool.pendingProfileImports || [];
  const importableProfiles = pool.importableProfiles || [];
  const poolSize = pool.poolSize || 5;
  const accountTotal = localAccounts.length + pendingAccounts.length;
  document.querySelector("#pool-count").innerHTML = `${pool.activeCount || 0}<small>/${accountTotal}</small>`;
  document.querySelector("#pool-state-label").textContent = pool.starting
    ? "正在启动"
    : pool.healthChecking ? "正在检测"
    : pendingAccounts.length ? `${pendingAccounts.length} 个等待登录`
    : authRequired.length ? `${authRequired.length} 个需要登录`
    : active.length ? `${active.length} 个运行中` : "等待添加账号";
  document.querySelector("#pool-configured-count").textContent = `已导入 ${localAccounts.length} 个账号`;
  slots.style.setProperty("--pool-size", String(Math.min(5, Math.max(1, accountTotal))));
  const statusCopy = {
    RUNNING: ["运行中", "active-slot"],
    CONTROLLED: ["启动中", "active-slot"],
    READY: ["可启动", "ready-slot"],
    ATTENTION: ["需要登录", "attention-slot"],
    PENDING: ["等待登录", "discovered-slot"]
  };
  const cards = [
    ...localAccounts,
    ...pendingAccounts.map(item => ({
      profileName: "新 Google 账号",
      email: "在谷歌浏览器页面完成登录",
      status: "PENDING",
      pendingToken: item.token
    }))
  ];
  slots.innerHTML = cards.map((account, index) => {
    const auth = authByAccountId.get(account.accountId);
    const [defaultStatus, className] = statusCopy[account.status] || statusCopy.DISCOVERED;
    const status = defaultStatus;
    const initial = String(account.profileName || account.email || "G").trim().slice(0, 1).toUpperCase();
    return `<article class="pool-slot account-slot ${className}">
      <span class="account-avatar">${escapeHtml(initial)}</span>
      <div><strong>${escapeHtml(account.profileName || account.email)}</strong><small>${escapeHtml(account.email)}</small></div>
      <span class="account-status">${status}</span>
      ${account.sessionId
        ? `<button class="account-close-action" type="button" data-pool-close="${escapeHtml(account.sessionId)}">关闭</button>`
        : account.pendingToken
          ? `<button class="account-bind-action" type="button" data-pool-add-complete="${escapeHtml(account.pendingToken)}">登录完成</button>`
          : account.loginAssistActive
            ? `<button class="account-bind-action" type="button" data-pool-login-complete="${escapeHtml(account.accountId)}">登录完成</button>`
            : account.status === "ATTENTION"
              ? `<button class="account-bind-action" type="button" data-pool-login="${escapeHtml(account.accountId)}">重新登录</button>`
              : ""}
    </article>`;
  }).join("");
  const waiting = pool.waiting || [];
  document.querySelector("#pool-queue-count").textContent = `${waiting.length} 个候补`;
  document.querySelector("#pool-flow-status").textContent = active.length
    ? `仅把邮箱验证通过的窗口计入 ${poolSize} 个活跃席位`
    : authRequired.length && authRequired.some(account => account.recoverable)
      ? "优先复用已保存 Profile；只有 Profile 本身失效才需要重新登录"
      : `启动后先验证 Google 邮箱，再计入 ${poolSize} 个活跃席位`;
  document.querySelector("#pool-waiting").innerHTML = waiting.length
    ? waiting.map(account => `<span>${escapeHtml(account.email)}</span>`).join("")
    : "<em>没有可用候补账号</em>";
  const authPanel = document.querySelector("#pool-auth-required");
  authPanel.hidden = true;
  authPanel.innerHTML = "";
  const discoveredPanel = document.querySelector("#pool-discovered-accounts");
  discoveredPanel.hidden = true;
  discoveredPanel.innerHTML = "";
  const error = document.querySelector("#pool-error");
  if (authRequired.length) {
    showError(error, `${authRequired.length} 个账号需要重新登录。`);
  } else if (pool.lastError) {
    showError(error, "账号窗口暂时无法启动，请稍后重新检测。");
  } else clearError(error);
}

function renderAccountPool(pool = {}) {
  const slots = document.querySelector("#pool-slots");
  const enrollment = document.querySelector("#pool-enrollment");
  const active = pool.active || [];
  const authRequired = pool.authRequired || [];
  const localAccounts = pool.localAccounts || [];
  const pendingAccounts = pool.pendingAccounts || [];
  const pendingProfileImports = pool.pendingProfileImports || [];
  const importableProfiles = pool.importableProfiles || [];
  const poolSize = pool.poolSize || 5;
  const authByAccountId = new Map(authRequired.map(account => [account.accountId, account]));
  const syncRequiredCount = localAccounts.filter(account => account.status === "SYNC_REQUIRED").length;
  const syncingCount = localAccounts.filter(account => account.status === "SYNCING").length;
  const operator = document.querySelector(".pool-operator");
  operator.hidden = !localAccounts.length;
  document.querySelector(".pool-workspace").classList.toggle("is-empty", !localAccounts.length);

  document.querySelector("#pool-count").innerHTML = `${pool.activeCount || 0}<small>/${localAccounts.length}</small>`;
  document.querySelector("#pool-state-label").textContent = pool.starting
    ? "正在启动"
    : syncingCount
      ? `${syncingCount} 个正在同步`
    : pool.healthChecking
      ? "正在检测"
      : syncRequiredCount
        ? `${syncRequiredCount} 个账号可供调度`
      : authRequired.length
        ? `${authRequired.length} 个登录失效`
        : active.length
          ? `${active.length} 个运行中`
          : localAccounts.length
            ? "已添加，等待启动"
            : "等待添加账号";
  document.querySelector("#pool-configured-count").textContent = `已导入 ${localAccounts.length} 个账号`;
  slots.style.setProperty("--pool-size", String(Math.min(5, Math.max(1, localAccounts.length))));
  const statusCopy = {
    RUNNING: ["运行中", "active-slot"],
    CONTROLLED: ["启动中", "active-slot"],
    READY: ["可启动", "ready-slot"],
    ATTENTION: ["登录失效", "attention-slot"],
    SYNC_REQUIRED: ["可供调度", "ready-slot"],
    SYNCING: ["正在同步", "attention-slot"],
    LOGIN_REQUIRED: ["需要本人登录", "attention-slot"]
  };
  slots.innerHTML = localAccounts.map(account => {
    const [status, className] = statusCopy[account.status] || statusCopy.READY;
    const initial = String(account.profileName || account.email || "G").trim().slice(0, 1).toUpperCase();
    return `<article class="pool-slot account-slot ${className}">
      <div class="account-card-main">
        <span class="account-avatar">${escapeHtml(initial)}</span>
        <div class="account-identity"><strong>${escapeHtml(account.profileName || account.email)}</strong><small>${escapeHtml(account.email)}</small></div>
      </div>
      <div class="account-card-footer">
        <span class="account-status"><i aria-hidden="true"></i>${status}</span>
        <button class="account-delete-action" type="button" data-pool-delete="${escapeHtml(account.accountId)}" data-pool-delete-email="${escapeHtml(account.email)}">移除</button>
      </div>
    </article>`;
  }).join("");

  if (pendingProfileImports.length) {
    enrollmentOpen = true;
    const pending = pendingProfileImports[0];
    enrollment.hidden = false;
    enrollment.classList.remove("is-failed");
    enrollment.innerHTML = `<div class="enrollment-copy">
      <span class="enrollment-dot" aria-hidden="true"></span>
      <div><span class="enrollment-step">第 2 步 / 共 2 步</span>
      <strong>导入并验证 ${escapeHtml(pending.email)}</strong>
      <small>助手会自动关闭普通 Chrome，从“${escapeHtml(pending.profileName)}”复制登录状态；邮箱验证正确后才会添加。</small></div>
    </div>
    <div class="enrollment-actions">
      <button class="enrollment-verify" type="button" data-profile-import="${escapeHtml(pending.token)}">关闭 Chrome 并完成</button>
      <button class="enrollment-cancel" type="button" data-profile-import-cancel="${escapeHtml(pending.token)}">取消</button>
    </div>`;
  } else if (enrollmentOpen && importableProfiles.length) {
    chromeLoginOpen = false;
    enrollment.hidden = false;
    enrollment.classList.remove("is-failed");
    enrollment.innerHTML = `<div class="enrollment-profile-stack">
      <div class="enrollment-copy">
        <span class="enrollment-dot" aria-hidden="true"></span>
        <div><span class="enrollment-step">第 1 步 / 共 2 步</span>
        <strong>选择要添加的 Google 账号</strong>
        <small>检测到 ${importableProfiles.length} 个新账号。每个邮箱只会创建一张账号卡。</small></div>
      </div>
      <button class="enrollment-cancel enrollment-close" type="button" data-enrollment-close>收起</button>
      <div class="enrollment-profile-list">
        ${importableProfiles.map(profile => `<div class="enrollment-profile-item">
          <span><strong>${escapeHtml(profile.accountEmail)}</strong><small>${escapeHtml(profile.name)}</small></span>
          <button class="enrollment-verify" type="button" data-local-profile-add="${escapeHtml(profile.id)}">创建环境</button>
        </div>`).join("")}
      </div>
    </div>`;
  } else if (chromeLoginOpen) {
    enrollment.hidden = false;
    enrollment.classList.remove("is-failed");
    enrollment.innerHTML = `<div class="enrollment-copy">
      <span class="enrollment-dot" aria-hidden="true"></span>
      <div><strong>先在普通 Chrome 中完成账号登录</strong>
      <small>登录完成后回到助手点击“重新检测”，新邮箱会自动出现在第 1 步。</small></div>
    </div>`;
  } else {
    enrollment.hidden = true;
    enrollment.classList.remove("is-failed");
    enrollment.innerHTML = "";
    pendingLoginFailures.clear();
  }

  const waiting = pool.waiting || [];
  document.querySelector("#pool-queue-count").textContent = `${waiting.length} 个候补`;
  document.querySelector("#pool-flow-status").textContent = active.length
    ? `${active.length} 运行 · ${waiting.length} 待命`
    : `${waiting.length} 个账号待命`;
  document.querySelector("#pool-waiting").innerHTML = waiting.length
    ? waiting.map(account => `<span>${escapeHtml(account.email)}</span>`).join("")
    : "<em>无</em>";
  const authPanel = document.querySelector("#pool-auth-required");
  authPanel.hidden = true;
  authPanel.innerHTML = "";
  const discoveredPanel = document.querySelector("#pool-discovered-accounts");
  discoveredPanel.hidden = true;
  discoveredPanel.innerHTML = "";
  const error = document.querySelector("#pool-error");
  if (authRequired.length) {
    showError(error, `${authRequired.length} 个账号需要先在本机 Chrome 登录。`);
  } else if (pool.lastError) {
    showError(error, "账号窗口暂时无法启动，请稍后重新检测。");
  } else {
    clearError(error);
  }
}

function renderPoolAccountRows(accounts = [], browsers = [], profiles = []) {
  const container = document.querySelector("#pool-account-rows");
  container.innerHTML = accounts.map((account, index) => {
    const selectedProfileId = account.profileId
      || profiles.find(profile => profile.accountEmail === String(account.email || "").toLowerCase())?.id
      || "";
    return `
      <article class="pool-account-row" data-pool-account-id="${escapeHtml(account.id || `account-${index + 1}`)}">
        <label><span>名称</span><input data-pool-field="label" value="${escapeHtml(account.label || "")}" placeholder="运营账号 ${index + 1}"></label>
        <label><span>邮箱</span><input data-pool-field="email" type="email" value="${escapeHtml(account.email || "")}" placeholder="user@gmail.com"></label>
        <label><span>谷歌浏览器环境</span><select data-pool-field="browserId"><option value="">请选择独立环境</option>${browsers.map(browser => `<option value="${escapeHtml(browser.id)}" ${browser.id === account.browserId ? "selected" : ""}>${escapeHtml(browser.name)} · ${escapeHtml(browser.id)}</option>`).join("")}</select></label>
        <label><span>登录来源 Profile</span><select data-pool-field="profileId"><option value="">仅检测，不自动恢复</option>${profiles.map(profile => `<option value="${escapeHtml(profile.id)}" ${profile.id === selectedProfileId ? "selected" : ""}>${escapeHtml(profile.accountEmail || profile.name)} · ${escapeHtml(profile.name)}</option>`).join("")}</select></label>
        <label><span>别名</span><input data-pool-field="aliases" value="${escapeHtml((account.aliases || []).join("，"))}" placeholder="账号一，客服A"></label>
        <label class="pool-enabled"><input data-pool-field="enabled" type="checkbox" ${account.enabled !== false ? "checked" : ""}><span>启用</span></label>
        <button type="button" class="pool-account-remove" aria-label="删除账号">×</button>
      </article>`;
  }).join("");
}

function collectPoolAccounts() {
  return [...document.querySelectorAll("[data-pool-account-id]")].map((row, index) => {
    const field = name => row.querySelector(`[data-pool-field="${name}"]`);
    const id = row.dataset.poolAccountId || `account-${index + 1}`;
    const existing = (currentSnapshot?.config?.browserAct?.accountPool?.accounts || [])
      .find(account => account.id === id) || {};
    return {
      ...existing,
      id,
      label: field("label").value,
      email: field("email").value,
      browserId: field("browserId").value,
      profileId: field("profileId").value,
      aliases: field("aliases").value.split(/[,，]/).map(value => value.trim()).filter(Boolean),
      enabled: field("enabled").checked
    };
  });
}

function render(snapshot) {
  currentSnapshot = snapshot;
  const { config, device, connections, tasks, events, accountPool } = snapshot;
  const isReady = connections.platform.state === "online" && connections.agent.state === "online";

  setupView.hidden = config.runMode !== "unpaired";
  agentView.hidden = config.runMode === "unpaired";
  if (config.runMode === "unpaired") {
    document.querySelector("#setup-agent-name").value = config.agentName || `${device.hostname} 执行器`;
    document.querySelector("#setup-platform-url").value = config.platformUrl;
    return;
  }

  setConnectionNode("platform", connections.platform);
  setConnectionNode("agent", connections.agent);
  setConnectionNode("feikua", connections.feikua);
  setConnectionNode("browser-act", connections.browserAct);
  const browserSummary = connections.browser || { state: "offline", label: "没有可用执行器" };
  document.querySelector("#connection-title").textContent = isReady
    ? "这台电脑已准备就绪"
    : "有一项连接需要处理";
  const executorCount = [connections.feikua, connections.browserAct]
    .filter(connection => connection?.state === "online").length;
  const executorLabel = executorCount === 2
    ? "双执行器在线"
    : executorCount === 1
      ? `${browserSummary.label}`
      : "等待执行器";
  document.querySelector("#header-state").innerHTML = `<i></i>${config.acceptJobs ? executorLabel : "已暂停接单"}`;
  document.querySelector("#readiness-title").textContent = config.acceptJobs ? "准备接收任务" : "已暂停接收任务";
  document.querySelector("#readiness-copy").textContent = config.acceptJobs
    ? `平台下发任务后按 Skill 路由执行，当前 ${executorCount}/2 个执行器可用。`
    : "当前任务不受影响，平台不会再分配新任务。";
  document.querySelector("#browser-state").textContent = browserSummary.label;
  const acceptButton = document.querySelector("#accept-jobs-button");
  acceptButton.classList.toggle("paused", !config.acceptJobs);
  document.querySelector("#accept-jobs-label").textContent = config.acceptJobs ? "正在接收任务" : "已暂停接单";

  document.querySelector("#device-name").textContent = config.agentName || device.hostname;
  document.querySelector("#device-platform").textContent = `${device.platform} · ${device.architecture}`;
  document.querySelector("#device-version").textContent = `v${device.version}`;
  document.querySelector("#task-count").textContent = `${tasks.length} 个任务`;
  const taskList = document.querySelector("#task-list");
  if (tasks.length) {
    taskList.innerHTML = tasks.map((task) => `
      <article class="local-task-row">
        <span class="local-task-spinner"></span>
        <div><strong>${task.title}</strong><p>正在本机执行，过程会持续回传平台</p></div>
        <em>执行中</em>
      </article>
    `).join("");
  } else {
    taskList.innerHTML = `
      <div class="task-empty">
        <span aria-hidden="true">↘</span>
        <strong>等待平台下发任务</strong>
        <p>任务到达后，助手会先校验店铺和浏览器环境，再开始操作。</p>
      </div>`;
  }

  const feikua = connections.feikua;
  document.querySelector("#store-count").textContent = feikua.state === "online"
    ? `${feikua.storeCount} 个店铺`
    : "未连接";
  const detail = document.querySelector("#feikua-detail");
  detail.classList.toggle("online", feikua.state === "online");
  detail.querySelector("strong").textContent = feikua.state === "online" ? "飞跨客户端已连接" : "未连接飞跨客户端";
  detail.querySelector("p").textContent = feikua.state === "online"
    ? `已识别 ${feikua.storeCount} 个可用店铺环境`
    : (feikua.detail || "请先启动并登录飞跨客户端");

  const browserAct = connections.browserAct;
  document.querySelector("#browser-act-count").textContent = browserAct.state === "online"
    ? `${browserAct.browserCount} 个实例`
    : "未就绪";
  const browserActDetail = document.querySelector("#browser-act-detail");
  browserActDetail.classList.toggle("online", browserAct.state === "online");
  browserActDetail.querySelector("strong").textContent = browserAct.state === "online"
    ? "谷歌浏览器可用"
    : "谷歌浏览器未就绪";
  browserActDetail.querySelector("p").textContent = browserAct.state === "online"
    ? (browserAct.selectedBrowser
      ? `默认：${browserAct.selectedBrowser.name} · ${browserAct.selectedBrowser.type} · ${browserAct.selectedBrowser.proxyConfigured ? "已配置代理" : "直连"}`
      : `已识别 ${browserAct.browserCount} 个实例，请选择默认实例`)
    : (browserAct.detail || "请安装谷歌浏览器执行服务");

  const browserActAuthorized = browserAct.state === "online";
  const authDot = document.querySelector("#browser-act-auth-dot");
  authDot.classList.toggle("online", browserActAuthorized);
  document.querySelector("#browser-act-auth-state").textContent = browserActAuthorized ? "已连接" : "未连接";
  document.querySelector("#browser-act-auth-title").textContent = browserActAuthorized
    ? "谷歌浏览器已授权"
    : "需要网页授权";
  document.querySelector("#browser-act-auth-detail").textContent = browserActAuthorized
    ? `已识别 ${browserAct.browserCount || 0} 个环境、${accountPool.localProfiles?.length || 0} 个本机 Chrome 账号`
    : (browserAct.detail || "点击连接后在网页完成授权");
  const authButton = document.querySelector("#browser-act-auth-button");
  if (authButton.dataset.busy !== "true") {
    authButton.textContent = browserActAuthorized ? "重新连接" : "连接谷歌浏览器";
  }

  renderEvents(events);
  renderAccountPool(accountPool);
  document.querySelector("#settings-agent-name").value = config.agentName;
  document.querySelector("#settings-platform-url").value = config.platformUrl;
  document.querySelector("#settings-executor-preference").value =
    config.browserAct?.executorPreference || "AUTO";
  const browserSelect = document.querySelector("#settings-browser-id");
  browserSelect.innerHTML = '<option value="">请选择浏览器实例</option>'
    + (browserAct.browsers || []).map(browser =>
      `<option value="${escapeHtml(browser.id)}">${escapeHtml(browser.name)} · ${escapeHtml(browser.type)}${browser.proxyConfigured ? " · 已配置代理" : ""}</option>`
    ).join("");
  browserSelect.value = config.browserAct?.browserId || "";
  document.querySelector("#settings-proxy-policy").value = config.browserAct?.proxyPolicy || "DIRECT";
  document.querySelector("#settings-required-region").value = config.browserAct?.requiredRegion || "";
  const poolConfig = config.browserAct?.accountPool || {};
  document.querySelector("#settings-pool-size").value = poolConfig.poolSize || 5;
  document.querySelector("#settings-pool-url").value = poolConfig.startUrl || "https://myaccount.google.com/";
  renderPoolAccountRows(
    poolConfig.accounts || [], browserAct.browsers || [], browserAct.localProfiles || []
  );
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>'"]/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  })[character]);
}

async function refresh() {
  if (probing) {
    refreshQueued = true;
    return;
  }
  probing = true;
  const button = document.querySelector("#probe-button");
  button.disabled = true;
  button.textContent = "检测中…";
  try {
    do {
      refreshQueued = false;
      render(await window.heyiAgent.probe());
    } while (refreshQueued);
  } finally {
    probing = false;
    button.disabled = false;
    button.textContent = "重新检测";
  }
}

setupForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  clearError(setupError);
  try {
    await window.heyiAgent.startLocal({
      agentName: document.querySelector("#setup-agent-name").value,
      platformUrl: document.querySelector("#setup-platform-url").value
    });
    await refresh();
  } catch (error) {
    showError(setupError, error);
  }
});

document.querySelector("#probe-button").addEventListener("click", refresh);
document.querySelector("#accept-jobs-button").addEventListener("click", async () => {
  await window.heyiAgent.toggleAcceptJobs(!currentSnapshot.config.acceptJobs);
  await refresh();
});
document.querySelector("#open-logs-button").addEventListener("click", () => window.heyiAgent.openLogs());
document.querySelector("#settings-button").addEventListener("click", () => settingsDialog.showModal());
document.querySelector("#settings-close").addEventListener("click", () => settingsDialog.close());

document.querySelector("#browser-act-website-button").addEventListener("click", () => {
  window.heyiAgent.openBrowserActWebsite();
});

document.querySelector("#browser-act-auth-button").addEventListener("click", async () => {
  const button = document.querySelector("#browser-act-auth-button");
  const errorBox = document.querySelector("#browser-act-auth-error");
  if (!beginButtonBusy(button, "等待网页授权…")) return;
  clearError(errorBox);
  const sequence = ++browserActAuthSequence;
  let busyEnded = false;
  try {
    await window.heyiAgent.startBrowserActAuthorization();
    document.querySelector("#browser-act-auth-state").textContent = "等待授权";
    document.querySelector("#browser-act-auth-title").textContent = "请在打开的谷歌浏览器授权页确认";
    document.querySelector("#browser-act-auth-detail").textContent = "完成后助手会自动连接并扫描本机 Chrome 账号";
    for (let attempt = 0; attempt < 100 && sequence === browserActAuthSequence; attempt += 1) {
      await new Promise(resolve => setTimeout(resolve, 3000));
      const result = await window.heyiAgent.pollBrowserActAuthorization();
      if (["success", "completed", "authorized", "registered"].includes(result.status)) {
        endButtonBusy(button);
        busyEnded = true;
        await refresh();
        return;
      }
      if (["expired", "failed", "error", "cancelled", "canceled"].includes(result.status)) {
        throw new Error(result.message || "网页授权未完成，请重新连接");
      }
    }
    if (sequence === browserActAuthSequence) throw new Error("网页授权等待超时，请重新连接");
  } catch (error) {
    showError(errorBox, error);
  } finally {
    if (!busyEnded) endButtonBusy(button);
  }
});

document.querySelector("#settings-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const errorBox = document.querySelector("#settings-error");
  clearError(errorBox);
  try {
    await window.heyiAgent.saveSettings({
      agentName: document.querySelector("#settings-agent-name").value,
      platformUrl: document.querySelector("#settings-platform-url").value,
      browserAct: {
        enabled: true,
        executorPreference: document.querySelector("#settings-executor-preference").value,
        browserId: document.querySelector("#settings-browser-id").value,
        proxyPolicy: document.querySelector("#settings-proxy-policy").value,
        requiredRegion: document.querySelector("#settings-required-region").value,
        accountPool: {
          enabled: true,
          autoStart: false,
          poolSize: Number(document.querySelector("#settings-pool-size").value || 5),
          startUrl: document.querySelector("#settings-pool-url").value,
          accounts: collectPoolAccounts()
        }
      }
    });
    settingsDialog.close();
    await refresh();
  } catch (error) {
    showError(errorBox, error);
  }
});

document.querySelector("#pool-add-button").addEventListener("click", async () => {
  const errorBox = document.querySelector("#pool-error");
  clearError(errorBox);
  const button = document.querySelector("#pool-add-button");
  try {
    const importable = currentSnapshot?.accountPool?.importableProfiles || [];
    if (importable.length) {
      enrollmentOpen = true;
      renderAccountPool(currentSnapshot.accountPool);
      return;
    }
    if (!beginButtonBusy(button, "正在打开…")) return;
    await window.heyiAgent.openChromeLogin();
    chromeLoginOpen = true;
    await refresh();
  } catch (error) {
    await refresh();
    showError(errorBox, error);
  } finally {
    endButtonBusy(button);
  }
});

document.querySelector("#pool-enrollment").addEventListener("click", async event => {
  const profileAddButton = event.target.closest("[data-local-profile-add]");
  const profileImportButton = event.target.closest("[data-profile-import]");
  const profileCancelButton = event.target.closest("[data-profile-import-cancel]");
  const verifyButton = event.target.closest("[data-pool-add-complete]");
  const cancelButton = event.target.closest("[data-pool-add-cancel]");
  const closeButton = event.target.closest("[data-enrollment-close]");
  if (closeButton) {
    enrollmentOpen = false;
    renderAccountPool(currentSnapshot?.accountPool || {});
    return;
  }
  if (!profileAddButton && !profileImportButton && !profileCancelButton && !verifyButton && !cancelButton) return;
  const errorBox = document.querySelector("#pool-error");
  clearError(errorBox);
  if (profileAddButton) {
    try {
      const plan = await window.heyiAgent.getLocalProfileCreatePlan(profileAddButton.dataset.localProfileAdd);
      if (!window.confirm(
        `创建 ${plan.email} 的独立环境？\n下一步再导入登录状态。`
      )) return;
      if (!beginButtonBusy(profileAddButton, "正在创建…")) return;
      await window.heyiAgent.createFromLocalProfile({ ...plan, confirmed: true });
      await refresh();
    } catch (error) {
      await refresh();
      showError(errorBox, error);
    } finally {
      endButtonBusy(profileAddButton);
    }
    return;
  }
  if (profileImportButton) {
    if (!window.confirm(
      "关闭普通 Chrome 并导入这个账号？"
    )) return;
    if (!beginButtonBusy(profileImportButton, "正在导入…")) return;
    try {
      await window.heyiAgent.finishLocalProfileImport(profileImportButton.dataset.profileImport);
      await refresh();
    } catch (error) {
      await refresh();
      showError(errorBox, error);
    } finally {
      endButtonBusy(profileImportButton);
    }
    return;
  }
  if (profileCancelButton) {
    if (!window.confirm(
      "取消并删除刚创建的谷歌浏览器环境？\n\n该环境中的 Cookie 和配置将永久删除，无法恢复。"
    )) return;
    if (!beginButtonBusy(profileCancelButton, "正在取消…")) return;
    try {
      await window.heyiAgent.cancelLocalProfileImport(profileCancelButton.dataset.profileImportCancel);
      await refresh();
    } catch (error) {
      await refresh();
      showError(errorBox, error);
    } finally {
      endButtonBusy(profileCancelButton);
    }
    return;
  }
  if (cancelButton) {
    if (!window.confirm(
      "取消这次账号添加？\n\n"
      + "未完成的谷歌浏览器临时环境和其中的登录数据将被永久删除。"
    )) return;
    if (!beginButtonBusy(cancelButton, "正在取消…")) return;
    try {
      await window.heyiAgent.cancelAddingAccount(cancelButton.dataset.poolAddCancel);
      pendingLoginFailures.delete(cancelButton.dataset.poolAddCancel);
      await refresh();
    } catch (error) {
      await refresh();
      showError(errorBox, error);
    } finally {
      endButtonBusy(cancelButton);
    }
    return;
  }
  if (!beginButtonBusy(verifyButton, "正在验证…")) return;
  try {
    await window.heyiAgent.finishAddingAccount(verifyButton.dataset.poolAddComplete);
    pendingLoginFailures.delete(verifyButton.dataset.poolAddComplete);
    await refresh();
  } catch (error) {
    pendingLoginFailures.add(verifyButton.dataset.poolAddComplete);
    await refresh();
    showError(errorBox, "登录未完成：没有检测到 Google 邮箱，此账号尚未加入列表。");
  } finally {
    endButtonBusy(verifyButton);
  }
});

document.querySelector("#pool-slots").addEventListener("click", async event => {
  const deleteButton = event.target.closest("[data-pool-delete]");
  if (deleteButton) {
    const email = deleteButton.dataset.poolDeleteEmail || "这个账号";
    if (!window.confirm(
      `删除 ${email}？账号和登录状态将永久移除。`
    )) return;
    const errorBox = document.querySelector("#pool-error");
    clearError(errorBox);
    if (!beginButtonBusy(deleteButton, "正在删除…")) return;
    try {
      await window.heyiAgent.deletePoolAccount(deleteButton.dataset.poolDelete);
      await refresh();
    } catch (error) {
      await refresh();
      showError(errorBox, error);
    } finally {
      endButtonBusy(deleteButton);
    }
    return;
  }
  const addCompleteButton = event.target.closest("[data-pool-add-complete]");
  const loginButton = event.target.closest("[data-pool-login]");
  const loginCompleteButton = event.target.closest("[data-pool-login-complete]");
  if (addCompleteButton || loginButton || loginCompleteButton) {
    const errorBox = document.querySelector("#pool-error");
    clearError(errorBox);
    const actionButton = addCompleteButton || loginButton || loginCompleteButton;
    const busyLabel = addCompleteButton ? "正在识别…" : loginButton ? "正在打开…" : "正在验证…";
    if (!beginButtonBusy(actionButton, busyLabel)) return;
    try {
      if (addCompleteButton) {
        await window.heyiAgent.finishAddingAccount(addCompleteButton.dataset.poolAddComplete);
      } else if (loginButton) {
        await window.heyiAgent.startPoolLoginAssist(loginButton.dataset.poolLogin);
      } else {
        await window.heyiAgent.finishPoolLoginAssist(loginCompleteButton.dataset.poolLoginComplete);
      }
      await refresh();
    } catch (error) {
      await refresh();
      showError(errorBox, error);
    } finally {
      endButtonBusy(actionButton);
    }
    return;
  }
});

async function recoverPoolAccountFromButton(button) {
  const errorBox = document.querySelector("#pool-error");
  clearError(errorBox);
  try {
    const plan = await window.heyiAgent.getPoolRecoveryPlan(button.dataset.poolRecover);
    const confirmed = window.confirm(
      `复用 ${plan.email} 已保存的登录状态？\n\n`
      + `来源：${plan.profileName}\n目标环境：${plan.browserId}\n\n${plan.warning}`
    );
    if (!confirmed) return;
    if (!beginButtonBusy(button, "正在恢复…")) return;
    await window.heyiAgent.recoverPoolAccount(plan.accountId);
    await refresh();
  } catch (error) {
    await refresh();
    showError(errorBox, error);
  } finally {
    endButtonBusy(button);
  }
}

document.querySelector("#pool-auth-required").addEventListener("click", async event => {
  const button = event.target.closest("[data-pool-recover]");
  if (!button || button.disabled) return;
  await recoverPoolAccountFromButton(button);
});

document.querySelector("#pool-slots").addEventListener("click", async event => {
  const button = event.target.closest("[data-pool-recover]");
  if (!button || button.disabled) return;
  await recoverPoolAccountFromButton(button);
});

document.querySelector("#pool-discovered-accounts").addEventListener("click", event => {
  const button = event.target.closest("[data-pool-discovered-add]");
  if (!button) return;
  const discovered = currentSnapshot?.accountPool?.discoveredAccounts?.find(
    account => account.profileId === button.dataset.poolDiscoveredAdd
  );
  if (!discovered) return;
  const current = collectPoolAccounts();
  const existingIndex = current.findIndex(account => account.profileId === discovered.profileId);
  if (existingIndex >= 0) {
    if (!window.confirm(
      `Profile ${discovered.profileName} 的账号已从 ${current[existingIndex].email || "未知邮箱"} 变为 ${discovered.email}。\n\n确认更新此账号绑定？`
    )) return;
    current[existingIndex] = {
      ...current[existingIndex],
      label: discovered.profileName || current[existingIndex].label,
      email: discovered.email
    };
  } else if (!current.some(account => account.email === discovered.email)) {
    current.push({
      id: `google-${Date.now()}`,
      label: discovered.profileName,
      email: discovered.email,
      browserId: "",
      profileId: discovered.profileId,
      aliases: [],
      enabled: true
    });
  }
  renderPoolAccountRows(
    current,
    currentSnapshot?.connections?.browserAct?.browsers || [],
    currentSnapshot?.connections?.browserAct?.localProfiles || []
  );
  settingsDialog.showModal();
});

document.querySelector("#pool-auth-required").addEventListener("click", async event => {
  const startButton = event.target.closest("[data-pool-login]");
  const finishButton = event.target.closest("[data-pool-login-complete]");
  if (!startButton && !finishButton) return;
  const errorBox = document.querySelector("#pool-error");
  clearError(errorBox);
  const button = startButton || finishButton;
  if (!beginButtonBusy(button, startButton ? "正在打开…" : "正在验证…")) return;
  try {
    if (startButton) {
      await window.heyiAgent.startPoolLoginAssist(button.dataset.poolLogin);
    } else {
      await window.heyiAgent.finishPoolLoginAssist(button.dataset.poolLoginComplete);
    }
    await refresh();
  } catch (error) {
    await refresh();
    showError(errorBox, error);
  } finally {
    endButtonBusy(button);
  }
});

document.querySelector("#pool-account-add").addEventListener("click", () => {
  const current = collectPoolAccounts();
  current.push({
    id: `account-${Date.now()}`, label: "", email: "", browserId: "", profileId: "", aliases: [], enabled: true
  });
  renderPoolAccountRows(
    current,
    currentSnapshot?.connections?.browserAct?.browsers || [],
    currentSnapshot?.connections?.browserAct?.localProfiles || []
  );
});

document.querySelector("#pool-account-rows").addEventListener("click", event => {
  const button = event.target.closest(".pool-account-remove");
  if (button) button.closest(".pool-account-row").remove();
});

document.querySelector("#reset-button").addEventListener("click", async () => {
  if (!window.confirm("解除后，这台电脑将停止接收平台任务。确定继续吗？")) return;
  await window.heyiAgent.reset();
  settingsDialog.close();
  render(await window.heyiAgent.getSnapshot());
});

window.heyiAgent.getSnapshot().then(render).catch((error) => showError(setupError, error));
window.heyiAgent.onRuntimeUpdate(() => refresh());
