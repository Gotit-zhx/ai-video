const { app, BrowserWindow, dialog, ipcMain, shell } = require("electron");
const fs = require("node:fs/promises");
const path = require("node:path");
const { spawn } = require("node:child_process");

const DEFAULT_LAUNCH_URL = "https://gemini.google.com/";
const PROFILE_DIRECTORY_PATTERN = /^(Default|Profile \d+|Guest Profile|System Profile)$/;

let mainWindow;
let managerConfig;

function defaultChromeUserDataDir() {
  if (process.platform === "win32") {
    return path.join(
      process.env.LOCALAPPDATA || path.join(process.env.USERPROFILE || "", "AppData", "Local"),
      "Google",
      "Chrome",
      "User Data"
    );
  }
  if (process.platform === "darwin") {
    return path.join(process.env.HOME || "", "Library", "Application Support", "Google", "Chrome");
  }
  return path.join(process.env.HOME || "", ".config", "google-chrome");
}

function configPath() {
  return path.join(app.getPath("userData"), "manager-config.json");
}

function normalizeLaunchUrl(value) {
  const candidate = String(value || "").trim() || DEFAULT_LAUNCH_URL;
  try {
    const parsed = new URL(candidate);
    if (!["http:", "https:"].includes(parsed.protocol)) throw new Error("URL 协议不安全");
    return parsed.toString();
  } catch {
    return DEFAULT_LAUNCH_URL;
  }
}

function normalizeConfig(value = {}) {
  const profiles = value.profiles && typeof value.profiles === "object" ? value.profiles : {};
  return {
    chromeUserDataDir: String(value.chromeUserDataDir || defaultChromeUserDataDir()),
    launchUrl: normalizeLaunchUrl(value.launchUrl),
    profiles
  };
}

async function loadConfig() {
  try {
    const content = await fs.readFile(configPath(), "utf8");
    managerConfig = normalizeConfig(JSON.parse(content));
  } catch {
    managerConfig = normalizeConfig();
  }
  return managerConfig;
}

async function saveConfig() {
  managerConfig = normalizeConfig(managerConfig);
  await fs.mkdir(path.dirname(configPath()), { recursive: true });
  await fs.writeFile(configPath(), JSON.stringify(managerConfig, null, 2), "utf8");
  return managerConfig;
}

function pathIsInside(parent, child) {
  const relative = path.relative(path.resolve(parent), path.resolve(child));
  return Boolean(relative) && !relative.startsWith("..") && !path.isAbsolute(relative);
}

function maskEmail(value) {
  const email = String(value || "").trim();
  const at = email.indexOf("@");
  if (at <= 0) return email ? `${email.slice(0, 1)}***` : "";
  const local = email.slice(0, at);
  const domain = email.slice(at + 1);
  return `${local.slice(0, 1)}${local.length > 1 ? "•••" : ""}@${domain}`;
}

async function readJson(filePath) {
  try {
    const content = await fs.readFile(filePath, "utf8");
    return JSON.parse(content.replace(/^\uFEFF/, ""));
  } catch {
    return null;
  }
}

async function scanProfiles() {
  const root = path.resolve(managerConfig.chromeUserDataDir);
  const localState = await readJson(path.join(root, "Local State"));
  const infoCache = localState?.profile?.info_cache || {};

  let entries;
  try {
    entries = await fs.readdir(root, { withFileTypes: true });
  } catch (error) {
    return {
      root,
      profiles: [],
      error: `找不到 Chrome 数据目录：${root}`,
      detail: error.message
    };
  }

  const directoryNames = entries
    .filter(entry => entry.isDirectory() && PROFILE_DIRECTORY_PATTERN.test(entry.name))
    .map(entry => entry.name);

  const profiles = directoryNames.map(directory => {
    const info = infoCache[directory] || {};
    const saved = managerConfig.profiles[directory] || {};
    const email = String(info.user_name || info.gaia_name || "").trim();
    const configured = Boolean(email || info.gaia_id || info.gaia_name);
    const count = Number.isFinite(Number(saved.count)) ? Math.max(0, Number(saved.count)) : 0;
    return {
      directory,
      title: String(saved.alias || info.name || directory),
      chromeName: String(info.name || directory),
      email: maskEmail(email),
      configured,
      count,
      note: String(saved.note || ""),
      lastOpenedAt: saved.lastOpenedAt || null
    };
  });

  profiles.sort((left, right) => {
    if (left.directory === "Default") return -1;
    if (right.directory === "Default") return 1;
    return left.directory.localeCompare(right.directory, "en", { numeric: true });
  });

  return { root, profiles, error: "" };
}

