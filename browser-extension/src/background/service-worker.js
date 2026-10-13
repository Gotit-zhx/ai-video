import {
  MESSAGE_TYPES,
  normalizePlatformUrl,
  originPattern,
  safeAuthView
} from "../shared/constants.js";
import {
  ApiError,
  createCapture,
  exchangePairingCode,
  loginWithPassword,
  connectBrowserSession,
  archiveCaptureVideos,
  deduplicateVideos,
  logoutCurrentDevice,
  retryPendingRevocations,
  refreshCurrentUser
} from "./api.js";
import {
  pendingRevocationCount,
  readAuth,
  readPendingRevocations,
  readSettings,
  restrictStorageToExtensionPages,
  writeSettings
} from "./storage.js";

const PENDING_CAPTURE_PREFIX = "heyiPendingCaptureV1:";
let revocationRetryInFlight = null;

initializeStorageAndRevocations().catch(() => {
  // Pairing invokes the same check again and fails closed before storing a token.
});

chrome.runtime.onInstalled.addListener(() => {
  initializeStorageAndRevocations().catch(() => {});
});

chrome.runtime.onStartup.addListener(() => {
  initializeStorageAndRevocations().catch(() => {});
});

globalThis.addEventListener?.("online", () => {
  retryRevocationsInBackground({ force: true }).catch(() => {});
});

chrome.storage.session?.setAccessLevel?.({ accessLevel: "TRUSTED_CONTEXTS" }).catch(() => {});

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  dispatchMessage(message, sender)
    .then(data => sendResponse({ ok: true, data }))
    .catch(error => sendResponse({ ok: false, error: publicError(error) }));
  return true;
});

async function dispatchMessage(message, sender) {
  if (!message || typeof message !== "object" || sender.id !== chrome.runtime.id) {
    throw new ApiError("不可信的插件请求", 403, "UNTRUSTED_SENDER");
  }

  switch (message.type) {
    case MESSAGE_TYPES.GET_STATE:
      return currentState(Boolean(message.refresh), sender);
    case MESSAGE_TYPES.SAVE_PLATFORM:
      requirePopup(sender);
      return savePlatform(message.baseUrl, message.deviceName);
    case MESSAGE_TYPES.OPEN_PLATFORM:
      requirePopup(sender);
      return openPlatform(message.baseUrl);
    case MESSAGE_TYPES.LOGIN:
      requirePopup(sender);
      return loginDirect(message);
    case MESSAGE_TYPES.PAIR:
      requirePopup(sender);
      return pair(message);
    case MESSAGE_TYPES.LOGOUT:
      requirePopup(sender);
      return logoutAndRevokePermission();
    case MESSAGE_TYPES.OPEN_PRODUCT_DETAIL:
      requireMarketplaceContentScript(sender);
      return prepareProductDetail(message.productUrl, sender);
    case MESSAGE_TYPES.TAKE_PENDING_CAPTURE:
      requireMarketplaceContentScript(sender);
      return takePendingCapture(sender);
    case MESSAGE_TYPES.CAPTURE:
      requireMarketplaceContentScript(sender);
      return captureProduct(message.payload, sender.tab.url);
    default:
      throw new ApiError("未知的插件请求", 400, "UNKNOWN_MESSAGE");
  }
}

async function prepareProductDetail(rawProductUrl, sender) {
  if (!(await readAuth())) throw new ApiError("请先登录赫逸平台账号", 401, "AUTH_REQUIRED");
  if (!Number.isInteger(sender.tab?.id)) throw new ApiError("无法确认当前标签页", 403, "TAB_REQUIRED");
  const platform = platformForPageUrl(sender.tab.url);
  const url = safeMarketplaceUrl(rawProductUrl, platform);
  const identity = marketplaceProductIdentity(url, platform);
  if (!identity?.productId) throw new ApiError("无法识别商品详情链接", 400, "INVALID_PRODUCT_URL");
  const pending = {
    url,
    platform,
    productId: identity.productId,
    shopId: identity.shopId || null,
    expiresAt: Date.now() + 3 * 60 * 1000
  };
  await chrome.storage.session.set({ [`${PENDING_CAPTURE_PREFIX}${sender.tab.id}`]: pending });
  return { url, pending: true };
}

