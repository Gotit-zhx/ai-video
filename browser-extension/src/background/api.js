import { API_PATHS, joinPlatformUrl } from "../shared/constants.js";
import {
  clearAuth,
  enqueuePendingRevocation,
  markPendingRevocationFailure,
  pendingRevocationCount,
  readAuth,
  readPendingRevocations,
  removePendingRevocation,
  updateAuthProfile,
  writeAuth
} from "./storage.js";

const REVOCATION_RETRY_INTERVAL_MS = 30_000;

export class ApiError extends Error {
  constructor(message, status = 0, code = "REQUEST_FAILED", payload = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.payload = payload;
  }
}

async function fetchJson(baseUrl, path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("Accept", "application/json");
  if (options.body !== undefined) headers.set("Content-Type", "application/json");
  if (options.accessToken) headers.set("Authorization", `Bearer ${options.accessToken}`);

  let response;
  try {
    response = await fetch(joinPlatformUrl(baseUrl, path), {
      method: options.method || "GET",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
      referrerPolicy: "no-referrer"
    });
  } catch (error) {
    throw new ApiError(
      error instanceof TypeError ? "无法连接平台，请检查平台地址和网络" : "平台请求失败",
      0,
      "NETWORK_ERROR"
    );
  }

  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ApiError(
      payload?.message || defaultMessage(response.status),
      response.status,
      payload?.code || `HTTP_${response.status}`,
      payload
    );
  }
  return payload?.data ?? payload;
}

export async function exchangePairingCode(baseUrl, code, deviceName) {
  const data = await fetchJson(baseUrl, API_PATHS.EXCHANGE, {
    method: "POST",
    body: {
      code,
      deviceName,
      extensionId: chrome.runtime.id,
      extensionVersion: chrome.runtime.getManifest().version
    }
  });
  const accessToken = data?.accessToken || data?.token;
  if (!accessToken) throw new ApiError("平台没有返回访问凭证", 502, "MISSING_ACCESS_TOKEN");
  return writeAuth({
    baseUrl,
    accessToken,
    expiresAt: data?.expiresAt,
    user: data?.user,
    permissions: data?.permissions || data?.permissionCodes
      || data?.user?.permissions || data?.user?.permissionCodes
  });
}

export async function refreshCurrentUser() {
  const auth = await requireAuth();
  try {
    const data = await fetchJson(auth.baseUrl, API_PATHS.ME, { accessToken: auth.accessToken });
    return updateAuthProfile(data);
  } catch (error) {
    await clearIfUnauthorized(error);
    throw error;
  }
}

export async function logoutCurrentDevice(options = {}) {
  const auth = await readAuth();
  if (!auth) {
    return {
      hadCredential: false,
      revoked: true,
      deferred: false,
      pendingRevocationCount: await pendingRevocationCount()
    };
  }
  try {
    await revokeCredential(auth);
  } catch (error) {
    // A 401 means the server-side token is already unusable, so local cleanup
    // is safe. For network/5xx failures retain the credential so logout can be
    // retried instead of orphaning a still-active server token.
    if (!options.deferOnFailure) throw logoutRetryRequired(error);
    let queued;
    try {
      queued = await enqueuePendingRevocation(
        auth,
        error instanceof ApiError ? error.code : "REQUEST_FAILED",
        options.reason
      );
    } catch {
      throw new ApiError(
        "无法安全保存待撤销凭证，本地绑定仍保留；请联网后重试",
        0,
        "REVOCATION_QUEUE_FAILED"
      );
    }
    if (!queued.queued) throw logoutRetryRequired(error);
    await clearAuth();
    return {
      hadCredential: true,
      revoked: false,
      deferred: true,
      pendingRevocationCount: queued.count
    };
  }
  await clearAuth();
  return {
    hadCredential: true,
    revoked: true,
    deferred: false,
    pendingRevocationCount: await pendingRevocationCount()
  };
}

export async function retryPendingRevocations(options = {}) {
  const pending = await readPendingRevocations();
  const active = await readAuth();
  const now = Date.now();
  let attempted = 0;
  let revoked = 0;
  const resolved = [];
  for (const credential of pending) {
    if (active && active.baseUrl === credential.baseUrl
        && active.accessToken === credential.accessToken) continue;
    const lastAttempt = Date.parse(String(credential.lastAttemptAt || ""));
    if (!options.force && Number.isFinite(lastAttempt)
        && now - lastAttempt < REVOCATION_RETRY_INTERVAL_MS) continue;
    attempted += 1;
    try {
      await revokeCredential(credential);
      await removePendingRevocation(credential.id);
      revoked += 1;
      resolved.push({ baseUrl: credential.baseUrl, reason: credential.reason });
    } catch (error) {
      await markPendingRevocationFailure(
        credential.id,
        error instanceof ApiError ? error.code : "REQUEST_FAILED"
      );
    }
  }
  return {
    attempted,
    revoked,
    pendingRevocationCount: await pendingRevocationCount(),
    resolved
  };
}

export async function createCapture(payload, idempotencyKey) {
  const auth = await requireAuth();
  try {
    return await fetchJson(auth.baseUrl, API_PATHS.CAPTURES, {
      method: "POST",
      accessToken: auth.accessToken,
      headers: { "Idempotency-Key": idempotencyKey },
      body: payload
    });
  } catch (error) {
    await clearIfUnauthorized(error);
    throw error;
  }
}

async function requireAuth() {
  const auth = await readAuth();
  if (!auth) throw new ApiError("请先在插件中登录并配对", 401, "AUTH_REQUIRED");
  return auth;
}

async function clearIfUnauthorized(error) {
  if (error instanceof ApiError && error.status === 401) await clearAuth();
}

async function revokeCredential(credential) {
  try {
    await fetchJson(credential.baseUrl, API_PATHS.LOGOUT, {
      method: "POST",
      accessToken: credential.accessToken
    });
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error;
  }
}

function logoutRetryRequired(error) {
  return new ApiError(
    "服务端撤销未完成，本地配对凭证仍保留；请联网后再次点击“退出并撤销本设备”",
    error instanceof ApiError ? error.status : 0,
    "LOGOUT_RETRY_REQUIRED"
  );
}

function defaultMessage(status) {
  if (status === 401) return "登录已失效，请重新配对";
  if (status === 403) return "当前账号没有商品采集权限";
  if (status === 409) return "请求发生冲突，请检查商品状态后重试";
  if (status === 413) return "商品数据过大，无法提交";
  if (status === 429) return "操作过于频繁，请稍后重试";
  return "平台暂时无法处理请求";
}
