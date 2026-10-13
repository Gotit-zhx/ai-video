import {
  DEFAULT_PLATFORM_URL,
  MESSAGE_TYPES,
  originPattern
} from "../shared/constants.js";

const elements = {
  notice: document.querySelector("#notice"),
  revocationWarning: document.querySelector("#revocation-warning"),
  revocationWarningCopy: document.querySelector("#revocation-warning-copy"),
  updateRequired: document.querySelector("#update-required"),
  updateCopy: document.querySelector("#update-copy"),
  platformState: document.querySelector("#platform-state"),
  connectionDot: document.querySelector("#connection-dot"),
  pairingPanel: document.querySelector("#pairing-panel"),
  accountPanel: document.querySelector("#account-panel"),
  pairingForm: document.querySelector("#pairing-form"),
  loginAccount: document.querySelector("#login-account"),
  loginPassword: document.querySelector("#login-password"),
  deviceName: document.querySelector("#device-name"),
  pairButton: document.querySelector("#pair-button"),
  openPlatform: document.querySelector("#open-platform"),
  currentUser: document.querySelector("#current-user"),
  currentOrganization: document.querySelector("#current-organization"),
  tokenExpiry: document.querySelector("#token-expiry"),
  permissionList: document.querySelector("#permission-list"),
  logoutButton: document.querySelector("#logout-button")
};

let updateRequired = false;
elements.deviceName.value = suggestedDeviceName();

elements.openPlatform.addEventListener("click", async () => {
  clearNotice();
  try {
    const baseUrl = DEFAULT_PLATFORM_URL;
    await requestPlatformPermission(baseUrl);
    const state = await callBackground({
      type: MESSAGE_TYPES.SAVE_PLATFORM,
      baseUrl,
      deviceName: elements.deviceName.value
    });
    renderState(state);
    await callBackground({ type: MESSAGE_TYPES.OPEN_PLATFORM, baseUrl });
  } catch (error) {
    showError(error);
  }
});

elements.pairingForm.addEventListener("submit", async event => {
  event.preventDefault();
  clearNotice();
  elements.pairButton.disabled = true;
  elements.pairButton.textContent = "正在登录…";
  try {
    const baseUrl = DEFAULT_PLATFORM_URL;
    await requestPlatformPermission(baseUrl);
    const state = await callBackground({
      type: MESSAGE_TYPES.LOGIN,
      baseUrl,
      account: elements.loginAccount.value,
      password: elements.loginPassword.value,
      deviceName: elements.deviceName.value
    });
    elements.loginPassword.value = "";
    renderState(state);
    showNotice(state.revocationPending
      ? "登录成功；旧平台凭证仍在后台等待撤销"
      : "登录成功，可以开始采集商品", true);
  } catch (error) {
    showError(error);
  } finally {
    elements.loginPassword.value = "";
    elements.pairButton.disabled = updateRequired;
    elements.pairButton.textContent = "登录";
  }
});

elements.logoutButton.addEventListener("click", async () => {
  clearNotice();
  elements.logoutButton.disabled = true;
  elements.logoutButton.textContent = "正在撤销…";
  try {
    const state = await callBackground({ type: MESSAGE_TYPES.LOGOUT });
    renderState(state);
    showNotice(state.revocationDeferred
      ? "已解除本地绑定；服务端撤销尚未完成，插件会在联网后自动重试"
      : "已退出并撤销本设备访问", true);
  } catch (error) {
    showError(error);
    await loadState(false);
  } finally {
    elements.logoutButton.disabled = false;
    elements.logoutButton.textContent = "退出并撤销本设备";
  }
});

void loadState(true);

async function loadState(refresh) {
  try {
    const state = await callBackground({ type: MESSAGE_TYPES.GET_STATE, refresh });
    renderState(state);
  } catch (error) {
    showError(error);
  }
}