async function takePendingCapture(sender) {
  if (!Number.isInteger(sender.tab?.id)) throw new ApiError("无法确认当前标签页", 403, "TAB_REQUIRED");
  const key = `${PENDING_CAPTURE_PREFIX}${sender.tab.id}`;
  const stored = await chrome.storage.session.get(key);
  const pending = stored[key];
  if (!pending || pending.expiresAt <= Date.now()) {
    if (pending) await chrome.storage.session.remove(key);
    return { pending: false };
  }
  const platform = platformForPageUrl(sender.tab.url);
  const identity = marketplaceProductIdentity(sender.tab.url, platform);
  if (platform !== pending.platform || identity?.productId !== pending.productId
    || (pending.shopId && identity?.shopId !== pending.shopId)) return { pending: false };
  await chrome.storage.session.remove(key);
  return { pending: true, productId: pending.productId };
}

async function currentState(refresh, sender) {
  let retryResult = null;
  if (refresh && isPopup(sender)) {
    retryResult = await retryRevocationsInBackground().catch(() => null);
  }
  const settings = await readSettings();
  let auth = await readAuth();
  if (!auth) auth = await autoLoginFromPlatform(settings).catch(() => null);
  if (auth && !(await chrome.permissions.contains({ origins: [originPattern(auth.baseUrl)] }))) {
    await logoutCurrentDevice({ deferOnFailure: true, reason: "REPAIR" });
    await setAuthBadge(false);
    auth = null;
  }
  if (refresh && auth) {
    try {
      auth = await refreshCurrentUser();
      await setAuthBadge(true);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        auth = null;
        await setAuthBadge(false);
      }
      else throw error;
    }
  }
  if (!auth) await clearPendingCaptures();
  const view = safeAuthView(settings, auth);
  return isPopup(sender)
    ? popupState(view, retryResult)
    : { authenticated: view.authenticated };
}

async function savePlatform(rawBaseUrl, rawDeviceName) {
  await retryRevocationsInBackground().catch(() => null);
  const baseUrl = normalizePlatformUrl(rawBaseUrl);
  await requireOriginPermission(baseUrl);
  const existing = await readAuth();
  let retirement = null;
  if (existing && existing.baseUrl !== baseUrl) {
    retirement = await logoutCurrentDevice({ deferOnFailure: true, reason: "SWITCH" });
    await clearPendingCaptures();
    await setAuthBadge(false);
  }
  const settings = await writeSettings({ baseUrl, deviceName: cleanText(rawDeviceName, 80) });
  if (existing && retirement?.revoked && originPattern(existing.baseUrl) !== originPattern(baseUrl)) {
    await chrome.permissions.remove({ origins: [originPattern(existing.baseUrl)] }).catch(() => false);
  }
  return popupState(safeAuthView(settings, await readAuth()), retirement);
}

async function openPlatform(rawBaseUrl) {
  await chrome.storage.local.set({heyiAutoLoginPaused:false});
  retryRevocationsInBackground().catch(() => {});
  const baseUrl = normalizePlatformUrl(rawBaseUrl);
  await chrome.tabs.create({ url: `${baseUrl}/#product-capture`, active: true });
  return { opened: true };
}

let autoLoginFlight = null;
let autoLoginLastAttempt = 0;
async function autoLoginFromPlatform(settings) {
  if ((await chrome.storage.local.get("heyiAutoLoginPaused")).heyiAutoLoginPaused) return null;
  if (autoLoginFlight) return autoLoginFlight;
  if (Date.now()-autoLoginLastAttempt < 10000) return null;
  autoLoginLastAttempt=Date.now();
  autoLoginFlight=(async()=>{
    for (const baseUrl of ['https://hoyee.org.cn']) {
    if (!(await chrome.permissions.contains({origins:[originPattern(baseUrl)]}))) continue;
    try {const auth=await connectBrowserSession(baseUrl,settings.deviceName||defaultDeviceName());await writeSettings({...settings,baseUrl});await setAuthBadge(true);return auth;} catch {}
    const tabs=await chrome.tabs.query({url:originPattern(baseUrl)});
    for (const tab of tabs) {
      if (!tab.id || new URL(tab.url).origin !== new URL(baseUrl).origin) continue;
      const results=await chrome.scripting.executeScript({target:{tabId:tab.id},func:async()=>{
        const r=await fetch('/api/v1/product-captures/extension/pairing-code',{method:'POST',credentials:'same-origin'});
        if(!r.ok)return null;return (await r.json())?.data?.code || null;
      }});
      const code=results?.[0]?.result;
      if(code) {const auth=await exchangePairingCode(baseUrl,code,settings.deviceName||defaultDeviceName());await writeSettings({...settings,baseUrl});await setAuthBadge(true);return auth;}
    }
    }
    return null;
  })();
  try {return await autoLoginFlight;} finally {autoLoginFlight=null;}
}
async function loginDirect(message) {
  const baseUrl=normalizePlatformUrl(message.baseUrl);
  await requireOriginPermission(baseUrl);
  await logoutCurrentDevice({deferOnFailure:true,reason:'SWITCH'});
  const settings=await writeSettings({baseUrl,deviceName:cleanText(message.deviceName,80)||defaultDeviceName()});
  const auth=await loginWithPassword(baseUrl,String(message.account||'').trim(),String(message.password||''),settings.deviceName);
  await chrome.storage.local.set({heyiAutoLoginPaused:false});
  await setAuthBadge(true);
  return popupState(safeAuthView(settings,auth));
}

