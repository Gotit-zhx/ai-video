const refs = {
  refresh: document.querySelector("#refresh-button"),
  dataDirectory: document.querySelector("#data-directory"),
  launchUrl: document.querySelector("#launch-url"),
  saveUrl: document.querySelector("#save-url-button"),
  chooseDirectory: document.querySelector("#choose-directory-button"),
  openDirectory: document.querySelector("#open-directory-button"),
  warningDirectory: document.querySelector("#warning-directory-button"),
  scanWarning: document.querySelector("#scan-warning"),
  scanWarningCopy: document.querySelector("#scan-warning-copy"),
  profileGrid: document.querySelector("#profile-grid"),
  emptyState: document.querySelector("#empty-state"),
  emptyDirectory: document.querySelector("#empty-directory-button"),
  profileSummary: document.querySelector("#profile-summary"),
  orbitNumber: document.querySelector("#orbit-number"),
  heroStatus: document.querySelector("#hero-status"),
  heroStatusDetail: document.querySelector("#hero-status-detail"),
  usageTotal: document.querySelector("#usage-total"),
  usageMeterBar: document.querySelector("#usage-meter-bar"),
  profileCount: document.querySelector("#profile-count"),
  configuredCount: document.querySelector("#configured-count"),
  unconfiguredCount: document.querySelector("#unconfigured-count"),
  resetCounts: document.querySelector("#reset-counts-button"),
  dialog: document.querySelector("#edit-dialog"),
  form: document.querySelector("#edit-form"),
  closeDialog: document.querySelector("#close-dialog"),
  cancelEdit: document.querySelector("#cancel-edit"),
  editDirectory: document.querySelector("#edit-directory"),
  editAlias: document.querySelector("#edit-alias"),
  editNote: document.querySelector("#edit-note"),
  editError: document.querySelector("#edit-error"),
  toast: document.querySelector("#toast")
};

let currentSnapshot = null;
let toastTimer;

function showToast(message, type = "") {
  refs.toast.textContent = message;
  refs.toast.className = `toast visible ${type}`.trim();
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    refs.toast.className = "toast";
  }, 2800);
}

function formatDate(value) {
  if (!value) return "还没有打开记录";
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return "还没有打开记录";
  return `上次打开 ${date.toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" })}`;
}

function setButtonBusy(button, busy, busyText, idleText) {
  button.disabled = busy;
  button.textContent = busy ? busyText : idleText;
}

function createText(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  element.textContent = text;
  return element;
}

function renderProfile(profile) {
  const card = document.createElement("article");
  card.className = `profile-card ${profile.configured ? "is-configured" : "is-unconfigured"}`;

  const header = document.createElement("div");
  header.className = "profile-card-header";
  const avatar = createText("span", "profile-avatar", (profile.title || profile.directory).slice(0, 1).toUpperCase());
  const status = document.createElement("span");
  status.className = `profile-status ${profile.configured ? "ready" : "pending"}`;
  status.append(createText("i", "", ""), createText("span", "", profile.configured ? "已配置" : "待登录"));
  header.append(avatar, status);
  card.append(header);

  const title = createText("h3", "profile-title", profile.title);
  const identity = createText("p", "profile-identity", profile.email || "尚未发现 Google 账号摘要");
  const directory = createText("span", "profile-directory", profile.directory);
  directory.title = profile.directory;
  card.append(title, identity, directory);

  const details = document.createElement("div");
  details.className = "profile-details";
  const usage = document.createElement("div");
  usage.append(createText("span", "detail-label", "人工记录"), createText("strong", "detail-value", `${profile.count} 次`));
  const opened = document.createElement("div");
  opened.append(createText("span", "detail-label", "最近活动"), createText("strong", "detail-value muted", formatDate(profile.lastOpenedAt)));
  details.append(usage, opened);
  card.append(details);

  if (profile.note) card.append(createText("p", "profile-note", profile.note));

  const actions = document.createElement("div");
  actions.className = "profile-actions";
  const openButton = createText("button", "primary-button", "打开 Profile");
  openButton.type = "button";
  const recordButton = createText("button", "secondary-button", "记录一次");
  recordButton.type = "button";
  const editButton = createText("button", "edit-button", "编辑");
  editButton.type = "button";
  actions.append(openButton, recordButton, editButton);
  card.append(actions);

  openButton.addEventListener("click", async () => {
    setButtonBusy(openButton, true, "正在打开…", "打开 Profile");
    try {
      currentSnapshot = await window.chromeProfiles.openProfile(profile.directory);
      render(currentSnapshot);
      showToast(`已打开 ${profile.title}`);
    } catch (error) {
      showToast(error.message || "打开 Profile 失败", "error");
    } finally {
      openButton.disabled = false;
    }
  });

  recordButton.addEventListener("click", async () => {
    recordButton.disabled = true;
    try {
      currentSnapshot = await window.chromeProfiles.recordUsage(profile.directory);
      render(currentSnapshot);
      showToast(`已记录 ${profile.title} 的一次使用`);
    } catch (error) {
      showToast(error.message || "记录失败", "error");
    } finally {
      recordButton.disabled = false;
    }
  });

  editButton.addEventListener("click", () => openEditor(profile));
  return card;
}

