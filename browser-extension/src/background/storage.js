import { DEFAULT_PLATFORM_URL, STORAGE_KEYS, normalizePlatformUrl } from "../shared/constants.js";

const MAX_PENDING_REVOCATIONS = 20;
let pendingRevocationMutation = Promise.resolve();

export async function restrictStorageToExtensionPages() {
  if (typeof chrome.storage.local.setAccessLevel !== "function") {
    throw new Error("当前浏览器版本不支持安全的插件存储");
  }
  await chrome.storage.local.setAccessLevel({ accessLevel: "TRUSTED_CONTEXTS" });
}

export async function readSettings() {
  const stored = await chrome.storage.local.get(STORAGE_KEYS.SETTINGS);
  const value = stored[STORAGE_KEYS.SETTINGS] || {};
  let baseUrl = DEFAULT_PLATFORM_URL;
  try {
    baseUrl = normalizePlatformUrl(value.baseUrl || DEFAULT_PLATFORM_URL);
  } catch {
    // A malformed value from an older build must never become a request target.
  }
  return {
    baseUrl,
    deviceName: typeof value.deviceName === "string" ? value.deviceName.slice(0, 80) : ""
  };
}

export async function writeSettings(settings) {
  const previous = await readSettings();
  const next = {
    baseUrl: normalizePlatformUrl(settings?.baseUrl || previous.baseUrl),
    deviceName: typeof settings?.deviceName === "string"
      ? settings.deviceName.trim().slice(0, 80)
      : previous.deviceName
  };
  await chrome.storage.local.set({ [STORAGE_KEYS.SETTINGS]: next });
  return next;
}

export async function readAuth() {
  const stored = await chrome.storage.local.get(STORAGE_KEYS.AUTH);
  const auth = stored[STORAGE_KEYS.AUTH] || null;
  if (!auth?.accessToken || !auth?.baseUrl) return null;
  try {
    normalizePlatformUrl(auth.baseUrl);
  } catch {
    await clearAuth();
    return null;
  }
  const expiry = Date.parse(String(auth.expiresAt || ""));
  if (!Number.isFinite(expiry) || expiry <= Date.now()) {
    await clearAuth();
    return null;
  }
  return auth;
}

export async function writeAuth(auth) {
  await restrictStorageToExtensionPages();
  if (!auth?.accessToken) throw new Error("平台没有返回访问凭证");
  const accessToken = String(auth.accessToken);
  if (accessToken.length < 16 || accessToken.length > 8_192 || /\s|[\u0000-\u001F\u007F]/.test(accessToken)) {
    throw new Error("平台返回的访问凭证格式不正确");
  }
  const user = sanitizeUser(auth.user);
  const permissions = Array.isArray(auth.permissions)
    ? auth.permissions.filter(value => typeof value === "string").slice(0, 200)
    : Array.isArray(user?.permissions)
      ? user.permissions.filter(value => typeof value === "string").slice(0, 200)
      : [];
  const expiresAt = shortExpiry(auth.expiresAt);
  if (Date.parse(expiresAt) <= Date.now()) throw new Error("平台返回的访问凭证已经过期");
  const value = {
    baseUrl: normalizePlatformUrl(auth.baseUrl),
    accessToken,
    tokenType: "Bearer",
    expiresAt,
    pairedAt: new Date().toISOString(),
    user,
    permissions
  };
  await chrome.storage.local.set({ [STORAGE_KEYS.AUTH]: value });
  return value;
}

export async function updateAuthProfile(profile) {
  const auth = await readAuth();
  if (!auth) return null;
  const user = sanitizeUser(profile?.user && typeof profile.user === "object" ? profile.user : profile);
  const permissions = Array.isArray(profile?.permissions || profile?.permissionCodes)
    ? (profile.permissions || profile.permissionCodes)
    : Array.isArray(user?.permissions)
      ? user.permissions
      : auth.permissions;
  const next = {
    ...auth,
    user: user || auth.user,
    permissions: permissions.filter(value => typeof value === "string").slice(0, 200),
    expiresAt: shortExpiry(profile?.expiresAt || auth.expiresAt)
  };
  await chrome.storage.local.set({ [STORAGE_KEYS.AUTH]: next });
  return next;
}