async function pair(message) {
  await retryRevocationsInBackground().catch(() => null);
  const baseUrl = normalizePlatformUrl(message.baseUrl);
  await requireOriginPermission(baseUrl);
  const code = cleanPairingCode(message.code);
  const deviceName = cleanText(message.deviceName, 80) || defaultDeviceName();

  await clearPendingCaptures();
  const previous = await readAuth();
  const retirement = previous
    ? await logoutCurrentDevice({ deferOnFailure: true, reason: "SWITCH" })
    : null;
  if (retirement?.hadCredential) await setAuthBadge(false);
  const settings = await writeSettings({ baseUrl, deviceName });
  try {
    let auth = await exchangePairingCode(baseUrl, code, deviceName);
    if (!auth.user) auth = await refreshCurrentUser();
    await setAuthBadge(true);
    return popupState(safeAuthView(settings, auth), retirement);
  } catch (error) {
    const issuedAuth = await readAuth();
    if (issuedAuth) {
      try {
        await logoutCurrentDevice({ deferOnFailure: true, reason: "REPAIR" });
      } catch (cleanupError) {
        await setAuthBadge(true);
        throw cleanupError;
      }
    }
    await setAuthBadge(false);
    if (error instanceof ApiError && (error.status === 400 || error.status === 401 || error.status === 404)) {
      throw new ApiError("绑定码无效或已过期，请在平台重新生成", error.status, "PAIRING_CODE_INVALID");
    }
    throw error;
  }
}

async function logoutAndRevokePermission() {
  await chrome.storage.local.set({heyiAutoLoginPaused:true});
  const auth = await readAuth();
  const settings = await readSettings();
  const baseUrl = auth?.baseUrl || settings.baseUrl;
  const retirement = await logoutCurrentDevice({ deferOnFailure: true, reason: "LOGOUT" });
  await clearPendingCaptures();
  await setAuthBadge(false);
  if (!retirement.deferred && !(await pendingRevocationRequiresOrigin(baseUrl))) {
    await chrome.permissions.remove({ origins: [originPattern(baseUrl)] }).catch(() => false);
  }
  return popupState(safeAuthView(settings, null), {
    ...retirement,
    localUnbound: true
  });
}

async function pendingRevocationRequiresOrigin(baseUrl) {
  const origin = originPattern(baseUrl);
  const pending = await readPendingRevocations();
  return pending.some(item => originPattern(item.baseUrl) === origin);
}

async function captureProduct(rawPayload, senderUrl) {
  const senderPlatform = platformForPageUrl(senderUrl);
  const payload = sanitizeCapture(rawPayload, senderPlatform);
  const key = await idempotencyKey(payload);
  try {
    const result = await createCapture(payload, key);
    const videoResult = await archiveCaptureVideos(result?.id || result?.captureId, payload.domData.videos || []);
    retryRevocationsInBackground().catch(() => {});
    await setAuthBadge(true);
    return {
      id: result?.id || result?.captureId || null,
      productId: result?.productId || null,
      status: result?.status || "CAPTURED",
      completeness: result?.completeness || null,
      skuCompleteness: result?.skuCompleteness || null,
      videoCompleteness: videoResult && typeof videoResult === "object" ? videoResult : null,
      missingFields: Array.isArray(result?.missingFields) ? result.missingFields.slice(0, 50) : [],
      message: videoResult?.message || (typeof videoResult === "string" ? videoResult : "") || result?.message || (result?.completeness === "NEEDS_ENRICHMENT" ? "已采集，部分字段待补充" : "已提交到平台")
    };
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      await clearPendingCaptures();
      await setAuthBadge(false);
    }
    throw error;
  }
}

async function clearPendingCaptures() {
  const values = await chrome.storage.session.get(null);
  const keys = Object.keys(values).filter(key => key.startsWith(PENDING_CAPTURE_PREFIX));
  if (keys.length) await chrome.storage.session.remove(keys);
}

async function initializeStorageAndRevocations() {
  await restrictStorageToExtensionPages();
  await retryRevocationsInBackground();
}