function renderState(state) {
  const authenticated = Boolean(state?.authenticated);
  const pendingRevocations = Number(state?.pendingRevocationCount || 0);
  elements.revocationWarning.hidden = pendingRevocations <= 0;
  elements.revocationWarningCopy.textContent = pendingRevocations > 0
    ? `还有 ${pendingRevocations} 个旧平台凭证未获服务端撤销确认。凭证仅保存在插件受限存储中，联网后自动重试；不影响切换或配对新平台。`
    : "";
  elements.pairingPanel.hidden = authenticated;
  elements.accountPanel.hidden = !authenticated;
  elements.connectionDot.classList.toggle("connected", authenticated);
  elements.connectionDot.title = authenticated ? "已配对" : "未配对";
  elements.platformState.textContent = authenticated ? "安全连接" : "未连接";
  if (!authenticated) return;

  const user = state.user || {};
  elements.currentUser.textContent = user.displayName || user.username || user.phone || "已登录用户";
  const organizationName = user.organizationName || user.organization?.name || "当前企业";
  const organizationCode = user.organizationCode || user.organization?.code || "";
  elements.currentOrganization.textContent = organizationCode ? `${organizationName}（${organizationCode}）` : organizationName;
  elements.tokenExpiry.textContent = formatExpiry(state.expiresAt);
  renderPermissions(state.permissions || user.permissions || user.permissionCodes || []);
}

function renderPermissions(permissions) {
  elements.permissionList.replaceChildren();
  if (!Array.isArray(permissions) || permissions.length === 0) {
    const empty = document.createElement("span");
    empty.className = "empty";
    empty.textContent = "平台未返回权限明细";
    elements.permissionList.appendChild(empty);
    return;
  }
  for (const permission of permissions.slice(0, 100)) {
    const chip = document.createElement("span");
    chip.textContent = String(permission);
    elements.permissionList.appendChild(chip);
  }
}

async function requestPlatformPermission(baseUrl) {
  const origins = [originPattern(baseUrl), ...["shopee.com","shopeemobile.com","susercontent.com","tiktokcdn.com","tiktokcdn-us.com","ibytedtos.com","byteoversea.com"].map(h=>`https://*.${h}/*`)];
  if (await chrome.permissions.contains({ origins })) return;
  const granted = await chrome.permissions.request({ origins });
  if (!granted) throw new Error("未获得平台域名访问权限，无法登录或提交商品");
}

async function callBackground(message) {
  let response;
  try {
    response = await chrome.runtime.sendMessage(message);
  } catch {
    throw new Error("插件后台未响应，请重新加载插件后重试");
  }
  if (!response?.ok) {
    const error = new Error(response?.error?.message || "操作失败，请重试");
    error.status = response?.error?.status;
    error.code = response?.error?.code;
    throw error;
  }
  return response.data;
}

function formatExpiry(value) {
  const timestamp = Date.parse(String(value || ""));
  if (!Number.isFinite(timestamp)) return "短期凭证";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
  }).format(new Date(timestamp));
}

function suggestedDeviceName() {
  const platform = navigator.userAgentData?.platform || navigator.platform || "电脑";
  return `Chrome · ${String(platform).slice(0, 48)}`;
}

function showNotice(message, success = false) {
  elements.notice.textContent = message;
  elements.notice.hidden = false;
  elements.notice.classList.toggle("success", success);
}

function showError(error) {
  if (error?.status === 426 || error?.code === "EXTENSION_UPDATE_REQUIRED") {
    updateRequired = true;
    elements.updateRequired.hidden = false;
    elements.updateCopy.textContent = error.message;
    elements.pairingForm.hidden = true;
    elements.pairButton.disabled = true;
    elements.platformState.textContent = "需要更新";
    return;
  }
  showNotice(error.message);
}

function clearNotice() {
  elements.notice.hidden = true;
  elements.notice.textContent = "";
  elements.notice.classList.remove("success");
}