export async function clearAuth() {
  await chrome.storage.local.remove(STORAGE_KEYS.AUTH);
}

export async function enqueuePendingRevocation(auth, errorCode = "REQUEST_FAILED", reason = "SWITCH") {
  return serializePendingRevocation(async () => {
    await restrictStorageToExtensionPages();
    const pending = await loadPendingRevocations();
    const credential = pendingCredential(auth, {
      errorCode,
      reason,
      attemptCount: 1,
      lastAttemptAt: new Date().toISOString()
    });
    if (!credential) return { queued: false, count: pending.length };
    const existingIndex = pending.findIndex(item =>
      item.baseUrl === credential.baseUrl && item.accessToken === credential.accessToken);
    if (existingIndex >= 0) {
      const existing = pending[existingIndex];
      pending[existingIndex] = {
        ...existing,
        expiresAt: credential.expiresAt,
        lastAttemptAt: credential.lastAttemptAt,
        attemptCount: Math.max(existing.attemptCount + 1, credential.attemptCount),
        lastErrorCode: credential.lastErrorCode,
        reason: existing.reason === "LOGOUT" ? "LOGOUT" : credential.reason
      };
    } else {
      if (pending.length >= MAX_PENDING_REVOCATIONS) {
        throw new Error("待撤销凭证队列已满，请联网完成旧凭证撤销后重试");
      }
      pending.push(credential);
    }
    await writePendingRevocations(pending);
    return { queued: true, count: pending.length };
  });
}

export async function readPendingRevocations() {
  return serializePendingRevocation(async () => {
    const pending = await loadPendingRevocations();
    await writePendingRevocations(pending);
    return pending.map(item => ({ ...item }));
  });
}

export async function pendingRevocationCount() {
  return (await readPendingRevocations()).length;
}

export async function markPendingRevocationFailure(id, errorCode = "REQUEST_FAILED") {
  return serializePendingRevocation(async () => {
    const pending = await loadPendingRevocations();
    const index = pending.findIndex(item => item.id === id);
    if (index < 0) return pending.length;
    pending[index] = {
      ...pending[index],
      lastAttemptAt: new Date().toISOString(),
      attemptCount: Math.min(10_000, pending[index].attemptCount + 1),
      lastErrorCode: cleanRevocationCode(errorCode)
    };
    await writePendingRevocations(pending);
    return pending.length;
  });
}

export async function removePendingRevocation(id) {
  return serializePendingRevocation(async () => {
    const pending = await loadPendingRevocations();
    const next = pending.filter(item => item.id !== id);
    if (next.length !== pending.length) await writePendingRevocations(next);
    return next.length;
  });
}

function serializePendingRevocation(operation) {
  const next = pendingRevocationMutation.then(operation, operation);
  pendingRevocationMutation = next.then(() => undefined, () => undefined);
  return next;
}

async function loadPendingRevocations() {
  const stored = await chrome.storage.local.get(STORAGE_KEYS.PENDING_REVOCATIONS);
  const raw = stored[STORAGE_KEYS.PENDING_REVOCATIONS];
  if (!Array.isArray(raw)) return [];
  const pending = [];
  for (const value of raw.slice(0, MAX_PENDING_REVOCATIONS * 2)) {
    const credential = pendingCredential(value);
    if (!credential) continue;
    const duplicate = pending.findIndex(item =>
      item.baseUrl === credential.baseUrl && item.accessToken === credential.accessToken);
    if (duplicate >= 0) pending[duplicate] = credential;
    else if (pending.length < MAX_PENDING_REVOCATIONS) pending.push(credential);
  }
  return pending;
}

async function writePendingRevocations(pending) {
  if (pending.length) {
    await chrome.storage.local.set({ [STORAGE_KEYS.PENDING_REVOCATIONS]: pending });
  } else {
    await chrome.storage.local.remove(STORAGE_KEYS.PENDING_REVOCATIONS);
  }
}