async function retryRevocationsInBackground(options = {}) {
  if (revocationRetryInFlight) return revocationRetryInFlight;
  revocationRetryInFlight = retryPendingRevocations(options)
    .then(async result => {
      if (result.resolved?.length) await releaseResolvedOriginPermissions(result.resolved);
      return result;
    })
    .finally(() => { revocationRetryInFlight = null; });
  return revocationRetryInFlight;
}

async function releaseResolvedOriginPermissions(resolved) {
  const auth = await readAuth();
  const settings = await readSettings();
  for (const item of resolved) {
    const origin = originPattern(item.baseUrl);
    const activeOrigin = auth ? originPattern(auth.baseUrl) : null;
    const configuredOrigin = originPattern(settings.baseUrl);
    const keepForSwitch = item.reason === "SWITCH" && configuredOrigin === origin;
    if (activeOrigin === origin || keepForSwitch) continue;
    await chrome.permissions.remove({ origins: [origin] }).catch(() => false);
  }
}

async function popupState(view, outcome = null) {
  const count = await pendingRevocationCount();
  return {
    ...view,
    revocationPending: count > 0,
    pendingRevocationCount: count,
    revocationDeferred: Boolean(outcome?.deferred),
    localUnbound: Boolean(outcome?.localUnbound),
    revokedNow: Boolean(outcome?.revoked && outcome?.hadCredential),
    resolvedRevocationCount: Number(outcome?.revoked || 0)
  };
}

function sanitizeCapture(raw, senderPlatform) {
  if (!raw || typeof raw !== "object") throw new ApiError("没有可采集的商品数据", 400, "INVALID_CAPTURE");
  const sourceUrl = safeMarketplaceUrl(raw.sourceUrl || raw.productUrl, senderPlatform);
  const urlIdentity = marketplaceProductIdentity(sourceUrl, senderPlatform);
  const claimedPlatform = cleanText(raw.sourcePlatform, 24).toUpperCase();
  if (claimedPlatform && claimedPlatform !== senderPlatform) {
    throw new ApiError("商品平台与当前页面不一致", 400, "PLATFORM_MISMATCH");
  }

  const priceMin = safeNumber(raw.priceMin);
  const priceMax = safeNumber(raw.priceMax);
  const images = sanitizeImages(raw.images);
  const skus = sanitizeSkus(raw.skus);
  const claimedProductId = cleanNumericIdentifier(raw.sourceProductId);
  const claimedShopId = cleanNumericIdentifier(raw.sourceShopId);
  if (!urlIdentity?.productId || claimedProductId !== urlIdentity.productId) {
    throw new ApiError("商品 ID 与详情链接不一致", 400, "PRODUCT_ID_MISMATCH");
  }
  if (senderPlatform === "SHOPEE" && (!urlIdentity.shopId || claimedShopId !== urlIdentity.shopId)) {
    throw new ApiError("店铺 ID 与详情链接不一致", 400, "SHOP_ID_MISMATCH");
  }
  if (senderPlatform === "TIKTOK_SHOP" && urlIdentity.shopId && claimedShopId !== urlIdentity.shopId) {
    throw new ApiError("店铺 ID 与详情链接不一致", 400, "SHOP_ID_MISMATCH");
  }
  const variantHints = sanitizeVariantHints(raw.variantHints);
  const fieldSources = sanitizeFieldSources(raw.fieldSources);
  const skuCompleteness = sanitizeSkuCompleteness(raw.skuCompleteness, skus, raw.skus);
  const imageCompleteness = sanitizeImageCompleteness(raw.imageCompleteness, images, skus);

  return {
    sourceUrl,
    sourcePlatform: senderPlatform,
    title: cleanText(raw.title, 255),
    description: cleanMultilineText(raw.description, 20_000),
    categoryName: cleanText(raw.categoryName, 255) || null,
    brandName: cleanText(raw.brandName, 128) || null,
    currency: cleanCurrency(raw.currency),
    priceMin: priceMin ?? priceMax,
    priceMax: priceMax ?? priceMin,
    sourceProductId: claimedProductId,
    sourceShopId: claimedShopId,
    skus,
    images,
    imageCompleteness,
    fieldSources,
    enrichmentProvider: null,
    domData: {
      videos: deduplicateVideos(raw.videos),
      pageType: raw.pageType === "CARD" ? "CARD" : "DETAIL",
      pageUrl: cleanText(raw.pageUrl, 4_000),
      canonicalUrl: cleanText(raw.canonicalUrl, 4_000),
      locale: cleanText(raw.locale, 32),
      capturedAt: validIsoDate(raw.capturedAt),
      priceText: cleanText(raw.priceText, 240),
      originalPriceText: cleanText(raw.originalPriceText, 240),
      originalPriceMin: safeNumber(raw.originalPriceMin),
      originalPriceMax: safeNumber(raw.originalPriceMax),
      salesText: cleanText(raw.salesText, 160),
      salesCount: safeNumber(raw.salesCount),
      ratingText: cleanText(raw.ratingText, 160),
      ratingValue: boundedNumber(raw.ratingValue, 0, 5),
      ratingCount: safeNumber(raw.ratingCount),
      shopName: cleanText(raw.shopName, 160) || null,
      discountText: cleanText(raw.discountText, 120) || null,
      attributes: sanitizeAttributes(raw.attributes, 50, 100, 500),
      variantHints,
      skuCompleteness,
      imageCompleteness,
      extractionStrategies: cleanStringArray(raw.extractionStrategies, 12, 40)
    }
  };
}

