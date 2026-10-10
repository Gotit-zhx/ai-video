import { supportsNativeTransparentPng } from "./image-output-capabilities.js";

const COPYRIGHT_FALLBACK_MODEL_ID = "grok-imagine-image-2.0";
const COPYRIGHT_REJECTION_STATUSES = new Set([400, 403, 422]);

function authorizedCopyrightFallback(config, error) {
  const copyright = error?.failureKind === "COPYRIGHT" && error?.requestState === "REJECTED"
    && COPYRIGHT_REJECTION_STATUSES.has(error?.providerStatus);
  const connection = ["NETWORK", "NETWORK_TIMEOUT"].includes(error?.failureKind)
    && ["NOT_SENT", "RESPONSE_TIMEOUT", "ACCEPTED_OR_UNKNOWN"].includes(error?.requestState)
    && error?.retryAuthorized === true;
  if ((!copyright && !connection) || config?.modelId === COPYRIGHT_FALLBACK_MODEL_ID) {
    return null;
  }
  const routes = Array.isArray(config?.copyrightFallbackModels)
    ? config.copyrightFallbackModels : [];
  const baseUrl = typeof config?.baseUrl === "string" ? config.baseUrl.replace(/\/+$/, "") : "";
  if (!baseUrl) return null;
  return routes.find((route) => route?.modelId === COPYRIGHT_FALLBACK_MODEL_ID
    && typeof route.baseUrl === "string" && route.baseUrl.replace(/\/+$/, "") === baseUrl
    && route.apiFormat === config.apiFormat
    && typeof route.apiKey === "string" && route.apiKey.trim().length > 0) || null;
}

function attemptCount(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

export async function callImageModelWithCopyrightFallback(config, params, inputImages, callSingleImageModel) {
  const requiresNativeAlpha = params?.background === "transparent";
  const detailGrid = params?.detailGrid === true && !requiresNativeAlpha;
  const productMockup = params?.productMockup === true || detailGrid;
  const singleSubmission = requiresNativeAlpha || productMockup;
  if (requiresNativeAlpha && !supportsNativeTransparentPng(config)) {
    throw Object.assign(new Error("当前图片模型未确认支持原生透明 PNG，尚未发起模型调用"), {
      requestState: "REJECTED", failureKind: "PROVIDER", failureCode: "NATIVE_TRANSPARENT_PNG_UNSUPPORTED",
      retrySafe: false, retryAuthorized: false, safeRetryCount: 0, networkRetryCount: 0,
      imageOutputRequirement: { code: "NATIVE_TRANSPARENT_PNG_REQUIRED", background: "transparent", outputFormat: "png", fallbackSkipped: false }
    });
  }
  let attempts = 0;
  const onProgress = params?.onProgress;
  const hasBackup = authorizedCopyrightFallback(config, {failureKind:"COPYRIGHT", requestState:"REJECTED", providerStatus:400});
  const paramsWithProgress = (offset, maxNetworkRetries) => ({
    ...params,
    ...(maxNetworkRetries !== undefined ? {maxNetworkRetries} : {}),
    // Provider refusals are counted by the durable job ledger. Only the dedicated
    // detail grid may recover its connection, within the persisted operation budget.
    ...(singleSubmission ? {maxProviderRetries: 0} : {}),
    onProgress: (stage, localAttempts) => {
      attempts = Math.max(attempts, offset + attemptCount(localAttempts));
      onProgress?.(stage, attempts);
    }
  });

  let primaryError;
  let fallbackRoute;
  try {
    // Transparent artwork and product scenes keep one POST. The grid opts into
    // two durable network recoveries on this model; it cannot use a backup.
    return await callSingleImageModel(config, paramsWithProgress(0, detailGrid ? 2 : singleSubmission || hasBackup ? 0 : undefined), inputImages);
  } catch (error) {
    // Final commerce photos also own a durable budgeted attempt. A timeout or
    // refusal must not silently spend another POST or switch to another model.
    if (productMockup && !requiresNativeAlpha) throw error;
    fallbackRoute = authorizedCopyrightFallback(config, error);
    if (requiresNativeAlpha) {
      // Retain the primary's exact refusal/unknown/download state. In particular,
      // a timeout must never become a definitive failure or another billable POST.
      error.imageOutputRequirement = {
        code: "NATIVE_TRANSPARENT_PNG_REQUIRED", background: "transparent", outputFormat: "png",
        fallbackSkipped: Boolean(fallbackRoute)
      };
      throw error;
    }
    if (!fallbackRoute) throw error;
    primaryError = error;
  }

  // safeRetryCount remains local to each model; only displayed POST counts span models.
  attempts = Math.max(attempts, attemptCount(primaryError.safeRetryCount) + 1);
  const fallback = {
    reason: primaryError.failureKind === "COPYRIGHT" ? "COPYRIGHT" : "NETWORK",
    fromModelId: config.modelId,
    toModelId: COPYRIGHT_FALLBACK_MODEL_ID
  };
  const fallbackConfig = {
    ...config,
    modelId: COPYRIGHT_FALLBACK_MODEL_ID,
    apiKey: fallbackRoute.apiKey,
    copyrightFallbackModels: []
  };
  onProgress?.("SWITCHING_MODEL", attempts);

  let result;
  try {
    result = await callSingleImageModel(fallbackConfig, paramsWithProgress(attempts, fallback.reason === "NETWORK" ? 1 : undefined), inputImages);
  } catch (error) {
    error.imageModelFallback = fallback;
    throw error;
  }
  return {
    ...result,
    ...(result?.result ? { result: { ...result.result, modelId: fallbackConfig.modelId } } : {}),
    ...(result?.artifact ? { artifact: { ...result.artifact, modelId: fallbackConfig.modelId } } : {}),
    requestMeta: { ...result?.requestMeta, fallback }
  };
}
