export const DEFAULT_PLATFORM_URL = "https://hoyee.org.cn";

export const STORAGE_KEYS = Object.freeze({
  SETTINGS: "heyiProductCaptureSettingsV1",
  AUTH: "heyiProductCaptureAuthV1",
  PENDING_REVOCATIONS: "heyiProductCapturePendingRevocationsV1"
});

export const MESSAGE_TYPES = Object.freeze({
  GET_STATE: "HEYI_GET_STATE",
  SAVE_PLATFORM: "HEYI_SAVE_PLATFORM",
  PAIR: "HEYI_PAIR",
  LOGOUT: "HEYI_LOGOUT",
  OPEN_PLATFORM: "HEYI_OPEN_PLATFORM",
  OPEN_PRODUCT_DETAIL: "HEYI_OPEN_PRODUCT_DETAIL",
  TAKE_PENDING_CAPTURE: "HEYI_TAKE_PENDING_CAPTURE",
  CAPTURE: "HEYI_CAPTURE_PRODUCT"
});

export const API_PATHS = Object.freeze({
  EXCHANGE: "/api/v1/extension-auth/exchange",
  ME: "/api/v1/extension-auth/me",
  LOGOUT: "/api/v1/extension-auth/logout",
  CAPTURES: "/api/v1/product-captures"
});

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1"]);

export function normalizePlatformUrl(value) {
  let url;
  try {
    url = new URL(String(value || "").trim());
  } catch {
    throw new Error("请输入有效的平台地址");
  }

  const isSecure = url.protocol === "https:";
  const isLoopbackHttp = url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname);
  if (!isSecure && !isLoopbackHttp) {
    throw new Error("平台地址必须使用 HTTPS；仅本机调试允许 HTTP");
  }
  if (url.username || url.password) throw new Error("平台地址不能包含账号或密码");
  if (url.search || url.hash) throw new Error("平台地址不能包含查询参数或片段");

  return url.origin;
}

export function originPattern(value) {
  const baseUrl = normalizePlatformUrl(value);
  const url = new URL(baseUrl);
  // Chrome match patterns grant by host rather than by TCP port. Requests are
  // still sent only to the exact configured baseUrl (including its port).
  return `${url.protocol}//${url.hostname}/*`;
}

export function joinPlatformUrl(baseUrl, path) {
  return new URL(path, `${normalizePlatformUrl(baseUrl)}/`).toString();
}

export function safeAuthView(settings, auth) {
  const now = Date.now();
  const expiresAt = auth?.expiresAt || null;
  const expired = Boolean(expiresAt && Date.parse(expiresAt) <= now);
  return {
    baseUrl: settings?.baseUrl || DEFAULT_PLATFORM_URL,
    authenticated: Boolean(auth?.accessToken) && !expired,
    expiresAt,
    user: auth?.user || null,
    permissions: Array.isArray(auth?.permissions) ? auth.permissions : [],
    pairedAt: auth?.pairedAt || null
  };
}