function sanitizeImages(values) {
  if (!Array.isArray(values)) return [];
  const seen = new Set();
  const result = [];
  for (const value of values.slice(0, 240)) {
    const candidate = typeof value === "string" ? { url: value } : value;
    let url;
    try {
      const parsed = new URL(candidate?.url || candidate?.originalUrl || "");
      if (parsed.protocol !== "https:" || parsed.username || parsed.password) continue;
      url = parsed.toString();
    } catch {
      continue;
    }
    if (seen.has(url)) continue;
    seen.add(url);
    result.push({
      url,
      originalUrl: url,
      alt: cleanText(candidate?.alt || candidate?.altText, 300),
      position: result.length,
      role: cleanText(candidate?.role, 32) || (result.length === 0 ? "MAIN" : "GALLERY")
    });
    if (result.length >= 120) break;
  }
  return result;
}

function sanitizeSkus(values) {
  if (!Array.isArray(values)) return [];
  const result = [];
  const externalIds = new Set();
  for (const value of values.slice(0, 500)) {
    if (!value || typeof value !== "object") continue;
    const externalSkuId = cleanIdentifier(value.externalSkuId || value.skuId || value.id);
    const sellerSku = cleanIdentifier(value.sellerSku || value.sku || value.skuCode);
    const stock = safeInteger(value.stock ?? value.inventory);
    const variationName = cleanText(value.variationName || value.name, 300);
    if (!externalSkuId && !sellerSku && stock === null) continue;
    if (externalSkuId && externalIds.has(externalSkuId)) continue;
    if (externalSkuId) externalIds.add(externalSkuId);
    const attributes = sanitizeAttributes(value.attributes, 30, 80, 200);
    result.push({
      externalSkuId,
      sellerSku,
      variationName: variationName || null,
      price: safeNumber(value.price),
      originalPrice: safeNumber(value.originalPrice),
      originalPriceSource: cleanText(value.originalPriceSource, 60) || null,
      stock,
      stockSource: cleanText(value.stockSource, 60) || null,
      attributes,
      imageUrl: safeHttpsUrl(value.imageUrl || value.image || value.skuImage),
      imageSource: cleanText(value.imageSource, 60) || null,
      imageInherited: Boolean(value.imageInherited),
      status: cleanText(value.status, 40) || null
    });
  }
  return result;
}

function sanitizeSkuCompleteness(value, skus, rawSkus) {
  const raw = value && typeof value === "object" && !Array.isArray(value) ? value : {};
  const expectedCount = safeInteger(raw.expectedCount) ?? 0;
  const variantDimensionCount = safeInteger(raw.variantDimensionCount) ?? 0;
  const issues = cleanStringArray(raw.issues, 80, 100)
    .filter(issue => !/^SKU_\d+_STOCK_MISSING$/.test(issue) && issue !== "STOCK_MISSING");
  const ids = skus.map(sku => sku.externalSkuId).filter(Boolean);
  const rawIds = Array.isArray(rawSkus)
    ? rawSkus.map(sku => cleanIdentifier(sku?.externalSkuId || sku?.skuId || sku?.id)).filter(Boolean)
    : [];
  const hasDuplicateIds = new Set(ids).size !== ids.length || new Set(rawIds).size !== rawIds.length;
  if (expectedCount !== skus.length && !issues.includes("SKU_COUNT_MISMATCH")) issues.push("SKU_COUNT_MISMATCH");
  if (hasDuplicateIds && !issues.includes("DUPLICATE_MODEL_ID")) issues.push("DUPLICATE_MODEL_ID");
  const completeFields = skus.length > 0 && skus.every(sku => sku.externalSkuId
    && sku.price !== null && sku.originalPrice !== null && sku.originalPrice >= sku.price
    && sku.imageUrl
    && (variantDimensionCount === 0 || Object.keys(sku.attributes).length === variantDimensionCount));
  const complete = raw.complete === true && expectedCount > 0 && expectedCount === skus.length
    && !hasDuplicateIds && completeFields && issues.length === 0;
  return {
    expectedCount,
    capturedCount: skus.length,
    variantDimensionCount,
    complete,
    source: cleanText(raw.source, 60) || "UNAVAILABLE",
    issues
  };
}