async function findChromeExecutable() {
  const candidates = process.platform === "win32"
    ? [
        path.join(process.env.PROGRAMFILES || "", "Google", "Chrome", "Application", "chrome.exe"),
        path.join(process.env["PROGRAMFILES(X86)"] || "", "Google", "Chrome", "Application", "chrome.exe"),
        path.join(process.env.LOCALAPPDATA || "", "Google", "Chrome", "Application", "chrome.exe")
      ]
    : process.platform === "darwin"
      ? ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"]
      : ["/usr/bin/google-chrome", "/usr/bin/google-chrome-stable"];

  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      await fs.access(candidate);
      return candidate;
    } catch {
      // Try the next known installation location.
    }
  }
  return "";
}

async function getSnapshot() {
  const scanned = await scanProfiles();
  const executable = await findChromeExecutable();
  const configuredCount = scanned.profiles.filter(profile => profile.configured).length;
  const usageTotal = scanned.profiles.reduce((total, profile) => total + profile.count, 0);
  return {
    chromeUserDataDir: managerConfig.chromeUserDataDir,
    launchUrl: managerConfig.launchUrl,
    chromeExecutable: executable,
    profiles: scanned.profiles,
    profileCount: scanned.profiles.length,
    configuredCount,
    usageTotal,
    scanError: scanned.error,
    dataDirExists: !scanned.error
  };
}

async function openProfile(directory) {
  const profileName = String(directory || "").trim();
  const snapshot = await getSnapshot();
  const profile = snapshot.profiles.find(item => item.directory === profileName);
  if (!profile) throw new Error("找不到这个 Chrome Profile，请先刷新列表");
  if (!snapshot.chromeExecutable) throw new Error("找不到 Chrome 可执行文件，请检查安装路径");

  const root = path.resolve(managerConfig.chromeUserDataDir);
  const profilePath = path.join(root, profile.directory);
  if (!pathIsInside(root, profilePath)) throw new Error("Profile 路径无效");

  const child = spawn(snapshot.chromeExecutable, [
    `--user-data-dir=${root}`,
    `--profile-directory=${profile.directory}`,
    "--new-window",
    managerConfig.launchUrl
  ], {
    detached: true,
    stdio: "ignore",
    windowsHide: true
  });
  child.unref();

  managerConfig.profiles[profile.directory] = {
    ...(managerConfig.profiles[profile.directory] || {}),
    lastOpenedAt: new Date().toISOString()
  };
  await saveConfig();
  return getSnapshot();
}

async function recordUsage(directory) {
  const profileName = String(directory || "").trim();
  const current = managerConfig.profiles[profileName] || {};
  const count = Number.isFinite(Number(current.count)) ? Math.max(0, Number(current.count)) : 0;
  managerConfig.profiles[profileName] = {
    ...current,
    count: count + 1,
    lastOpenedAt: current.lastOpenedAt || new Date().toISOString()
  };
  await saveConfig();
  return getSnapshot();
}

async function updateProfile(payload = {}) {
  const directory = String(payload.directory || "").trim();
  if (!PROFILE_DIRECTORY_PATTERN.test(directory)) throw new Error("Profile 名称无效");
  const current = managerConfig.profiles[directory] || {};
  managerConfig.profiles[directory] = {
    ...current,
    alias: String(payload.alias || "").trim().slice(0, 80),
    note: String(payload.note || "").trim().slice(0, 240)
  };
  await saveConfig();
  return getSnapshot();
}

async function chooseDataDirectory() {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "选择 Chrome User Data 文件夹",
    properties: ["openDirectory", "createDirectory"]
  });
  if (result.canceled || !result.filePaths[0]) return getSnapshot();
  managerConfig.chromeUserDataDir = result.filePaths[0];
  await saveConfig();
  return getSnapshot();
}

async function updateLaunchUrl(value) {
  managerConfig.launchUrl = normalizeLaunchUrl(value);
  await saveConfig();
  return getSnapshot();
}

async function resetCounts(directory = "") {
  if (directory) {
    if (managerConfig.profiles[directory]) managerConfig.profiles[directory].count = 0;
  } else {
    for (const profile of Object.values(managerConfig.profiles)) profile.count = 0;
  }
  await saveConfig();
  return getSnapshot();
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1220,
    height: 820,
    minWidth: 900,
    minHeight: 620,
    backgroundColor: "#eef3f7",
    title: "Chrome Profile 管理器",
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, "preload.js")
    }
  });
  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
}

app.whenReady().then(async () => {
  await loadConfig();
  ipcMain.handle("manager:snapshot", getSnapshot);
  ipcMain.handle("manager:refresh", getSnapshot);
  ipcMain.handle("manager:open-profile", (_event, directory) => openProfile(directory));
  ipcMain.handle("manager:record-usage", (_event, directory) => recordUsage(directory));
  ipcMain.handle("manager:update-profile", (_event, payload) => updateProfile(payload));
  ipcMain.handle("manager:choose-data-directory", chooseDataDirectory);
  ipcMain.handle("manager:update-launch-url", (_event, value) => updateLaunchUrl(value));
  ipcMain.handle("manager:reset-counts", (_event, directory) => resetCounts(directory));
  ipcMain.handle("manager:open-data-directory", () => shell.openPath(managerConfig.chromeUserDataDir));

  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