function openEditor(profile) {
  refs.editDirectory.value = profile.directory;
  refs.editAlias.value = profile.title === profile.chromeName ? "" : profile.title;
  refs.editNote.value = profile.note || "";
  refs.editError.hidden = true;
  refs.dialog.showModal();
  refs.editAlias.focus();
}

function render(snapshot) {
  currentSnapshot = snapshot;
  const profiles = snapshot.profiles || [];
  refs.dataDirectory.textContent = snapshot.chromeUserDataDir || "未设置";
  refs.dataDirectory.title = snapshot.chromeUserDataDir || "";
  refs.launchUrl.value = snapshot.launchUrl || "";
  refs.orbitNumber.textContent = String(profiles.length);
  refs.profileCount.textContent = String(profiles.length);
  refs.configuredCount.textContent = String(snapshot.configuredCount || 0);
  refs.unconfiguredCount.textContent = String(Math.max(0, profiles.length - (snapshot.configuredCount || 0)));
  refs.usageTotal.textContent = String(snapshot.usageTotal || 0);
  refs.profileSummary.textContent = `${profiles.length} 个 Profile`;
  refs.usageMeterBar.style.width = `${Math.min(100, Number(snapshot.usageTotal || 0) * 8)}%`;

  const hasDirectory = Boolean(snapshot.dataDirExists);
  refs.heroStatus.textContent = hasDirectory ? "Chrome 已连接" : "等待 Chrome 目录";
  refs.heroStatusDetail.textContent = snapshot.chromeExecutable ? "可以打开本机 Profile" : "没有发现 Chrome 程序";
  refs.scanWarning.hidden = hasDirectory && !snapshot.scanError;
  refs.scanWarningCopy.textContent = snapshot.scanError || "请确认 Chrome 已安装，或选择正确的 User Data 文件夹。";
  refs.emptyState.hidden = profiles.length !== 0;
  refs.profileGrid.hidden = profiles.length === 0;
  refs.profileGrid.replaceChildren(...profiles.map(renderProfile));
}

async function refresh() {
  setButtonBusy(refs.refresh, true, "读取中…", "刷新列表");
  try {
    render(await window.chromeProfiles.refresh());
  } catch (error) {
    showToast(error.message || "读取 Chrome Profile 失败", "error");
  } finally {
    refs.refresh.disabled = false;
    refs.refresh.textContent = "刷新列表";
  }
}

async function chooseDirectory() {
  try {
    render(await window.chromeProfiles.chooseDataDirectory());
    showToast("Chrome 数据目录已更新");
  } catch (error) {
    showToast(error.message || "选择目录失败", "error");
  }
}

refs.refresh.addEventListener("click", refresh);
refs.chooseDirectory.addEventListener("click", chooseDirectory);
refs.warningDirectory.addEventListener("click", chooseDirectory);
refs.emptyDirectory.addEventListener("click", chooseDirectory);
refs.openDirectory.addEventListener("click", () => window.chromeProfiles.openDataDirectory());

refs.saveUrl.addEventListener("click", async () => {
  try {
    render(await window.chromeProfiles.updateLaunchUrl(refs.launchUrl.value));
    showToast("打开网址已保存");
  } catch (error) {
    showToast(error.message || "保存网址失败", "error");
  }
});

refs.resetCounts.addEventListener("click", async () => {
  if (!window.confirm("确定清空所有 Profile 的人工使用记录吗？")) return;
  try {
    render(await window.chromeProfiles.resetCounts());
    showToast("使用记录已清空");
  } catch (error) {
    showToast(error.message || "清空记录失败", "error");
  }
});

refs.closeDialog.addEventListener("click", () => refs.dialog.close());
refs.cancelEdit.addEventListener("click", () => refs.dialog.close());
refs.form.addEventListener("submit", async event => {
  event.preventDefault();
  refs.editError.hidden = true;
  try {
    render(await window.chromeProfiles.updateProfile({
      directory: refs.editDirectory.value,
      alias: refs.editAlias.value,
      note: refs.editNote.value
    }));
    refs.dialog.close();
    showToast("Profile 名称已保存");
  } catch (error) {
    refs.editError.textContent = error.message || "保存失败";
    refs.editError.hidden = false;
  }
});

window.chromeProfiles.snapshot().then(render).catch(error => showToast(error.message || "启动失败", "error"));