function sanitizeImageCompleteness(value, images, skus) {
  const raw = value && typeof value === "object" && !Array.isArray(value) ? value : {};
  const imageUrls = new Set(images.map(image => image.url));
  const requiredSkuUrls = new Set(skus.map(sku => sku.imageUrl).filter(Boolean));
  const calculatedCovered = [...requiredSkuUrls].filter(url => imageUrls.has(url)).length;
  const expectedCount = Math.max(safeInteger(raw.expectedCount) ?? images.length, images.length);
  const capturedCount = Math.min(safeInteger(raw.capturedCount) ?? images.length, images.length);
  const requiredSkuImageCount = Math.max(
    safeInteger(raw.requiredSkuImageCount) ?? requiredSkuUrls.size,
    requiredSkuUrls.size
  );
  const coveredSkuImageCount = Math.min(
    safeInteger(raw.coveredSkuImageCount) ?? calculatedCovered,
    calculatedCovered
  );
  const issues = cleanStringArray(raw.issues, 40, 100);
  if (!images.length && !issues.includes("IMAGES_MISSING")) issues.push("IMAGES_MISSING");
  if (requiredSkuImageCount !== coveredSkuImageCount
      && !issues.includes("VARIANT_IMAGE_COVERAGE_TRUNCATED")) {
    issues.push("VARIANT_IMAGE_COVERAGE_TRUNCATED");
  }
  const selectedRoleCounts = countImageRoles(images);
  const roleCounts = sanitizeImageRoleCounts(raw.roleCounts, selectedRoleCounts);
  return {
    expectedCount,
    capturedCount,
    requiredSkuImageCount,
    coveredSkuImageCount,
    complete: raw.complete === true && expectedCount === capturedCount
      && requiredSkuImageCount === coveredSkuImageCount && issues.length === 0,
    roleCounts,
    selectedRoleCounts,
    source: cleanText(raw.source, 60) || null,
    issues
  };
}

function countImageRoles(images) {
  const counts = { MAIN: 0, GALLERY: 0, VARIANT: 0, DETAIL: 0, SIZE_CHART: 0 };
  for (const image of images) counts[listingImageRole(image?.role)] += 1;
  return counts;
}

function sanitizeImageRoleCounts(value, minimums) {
  const raw = value && typeof value === "object" && !Array.isArray(value) ? value : {};
  return Object.fromEntries(Object.keys(minimums).map(role => [
    role,
    Math.max(safeInteger(raw[role]) ?? minimums[role], minimums[role])
  ]));
}

function listingImageRole(value) {
  const role = cleanText(value, 32).toUpperCase();
  if (role === "MAIN") return "MAIN";
  if (role === "SIZE_CHART") return "SIZE_CHART";
  if (["VARIANT", "SKU", "MODEL", "VARIANT_OPTION"].includes(role)) return "VARIANT";
  if (["DETAIL", "DESCRIPTION"].includes(role)) return "DETAIL";
  return "GALLERY";
}

function sanitizeAttributes(value, maxItems, maxKeyLength, maxValueLength) {
  const result = {};
  if (!value || typeof value !== "object" || Array.isArray(value)) return result;
  for (const [key, rawValue] of Object.entries(value).slice(0, maxItems)) {
    const safeKey = cleanText(key, maxKeyLength);
    const safeValue = cleanText(rawValue, maxValueLength);
    if (safeKey && safeValue) result[safeKey] = safeValue;
  }
  return result;
}

function safeHttpsUrl(value) {
  if (!value) return null;
  try {
    const url = new URL(String(value));
    return url.protocol === "https:" && !url.username && !url.password ? url.toString() : null;
  } catch {
    return null;
  }
}

function sanitizeVariantHints(values) {
  if (!Array.isArray(values)) return [];
  return values.slice(0, 30).map(value => ({
    name: cleanText(value?.name, 120) || "规格",
    values: cleanStringArray(value?.values, 50, 160)
  })).filter(value => value.values.length > 0);
}

function sanitizeFieldSources(values) {
  if (!Array.isArray(values)) return [];
  return values.slice(0, 50).map(value => ({
    field: cleanText(value?.field || value?.fieldName, 80),
    source: cleanText(value?.source || value?.sourceType, 40) || "DOM",
    path: cleanText(value?.path || value?.sourcePath, 300),
    confidence: boundedNumber(value?.confidence, 0, 1)
  })).filter(value => value.field);
}