function pendingCredential(value, overrides = {}) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  let baseUrl;
  try {
    baseUrl = normalizePlatformUrl(value.baseUrl);
  } catch {
    return null;
  }
  const accessToken = typeof value.accessToken === "string" ? value.accessToken : "";
  if (accessToken.length < 16 || accessToken.length > 8_192
      || /\s|[\u0000-\u001F\u007F]/.test(accessToken)) return null;
  const expiresAt = normalizedFutureDate(value.expiresAt);
  if (!expiresAt) return null;
  const now = new Date().toISOString();
  const id = typeof value.id === "string" && /^[A-Za-z0-9_-]{8,100}$/.test(value.id)
    ? value.id : crypto.randomUUID();
  const queuedAt = normalizedDate(value.queuedAt) || now;
  const lastAttemptAt = normalizedDate(overrides.lastAttemptAt ?? value.lastAttemptAt);
  const attemptValue = Number(overrides.attemptCount ?? value.attemptCount);
  const attemptCount = Number.isSafeInteger(attemptValue) && attemptValue >= 0
    ? Math.min(10_000, attemptValue) : 0;
  const requestedReason = String(overrides.reason ?? value.reason ?? "SWITCH").toUpperCase();
  const reason = ["LOGOUT", "SWITCH", "REPAIR"].includes(requestedReason)
    ? requestedReason : "SWITCH";
  return {
    id,
    baseUrl,
    accessToken,
    expiresAt,
    queuedAt,
    lastAttemptAt,
    attemptCount,
    lastErrorCode: cleanRevocationCode(overrides.errorCode ?? value.lastErrorCode),
    reason
  };
}

function normalizedDate(value) {
  const parsed = typeof value === "number" && Number.isFinite(value)
    ? (value < 10_000_000_000 ? value * 1000 : value)
    : Date.parse(String(value || ""));
  return Number.isFinite(parsed) ? new Date(parsed).toISOString() : null;
}

function normalizedFutureDate(value) {
  const normalized = normalizedDate(value);
  return normalized && Date.parse(normalized) > Date.now() ? normalized : null;
}

function cleanRevocationCode(value) {
  const code = String(value || "REQUEST_FAILED").toUpperCase();
  return /^[A-Z0-9_-]{1,80}$/.test(code) ? code : "REQUEST_FAILED";
}

function normalizeExpiry(value) {
  if (!value) return null;
  if (typeof value === "number" && Number.isFinite(value)) {
    const milliseconds = value < 10_000_000_000 ? value * 1000 : value;
    return new Date(milliseconds).toISOString();
  }
  const parsed = Date.parse(String(value));
  return Number.isFinite(parsed) ? new Date(parsed).toISOString() : null;
}

function shortExpiry(value) {
  const now = Date.now();
  const fallback = now + 8 * 60 * 60 * 1000;
  const maximum = now + 24 * 60 * 60 * 1000;
  const parsed = Date.parse(normalizeExpiry(value) || "");
  return new Date(Math.min(Number.isFinite(parsed) ? parsed : fallback, maximum)).toISOString();
}

function sanitizeUser(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const clean = (candidate, max = 160) => candidate === null || candidate === undefined
    ? null
    : String(candidate).replace(/[\u0000-\u001F\u007F]/g, " ").trim().slice(0, max);
  const strings = (candidate, maxItems = 200) => Array.isArray(candidate)
    ? candidate.filter(item => typeof item === "string").map(item => clean(item, 120)).filter(Boolean).slice(0, maxItems)
    : [];
  const permissions = strings(value.permissions || value.permissionCodes);
  return {
    userId: clean(value.userId || value.id, 64),
    username: clean(value.username || value.phone, 160),
    displayName: clean(value.displayName || value.name, 160),
    organizationId: clean(value.organizationId || value.organization?.id, 64),
    organizationCode: clean(value.organizationCode || value.organization?.code, 80),
    organizationName: clean(value.organizationName || value.organization?.name, 160),
    roles: strings(value.roles, 100),
    permissions,
    permissionCodes: permissions,
    platformAdmin: Boolean(value.platformAdmin)
  };
}
