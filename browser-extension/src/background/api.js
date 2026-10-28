import "../shared/video-source.js";
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

export const deduplicateVideos = globalThis.HeyiVideoSources.deduplicate;

const REVOCATION_RETRY_INTERVAL_MS = 30_000;
const SERVER_CLOCK = Symbol("platform response clock");

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
  headers.set("X-Heyi-Extension-Version", chrome.runtime.getManifest().version);
  if (options.body !== undefined) headers.set("Content-Type", "application/json");
  if (options.accessToken) headers.set("Authorization", `Bearer ${options.accessToken}`);

  let response;
  try {
    response = await fetch(joinPlatformUrl(baseUrl, path), {
      method: options.method || "GET",
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      cache: "no-store",
      credentials: options.credentials || "omit",
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
  if (response.status === 204 && path === API_PATHS.LOGOUT) return null;
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new ApiError("平台响应不是有效数据，请检查本机代理或网络后重试", 502, "INVALID_RESPONSE");
  }
  const data = payload?.data ?? payload;
  const serverTimeMs = Date.parse(response.headers?.get?.("date") || "");
  if (data && typeof data === "object" && Number.isFinite(serverTimeMs)) {
    Object.defineProperty(data, SERVER_CLOCK, {
      value: { serverTimeMs, receivedAtMs: Date.now() }
    });
  }
  return data;
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
    serverClock: data?.[SERVER_CLOCK],
    user: data?.user,
    permissions: data?.permissions || data?.permissionCodes
      || data?.user?.permissions || data?.user?.permissionCodes
  });
}

export async function connectBrowserSession(baseUrl,deviceName) {
 const data=await fetchJson(baseUrl,'/api/v1/extension-auth/session',{method:'POST',credentials:'include',body:{deviceName,extensionId:chrome.runtime.id,extensionVersion:chrome.runtime.getManifest().version}});
 if(!data?.accessToken)throw new ApiError('浏览器尚未登录',401);
 return writeAuth({baseUrl,accessToken:data.accessToken,expiresAt:data.expiresAt,user:data.user,permissions:data.permissions,serverClock:data[SERVER_CLOCK]});
}

export async function loginWithPassword(baseUrl, account, password, deviceName) {
  const data = await fetchJson(baseUrl, '/api/v1/extension-auth/login', {
    method:'POST', body:{account,password,deviceName,extensionId:chrome.runtime.id,extensionVersion:chrome.runtime.getManifest().version}
  });
  if (!data?.accessToken) throw new ApiError('登录没有返回凭证',502);
  return writeAuth({baseUrl,accessToken:data.accessToken,expiresAt:data.expiresAt,user:data.user,permissions:data.permissions,serverClock:data[SERVER_CLOCK]});
}

export async function refreshCurrentUser() {
  const auth = await requireAuth();
  try {
    const data = await fetchJson(auth.baseUrl, API_PATHS.ME, { accessToken: auth.accessToken });
    return updateAuthProfile(data, data?.[SERVER_CLOCK]);
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

function allowedVideoUrl(raw) {
  try {const u=new URL(raw);return u.protocol==='https:'&&!u.username&&!u.password&&(!u.port||u.port==='443')&&['shopee.com','shopee.com.my','shopee.ph','shopeemobile.com','susercontent.com','shopee.sg','shopee.vn','tiktok.com','tiktokcdn.com','tiktokcdn-us.com','ibytedtos.com','byteoversea.com'].some(h=>u.hostname===h||u.hostname.endsWith('.'+h));}catch{return false;}
}
export async function archiveCaptureVideos(captureId, videos) {
  videos = deduplicateVideos(videos);
  if(!videos.length)return {savedCount:0,totalCount:0,failedCount:0,complete:true,message:''};
  if(!captureId)return {savedCount:0,totalCount:videos.length,failedCount:videos.length,complete:false,message:'商品已采集，未取得草稿 ID，视频未保存，请重试'};
  const auth=await requireAuth();let saved=0;const errors=[];
  for(const video of videos){
    try {
      if(!allowedVideoUrl(video.url))throw Error('视频域名尚未支持');
      const response=await fetch(video.url,{credentials:'omit',signal:AbortSignal.timeout(60000)});
      if(!response.ok||!allowedVideoUrl(response.url))throw Error('视频下载失败或来源域名不受支持');
      const reader=response.body.getReader(),chunks=[];let size=0;
      while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>64*1024*1024){await reader.cancel();throw Error('视频超过64MiB');}chunks.push(value);}
      const form=new FormData();form.append('sourceUrl',video.url);form.append('file',new Blob(chunks,{type:response.headers.get('content-type')||'video/mp4'}),'source-video.mp4');
      const r=await fetch(joinPlatformUrl(auth.baseUrl,`/api/v1/product-captures/${encodeURIComponent(captureId)}/videos`),{method:'POST',headers:{Authorization:`Bearer ${auth.accessToken}`,'X-Heyi-Extension-Version':chrome.runtime.getManifest().version},body:form,credentials:'omit',redirect:'error',signal:AbortSignal.timeout(90000)});
      if(!r.ok)throw Error((await r.json().catch(()=>({}))).message||'视频归档失败');saved++;
    }catch(error){errors.push(error.message);}
  }
  return {
    savedCount:saved,totalCount:videos.length,failedCount:videos.length-saved,complete:saved===videos.length,
    message:`商品已采集，视频已保存 ${saved}/${videos.length}${errors.length?'；'+errors[0]+'；点击重试补采视频':''}`
  };
}