function safeMarketplaceUrl(value, expectedPlatform) {
  let url;
  try {
    url = new URL(String(value || ""));
  } catch {
    throw new ApiError("商品链接无效", 400, "INVALID_PRODUCT_URL");
  }
  if (url.protocol !== "https:" || url.username || url.password) {
    throw new ApiError("商品链接必须使用 HTTPS", 400, "INVALID_PRODUCT_URL");
  }
  const actualPlatform = platformForPageUrl(url.toString(), true);
  if (actualPlatform !== expectedPlatform) {
    throw new ApiError("商品链接不属于当前平台", 400, "INVALID_PRODUCT_URL");
  }
  const result = url.toString();
  if (result.length > 2_048) throw new ApiError("商品链接过长，无法安全提交", 400, "INVALID_PRODUCT_URL");
  return result;
}

function platformForPageUrl(value, productUrl = false) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new ApiError("不支持当前页面", 400, "UNSUPPORTED_PAGE");
  }
  const host = url.hostname.toLowerCase();
  const shopeeMarkets = ["shopee.sg", "shopee.com.my", "shopee.ph", "shopee.co.th", "shopee.co.id", "shopee.vn"];
  if (shopeeMarkets.some(domain => host === domain || host.endsWith(`.${domain}`))) {
    const itemQuery = url.searchParams.get("itemid") || url.searchParams.get("item_id");
    const shopQuery = url.searchParams.get("shopid") || url.searchParams.get("shop_id");
    const queryIdentity = /^\d+$/.test(itemQuery || "") && /^\d+$/.test(shopQuery || "");
    if (productUrl && !(/-i\.\d+\.\d+(?:$|[/?])/i.test(url.pathname) || /\/product\/\d+\/\d+/i.test(url.pathname) || queryIdentity)) {
      throw new ApiError("无法识别 Shopee 商品链接", 400, "INVALID_PRODUCT_URL");
    }
    return "SHOPEE";
  }
  if (host === "tiktok.com" || host.endsWith(".tiktok.com")) {
    const path = url.pathname.toLowerCase();
    const supported = host === "shop.tiktok.com" || path.startsWith("/shop/")
      || path.startsWith("/view/product/") || path.startsWith("/pdp/")
      || /^\/[a-z]{2}(?:-[a-z]{2})?\/(?:shop\/)?(?:pdp|product)\//i.test(path);
    if (!supported) throw new ApiError("当前页面不是 TikTok Shop 页面", 400, "UNSUPPORTED_PAGE");
    if (productUrl && !(/\/view\/product\/[^/]+/i.test(path)
      || /\/(?:(?:[a-z]{2}(?:-[a-z]{2})?)\/)?(?:shop\/)?(?:pdp|product)\//i.test(path)
      || url.searchParams.has("product_id") || url.searchParams.has("productId"))) {
      throw new ApiError("无法识别 TikTok Shop 商品链接", 400, "INVALID_PRODUCT_URL");
    }
    return "TIKTOK_SHOP";
  }
  throw new ApiError("仅支持 Shopee 与 TikTok Shop", 400, "UNSUPPORTED_PAGE");
}

function marketplaceProductIdentity(value, platform) {
  let url;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (platform === "SHOPEE") {
    const direct = url.pathname.match(/-i\.(\d+)\.(\d+)(?:$|[/?])/i)
      || url.pathname.match(/\/product\/(\d+)\/(\d+)(?:$|[/?])/i);
    if (direct) return { shopId: direct[1], productId: direct[2] };
    const shopId = url.searchParams.get("shopid") || url.searchParams.get("shop_id");
    const productId = url.searchParams.get("itemid") || url.searchParams.get("item_id");
    return /^\d{1,32}$/.test(shopId || "") && /^\d{1,32}$/.test(productId || "")
      ? { shopId, productId }
      : null;
  }
  if (platform === "TIKTOK_SHOP") {
    let path;
    try {
      path = decodeURIComponent(url.pathname);
    } catch {
      path = url.pathname;
    }
    const direct = path.match(/\/view\/product\/(\d{6,32})/i);
    const pdp = path.match(/\/(?:(?:[a-z]{2}(?:-[a-z]{2})?)\/)?(?:shop\/)?(?:pdp|product)\/(?:[^/?]*?[-/])?(\d{8,32})(?:$|[/?-])/i);
    const query = url.searchParams.get("product_id") || url.searchParams.get("productId");
    const productId = direct?.[1] || pdp?.[1] || (/^\d{6,32}$/.test(query || "") ? query : null);
    return productId ? { productId, shopId: cleanNumericIdentifier(url.searchParams.get("shop_id")) } : null;
  }
  return null;
}

async function idempotencyKey(payload) {
  const identity = payload.sourceProductId
    ? payload.sourcePlatform === "TIKTOK_SHOP"
      ? `${payload.sourcePlatform}|${payload.sourceProductId}`
      : `${payload.sourcePlatform}|${payload.sourceShopId || ""}|${payload.sourceProductId}`
    : `${payload.sourcePlatform}|${payload.sourceUrl}`;
  const bytes = new TextEncoder().encode(identity);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const hex = [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, "0")).join("");
  return `capture-${hex}`;
}

async function requireOriginPermission(baseUrl) {
  const origins = [originPattern(baseUrl)];
  if (!(await chrome.permissions.contains({ origins }))) {
    throw new ApiError("请先授权插件连接这个平台地址", 403, "HOST_PERMISSION_REQUIRED");
  }
}

function requirePopup(sender) {
  if (!isPopup(sender)) throw new ApiError("此操作只能在插件窗口中执行", 403, "POPUP_REQUIRED");
}

function isPopup(sender) {
  return sender.id === chrome.runtime.id
    && typeof sender.url === "string"
    && sender.url === chrome.runtime.getURL("src/popup/popup.html");
}

function requireMarketplaceContentScript(sender) {
  if (!sender.tab?.url) throw new ApiError("无法确认商品页面", 403, "CONTENT_SCRIPT_REQUIRED");
  platformForPageUrl(sender.tab.url);
}

function cleanPairingCode(value) {
  const code = String(value || "").trim();
  if (code.length < 6 || code.length > 160 || !/^[A-Za-z0-9._~-]+$/.test(code)) {
    throw new ApiError("请输入平台生成的有效绑定码", 400, "INVALID_PAIRING_CODE");
  }
  return code;
}

function cleanText(value, maxLength) {
  if (value === null || value === undefined) return "";
  return String(value).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, " ")
    .replace(/\s+/g, " ").trim().slice(0, maxLength);
}

function cleanMultilineText(value, maxLength) {
  if (value === null || value === undefined) return "";
  return String(value).replace(/\r\n?/g, "\n")
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, " ")
    .trim().slice(0, maxLength);
}

function cleanIdentifier(value) {
  const normalized = cleanText(value, 128);
  return /^[A-Za-z0-9._:-]+$/.test(normalized) ? normalized : null;
}

function cleanNumericIdentifier(value) {
  const normalized = cleanText(value, 32);
  return /^\d{1,32}$/.test(normalized) ? normalized : null;
}

function cleanCurrency(value) {
  const normalized = cleanText(value, 8).toUpperCase();
  return /^[A-Z]{3}$/.test(normalized) ? normalized : null;
}

function cleanStringArray(values, maxItems, maxLength) {
  if (!Array.isArray(values)) return [];
  return [...new Set(values.map(value => cleanText(value, maxLength)).filter(Boolean))].slice(0, maxItems);
}

function safeNumber(value) {
  if (value === null || value === undefined || (typeof value === "string" && !value.trim())) return null;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 && number <= 1_000_000_000_000 ? number : null;
}

function safeInteger(value) {
  if (value === null || value === undefined || (typeof value === "string" && !value.trim())) return null;
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 0 && number <= 1_000_000_000 ? number : null;
}

function boundedNumber(value, minimum, maximum) {
  if (value === null || value === undefined || (typeof value === "string" && !value.trim())) return null;
  const number = Number(value);
  return Number.isFinite(number) && number >= minimum && number <= maximum ? number : null;
}

function validIsoDate(value) {
  const parsed = Date.parse(String(value || ""));
  return Number.isFinite(parsed) ? new Date(parsed).toISOString() : new Date().toISOString();
}

function defaultDeviceName() {
  const platform = navigator.userAgentData?.platform || navigator.platform || "Chrome";
  return `Chrome · ${cleanText(platform, 48)}`;
}

async function setAuthBadge(authenticated) {
  await chrome.action.setBadgeBackgroundColor({ color: authenticated ? "#16845b" : "#c83b3b" });
  await chrome.action.setBadgeText({ text: authenticated ? "" : "!" });
}

function publicError(error) {
  if (error instanceof ApiError) {
    return {
      status: error.status,
      code: error.code,
      kind: error.status === 426 ? "UPDATE"
        : error.status === 401 ? "AUTH"
        : error.status === 403 ? "PERMISSION"
          : error.status === 429 ? "RATE_LIMIT"
            : error.code === "NETWORK_ERROR" ? "NETWORK"
              : "REQUEST",
      message: error.message
    };
  }
  return { status: 0, code: "UNEXPECTED", kind: "REQUEST", message: "插件暂时无法完成操作" };
}
