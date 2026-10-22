const loginView = document.querySelector("#login-view");
const dashboardView = document.querySelector("#dashboard-view");
const loginForm = document.querySelector("#login-form");
const loginError = document.querySelector("#login-error");
const loginButton = document.querySelector("#login-button");
const passwordInput = document.querySelector("#password");
const socialLoginButtons = [...document.querySelectorAll("[data-social-provider]")];
const socialLoginStatus = document.querySelector("#social-login-status");
const loginMethodTabs = [...document.querySelectorAll(".login-method-switch [data-login-method]")];
const loginMethodTriggers = [...document.querySelectorAll("[data-login-method]")];
const wechatQrFrame = document.querySelector("#wechat-qr-frame");
const wechatQrCode = document.querySelector("#wechat-qr-code");
const wechatLoginButton = document.querySelector("#wechat-login-button");
const socialBindDialog = document.querySelector("#social-bind-dialog");
const socialBindForm = document.querySelector("#social-bind-form");
const sidebarToggle = document.querySelector("#sidebar-toggle");

let sidebarExpanded = false;

function applySidebarExpanded(expanded) {
  sidebarExpanded = Boolean(expanded);
  dashboardView.classList.toggle("sidebar-expanded", sidebarExpanded);
  if (!sidebarToggle) return;
  const actionLabel = sidebarExpanded ? "收起主导航" : "展开主导航";
  sidebarToggle.setAttribute("aria-expanded", String(sidebarExpanded));
  sidebarToggle.setAttribute("aria-label", actionLabel);
  sidebarToggle.title = actionLabel;
}

sidebarToggle?.addEventListener("click", () => applySidebarExpanded(!sidebarExpanded));
document.addEventListener("keydown", event => {
  if (event.key === "Escape" && sidebarExpanded && window.matchMedia("(min-width: 901px)").matches) {
    applySidebarExpanded(false);
    sidebarToggle?.focus();
  }
});
applySidebarExpanded(true);

const commerceSections = {
  "commerce-assets": "素材管理",
  "product-capture": "采集链接",
  "product-main-image-styles": "主图风格模板",
  "commerce-designs": "一键裂变",
  "commerce-templates": "模板配置",
  "commerce-batches": "批次上架",
  "commerce-ads": "广告测款"
};
function commerceDataModule(route = window.location.hash.slice(1).split("?")[0]) {
  return {"commerce-designs":"FISSION","commerce-batches":"FISSION",
    "commerce-templates":"TEMPLATES","commerce-ads":"ADS","commerce-assets":"ASSETS"}[route] || "CAPTURE";
}
function commerceRouteFromHash() {
  const route = window.location.hash.slice(1).split("?")[0];
  return Object.hasOwn(commerceSections, route) ? route : null;
}
function selectCommerceSection() {
  const route = commerceRouteFromHash() || "product-capture";
  document.querySelectorAll("[data-commerce-panel]").forEach(panel => { panel.hidden = panel.dataset.commercePanel !== route; });
  document.querySelectorAll("[data-commerce-route]").forEach(link => {
    const active = link.dataset.commerceRoute === route;
    link.classList.toggle("active", active);
    if (active) link.setAttribute("aria-current", "page"); else link.removeAttribute("aria-current");
  });
  const workbenchActive=route==='product-capture';
  document.querySelector("#nav-publish").classList.toggle("active",workbenchActive);
  if(workbenchActive)document.querySelector('#nav-publish').setAttribute('aria-current','page');else document.querySelector('#nav-publish').removeAttribute('aria-current');
  document.querySelector(".topbar h2").textContent = `商品运营 / ${commerceSections[route]}`;
  window.history.replaceState(null, document.title, `${window.location.pathname}${window.location.search}#${route}${route === "product-capture" ? window.location.hash.slice(1).replace(/^[^?]*/, "") : ""}`);
  syncProductCaptureView();
  window.dispatchEvent(new CustomEvent("heyi:commerce-section-entered", {detail:{route}}));
  if(route!=="product-capture" && route!=="product-main-image-styles"){
    const panel=document.querySelector(`[data-commerce-panel="${route}"]`);
    const heading=route==="commerce-designs"?document.querySelector(".commerce-batch-hub header"):panel?.querySelector("header");
    if(heading&&!heading.querySelector("[data-module-listing-settings]")){
      const settings=document.createElement("button");settings.type="button";
      settings.dataset.moduleListingSettings="";settings.textContent="上传配置";
      settings.addEventListener("click",()=>ProductListingTemplates.open());
      heading.append(settings);
    }
  }
}
document.querySelectorAll(".nav-group > summary").forEach(summary => {
  summary.addEventListener("click", () => { if (!sidebarExpanded) applySidebarExpanded(true); });
});

const THEME_MODE_STORAGE_KEY = "heyi:theme-mode";
const supportedThemeModes = new Set(["light", "dark", "auto"]);
let selectedThemeMode = supportedThemeModes.has(document.documentElement.dataset.themeMode)
  ? document.documentElement.dataset.themeMode
  : "auto";

function resolvedThemeForMode(mode, now = new Date()) {
  if (mode === "light" || mode === "dark") return mode;
  const beijingHour = (now.getUTCHours() + 8) % 24;
  return beijingHour >= 7 && beijingHour < 19 ? "light" : "dark";
}

function syncThemeModeControls() {
  const resolvedTheme = resolvedThemeForMode(selectedThemeMode);
  document.querySelectorAll(".theme-mode-option[data-theme-choice]").forEach(button => {
    const choice = button.dataset.themeChoice;
    const active = choice === selectedThemeMode;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
    if (choice === "light") button.title = "日间：始终使用浅色主题";
    if (choice === "dark") button.title = "夜间：始终使用深色主题";
    if (choice === "auto") {
      button.title = `自动：北京时间 07:00–18:59 使用日间主题，当前为${resolvedTheme === "light" ? "日间" : "夜间"}`;
    }
  });
}

function applyThemeMode(mode, { persist = false } = {}) {
  selectedThemeMode = supportedThemeModes.has(mode) ? mode : "auto";
  const resolvedTheme = resolvedThemeForMode(selectedThemeMode);
  document.documentElement.dataset.themeMode = selectedThemeMode;
  document.documentElement.dataset.theme = resolvedTheme;
  document.documentElement.style.colorScheme = resolvedTheme;
  if (persist) {
    try {
      localStorage.setItem(THEME_MODE_STORAGE_KEY, selectedThemeMode);
    } catch (_) {
      // The selected mode still applies for this page when storage is unavailable.
    }
  }
  syncThemeModeControls();
}

function refreshAutomaticTheme() {
  if (selectedThemeMode === "auto") applyThemeMode("auto");
}

document.addEventListener("click", event => {
  const button = event.target.closest?.(".theme-mode-option[data-theme-choice]");
  if (button) applyThemeMode(button.dataset.themeChoice, { persist: true });
});

window.addEventListener("storage", event => {
  if (event.key === THEME_MODE_STORAGE_KEY) applyThemeMode(event.newValue || "auto");
});
window.addEventListener("focus", refreshAutomaticTheme);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) refreshAutomaticTheme();
});
window.setInterval(refreshAutomaticTheme, 60_000);
applyThemeMode(selectedThemeMode);

let departments = [];
let roles = [];
let permissions = [];
let users = [];
let knowledgeWriterAccess = {};
let organization = null;
let organizations = [];
let modelConfigs = [];
let selectedModelOrganizationId = "";
let selectedPeopleOrganizationId = "";
let userDialogDepartments = [];
let userDialogRoles = [];
const rememberedOrganizationCode = localStorage.getItem("heyi:last-organization-code");
if (rememberedOrganizationCode) {
  document.querySelector("#organization-code").value = rememberedOrganizationCode;
}
let selectedDepartmentId = "";
let selectedRoleId = "";
let currentUser = null;
let activeWorkspace = "";
let pendingWorkspaceAfterLogin = "";
let pendingProductCaptureAfterLogin = "";
let sessionExpiryActive = false;
let assistantSessionId = createAssistantSessionId();
let assistantCommandPoll = null;
let assistantLastChatUsage = {};
let assistantSessionChatUsage = {};
let assistantCurrentCommandIds = new Set();
let assistantLastCommands = [];
let assistantReferenceImages = [];
let assistantImageUploadsInFlight = 0;
let assistantImageModelReady = false;
let workflowEventSource = null;
let currentWorkflowTaskId = null;
let automationSkills = [];
let selectedAutomationCode = null;
let managedAutomationSkills = [];
let automationJobs = [];
let automationRunPoll = null;
let pendingAutomationPlan = null;
let pendingAutomationParameters = null;
let automationPlanRequestInFlight = false;
let localAutomationAssistant = null;
let automationAssistantHeartbeat = null;
let automationFormSkillCode = null;
let automationRuntimeSkillCode = null;
let automationFormItems = [];
let selectedAutomationFormItemId = null;
let automationFormPaletteSortable = null;
let automationFormCanvasSortable = null;
let skillConvertStatusTimer = null;
let videoScriptStatusTimer = null;
let videoScriptPollingGeneration = 0;
let videoScriptLoadGeneration = 0;
let videoScriptDetailGeneration = 0;
let videoScriptProgressId = null;
let activeVideoScript = null;
let selectedVideoScriptId = null;
let selectedVideoScriptDetail = null;
let selectedVideoShot = null;
let videoScriptVisualProgress = 0;
let videoScriptVisualStatus = "";
let videoScriptVisualKey = "";
let videoScriptVisualServerProgress = 0;
let videoScriptVisualProgressTimer = null;
let videoScriptVisualProgressUpdatedAt = 0;
const videoScriptProgressStates = new Map();
let manualProductProgressTimer = null;
let manualProductProgressStartedAt = 0;
let manualProductReferences = [];
let manualProductReferenceSequence = 0;
let videoScriptClipObjectUrl = "";
let videoScriptClipDuration = 0;
let videoScriptClipStartSecond = 0;
let videoScriptClipEndSecond = 0;
let videoScriptClipPreviewing = false;
let videoScriptClipLoadGeneration = 0;
const videoScriptClipMaxDuration = 15;
const manualProductReferenceLimit = 6;
const manualProductImageByteLimit = 20 * 1024 * 1024;
const manualProductTotalByteLimit = 60 * 1024 * 1024;
let videoScripts = [];
let aiVideoUsableScripts = [];
let videoScriptSearchQuery = "";
let aiVideoTasks = [];
let aiVideoTaskSearchQuery = "";
let selectedAiVideoTaskId = null;
let aiVideoTaskDetail = null;
let aiVideoTaskPollTimer = null;
let aiVideoTaskPollInFlight = false;
let aiVideoTaskLoadGeneration = 0;
let aiVideoTaskRenderSignature = "";
let aiVideoElapsedTimer = null;
let aiVideoMonitorSnapshot = { stats: {}, tasks: [] };
let selectedMonitorTaskId = null;
let selectedMonitorTaskDetail = null;
let aiVideoMonitorStatus = "ALL";
let aiVideoMonitorTimer = null;
let aiVideoMonitorLoadGeneration = 0;
let slidingSwitchFrame = 0;
const flippedAiVideoPromptItems = new Set();
const aiVideoPromptDrafts = new Map();
const expandedAiVideoItemIds = new Set();
let generalVideoTasks = [];
let generalVideoReferences = [];
let generalVideoReference = null;
let generalMentionStart = null;
let generalVideoLoadGeneration = 0;
let generalVideoPollTimer = null;
let generalVideoPollInFlight = false;
const expandedGeneralVideoTaskIds = new Set();
const generalVideoTaskDetails = new Map();
let productCaptures = [];
let selectedProductCaptureId = null;
const productBulkSelectedIds = new Set();
const productBulkSelectedCaptures = new Map();
let productBulkItems = [];
let productBulkStorageKey = "";
let productBulkOrganization = "";
let productBulkBusy = false;
let productBulkPollTimer = null;
let selectedProductCaptureDetail = null;
let productCaptureStatusFilter = "CAPTURED";
let productCaptureSearchQuery = "";
let productCaptureLoadGeneration = 0;
let productCaptureDetailLoadGeneration = 0;
let productCapturePage = -1;
let productCaptureTotalElements = 0;
let productCaptureTotalPages = 0;
let productCaptureStatusCounts = {};
let productCaptureStatusCountsKnown = false;
let productCaptureLegacyListResponse = false;
let productCaptureListLoading = false;
let productCaptureAppendLoading = false;
let productCapturePaginationMessage = "";
let productCaptureSearchDebounceTimer = null;
let productCapturePollTimer = null;
let productCapturePollInFlight = false;
let productPairingExpiresAt = null;
let productPairingCountdownTimer = null;
let productPublishIdempotencyKey = "";
let productPublishIdempotencyCaptureId = "";
let productUploadInFlightCaptureId = "";
let productPublishWorkflowState = null;
let productPublishWorkflowPollTimer = null;
let productPublishWorkflowPollGeneration = 0;
let productPublishWorkflowLookupGeneration = 0;
const finalizedProductPublishWorkflowIds = new Set();
let marketplaceShops = [];
let marketplaceShopPlatformFilter = "ALL";
let marketplaceShopStatusFilter = "ALL";
let marketplaceShopLoadGeneration = 0;
const marketplaceShopActionsInFlight = new Set();
let marketplaceIntegrationStatus = {
  shopeeConfigured: false,
  tiktokShopConfigured: false,
  livePublishingEnabled: false,
  publishingProvider: "DRY_RUN"
};
let productLastNotice = null;
let productSkuCompletenessDirty = false;
let productSkuDraftDirty = false;
let productSkuDraftCaptureId = null;
let productSkuDraftState = [];
let selectedProductSkuIndex = 0;
let productSkuSearchQuery = "";
let productEditorDirty = false;
let productCaptureSaveInFlightId = null;
const productImageProcessingMeta = new Map();
const productPublishAuditMeta = new Map();
let socialProviderStatuses = new Map();

function createLoginParticleField() {
  const canvas = document.querySelector("#login-particle-field");
  const cursorLens = document.querySelector("#login-cursor-lens");
  if (!canvas || typeof canvas.getContext !== "function") return;
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const coarsePointer = window.matchMedia("(pointer: coarse)");
  const context = canvas.getContext("2d", { alpha: true });
  const titleCanvas = document.createElement("canvas");
  const titleContext = titleCanvas.getContext("2d", { alpha: true });
  const coreLogo = new Image();
  coreLogo.decoding = "async";
  coreLogo.src = "/assets/hoyee-logo-transparent.png";
  let width = 0;
  let height = 0;
  let pixelRatio = 1;
  let frame = 0;
  let pointerStageX = -1000;
  let pointerStageY = -1000;
  let pointerActive = false;
  let lastTimestamp = 0;
  let titleMetrics = null;
  let lastPointerInputX = null;
  let lastPointerInputY = null;
  let pointerImpulse = 0;
  const coreMotion = {
    x: 0,
    y: 0,
    velocityX: 0,
    velocityY: 0,
    scale: 0,
    scaleVelocity: 0,
    rotation: 0
  };

  function disabled() {
    return reducedMotion.matches || coarsePointer.matches || loginView.hidden;
  }

  function resize() {
    width = window.innerWidth;
    height = window.innerHeight;
    pixelRatio = Math.min(window.devicePixelRatio || 1, 1.5);
    canvas.width = Math.round(width * pixelRatio);
    canvas.height = Math.round(height * pixelRatio);
    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    titleMetrics = null;
  }

  function stageGeometry() {
    const stage = loginView.querySelector(".brand-stage");
    const bounds = stage?.getBoundingClientRect();
    const stageWidth = Math.max(1, bounds?.width || width * 0.68);
    const stageHeight = Math.max(1, bounds?.height || height);
    const stageLeft = bounds?.left || 0;
    const stageTop = bounds?.top || 0;
    return {
      left: stageLeft,
      width: stageWidth,
      coreX: stageLeft + stageWidth * 0.5,
      coreY: stageTop + stageHeight * 0.57,
      radius: Math.max(68, Math.min(108, stageWidth * 0.086))
    };
  }

  function animateCore(baseGeometry) {
    const pointerDistanceX = pointerActive ? pointerStageX - baseGeometry.coreX : 0;
    const pointerDistanceY = pointerActive ? pointerStageY - baseGeometry.coreY : 0;
    const targetX = pointerActive ? Math.max(-18, Math.min(18, pointerDistanceX * .026)) : 0;
    const targetY = pointerActive ? Math.max(-12, Math.min(12, pointerDistanceY * .022)) : 0;
    const pointerDistance = Math.hypot(pointerDistanceX, pointerDistanceY);
    const proximity = pointerActive
      ? Math.exp(-pointerDistance / Math.max(1, baseGeometry.radius * 3.4))
      : 0;
    const targetScale = proximity * .024 + pointerImpulse * .052;

    coreMotion.velocityX = (coreMotion.velocityX + (targetX - coreMotion.x) * .065) * .8;
    coreMotion.velocityY = (coreMotion.velocityY + (targetY - coreMotion.y) * .065) * .8;
    coreMotion.x += coreMotion.velocityX;
    coreMotion.y += coreMotion.velocityY;
    coreMotion.scaleVelocity = (coreMotion.scaleVelocity + (targetScale - coreMotion.scale) * .12) * .72;
    coreMotion.scale += coreMotion.scaleVelocity;
    coreMotion.rotation += .00045 + pointerImpulse * .005;
    pointerImpulse *= .88;

    return {
      ...baseGeometry,
      coreX: baseGeometry.coreX + coreMotion.x,
      coreY: baseGeometry.coreY + coreMotion.y,
      radius: baseGeometry.radius * (1 + coreMotion.scale)
    };
  }

  function prepareTitleSurface() {
    if (titleMetrics) return titleMetrics;
    const title = loginView.querySelector("#brand-title");
    if (!title || !titleContext) return null;
    const style = getComputedStyle(title);
    const fontSize = Number.parseFloat(style.fontSize) || 64;
    const fontFamily = style.fontFamily || '"HarmonyOS Sans SC", "Microsoft YaHei UI", sans-serif';
    const fontWeight = style.fontWeight || "750";
    const scale = Math.min(1.6, pixelRatio || 1);
    const font = `${fontWeight} ${fontSize}px ${fontFamily}`;
    const parts = ["HOYEE AI", "", ""];
    const signature = `${font}|${scale}|${parts.join("")}`;
    titleContext.setTransform(1, 0, 0, 1, 0, 0);
    titleContext.font = font;
    const widths = parts.map(part => titleContext.measureText(part).width);
    const surfaceWidth = Math.ceil(widths.reduce((total, partWidth) => total + partWidth, 0) + 44);
    const surfaceHeight = Math.ceil(fontSize * 1.65);
    titleCanvas.width = Math.ceil(surfaceWidth * scale);
    titleCanvas.height = Math.ceil(surfaceHeight * scale);
    titleContext.setTransform(scale, 0, 0, scale, 0, 0);
    titleContext.clearRect(0, 0, surfaceWidth, surfaceHeight);
    titleContext.font = font;
    titleContext.textBaseline = "middle";
    titleContext.textAlign = "left";
    titleContext.lineJoin = "round";
    const baseline = surfaceHeight * .52;
    let x = 22;

    titleContext.save();
    titleContext.globalAlpha = .2;
    titleContext.fillStyle = "#d1aeff";
    titleContext.fillText(parts.join(""), x - 2.2, baseline);
    titleContext.fillStyle = "#63d6c5";
    titleContext.fillText(parts.join(""), x + 2.2, baseline);
    titleContext.restore();

    titleContext.fillStyle = "rgba(249,253,255,.96)";
    titleContext.shadowColor = "rgba(211,236,255,.18)";
    titleContext.shadowBlur = 8;
    titleContext.fillText(parts[0], x, baseline);
    x += widths[0];
    titleContext.shadowBlur = 0;
    titleContext.strokeStyle = "rgba(99,214,197,.98)";
    titleContext.lineWidth = 1.5;
    titleContext.strokeText(parts[1], x, baseline);
    x += widths[1];
    titleContext.fillStyle = "rgba(249,253,255,.96)";
    titleContext.shadowColor = "rgba(211,236,255,.18)";
    titleContext.shadowBlur = 8;
    titleContext.fillText(parts[2], x, baseline);
    titleContext.shadowBlur = 0;

    titleContext.save();
    titleContext.globalCompositeOperation = "destination-out";
    titleContext.fillStyle = "rgba(0,0,0,.18)";
    for (let scanY = 2; scanY < surfaceHeight; scanY += 4) {
      titleContext.fillRect(0, scanY, surfaceWidth, .72);
    }
    titleContext.restore();

    titleMetrics = { signature, width: surfaceWidth, height: surfaceHeight, scale };
    return titleMetrics;
  }

  function drawWarpedTitle(time, geometry) {
    const metrics = prepareTitleSurface();
    if (!metrics) return;
    const baseY = geometry.coreY - metrics.height * .5;
    const sliceWidth = 4;
    const mouseRadius = 118;
    const copyGap = 128;
    const cycleWidth = metrics.width + copyGap;
    const scrollOffset = (time * .052) % cycleWidth;
    const firstCopyX = geometry.left + scrollOffset - cycleWidth;
    const stageRight = geometry.left + geometry.width;

    context.save();
    context.globalCompositeOperation = "screen";
    context.imageSmoothingEnabled = true;
    for (let copyX = firstCopyX; copyX < stageRight + cycleWidth; copyX += cycleWidth) {
      for (let sourceX = 0; sourceX < metrics.width; sourceX += sliceWidth) {
        const sourceWidth = Math.min(sliceWidth, metrics.width - sourceX);
        const restingX = copyX + sourceX;
        if (restingX < geometry.left - 150 || restingX > stageRight + 150) continue;
        const coreDistance = (restingX - geometry.coreX) / geometry.radius;
        const coreLens = Math.exp(-coreDistance * coreDistance * .82);
        let drawX = restingX - Math.sign(coreDistance || 1) * coreLens * 25;
        let drawHeight = metrics.height * (1 - coreLens * .38);
        let drawY = baseY + (metrics.height - drawHeight) * .5
          + Math.sin(coreDistance * 3.1 + time * .0022) * coreLens * 7;
        let drawWidth = sourceWidth * (1.12 + coreLens * 1.55);
        let pressure = 0;

        if (pointerActive) {
          const sampleY = drawY + drawHeight * .5;
          const pointerDx = drawX - pointerStageX;
          const pointerDy = sampleY - pointerStageY;
          const pointerDistance = Math.hypot(pointerDx, pointerDy);
          if (pointerDistance < mouseRadius) {
            pressure = Math.pow(1 - pointerDistance / mouseRadius, 1.7);
            const safeDistance = Math.max(1, pointerDistance);
            const directionX = pointerDistance < 1 ? (sourceX % 2 ? 1 : -1) : pointerDx / safeDistance;
            const directionY = pointerDistance < 1 ? -1 : pointerDy / safeDistance;
            drawX += directionX * pressure * 86;
            drawY += directionY * pressure * 58;
            drawWidth *= 1 + pressure * 1.7;
            drawHeight *= 1 - pressure * .16;
          }
        }

        context.globalAlpha = .88 - coreLens * .18 + pressure * .08;
        context.drawImage(
          titleCanvas,
          sourceX * metrics.scale,
          0,
          sourceWidth * metrics.scale,
          metrics.height * metrics.scale,
          drawX,
          drawY,
          drawWidth,
          drawHeight
        );

        if (pressure > .08) {
          context.globalAlpha = pressure * .22;
          context.drawImage(
            titleCanvas,
            sourceX * metrics.scale,
            0,
            sourceWidth * metrics.scale,
            metrics.height * metrics.scale,
            drawX + (sourceX % 2 ? 3.5 : -3.5),
            drawY,
            drawWidth,
            drawHeight
          );
        }
      }
    }
    context.restore();
  }

  function drawGlow(coreX, coreY, radius, time) {
    const breathingRadius = radius * (1 + Math.sin(time * .00082) * .006);

    context.save();
    context.translate(coreX, coreY);
    context.scale(1.16, .9);
    const halo = context.createRadialGradient(0, 0, breathingRadius * .46, 0, 0, breathingRadius * 2.5);
    halo.addColorStop(0, "rgba(0,2,5,.22)");
    halo.addColorStop(.34, "rgba(4,9,14,.18)");
    halo.addColorStop(.58, "rgba(142,169,181,.05)");
    halo.addColorStop(.76, "rgba(91,115,128,.018)");
    halo.addColorStop(1, "rgba(60,83,96,0)");
    context.fillStyle = halo;
    context.beginPath();
    context.arc(0, 0, breathingRadius * 2.5, 0, Math.PI * 2);
    context.fill();
    context.restore();

    const core = context.createRadialGradient(
      coreX - breathingRadius * .24,
      coreY - breathingRadius * .28,
      breathingRadius * .04,
      coreX,
      coreY,
      breathingRadius
    );
    core.addColorStop(0, "rgba(26,35,43,.24)");
    core.addColorStop(.46, "rgba(6,10,15,.54)");
    core.addColorStop(1, "rgba(0,1,3,.82)");
    context.fillStyle = core;
    context.beginPath();
    context.arc(coreX, coreY, breathingRadius * .965, 0, Math.PI * 2);
    context.fill();

    if (coreLogo.complete && coreLogo.naturalWidth) {
      const logoWidth = breathingRadius * 1.3;
      const logoHeight = breathingRadius * 1.16;
      context.save();
      context.globalAlpha = .62 + Math.sin(time * .0009) * .018;
      context.shadowColor = "rgba(190,214,222,.1)";
      context.shadowBlur = 8;
      context.drawImage(
        coreLogo,
        165, 115, 480, 420,
        coreX - logoWidth * .5,
        coreY - logoHeight * .5,
        logoWidth,
        logoHeight
      );
      context.restore();
    }

    context.save();
    context.globalCompositeOperation = "lighter";
    context.lineCap = "round";
    const orbitDrift = time * .00019 + coreMotion.rotation * .18;
    const crescentStart = -Math.PI * .78 + orbitDrift;
    const crescentEnd = Math.PI * .18 + orbitDrift;

    context.beginPath();
    context.arc(coreX, coreY, breathingRadius + .6, 0, Math.PI * 2);
    context.strokeStyle = "rgba(218,231,236,.028)";
    context.lineWidth = .8;
    context.shadowColor = "rgba(197,218,226,.08)";
    context.shadowBlur = 7;
    context.stroke();

    context.beginPath();
    context.arc(coreX, coreY, breathingRadius + .8, crescentStart, crescentEnd);
    context.strokeStyle = "rgba(231,240,243,.03)";
    context.lineWidth = 9;
    context.shadowColor = "rgba(226,239,243,.22)";
    context.shadowBlur = 34;
    context.stroke();

    const rimLight = context.createLinearGradient(
      coreX + Math.cos(crescentStart) * breathingRadius,
      coreY + Math.sin(crescentStart) * breathingRadius,
      coreX + Math.cos(crescentEnd) * breathingRadius,
      coreY + Math.sin(crescentEnd) * breathingRadius
    );
    rimLight.addColorStop(0, "rgba(210,225,231,.025)");
    rimLight.addColorStop(.42, "rgba(225,238,242,.12)");
    rimLight.addColorStop(.78, "rgba(242,248,249,.66)");
    rimLight.addColorStop(1, "rgba(255,255,255,.94)");
    context.beginPath();
    context.arc(coreX, coreY, breathingRadius, crescentStart + .05, crescentEnd - .04);
    context.strokeStyle = rimLight;
    context.lineWidth = 1.45;
    context.shadowColor = "rgba(242,249,250,.48)";
    context.shadowBlur = 14;
    context.stroke();

    context.beginPath();
    context.arc(coreX, coreY, breathingRadius - 1.5, crescentStart + .34, crescentEnd - .28);
    context.strokeStyle = "rgba(232,242,245,.085)";
    context.lineWidth = .65;
    context.shadowBlur = 5;
    context.stroke();

    context.beginPath();
    context.arc(coreX, coreY, breathingRadius + 1.5, Math.PI * .62 + orbitDrift, Math.PI * 1.06 + orbitDrift);
    context.strokeStyle = "rgba(160,187,198,.03)";
    context.lineWidth = .8;
    context.shadowColor = "rgba(143,174,186,.08)";
    context.shadowBlur = 8;
    context.stroke();

    const flareAngle = crescentEnd - .02;
    const flareX = coreX + Math.cos(flareAngle) * breathingRadius;
    const flareY = coreY + Math.sin(flareAngle) * breathingRadius;
    const flare = context.createRadialGradient(flareX, flareY, 0, flareX, flareY, breathingRadius * .16);
    flare.addColorStop(0, "rgba(255,255,255,.54)");
    flare.addColorStop(.18, "rgba(244,250,251,.18)");
    flare.addColorStop(1, "rgba(214,232,238,0)");
    context.fillStyle = flare;
    context.beginPath();
    context.arc(flareX, flareY, breathingRadius * .16, 0, Math.PI * 2);
    context.fill();
    context.restore();
  }

  function draw(timestamp = 0) {
    if (disabled()) {
      frame = 0;
      context.clearRect(0, 0, width, height);
      return;
    }
    lastTimestamp = timestamp || lastTimestamp;
    context.clearRect(0, 0, width, height);
    const geometry = animateCore(stageGeometry());
    context.save();
    context.beginPath();
    context.rect(geometry.left, 0, geometry.width, height);
    context.clip();
    drawWarpedTitle(lastTimestamp, geometry);
    drawGlow(geometry.coreX, geometry.coreY, geometry.radius, lastTimestamp);
    context.restore();
    context.shadowBlur = 0;
    frame = requestAnimationFrame(draw);
  }

  function trackPointer(event) {
    const stageBounds = loginView.querySelector(".brand-stage")?.getBoundingClientRect();
    pointerActive = Boolean(stageBounds
      && event.clientX >= stageBounds.left
      && event.clientX <= stageBounds.right
      && event.clientY >= stageBounds.top
      && event.clientY <= stageBounds.bottom);
    pointerStageX = event.clientX;
    pointerStageY = event.clientY;
    if (lastPointerInputX !== null && lastPointerInputY !== null) {
      const inputSpeed = Math.hypot(event.clientX - lastPointerInputX, event.clientY - lastPointerInputY);
      pointerImpulse = Math.max(pointerImpulse, Math.min(1, inputSpeed / 46));
    }
    lastPointerInputX = event.clientX;
    lastPointerInputY = event.clientY;
    if (cursorLens) {
      cursorLens.style.transform = `translate3d(${event.clientX}px, ${event.clientY}px, 0) translate(-50%, -50%)`;
      cursorLens.classList.toggle("is-active", pointerActive);
    }
  }

  function resetPointer() {
    pointerActive = false;
    lastPointerInputX = null;
    lastPointerInputY = null;
    cursorLens?.classList.remove("is-active");
  }

  function start() {
    if (!frame && !disabled()) frame = requestAnimationFrame(draw);
  }

  resize();
  loginView.addEventListener("pointermove", trackPointer, { passive: true });
  loginView.addEventListener("pointerrawupdate", trackPointer, { passive: true });
  loginView.addEventListener("pointerleave", resetPointer, { passive: true });
  window.addEventListener("resize", () => {
    resize();
    start();
  }, { passive: true });
  reducedMotion.addEventListener?.("change", start);
  coarsePointer.addEventListener?.("change", start);
  coreLogo.addEventListener("load", start, { once: true });
  new MutationObserver(start).observe(loginView, { attributes: true, attributeFilter: ["hidden"] });
  start();
}

createLoginParticleField();

const slidingSwitchSelector = ".sliding-switch";

function syncSlidingSwitch(control) {
  if (!control || control.offsetParent === null) return;
  const active = control.querySelector("button.active:not([hidden])");
  if (!active) return;
  control.style.setProperty("--switch-x", `${active.offsetLeft}px`);
  control.style.setProperty("--switch-y", `${active.offsetTop}px`);
  control.style.setProperty("--switch-width", `${active.offsetWidth}px`);
  control.style.setProperty("--switch-height", `${active.offsetHeight}px`);
  control.classList.add("slider-ready");
}

function scheduleSlidingSwitchSync() {
  if (slidingSwitchFrame) cancelAnimationFrame(slidingSwitchFrame);
  slidingSwitchFrame = requestAnimationFrame(() => {
    slidingSwitchFrame = 0;
    document.querySelectorAll(slidingSwitchSelector).forEach(syncSlidingSwitch);
  });
}

document.addEventListener("click", event => {
  if (event.target.closest(`${slidingSwitchSelector} > button`)) {
    requestAnimationFrame(scheduleSlidingSwitchSync);
  }
});

window.addEventListener("resize", scheduleSlidingSwitchSync, { passive: true });

if (typeof ResizeObserver === "function") {
  const slidingSwitchResizeObserver = new ResizeObserver(scheduleSlidingSwitchSync);
  document.querySelectorAll(slidingSwitchSelector).forEach(control =>
    slidingSwitchResizeObserver.observe(control)
  );
}

document.querySelectorAll("dialog:has(.sliding-switch)").forEach(dialog => {
  new MutationObserver(scheduleSlidingSwitchSync).observe(dialog, {
    attributes: true,
    attributeFilter: ["open"]
  });
});

scheduleSlidingSwitchSync();

const statusNames = {
  DRAFT: "草稿",
  QUEUED: "待执行",
  RUNNING: "运行中",
  WAITING_EXECUTOR: "等待执行器",
  REVIEW_REQUIRED: "待审核",
  APPROVED: "已审核",
  COMPLETED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消"
};

const typeNames = {
  IMAGE_TO_VIDEO: "图生视频",
  IMAGE_COLLECTION: "素材采集",
  IMAGE_VARIATION: "款式裂变",
  ECOMMERCE_IMAGE: "电商图生成",
  PRODUCT_PUBLISH: "商品发布"
};

class ApiRequestError extends Error {
  constructor(message, status = 0, payload = null) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
    this.payload = payload;
  }
}

const PRODUCT_CAPTURE_RETURN_KEY = "heyi:return-to-product-capture";

function rememberProductCaptureRoute(enabled) {
  try {
    if (enabled) sessionStorage.setItem(PRODUCT_CAPTURE_RETURN_KEY, "1");
    else sessionStorage.removeItem(PRODUCT_CAPTURE_RETURN_KEY);
  } catch (_error) {
    // The URL hash still preserves the route when storage is unavailable.
  }
}

function hasRememberedProductCaptureRoute() {
  try {
    return sessionStorage.getItem(PRODUCT_CAPTURE_RETURN_KEY) === "1";
  } catch (_error) {
    return false;
  }
}

function rememberWorkspaceForLogin() {
  if (!currentUser) return;
  pendingWorkspaceAfterLogin = activeWorkspace;
  if (activeWorkspace === "productPublish") {
    pendingProductCaptureAfterLogin = selectedProductCaptureId || "";
  }
}

function handleSessionExpired() {
  if (sessionExpiryActive && !currentUser) return;
  const hadSession = Boolean(currentUser);
  rememberWorkspaceForLogin();
  sessionExpiryActive = hadSession;
  showLogin({ message: hadSession ? "登录已过期，请重新登录。登录后将返回刚才的商品页面。" : "" });
}

async function api(path, options = {}) {
  let response;
  try {
    response = await fetch(path, {
      ...options,
      cache: options.cache || "no-store",
      credentials: "same-origin",
      headers: { "X-Commerce-Module": typeof commerceDataModule === "function" ? commerceDataModule() : "CAPTURE", ...(options.headers || {}) }
    });
  } catch (_error) {
    throw new ApiRequestError("无法连接平台服务，请检查网络后重试", 0);
  }
  let bodyReadFailed = false;
  const result = await response.json().catch(() => { bodyReadFailed = true; return null; });
  if (options.signal?.aborted) throw options.signal.reason || new ApiRequestError('请求已取消', 0);
  if (response.status === 401 && path !== "/api/v1/auth/login") {
    handleSessionExpired();
    throw new ApiRequestError(result?.message || "登录已失效，请重新登录", 401, result);
  }
  if (!response.ok) {
    const fallback = response.status === 403
      ? "当前账号已登录，但没有执行此操作的权限"
      : "服务暂时不可用，请稍后重试";
    throw new ApiRequestError(result?.message || fallback, response.status, result);
  }
  // A successful HTTP header does not guarantee that the JSON body arrived.
  // Only reads are classified for automatic recovery; mutation semantics stay unchanged.
  if (bodyReadFailed && (options.method || 'GET').toUpperCase() === 'GET') {
    throw new ApiRequestError('响应数据未完整接收，请稍后重试', 0);
  }
  return result;
}

const GENERAL_VIDEO_API_ROOT = "/api/v1/general-video-tasks";

function generalVideoApi(path = "", options = {}) {
  return api(`${GENERAL_VIDEO_API_ROOT}${path}`, options);
}

function generalVideoAssetUrl(taskId, asset, preview = false) {
  if (!asset) return "";
  const responseUrl = preview ? asset.previewUrl || asset.thumbnailUrl : asset.url || asset.downloadUrl;
  if (responseUrl) {
    // Server-provided local URLs must also bypass cached OSS redirects.
    // Preserve external signed URLs exactly, including their signatures.
    try {
      const url = new URL(responseUrl, window.location.href);
      if (url.origin === window.location.origin
          && /^\/api\/v1\/general-video-tasks\/[^/]+\/assets\/[^/]+$/.test(url.pathname)) {
        url.searchParams.set("cache", "20261008-local");
        return url.pathname + url.search + url.hash;
      }
    } catch (_error) {}
    return responseUrl;
  }
  const suffix = preview ? "?preview=true&cache=20261008-local" : "?cache=20261008-local";
  return `${GENERAL_VIDEO_API_ROOT}/${encodeURIComponent(taskId)}/assets/${encodeURIComponent(asset.id)}${suffix}`;
}

function uploadFormData(path, formData, onProgress) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open("POST", path, true);
    request.responseType = "json";
    request.withCredentials = true;
    request.upload.addEventListener("progress", event => {
      if (!event.lengthComputable || typeof onProgress !== "function") return;
      onProgress(Math.min(99, Math.max(1, Math.round(event.loaded * 100 / event.total))));
    });
    request.addEventListener("load", () => {
      const result = request.response || (() => {
        try { return JSON.parse(request.responseText || "null"); } catch (_) { return null; }
      })();
      if (request.status === 401) {
        handleSessionExpired();
        reject(new ApiRequestError(result?.message || "登录已失效，请重新登录", 401, result));
        return;
      }
      if (request.status < 200 || request.status >= 300) {
        const fallback = request.status === 403
          ? "当前账号已登录，但没有上传或处理图片的权限"
          : "图片上传失败，请稍后重试";
        reject(new ApiRequestError(result?.message || fallback, request.status, result));
        return;
      }
      resolve(result);
    });
    request.addEventListener("error", () => reject(new Error("图片上传连接中断，请检查网络后重试")));
    request.addEventListener("abort", () => reject(new Error("图片上传已中断，请重新选择图片")));
    request.send(formData);
  });
}

function showLogin({ message = "" } = {}) {
  stopWorkflowEvents();
  document.querySelectorAll("dialog[open]").forEach(dialog => dialog.close());
  passwordInput.value = "";
  resetCompanyScopedWorkspaceState();
  currentUser = null;
  const portalRoute = !window.location.hash || ["#home", "#platform-capabilities", "#platform-preview"].includes(window.location.hash);
  const showPortal = portalRoute && !message;
  document.querySelector("#portal-view").hidden = !showPortal;
  loginView.hidden = showPortal;
  dashboardView.hidden = true;
  loginError.textContent = message;
  loginError.hidden = !message;
  selectLoginMethod(socialProviderStatuses.get("wechat")?.enabled ? "wechat" : "password", { focus: false });
}

async function showDashboard(user) {
  if (currentUser?.organizationId && (currentUser.organizationId !== user?.organizationId
      || currentUser.userId !== user?.userId)) {
    resetCompanyScopedWorkspaceState();
  }
  currentUser = user;
  sessionExpiryActive = false;
  document.querySelector("#portal-view").hidden = true;
  loginView.hidden = true;
  dashboardView.hidden = false;
  loginError.hidden = true;
  loginError.textContent = "";
  document.querySelector("#current-user").setAttribute("translate", (user.displayName) ? "no" : "yes");
  document.querySelector("#current-user").textContent = user.displayName || user.username;
  document.querySelector("#current-organization").setAttribute("translate", (user.organizationName) ? "no" : "yes");
  document.querySelector("#current-organization").textContent = `${user.organizationName || "企业工作空间"}${user.organizationCode ? ` · ${user.organizationCode}` : ""}`;
  document.querySelector("#current-permission-state").textContent = productPermissionLabel();
  applyFeatureAccess();
  if (window.location.hash.startsWith('#finance-') && globalThis.FinanceWorkspace?.allowed()) {
    switchWorkspace('finance'); globalThis.FinanceWorkspace.enter(window.location.hash.slice(9)); return;
  }
  if (window.location.hash.startsWith('#knowledge-') && canAccessKnowledge()) {
    switchWorkspace('knowledge'); globalThis.KnowledgeWorkspace?.enter(window.location.hash.slice(1)); return;
  }
  if (window.location.hash.startsWith('#hr-') && globalThis.HrWorkspace?.allowed()) {
    switchWorkspace('hr'); globalThis.HrWorkspace.enter(window.location.hash.slice(4)); return;
  }
  if ((window.location.hash === "#shops" || new URLSearchParams(window.location.search).get("view") === "shops")
      && hasAnyPermission("product:view", "product:publish")) {
    switchWorkspace("shops");
    await loadMarketplaceShops({ silent: true });
    consumeMarketplaceAuthorizationReturn();
    return;
  }
  const returnToProducts = Boolean(commerceRouteFromHash())
    || pendingWorkspaceAfterLogin === "productPublish"
    || hasRememberedProductCaptureRoute();
  const returnCaptureId = pendingProductCaptureAfterLogin;
  pendingWorkspaceAfterLogin = "";
  pendingProductCaptureAfterLogin = "";
  if (returnToProducts && canAccessProductCenter()) {
    switchWorkspace("productPublish");
    await Promise.all([
      loadProductCaptures(returnCaptureId || null),
      loadMarketplaceShops({ silent: true })
    ]);
    consumeMarketplaceAuthorizationReturn();
    return;
  }
  if (returnToProducts) rememberProductCaptureRoute(false);
  if (user.permissions?.includes('hr:access') && !user.platformAdmin && globalThis.HrWorkspace?.allowed()) {
    history.replaceState(null, "", "#hr-overview");
    switchWorkspace('hr'); globalThis.HrWorkspace.enter('overview'); return;
  }
  if (hasAnyPermission("video:monitor:view")) {
    switchWorkspace("aiVideoMonitor");
    await loadAiVideoMonitor();
  } else if (hasAnyPermission("video:script:view", "video:script:manage")) {
    switchWorkspace("videoScripts");
    await loadVideoScripts();
  } else if (hasAnyPermission("video:task:view", "video:task:manage")) {
    switchWorkspace("aiVideoTasks");
    await loadAiVideoTasks();
  } else if (canAccessProductCenter()) {
    switchWorkspace("productPublish");
    await Promise.all([loadProductCaptures(), loadMarketplaceShops({ silent: true })]);
    consumeMarketplaceAuthorizationReturn();
  } else if (hasAnyPermission("automation:view", "automation:execute")) {
    switchWorkspace("automations");
    await loadAutomationCenter();
  } else if (hasAnyPermission("system:model:view", "system:model:manage")) {
    switchWorkspace("models");
    await loadSystemData();
  } else if (hasAnyPermission("system:organization:view", "system:organization:manage", "system:user:view", "system:user:manage", "system:permission:view", "system:role:manage")) {
    switchWorkspace("system");
    await loadSystemData();
  } else {
    // Every active company member can use the shared knowledge library.
    history.replaceState(null, "", "#knowledge-shop");
    switchWorkspace("knowledge");
    globalThis.KnowledgeWorkspace?.enter("knowledge-shop");
  }
}

function hasAnyPermission(...codes) {
  const granted = new Set(currentUser?.permissions || []);
  const isAdmin = (currentUser?.roles || []).includes("ADMIN");
  return isAdmin || codes.some(code => granted.has(code));
}

function canAccessKnowledge() {
  return !!currentUser;
}

function productAccessCapabilities() {
  return {
    collect: hasAnyPermission("product:collect"),
    view: hasAnyPermission("product:view"),
    edit: hasAnyPermission("product:edit"),
    publish: hasAnyPermission("product:publish"),
    watermark: hasAnyPermission("asset:watermark")
  };
}

function canAccessProductCenter() {
  return productAccessCapabilities().view;
}

function productPermissionLabel() {
  const access = productAccessCapabilities();
  const abilities = [];
  if (access.collect) abilities.push("采集");
  if (access.edit) abilities.push("编辑/审核");
  if (access.watermark) abilities.push("图片处理");
  if (access.publish) abilities.push("发布");
  if (abilities.length) return `商品权限 · 可${abilities.join("、")}`;
  if (access.view) return "商品权限 · 仅查看";
  if (currentUser?.permissions?.includes("hr:access")) return "人力资源 · 全部功能";
  return "未授予商品权限";
}

function applyFeatureAccess() {
  const canManageWorkflow = hasAnyPermission("workflow:manage");
  const canViewScripts = hasAnyPermission("video:script:view", "video:script:manage");
  const canManageScripts = hasAnyPermission("video:script:manage");
  const canViewVideoTasks = hasAnyPermission("video:task:view", "video:task:manage");
  const canManageVideoTasks = hasAnyPermission("video:task:manage");
  const canViewVideoMonitor = hasAnyPermission("video:monitor:view");
  const canUseAutomation = hasAnyPermission("automation:view", "automation:execute");
  const canViewAutomationAdmin = hasAnyPermission("system:automation:view", "system:automation:manage");
  const canManageAutomation = hasAnyPermission("system:automation:manage");
  const canViewAssistant = hasAnyPermission("assistant:view", "assistant:use");
  const productAccess = productAccessCapabilities();
  const canOpenSystem = hasAnyPermission(
    "system:organization:view", "system:organization:manage",
    "system:user:view", "system:user:manage",
    "system:permission:view", "system:role:manage"
  );

  document.querySelector("#nav-video-scripts").hidden = !canViewScripts;
  document.querySelector("#nav-ai-video-tasks").hidden = !canViewVideoTasks;
  document.querySelector("#nav-general-video").hidden = !canViewVideoTasks;
  document.querySelector("#nav-ai-video-monitor").hidden = !canViewVideoMonitor;
  document.querySelector("#nav-publish").hidden = !canAccessProductCenter();
  document.querySelectorAll('[data-finance-route]').forEach(link => { link.hidden = !window.FinanceWorkspace?.allowed(); });
  document.querySelector("#nav-shops").hidden = !hasAnyPermission("product:view", "product:publish");
  document.querySelector("#nav-automations").hidden = !canUseAutomation;
  document.querySelector("#nav-automation-admin").hidden = !canViewAutomationAdmin;
  document.querySelector("#nav-models").hidden = !hasAnyPermission("system:model:view", "system:model:manage");
  document.querySelector("#nav-system").hidden = !canOpenSystem;
  document.querySelector("#nav-assistant-test").hidden = !canViewAssistant;
  document.querySelectorAll("[data-commerce-route]").forEach(link => { link.hidden = !canAccessProductCenter(); });
  document.querySelectorAll("[data-knowledge-route]").forEach(link => { link.hidden = !canAccessKnowledge(); });
  document.querySelectorAll("[data-hr-route]").forEach(link => {
    link.hidden = !((currentUser?.platformAdmin === true && currentUser?.username === "13800000000")
      || currentUser?.permissions?.includes('hr:access') === true);
  });
  document.querySelectorAll(".nav-group").forEach(group => {
    group.hidden = ![...group.querySelectorAll(".nav-item")].some(link => !link.hidden);
  });

  document.querySelector("#new-job-button").hidden = !canManageWorkflow;
  document.querySelector("#cancel-current-task").hidden = !hasAnyPermission("workflow:manage", "workflow:review");
  document.querySelector("#import-video-script").hidden = !canManageScripts;
  document.querySelector("#delete-video-script").hidden = !canManageScripts;
  document.querySelector("#copy-video-script").hidden = !canManageScripts;
  document.querySelector("#edit-video-script-tone").hidden = !canManageScripts;
  document.querySelector("#edit-video-shot").hidden = !canManageScripts;
  document.querySelector("#create-video-task").hidden = !canManageVideoTasks;
  document.querySelector("#batch-garment-upload-label").hidden = !canManageVideoTasks;
  document.querySelector("#generate-shot-images").hidden = !canManageVideoTasks;
  document.querySelector("#general-video-submit").hidden = !canManageVideoTasks;
  document.querySelector("#general-reference-drop").classList.toggle("is-disabled", !canManageVideoTasks);
  document.querySelector("#general-reference-input").disabled = !canManageVideoTasks;
  document.querySelector("#create-extension-pairing-code").hidden = !productAccess.collect;
  document.querySelector("#save-product-capture").hidden = !productAccess.edit;
  document.querySelector("#process-product-images").hidden = !productAccess.watermark;
  document.querySelector("#approve-product-capture").hidden = !productAccess.edit;
  document.querySelector("#design-current-product").hidden = !productAccess.edit;
  document.querySelector("#skill-converter-card").hidden = !canManageAutomation;
  document.querySelector("#automation-access-card").hidden = !canManageAutomation;
}

async function restoreSession() {
  try {
    const result = await api("/api/v1/auth/me");
    await showDashboard(result.data);
  } catch (error) {
    showLogin({ message: error?.status === 401 ? "" : error?.message || "平台连接失败，请稍后重试" });
  }
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const organizationCode = document.querySelector("#organization-code").value.trim().toLowerCase();
  const username = document.querySelector("#username").value.trim();
  const password = passwordInput.value;
  if (!organizationCode || !username || !password) {
    loginError.textContent = "请输入公司代码、手机号和密码";
    loginError.hidden = false;
    return;
  }

  loginError.hidden = true;
  loginButton.disabled = true;
  loginButton.querySelector("span").textContent = "正在验证";
  try {
    const result = await api("/api/v1/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        organizationCode,
        phone: username,
        password
      })
    });
    localStorage.setItem("heyi:last-organization-code", organizationCode);
    await showDashboard(result.data);
  } catch (error) {
    loginError.textContent = error.message;
    loginError.hidden = false;
  } finally {
    loginButton.disabled = false;
    loginButton.querySelector("span").textContent = "登录";
  }
});

document.querySelector("#toggle-password").addEventListener("click", (event) => {
  const visible = passwordInput.type === "text";
  passwordInput.type = visible ? "password" : "text";
  event.currentTarget.textContent = visible ? "显示" : "隐藏";
  event.currentTarget.setAttribute("aria-label", visible ? "显示密码" : "隐藏密码");
});

document.querySelector("#logout-button").addEventListener("click", async event => {
  const button=event.currentTarget;
  if(button.disabled)return;
  button.disabled=true;button.textContent="正在退出…";
  let message="";
  // Explicit logout only: session expiry/navigation must not erase BOSS data.
  if(globalThis.HrWorkspace?.allowed()&&globalThis.HeyiHrTransport){
    globalThis.HrWorkspace.beginLogout?.();
    try{
      await globalThis.HeyiHrTransport.disconnect({ensureConnection:true});
    }catch(_error){
      message="已退出平台，但未能确认助手中的 BOSS 登录信息已清除。请更新并打开助手，右键悬浮球选择“退出 BOSS 登录”。";
    }
  }
  try {
    await api("/api/v1/auth/logout", { method: "POST" });
  } catch (_error) {
    message=message
      ? "平台退出请求未确认，助手登录信息也未确认清除。请检查网络后重试。"
      : "未能确认平台退出，请检查网络后重试。";
  } finally {
    sessionExpiryActive = false;
    pendingWorkspaceAfterLogin = "";
    pendingProductCaptureAfterLogin = "";
    loginForm.reset();
    showLogin({message});
    button.disabled=false;button.textContent="退出";
  }
});

document.querySelector("#refresh-button").addEventListener("click", loadJobs);
document.querySelector("#new-job-button").addEventListener("click", () => {
  document.querySelector("#workflow-error").hidden = true;
  document.querySelector("#workflow-dialog").showModal();
});

document.querySelector("#workflow-form").addEventListener("submit", async event => {
  event.preventDefault();
  const submit = document.querySelector("#workflow-submit");
  const errorLabel = document.querySelector("#workflow-error");
  submit.disabled = true;
  errorLabel.hidden = true;
  let createdJob = null;
  try {
    const budgetText = document.querySelector("#workflow-budget").value.trim();
    const created = await api("/api/v1/workflow-jobs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        type: document.querySelector("#workflow-type").value,
        title: document.querySelector("#workflow-title").value.trim(),
        inputPayload: {
          description: document.querySelector("#workflow-description").value.trim()
        },
        budgetLimit: budgetText ? Number(budgetText) : null
      })
    });
    createdJob = created.data;
    await api(`/api/v1/workflow-jobs/${createdJob.id}/status`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status: "QUEUED" })
    });
    event.currentTarget.reset();
    document.querySelector("#workflow-dialog").close();
    await Promise.all([loadJobs(), loadCurrentTask()]);
  } catch (error) {
    if (createdJob) {
      await api(`/api/v1/workflow-jobs/${createdJob.id}/status`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ status: "CANCELLED" })
      }).catch(() => {});
    }
    errorLabel.textContent = error.message;
    errorLabel.hidden = false;
  } finally {
    submit.disabled = false;
  }
});

document.querySelector("#cancel-current-task").addEventListener("click", async () => {
  if (!currentWorkflowTaskId) return;
  const button = document.querySelector("#cancel-current-task");
  button.disabled = true;
  try {
    await api(`/api/v1/workflow-jobs/${currentWorkflowTaskId}/status`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status: "CANCELLED" })
    });
    await Promise.all([loadJobs(), loadCurrentTask()]);
  } catch (error) {
    alert(error.message);
  } finally {
    button.disabled = false;
  }
});

document.querySelector("#nav-system").addEventListener("click", async (event) => {
  event.preventDefault();
  switchWorkspace("system");
  await loadSystemData();
  document.querySelector('[data-system-tab="people"]')?.click();
});

document.querySelector("#nav-models").addEventListener("click", async (event) => {
  event.preventDefault();
  switchWorkspace("models");
  await loadSystemData();
});

document.querySelector("#nav-automations").addEventListener("click", async event => {
  event.preventDefault();
  switchWorkspace("automations");
  await loadAutomationCenter();
});

document.querySelector("#nav-automation-admin").addEventListener("click", async event => {
  event.preventDefault();
  switchWorkspace("automationAdmin");
  await loadAutomationAdmin();
});

document.querySelector("#nav-video-scripts").addEventListener("click", async event => {
  event.preventDefault();
  switchWorkspace("videoScripts");
  await loadVideoScripts();
});

document.querySelector("#nav-ai-video-tasks").addEventListener("click", async event => {
  event.preventDefault();
  switchWorkspace("aiVideoTasks");
  await loadAiVideoTasks();
});

document.querySelector("#nav-publish").addEventListener("click", async event => {
  event.preventDefault();
  window.history.replaceState(null, document.title, `${window.location.pathname}${window.location.search}#product-capture`);
  resetProductCaptureDefaultFilter();
  switchWorkspace("productPublish");
  await Promise.all([
    loadProductCaptures(null, { page: 0, refreshDetail: false }),
    loadMarketplaceShops({ silent: true })
  ]);
});

document.querySelectorAll("[data-commerce-route]").forEach(link => {
  link.addEventListener("click", async event => {
    event.preventDefault();
    if (!currentUser || !canAccessProductCenter()) return;
    window.history.pushState(null, document.title, `${window.location.pathname}${window.location.search}#${link.dataset.commerceRoute}`);
    if (link.dataset.commerceRoute === "product-capture") resetProductCaptureDefaultFilter();
    switchWorkspace("productPublish");
    if (link.dataset.commerceRoute === "product-capture") {
      await loadProductCaptures(null, { page: 0, refreshDetail: false });
    }
  });
});

document.querySelector("#nav-general-video").addEventListener("click", async event => {
  event.preventDefault();
  switchWorkspace("generalVideo");
  await loadGeneralVideoTasks();
});

document.querySelector("#nav-ai-video-monitor").addEventListener("click", async event => {
  event.preventDefault();
  switchWorkspace("aiVideoMonitor");
  await loadAiVideoMonitor();
});

document.querySelector("#nav-assistant-test").addEventListener("click", async (event) => {
  event.preventDefault();
  switchWorkspace("assistant");
  await checkLocalAutomationAssistant();
  await loadAssistantStatus();
  startAssistantCommandPolling();
});

document.querySelector("#nav-shops").addEventListener("click", async event => {
  event.preventDefault();
  if (!hasAnyPermission("product:view", "product:publish")) return;
  switchWorkspace("shops");
  await loadMarketplaceShops();
});

function switchWorkspace(page) {
  activeWorkspace = page;
  const isSystem = page === "system";
  const isModels = page === "models";
  const isAssistant = page === "assistant";
  const isAutomations = page === "automations";
  const isAutomationAdmin = page === "automationAdmin";
  const isVideoScripts = page === "videoScripts";
  const isAiVideoTasks = page === "aiVideoTasks";
  const isGeneralVideo = page === "generalVideo";
  const isAiVideoMonitor = page === "aiVideoMonitor";
  const isProductPublish = page === "productPublish";
  const isShops = page === "shops";
  const isFinance = page === 'finance';
  document.querySelector('#finance-page').hidden = !isFinance;
  if (!isFinance) globalThis.FinanceWorkspace?.leave();
  document.querySelectorAll('[data-finance-route]').forEach(link => link.classList.toggle('active', isFinance && link.hash === window.location.hash));
  const isKnowledge = page === 'knowledge';
  document.querySelector('#knowledge-page').hidden = !isKnowledge;
  const isHr = page === 'hr';
  document.querySelector('#hr-page').hidden = !isHr;
  document.querySelectorAll('[data-hr-route]').forEach(a => a.classList.toggle('active', isHr && a.hash === window.location.hash));
  document.querySelectorAll('[data-knowledge-route]').forEach(link => { link.classList.toggle('active',isKnowledge && link.hash===window.location.hash);if(!isKnowledge)link.removeAttribute('aria-current'); });
  document.querySelector("#shop-management-page").hidden = !isShops;
  document.querySelector("#nav-shops").classList.toggle("active", isShops);
  document.querySelector("#dashboard-page").hidden = true;
  document.querySelector("#system-page").hidden = !isSystem;
  document.querySelector("#models-page").hidden = !isModels;
  document.querySelector("#assistant-test-page").hidden = !isAssistant;
  document.querySelector("#automations-page").hidden = !isAutomations;
  document.querySelector("#automation-admin-page").hidden = !isAutomationAdmin;
  document.querySelector("#video-scripts-page").hidden = !isVideoScripts;
  document.querySelector("#ai-video-tasks-page").hidden = !isAiVideoTasks;
  document.querySelector("#general-video-page").hidden = !isGeneralVideo;
  document.querySelector("#ai-video-monitor-page").hidden = !isAiVideoMonitor;
  document.querySelector("#product-publish-page").hidden = !isProductPublish;
  document.querySelector("#nav-system").classList.toggle("active", isSystem && !isModels);
  document.querySelector("#nav-models").classList.toggle("active", isModels);
  document.querySelector("#nav-assistant-test").classList.toggle("active", isAssistant);
  document.querySelector("#nav-automations").classList.toggle("active", isAutomations);
  document.querySelector("#nav-automation-admin").classList.toggle("active", isAutomationAdmin);
  document.querySelector("#nav-video-scripts").classList.toggle("active", isVideoScripts);
  document.querySelector("#nav-ai-video-tasks").classList.toggle("active", isAiVideoTasks);
  document.querySelector("#nav-general-video").classList.toggle("active", isGeneralVideo);
  document.querySelector("#nav-ai-video-monitor").classList.toggle("active", isAiVideoMonitor);
  document.querySelector("#nav-publish").classList.toggle("active", isProductPublish);
  document.querySelectorAll("[data-commerce-route]").forEach(link => { link.classList.remove("active"); link.removeAttribute("aria-current"); });
  document.querySelector(".topbar h2").textContent =
    isFinance ? "财务 BI" : isKnowledge ? "知识库" : isHr ? "人力资源" : isShops ? "店铺管理" :
    isVideoScripts ? "视频脚本管理" : isGeneralVideo ? "通用生成" : isAiVideoTasks ? "AI 视频任务" : isAiVideoMonitor ? "AI 任务监控" : isProductPublish ? "商品采集与发布" : isAutomationAdmin ? "自动化管理" : isAutomations ? "自动化中心" : isAssistant ? "助手联调" : isModels ? "模型接入" : isSystem ? "企业系统配置" : "内容运营工作台";
  if (isKnowledge || isHr || isFinance) {
    rememberProductCaptureRoute(false);
  } else if (isShops) {
    rememberProductCaptureRoute(false);
    window.history.replaceState(null, document.title, `${window.location.pathname}${window.location.search}#shops`);
  } else if (isProductPublish) {
    rememberProductCaptureRoute(true);
    selectCommerceSection();
  } else if (commerceRouteFromHash() || window.location.hash === "#shops" || window.location.hash.startsWith("#knowledge-") || window.location.hash.startsWith("#hr-") || window.location.hash.startsWith("#finance-")) {
    rememberProductCaptureRoute(false);
    window.history.replaceState(null, document.title, `${window.location.pathname}${window.location.search}`);
  }
  if (!isAssistant) stopAssistantCommandPolling();
  if (!isVideoScripts) {
    stopVideoScriptPolling();
    stopVideoScriptVisualProgress();
  }
  if (!isAutomations && automationAssistantHeartbeat) {
    clearInterval(automationAssistantHeartbeat);
    automationAssistantHeartbeat = null;
  }
  if (!isAiVideoTasks && aiVideoTaskPollTimer) {
    clearInterval(aiVideoTaskPollTimer);
    aiVideoTaskPollTimer = null;
  }
  if (!isAiVideoMonitor && aiVideoMonitorTimer) {
    clearInterval(aiVideoMonitorTimer);
    aiVideoMonitorTimer = null;
  }
  if (!isGeneralVideo && generalVideoPollTimer) {
    clearInterval(generalVideoPollTimer);
    generalVideoPollTimer = null;
  }
  if (!isProductPublish && productCapturePollTimer) {
    clearInterval(productCapturePollTimer);
    productCapturePollTimer = null;
  }
  if (isGeneralVideo) syncGeneralVideoPolling();
  if (isProductPublish) syncProductCapturePolling();
  document.querySelectorAll(".nav-group").forEach(group => {
    const active = Boolean(group.querySelector(".nav-item.active"));
    group.classList.toggle("has-active", active);
    if (active) group.open = true;
    group.querySelectorAll(".nav-item").forEach(link => {
      if (link.classList.contains("active")) link.setAttribute("aria-current", "page");
      else link.removeAttribute("aria-current");
    });
  });
  if (isAiVideoTasks) requestAnimationFrame(syncAiVideoTaskRailHeight);
  scheduleSlidingSwitchSync();
}

window.addEventListener("hashchange", () => {
  if (!currentUser && ["#login", "#home", "#platform-capabilities", "#platform-preview", ""].includes(window.location.hash)) {
    showLogin();
    if (["#login", "#home", ""].includes(window.location.hash)) window.scrollTo(0, 0);
    else requestAnimationFrame(() => document.querySelector(window.location.hash)?.scrollIntoView());
    return;
  }

  if (window.location.hash.startsWith('#finance-') && globalThis.FinanceWorkspace?.allowed()) {
    switchWorkspace('finance'); globalThis.FinanceWorkspace.enter(window.location.hash.slice(9)); return;
  }
  if (window.location.hash.startsWith('#knowledge-') && canAccessKnowledge()) {
    switchWorkspace('knowledge'); globalThis.KnowledgeWorkspace?.enter(window.location.hash.slice(1)); return;
  }
  if (window.location.hash.startsWith('#hr-') && globalThis.HrWorkspace?.allowed()) {
    switchWorkspace('hr'); globalThis.HrWorkspace.enter(window.location.hash.slice(4)); return;
  }
  if (window.location.hash === "#shops" && currentUser && hasAnyPermission("product:view", "product:publish")) {
    switchWorkspace("shops");
    loadMarketplaceShops();
    return;
  }
  if (!commerceRouteFromHash() || !currentUser || !canAccessProductCenter()) return;
  switchWorkspace("productPublish");
  loadProductCaptures(selectedProductCaptureId);
});

function createAssistantSessionId() {
  const secureUuid = globalThis.crypto?.randomUUID?.();
  const randomPart = secureUuid
    ? secureUuid.replaceAll("-", "")
    : `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`;
  return `test_${randomPart}`;
}

function assistantAgentHeaders(headers = {}) {
  if (!localAutomationAssistant?.agentId) return headers;
  return { ...headers, "X-Heyi-Agent-Id": localAutomationAssistant.agentId };
}

function setAssistantState(name, ready, detail) {
  const element = document.querySelector(`[data-assistant-state="${name}"]`);
  element.classList.toggle("ready", ready);
  element.classList.toggle("blocked", !ready);
  document.querySelector(`#assistant-${name}-state`).textContent = detail;
}

async function loadAssistantStatus() {
  try {
    const result = await api("/api/v1/assistant-test/status", {
      headers: assistantAgentHeaders()
    });
    const status = result.data;
    setAssistantState("model", status.modelConfigured, status.modelConfigured ? status.modelId : "未配置");
    setAssistantState("pi", status.piOnline, status.piOnline ? "运行正常" : "未启动");
    setAssistantState("agent", status.agentOnline, status.agentOnline ? status.agentName : "未连接");
    assistantImageModelReady = Boolean(status.imageModelConfigured);
    const imageState = document.querySelector("#assistant-image-model-state");
    imageState.textContent = assistantImageModelReady
      ? `图片模型 ${status.imageModelId} 已连接`
      : "图片模型未配置";
    imageState.classList.toggle("ready", assistantImageModelReady);
    syncAssistantImagePickerState();
    const ready = status.modelConfigured && status.piOnline && hasAnyPermission("assistant:use");
    const sendButton = document.querySelector("#assistant-send-button");
    sendButton.disabled = !ready;
    sendButton.title = ready ? "" : hasAnyPermission("assistant:use")
      ? "请先连接驱动模型和智能调度服务" : "当前角色只有查看权限";
    if (!status.modelConfigured) {
      showAssistantError("请先在“模型接入”中添加一个驱动模型，再返回这里测试。");
    } else if (!status.piOnline) {
      showAssistantError("智能调度服务未启动，请检查 Docker 服务。");
    } else {
      hideAssistantError();
    }
  } catch (error) {
    showAssistantError(error.message);
  }
}

function showAssistantError(message) {
  const element = document.querySelector("#assistant-form-error");
  element.textContent = assistantDisplayText(message);
  element.hidden = false;
}

function assistantDisplayText(message) {
  const text = String(message || "");
  if (/system_cpu_overloaded|status_code=503|模型服务当前繁忙/i.test(text)) {
    return "模型服务当前繁忙，系统已自动重试，请稍后再试。";
  }
  if (/status_code=429|rate.?limit|too many requests/i.test(text)) {
    return "模型服务请求过于频繁，请稍后再试。";
  }
  return text
    .replace(/Pi Agent/gi, "智能调度服务")
    .replace(/Pi Runtime/gi, "智能调度服务")
    .replace(/\bPi\b/g, "智能调度");
}

function hideAssistantError() {
  const element = document.querySelector("#assistant-form-error");
  element.hidden = true;
  element.textContent = "";
}

function appendAssistantMessage(role, text, pending = false) {
  const list = document.querySelector("#assistant-messages");
  const article = document.createElement("article");
  article.className = `assistant-message ${role === "user" ? "user-message" : ""}${pending ? " pending-message" : ""}`;
  article.innerHTML = role === "user"
    ? `<div><strong>你</strong><p>${escapeHtml(text)}</p></div><span class="message-avatar">我</span>`
    : `<span class="message-avatar">H</span><div><strong>赫逸助手</strong><p>${escapeHtml(text)}</p></div>`;
  list.append(article);
  list.scrollTop = list.scrollHeight;
  return article;
}

function readableFileSize(bytes) {
  const size = Number(bytes || 0);
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function appendAssistantArtifacts(artifacts = []) {
  const list = document.querySelector("#assistant-messages");
  artifacts.forEach(artifact => {
    if (!artifact?.id) return;
    const article = document.createElement("article");
    article.className = "assistant-message assistant-image-result";
    const id = encodeURIComponent(artifact.id);
    const label = artifact.mode === "edit" ? "图生图结果" : "文字生图结果";
    article.innerHTML = `
      <span class="message-avatar">H</span>
      <div>
        <strong>赫逸助手 · ${escapeHtml(label)}</strong>
        <section class="generated-image-card">
          <img src="/api/v1/assistant-test/artifacts/${id}" alt="${escapeHtml(label)}" loading="lazy">
          <footer>
            <span>
              <b>${escapeHtml(artifact.name || "生成图片")}</b>
              <small>${escapeHtml(artifact.modelId || "图片模型")} · ${readableFileSize(artifact.size)} · 保留 ${artifact.retentionDays || 3} 天</small>
            </span>
            <a href="/api/v1/assistant-test/artifacts/${id}?download=true" download="${escapeHtml(artifact.name || "生成图片.png")}">下载图片</a>
          </footer>
        </section>
      </div>`;
    list.append(article);
  });
  list.scrollTop = list.scrollHeight;
}

function syncAssistantImagePickerState() {
  const picker = document.querySelector("#assistant-image-picker");
  if (!picker) return;
  picker.disabled = !assistantImageModelReady
    || assistantImageUploadsInFlight > 0
    || assistantReferenceImages.length >= 1;
  picker.title = !assistantImageModelReady
    ? "请先在模型接入中配置图片模型"
    : assistantReferenceImages.length >= 1 ? "每次图生图使用 1 张参考图" : "上传图生图参考图片";
}

function renderAssistantReferenceImages() {
  const list = document.querySelector("#assistant-reference-list");
  list.hidden = assistantReferenceImages.length === 0;
  list.innerHTML = assistantReferenceImages.map((image, index) => `
    <figure class="assistant-reference-item" title="${escapeHtml(image.name)}">
      <img src="${escapeHtml(image.previewUrl)}" alt="参考图 ${index + 1}">
      <button type="button" data-remove-assistant-image="${index}" aria-label="移除 ${escapeHtml(image.name)}">×</button>
    </figure>`).join("");
  syncAssistantImagePickerState();
}

function clearAssistantReferenceImages() {
  assistantReferenceImages.forEach(image => URL.revokeObjectURL(image.previewUrl));
  assistantReferenceImages = [];
  renderAssistantReferenceImages();
  document.querySelector("#assistant-image-input").value = "";
}

document.querySelector("#assistant-image-picker").addEventListener("click", () => {
  document.querySelector("#assistant-image-input").click();
});

document.querySelector("#assistant-reference-list").addEventListener("click", event => {
  const button = event.target.closest("[data-remove-assistant-image]");
  if (!button) return;
  const index = Number(button.dataset.removeAssistantImage);
  const [removed] = assistantReferenceImages.splice(index, 1);
  if (removed) URL.revokeObjectURL(removed.previewUrl);
  renderAssistantReferenceImages();
});

document.querySelector("#assistant-image-input").addEventListener("change", async event => {
  const files = [...event.target.files];
  if (!files.length) return;
  hideAssistantError();
  assistantImageUploadsInFlight += 1;
  syncAssistantImagePickerState();
  try {
    for (const file of files) {
      if (assistantReferenceImages.length >= 1) break;
      if (!/^image\/(png|jpeg|webp)$/i.test(file.type)) {
        throw new Error(`${file.name} 不是支持的 PNG、JPG 或 WebP 图片`);
      }
      if (file.size > 20 * 1024 * 1024) {
        throw new Error(`${file.name} 超过 20MB，请选择更小的图片`);
      }
      const previewUrl = URL.createObjectURL(file);
      const form = new FormData();
      form.append("file", file);
      try {
        const uploaded = await api("/api/v1/automations/attachments", {
          method: "POST",
          body: form
        });
        assistantReferenceImages.push({
          id: uploaded.data.id,
          name: uploaded.data.name,
          size: uploaded.data.size,
          previewUrl
        });
        renderAssistantReferenceImages();
      } catch (error) {
        URL.revokeObjectURL(previewUrl);
        throw error;
      }
    }
  } catch (error) {
    showAssistantError(error.message);
  } finally {
    assistantImageUploadsInFlight -= 1;
    event.target.value = "";
    syncAssistantImagePickerState();
  }
});

function pageTaskResultText(output = {}) {
  const results = Array.isArray(output.results) ? output.results : [];
  if (!results.length) {
    return `页面任务已完成 ${output.succeededCount ?? 0}/${output.requestedCount ?? 0} 个账号。`;
  }
  return results.map(result => {
    const analysis = result.analysisResult || {};
    const lines = [`【${result.email || result.label || "账号"}】`];
    const executedActions = Array.isArray(result.executedActions) ? result.executedActions : [];
    if (result.taskMode === "INTERACTIVE_ACTION") {
      lines.push(`实际执行：${executedActions.length} 个已确认动作`);
    }
    if (analysis.overview || result.message) lines.push(analysis.overview || result.message);
    if (Array.isArray(analysis.keyPoints) && analysis.keyPoints.length) {
      lines.push("关键内容：", ...analysis.keyPoints.map(item => `- ${item}`));
    }
    if (Array.isArray(analysis.warnings) && analysis.warnings.length) {
      lines.push("注意事项：", ...analysis.warnings.map(item => `- ${item}`));
    }
    if (analysis.conclusion && analysis.conclusion !== analysis.overview) {
      lines.push(`结论：${analysis.conclusion}`);
    }
    if (result.completionEvidence) lines.push(`完成证据：${result.completionEvidence}`);
    if (result.evidenceVerified) {
      const sourceName = result.evidenceSource === "VISUAL_SCREENSHOT" ? "页面截图" : "页面内容";
      lines.push(`证据核验：已通过（${sourceName}）`);
    }
    if (result.url) lines.push(`页面：${result.url}`);
    return lines.join("\n");
  }).join("\n\n");
}

function normalizedTokenUsage(value = {}) {
  const number = key => Math.max(0, Number(value?.[key] || 0));
  return {
    inputTokens: number("inputTokens"),
    outputTokens: number("outputTokens"),
    cacheReadTokens: number("cacheReadTokens"),
    cacheWriteTokens: number("cacheWriteTokens"),
    reasoningTokens: number("reasoningTokens"),
    totalTokens: number("totalTokens"),
    modelCalls: number("modelCalls"),
    reported: Boolean(value?.reported)
  };
}

function addTokenUsages(...values) {
  const total = normalizedTokenUsage();
  values.map(normalizedTokenUsage).forEach(value => {
    Object.keys(total).filter(key => key !== "reported").forEach(key => {
      total[key] += value[key];
    });
    total.reported = total.reported || value.reported;
  });
  return total;
}

function commandTokenUsage(commands = [], selectedIds = null) {
  const selected = selectedIds instanceof Set && selectedIds.size
    ? commands.filter(command => selectedIds.has(command.id))
    : selectedIds instanceof Set ? [] : commands;
  return addTokenUsages(...selected.map(command => command.tokenUsage || {}));
}

function formatTokenCount(value) {
  return new Intl.NumberFormat((globalThis.HoyeeLanguage?.locale || "zh-CN")).format(Math.max(0, Number(value || 0)));
}

function renderAssistantTokenMonitor(commands = assistantLastCommands) {
  assistantLastCommands = Array.isArray(commands) ? commands : [];
  const currentExecution = commandTokenUsage(assistantLastCommands, assistantCurrentCommandIds);
  const sessionExecution = commandTokenUsage(assistantLastCommands);
  const current = addTokenUsages(assistantLastChatUsage, currentExecution);
  const session = addTokenUsages(assistantSessionChatUsage, sessionExecution);
  const reported = current.reported || session.reported;
  const value = (id, number) => {
    document.querySelector(id).textContent = reported ? formatTokenCount(number) : "—";
  };
  value("#assistant-token-total", current.totalTokens);
  value("#assistant-token-input", current.inputTokens);
  value("#assistant-token-output", current.outputTokens);
  value("#assistant-token-execution", currentExecution.totalTokens);
  value("#assistant-token-session", session.totalTokens);
  value("#assistant-token-calls", session.modelCalls);
  document.querySelector("#assistant-token-state").textContent = reported
    ? currentExecution.totalTokens > 0 ? "自动化持续计量中" : "本次用量已上报"
    : "等待模型上报";
  const inputShare = current.totalTokens > 0 ? current.inputTokens / current.totalTokens * 100 : 0;
  const outputShare = current.totalTokens > 0 ? current.outputTokens / current.totalTokens * 100 : 0;
  document.querySelector("#assistant-token-input-bar").style.width = `${Math.min(100, inputShare)}%`;
  document.querySelector("#assistant-token-output-bar").style.width = `${Math.min(100 - inputShare, outputShare)}%`;
  document.querySelector(".assistant-token-monitor").title = reported
    ? `缓存读取 ${formatTokenCount(current.cacheReadTokens)} · 缓存写入 ${formatTokenCount(current.cacheWriteTokens)} · 推理 ${formatTokenCount(current.reasoningTokens)}`
    : "当前模型供应商尚未返回 Token usage";
}

function renderPiTrace(trace = [], commands = []) {
  const list = document.querySelector("#assistant-trace-list");
  const items = [
    ...trace.map(item => ({
      status: item.type === "error" ? "failed" : "done",
      title: assistantDisplayText(item.label),
      detail: item.type === "model" ? "模型请求" : item.toolName || "智能调度"
    })),
    ...commands.map(command => ({
      status: command.status === "SUCCEEDED" ? "done" : command.status === "FAILED" ? "failed" : "running",
      title: ({
        FEIKUA_INSPECT: "已下发飞跨检查",
        FEIKUA_OPEN_STORE: "已下发店铺启动",
        GOOGLE_ACCOUNT_POOL_START: "已下发 Google 账号池启动",
        GOOGLE_ACCOUNT_CLOSE: "已下发账号关闭补位",
        GOOGLE_ACCOUNT_COMMAND: "已下发 Google 账号池指令",
        GOOGLE_ACCOUNT_PAGE_TASK: "已下发 Google 页面自动化"
      })[command.commandType] || command.commandType,
      detail: command.status === "QUEUED" ? "等待本机助手领取" : command.status
    }))
  ];
  if (!items.length) return;
  list.innerHTML = items.map(item => `
    <li class="${item.status}"><i></i><div><strong translate="no">${escapeHtml(item.title)}</strong><p>${escapeHtml(item.detail)}</p></div></li>
  `).join("");
  document.querySelector("#assistant-trace-state").textContent =
    items.some(item => item.status === "running") ? "执行中" : "调度完成";
}

document.querySelector("#assistant-chat-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const input = document.querySelector("#assistant-prompt");
  const prompt = input.value.trim();
  if (!prompt) return;
  if (assistantImageUploadsInFlight > 0) {
    showAssistantError("参考图仍在上传，请等待上传完成后再发送");
    return;
  }
  hideAssistantError();
  const attachmentIds = assistantReferenceImages.map(image => image.id);
  appendAssistantMessage(
    "user",
    attachmentIds.length ? `${prompt}\n（已附 ${attachmentIds.length} 张参考图）` : prompt
  );
  input.value = "";
  const pending = appendAssistantMessage("assistant", "正在理解并调度任务…", true);
  const button = document.querySelector("#assistant-send-button");
  button.disabled = true;
  button.querySelector("span").textContent = "执行中";
  document.querySelector("#assistant-trace-state").textContent = "模型处理中";
  try {
    const result = await api("/api/v1/assistant-test/chat", {
      method: "POST",
      headers: assistantAgentHeaders({ "Content-Type": "application/json" }),
      body: JSON.stringify({ sessionId: assistantSessionId, prompt, attachmentIds })
    });
    pending.remove();
    appendAssistantMessage("assistant", result.data.reply);
    appendAssistantArtifacts(result.data.artifacts || []);
    clearAssistantReferenceImages();
    assistantLastChatUsage = normalizedTokenUsage(result.data.usage || {});
    assistantSessionChatUsage = normalizedTokenUsage(result.data.usage?.session || {});
    assistantCurrentCommandIds = new Set(
      (result.data.commands || []).map(command => command.id).filter(Boolean)
    );
    renderAssistantTokenMonitor([]);
    renderPiTrace(result.data.trace, result.data.commands);
    startAssistantCommandPolling();
  } catch (error) {
    pending.remove();
    showAssistantError(error.message);
    appendAssistantMessage("assistant", `本轮没有执行：${assistantDisplayText(error.message)}`);
  } finally {
    button.disabled = false;
    button.querySelector("span").textContent = "发送并测试";
    await loadAssistantStatus();
  }
});

document.querySelectorAll("[data-assistant-prompt]").forEach(button => {
  button.addEventListener("click", () => {
    document.querySelector("#assistant-prompt").value = button.dataset.assistantPrompt;
    document.querySelector("#assistant-prompt").focus();
  });
});

document.querySelector("#clear-assistant-chat").addEventListener("click", () => {
  assistantSessionId = createAssistantSessionId();
  document.querySelector("#assistant-messages").innerHTML = `
    <article class="assistant-message system-message">
      <span class="message-avatar">H</span>
      <div><strong>赫逸助手</strong><p>新对话已开始。可以执行本机自动化，也可以直接生成图片或上传参考图进行图生图。</p></div>
    </article>`;
  document.querySelector("#assistant-trace-list").innerHTML = `
    <li class="muted"><i></i><div><strong>还没有执行记录</strong><p>发送一条测试消息后，链路步骤会显示在这里。</p></div></li>`;
  document.querySelector("#assistant-trace-state").textContent = "等待测试";
  assistantLastChatUsage = {};
  assistantSessionChatUsage = {};
  assistantCurrentCommandIds = new Set();
  assistantLastCommands = [];
  clearAssistantReferenceImages();
  renderAssistantTokenMonitor([]);
});

async function pollAssistantCommands() {
  if (document.querySelector("#assistant-test-page").hidden) return;
  try {
    const result = await api(`/api/v1/assistant-test/commands?sessionId=${encodeURIComponent(assistantSessionId)}`);
    const commands = result.data || [];
    renderAssistantTokenMonitor(commands);
    if (!commands.length) return;
    renderPiTrace([], commands);
    const latest = commands[0];
    if (latest.status === "SUCCEEDED") {
      const output = latest.output || {};
      document.querySelector("#assistant-trace-state").textContent = "链路已跑通";
      const key = `assistant-result-${latest.id}`;
      if (!sessionStorage.getItem(key)) {
        if (latest.commandType === "FEIKUA_OPEN_STORE") {
          setAssistantState("feikua", true, "店铺已打开");
          appendAssistantMessage(
            "assistant",
            `本机助手已回传：店铺“${output.storeName || "指定店铺"}”已打开，浏览器连接正常。`
          );
        } else if (latest.commandType === "FEIKUA_INSPECT") {
          setAssistantState("feikua", true, "检查完成");
          appendAssistantMessage(
            "assistant",
            `本机助手已回传：飞跨连接正常，识别 ${output.storeCount ?? 0} 个店铺环境，当前运行 ${output.runningCount ?? 0} 个。`
          );
        } else if (latest.commandType === "GOOGLE_ACCOUNT_POOL_START") {
          setAssistantState("feikua", true, "账号池已启动");
          const pageTask = output.pageTask;
          appendAssistantMessage(
            "assistant",
            `本机助手已回传：Google 账号池已启动，当前活跃 ${output.activeCount ?? 0}/${output.poolSize ?? 0} 个席位${output.targetUrl ? `，已进入 ${output.targetUrl}` : ""}。${pageTask ? `\n\n${pageTaskResultText(pageTask)}` : ""}`
          );
        } else if (["GOOGLE_ACCOUNT_CLOSE", "GOOGLE_ACCOUNT_COMMAND"].includes(latest.commandType)) {
          const closed = output.closed?.email || output.closed?.label || "指定账号";
          const replacement = output.replacement?.email || output.replacement?.label;
          setAssistantState("feikua", true, "关闭补位完成");
          appendAssistantMessage(
            "assistant",
            replacement
              ? `本机助手已回传：${closed} 已关闭，${replacement} 已自动补位。`
              : `本机助手已回传：${closed} 已关闭，当前没有可用账号补位。`
          );
        } else if (latest.commandType === "GOOGLE_ACCOUNT_PAGE_TASK") {
          setAssistantState("feikua", true, "页面任务完成");
          appendAssistantMessage(
            "assistant",
            `本机助手已回传：\n${pageTaskResultText(output)}`
          );
        } else {
          setAssistantState("feikua", true, "执行完成");
          appendAssistantMessage("assistant", "本机助手已回传：指令执行完成。");
        }
        sessionStorage.setItem(key, "shown");
      }
    } else if (latest.status === "FAILED") {
      setAssistantState("feikua", false, "执行失败");
      showAssistantError(latest.errorMessage || "本机自动化执行失败");
    }
  } catch (error) {
    showAssistantError(error.message);
  }
}

function startAssistantCommandPolling() {
  stopAssistantCommandPolling();
  assistantCommandPoll = window.setInterval(pollAssistantCommands, 1800);
  pollAssistantCommands();
}

function stopAssistantCommandPolling() {
  if (assistantCommandPoll) window.clearInterval(assistantCommandPoll);
  assistantCommandPoll = null;
}

async function loadCurrentTask() {
  const banner = document.querySelector("#current-task-banner");
  try {
    const result = await api("/api/v1/tasks/current");
    const task = result.data;
    if (!task) {
      currentWorkflowTaskId = null;
      stopWorkflowEvents();
      banner.hidden = true;
      return;
    }

    banner.hidden = false;
    document.querySelector("#current-task-detail").textContent =
      task.taskType === "WORKFLOW"
        ? "任务正在按账号串行执行，完成后才可创建下一条"
        : "赫逸自动化助手正在处理本机自动化命令";
    if (task.taskType !== "WORKFLOW") {
      currentWorkflowTaskId = null;
      stopWorkflowEvents();
      document.querySelector("#current-task-title").textContent = "助手自动化任务";
      document.querySelector("#current-task-status").textContent = task.status || "处理中";
      document.querySelector("#cancel-current-task").hidden = true;
      return;
    }

    const jobResult = await api(`/api/v1/workflow-jobs/${task.taskId}`);
    const job = jobResult.data;
    currentWorkflowTaskId = job.id;
    document.querySelector("#current-task-title").textContent = job.title;
    const status = document.querySelector("#current-task-status");
    status.textContent = statusNames[job.status] || job.status;
    status.className = `job-status status-${job.status.toLowerCase()}`;
    document.querySelector("#current-task-detail").textContent =
      job.status === "WAITING_EXECUTOR"
        ? "调度链路正常，正在等待对应的内置 Skill 执行器接入"
        : `${typeNames[job.type] || job.type} · ${job.jobNo}`;
    document.querySelector("#cancel-current-task").hidden =
      ["COMPLETED", "FAILED", "CANCELLED", "APPROVED"].includes(job.status);
    watchWorkflowEvents(job.id);
  } catch (error) {
    if (dashboardView.hidden) return;
    banner.hidden = false;
    document.querySelector("#current-task-title").textContent = "任务状态读取失败";
    document.querySelector("#current-task-detail").textContent = error.message;
  }
}

function watchWorkflowEvents(jobId) {
  if (currentWorkflowTaskId === jobId
      && workflowEventSource
      && workflowEventSource.readyState !== EventSource.CLOSED) return;
  stopWorkflowEvents();
  currentWorkflowTaskId = jobId;
  workflowEventSource = new EventSource(
    `/api/v1/workflow-jobs/${encodeURIComponent(jobId)}/events`
  );
  workflowEventSource.addEventListener("workflow", async event => {
    const update = JSON.parse(event.data);
    const status = document.querySelector("#current-task-status");
    status.textContent = statusNames[update.status] || update.status;
    status.className = `job-status status-${String(update.status).toLowerCase()}`;
    document.querySelector("#current-task-detail").textContent =
      update.message || "任务状态已更新";
    await loadJobs();
    if (["COMPLETED", "FAILED", "CANCELLED", "APPROVED"].includes(update.status)) {
      await loadCurrentTask();
    }
  });
}

function stopWorkflowEvents() {
  if (workflowEventSource) workflowEventSource.close();
  workflowEventSource = null;
}

async function loadAutomationCenter() {
  try {
    const [skillsResult, jobsResult] = await Promise.all([
      api("/api/v1/automations"),
      api("/api/v1/automations/jobs"),
      checkLocalAutomationAssistant()
    ]);
    automationSkills = skillsResult.data || [];
    renderAutomationSkills();
    renderAutomationJobs(jobsResult.data || []);
    startAutomationAssistantHeartbeat();
  } catch (error) {
    document.querySelector("#automation-skill-list").innerHTML =
      `<div class="empty-state">${escapeHtml(error.message)}</div>`;
  }
}

async function checkLocalAutomationAssistant() {
  renderAutomationAssistantLink("checking", "正在检测本机助手", "确认程序、平台心跳和接单状态…");
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 2200);
  try {
    const response = await fetch("http://127.0.0.1:17879/v1/status", {
      method: "GET",
      cache: "no-store",
      signal: controller.signal
    });
    const local = await response.json().catch(() => ({}));
    if (!response.ok || !local.agentId) {
      throw new Error("未发现当前电脑上的赫逸助手");
    }
    if (!local.platformConnected) {
      throw new Error("助手已启动，但平台心跳尚未连通");
    }
    if (!local.acceptingJobs) {
      throw new Error("助手已暂停接收任务");
    }
    const connected = await api("/api/v1/automations/assistant/connect", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ agentId: local.agentId })
    });
    localAutomationAssistant = {
      ...local,
      ...(connected.data || {}),
      ready: true,
      checkedAt: new Date().toISOString()
    };
    renderAutomationAssistantLink(
      "ready",
      "本机助手连接正常",
      `${localAutomationAssistant.name || "赫逸自动化助手"} · 心跳正常 · 正在接收任务`
    );
    return localAutomationAssistant;
  } catch (error) {
    localAutomationAssistant = {
      ready: false,
      message: error.name === "AbortError" || error instanceof TypeError
        ? "未发现当前电脑上的赫逸助手"
        : error.message
    };
    renderAutomationAssistantLink(
      "offline",
      "本机助手未连接",
      `${localAutomationAssistant.message}。请启动赫逸自动化助手并保持接单开启。`
    );
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

function renderAutomationAssistantLink(state, title, detail) {
  const panel = document.querySelector("#automation-assistant-link");
  if (!panel) return;
  panel.classList.remove("checking", "ready", "offline");
  panel.classList.add(state);
  document.querySelector("#automation-assistant-title").textContent = title;
  document.querySelector("#automation-assistant-detail").textContent = detail;
  document.querySelector("#automation-assistant-state").textContent = {
    checking: "检测中",
    ready: "连接正常",
    offline: "不可用"
  }[state] || state;
}

function startAutomationAssistantHeartbeat() {
  if (automationAssistantHeartbeat) clearInterval(automationAssistantHeartbeat);
  automationAssistantHeartbeat = setInterval(async () => {
    if (document.querySelector("#automations-page").hidden) return;
    await checkLocalAutomationAssistant();
    renderAutomationSkills();
  }, 8000);
}

async function loadAutomationAdmin() {
  try {
    const canReadUsers = hasAnyPermission("system:user:view", "system:user:manage");
    const [skillsResult, usersResult] = await Promise.all([
      api("/api/v1/system/automation-skills"),
      canReadUsers ? api("/api/v1/system/users") : Promise.resolve({ data: [] })
    ]);
    managedAutomationSkills = skillsResult.data || [];
    users = usersResult.data || [];
    renderManagedSkills(managedAutomationSkills);
    renderAutomationAdminUsers();
  } catch (error) {
    document.querySelector("#automation-admin-users").innerHTML =
      `<div class="empty-state">${escapeHtml(error.message)}</div>`;
  }
}

function renderAutomationAdminUsers() {
  const container = document.querySelector("#automation-admin-users");
  const canManage = hasAnyPermission("system:automation:manage");
  container.innerHTML = users.map(user => `
    <article>
      <span class="automation-user-avatar" aria-hidden="true">${escapeHtml((user.displayName || user.username || "人").slice(0, 1))}</span>
      <div><strong>${escapeHtml(user.displayName)}</strong><span>${escapeHtml(user.departmentName || "未分配部门")} · ${escapeHtml(user.phone || user.username)}</span></div>
      ${canManage ? `<button class="row-action" data-configure-automation-user="${escapeHtml(user.id)}">配置权限</button>` : ""}
    </article>
  `).join("") || `<div class="empty-state">${canManage ? "暂无人员。" : "当前角色可查看自动化配置，但不能修改人员授权。"}</div>`;
  container.querySelectorAll("[data-configure-automation-user]").forEach(button => {
    button.addEventListener("click", () =>
      openAutomationAccess(users.find(user => user.id === button.dataset.configureAutomationUser))
    );
  });
}

function renderManagedSkills(skills) {
  managedAutomationSkills = skills;
  const canManage = hasAnyPermission("system:automation:manage");
  const container = document.querySelector("#managed-skill-list");
  if (!container) return;
  if (!skills.length) {
    container.innerHTML = '<div class="empty-state">还没有转换或内置的 Skill。</div>';
    return;
  }
  container.innerHTML = skills.map(skill => `
    <article class="managed-skill-row">
      <div>
        <strong translate="no">${escapeHtml(skill.name)}</strong>
        <span>${escapeHtml(skill.runnerType)} · ${escapeHtml(skill.sourceType)} · v${escapeHtml(skill.version)}</span>
      </div>
      <span class="job-status status-${String(skill.status).toLowerCase()}">${escapeHtml(skill.status)}</span>
      <div class="managed-skill-actions">
        ${canManage ? `<button class="row-action" data-design-skill-form="${escapeHtml(skill.code)}">配置表单</button>
        <button class="row-action" data-configure-skill-runtime="${escapeHtml(skill.code)}">执行环境</button>` : ""}
        ${canManage && skill.status === "DRAFT"
          ? `<button class="row-action" data-activate-skill="${escapeHtml(skill.code)}">审核并启用</button>`
          : '<span class="managed-skill-ready">可授权</span>'}
        ${canManage && skill.sourceType === "UPLOADED"
          ? `<button class="row-action row-action-danger" data-delete-skill="${escapeHtml(skill.code)}" data-delete-skill-name="${escapeHtml(skill.name)}">删除</button>`
          : ""}
      </div>
    </article>
  `).join("");
  container.querySelectorAll("[data-activate-skill]").forEach(button => {
    button.addEventListener("click", async () => {
      button.disabled = true;
      try {
        await api(`/api/v1/system/automation-skills/${encodeURIComponent(button.dataset.activateSkill)}/activate`, {
          method: "POST"
        });
        await loadAutomationAdmin();
      } catch (error) {
        alert(error.message);
        button.disabled = false;
      }
    });
  });
  container.querySelectorAll("[data-design-skill-form]").forEach(button => {
    button.addEventListener("click", () =>
      openAutomationFormDesigner(button.dataset.designSkillForm)
    );
  });
  container.querySelectorAll("[data-configure-skill-runtime]").forEach(button => {
    button.addEventListener("click", () =>
      openAutomationRuntime(button.dataset.configureSkillRuntime)
    );
  });
  container.querySelectorAll("[data-delete-skill]").forEach(button => {
    button.addEventListener("click", async () => {
      const name = button.dataset.deleteSkillName || button.dataset.deleteSkill;
      if (!confirm(`确定从平台删除“${name}”吗？人员授权和平台适配包也会一并移除。`)) return;
      button.disabled = true;
      try {
        await api(`/api/v1/system/automation-skills/${encodeURIComponent(button.dataset.deleteSkill)}`, {
          method: "DELETE"
        });
        await loadAutomationAdmin();
        if (!document.querySelector("#automations-page").hidden) {
          await loadAutomationCenter();
        }
      } catch (error) {
        alert(error.message);
        button.disabled = false;
      }
    });
  });
}

function openAutomationRuntime(code) {
  const skill = managedAutomationSkills.find(item => item.code === code);
  if (!skill) return;
  automationRuntimeSkillCode = code;
  let runtime = {};
  try { runtime = JSON.parse(skill.runtimeConfig || "{}"); } catch {}
  const form = document.querySelector("#automation-runtime-form");
  form.elements.skillCode.value = code;
  form.elements.browserEngine.value = runtime.browserEngine || "FEIKUA";
  form.elements.browserType.value = runtime.browserType || "chrome";
  form.elements.browserId.value = runtime.browserId || "";
  form.elements.proxyPolicy.value = runtime.proxyPolicy || "DIRECT";
  form.elements.requiredRegion.value = runtime.requiredRegion || "";
  form.elements.allowedDomains.value = Array.isArray(runtime.allowedDomains)
    ? runtime.allowedDomains.join("\n") : "";
  document.querySelector("#automation-runtime-title").textContent =
    `配置 ${skill.name} 执行环境`;
  document.querySelector("#automation-runtime-error").hidden = true;
  document.querySelector("#automation-runtime-dialog").showModal();
}

function openAutomationFormDesigner(code) {
  const skill = managedAutomationSkills.find(item => item.code === code);
  if (!skill) return;
  automationFormSkillCode = code;
  selectedAutomationFormItemId = null;
  let schema = {};
  try { schema = JSON.parse(skill.inputSchema || "{}"); } catch {}
  const fields = Array.isArray(schema.fields)
    ? schema.fields.filter(field => field.name !== "outputRoot"
      && !(code === "YUE_BILL_EXPORT"
        && /output|输出/i.test(`${field.name || ""} ${field.label || ""}`)))
    : [];
  const prerequisites = Array.isArray(schema.prerequisites)
    ? schema.prerequisites : [];
  automationFormItems = [
    ...prerequisites.map(item => ({
      ...item,
      kind: "prerequisite",
      _id: createDesignerId()
    })),
    ...fields.map(item => ({
      ...item,
      kind: "field",
      _id: createDesignerId()
    }))
  ];
  document.querySelector("#automation-form-title").textContent =
    `配置 ${skill.name} 启动表单`;
  document.querySelector("#automation-form-error").hidden = true;
  renderAutomationFormDesigner();
  initializeAutomationFormDrag();
  document.querySelector("#automation-form-dialog").showModal();
}

function createDesignerId() {
  return `form_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}

function designerTypeName(type) {
  return {
    text: "单行文本",
    textarea: "多行文本",
    number: "数字",
    date: "日期",
    select: "下拉选择",
    checkbox: "确认开关",
    localFile: "文件上传",
    localDirectory: "本机文件夹",
    prerequisite: "前置确认"
  }[type] || type;
}

function defaultDesignerItem(type) {
  const index = automationFormItems.filter(item => item.kind === "field").length + 1;
  if (type === "prerequisite") {
    return {
      _id: createDesignerId(),
      kind: "prerequisite",
      label: "请确认前置条件",
      description: ""
    };
  }
  return {
    _id: createDesignerId(),
    kind: "field",
    type,
    name: `parameter${index}`,
    label: designerTypeName(type),
    required: true,
    ...(type === "select" ? { options: ["选项一", "选项二"] } : {}),
    ...(type === "number" ? { min: 1, max: 100 } : {}),
    ...(type === "localFile" ? { accept: ".xlsx", maxFileMb: 100 } : {})
  };
}

function addAutomationFormItem(type) {
  const item = defaultDesignerItem(type);
  automationFormItems.push(item);
  selectedAutomationFormItemId = item._id;
  renderAutomationFormDesigner();
}

function renderAutomationFormDesigner() {
  const canvas = document.querySelector("#automation-form-canvas");
  canvas.innerHTML = automationFormItems.map(item => `
    <article class="designer-field-card ${item._id === selectedAutomationFormItemId ? "selected" : ""}"
      data-designer-id="${escapeHtml(item._id)}">
      <span class="drag-mark" aria-hidden="true">⠿</span>
      <div>
        <strong>${escapeHtml(item.label || "未命名控件")}</strong>
        <small>${item.kind === "prerequisite"
          ? "员工必须勾选确认"
          : `${designerTypeName(item.type)} · ${escapeHtml(item.name || "未设置参数名")}`}</small>
      </div>
      <em>${item.required === false ? "选填" : "必填"}</em>
    </article>
  `).join("");
  document.querySelector("#automation-form-count").textContent =
    `${automationFormItems.length} 个控件`;
  canvas.querySelectorAll("[data-designer-id]").forEach(card => {
    card.addEventListener("click", () => {
      selectedAutomationFormItemId = card.dataset.designerId;
      renderAutomationFormDesigner();
    });
  });
  renderAutomationFieldSettings();
}

function renderAutomationFieldSettings() {
  const panel = document.querySelector("#automation-field-settings");
  const item = automationFormItems.find(
    value => value._id === selectedAutomationFormItemId
  );
  if (!item) {
    panel.innerHTML = `<div class="designer-settings-empty">
      <strong>字段设置</strong><span>选择中间的控件进行配置。</span>
    </div>`;
    return;
  }
  const commonHeader = `<header><strong>${designerTypeName(
    item.kind === "prerequisite" ? "prerequisite" : item.type
  )}</strong><button type="button" class="designer-remove" data-remove-designer-item>删除控件</button></header>`;
  panel.innerHTML = item.kind === "prerequisite"
    ? `<div class="designer-settings-form">${commonHeader}
        <label>确认标题<input data-designer-prop="label" maxlength="120" value="${escapeHtml(item.label || "")}"></label>
        <label>辅助说明<textarea data-designer-prop="description" rows="4" maxlength="300">${escapeHtml(item.description || "")}</textarea></label>
      </div>`
    : `<div class="designer-settings-form">${commonHeader}
        <label>显示名称<input data-designer-prop="label" maxlength="80" value="${escapeHtml(item.label || "")}"></label>
        <label>数据标识（可留空）<input data-designer-prop="name" maxlength="64" value="${escapeHtml(item.name || "")}" placeholder="留空时系统自动生成"></label>
        <label>提示文字<input data-designer-prop="placeholder" maxlength="160" value="${escapeHtml(item.placeholder || "")}"></label>
        ${item.type === "select" ? `<label>选项（每行一个）<textarea data-designer-prop="options" rows="5">${escapeHtml((item.options || []).join("\n"))}</textarea></label>` : ""}
        ${item.type === "number" ? `
          <label>最小值<input data-designer-prop="min" type="number" value="${item.min ?? ""}"></label>
          <label>最大值<input data-designer-prop="max" type="number" value="${item.max ?? ""}"></label>` : ""}
        ${item.type === "localFile" ? `
          <div class="designer-setting-note">员工点击后从当前电脑选择文件，文件会随任务上传到平台。</div>
          <label>允许格式<input data-designer-prop="accept" maxlength="120" value="${escapeHtml(item.accept || "")}" placeholder="例如 .xlsx,.csv"></label>
          <label>最大文件大小（MB）<input data-designer-prop="maxFileMb" type="number" min="1" max="100" value="${item.maxFileMb ?? 100}"></label>` : ""}
        <label class="designer-check"><input data-designer-prop="required" type="checkbox" ${item.required === false ? "" : "checked"}><span>设为必填</span></label>
      </div>`;
  panel.querySelectorAll("[data-designer-prop]").forEach(input => {
    input.addEventListener("input", () => {
      const property = input.dataset.designerProp;
      if (property === "required") item[property] = input.checked;
      else if (property === "options") {
        item[property] = input.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
      } else if (["min", "max", "maxFileMb"].includes(property)) {
        item[property] = input.value === "" ? null : Number(input.value);
      } else item[property] = input.value;
      const card = document.querySelector(
        `#automation-form-canvas [data-designer-id="${CSS.escape(item._id)}"]`
      );
      if (card) {
        card.querySelector("strong").textContent = item.label || "未命名控件";
        card.querySelector("small").textContent = item.kind === "prerequisite"
          ? "员工必须勾选确认"
          : `${designerTypeName(item.type)} · ${item.name || "未设置参数名"}`;
        card.querySelector("em").textContent =
          item.required === false ? "选填" : "必填";
      }
    });
  });
  panel.querySelector("[data-remove-designer-item]").addEventListener("click", () => {
    automationFormItems = automationFormItems.filter(
      value => value._id !== item._id
    );
    selectedAutomationFormItemId = null;
    renderAutomationFormDesigner();
  });
}

function initializeAutomationFormDrag() {
  if (typeof Sortable === "undefined") {
    document.querySelector("#automation-form-error").textContent =
      "拖拽组件未加载，请刷新页面后重试";
    document.querySelector("#automation-form-error").hidden = false;
    return;
  }
  if (!automationFormPaletteSortable) {
    automationFormPaletteSortable = new Sortable(
      document.querySelector("#automation-field-palette"),
      {
        group: { name: "automation-form-fields", pull: "clone", put: false },
        sort: false,
        animation: 140
      }
    );
    document.querySelectorAll("#automation-field-palette [data-field-type]")
      .forEach(button => button.addEventListener("click", () =>
        addAutomationFormItem(button.dataset.fieldType)
      ));
  }
  if (automationFormCanvasSortable) automationFormCanvasSortable.destroy();
  automationFormCanvasSortable = new Sortable(
    document.querySelector("#automation-form-canvas"),
    {
      group: { name: "automation-form-fields", pull: false, put: true },
      animation: 140,
      ghostClass: "designer-sort-ghost",
      onAdd(event) {
        const type = event.item.dataset.fieldType;
        event.item.remove();
        const item = defaultDesignerItem(type);
        automationFormItems.splice(event.newIndex, 0, item);
        selectedAutomationFormItemId = item._id;
        renderAutomationFormDesigner();
      },
      onEnd() {
        const ids = [...document.querySelectorAll(
          "#automation-form-canvas [data-designer-id]"
        )].map(card => card.dataset.designerId);
        automationFormItems = ids.map(id =>
          automationFormItems.find(item => item._id === id)
        ).filter(Boolean);
        renderAutomationFormDesigner();
      }
    }
  );
}

function renderAutomationSkills() {
  const container = document.querySelector("#automation-skill-list");
  const count = document.querySelector("#automation-skill-count");
  if (count) count.textContent = `${automationSkills.length} 项可用`;
  if (!automationSkills.length) {
    container.innerHTML = '<div class="empty-state automation-empty-state"><i aria-hidden="true">↯</i><strong>还没有可执行的自动化</strong><span>管理员授权后，工作流会出现在这里。</span><small>可前往“自动化管理”为当前账号开通权限</small></div>';
    return;
  }
  const canExecute = hasAnyPermission("automation:execute");
  const assistantReady = Boolean(localAutomationAssistant?.ready) && canExecute;
  container.innerHTML = automationSkills.map(skill => `
    <article class="automation-skill-card">
      <header><div><h3 translate="no">${escapeHtml(skill.name)}</h3><small>${escapeHtml(skill.runnerType)} · v${escapeHtml(skill.version)}</small></div><span class="job-status status-completed">已授权</span></header>
      <p translate="${skill.description ? 'no' : 'yes'}">${escapeHtml(skill.description || "本机自动化 Skill")}</p>
      <footer><span>${escapeHtml(skill.supportedOs)}</span><button class="new-job-button" data-run-skill="${escapeHtml(skill.code)}"
        ${assistantReady ? "" : `disabled title="${canExecute ? "请先启动并连接本机助手" : "当前角色只有查看权限"}"`}>${assistantReady ? "执行" : canExecute ? "助手未连接" : "仅可查看"}</button></footer>
    </article>
  `).join("");
  container.querySelectorAll("[data-run-skill]").forEach(button => {
    button.addEventListener("click", () => openAutomationRun(button.dataset.runSkill));
  });
}

async function openAutomationRun(code) {
  const skill = automationSkills.find(item => item.code === code);
  if (!skill) return;
  if (!localAutomationAssistant?.ready) {
    await checkLocalAutomationAssistant();
    renderAutomationSkills();
    if (!localAutomationAssistant?.ready) return;
  }
  stopAutomationRunPoll();
  resetAutomationPlan();
  selectedAutomationCode = code;
  document.querySelector("#automation-run-title").textContent = skill.name;
  document.querySelector("#automation-run-subtitle").textContent =
    "开始前填写一次，提交后本轮不可修改。";
  document.querySelector("#automation-setup-stage").hidden = false;
  document.querySelector("#automation-live-stage").hidden = true;
  document.querySelector("#automation-start-button").disabled = false;
  document.querySelector("#automation-start-button").textContent = "检查执行计划";
  document.querySelector("#automation-execution-requirement").value = "";
  let schema = {};
  try { schema = JSON.parse(skill.inputSchema || "{}"); } catch {}
  const fields = (schema.fields || []).filter(field => field.name !== "outputRoot"
    && !(code === "YUE_BILL_EXPORT"
      && /output|输出/i.test(`${field.name || ""} ${field.label || ""}`)));
  document.querySelector("#automation-run-fields").innerHTML = fields.map(field => {
    const fieldClass = ["localFile", "localDirectory"].includes(field.type)
      ? "full-field" : "";
    const required = field.required ? "required" : "";
    const placeholder = escapeHtml(field.placeholder || (
      field.type === "localDirectory" ? "请选择本机输出文件夹" : ""
    ));
    if (field.type === "textarea") {
      return `<label class="${fieldClass}">${escapeHtml(field.label || field.name)}
        <textarea name="${escapeHtml(field.name)}" rows="4" ${required} placeholder="${placeholder}">${escapeHtml(field.default ?? "")}</textarea>
      </label>`;
    }
    if (field.type === "select") {
      return `<label class="${fieldClass}">${escapeHtml(field.label || field.name)}
        <select name="${escapeHtml(field.name)}" ${required}>
          ${(field.options || []).map(option =>
            `<option value="${escapeHtml(option)}">${escapeHtml(option)}</option>`
          ).join("")}
        </select>
      </label>`;
    }
    if (field.type === "checkbox") {
      return `<label class="${fieldClass} automation-checkbox-field">
        <input name="${escapeHtml(field.name)}" type="checkbox" ${required}>
        <span translate="no">${escapeHtml(field.label || field.name)}</span>
      </label>`;
    }
    if (field.type === "localFile") {
      return `<label class="${fieldClass}">${escapeHtml(field.label || field.name)}
        <input name="${escapeHtml(field.name)}" type="file" ${required}
          ${field.accept ? `accept="${escapeHtml(field.accept)}"` : ""}
          data-max-file-mb="${Number(field.maxFileMb || 100)}">
        <small class="field-help">从当前电脑选择文件，单个文件不超过 ${Number(field.maxFileMb || 100)}MB。</small>
      </label>`;
    }
    const inputType = field.type === "date" ? "date"
      : field.type === "number" ? "number" : "text";
    const input = `<input name="${escapeHtml(field.name)}" type="${inputType}" ${required}
      value="${escapeHtml(field.default ?? "")}" placeholder="${placeholder}"
      ${field.type === "localDirectory" ? "readonly" : ""}
      ${field.min != null ? `min="${field.min}"` : ""} ${field.max != null ? `max="${field.max}"` : ""}>`;
    return `<label class="${fieldClass}">${escapeHtml(field.label || field.name)}
      ${field.type === "localDirectory"
        ? `<span class="local-path-control">${input}<button type="button" class="quiet-button" data-pick-directory="${escapeHtml(field.name)}">选择文件夹</button></span>`
        : input}
    </label>`;
  }).join("") || `
    <div class="automation-form-unconfigured">
      <strong>启动表单尚未配置</strong>
      <span>请由管理员在“自动化管理”中配置后再执行。</span>
    </div>`;
  document.querySelectorAll("[data-pick-directory]").forEach(button => {
    button.addEventListener("click", () => pickLocalDirectory(button));
  });
  const prerequisites = Array.isArray(schema.prerequisites) ? schema.prerequisites : [];
  const prerequisiteBox = document.querySelector("#automation-prerequisites");
  prerequisiteBox.hidden = prerequisites.length === 0;
  prerequisiteBox.innerHTML = prerequisites.length
    ? `<strong>开始前确认</strong>${prerequisites.map(item => `
        <label>
          <input type="checkbox" name="prerequisite" required>
          <span><b>${escapeHtml(item.label || item.title || "确认事项")}</b>
          ${item.description ? `<small translate="no">${escapeHtml(item.description)}</small>` : ""}</span>
        </label>`).join("")}`
    : "";
  document.querySelector("#automation-start-button").disabled = fields.length === 0;
  document.querySelector("#automation-run-error").hidden = true;
  document.querySelector("#automation-run-dialog").showModal();
}

async function pickLocalDirectory(button) {
  const input = document.querySelector(
    `#automation-run-fields [name="${CSS.escape(button.dataset.pickDirectory)}"]`
  );
  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = "等待本机选择…";
  try {
    const response = await fetch("http://127.0.0.1:17879/v1/picker/directory", {
      method: "GET",
      cache: "no-store"
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || "本机助手拒绝了选择请求");
    if (!result.cancelled && result.path) {
      input.value = result.path;
      input.dispatchEvent(new Event("change", { bubbles: true }));
    }
  } catch (error) {
    alert(`无法调用赫逸助手选择文件夹：${error.message}。请确认本机助手已启动。`);
  } finally {
    button.disabled = false;
    button.textContent = originalText;
  }
}

async function collectAutomationParameters(form) {
  const parameters = {};
  const inputs = [...form.querySelectorAll("#automation-run-fields [name]")];
  for (const input of inputs) {
    if (input.type === "file") {
      const file = input.files?.[0];
      if (!file) {
        if (input.required) throw new Error("请选择需要上传的文件");
        parameters[input.name] = "";
        continue;
      }
      const maxFileMb = Number(input.dataset.maxFileMb || 100);
      if (file.size > maxFileMb * 1024 * 1024) {
        throw new Error(`${file.name} 超过 ${maxFileMb}MB，请选择更小的文件`);
      }
      const upload = new FormData();
      upload.append("file", file, file.name);
      const uploaded = await api("/api/v1/automations/attachments", {
        method: "POST",
        body: upload
      });
      parameters[input.name] = uploaded.data.reference;
    } else {
      parameters[input.name] = input.type === "checkbox"
        ? input.checked
        : input.type === "number" ? Number(input.value) : input.value.trim();
    }
  }
  parameters.executionRequirement =
    document.querySelector("#automation-execution-requirement").value.trim();
  return parameters;
}

function renderAutomationPlan(plan) {
  const source = plan.modelId === "RULE_FALLBACK"
    ? "规则回退"
    : plan.modelUsed ? (plan.cached ? "模型计划 · 已缓存" : "模型计划") : "固定参数";
  document.querySelector("#automation-plan-source").textContent = source;
  document.querySelector("#automation-plan-summary").textContent =
    plan.summary || "按当前表单参数执行";
  const labels = {
    storeLimit: "最多店铺任务",
    maxConcurrency: "同时执行店铺",
    maxLiveStoreSessions: "最多浏览器会话",
    onlyPlatform: "限定平台",
    onlyStore: "限定店铺",
    startDate: "开始日期",
    endDate: "结束日期"
  };
  document.querySelector("#automation-plan-parameters").innerHTML =
    Object.entries(plan.parameters || {})
      .filter(([key]) => !["workbookPath", "outputRoot"].includes(key))
      .map(([key, value]) => `<div><dt>${escapeHtml(labels[key] || key)}</dt><dd>${
        escapeHtml(key === "storeLimit" && Number(value) === 0 ? "不限制" : String(value))
      }</dd></div>`).join("");
  const notes = [...(plan.assumptions || []), ...(plan.warnings || [])];
  document.querySelector("#automation-plan-notes").innerHTML = notes.length
    ? notes.map(note => `<p>${escapeHtml(note)}</p>`).join("")
    : "";
  document.querySelector("#automation-plan-preview").hidden = false;
}

function setAutomationPlanLocked(locked) {
  document.querySelectorAll(
    "#automation-run-fields input, #automation-run-fields textarea, #automation-run-fields select, #automation-prerequisites input, #automation-execution-requirement"
  ).forEach(input => { input.disabled = locked; });
}

function resetAutomationPlan() {
  automationPlanRequestInFlight = false;
  pendingAutomationPlan = null;
  pendingAutomationParameters = null;
  const preview = document.querySelector("#automation-plan-preview");
  if (preview) preview.hidden = true;
  setAutomationPlanLocked(false);
  const button = document.querySelector("#automation-start-button");
  if (button) button.textContent = "检查执行计划";
  const loading = document.querySelector("#automation-plan-loading");
  if (loading) loading.hidden = true;
}

document.querySelector("#automation-replan-button").addEventListener("click", () => {
  resetAutomationPlan();
  document.querySelector("#automation-run-error").hidden = true;
});

document.querySelector("#automation-run-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (automationPlanRequestInFlight) return;
  const errorBox = document.querySelector("#automation-run-error");
  const startButton = document.querySelector("#automation-start-button");
  startButton.disabled = true;
  try {
    errorBox.hidden = true;
    if (!pendingAutomationPlan) {
      automationPlanRequestInFlight = true;
      startButton.innerHTML = '<i class="button-spinner" aria-hidden="true"></i> Pi Agent 正在理解要求…';
      document.querySelector("#automation-plan-loading").hidden = false;
      pendingAutomationParameters = await collectAutomationParameters(event.currentTarget);
      const planned = await api(
        `/api/v1/automations/${encodeURIComponent(selectedAutomationCode)}/plan`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(pendingAutomationParameters)
        }
      );
      pendingAutomationPlan = planned.data;
      renderAutomationPlan(pendingAutomationPlan);
      document.querySelector("#automation-plan-loading").hidden = true;
      setAutomationPlanLocked(true);
      startButton.textContent = "确认计划并开始";
      startButton.disabled = false;
      return;
    }
    const result = await api(
      `/api/v1/automations/${encodeURIComponent(selectedAutomationCode)}/execute`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Heyi-Agent-Id": localAutomationAssistant.agentId
        },
        body: JSON.stringify(pendingAutomationParameters)
      }
    );
    showAutomationLive(result.data);
    await loadAutomationCenter();
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.hidden = false;
    startButton.disabled = false;
  } finally {
    automationPlanRequestInFlight = false;
    const loading = document.querySelector("#automation-plan-loading");
    if (loading && !pendingAutomationPlan) loading.hidden = true;
    if (!pendingAutomationPlan) startButton.textContent = "检查执行计划";
  }
});

document.querySelector("#automation-run-form-legacy")?.addEventListener("submit", async event => {
  event.preventDefault();
  const errorBox = document.querySelector("#automation-run-error");
  const parameters = {};
  const startButton = document.querySelector("#automation-start-button");
  startButton.disabled = true;
  try {
    const inputs = [...event.currentTarget.querySelectorAll("#automation-run-fields [name]")];
    for (const input of inputs) {
      if (input.type === "file") {
        const file = input.files?.[0];
        if (!file) {
          if (input.required) throw new Error(`请选择${input.closest("label")?.childNodes[0]?.textContent?.trim() || "文件"}`);
          parameters[input.name] = "";
          continue;
        }
        const maxFileMb = Number(input.dataset.maxFileMb || 100);
        if (file.size > maxFileMb * 1024 * 1024) {
          throw new Error(`${file.name} 超过 ${maxFileMb}MB，请选择更小的文件`);
        }
        const upload = new FormData();
        upload.append("file", file, file.name);
        const uploaded = await api("/api/v1/automations/attachments", {
          method: "POST",
          body: upload
        });
        parameters[input.name] = uploaded.data.reference;
      } else {
        parameters[input.name] = input.type === "checkbox"
          ? input.checked
          : input.type === "number" ? Number(input.value) : input.value.trim();
      }
    }
    const result = await api(`/api/v1/automations/${encodeURIComponent(selectedAutomationCode)}/execute`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Heyi-Agent-Id": localAutomationAssistant.agentId
      },
      body: JSON.stringify(parameters)
    });
    showAutomationLive(result.data);
    await loadAutomationCenter();
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.hidden = false;
    startButton.disabled = false;
  }
});

function renderAutomationJobs(jobs) {
  automationJobs = jobs;
  const list = document.querySelector("#automation-job-list");
  const count = document.querySelector("#automation-job-count");
  if (count) count.textContent = `${jobs.length} 条记录`;
  if (!jobs.length) {
    IncrementalDOM.html(list, '<div class="empty-state automation-empty-state compact"><i aria-hidden="true">✓</i><strong>这里还很安静</strong><span>运行第一项自动化后，进度与结果会保存在这里。</span></div>');
    return;
  }
  IncrementalDOM.html(list, jobs.map(job => `
    <button class="job-row automation-job-button" data-view-automation-job="${escapeHtml(job.id)}">
      <div class="job-icon">↯</div>
      <div class="job-main"><strong>${escapeHtml(job.automationCode)}</strong><span>${escapeHtml(job.progressMessage || "等待执行")}</span></div>
      <span class="job-status status-${job.status.toLowerCase()}">${escapeHtml(job.status)}</span>
      <time>${job.progressPercent}%</time>
    </button>
  `).join(""));
  list.querySelectorAll("[data-view-automation-job]").forEach(button => {
    button.onclick = () => {
      const job = automationJobs.find(item => item.id === button.dataset.viewAutomationJob);
      if (job) openAutomationJob(job);
    };
  });
}

function openAutomationJob(job) {
  stopAutomationRunPoll();
  selectedAutomationCode = job.automationCode;
  const skill = automationSkills.find(item => item.code === job.automationCode);
  document.querySelector("#automation-run-title").textContent =
    skill?.name || job.automationCode;
  document.querySelector("#automation-run-dialog").showModal();
  showAutomationLive(job);
}

function showAutomationLive(job) {
  document.querySelector("#automation-setup-stage").hidden = true;
  document.querySelector("#automation-live-stage").hidden = false;
  document.querySelector("#automation-run-subtitle").textContent =
    "本轮输入已锁定，下面只展示执行过程和返回结果。";
  renderAutomationLive(job, []);
  pollAutomationJob(job.id);
}

async function pollAutomationJob(jobId) {
  stopAutomationRunPoll();
  let stopped = false;
  let failedPolls = 0;
  let summaryPolls = 0;
  let unitAnalysisPolls = 0;
  const refresh = async () => {
    try {
      const [jobResult, eventResult] = await Promise.all([
        api(`/api/v1/automations/jobs/${encodeURIComponent(jobId)}`),
        api(`/api/v1/automations/jobs/${encodeURIComponent(jobId)}/events`)
      ]);
      renderAutomationLive(jobResult.data, eventResult.data || []);
      const hasDiagnosis = (eventResult.data || []).some(
        event => event.eventType === "MODEL_DIAGNOSIS"
      );
      const hasSummary = (eventResult.data || []).some(
        event => event.eventType === "MODEL_SUMMARY"
      );
      const unitResultCount = (eventResult.data || []).filter(
        event => event.eventType === "EXECUTION_UNIT_RESULT"
      ).length;
      const analyzedUnitIds = new Set(
        (eventResult.data || [])
          .filter(event => event.eventType === "MODEL_UNIT_RESULT")
          .map(event =>
            event.payload?.unitResult?.unitId
            || event.payload?.unitResult?.unitName
            || event.id
          )
      );
      if (jobResult.data.status === "FAILED" && !hasDiagnosis && failedPolls < 50) {
        failedPolls += 1;
        return;
      }
      if (jobResult.data.status === "SUCCEEDED" && !hasSummary && summaryPolls < 30) {
        summaryPolls += 1;
        return;
      }
      if (
        ["SUCCEEDED", "FAILED"].includes(jobResult.data.status)
        && analyzedUnitIds.size < unitResultCount
        && unitAnalysisPolls < 60
      ) {
        unitAnalysisPolls += 1;
        return;
      }
      if (["SUCCEEDED", "FAILED", "CANCELLED", "PAUSED", "INTERRUPTED"].includes(jobResult.data.status)) {
        stopped = true;
        stopAutomationRunPoll();
        await loadAutomationCenter();
      }
    } catch (error) {
      document.querySelector("#automation-event-stream").innerHTML =
        `<div class="automation-event error"><span>平台</span><p>${escapeHtml(error.message)}</p></div>`;
      stopAutomationRunPoll();
      stopped = true;
    }
  };
  await refresh();
  if (!stopped) automationRunPoll = setInterval(refresh, 2000);
}

function stopAutomationRunPoll() {
  if (automationRunPoll) clearInterval(automationRunPoll);
  automationRunPoll = null;
}

function renderAutomationLive(job, events) {
  if (!job) return;
  const skill = automationSkills.find(item => item.code === job.automationCode);
  let schema = {};
  try { schema = JSON.parse(skill?.inputSchema || "{}"); } catch {}
  const fieldLabels = Object.fromEntries((schema.fields || []).map(field => [
    field.name, field.label || field.name
  ]));
  const input = job.input || {};
  const parameters = input.parameters || input;
  document.querySelector("#automation-live-status").textContent =
    automationStatusName(job.status);
  document.querySelector("#automation-live-status").className =
    `job-status status-${String(job.status).toLowerCase()}`;
  document.querySelector("#automation-live-progress").textContent =
    `${job.progressPercent || 0}%`;
  document.querySelector("#automation-progress-bar").style.width =
    `${job.progressPercent || 0}%`;
  renderAutomationControls(job);
  renderAutomationBusinessResults(job, events);
  IncrementalDOM.html(document.querySelector("#automation-input-snapshot"), Object.entries(parameters || {}).map(([key, value]) =>
      `<div><dt>${escapeHtml(fieldLabels[key] || key)}</dt><dd>${escapeHtml(
        String(value).startsWith("heyi-attachment:")
          ? String(value).split(":").slice(2).join(":")
          : String(value)
      )}</dd></div>`
    ).join("") || "<div><dd>没有额外输入参数</dd></div>");
  document.querySelector("#automation-event-count").textContent =
    `${events.length} 条`;
  const stream = document.querySelector("#automation-event-stream");
  const followTail = stream.scrollHeight - stream.scrollTop - stream.clientHeight < 48;
  IncrementalDOM.html(stream, events.length
    ? events.map(event => `
      <article data-update-key="${escapeHtml(event.id || `${event.eventType}:${event.createdAt}`)}" class="automation-event ${String(event.eventType).toLowerCase()}">
        <div><span>${automationSourceName(event.source)}</span><time>${formatAutomationTime(event.createdAt)}</time></div>
        <p>${escapeHtml(event.message)}</p>
        ${event.payload && ["MODEL_DIAGNOSIS", "MODEL_RECOVERY"].includes(event.eventType)
          ? `<pre>${escapeHtml(JSON.stringify(event.payload, null, 2))}</pre>`
          : ""}
      </article>
    `).join("")
    : '<div class="automation-event-empty">正在等待本机助手返回第一条消息…</div>');
  if (followTail) stream.scrollTop = stream.scrollHeight;
  const resultPanel = document.querySelector("#automation-result-panel");
  const result = job.output || (job.errorMessage ? { error: job.errorMessage } : null);
  resultPanel.hidden = !result;
  const artifact = job.output?.artifact;
  const delivery = job.output?.delivery;
  IncrementalDOM.html(document.querySelector("#automation-result-download"), artifact?.id
    ? `<a href="/api/v1/automations/attachments/${encodeURIComponent(artifact.id)}" download="${escapeHtml(artifact.name || "任务结果.zip")}">下载任务结果</a>
       <span>${escapeHtml(artifact.name || "任务结果.zip")} · 仅保留 ${artifact.retentionDays || 3} 天</span>`
    : delivery?.status === "FAILED"
      ? `<span class="automation-delivery-error">业务执行已经完成，但结果包生成失败。本机结果已保留，请重试交付或联系管理员。${escapeHtml(delivery.message || "")}</span>`
      : "");
  const businessResult = job.output?.businessResult;
  document.querySelector("#automation-result-output").textContent = result
    ? businessResult
      ? `本轮共处理 ${businessResult.total || 0} 家：成功 ${businessResult.succeeded || 0} 家，待处理 ${businessResult.needsAttention || 0} 家，失败 ${businessResult.failed || 0} 家。具体原因见上方店铺结果。`
      : (job.errorMessage || "任务结果已生成")
    : "";
}

function renderAutomationBusinessResults(job, events) {
  const panel = document.querySelector("#automation-business-results");
  const unitMap = new Map();
  for (const event of events.filter(item => item.eventType === "EXECUTION_UNIT_RESULT")) {
    const unit = event.payload || {};
    unitMap.set(unit.unitId || unit.unitName || event.id, { ...unit });
  }
  // Final output is authoritative; earlier recovery attempts are history.
  for (const unit of job.output?.businessResult?.units || []) {
    unitMap.set(unit.unitId || unit.unitName, { ...unit });
  }
  const analysisMap = new Map();
  for (const event of events.filter(item => item.eventType === "MODEL_UNIT_RESULT")) {
    const unit = event.payload?.unitResult || {};
    analysisMap.set(unit.unitId || unit.unitName || event.id, {
      message: event.message,
      diagnosis: event.payload?.diagnosis || {}
    });
  }
  const units = [...unitMap.values()];
  panel.hidden = units.length === 0;
  if (!units.length) return;
  const count = outcome => units.filter(unit => unit.outcome === outcome).length;
  document.querySelector("#automation-result-counts").textContent =
    `共 ${units.length} 项 · 成功 ${count("SUCCESS")} · 待处理 ${count("NEEDS_ATTENTION")} · 失败 ${count("FAILED")}`;
  IncrementalDOM.html(document.querySelector("#automation-result-units"), units.map(unit => {
    const analysis = analysisMap.get(unit.unitId || unit.unitName) || {};
    const diagnosis = analysis.diagnosis || {};
    const statusClass = String(unit.outcome || "UNKNOWN").toLowerCase();
    const statusName = unit.outcome === "SUCCESS"
      ? (unit.status === "no_data" ? "无数据" : "成功")
      : unit.outcome === "NEEDS_ATTENTION" ? "待处理" : "失败";
    const description = unit.outcome === "SUCCESS"
      ? (unit.message
        || diagnosis.resultDescription
        || analysis.message
        || unit.statusText
        || "已完成")
      : (diagnosis.resultDescription
        || unit.message
        || analysis.message
        || unit.statusText
        || "模型正在复核未完成原因");
    const output = diagnosis.outputDescription
      || (unit.verifiedFileCount
        ? `已确认 ${unit.verifiedFileCount} 个结果文件`
        : "");
    return `<article data-update-key="${escapeHtml(unit.unitId || unit.unitName)}" class="automation-result-unit ${statusClass}">
      <div><strong>${escapeHtml(unit.unitName || "未命名执行项")}</strong><small>${escapeHtml(unit.platform || unit.unitType || "")}</small></div>
      <div><p>${escapeHtml(description)}</p>${output ? `<small>${escapeHtml(output)}</small>` : ""}</div>
      <span>${escapeHtml(statusName)}</span>
    </article>`;
  }).join(""));
}

function renderAutomationControls(job) {
  const bar = document.querySelector("#automation-control-bar");
  const controls = {
    QUEUED: [
      ["PAUSE", "暂停", "quiet-button"],
      ["CANCEL", "取消任务", "control-danger"]
    ],
    RUNNING: [
      ["PAUSE", "暂停", "quiet-button"],
      ["INTERRUPT", "立即中断", "control-warning"],
      ["CANCEL", "取消任务", "control-danger"]
    ],
    PAUSED: [
      ["RESUME", "从断点继续", "new-job-button"],
      ["CANCEL", "取消任务", "control-danger"]
    ],
    INTERRUPTED: [
      ["RESUME", "从断点恢复", "new-job-button"],
      ["CANCEL", "取消任务", "control-danger"]
    ]
  }[job.status] || [];
  if (String(job.status).endsWith("_REQUESTED")) {
    IncrementalDOM.html(bar, `<span>控制请求已发送，正在等待本机助手确认…</span>`);
    return;
  }
  IncrementalDOM.html(bar, controls.length
    ? `<span>任务控制</span><div>${controls.map(([action, label, className]) =>
        `<button type="button" class="${className}" data-automation-control="${action}" data-job-id="${escapeHtml(job.id)}">${label}</button>`
      ).join("")}</div>`
    : "");
  bar.querySelectorAll("[data-automation-control]").forEach(button => {
    button.onclick = async () => {
      const action = button.dataset.automationControl;
      if (action === "CANCEL" && !confirm("取消后本轮不能继续，确定取消吗？")) return;
      button.disabled = true;
      try {
        const result = await api(
          `/api/v1/automations/jobs/${encodeURIComponent(button.dataset.jobId)}/control`,
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ action })
          }
        );
        renderAutomationLive(result.data, []);
        pollAutomationJob(result.data.id);
      } catch (error) {
        alert(error.message);
        button.disabled = false;
      }
    };
  });
}

function automationStatusName(status) {
  return {
    QUEUED: "等待本机助手",
    RUNNING: "正在执行",
    PAUSE_REQUESTED: "正在暂停",
    INTERRUPT_REQUESTED: "正在中断",
    CANCEL_REQUESTED: "正在取消",
    PAUSED: "已暂停",
    INTERRUPTED: "已中断",
    SUCCEEDED: "执行完成",
    FAILED: "执行失败",
    CANCELLED: "已取消"
  }[status] || status;
}

function automationSourceName(source) {
  return {
    PLATFORM: "平台",
    LOCAL_ASSISTANT: "赫逸助手",
    SKILL: "自动化 Skill",
    MODEL: "模型"
  }[source] || source || "执行器";
}

function formatAutomationTime(value) {
  if (!value) return "";
  return new Date(value).toLocaleTimeString((globalThis.HoyeeLanguage?.locale || "zh-CN"), { hour12: false });
}

document.querySelector("#automation-run-dialog").addEventListener("close", stopAutomationRunPoll);

document.querySelector("#refresh-automations").addEventListener("click", loadAutomationCenter);
function setSkillConvertStatus(state, label, percent) {
  const panel = document.querySelector("#skill-convert-status");
  if (!panel) return;
  if (skillConvertStatusTimer) {
    clearTimeout(skillConvertStatusTimer);
    skillConvertStatusTimer = null;
  }
  panel.hidden = false;
  panel.classList.remove("is-closing");
  panel.classList.remove("uploading", "processing", "success", "error");
  if (state) panel.classList.add(state);
  document.querySelector("#skill-convert-status-label").textContent = label;
  const value = Math.max(0, Math.min(100, Math.round(Number(percent) || 0)));
  document.querySelector("#skill-convert-status-percent").textContent = String(value) + "%";
  document.querySelector("#skill-convert-progress-bar").style.width = String(value) + "%";
  if (state === "success") {
    skillConvertStatusTimer = setTimeout(() => {
      panel.classList.add("is-closing");
      skillConvertStatusTimer = setTimeout(() => {
        panel.hidden = true;
        panel.classList.remove("is-closing", "success");
        skillConvertStatusTimer = null;
      }, 180);
    }, 1600);
  }
}

function convertSkillWithProgress(form, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/v1/system/automation-skills/convert");
    xhr.withCredentials = true;
    xhr.timeout = 10 * 60 * 1000;
    xhr.upload.addEventListener("progress", event => {
      if (!event.lengthComputable) return;
      onProgress({
        stage: "upload",
        percent: Math.min(70, Math.round(event.loaded / event.total * 70))
      });
    });
    xhr.upload.addEventListener("load", () => {
      onProgress({ stage: "processing", percent: 75 });
    });
    xhr.onload = () => {
      let result = {};
      try {
        result = JSON.parse(xhr.responseText || "{}");
      } catch {
        reject(new Error("转换服务返回了无法识别的结果"));
        return;
      }
      if (xhr.status === 401) {
        currentUser = null;
        showLogin();
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress({ stage: "processing", percent: 92 });
        resolve(result);
      } else {
        reject(new Error(result.message || "转换失败，请稍后重试"));
      }
    };
    xhr.onerror = () => reject(new Error("文件上传失败，请检查网络连接"));
    xhr.ontimeout = () => reject(new Error("转换超时，请稍后重试"));
    xhr.send(form);
  });
}

function renderSkillUploadSelection() {
  const packageFiles = document.querySelector("#skill-package-file").files;
  const folderFiles = document.querySelector("#skill-folder-file").files;
  const selection = document.querySelector("#skill-upload-selection");
  const triggerLabel = document.querySelector("#skill-source-trigger-label");
  if (folderFiles.length) {
    triggerLabel.textContent = "已选文件夹 · " + folderFiles.length + " 个文件";
    selection.textContent = `已选择文件夹（${folderFiles.length} 个文件）`;
  } else if (packageFiles.length) {
    triggerLabel.textContent = packageFiles[0].name;
    selection.textContent = `已选择：${packageFiles[0].name}`;
  } else {
    triggerLabel.textContent = "选择文件";
    selection.textContent = "未选择文件";
  }
}
const skillSourceTrigger = document.querySelector("#skill-source-trigger");
const skillSourceMenu = document.querySelector("#skill-source-menu");
function closeSkillSourceMenu() {
  skillSourceMenu.hidden = true;
  skillSourceTrigger.setAttribute("aria-expanded", "false");
}
skillSourceTrigger.addEventListener("click", () => {
  const willOpen = skillSourceMenu.hidden;
  skillSourceMenu.hidden = !willOpen;
  skillSourceTrigger.setAttribute("aria-expanded", String(willOpen));
});
skillSourceMenu.addEventListener("click", event => {
  const option = event.target.closest("[data-skill-source]");
  if (!option) return;
  const packageInput = document.querySelector("#skill-package-file");
  const folderInput = document.querySelector("#skill-folder-file");
  closeSkillSourceMenu();
  if (option.dataset.skillSource === "folder") {
    packageInput.value = "";
    renderSkillUploadSelection();
    folderInput.click();
  } else {
    folderInput.value = "";
    renderSkillUploadSelection();
    packageInput.click();
  }
});
document.addEventListener("click", event => {
  if (!event.target.closest(".skill-source-picker")) closeSkillSourceMenu();
});
document.addEventListener("keydown", event => {
  if (event.key !== "Escape" || skillSourceMenu.hidden) return;
  closeSkillSourceMenu();
  skillSourceTrigger.focus();
});
document.querySelector("#skill-package-file").addEventListener("change", renderSkillUploadSelection);
document.querySelector("#skill-folder-file").addEventListener("change", renderSkillUploadSelection);
document.querySelector("#convert-skill-button").addEventListener("click", async () => {
  const packageInput = document.querySelector("#skill-package-file");
  const folderInput = document.querySelector("#skill-folder-file");
  const nameInput = document.querySelector("#skill-display-name");
  const convertButton = document.querySelector("#convert-skill-button");
  const packageFile = packageInput.files[0];
  const folderFiles = Array.from(folderInput.files || []);
  const skillName = nameInput.value.trim();
  if (!skillName) {
    nameInput.focus();
    return alert("请先填写技能名称");
  }
  if (!packageFile && !folderFiles.length) {
    return alert("请选择 ZIP、RAR、SKILL.md 或技能文件夹");
  }
  const form = new FormData();
  if (folderFiles.length) {
    folderFiles.forEach(file => form.append(
      "files", file, file.webkitRelativePath || file.name
    ));
  } else {
    form.append("file", packageFile);
  }
  form.append("name", skillName);
  convertButton.disabled = true;
  convertButton.textContent = "转换中…";
  setSkillConvertStatus("uploading", "正在上传文件…", 0);
  try {
    const result = await convertSkillWithProgress(form, progress => {
      if (progress.stage === "upload") {
        setSkillConvertStatus("uploading", "正在上传文件…", progress.percent);
      } else {
        setSkillConvertStatus("processing", "文件已上传，正在转换…", progress.percent);
      }
    });
    setSkillConvertStatus("success", "转换成功，已进入待审核", 100);
    alert(`${result.data.name} 已转换并进入待审核`);
    packageInput.value = "";
    folderInput.value = "";
    nameInput.value = "";
    renderSkillUploadSelection();
    await loadAutomationAdmin();
  } catch (error) {
    setSkillConvertStatus("error", "转换失败：" + error.message, 100);
    alert(error.message);
  } finally {
    convertButton.disabled = false;
    convertButton.textContent = "上传并转换";
  }
});

async function loadJobs() {
  const list = document.querySelector("#job-list");
  try {
    const [all, queued, running, review] = await Promise.all([
      api("/api/v1/workflow-jobs?page=0&size=8"),
      api("/api/v1/workflow-jobs?status=QUEUED&page=0&size=1"),
      api("/api/v1/workflow-jobs?status=RUNNING&page=0&size=1"),
      api("/api/v1/workflow-jobs?status=REVIEW_REQUIRED&page=0&size=1")
    ]);
    document.querySelector("#queued-count").textContent = queued.data.totalElements;
    document.querySelector("#running-count").textContent = running.data.totalElements;
    document.querySelector("#review-count").textContent = review.data.totalElements;
    const jobs = all.data.items || all.data.content || [];
    if (!jobs.length) {
      list.innerHTML = '<div class="empty-state"><strong>还没有任务</strong><span>创建第一条 AI 内容生产线，任务会出现在这里。</span></div>';
      return;
    }
    list.innerHTML = jobs.map(job => `
      <article class="job-row">
        <div class="job-icon">${job.type === "IMAGE_TO_VIDEO" ? "▶" : "◇"}</div>
        <div class="job-main"><strong translate="no">${escapeHtml(job.title)}</strong><span>${typeNames[job.type] || job.type} · ${escapeHtml(job.jobNo)}</span></div>
        <span class="job-status status-${job.status.toLowerCase()}">${statusNames[job.status] || job.status}</span>
        <time>${formatDate(job.createdAt)}</time>
      </article>
    `).join("");
  } catch (error) {
    list.innerHTML = `<div class="empty-state">${escapeHtml(error.message)}</div>`;
  }
}

function escapeHtml(value = "") {
  return String(value).replace(/[&<>"']/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;"
  })[char]);
}

function formatDate(value) {
  if (!value) return "—";
  return new Intl.DateTimeFormat((globalThis.HoyeeLanguage?.locale || "zh-CN"), {
    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
  }).format(new Date(value));
}

const PRODUCT_CAPTURE_API_ROOT = "/api/v1/product-captures";
const MARKETPLACE_SHOP_API_ROOT = "/api/v1/shops";
const PRODUCT_PUBLISH_WORKFLOW_STORAGE_PREFIX = "heyi:product-publish-workflow";
const PRODUCT_CAPTURE_PAGE_SIZE = 24;
const productCaptureStatusNames = {
  CAPTURED: "待整理",
  DRAFT: "待整理",
  IMAGE_PROCESSING: "图片处理中",
  PROCESSING: "处理中",
  READY: "待人工确认",
  REVIEW: "待人工确认",
  APPROVED: "可发布",
  PUBLISHED: "已提交平台",
  FAILED: "处理失败"
};

function productCaptureApi(path = "", options = {}) {
  return api(`${PRODUCT_CAPTURE_API_ROOT}${path}`, options);
}

function marketplaceShopApi(path = "", options = {}) {
  return api(`${MARKETPLACE_SHOP_API_ROOT}${path}`, options);
}

function productPublishWorkflowPath(captureId, workflowId = "") {
  const root = `${PRODUCT_CAPTURE_API_ROOT}/${encodeURIComponent(captureId)}/publish-workflows`;
  return workflowId ? `${root}/${encodeURIComponent(workflowId)}` : root;
}

function marketplacePlatformName(platform) {
  return String(platform || "").toUpperCase() === "SHOPEE" ? "Shopee" : "TikTok Shop";
}

function normalizedMarketplacePlatform(platform) {
  const normalized = String(platform || "").toUpperCase();
  return normalized === "TIKTOK" ? "TIKTOK_SHOP" : normalized;
}

function activeMarketplaceShops(platform = "") {
  const normalized = normalizedMarketplacePlatform(platform);
  return marketplaceShops.filter(shop => String(shop.status).toUpperCase() === "ACTIVE"
    && (!normalized || normalizedMarketplacePlatform(shop.platform) === normalized));
}

function marketplaceShopStatusMeta(status) {
  const normalized = String(status || "").toUpperCase();
  if (normalized === "ACTIVE") {
    return { bucket: "ACTIVE", label: "可发布", className: "is-active", rank: 0 };
  }
  if (normalized === "DISABLED" || normalized === "DISCONNECTED") {
    return { bucket: "DISABLED", label: "已断开", className: "is-disabled", rank: 2 };
  }
  const labels = {
    TOKEN_EXPIRED: "授权已过期",
    ERROR: "授权异常",
    PENDING: "授权处理中"
  };
  return {
    bucket: "ATTENTION",
    label: labels[normalized] || (normalized ? normalized.replaceAll("_", " ") : "状态未知"),
    className: "is-attention",
    rank: 1
  };
}

function formatMarketplaceShopTime(value, fallback = "未返回") {
  if (!value) return fallback;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return fallback;
  return new Intl.DateTimeFormat((globalThis.HoyeeLanguage?.locale || "zh-CN"), {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
  }).format(date);
}

function marketplaceShopExpiry(shop) {
  if (shop.authorizationExpiresAt) {
    return { label: "授权到期", value: shop.authorizationExpiresAt,
      text: formatMarketplaceShopTime(shop.authorizationExpiresAt) };
  }
  if (shop.refreshTokenExpiresAt) {
    return { label: "刷新凭证到期", value: shop.refreshTokenExpiresAt,
      text: formatMarketplaceShopTime(shop.refreshTokenExpiresAt) };
  }
  if (shop.accessTokenExpiresAt) {
    return { label: "访问 Token", value: shop.accessTokenExpiresAt,
      text: `${formatMarketplaceShopTime(shop.accessTokenExpiresAt)} 自动刷新` };
  }
  const value = shop.tokenExpiresAt || shop.expiresAt;
  return { label: "凭证有效期", value, text: formatMarketplaceShopTime(value, "平台未返回") };
}

function filteredMarketplaceShops() {
  return marketplaceShops.filter(shop => {
    const platform = normalizedMarketplacePlatform(shop.platform);
    const status = marketplaceShopStatusMeta(shop.status).bucket;
    return (marketplaceShopPlatformFilter === "ALL" || platform === marketplaceShopPlatformFilter)
      && (marketplaceShopStatusFilter === "ALL" || status === marketplaceShopStatusFilter);
  });
}

function renderMarketplaceShopCard(shop) {
  const shopId = String(shop.id || "");
  const platform = normalizedMarketplacePlatform(shop.platform);
  const status = marketplaceShopStatusMeta(shop.status);
  const expiry = marketplaceShopExpiry(shop);
  const isDisconnected = status.bucket === "DISABLED";
  const busy = marketplaceShopActionsInFlight.has(shopId);
  const canManage = hasAnyPermission("product:publish");
  const name = shop.name || shop.externalShopId || "未命名店铺";
  const type = ({LOCAL:'本土店',CROSS_BORDER:'跨境店',SELLER:'卖家店铺'})[shop.sellerType] || shop.sellerType || '店铺';
  const disabled = busy || !canManage ? 'disabled' : '';
  return `<article class="marketplace-shop-card ${status.className}" data-shop-id="${escapeHtml(shopId)}">
    <small class="shop-type-label">${escapeHtml(shop.region || '')} · ${escapeHtml(type)}</small>
    <strong class="shop-display-name" title="${escapeHtml(name)}">${escapeHtml(name)}</strong>
    <dl><div><dt>实际名称</dt><dd title="${escapeHtml(shop.actualName || shop.name || '')}">${escapeHtml(shop.actualName || shop.name || '未返回')}</dd></div>
    <div><dt>到期时间</dt><dd title="${escapeHtml(expiry.label)}">${escapeHtml(expiry.text)}</dd></div></dl>
    <footer><button type="button" data-rename-shop="${escapeHtml(shopId)}" ${disabled}>重命名</button>
    ${isDisconnected ? `<button type="button" data-authorize-shop="${escapeHtml(platform)}" ${disabled}>重新授权</button>` : `<button type="button" data-refresh-shop="${escapeHtml(shopId)}" ${disabled}>${busy ? '处理中…' : '刷新授权'}</button>`}
    <button type="button" data-delete-shop="${escapeHtml(shopId)}" ${disabled}>删除</button></footer></article>`;
}

function renderMarketplaceShopList() {
  const list = document.querySelector("#marketplace-shop-list");
  const visible = filteredMarketplaceShops().sort((left, right) => {
    const platformOrder = { SHOPEE: 0, TIKTOK_SHOP: 1 };
    const platformDifference = (platformOrder[normalizedMarketplacePlatform(left.platform)] ?? 9)
      - (platformOrder[normalizedMarketplacePlatform(right.platform)] ?? 9);
    if (platformDifference) return platformDifference;
    const statusDifference = marketplaceShopStatusMeta(left.status).rank
      - marketplaceShopStatusMeta(right.status).rank;
    if (statusDifference) return statusDifference;
    return String(left.name || left.externalShopId || "").localeCompare(
      String(right.name || right.externalShopId || ""), "zh-CN");
  });
  const organizationName = currentUser?.organizationName || "当前公司";
  const count = document.querySelector("#marketplace-shop-count");
  count.textContent = `${organizationName} · ${visible.length === marketplaceShops.length ? `${marketplaceShops.length} 家` : `${visible.length} / ${marketplaceShops.length} 家`}店铺`;
  document.querySelectorAll("[data-shop-platform-filter]").forEach(button => {
    const active = button.dataset.shopPlatformFilter === marketplaceShopPlatformFilter;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
  const statusSelect = document.querySelector("#marketplace-shop-status-filter");
  if (statusSelect) statusSelect.value = marketplaceShopStatusFilter;
  if (!visible.length) {
    list.innerHTML = `<div class="marketplace-shop-empty"><strong>${marketplaceShops.length ? "没有符合筛选的店铺" : "还没有授权店铺"}</strong><span>${marketplaceShops.length ? "切换平台或状态查看其他店铺。" : "可在下方继续授权多个 Shopee 或 TikTok Shop 店铺。"}</span></div>`;
    return;
  }
  const groups = new Map();
  visible.forEach(shop => {
    const platform = normalizedMarketplacePlatform(shop.platform);
    const status = marketplaceShopStatusMeta(shop.status);
    const key = `${platform}:${status.bucket}`;
    if (!groups.has(key)) groups.set(key, { platform, status, shops: [] });
    groups.get(key).shops.push(shop);
  });
  list.innerHTML = [...groups.values()].map(group => `
    <section class="marketplace-shop-group">
      <div class="marketplace-shop-group-heading">
        <strong>${escapeHtml(marketplacePlatformName(group.platform))}</strong>
        <span>${escapeHtml(group.status.bucket === "ATTENTION" ? "需处理" : group.status.label)} · ${group.shops.length}</span>
      </div>
      ${group.shops.map(renderMarketplaceShopCard).join("")}
    </section>`).join("");
}

function renderMarketplaceShops() {
  const state = document.querySelector("#marketplace-live-state");
  const note = document.querySelector("#marketplace-shop-note");
  const active = activeMarketplaceShops();
  const attention = marketplaceShops.filter(shop => marketplaceShopStatusMeta(shop.status).bucket === "ATTENTION");
  const live = marketplaceIntegrationStatus.livePublishingEnabled;
  state.textContent = live ? "官方发布已启用" : "安全演练模式";
  state.className = live ? "is-live" : "";
  const badge = document.querySelector("#product-publishing-mode-badge");
  if (badge) {
    badge.textContent = live ? "发布：官方接口已启用" : "发布：DRY RUN";
    badge.classList.toggle("is-live", live);
  }
  renderMarketplaceShopList();
  document.querySelectorAll("[data-authorize-shop]").forEach(button => {
    const configured = normalizedMarketplacePlatform(button.dataset.authorizeShop) === "SHOPEE"
      ? marketplaceIntegrationStatus.shopeeConfigured
      : marketplaceIntegrationStatus.tiktokShopConfigured;
    button.disabled = !configured || !hasAnyPermission("product:publish");
    button.title = configured ? (button.dataset.authorizationCopy === "true" ? "复制授权链接，在店铺对应网络环境中打开" : "打开平台官方授权页") : "管理员尚未配置该平台 App Key/Secret";
  });
  if (!marketplaceIntegrationStatus.shopeeConfigured && !marketplaceIntegrationStatus.tiktokShopConfigured) {
    note.textContent = "需先在服务端配置官方开发者应用；密钥不会发送到浏览器。";
  } else if (!marketplaceShops.length) {
    note.textContent = "同一公司可以继续授权多个店铺；回调完成后会自动同步到这里。";
  } else if (!marketplaceIntegrationStatus.livePublishingEnabled) {
    note.textContent = `当前保持 Dry Run；${active.length} 家可发布${attention.length ? `，${attention.length} 家授权需处理` : ""}。`;
  } else {
    note.textContent = `${active.length} 家店铺可用于官方发布${attention.length ? `，${attention.length} 家授权需处理` : ""}。`;
  }
  syncPublishShopOptions();
}

async function loadMarketplaceShops({ silent = false } = {}) {
  if (!currentUser || !hasAnyPermission("product:view", "product:publish")) return;
  const generation = ++marketplaceShopLoadGeneration;
  const organizationId = currentUser.organizationId || "";
  try {
    const [shopsResult, statusResult] = await Promise.all([
      marketplaceShopApi(),
      marketplaceShopApi("/integration-status")
    ]);
    if (generation !== marketplaceShopLoadGeneration
        || !currentUser || (currentUser.organizationId || "") !== organizationId) return;
    marketplaceShops = Array.isArray(shopsResult.data) ? shopsResult.data : [];
    marketplaceIntegrationStatus = { ...marketplaceIntegrationStatus, ...(statusResult.data || {}) };
    renderMarketplaceShops();
  } catch (error) {
    if (generation !== marketplaceShopLoadGeneration
        || !currentUser || (currentUser.organizationId || "") !== organizationId) return;
    if (error?.status === 401) return;
    document.querySelector("#marketplace-shop-list").innerHTML = `<span>${escapeHtml(error.message)}</span>`;
    document.querySelector("#marketplace-shop-count").textContent = `${currentUser?.organizationName || "当前公司"} · 读取失败`;
    if (!silent) document.querySelector("#marketplace-shop-note").textContent = "授权店铺读取失败，请刷新后重试。";
  }
}

function replaceMarketplaceShop(updatedShop) {
  if (!updatedShop?.id) return;
  const index = marketplaceShops.findIndex(shop => String(shop.id) === String(updatedShop.id));
  if (index < 0) marketplaceShops = [...marketplaceShops, updatedShop];
  else marketplaceShops = marketplaceShops.map((shop, position) => position === index ? updatedShop : shop);
}

async function refreshMarketplaceShop(shopId) {
  if (!shopId || !hasAnyPermission("product:publish") || marketplaceShopActionsInFlight.has(shopId)) return;
  const shop = marketplaceShops.find(candidate => String(candidate.id) === String(shopId));
  if (!shop) return;
  const organizationId = currentUser?.organizationId || "";
  marketplaceShopActionsInFlight.add(shopId);
  renderMarketplaceShops();
  let feedback = "";
  try {
    const result = await marketplaceShopApi(`/${encodeURIComponent(shopId)}/refresh`, { method: "POST" });
    if (!currentUser || (currentUser.organizationId || "") !== organizationId) return;
    replaceMarketplaceShop(result.data);
    feedback = `${shop.name || shop.externalShopId || "店铺"} 的授权已刷新，状态和同步时间已更新。`;
  } catch (error) {
    if (error?.status === 401) return;
    feedback = `刷新失败：${error.message}`;
  } finally {
    marketplaceShopActionsInFlight.delete(shopId);
    if (currentUser && (currentUser.organizationId || "") === organizationId) {
      renderMarketplaceShops();
      if (feedback) document.querySelector("#marketplace-shop-note").textContent = feedback;
    }
  }
}

async function editMarketplaceShop(shopId, remove = false) {
  if (!hasAnyPermission('product:publish') || marketplaceShopActionsInFlight.has(shopId)) return;
  const shop = marketplaceShops.find(item => String(item.id) === shopId);
  if (!shop) return;
  const organizationId = currentUser?.organizationId;
  let name;
  if (remove) {
    if (!window.confirm(`删除店铺“${shop.name}”？将移除本地连接和授权凭证，历史上传记录保留。`)) return;
  } else {
    name = window.prompt('设置店铺名称（最多 100 字）', shop.name || '');
    if (name === null) return;
    name = name.trim();
    if (!name || name.length > 100) { document.querySelector('#marketplace-shop-note').textContent = '名称需为 1–100 个字符'; return; }
  }
  marketplaceShopActionsInFlight.add(shopId);
  renderMarketplaceShops();
  let message;
  try {
    const result = await marketplaceShopApi(`/${encodeURIComponent(shopId)}`, {method:remove ? 'DELETE' : 'PATCH', ...(remove ? {} : {headers:{'Content-Type':'application/json'},body:JSON.stringify({name})})});
    if (currentUser?.organizationId !== organizationId) return;
    if (remove) marketplaceShops = marketplaceShops.filter(item => String(item.id) !== shopId);
    else replaceMarketplaceShop(result.data);
    message = remove ? '店铺已删除' : '店铺名称已保存';
  } catch(error) { message = error.message; }
  finally {
    marketplaceShopActionsInFlight.delete(shopId);
    if (currentUser?.organizationId === organizationId) { renderMarketplaceShops(); document.querySelector('#marketplace-shop-note').textContent = message || ''; }
  }
}

async function disconnectMarketplaceShop(shopId) {
  if (!shopId || !hasAnyPermission("product:publish") || marketplaceShopActionsInFlight.has(shopId)) return;
  const shop = marketplaceShops.find(candidate => String(candidate.id) === String(shopId));
  if (!shop) return;
  const organizationId = currentUser?.organizationId || "";
  const shopName = shop.name || shop.externalShopId || "未命名店铺";
  const externalId = shop.externalShopId || "未返回";
  const confirmed = window.confirm(
    `确认断开 ${marketplacePlatformName(shop.platform)} 店铺“${shopName}”吗？\n\n外部 ID：${externalId}\n断开后服务端会删除该店铺凭证，不能再向它发布商品；恢复使用需要重新走官方授权。`
  );
  if (!confirmed) return;
  marketplaceShopActionsInFlight.add(shopId);
  renderMarketplaceShops();
  let feedback = "";
  try {
    const result = await marketplaceShopApi(`/${encodeURIComponent(shopId)}/disconnect`, { method: "POST" });
    if (!currentUser || (currentUser.organizationId || "") !== organizationId) return;
    replaceMarketplaceShop(result.data);
    feedback = `已断开“${shopName}”；该店铺已从可发布目标中移除。`;
  } catch (error) {
    if (error?.status === 401) return;
    feedback = `断开失败：${error.message}`;
  } finally {
    marketplaceShopActionsInFlight.delete(shopId);
    if (currentUser && (currentUser.organizationId || "") === organizationId) {
      renderMarketplaceShops();
      if (feedback) document.querySelector("#marketplace-shop-note").textContent = feedback;
    }
  }
}

async function reloadMarketplaceShops(button) {
  if (button.disabled) return;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = "读取中…";
  await loadMarketplaceShops();
  button.disabled = false;
  button.textContent = original;
}

async function startMarketplaceAuthorization(platform, button) {
  if (button.disabled || !hasAnyPermission("product:publish")) return;
  const copyOnly = button.dataset.authorizationCopy === "true";
  const original = button.textContent;
  button.disabled = true;
  button.textContent = copyOnly ? "正在生成链接…" : "正在打开…";
  try {
    rememberProductCaptureRoute(true);
    const result = await api(`/api/v1/shop-authorizations/${encodeURIComponent(platform)}/start`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ returnPath: "/?view=shops",
        ...(platform === "TIKTOK_SHOP" ? {market: button.dataset.authorizationMarket
          || document.querySelector("#tiktok-authorization-market")?.value || "ROW"} : {}) })
    });
    const url = safeProductUrl(result.data?.authorizationUrl);
    if (!url) throw new ApiRequestError("平台没有返回有效的官方授权地址", 502, result);
    if (copyOnly) {
      const copied = await copyTextToClipboard(url);
      const note = document.querySelector("#marketplace-shop-note");
      note.textContent = copied ? "授权链接已复制。请到对应的网络环境店铺进行授权" : "未能自动复制，请复制下方链接。请到对应的网络环境店铺进行授权";
      const expiresAt = new Date(result.data?.expiresAt);
      if (Number.isFinite(expiresAt.getTime())) {
        note.textContent += `。有效至 ${expiresAt.toLocaleString("zh-CN", {
          month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false
        })}，过期后请重新复制授权链接。`;
      }
      if (!copied) {
        const input = document.createElement("input");
        input.type = "text"; input.readOnly = true; input.value = url;
        input.setAttribute("aria-label", "店铺授权链接");input.style.width = "100%";
        note.append(input);input.focus();input.select();
      }
    } else window.location.assign(url);
  } catch (error) {
    document.querySelector("#marketplace-shop-note").textContent = error.message;
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

function consumeMarketplaceAuthorizationReturn() {
  const params = new URLSearchParams(window.location.search);
  const state = params.get("shopAuth");
  if (!state) return;
  const platform = marketplacePlatformName(params.get("platform"));
  const note = document.querySelector("#marketplace-shop-note");
  note.textContent = state === "success"
    ? `${platform} 官方授权成功，已同步 ${params.get("connected") || "1"} 家店铺。`
    : `${platform} 授权失败：${params.get("message") || "请重新发起授权"}`;
  ["shopAuth", "platform", "connected", "message", "view"].forEach(key => params.delete(key));
  const query = params.toString();
  window.history.replaceState(null, document.title,
    `${window.location.pathname}${query ? `?${query}` : ""}#shops`);
  switchWorkspace("shops");
}

function productCapturePayload(value) {
  if (!value || typeof value !== "object") return null;
  if (value.productCapture) return productCapturePayload(value.productCapture);
  if (!value.capture) return value;
  const detailFields = ["skus", "images", "fieldSources", "normalizedData", "rawData"];
  const isCaptureDetail = detailFields.some(field => Object.prototype.hasOwnProperty.call(value, field));
  if (!isCaptureDetail) return productCapturePayload(value.capture);
  const entity = productCapturePayload(value.capture) || {};
  const { capture: _nestedCapture, ...detail } = value;
  return {
    ...entity,
    ...detail,
    skus: Array.isArray(value.skus) ? value.skus : productCaptureSkus(entity),
    images: Array.isArray(value.images) ? value.images : productCaptureImages(entity),
    fieldSources: Array.isArray(value.fieldSources) ? value.fieldSources : entity.fieldSources || [],
    normalizedData: value.normalizedData ?? entity.normalizedData ?? {},
    rawData: value.rawData ?? entity.rawData ?? {}
  };
}

function productCaptureListPayload(value) {
  if (Array.isArray(value)) return value;
  if (!value || typeof value !== "object") return [];
  return value.items || value.captures || value.content || [];
}

function productCaptureCountValue(value, fallback = 0) {
  const count = Number(value);
  return Number.isFinite(count) && count >= 0 ? Math.trunc(count) : fallback;
}

function productCapturePagePayload(value, requestedPage = 0) {
  const objectValue = value && typeof value === "object" && !Array.isArray(value) ? value : null;
  const paged = Boolean(objectValue && (
    Object.prototype.hasOwnProperty.call(objectValue, "totalElements")
    || Object.prototype.hasOwnProperty.call(objectValue, "totalPages")
    || Object.prototype.hasOwnProperty.call(objectValue, "statusCounts")
  ));
  const content = productCaptureListPayload(value);
  const page = paged ? productCaptureCountValue(objectValue.page, requestedPage) : 0;
  const size = paged ? productCaptureCountValue(objectValue.size, PRODUCT_CAPTURE_PAGE_SIZE) : content.length;
  const totalElements = paged
    ? productCaptureCountValue(objectValue.totalElements, content.length)
    : content.length;
  const totalPagesFallback = totalElements
    ? Math.ceil(totalElements / Math.max(1, size || PRODUCT_CAPTURE_PAGE_SIZE))
    : 0;
  const totalPages = paged
    ? productCaptureCountValue(objectValue.totalPages, totalPagesFallback)
    : (content.length ? 1 : 0);
  return {
    content,
    page,
    size,
    totalElements,
    totalPages,
    statusCounts: objectValue?.statusCounts,
    paged
  };
}

function resetProductCaptureDefaultFilter() {
  productCaptureStatusFilter = "CAPTURED";
  if (productCaptureSearchDebounceTimer) clearTimeout(productCaptureSearchDebounceTimer);
  productCaptureSearchDebounceTimer = null;
  document.querySelectorAll("#product-status-filter [data-product-status]").forEach(button => {
    const active = button.dataset.productStatus === productCaptureStatusFilter;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
}

function productCaptureListPath(page = 0) {
  const parameters = new URLSearchParams({
    page: String(Math.max(0, page)),
    size: String(PRODUCT_CAPTURE_PAGE_SIZE),
    status: productCaptureStatusFilter,
    query: productCaptureSearchQuery.trim()
  });
  return `?${parameters.toString()}`;
}

function productCaptureStatusGroup(status = "") {
  const normalized = String(status).toUpperCase();
  if (["CAPTURED", "DRAFT", "RECEIVED"].includes(normalized)) return "DRAFT";
  if (["IMAGE_PROCESSING", "PROCESSING", "QUEUED", "RUNNING", "PUBLISHING"].includes(normalized)) return "PROCESSING";
  if (["READY", "REVIEW_REQUIRED"].includes(normalized)) return "REVIEW";
  if (normalized === "APPROVED") return "APPROVED";
  if (normalized === "PUBLISHED") return "PUBLISHED";
  if (["FAILED", "REJECTED"].includes(normalized)) return "FAILED";
  return "DRAFT";
}

function emptyProductCaptureStatusCounts() {
  return { DRAFT: 0, PROCESSING: 0, REVIEW: 0, APPROVED: 0, PUBLISHED: 0, FAILED: 0 };
}

function normalizeProductCaptureStatusCounts(value) {
  const counts = emptyProductCaptureStatusCounts();
  if (!value || typeof value !== "object" || Array.isArray(value)) return counts;
  Object.entries(value).forEach(([status, rawCount]) => {
    const count = Number(rawCount);
    if (!Number.isFinite(count) || count < 0) return;
    counts[productCaptureStatusGroup(status)] += Math.trunc(count);
  });
  return counts;
}

function deriveProductCaptureStatusCounts(captures = productCaptures) {
  const counts = emptyProductCaptureStatusCounts();
  captures.forEach(capture => {
    counts[productCaptureStatusGroup(capture?.status)] += 1;
  });
  return counts;
}

function productCaptureStatusName(status) {
  return productCaptureStatusNames[status] || status || "待整理";
}

function productCaptureDisplayStatus(capture = {}) {
  const status = String(capture.status || "CAPTURED").toUpperCase();
  const hasCompletenessEvidence = Object.prototype.hasOwnProperty.call(capture, "skuCompleteness")
    || Object.prototype.hasOwnProperty.call(capture, "normalizedData")
    || Object.prototype.hasOwnProperty.call(capture, "missingFields");
  if (!hasCompletenessEvidence) return productCaptureStatusName(status);
  const skuCompleteness = productSkuCompletenessMeta(capture);
  if (skuCompleteness.incomplete && ["CAPTURED", "READY"].includes(status)) return "SKU 待补采";
  if (!skuCompleteness.known && ["CAPTURED", "READY"].includes(status)) return "SKU 待确认";
  if (capture.completeness === "NEEDS_ENRICHMENT"
      && productBlockingMissingFields(capture, skuCompleteness).length > 0
      && ["CAPTURED", "READY"].includes(status)) return "待补全";
  return productCaptureStatusName(status);
}

const productLiveImageProgress = new Map();
function productLiveImageStage(image) {
  return productLiveImageProgress.get(selectedProductCaptureId)?.find(row => row.imageId === image.id);
}
function productImageProgressTime(row) {
  const timestamp = String(row?.updatedAt || "");
  return new Date(/[zZ]$|[+-]\d{2}:\d{2}$/.test(timestamp) ? timestamp : `${timestamp}Z`).getTime();
}
function productImageIsActivelyProcessing(image) {
  const row = productLiveImageStage(image);
  return row && !(String(image.status).toUpperCase() === "FAILED" && new Date(image.updatedAt).getTime() >= productImageProgressTime(row))
    && ["RUNNING", "DETECTING", "EDITING", "RESULT_RECEIVED", "EDITED"].includes(row.status)
    && Date.now() - productImageProgressTime(row) < 20 * 60 * 1000;
}
let productLiveProgressBusy = false;
async function refreshProductLiveImageProgress() {
  const id = selectedProductCaptureId;
  const org = currentUser?.organizationId;
  if (productLiveProgressBusy || !id || !org || document.hidden || !document.querySelector("#product-publish-page").classList.contains("capture-detail-view") || document.querySelector("#product-publish-page").hidden) return;
  productLiveProgressBusy = true;
  try {
    const response = await productCaptureApi(`/${encodeURIComponent(id)}/image-progress`);
    if (selectedProductCaptureId !== id || currentUser?.organizationId !== org) return;
    productLiveImageProgress.set(id, Array.isArray(response.data) ? response.data : []);
    if (selectedProductCaptureDetail?.id === id && !document.querySelector("#asset-preview-dialog").open) renderProductImages(selectedProductCaptureDetail);
  } catch (_) { /* Keep the last known image state if progress refresh is unavailable. */ }
  finally { productLiveProgressBusy = false; }
}
window.setInterval(refreshProductLiveImageProgress, 3000);

function productImageState(image = {}) {
  const live = productLiveImageStage(image);
  if (live?.status === "QUEUED") return "waiting";
  if (live?.status === "FAILED") return "failed";
  if (productImageIsActivelyProcessing(image)) return "processing";
  const status = String(image.status || "").toUpperCase();
  if (["FAILED", "ERROR"].includes(status)) return "failed";
  if (["PROCESSING", "RUNNING"].includes(status)) return "processing";
  if (["QUEUED", "PENDING"].includes(status)) return "waiting";
  if (["COMPLETED", "PROCESSED", "READY", "SUCCEEDED", "SUCCESS"].includes(status)) return "completed";
  if (image.processedUrl && !image.errorMessage) return "completed";
  return "waiting";
}

function productImageStateName(image = {}) {
  const state = productImageState(image);
  if (state === "completed") return "去水印完成";
  if (state === "processing") return productLiveImageStage(image)?.status === "DETECTING" ? "水印检测中" : "AI 去水印中";
  if (state === "failed") return "处理失败";
  const retry = productLiveImageStage(image)?.message?.match(/自动重试\s+\d+\/\d+/);
  if (retry) return retry[0];
  return productLiveImageStage(image)?.message?.includes("并发") ? "接口限流，自动排队" : "排队中";
}

function productImageIsDryRun(image = {}, capture = selectedProductCaptureDetail) {
  const meta = productImageProcessingMeta.get(capture?.id) || {};
  const provider = image.processingProvider || image.provider
    || capture?.imageProcessingProvider || capture?.watermarkProvider || meta.provider || "";
  const original = safeProductUrl(image.originalUrl || image.url);
  const processed = safeProductUrl(image.processedUrl);
  return image.dryRun === true
    || capture?.imageProcessingDryRun === true
    || meta.dryRun === true
    || /dry.?run/i.test(String(provider))
    || Boolean(original && processed && original === processed);
}

function productImageDisplayState(image = {}, capture = selectedProductCaptureDetail) {
  if (productImageState(image) === "completed" && productImageIsDryRun(image, capture)) {
    return "Dry Run 占位图（未实际去水印）";
  }
  return productImageStateName(image);
}

function safeProductUrl(value) {
  if (!value) return "";
  try {
    const url = new URL(String(value), window.location.origin);
    return ["http:", "https:"].includes(url.protocol) ? url.href : "";
  } catch (_error) {
    return "";
  }
}

function productSourcePlatformName(value) {
  const key = String(value || "").toUpperCase();
  return ({ SHOPEE: "Shopee", TIKTOK: "TikTok Shop", TIKTOK_SHOP: "TikTok Shop", LAZADA: "Lazada" })[key]
    || value || "来源平台";
}

function productCaptureImages(capture = selectedProductCaptureDetail) {
  return Array.isArray(capture?.images) ? capture.images : [];
}

function productCaptureSkus(capture = selectedProductCaptureDetail) {
  return Array.isArray(capture?.skus) ? capture.skus : [];
}

function canonicalProductSkuImageSource(value) {
  const source = String(value || "").trim().toUpperCase();
  return source === "PRODUCT_MAIN_PAGE" ? "PRODUCT_MAIN" : source;
}

function productSkuEntrySource(sku = {}) {
  const source = sku.entrySource || sku.sourceType || sku.origin
    || sku.source?.entrySource || sku.source?.sourceType || sku.source?.origin || "";
  if (/user.?edited/i.test(String(source))) return "USER_EDITED";
  return /manual|user/i.test(String(source)) ? "MANUAL" : "PLATFORM";
}

function productSkuCompletenessMeta(capture = selectedProductCaptureDetail) {
  const normalized = capture?.normalizedData && typeof capture.normalizedData === "object"
    ? capture.normalizedData : {};
  const value = capture?.skuCompleteness ?? normalized.skuCompleteness;
  const raw = value && typeof value === "object" && !Array.isArray(value) ? value : {};
  const skus = productCaptureSkus(capture);
  const count = input => {
    if (input === null || input === undefined || input === "") return null;
    const number = Number(input);
    return Number.isFinite(number) && number >= 0 ? Math.trunc(number) : null;
  };
  const declaredExpectedCount = count(raw.expectedCount);
  // Marketplace fallbacks use 0 to mean that no authoritative SKU count was
  // available. Treat it as unknown so an editor can replace the missing matrix
  // and explicitly confirm the complete USER-authored listing.
  const expectedCount = declaredExpectedCount && declaredExpectedCount > 0
    ? declaredExpectedCount : null;
  const variantDimensionCount = count(raw.variantDimensionCount);
  const hasVariants = typeof raw.hasVariants === "boolean"
    ? raw.hasVariants : (variantDimensionCount || 0) > 0;
  const capturedCount = count(raw.capturedCount)
    ?? skus.filter(sku => productSkuEntrySource(sku) !== "MANUAL").length;
  const missingFields = Array.isArray(capture?.missingFields)
    ? capture.missingFields.map(field => String(field).toUpperCase()) : [];
  const locallyDirty = productSkuCompletenessDirty && capture?.id === selectedProductCaptureId;
  const countsMatch = expectedCount !== null
    && expectedCount === capturedCount
    && expectedCount === skus.length;
  const rawIssues = Array.isArray(raw.issues) ? raw.issues.filter(Boolean) : [];
  const inventoryIssues = rawIssues.filter(productSkuIssueIsOptionalInventory).slice(0, 80);
  const issues = rawIssues.filter(issue => !productSkuIssueIsOptionalInventory(issue)).slice(0, 80);
  // Inventory is optional. Legacy captures can still declare complete=false and
  // SKU_INCOMPLETE when their only parser findings are missing stock values.
  const onlyOptionalInventoryIssues = rawIssues.length > 0
    && rawIssues.every(productSkuIssueIsOptionalInventory);
  const stockOnlyDeclarationFailure = raw.complete === false && onlyOptionalInventoryIssues;
  const explicitlyIncomplete = (missingFields.includes("SKU_INCOMPLETE") && !onlyOptionalInventoryIssues)
    || (raw.complete === false && !stockOnlyDeclarationFailure);
  const completeDeclaration = raw.complete === true || stockOnlyDeclarationFailure;
  const complete = completeDeclaration && countsMatch && issues.length === 0
    && !explicitlyIncomplete && !locallyDirty;
  const known = Object.keys(raw).length > 0 || missingFields.includes("SKU_INCOMPLETE");
  const incomplete = !complete && (known || locallyDirty);
  const source = String(raw.source || "UNKNOWN").trim().toUpperCase() || "UNKNOWN";
  return {
    raw,
    expectedCount,
    variantDimensionCount,
    hasVariants,
    capturedCount,
    actualCount: skus.length,
    complete,
    incomplete,
    known,
    source,
    issues,
    inventoryIssues,
    inventoryMissingCount: skus.filter(sku => sku.stock === null || sku.stock === undefined || sku.stock === "").length,
    userConfirmed: /USER|MANUAL/.test(source),
    locallyDirty
  };
}

function productSkuCompletenessSourceLabel(source) {
  const normalized = String(source || "UNKNOWN").toUpperCase();
  if (/USER|MANUAL/.test(normalized)) return "人工核对（USER）";
  if (/DOM|PAGE|PLATFORM|SHOPEE|TIKTOK/.test(normalized)) return "商品详情页直采";
  if (/API|REEF|TMAPI/.test(normalized)) return `数据接口（${source}）`;
  return normalized === "UNKNOWN" ? "等待平台返回" : source;
}

function productSkuCompletenessIssueText(issue) {
  if (issue && typeof issue === "object") {
    if (issue.message) return String(issue.message);
    issue = issue.code || issue.type || JSON.stringify(issue);
  }
  const code = String(issue || "").toUpperCase();
  const exact = {
    AUTHORITATIVE_SKU_DATA_UNAVAILABLE: "未读取到平台权威 SKU 数据",
    MODELS_MISSING: "页面数据中缺少 Shopee 规格模型",
    SKUS_MISSING: "页面数据中缺少 SKU 列表",
    NO_REAL_SKU_MODELS: "未发现真实 Shopee SKU 模型",
    NO_REAL_SKUS: "未发现真实 SKU",
    SKU_LIMIT_EXCEEDED: "SKU 超过单商品 500 条安全上限",
    SKU_COUNT_MISMATCH: "已采集数量与平台应有数量不一致",
    DUPLICATE_MODEL_ID: "存在重复的平台 SKU ID",
    IMAGE_LIMIT_EXCEEDED: "刊登素材超过 30 张上限，已按主图、规格图、图库、详情图顺序保留",
    VARIANT_IMAGE_COVERAGE_TRUNCATED: "30 张上限无法容纳全部规格代表图，禁止审核",
    GALLERY_IMAGE_COVERAGE_TRUNCATED: "30 张上限导致部分商品图库未纳入",
    DETAIL_IMAGE_COVERAGE_TRUNCATED: "30 张上限导致部分详情图未纳入"
  };
  if (exact[code]) return exact[code];
  const match = code.match(/^SKU_(\d+)_(.+)$/);
  if (match) {
    const reasons = {
      INVALID: "数据无效",
      MODEL_ID_MISSING: "缺少平台型号 ID",
      ID_MISSING: "缺少平台 SKU ID",
      ATTRIBUTES_MISSING: "规格属性维度不完整",
      PRICE_MISSING: "缺少售价",
      ORIGINAL_PRICE_MISSING: "缺少原价",
      ORIGINAL_PRICE_INVALID: "原价低于售价",
      STOCK_MISSING: "缺少库存",
      IMAGE_MISSING: "缺少 SKU 图片或可继承图片"
    };
    return `SKU ${match[1]}：${reasons[match[2]] || match[2]}`;
  }
  return code || "SKU 完整性未通过";
}

function productSkuIssueCode(issue) {
  if (issue && typeof issue === "object") return String(issue.code || issue.type || "").toUpperCase();
  return String(issue || "").toUpperCase();
}

function productSkuIssueIsOptionalInventory(issue) {
  const code = productSkuIssueCode(issue);
  return code === "STOCK_MISSING"
    || /^SKU_\d+_(?:STOCK|INVENTORY)_MISSING$/.test(code);
}

function productMissingFieldIsOptionalInventory(field) {
  return ["STOCK", "SKU_STOCK", "SKU_INVENTORY"].includes(String(field || "").toUpperCase());
}

function productBlockingMissingFields(capture, skuCompleteness = productSkuCompletenessMeta(capture)) {
  const fields = Array.isArray(capture?.missingFields) ? capture.missingFields : [];
  return fields
    .map(field => String(field).toUpperCase())
    .filter(field => !productMissingFieldIsOptionalInventory(field))
    .filter(field => field !== "SKU_INCOMPLETE" || !skuCompleteness.complete);
}

function productSkuIssueIsRepairable(issue) {
  const code = productSkuIssueCode(issue);
  if (/^SKU_\d+_(INVALID|MODEL_ID_MISSING|ID_MISSING|ATTRIBUTES_MISSING|PRICE_MISSING|ORIGINAL_PRICE_MISSING|ORIGINAL_PRICE_INVALID|STOCK_MISSING|IMAGE_MISSING)$/.test(code)) {
    return true;
  }
  return [
    "AUTHORITATIVE_SKU_DATA_UNAVAILABLE",
    "MODELS_MISSING",
    "SKUS_MISSING",
    "NO_REAL_SKU_MODELS",
    "NO_REAL_SKUS",
    "SKU_COUNT_MISMATCH",
    "DUPLICATE_MODEL_ID"
  ].includes(code);
}

function productSkuNonRepairableIssues(issues = []) {
  return issues.filter(issue => !productSkuIssueIsRepairable(issue));
}

function productCaptureSearchText(capture) {
  return [
    capture.id, capture.title, capture.sourceProductId, capture.sourceShopId,
    capture.sourcePlatform, capture.categoryName, capture.brandName,
    ...productCaptureSkus(capture).flatMap(sku => [sku.externalSkuId, sku.sellerSku, sku.variationName])
  ].filter(Boolean).join(" ").toLowerCase();
}

function filteredProductCaptures() {
  if (!productCaptureLegacyListResponse) return productCaptures;
  const query = productCaptureSearchQuery.trim().toLowerCase();
  return productCaptures.filter(capture => {
    const statusMatches = productCaptureStatusFilter === "ALL"
      || productCaptureStatusGroup(capture.status) === productCaptureStatusGroup(productCaptureStatusFilter);
    return statusMatches && (!query || productCaptureSearchText(capture).includes(query));
  });
}

function productPriceLabel(capture) {
  const min = capture?.priceMin;
  const max = capture?.priceMax;
  if (min === null || min === undefined || min === "") return "价格待核对";
  const currency = capture.currency || "";
  return max !== null && max !== undefined && max !== "" && String(max) !== String(min)
    ? `${currency} ${min}–${max}`.trim()
    : `${currency} ${min}`.trim();
}

function renderProductPageIdentity() {
  document.querySelector("#product-company-context").textContent = currentUser
    ? `${currentUser.organizationName || "当前公司"}${currentUser.organizationCode ? ` · ${currentUser.organizationCode}` : ""}`
    : "尚未登录";
  document.querySelector("#product-permission-context").textContent = productPermissionLabel();
}

function renderProductSummary() {
  const counts = productCaptureStatusCountsKnown
    ? productCaptureStatusCounts
    : deriveProductCaptureStatusCounts();
  const total = Object.values(counts).reduce((sum, count) => sum + count, 0);
  document.querySelector("#product-total-count").textContent = total;
  document.querySelector("#product-processing-count").textContent = counts.PROCESSING;
  document.querySelector("#product-review-count").textContent = counts.REVIEW;
  document.querySelector("#product-approved-count").textContent = counts.APPROVED;
  document.querySelector("#product-published-count").textContent = counts.PUBLISHED;
}

function syncProductCaptureView() {
  const id = new URLSearchParams(window.location.hash.split("?")[1] || "").get("id");
  document.querySelector("#product-publish-page").classList.toggle("capture-detail-view", Boolean(id));
  return id;
}

function syncProductCapturePagination(message = productCapturePaginationMessage) {
  const total = productCaptureLegacyListResponse ? filteredProductCaptures().length : productCaptureTotalElements;
  const pages = Math.max(1, productCaptureTotalPages);
  const current = Math.max(0, productCapturePage);
  document.querySelector("#product-result-count").textContent = `共 ${total} 件商品 · 每页 ${PRODUCT_CAPTURE_PAGE_SIZE} 件`;
  document.querySelector("#product-capture-pagination").hidden = false;
  const prev = document.querySelector("#previous-product-captures");
  const next = document.querySelector("#load-more-product-captures");
  prev.disabled = productCaptureListLoading || current === 0;
  next.disabled = productCaptureListLoading || current + 1 >= pages;
  next.hidden = false;
  next.textContent = "下一页 →";
  document.querySelector("#product-page-numbers").innerHTML = Array.from({length: pages}, (_, i) => i)
    .filter(i => i === 0 || i === pages - 1 || Math.abs(i - current) <= 2)
    .map((i, n, all) => `${n && i - all[n - 1] > 1 ? '<span>…</span>' : ''}<button type="button" data-capture-page="${i}" ${i === current ? 'aria-current="page"' : ''} ${productCaptureListLoading ? 'disabled' : ''}>${i + 1}</button>`).join("");
  document.querySelector("#product-load-more-status").textContent = message;
}

function productCaptureCanUpload(capture) {
  return hasAnyPermission("product:publish")
    && ["CAPTURED", "DRAFT", "IMAGE_PROCESSING", "READY", "APPROVED", "FAILED", "PUBLISHED"].includes(capture?.status);
}

function productCaptureOrigin(capture) {
  if (capture?.originType === "COMMERCE_GENERATED") return {label: "裂变商品", tone: "generated"};
  if (capture?.originType === "CAPTURED") return {label: "原始采集", tone: "captured"};
  return {label: "来源待确认", tone: "unknown"};
}

function syncProductBulkSelection() {
  const checkboxes = [...document.querySelectorAll("#product-capture-list [data-product-bulk-id]:not(:disabled)")];
  const allSelected = checkboxes.length > 0
    && checkboxes.every(checkbox => productBulkSelectedIds.has(checkbox.dataset.productBulkId));
  document.querySelector("#product-bulk-count").textContent = `已勾选 ${productBulkSelectedIds.size} 件`;
  document.querySelector("#open-product-bulk-upload").disabled = !productBulkSelectedIds.size;
  const toggle = document.querySelector("#toggle-product-bulk-selection");
  toggle.disabled = productCaptureListLoading || !checkboxes.length || !hasAnyPermission("product:publish");
  toggle.textContent = allSelected ? "取消全选" : "全部勾选";
  toggle.title = allSelected ? "取消当前页全部商品的勾选" : "勾选当前页全部可上传商品";
  toggle.setAttribute("aria-pressed", String(allSelected));
}

function toggleProductBulkPageSelection() {
  if (productCaptureListLoading || !hasAnyPermission("product:publish")) return;
  const checkboxes = [...document.querySelectorAll("#product-capture-list [data-product-bulk-id]:not(:disabled)")];
  const selectAll = !checkboxes.every(checkbox => productBulkSelectedIds.has(checkbox.dataset.productBulkId));
  for (const checkbox of checkboxes) {
    const id = checkbox.dataset.productBulkId;
    const capture = productCaptures.find(item => item.id === id);
    if (!productCaptureCanUpload(capture)) continue;
    if (selectAll) {
      productBulkSelectedIds.add(id);
      productBulkSelectedCaptures.set(id, capture);
    } else {
      productBulkSelectedIds.delete(id);
      productBulkSelectedCaptures.delete(id);
    }
    checkbox.checked = selectAll;
  }
  syncProductBulkSelection();
}

function renderProductCaptureList() {
  const list = document.querySelector("#product-capture-list");
  const filtered = filteredProductCaptures();
  syncProductCapturePagination();
  if (!filtered.length) {
    const hasCriteria = productCaptureStatusFilter !== "ALL" || Boolean(productCaptureSearchQuery.trim());
    IncrementalDOM.html(list, hasCriteria
      ? `<div class="product-empty"><strong>没有匹配商品</strong><span>调整状态筛选或搜索关键词。</span></div>`
      : `<div class="product-empty"><strong>还没有采集商品</strong><span>安装并登录浏览器插件，在商品详情页点击“采集商品”。</span></div>`);
    syncProductBulkSelection();
    return;
  }
  IncrementalDOM.html(list, filtered.map(capture => {
    const group = productCaptureStatusGroup(capture.status).toLowerCase();
    const title = capture.title || `商品 ${capture.sourceProductId || capture.id}`;
    const image = productCaptureImages(capture)[0];
    const thumb = safeProductUrl(capture.thumbnailUrl || image?.processedUrl || image?.originalUrl);
    const rawThumbnailStatus = String(capture.thumbnailStatus || "").toUpperCase();
    const thumbnailStatus = ["PROCESSING", "QUEUED", "FAILED"].includes(rawThumbnailStatus)
      ? rawThumbnailStatus.toLowerCase() : "";
    const selected = capture.id === selectedProductCaptureId;
    const selectable = productCaptureCanUpload(capture);
    const origin = productCaptureOrigin(capture);
    return `<div class="product-selectable-row" data-update-key="${escapeHtml(capture.id)}"><input type="checkbox" data-product-bulk-id="${escapeHtml(capture.id)}" aria-label="勾选 ${escapeHtml(title)}" ${productBulkSelectedIds.has(capture.id) ? "checked" : ""} ${selectable ? "" : "disabled"}><button class="product-capture-row ${selected ? "active" : ""}" type="button" data-product-capture-id="${escapeHtml(capture.id)}"${selected ? ` aria-current="true"` : ""}>
      <span class="product-row-thumb ${escapeHtml(thumbnailStatus)}">${thumb ? `<span class="capture-thumbnail-placeholder">商品图片加载中</span><img src="${escapeHtml(thumb)}" alt="" loading="lazy" onload="this.previousElementSibling.hidden=true" onerror="this.hidden=true;this.previousElementSibling.textContent='图片暂不可用'">` : `<span class="capture-thumbnail-placeholder">暂无商品图片</span>`}</span>
      <span class="product-origin-badge ${origin.tone}">${origin.label}</span>
      <span class="product-row-copy"><strong translate="no">${escapeHtml(title)}</strong><small>${escapeHtml(productSourcePlatformName(capture.sourcePlatform))} · ${escapeHtml(productPriceLabel(capture))}</small><em>${formatDate(capture.updatedAt || capture.createdAt)}</em></span>
      <span class="product-row-status ${group}">${escapeHtml(productCaptureDisplayStatus(capture))}</span>
    </button><div class="capture-card-actions">${selectable ? `<button type="button" class="capture-upload-button" data-upload-capture="${escapeHtml(capture.id)}">${capture.status === "PUBLISHED" ? "选择店铺再次上传" : "选择店铺上传"}</button>` : ""}${hasAnyPermission("product:edit") ? `<button type="button" class="capture-delete-button" data-delete-capture="${escapeHtml(capture.id)}" aria-label="删除 ${escapeHtml(title)}">删除</button>` : ""}</div></div>`;
  }).join(""));
  list.querySelectorAll(".product-row-thumb img").forEach(img => {
    if (img.complete && img.naturalWidth > 0) img.previousElementSibling.hidden = true;
  });
  syncProductBulkSelection();
}

function renderProductCaptureEmpty(title = "选择一条商品草稿", note = "插件完成采集后，商品资料、SKU 和图片处理结果会显示在这里。", loading = false) {
  const empty = document.querySelector("#product-capture-empty");
  document.querySelector("#product-capture-editor").hidden = true;
  empty.hidden = false;
  empty.classList.toggle("is-loading", loading);
  empty.querySelector("strong").textContent = title;
  empty.querySelector("p").textContent = note;
}

function productSkuImageMeta(sku = {}) {
  const url = safeProductUrl(sku.imageUrl || sku.skuImageUrl || sku.resolvedImageUrl);
  const source = canonicalProductSkuImageSource(sku.imageSource || sku.source?.imageSource);
  const inherited = sku.imageInherited === true || sku.source?.imageInherited === true;
  let label = "未提供 SKU 图";
  if (url && (inherited || source === "PRODUCT_MAIN")) label = "继承商品主图";
  else if (url && source === "VARIANT_OPTION") label = "继承规格图";
  else if (url && ["MODEL", "SKU"].includes(source)) label = "SKU 专属图";
  else if (url && /USER|MANUAL/.test(source)) label = "人工补录图片";
  else if (url) label = "SKU 图片";
  return { url, source, inherited, label };
}

function productSkuDraftText(value) {
  return value === null || value === undefined ? "" : String(value);
}

function createProductSkuEditorDraft(sku = {}) {
  return {
    original: sku,
    fields: {
      variationName: productSkuDraftText(sku.variationName || sku.name || "默认规格"),
      externalSkuId: productSkuDraftText(sku.externalSkuId || sku.skuId),
      attributes: JSON.stringify(productSkuAttributes(sku)),
      imageUrl: productSkuDraftText(sku.imageUrl || sku.skuImageUrl),
      sellerSku: productSkuDraftText(sku.sellerSku || sku.sku),
      price: productSkuDraftText(sku.price),
      originalPrice: productSkuDraftText(sku.originalPrice),
      stock: productSkuDraftText(sku.stock)
    },
    edited: false,
    imageEdited: false,
    originalPriceEdited: false
  };
}

function resetProductSkuEditorState(capture = null) {
  const skus = productCaptureSkus(capture);
  const nextCaptureId = capture?.id || null;
  const switchingCapture = productSkuDraftCaptureId !== nextCaptureId;
  productSkuDraftCaptureId = nextCaptureId;
  productSkuDraftState = skus.map(createProductSkuEditorDraft);
  productSkuDraftDirty = false;
  selectedProductSkuIndex = switchingCapture
    ? 0
    : Math.min(Math.max(0, selectedProductSkuIndex), Math.max(0, skus.length - 1));
  productSkuSearchQuery = "";
}

function ensureProductSkuEditorState(capture = selectedProductCaptureDetail) {
  if (productSkuDraftCaptureId !== capture?.id) resetProductSkuEditorState(capture);
  return productSkuDraftState;
}

function syncCurrentProductSkuDraftFromEditor() {
  const editor = document.querySelector("#product-sku-editor[data-product-sku-index]");
  if (!editor) return;
  const index = Number(editor.dataset.productSkuIndex);
  const draft = productSkuDraftState[index];
  if (!draft) return;
  editor.querySelectorAll("[data-product-sku-field]").forEach(field => {
    draft.fields[field.dataset.productSkuField] = field.value;
  });
  draft.edited = editor.dataset.productSkuEdited === "true";
  draft.imageEdited = editor.dataset.productSkuImageEdited === "true";
  draft.originalPriceEdited = editor.dataset.productSkuOriginalPriceEdited === "true";
}

function productSkuDraftSearchText(draft = {}) {
  const fields = draft.fields || {};
  return [fields.variationName, fields.externalSkuId, fields.sellerSku, fields.attributes]
    .join(" ").toLocaleLowerCase();
}

function filteredProductSkuIndexes() {
  const query = productSkuSearchQuery.trim().toLocaleLowerCase();
  return productSkuDraftState.reduce((indexes, draft, index) => {
    if (!query || productSkuDraftSearchText(draft).includes(query)) indexes.push(index);
    return indexes;
  }, []);
}

function productSkuDraftLabel(draft = {}, index = 0) {
  const fields = draft.fields || {};
  return fields.variationName.trim() || `未命名规格 ${index + 1}`;
}

function renderProductSkuSelector() {
  const select = document.querySelector("#product-sku-select");
  const indexes = filteredProductSkuIndexes();
  const total = productSkuDraftState.length;
  const selectedVisiblePosition = indexes.indexOf(selectedProductSkuIndex);
  if (indexes.length && selectedVisiblePosition < 0) selectedProductSkuIndex = indexes[0];
  select.innerHTML = indexes.length
    ? indexes.map(index => `<option value="${index}"${index === selectedProductSkuIndex ? " selected" : ""}>${escapeHtml(`${index + 1}. ${productSkuDraftLabel(productSkuDraftState[index], index)}`)}</option>`).join("")
    : `<option value="" selected>没有匹配的 SKU</option>`;
  select.disabled = indexes.length === 0;
  const position = indexes.indexOf(selectedProductSkuIndex);
  document.querySelector("#product-sku-previous").disabled = position <= 0;
  document.querySelector("#product-sku-next").disabled = position < 0 || position >= indexes.length - 1;
  document.querySelector("#product-sku-position").textContent = indexes.length
    ? `第 ${selectedProductSkuIndex + 1} / ${total} 条`
    : total ? `无匹配 / 共 ${total} 条` : "第 0 / 0 条";
  document.querySelector("#product-sku-filter-status").textContent = productSkuSearchQuery.trim()
    ? `筛选到 ${indexes.length} 条；清空搜索可查看全部 ${total} 条`
    : `${total} 条规格组合；可用 Alt + ← / → 快速切换`;
}

function renderCurrentProductSkuEditor() {
  const editor = document.querySelector("#product-sku-editor");
  if (productSkuSearchQuery.trim() && !filteredProductSkuIndexes().length) {
    editor.removeAttribute("data-product-sku-index");
    editor.innerHTML = `<div class="product-sku-no-match">没有匹配的 SKU；清空搜索后可继续编辑原有规格。</div>`;
    return;
  }
  const draft = productSkuDraftState[selectedProductSkuIndex];
  if (!draft) {
    editor.removeAttribute("data-product-sku-index");
    editor.innerHTML = `<div class="product-sku-no-match">没有匹配的 SKU；清空搜索后可继续编辑原有规格。</div>`;
    return;
  }
  const index = selectedProductSkuIndex;
  const sku = draft.original || {};
  const fields = draft.fields;
  const entrySource = productSkuEntrySource(sku);
  const entrySourceLabel = entrySource === "MANUAL"
    ? "人工补充" : entrySource === "USER_EDITED" ? "人工编辑" : "平台采集";
  editor.dataset.productSkuIndex = String(index);
  editor.dataset.productSkuEdited = String(draft.edited);
  editor.dataset.productSkuImageEdited = String(draft.imageEdited);
  editor.dataset.productSkuOriginalPriceEdited = String(draft.originalPriceEdited);
  editor.innerHTML = `<header class="product-sku-editor-head">
    <div><small>正在编辑第 ${index + 1} 条</small><strong>${escapeHtml(productSkuDraftLabel(draft, index))}</strong></div>
    <div><span class="product-sku-source ${entrySource === "PLATFORM" ? "" : "manual"}">${entrySourceLabel}</span><button class="product-sku-remove" type="button" data-remove-product-sku="${index}" aria-label="删除第 ${index + 1} 条 SKU：${escapeHtml(fields.variationName)}">删除此 SKU</button></div>
  </header>
  <div class="product-sku-editor-grid is-simple">
    <label>规格组合 / 名称<input data-product-sku-field="variationName" value="${escapeHtml(fields.variationName)}" autocomplete="off" aria-label="SKU ${index + 1} 规格组合或名称"></label>
    <label>售价<input data-product-sku-field="price" inputmode="decimal" value="${escapeHtml(fields.price)}" autocomplete="off" aria-label="SKU ${index + 1} 售价"></label>
    <p class="product-sku-preserved-note">其他平台字段已自动保留，无需填写。</p>
  </div>`;
}

function renderProductSkus(capture) {
  const skus = productCaptureSkus(capture);
  ensureProductSkuEditorState(capture);
  document.querySelector("#product-sku-count").textContent = `${productSkuDraftState.length} 个 SKU`;
  document.querySelector("#product-sku-search").value = productSkuSearchQuery;
  document.querySelector("#product-sku-workbench").hidden = productSkuDraftState.length === 0;
  document.querySelector("#product-sku-empty").hidden = productSkuDraftState.length > 0;
  if (!skus.length && !productSkuDraftState.length) return;
  selectedProductSkuIndex = Math.min(Math.max(0, selectedProductSkuIndex), productSkuDraftState.length - 1);
  renderProductSkuSelector();
  renderCurrentProductSkuEditor();
}

function productSkuManualConfirmationEligible(capture = selectedProductCaptureDetail, skus = productCaptureSkus(capture)) {
  const meta = productSkuCompletenessMeta(capture);
  if (!skus.length || productSkuNonRepairableIssues(meta.issues).length > 0
      || (meta.expectedCount !== null && skus.length !== meta.expectedCount)) return false;
  const ids = skus.map(sku => String(sku.externalSkuId || sku.skuId || "").trim());
  const completeAttributes = sku => {
    if (!meta.hasVariants) return true;
    const attributes = productSkuAttributes(sku);
    if (!productSkuAttributesComplete(attributes)) return false;
    return !meta.variantDimensionCount
      || Object.keys(attributes).length === meta.variantDimensionCount;
  };
  const validPrice = value => value !== null && value !== undefined && value !== ""
    && Number.isFinite(Number(value)) && Number(value) >= 0;
  return ids.every(Boolean)
    && new Set(ids).size === ids.length
    && skus.every(completeAttributes)
    && skus.every(sku => validPrice(sku.price))
    && skus.every(sku => validPrice(sku.originalPrice) && Number(sku.originalPrice) >= Number(sku.price))
    && skus.every(sku => Boolean(productSkuImageMeta(sku).url));
}

function renderProductSkuCompleteness(capture) {
  const meta = productSkuCompletenessMeta(capture);
  const panel = document.querySelector("#product-sku-completeness");
  let displayedSkus = productCaptureSkus(capture);
  let draftValidationError = "";
  if (capture?.id === selectedProductCaptureId && productSkuDraftDirty) {
    try {
      displayedSkus = productSkuDrafts();
    } catch (error) {
      draftValidationError = error.message || "当前 SKU 输入格式需要修正";
    }
  }
  const manualCount = displayedSkus
    .filter(sku => productSkuEntrySource(sku) === "MANUAL").length;
  const editedCount = displayedSkus
    .filter(sku => productSkuEntrySource(sku) === "USER_EDITED").length;
  const platformComplete = meta.complete && !meta.userConfirmed;
  panel.className = `product-sku-completeness ${platformComplete ? "is-complete" : meta.complete ? "is-user-complete" : meta.incomplete ? "is-incomplete" : "is-unknown"}`;
  document.querySelector("#product-sku-completeness-status").textContent = platformComplete
    ? "页面 SKU 必填字段齐全"
    : meta.complete ? "人工核对完整"
      : meta.incomplete ? "SKU 不完整 · 阻断审核" : "完整性待确认 · 阻断审核";
  document.querySelector("#product-sku-completeness-count").textContent = meta.expectedCount === null
    ? `已采集 ${meta.capturedCount} / 应有数量未知`
    : `已采集 ${meta.capturedCount} / 应有 ${meta.expectedCount}`;
  const dimensionLabel = meta.hasVariants
    ? `${meta.variantDimensionCount ?? "未知"} 个变体维度`
    : "无变体单 SKU 可使用空属性";
  document.querySelector("#product-sku-completeness-source").textContent =
    `来源：${productSkuCompletenessSourceLabel(meta.source)} · ${dimensionLabel}${meta.locallyDirty ? " · 有未保存人工修改" : ""} · 当前列表 ${displayedSkus.length} 条${manualCount ? ` · ${manualCount} 条人工补充` : ""}${editedCount ? ` · ${editedCount} 条人工编辑` : ""}${draftValidationError ? " · 当前输入待修正" : ""}`;

  const note = document.querySelector("#product-sku-completeness-note");
  if (platformComplete) {
    note.textContent = meta.inventoryMissingCount > 0
      ? `采集数量及必填 SKU 字段齐全；${meta.inventoryMissingCount} 个 SKU 的库存未采集，库存为选填，不影响审核。`
      : "采集数量与详情页声明数量一致，且每个 SKU 的平台 ID、变体属性、售价、原价和图片均齐全；库存为选填，发布前仍需人员核对。";
  } else if (meta.complete) {
    note.textContent = "当前刊登 SKU 已由人员逐项核对；来源为 USER，不代表插件已证明平台原始规格完整。";
  } else {
    note.textContent = meta.expectedCount !== null && meta.actualCount < meta.expectedCount
      ? `还缺 ${meta.expectedCount - meta.actualCount} 条规格。请刷新并展开商品详情页全部规格后重新采集；手工补充不会冒充平台采集。`
      : "请刷新并展开商品详情页全部规格后重新采集。也可补齐全部字段后以 USER 来源人工确认。";
  }
  const issueList = document.querySelector("#product-sku-completeness-issues");
  issueList.hidden = meta.issues.length === 0;
  issueList.innerHTML = meta.issues.map(issue =>
    `<li>${escapeHtml(productSkuCompletenessIssueText(issue))}</li>`
  ).join("");

  const sourceLink = document.querySelector("#product-sku-recapture-link");
  const sourceUrl = safeProductUrl(capture?.sourceUrl);
  sourceLink.hidden = meta.complete || !sourceUrl;
  sourceLink.href = sourceUrl || "#";

  const access = productAccessCapabilities();
  const published = String(capture?.status || "").toUpperCase() === "PUBLISHED";
  const confirmLabel = document.querySelector("#product-sku-manual-confirm-label");
  const confirm = document.querySelector("#product-sku-manual-confirm");
  confirmLabel.hidden = platformComplete || !access.edit || published;
  confirm.checked = meta.complete && meta.userConfirmed && !productSkuCompletenessDirty;
  confirm.disabled = Boolean(draftValidationError)
    || !productSkuManualConfirmationEligible(capture, displayedSkus);
  confirm.title = confirm.disabled
    ? draftValidationError || "请先清除采集问题，并补齐应有数量、平台 ID、变体属性、售价、原价和 SKU 图片；库存为选填"
    : "保存后完整性来源会标记为 USER";
}

let productVideoLoadGeneration=0;
async function renderProductVideos(capture) {
 const generation=++productVideoLoadGeneration, org=currentUser?.organizationId;
 let section=document.querySelector('#product-captured-videos');
 if(!section){section=document.createElement('section');section.id='product-captured-videos';document.querySelector('.product-action-dock').before(section);}
 section.replaceChildren();section.hidden=true;
 try {
  const result=await api(`/api/v1/product-captures/${encodeURIComponent(capture.id)}/videos`);
  if(generation!==productVideoLoadGeneration||selectedProductCaptureId!==capture.id||currentUser?.organizationId!==org)return;
  const videos=result.data||[];if(!videos.length)return;
  section.hidden=false;const title=document.createElement('h3');title.textContent=`已采集视频（${videos.length}）`;section.append(title);
  for(const item of videos){const url=`/api/v1/product-captures/${encodeURIComponent(capture.id)}/videos/${encodeURIComponent(item.id)}/file`;const box=document.createElement('div');box.style.display='inline-flex';box.style.flexDirection='column';box.style.margin='12px';const video=document.createElement('video');video.controls=true;video.preload='metadata';video.src=url;video.style.cssText='width:260px;max-height:360px;background:#111;border-radius:8px';const link=document.createElement('a');link.href=url;link.download=`${capture.id}-${item.id}.mp4`;link.textContent='下载原视频';box.append(video,link);section.append(box);}
 }catch(error){if(generation===productVideoLoadGeneration&&selectedProductCaptureId===capture.id){section.hidden=false;section.textContent='视频读取失败，请刷新重试';}}
}

function renderProductImages(capture) {
  const images = productCaptureImages(capture);
  const completed = images.filter(image => productImageState(image) === "completed").length;
  const progress = images.length ? Math.round(completed * 100 / images.length) : 0;
  document.querySelector("#product-image-progress-text").textContent = `${completed} / ${images.length}`;
  const counts = {processing:0,waiting:0,failed:0};
  images.forEach(image => { const state = productImageState(image); if (state in counts) counts[state]++; });
  document.querySelector("#product-image-queue-summary").textContent = `处理中 ${counts.processing} · 排队中 ${counts.waiting} · 失败 ${counts.failed} · 全局最多并发 500 张`;
  document.querySelector("#product-image-progress-bar").style.width = `${progress}%`;
  const grid = document.querySelector("#product-image-grid");
  if (!images.length) {
    IncrementalDOM.html(grid, `<div class="product-empty"><strong>没有采集到图片</strong><span>请返回来源商品页重新采集。</span></div>`);
    return;
  }
  IncrementalDOM.html(grid, images
    .slice()
    .sort((left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0))
    .map((image, index) => {
      const original = safeProductUrl(image.originalUrl || image.url);
      const state = productImageState(image);
      const processed = ["processing", "waiting"].includes(state) ? "" : safeProductUrl(image.processedUrl);
      const dryRun = productImageIsDryRun(image, capture);
      const roleName = String(image.role || image.roleName || "").toUpperCase();
      const role = roleName === "MAIN" ? "主图"
        : roleName === "VARIANT" ? "规格图"
          : ["DESCRIPTION", "DETAIL"].includes(roleName) ? "详情图" : `商品图 ${index + 1}`;
      return `<article data-update-key="${escapeHtml(image.id || image.originalUrl || image.url || index)}" class="product-image-card ${state}" aria-busy="${state === "processing"}">
        <header><div><strong>${escapeHtml(role)}</strong><small>${escapeHtml(image.mimeType || "图片")}</small></div><span>${escapeHtml(productImageDisplayState(image, capture))}</span></header>
        <div class="product-image-compare">
          <figure><figcaption>来源原图</figcaption>${original ? `<button type="button" data-asset-preview="来源原图" aria-label="放大查看来源原图"><img src="${escapeHtml(original)}" data-full-src="${escapeHtml(original)}" alt="${escapeHtml(role)}来源原图" loading="lazy"></button>` : `<div class="product-image-placeholder">无原图</div>`}</figure>
          <figure><figcaption>${dryRun ? "Dry Run 占位图" : "AI 处理图"}</figcaption>${processed ? `<button type="button" data-asset-preview="${dryRun ? "Dry Run 占位图" : "AI 去水印结果"}" aria-label="放大查看${dryRun ? " Dry Run 占位图" : " AI 去水印结果"}"><img src="${escapeHtml(processed)}" data-full-src="${escapeHtml(processed)}" alt="${escapeHtml(role)}处理图" loading="lazy"></button>` : `<div class="product-image-placeholder"><i></i><span>${state === "failed" ? "处理失败" : state === "processing" ? "AI 去水印中" : "排队中"}</span></div>`}</figure>
        </div>
        ${state === "processing" ? `<p class="product-image-working-note"><i aria-hidden="true"></i>${escapeHtml(productImageStateName(image))}，请稍候…</p>` : ["failed","waiting"].includes(state) && (productLiveImageStage(image)?.message || image.errorMessage) ? `<p role="status">${escapeHtml(productLiveImageStage(image)?.message || image.errorMessage)}</p>` : ""}
      </article>`;
    }).join(""));
}

function productReadiness(capture) {
  const images = productCaptureImages(capture);
  const skus = productCaptureSkus(capture);
  const rawMissingFields = Array.isArray(capture?.missingFields) ? capture.missingFields.filter(Boolean) : [];
  const skuCompleteness = productSkuCompletenessMeta(capture);
  const missingFields = productBlockingMissingFields(capture, skuCompleteness);
  const missingLabels = {
    TITLE: "标题", DESCRIPTION: "描述", CATEGORY: "分类", CATEGORY_NAME: "分类",
    BRAND: "品牌", BRAND_NAME: "品牌", PRICE: "价格", PRICE_MIN: "价格",
    SKU: "SKU", SKUS: "SKU", SKU_PRICE: "SKU 价格", SKU_ORIGINAL_PRICE: "SKU 原价",
    SKU_IMAGE: "SKU 图片", SKU_IMAGE_ASSET: "未纳入处理素材的 SKU 图片",
    SKU_INCOMPLETE: "平台 SKU 完整性", IMAGES: "图片",
    IMAGE_INCOMPLETE: "商品刊登图片不完整"
  };
  const needsEnrichment = capture?.completeness === "NEEDS_ENRICHMENT"
    && (!Array.isArray(capture?.missingFields) || missingFields.length > 0);
  const completenessKnown = Boolean(capture?.completeness) || rawMissingFields.length > 0;
  const completenessLabel = missingFields.length
    ? `页面未提供：${missingFields.map(field => missingLabels[String(field).toUpperCase()] || field).join("、")}，待补全`
    : needsEnrichment ? "页面仍有字段待补全" : "页面直采字段已补全";
  const allImagesProcessed = images.length > 0 && images.every(image => productImageState(image) === "completed");
  const publicationImageUrls = new Set(images.map(image =>
    safeProductUrl(image.originalUrl || image.url)
  ).filter(Boolean));
  const dryRunImages = allImagesProcessed && images.some(image => productImageIsDryRun(image, capture));
  const checks = [
    ...(completenessKnown ? [{ ok: !needsEnrichment && missingFields.length === 0, label: completenessLabel }] : []),
    { ok: Boolean(String(capture?.title || "").trim()), label: "商品标题已填写" },
    { ok: Boolean(String(capture?.description || "").trim()), label: "商品描述已填写" },
    { ok: capture?.priceMin !== null && capture?.priceMin !== undefined && capture?.priceMin !== "", label: "价格已核对" },
    { ok: skus.length > 0, label: "至少包含一个 SKU" },
    {
      ok: skuCompleteness.complete,
      label: skuCompleteness.complete
        ? `${skuCompleteness.userConfirmed ? "人工核对" : "平台采集"} SKU 完整（${skuCompleteness.capturedCount} / ${skuCompleteness.expectedCount}）`
        : skuCompleteness.expectedCount === null
          ? "SKU 应有数量尚未确认，禁止审核"
          : `SKU 不完整：已采 ${skuCompleteness.capturedCount} / 应有 ${skuCompleteness.expectedCount}，禁止审核`
    },
    { ok: skus.length > 0 && skus.every(sku => sku.price !== null && sku.price !== undefined && sku.price !== ""), label: "SKU 价格已补全" },
    {
      ok: skus.length > 0 && skus.every(sku => sku.originalPrice !== null && sku.originalPrice !== undefined
        && sku.originalPrice !== "" && Number(sku.originalPrice) >= Number(sku.price)),
      label: "SKU 原价已补全且不低于售价"
    },
    {
      ok: skus.length > 0 && skus.every(sku => !skuCompleteness.hasVariants
        || (productSkuAttributesComplete(productSkuAttributes(sku))
          && (!skuCompleteness.variantDimensionCount
            || Object.keys(productSkuAttributes(sku)).length === skuCompleteness.variantDimensionCount))),
      label: skuCompleteness.hasVariants
        ? `SKU 变体属性已覆盖 ${skuCompleteness.variantDimensionCount ?? "未知"} 个维度`
        : "无变体商品无需 SKU 属性"
    },
    { ok: skus.length > 0 && skus.every(sku => Boolean(productSkuImageMeta(sku).url)), label: "每个 SKU 均有专属图或继承图" },
    {
      ok: skus.length > 0 && skus.every(sku => publicationImageUrls.has(productSkuImageMeta(sku).url)),
      label: "每张 SKU / 规格图均已纳入图片处理任务"
    },
    { ok: images.length > 0, label: "至少包含一张商品图" },
    { ok: allImagesProcessed, label: dryRunImages ? "Dry Run 占位图已生成（未实际去水印）" : "全部图片去水印完成" }
  ];
  return { checks, ready: checks.every(check => check.ok) };
}

function syncProductEditorAccess(capture) {
  const access = productAccessCapabilities();
  const published = capture.status === "PUBLISHED";
  const workflowRunning = productUploadInFlightCaptureId === capture.id;
  const saving = productCaptureSaveInFlightId === capture.id;
  document.querySelectorAll("#product-capture-editor input, #product-capture-editor textarea").forEach(field => {
    field.disabled = !access.edit || published || saving || workflowRunning;
  });
  const skuSearch = document.querySelector("#product-sku-search");
  skuSearch.disabled = productSkuDraftState.length === 0;
  document.querySelector("#product-sku-select").disabled = filteredProductSkuIndexes().length === 0;
  document.querySelector("#add-product-sku").hidden = true;
  document.querySelector("#add-product-sku").disabled = saving || workflowRunning;
  document.querySelectorAll("#product-sku-editor [data-remove-product-sku]").forEach(button => {
    button.hidden = !access.edit || published;
    button.disabled = saving || workflowRunning;
  });
  document.querySelector("#product-edit-access-note").textContent = published
    ? "已提交平台的记录不可修改"
    : workflowRunning ? "一键上传正在后台执行，商品资料暂时只读"
      : saving ? "正在保存，请稍候" : access.edit ? "修改后先保存草稿" : "当前账号仅可查看";
  document.querySelector("#save-product-capture").hidden = !access.edit;
  document.querySelector("#process-product-images").hidden = !access.watermark;
  document.querySelector("#approve-product-capture").hidden = !access.edit;
  document.querySelector("#design-current-product").hidden = !access.edit;
}

const productUploadResultByCapture = new Map();
function renderProductMainImageProgress(workflow, compact = false) {
  const style = workflow?.mainImageStyle;
  if (!style) return '';
  const count = value => Math.max(0, Math.floor(Number(value) || 0));
  const total = count(style.totalImages);
  const done = Math.min(total, count(style.processedImages));
  const images = Array.isArray(style.images) ? style.images : [];
  const labels = {WAITING:'等待准备', QUEUED:'排队中', GENERATING:'正在生成', SAVING:'正在保存生成结果',
    REVIEW_QUEUED:'等待结果检查', REVIEW_MODEL_WAITING:'正在检查商品与版式',
    REVIEW_MODEL_STREAMING:'正在返回检查结果', REVIEW_VALIDATING:'正在校验检查结果',
    CORRECTING:'等待自动修正', READY:'生成完成', PRESERVED:'原样保留', FAILED:'处理失败', STOPPED:'已停止'};
  const activeStages = ['GENERATING','SAVING','REVIEW_MODEL_WAITING','REVIEW_MODEL_STREAMING','REVIEW_VALIDATING'];
  const active = images.find(image => activeStages.includes(image.stage));
  const preserved = images.filter(image => image.stage === 'PRESERVED').length;
  const failed = images.filter(image => image.stage === 'FAILED' || image.stage === 'STOPPED').length;
  const stageText = image => labels[image.stage] || '等待处理';
  const imageLink = (url, index) => {
    const safe = safeProductUrl(url);
    return safe ? `<a class="main-style-workflow-image" href="${escapeHtml(safe)}" target="_blank" rel="noopener"><img src="${escapeHtml(safe)}" alt="第 ${index + 1} 张风格主图" loading="lazy"></a>`
      : `<span class="main-style-workflow-number">${index + 1}</span>`;
  };
  const rows = images.map(image => {
    const index = count(image.index);
    const busy = activeStages.includes(image.stage);
    const tone = ['FAILED','STOPPED'].includes(image.stage) ? 'failed' : image.status === 'READY' ? 'ready' : busy ? 'active' : 'waiting';
    const metrics = [];
    if (busy && image.elapsedSeconds != null) metrics.push(`本阶段已用 ${count(image.elapsedSeconds)} 秒`);
    if (busy && count(image.outputCharacters)) metrics.push(`检查已返回 ${count(image.outputCharacters)} 字符`);
    return `<li class="main-style-workflow-image-row is-${tone}">
      ${imageLink(image.status === 'READY' ? image.imageUrl : null, index)}
      <div><strong>第 ${index + 1} 张</strong><span>${escapeHtml(stageText(image))}</span>
      ${metrics.length ? `<small>${escapeHtml(metrics.join(' · '))}</small>` : ''}
      ${image.errorMessage ? `<small class="main-style-workflow-error">${escapeHtml(image.errorMessage)}</small>` : ''}</div></li>`;
  }).join('');
  return `<section class="main-style-workflow-progress${compact ? ' is-compact' : ''}" aria-label="风格主图处理进度">
    <header><strong>风格主图</strong><span>${done} / ${total} 张就绪</span></header>
    <p class="main-style-workflow-template">${escapeHtml(style.templateName || '')}${preserved ? ` · 原样保留 ${preserved} 张` : ''}${failed ? ` · 未完成 ${failed} 张` : ''}</p>
    ${total ? `<progress value="${done}" max="${total}" aria-label="风格主图就绪张数"></progress>` : ''}
    ${active && productPublishWorkflowIsActive(workflow) ? `<p class="main-style-workflow-current">第 ${count(active.index) + 1} 张 · ${escapeHtml(stageText(active))}</p>` : ''}
    ${rows ? `<ol class="main-style-workflow-list">${rows}</ol>` : (style.imageUrls || []).length
      ? `<div class="main-style-result-strip">${style.imageUrls.map((url, index) => imageLink(url, index)).join('')}</div>` : ''}
    <p class="main-style-workflow-connection" role="status" hidden></p></section>`;
}

function renderProductUploadResult(capture) {
  const el = document.querySelector('#product-upload-result');
  if (!el) return;
  const workflow = productUploadResultByCapture.get(`${currentUser?.organizationId}:${capture.id}`);
  el.hidden = !workflow;
  if (!workflow) return;
  const state = String(workflow.status || '').toUpperCase();
  const remoteId = workflow.publication?.externalId || String(workflow.errorMessage || '').match(/Shopee 商品 (\d+) 已按未上架状态创建/)?.[1];
  const success = state === 'SUCCEEDED';
  const running = productPublishWorkflowIsActive(workflow);
  const dry = workflow.executionMode !== 'LIVE';
  el.dataset.state = success ? 'success' : running ? 'running' : 'warning';
  const title = success ? dry ? '模拟上传完成' : '✓ 上传成功' : running ? '上传处理中…' : remoteId ? '已创建，后续上传未完成' : '上传失败';
  const detail = success ? productPublishOutcome(workflow).stepText : running ? ({IMAGES:'图片处理中',STYLE_IMAGES:'风格主图自动生成中',APPROVE:'资料校验中',PUBLISH:'正在提交平台'})[workflow.currentStep] || '排队中' : workflow.errorMessage || '请查看上传结果';
  const copy = `${remoteId ? '平台商品 ID：' + remoteId + ' · ' : ''}${detail}`;
  replaceHtmlPreservingMedia(el, `<strong>${escapeHtml(title)}</strong><span>${escapeHtml(copy)}</span>${renderProductMainImageProgress(workflow)}`);
}
function syncProductActions(capture) {
  const status = String(capture.status || "CAPTURED").toUpperCase();
  const images = productCaptureImages(capture);
  const readiness = productReadiness(capture);
  const skuCompleteness = productSkuCompletenessMeta(capture);
  const processing = status === "IMAGE_PROCESSING";
  const published = status === "PUBLISHED";
  const approved = status === "APPROVED";
  const publishable = approved || published;
  const saving = Boolean(productCaptureSaveInFlightId);
  const workflowRunning = productUploadInFlightCaptureId === capture.id;
  const localChanges = productEditorDirty && selectedProductCaptureId === capture.id;
  document.querySelector("#design-current-product").disabled = localChanges || saving || processing || workflowRunning;
  document.querySelector("#save-product-capture").disabled = processing || published || saving || workflowRunning;
  document.querySelector("#save-product-capture").textContent = productCaptureSaveInFlightId === capture.id
    ? "正在保存…" : "保存修改";
  document.querySelector("#process-product-images").disabled = processing || published || !images.length || localChanges || saving || workflowRunning;
  document.querySelector("#process-product-images").textContent = status === "FAILED" ? "重试图片处理" : processing ? "图片处理中…" : "开始图片处理";
  document.querySelector("#approve-product-capture").disabled = status !== "READY" || !readiness.ready || localChanges || saving || workflowRunning;
  document.querySelector("#approve-product-capture").textContent = publishable ? "已人工确认" : "人工确认";

  const title = document.querySelector("#product-action-title");
  const note = document.querySelector("#product-action-note");
  if (published) {
    title.textContent = "已有平台提交记录";
    note.textContent = "商品资料保持只读；是否已上架以平台状态为准，仍可选择另一家店铺继续提交。";
  } else if (approved && productPublishAuditMeta.get(capture.id)?.dryRun) {
    title.textContent = "Dry Run 已验证";
    note.textContent = "模拟发布校验已通过，商品仍为可发布状态，未在真实店铺上架。";
  } else if (approved) {
    title.textContent = "商品已通过人工确认";
    note.textContent = "可返回商品列表，选择商品后从批量上传入口提交目标店铺。";
  } else if (!skuCompleteness.complete && !processing) {
    title.textContent = "SKU 尚未完整，人工审核已阻断";
    note.textContent = skuCompleteness.expectedCount === null
      ? "请回商品详情页刷新、展开全部规格后重新采集，或完整补录后以 USER 来源人工确认。"
      : `已采 ${skuCompleteness.capturedCount} / 应有 ${skuCompleteness.expectedCount}；请回详情页重新采集缺失规格。`;
  } else if (processing) {
    title.textContent = "AI 正在处理商品图片";
    note.textContent = "处理结束后页面会自动刷新，无需重复提交。";
  } else if (status === "READY" && readiness.ready) {
    title.textContent = "资料完整，等待人工确认";
    note.textContent = "确认后商品才会进入可发布状态。";
  } else {
    title.textContent = "先补齐商品资料与图片";
    note.textContent = "保存修改并完成全部图片去水印后再确认。";
  }
  renderProductUploadResult(capture);
}

function renderProductReadiness(capture) {
  const readiness = productReadiness(capture);
  const skuCompleteness = productSkuCompletenessMeta(capture);
  const status = String(capture.status || "CAPTURED").toUpperCase();
  const approved = ["APPROVED", "PUBLISHED"].includes(status);
  document.querySelector("#product-readiness-title").textContent = approved
    ? "已通过人工确认"
    : !skuCompleteness.complete ? "SKU 完整性未通过，禁止审核"
    : readiness.ready ? "资料完整，等待人工确认" : "还有项目需要处理";
  document.querySelector("#product-readiness-note").textContent = approved
    ? "平台是否审核通过并实际上架，仍需以目标平台状态为准。"
    : !skuCompleteness.complete
      ? "手工新增属于刊登补充；只有重新采集完整平台 SKU，或明确以 USER 来源逐项核对后，才可继续。"
    : "有商品编辑/审核权限的成员可以执行人工确认；真实发布另需发布权限。";
  IncrementalDOM.html(document.querySelector("#product-readiness-list"), readiness.checks.map(check =>
    `<li class="${check.ok ? "passed" : "missing"}"><i>${check.ok ? "✓" : "!"}</i><span>${escapeHtml(check.label)}</span></li>`
  ).join(""));
}

function showProductNotice(message, type = "error") {
  const element = document.querySelector("#product-detail-error");
  element.textContent = message;
  element.classList.toggle("is-success", type === "success");
  element.hidden = !message;
}

function renderProductCaptureDetail() {
  const capture = selectedProductCaptureDetail;
  if (!capture) {
    renderProductCaptureEmpty();
    return;
  }
  const empty = document.querySelector("#product-capture-empty");
  const editor = document.querySelector("#product-capture-editor");
  empty.hidden = true;
  editor.hidden = false;
  const statusChip = document.querySelector("#product-detail-status");
  statusChip.textContent = productCaptureDisplayStatus(capture);
  statusChip.className = `product-status-chip ${productCaptureStatusGroup(capture.status).toLowerCase()}`;
  document.querySelector("#product-source-platform").textContent = productSourcePlatformName(capture.sourcePlatform);
  const origin = productCaptureOrigin(capture);
  const originBadge = document.querySelector("#product-capture-mode");
  originBadge.textContent = origin.label;
  originBadge.className = `product-origin-badge ${origin.tone}`;
  document.querySelector("#product-source-id").textContent = capture.sourceProductId || capture.id;
  document.querySelector("#product-detail-title").setAttribute("translate", (capture.title) ? "no" : "yes");
  document.querySelector("#product-detail-title").textContent = capture.title || "未命名商品";
  document.querySelector("#product-updated-at").textContent = `更新于 ${formatDate(capture.updatedAt || capture.createdAt)}`;
  const sourceLink = document.querySelector("#product-source-link");
  const sourceUrl = safeProductUrl(capture.sourceUrl);
  sourceLink.hidden = !sourceUrl;
  sourceLink.href = sourceUrl || "#";
  document.querySelector("#product-title-input").value = capture.title || "";
  document.querySelector("#product-category-input").value = capture.categoryName || "";
  document.querySelector("#product-brand-input").value = capture.brandName || "";
  document.querySelector("#product-currency-input").value = capture.currency || "";
  document.querySelector("#product-price-min-input").value = capture.priceMin ?? "";
  document.querySelector("#product-price-max-input").value = capture.priceMax ?? "";
  document.querySelector("#product-description-input").value = capture.description || "";
  ProductListingTemplates.preview(capture);
  void ProductListingTemplates.ensure().then(() => {
    if (selectedProductCaptureDetail?.id === capture.id) ProductListingTemplates.preview(selectedProductCaptureDetail);
  }).catch(() => {});
  document.querySelector('#generate-product-ai-title').disabled = !hasAnyPermission('product:edit') || capture.status === 'PUBLISHED';
  const titleAi = capture.normalizedData?.titleAi;
  document.querySelector('#product-ai-title-status').textContent = titleAi?.status === 'SUCCEEDED' ? `${titleAi.basis === 'MAIN_IMAGE_AND_COLLECTED_FACTS' ? '采集时已根据主图与商品信息生成标题' : '采集时已由 Pi Agent SDK 处理标题'} · 原标题：${capture.normalizedData.originalTitle || ''}`
    : titleAi?.status === 'FAILED' ? `自动标题处理失败，已保留原标题：${titleAi.error || '请重试'}` : '新采集商品自动根据主图与商品信息生成标题';
  renderProductSkus(capture);
  renderProductImages(capture);
  void renderProductVideos(capture);
  renderProductReadiness(capture);
  syncProductEditorAccess(capture);
  renderProductSkuCompleteness(capture);
  syncProductActions(capture);
  if (productLastNotice?.captureId === capture.id) {
    showProductNotice(productLastNotice.message, productLastNotice.type);
  } else {
    showProductNotice("");
  }
}

function updateProductCaptureInMemory(capture, { preserveSelectedEditor = false } = {}) {
  if (!capture?.id) return;
  const index = productCaptures.findIndex(item => item.id === capture.id);
  const previousCapture = index >= 0
    ? productCaptures[index]
    : selectedProductCaptureId === capture.id ? selectedProductCaptureDetail : null;
  const previousVersion = Number(previousCapture?.version);
  const incomingVersion = Number(capture.version);
  if (Number.isSafeInteger(previousVersion) && Number.isSafeInteger(incomingVersion)
      && incomingVersion < previousVersion) return;
  const previousGroup = previousCapture?.status
    ? productCaptureStatusGroup(previousCapture.status) : null;
  const nextGroup = capture.status ? productCaptureStatusGroup(capture.status) : previousGroup;
  if (productCaptureStatusCountsKnown && previousGroup && nextGroup && previousGroup !== nextGroup) {
    productCaptureStatusCounts[previousGroup] = Math.max(0, productCaptureStatusCounts[previousGroup] - 1);
    productCaptureStatusCounts[nextGroup] += 1;
  }
  const leavesActiveServerFilter = !productCaptureLegacyListResponse
    && productCaptureStatusFilter !== "ALL"
    && nextGroup !== productCaptureStatusGroup(productCaptureStatusFilter);
  if (index >= 0 && leavesActiveServerFilter) {
    productCaptures.splice(index, 1);
    productCaptureTotalElements = Math.max(0, productCaptureTotalElements - 1);
    productCaptureTotalPages = productCaptureTotalElements
      ? Math.ceil(productCaptureTotalElements / PRODUCT_CAPTURE_PAGE_SIZE) : 0;
  } else if (index >= 0) productCaptures[index] = { ...productCaptures[index], ...capture };
  else if (productCaptureLegacyListResponse) productCaptures.unshift(capture);
  if (selectedProductCaptureId === capture.id && !preserveSelectedEditor) {
    productSkuCompletenessDirty = false;
    productSkuDraftDirty = false;
    productEditorDirty = false;
    selectedProductCaptureDetail = capture;
    resetProductSkuEditorState(capture);
  }
  renderProductSummary();
  renderProductCaptureList();
  if (selectedProductCaptureId === capture.id && !preserveSelectedEditor) renderProductCaptureDetail();
}

async function selectProductCapture(id, { silent = false, force = false } = {}) {
  const generation = ++productCaptureDetailLoadGeneration;
  if (!id) {
    stopProductPublishWorkflowPolling({ clearState: true });
    productPublishWorkflowLookupGeneration += 1;
    selectedProductCaptureId = null;
    selectedProductCaptureDetail = null;
    productEditorDirty = false;
    productSkuCompletenessDirty = false;
    resetProductSkuEditorState();
    renderProductCaptureList();
    renderProductCaptureEmpty();
    return;
  }
  if (selectedProductCaptureId !== id) {
    stopProductPublishWorkflowPolling({ clearState: true });
    productPublishWorkflowLookupGeneration += 1;
    productEditorDirty = false;
    productSkuCompletenessDirty = false;
    resetProductSkuEditorState();
  } else if (!force && (productEditorDirty || productCaptureSaveInFlightId)) {
    renderProductCaptureList();
    return;
  }
  selectedProductCaptureId = id;
  renderProductCaptureList();
  if (!silent) renderProductCaptureEmpty("正在读取商品详情…", "正在同步商品信息、SKU 与图片处理结果。", true);
  const organizationId = currentUser?.organizationId || "";
  try {
    const result = await productCaptureApi(`/${encodeURIComponent(id)}`);
    if (generation !== productCaptureDetailLoadGeneration
        || selectedProductCaptureId !== id
        || currentUser?.organizationId !== organizationId) return;
    const capture = productCapturePayload(result.data);
    if (!capture?.id) throw new ApiRequestError("商品详情响应不完整，请刷新后重试", 502, result);
    const preserveSelectedEditor = !force
      && selectedProductCaptureId === id
      && (productEditorDirty || Boolean(productCaptureSaveInFlightId));
    updateProductCaptureInMemory(capture, { preserveSelectedEditor });
    syncProductCapturePolling();
    void restoreProductPublishWorkflow(id);
    void refreshProductLiveImageProgress();
  } catch (error) {
    if (generation !== productCaptureDetailLoadGeneration || selectedProductCaptureId !== id) return;
    if (error?.status === 401) return;
    if (!force && (productEditorDirty || productCaptureSaveInFlightId)) return;
    selectedProductCaptureDetail = null;
    renderProductCaptureEmpty(
      error?.status === 403 ? "没有查看权限" : "商品详情读取失败",
      error.message || "请稍后重试"
    );
  }
}

function mergeProductCapturePages(existing, incoming) {
  const merged = existing.slice();
  const indexes = new Map(merged.map((capture, index) => [capture.id, index]));
  incoming.forEach(capture => {
    const index = indexes.get(capture.id);
    if (index === undefined) {
      indexes.set(capture.id, merged.length);
      merged.push(capture);
    } else {
      merged[index] = { ...merged[index], ...capture };
    }
  });
  return merged;
}

async function loadProductCaptures(preferredId = null, {
  silent = false,
  append = false,
  page = Math.max(0, productCapturePage),
  refreshDetail = true,
  refreshLoadedPages = false
} = {}) {
  if (append && (productCaptureListLoading || page < 1 || page >= productCaptureTotalPages)) return;
  const generation = ++productCaptureLoadGeneration;
  const organizationId = currentUser?.organizationId || "";
  const requestedPage = Math.max(0, page);
  const requestedPages = [requestedPage];
  renderProductPageIdentity();
  productCaptureListLoading = true;
  productCaptureAppendLoading = append;
  productCapturePaginationMessage = "";
  const list = document.querySelector("#product-capture-list");
  list.setAttribute("aria-busy", "true");
  void ProductListingTemplates.ensure().catch(() => {});
  if (!silent && !append) {
    document.querySelector("#product-capture-list").innerHTML = `<div class="product-empty is-loading"><strong>正在读取商品草稿…</strong><span>只显示当前公司的采集数据。</span></div>`;
    document.querySelector("#product-result-count").textContent = "正在读取商品…";
    document.querySelector("#product-capture-pagination").hidden = true;
  } else {
    syncProductCapturePagination();
  }
  syncProductBulkSelection();
  try {
    const results = await Promise.all(requestedPages.map(requested =>
      productCaptureApi(productCaptureListPath(requested))));
    if (generation !== productCaptureLoadGeneration || currentUser?.organizationId !== organizationId) return;
    const payloads = results.map((result, index) =>
      productCapturePagePayload(result.data, requestedPages[index]));
    const payload = payloads[0];
    if (payload.paged && requestedPage > 0 && requestedPage >= payload.totalPages) {
      return await loadProductCaptures(preferredId, {page:Math.max(0,payload.totalPages-1),silent,refreshDetail});
    }
    const captures = mergeProductCapturePages([], payloads.flatMap(current => current.content)
      .map(productCapturePayload)
      .filter(Boolean)
      .filter(capture => !capture.organizationId || capture.organizationId === organizationId));
    productCaptureLegacyListResponse = !payload.paged;
    productCapturePage = payload.paged
      ? Math.min(
        Math.max(...payloads.map(current => current.page)),
        Math.max(0, payload.totalPages - 1)
      )
      : 0;
    productCaptureTotalElements = payload.totalElements;
    productCaptureTotalPages = payload.totalPages;
    if (payload.statusCounts && typeof payload.statusCounts === "object") {
      productCaptureStatusCounts = normalizeProductCaptureStatusCounts(payload.statusCounts);
      productCaptureStatusCountsKnown = true;
    } else if (!payload.paged) {
      productCaptureStatusCounts = deriveProductCaptureStatusCounts(captures);
      productCaptureStatusCountsKnown = true;
    } else if (!append) {
      productCaptureStatusCounts = emptyProductCaptureStatusCounts();
      productCaptureStatusCountsKnown = false;
    }
    productCaptures = append && payload.paged
      ? mergeProductCapturePages(productCaptures, captures)
      : captures;
    productCaptureListLoading = false;
    productCaptureAppendLoading = false;
    list.setAttribute("aria-busy", "false");
    renderProductSummary();
    const candidateId = syncProductCaptureView();
    if (candidateId) selectedProductCaptureId = candidateId;
    renderProductCaptureList();
    if (!candidateId) {
      if (!selectedProductCaptureDetail) {
        const hasResults = filteredProductCaptures().length > 0;
        renderProductCaptureEmpty(
          hasResults ? "选择一条商品草稿" : "等待插件采集商品",
          hasResults
            ? "选择左侧商品后，再按需读取完整资料、SKU 和图片。"
            : "在 Shopee 商品详情页点击插件的“采集商品”，草稿会自动进入当前公司。"
        );
      }
      syncProductCapturePolling();
      return;
    }
    const canRefreshSelectedDetail = !productEditorDirty && !productCaptureSaveInFlightId;
    if (refreshDetail && canRefreshSelectedDetail) {
      const detailIsVisible = selectedProductCaptureDetail?.id === candidateId;
      await selectProductCapture(candidateId, { silent: silent || detailIsVisible });
    } else if (canRefreshSelectedDetail && selectedProductCaptureDetail?.id === candidateId) {
      renderProductCaptureDetail();
    }
    syncProductCapturePolling();
  } catch (error) {
    if (generation !== productCaptureLoadGeneration || currentUser?.organizationId !== organizationId) return;
    productCaptureListLoading = false;
    productCaptureAppendLoading = false;
    list.setAttribute("aria-busy", "false");
    if (error?.status === 401) return;
    if (append) {
      productCapturePaginationMessage = `加载更多失败：${error.message || "请稍后重试"}`;
      syncProductCapturePagination();
    } else if (silent && refreshLoadedPages) {
      productCapturePaginationMessage = `自动刷新失败，已保留当前列表：${error.message || "请稍后重试"}`;
      syncProductCapturePagination();
    } else {
      document.querySelector("#product-capture-list").innerHTML = `<div class="product-empty"><strong>${error?.status === 403 ? "没有商品查看权限" : "商品草稿读取失败"}</strong><span>${escapeHtml(error.message)}</span></div>`;
      document.querySelector("#product-result-count").textContent = "读取失败";
      document.querySelector("#product-capture-pagination").hidden = true;
    }
    syncProductBulkSelection();
  }
}

function syncProductCapturePolling() {
  if (productCapturePollTimer) clearInterval(productCapturePollTimer);
  productCapturePollTimer = null;
  const page = document.querySelector("#product-publish-page");
  if (!page || page.hidden) return;
  const active = (productCaptureStatusCountsKnown && productCaptureStatusCounts.PROCESSING > 0)
    || productCaptures.some(capture => productCaptureStatusGroup(capture.status) === "PROCESSING")
    || productCaptureStatusGroup(selectedProductCaptureDetail?.status) === "PROCESSING";
  productCapturePollTimer = window.setInterval(() => {
    if (productCapturePollInFlight || document.hidden || page.hidden) return;
    productCapturePollInFlight = true;
    const refreshSelectedDetail = productCaptureStatusGroup(selectedProductCaptureDetail?.status) === "PROCESSING"
      && !productEditorDirty
      && !productCaptureSaveInFlightId;
    loadProductCaptures(selectedProductCaptureId, {
      silent: true,
      refreshDetail: refreshSelectedDetail,
      refreshLoadedPages: true
    })
      .finally(() => { productCapturePollInFlight = false; });
  }, active ? 5000 : 15000);
}

function nullableProductValue(value) {
  const text = String(value ?? "").trim();
  return text ? text : null;
}

function productSkuAttributes(sku = {}) {
  if (sku.attributes && typeof sku.attributes === "object" && !Array.isArray(sku.attributes)) {
    return sku.attributes;
  }
  if (typeof sku.attributesJson === "string" && sku.attributesJson.trim()) {
    try {
      const parsed = JSON.parse(sku.attributesJson);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed;
    } catch (_error) {
      // Preserve a valid empty attribute object when legacy JSON cannot be decoded.
    }
  }
  return {};
}

function productSkuAttributesComplete(value) {
  if (value === null || value === undefined) return false;
  if (typeof value === "string") return Boolean(value.trim());
  if (Array.isArray(value)) return value.length > 0 && value.every(productSkuAttributesComplete);
  if (typeof value === "object") {
    const values = Object.values(value);
    return values.length > 0 && values.every(productSkuAttributesComplete);
  }
  return true;
}

function parseProductSkuAttributesDraft(value, index) {
  const text = String(value || "").trim();
  if (!text) return {};
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed;
  } catch (_error) {
    // A field-specific validation error below is more useful than the JSON parser detail.
  }
  throw new ApiRequestError(`SKU ${index + 1} 的规格属性必须是 JSON 对象，例如 {"颜色":"蓝色"}`, 400);
}

function parseProductSkuStockDraft(value, index) {
  const text = String(value || "").trim();
  if (!text) return null;
  if (!/^\d+$/.test(text)) throw new ApiRequestError(`SKU ${index + 1} 的库存必须是非负整数`, 400);
  const stock = Number(text);
  if (!Number.isSafeInteger(stock) || stock > 2147483647) {
    throw new ApiRequestError(`SKU ${index + 1} 的库存超出允许范围`, 400);
  }
  return stock;
}

const PRODUCT_SKU_SERVER_FIELDS = new Set([
  "id", "organizationId", "organization_id", "captureId", "capture_id", "skuKey",
  "createdAt", "created_at", "updatedAt", "updated_at",
  "__proto__", "prototype", "constructor"
]);

function productSkuPreservedFields(original = {}) {
  const source = original.source && typeof original.source === "object"
    && !Array.isArray(original.source) ? original.source : {};
  const preserved = { ...source };
  Object.entries(original).forEach(([key, value]) => {
    if (key !== "source" && !PRODUCT_SKU_SERVER_FIELDS.has(key)) preserved[key] = value;
  });
  if (Object.keys(source).length) preserved.source = source;
  return preserved;
}

function productSkuDrafts() {
  ensureProductSkuEditorState();
  syncCurrentProductSkuDraftFromEditor();
  return productSkuDraftState.map((draft, index) => {
    const original = draft.original || {};
    const field = name => draft.fields[name] ?? "";
    const price = nullableProductValue(field("price"));
    const capturedOriginalPrice = nullableProductValue(field("originalPrice"));
    const deriveOriginalPrice = price !== null && (capturedOriginalPrice === null
      || !Number.isFinite(Number(capturedOriginalPrice)) || Number(capturedOriginalPrice) < Number(price));
    const originalEntrySource = productSkuEntrySource(original);
    const entrySource = originalEntrySource === "MANUAL"
        ? "MANUAL_LISTING"
      : draft.edited || originalEntrySource === "USER_EDITED"
        ? "USER_EDITED" : "PLATFORM_CAPTURE";
    return {
      ...productSkuPreservedFields(original),
      externalSkuId: nullableProductValue(field("externalSkuId")),
      variationName: field("variationName").trim(),
      sellerSku: field("sellerSku").trim(),
      price,
      originalPrice: deriveOriginalPrice ? price : capturedOriginalPrice,
      originalPriceSource: deriveOriginalPrice ? "DERIVED"
        : draft.originalPriceEdited ? "USER" : original.originalPriceSource || original.source?.originalPriceSource || null,
      stock: parseProductSkuStockDraft(field("stock"), index),
      attributes: parseProductSkuAttributesDraft(field("attributes"), index),
      imageUrl: nullableProductValue(field("imageUrl")),
      imageSource: draft.imageEdited ? "USER"
        : canonicalProductSkuImageSource(original.imageSource || original.source?.imageSource) || null,
      imageInherited: draft.imageEdited ? false : Boolean(
        original.imageInherited ?? original.source?.imageInherited
          ?? canonicalProductSkuImageSource(original.imageSource || original.source?.imageSource) === "PRODUCT_MAIN"
      ),
      status: original.status || null,
      entrySource,
      sortOrder: index
    };
  });
}

function productSkuPatchCompleteness(skus) {
  const current = productSkuCompletenessMeta();
  const manuallyConfirmed = document.querySelector("#product-sku-manual-confirm").checked;
  if (manuallyConfirmed) {
    return {
      expectedCount: skus.length,
      capturedCount: skus.length,
      hasVariants: current.hasVariants,
      variantDimensionCount: current.variantDimensionCount ?? 0,
      complete: true,
      source: "USER",
      manualConfirmed: true,
      issues: productSkuNonRepairableIssues(current.issues)
    };
  }
  if (!productSkuCompletenessDirty && current.known) {
    return {
      expectedCount: current.expectedCount,
      capturedCount: current.capturedCount,
      hasVariants: current.hasVariants,
      variantDimensionCount: current.variantDimensionCount ?? 0,
      complete: current.complete,
      source: current.source,
      issues: current.issues
    };
  }
  return {
    expectedCount: current.expectedCount,
    capturedCount: skus.filter(sku => productSkuEntrySource(sku) !== "MANUAL").length,
    hasVariants: current.hasVariants,
    variantDimensionCount: current.variantDimensionCount ?? 0,
    complete: false,
    source: "USER",
    issues: current.issues
  };
}

function addProductSku() {
  const capture = selectedProductCaptureDetail;
  if (!capture || !hasAnyPermission("product:edit") || capture.status === "PUBLISHED") return;
  ensureProductSkuEditorState(capture);
  syncCurrentProductSkuDraftFromEditor();
  const skus = productCaptureSkus(capture);
  const skuCompleteness = productSkuCompletenessMeta(capture);
  productSkuCompletenessDirty = true;
  productSkuDraftDirty = true;
  productEditorDirty = true;
  const newSku = {
    id: null,
    externalSkuId: null,
    sellerSku: "",
    variationName: skus.length ? `规格 ${skus.length + 1}` : "默认规格",
    price: capture.priceMin ?? null,
    originalPrice: null,
    originalPriceSource: "USER",
    stock: null,
    attributes: {},
    imageUrl: null,
    imageSource: "USER",
    imageInherited: false,
    entrySource: "MANUAL_LISTING",
    sortOrder: skus.length
  };
  selectedProductCaptureDetail = {
    ...capture,
    completeness: "NEEDS_ENRICHMENT",
    skuCompleteness: {
      expectedCount: skuCompleteness.expectedCount,
      capturedCount: skuCompleteness.capturedCount,
      hasVariants: skuCompleteness.hasVariants,
      variantDimensionCount: skuCompleteness.variantDimensionCount ?? 0,
      complete: false,
      source: "USER",
      issues: skuCompleteness.issues
    },
    skus: [...skus, newSku]
  };
  productSkuDraftState.push(createProductSkuEditorDraft(newSku));
  selectedProductSkuIndex = productSkuDraftState.length - 1;
  productSkuSearchQuery = "";
  renderProductSkus(selectedProductCaptureDetail);
  renderProductReadiness(selectedProductCaptureDetail);
  syncProductEditorAccess(selectedProductCaptureDetail);
  renderProductSkuCompleteness(selectedProductCaptureDetail);
  syncProductActions(selectedProductCaptureDetail);
  document.querySelector("#product-sku-editor [data-product-sku-field=\"variationName\"]")?.focus();
}

function removeProductSku(index) {
  const capture = selectedProductCaptureDetail;
  if (!capture || !hasAnyPermission("product:edit") || capture.status === "PUBLISHED") return;
  ensureProductSkuEditorState(capture);
  syncCurrentProductSkuDraftFromEditor();
  const skus = productCaptureSkus(capture).filter((_sku, skuIndex) => skuIndex !== index);
  const skuCompleteness = productSkuCompletenessMeta(capture);
  productSkuCompletenessDirty = true;
  productSkuDraftDirty = true;
  productEditorDirty = true;
  productSkuDraftState.splice(index, 1);
  selectedProductSkuIndex = Math.min(index, Math.max(0, productSkuDraftState.length - 1));
  if (productSkuSearchQuery && !filteredProductSkuIndexes().length) productSkuSearchQuery = "";
  selectedProductCaptureDetail = {
    ...capture,
    completeness: "NEEDS_ENRICHMENT",
    skuCompleteness: {
      expectedCount: skuCompleteness.expectedCount,
      capturedCount: skus.filter(sku => productSkuEntrySource(sku) !== "MANUAL").length,
      hasVariants: skuCompleteness.hasVariants,
      variantDimensionCount: skuCompleteness.variantDimensionCount ?? 0,
      complete: false,
      source: "USER",
      issues: skuCompleteness.issues
    },
    skus
  };
  renderProductSkus(selectedProductCaptureDetail);
  renderProductReadiness(selectedProductCaptureDetail);
  syncProductEditorAccess(selectedProductCaptureDetail);
  renderProductSkuCompleteness(selectedProductCaptureDetail);
  syncProductActions(selectedProductCaptureDetail);
}

function selectProductSku(index, { focusField = "" } = {}) {
  ensureProductSkuEditorState();
  syncCurrentProductSkuDraftFromEditor();
  if (!Number.isSafeInteger(index) || !productSkuDraftState[index]) return;
  selectedProductSkuIndex = index;
  renderProductSkuSelector();
  renderCurrentProductSkuEditor();
  if (selectedProductCaptureDetail) syncProductEditorAccess(selectedProductCaptureDetail);
  if (focusField) document.querySelector(`#product-sku-editor [data-product-sku-field="${focusField}"]`)?.focus();
}

function stepProductSku(direction, { focusField = "" } = {}) {
  const indexes = filteredProductSkuIndexes();
  const position = indexes.indexOf(selectedProductSkuIndex);
  const nextPosition = position + direction;
  if (position < 0 || nextPosition < 0 || nextPosition >= indexes.length) return;
  selectProductSku(indexes[nextPosition], { focusField });
}

function productCapturePatchPayload(capture = selectedProductCaptureDetail, { forceSkus = false } = {}) {
  const version = Number(capture?.version);
  if (!Number.isSafeInteger(version) || version < 0) {
    throw new ApiRequestError("商品版本信息缺失，请刷新后重试", 409);
  }
  const payload = {
    version,
    title: document.querySelector("#product-title-input").value.trim(),
    description: document.querySelector("#product-description-input").value.trim(),
    categoryName: document.querySelector("#product-category-input").value.trim(),
    brandName: document.querySelector("#product-brand-input").value.trim(),
    currency: document.querySelector("#product-currency-input").value.trim().toUpperCase(),
    priceMin: nullableProductValue(document.querySelector("#product-price-min-input").value),
    priceMax: nullableProductValue(document.querySelector("#product-price-max-input").value)
  };
  if (forceSkus || productSkuDraftDirty || productSkuCompletenessDirty) {
    const skus = productSkuDrafts();
    payload.skus = skus;
    payload.skuCompleteness = productSkuPatchCompleteness(skus);
    const prices = skus.map(sku => ({ value: sku.price, number: Number(sku.price) }))
      .filter(price => price.value !== null && Number.isFinite(price.number) && price.number >= 0)
      .sort((left, right) => left.number - right.number);
    if (prices.length) {
      payload.priceMin = String(prices[0].value);
      payload.priceMax = String(prices[prices.length - 1].value);
      document.querySelector("#product-price-min-input").value = payload.priceMin;
      document.querySelector("#product-price-max-input").value = payload.priceMax;
    }
  }
  return payload;
}

function productSkuPriceNormalizationNeeded(capture = selectedProductCaptureDetail) {
  const skus = productCaptureSkus(capture);
  const prices = skus.map(sku => Number(sku.price)).filter(Number.isFinite);
  if (!prices.length) return false;
  if (skus.some(sku => {
    const price = Number(sku.price);
    const originalPrice = Number(sku.originalPrice);
    const originalMissing = sku.originalPrice === null || sku.originalPrice === undefined || sku.originalPrice === "";
    return Number.isFinite(price) && (originalMissing || !Number.isFinite(originalPrice) || originalPrice < price);
  })) return true;
  const rangeMissing = capture?.priceMin === null || capture?.priceMin === undefined || capture?.priceMin === ""
    || capture?.priceMax === null || capture?.priceMax === undefined || capture?.priceMax === "";
  return rangeMissing || Number(capture.priceMin) !== Math.min(...prices)
    || Number(capture.priceMax) !== Math.max(...prices);
}

async function normalizeProductCaptureForUpload(capture, isCurrent = () => true, requestApi = productCaptureApi) {
  const requireCurrent = () => {
    if (!isCurrent()) throw new ApiRequestError("当前企业已切换，已停止商品价格同步", 409);
  };
  requireCurrent();
  if (!["CAPTURED", "FAILED", "READY"].includes(String(capture?.status || "").toUpperCase())
      || !productSkuPriceNormalizationNeeded(capture)) return capture;
  if (!hasAnyPermission("product:edit")) {
    throw new ApiRequestError("SKU 原价或商品价格区间需要自动补齐，但账号没有商品编辑权限", 403);
  }
  const version = Number(capture.version);
  if (!Number.isSafeInteger(version) || version < 0 || !capture.id) {
    throw new ApiRequestError("商品版本信息缺失，请刷新后重试", 409);
  }
  const sourceSkus = productCaptureSkus(capture);
  const prices = sourceSkus.map(sku => nullableProductValue(sku.price))
    .filter(value => value !== null && Number.isFinite(Number(value)) && Number(value) >= 0)
    .sort((left, right) => Number(left) - Number(right));
  if (!prices.length) return capture;
  let originalsChanged = false;
  const skus = sourceSkus.map(sku => {
    const price = nullableProductValue(sku.price);
    const originalPrice = nullableProductValue(sku.originalPrice);
    const derive = price !== null && Number.isFinite(Number(price)) && Number(price) >= 0
      && (originalPrice === null || !Number.isFinite(Number(originalPrice))
        || Number(originalPrice) < Number(price));
    originalsChanged ||= derive;
    return {
      ...productSkuPreservedFields(sku),
      attributes: productSkuAttributes(sku),
      ...(derive ? { originalPrice: price, originalPriceSource: "DERIVED" } : {})
    };
  });
  // Read this capture only: the detail editor may currently display another product.
  // A range-only repair does not need to rewrite SKU identities or image associations.
  const payload = { version, priceMin: prices[0], priceMax: prices[prices.length - 1] };
  if (originalsChanged) {
    payload.skus = skus;
    const completeness = capture.skuCompleteness ?? capture.normalizedData?.skuCompleteness;
    if (completeness && typeof completeness === "object" && !Array.isArray(completeness)) {
      payload.skuCompleteness = { ...completeness };
    }
  }
  const path = `/${encodeURIComponent(capture.id)}`;
  let result;
  try {
    requireCurrent();
    result = await requestApi(path, {
      method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload)
    });
  } catch (error) {
    requireCurrent();
    if (error?.status !== 409 && !productPublishWorkflowCreateMayHaveCommitted(error)) throw error;
    // The versioned PATCH may have succeeded before its response was lost. Read it
    // back instead of replaying a mutation that can reset image-processing state.
    try {
      const latest = await requestApi(path);
      requireCurrent();
      const recovered = productCapturePayload(latest.data);
      if (recovered?.id !== capture.id || productSkuPriceNormalizationNeeded(recovered)) throw error;
      return recovered;
    } catch (refreshError) {
      if (refreshError?.status === 401 || !isCurrent()) throw refreshError;
      throw error;
    }
  }
  requireCurrent();
  const updated = productCapturePayload(result.data);
  if (updated?.id !== capture.id) throw new ApiRequestError("商品价格同步响应不完整，请刷新后重试", 502, result);
  return updated;
}

async function saveProductCapture() {
  if (!selectedProductCaptureId || !hasAnyPermission("product:edit") || productCaptureSaveInFlightId) return;
  const requestCaptureId = selectedProductCaptureId;
  const requestCapture = selectedProductCaptureDetail;
  showProductNotice("");
  try {
    const payload = productCapturePatchPayload(requestCapture);
    productCaptureSaveInFlightId = requestCaptureId;
    if (selectedProductCaptureId === requestCaptureId && selectedProductCaptureDetail) {
      syncProductEditorAccess(selectedProductCaptureDetail);
      syncProductActions(selectedProductCaptureDetail);
    }
    const result = await productCaptureApi(`/${encodeURIComponent(requestCaptureId)}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const capture = productCapturePayload(result.data);
    productCaptureDetailLoadGeneration += 1;
    productLastNotice = { captureId: capture.id, type: "success", message: "商品资料已保存；若内容发生变化，请重新执行人工确认。" };
    updateProductCaptureInMemory(capture);
  } catch (error) {
    if (error?.status === 409) {
      productLastNotice = {
        captureId: requestCaptureId,
        type: "error",
        message: "商品已被其他操作更新，已载入服务器最新版本；请重新核对后再保存。"
      };
      if (selectedProductCaptureId === requestCaptureId) {
        await selectProductCapture(requestCaptureId, { silent: true, force: true });
      }
    } else if (error?.status !== 401) showProductNotice(error.message);
  } finally {
    if (productCaptureSaveInFlightId === requestCaptureId) productCaptureSaveInFlightId = null;
    if (selectedProductCaptureDetail) {
      syncProductEditorAccess(selectedProductCaptureDetail);
      syncProductActions(selectedProductCaptureDetail);
    }
  }
}

async function processProductImages() {
  if (!selectedProductCaptureId || !hasAnyPermission("asset:watermark")) return;
  const button = document.querySelector("#process-product-images");
  button.disabled = true;
  button.textContent = "正在提交…";
  showProductNotice("");
  try {
    const idempotencyKey = `images-${selectedProductCaptureId}-${newProductIdempotencyKey()}`;
    const result = await productCaptureApi(`/${encodeURIComponent(selectedProductCaptureId)}/images/process`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({ idempotencyKey })
    });
    const capture = productCapturePayload(result.data);
    const job = result.data?.job || result.data?.processingJob || {};
    const provider = job.provider || result.data?.provider || "";
    const responseDryRun = job.dryRun ?? result.data?.dryRun;
    const dryRun = typeof responseDryRun === "boolean"
      ? responseDryRun
      : /dry.?run/i.test(String(provider));
    if (capture?.id) {
      productImageProcessingMeta.set(capture.id, {
        provider,
        dryRun
      });
    }
    productLastNotice = {
      captureId: selectedProductCaptureId,
      type: "success",
      message: dryRun
        ? capture?.status === "READY"
          ? "图片处理 Dry Run 已完成；当前结果仅为占位图，尚未实际去除水印。"
          : "图片处理 Dry Run 已提交，页面会自动刷新处理进度。"
        : capture?.status === "READY"
          ? `图片处理已由${provider ? ` ${provider}` : "正式提供方"}完成，请核对实际去水印结果。`
          : `图片处理已提交到${provider ? ` ${provider}` : "正式提供方"}，页面会自动刷新处理进度。`
    };
    if (capture?.id) updateProductCaptureInMemory(capture);
    await loadProductCaptures(selectedProductCaptureId, { silent: true });
  } catch (error) {
    if (error?.status !== 401) showProductNotice(error.message);
  } finally {
    if (selectedProductCaptureDetail) syncProductActions(selectedProductCaptureDetail);
  }
}

async function approveProductCapture() {
  if (!selectedProductCaptureId || !hasAnyPermission("product:edit")) return;
  if (!window.confirm("确认商品资料、SKU 与全部处理图片均已核对无误？确认后商品将进入可发布状态。")) return;
  const button = document.querySelector("#approve-product-capture");
  button.disabled = true;
  button.textContent = "正在确认…";
  showProductNotice("");
  try {
    const result = await productCaptureApi(`/${encodeURIComponent(selectedProductCaptureId)}/approve`, { method: "POST" });
    const capture = productCapturePayload(result.data);
    const provider = String(marketplaceIntegrationStatus.publishingProvider || "");
    const officialPublishing = marketplaceIntegrationStatus.livePublishingEnabled && !/dry.?run/i.test(provider);
    productLastNotice = {
      captureId: capture.id,
      type: "success",
      message: officialPublishing
        ? "人工确认已记录，现在可以选择目标店铺执行官方发布。"
        : "人工确认已记录，现在可以选择目标店铺执行 Dry Run 发布。"
    };
    updateProductCaptureInMemory(capture);
  } catch (error) {
    if (error?.status !== 401) showProductNotice(error.message);
  } finally {
    if (selectedProductCaptureDetail) syncProductActions(selectedProductCaptureDetail);
  }
}

function newProductIdempotencyKey() {
  return globalThis.crypto?.randomUUID?.()
    || `product_${Date.now().toString(36)}_${Math.random().toString(36).slice(2)}`;
}

function productPublishWorkflowStorageKey(captureId, organizationId = currentUser?.organizationId || "") {
  if (!captureId || !organizationId) return "";
  return `${PRODUCT_PUBLISH_WORKFLOW_STORAGE_PREFIX}:${organizationId}:${captureId}`;
}

function storedProductPublishWorkflowId(captureId, organizationId = currentUser?.organizationId || "") {
  const key = productPublishWorkflowStorageKey(captureId, organizationId);
  if (!key) return "";
  try {
    return String(localStorage.getItem(key) || "").trim();
  } catch (_error) {
    return "";
  }
}

function rememberProductPublishWorkflow(workflow, organizationId = currentUser?.organizationId || "") {
  const key = productPublishWorkflowStorageKey(workflow?.captureId, organizationId);
  if (!key || !workflow?.id) return;
  try {
    localStorage.setItem(key, String(workflow.id));
  } catch (_error) {
    // The list endpoint remains the recovery fallback when storage is unavailable.
  }
}

function forgetProductPublishWorkflow(captureId, organizationId = currentUser?.organizationId || "") {
  const key = productPublishWorkflowStorageKey(captureId, organizationId);
  if (!key) return;
  try {
    localStorage.removeItem(key);
  } catch (_error) {
    // A terminal workflow is ignored by list recovery even if this key cannot be removed.
  }
}

function productPublishWorkflowIsActive(workflow) {
  return ["PENDING", "RUNNING"].includes(String(workflow?.status || "").toUpperCase());
}

function productPublishWorkflowMayHaveReachedPlatform(workflow) {
  return Boolean(workflow?.publicationAttempted)
    && String(workflow?.executionMode || "").toUpperCase() === "LIVE"
    && workflow?.publication?.dryRun !== true;
}

function productPublishWorkflowForCapture(captureId) {
  return productPublishWorkflowState?.captureId === captureId ? productPublishWorkflowState : null;
}

function productPublishWorkflowPlatform(workflow) {
  return normalizedMarketplacePlatform(workflow?.destinationPlatform || "SHOPEE");
}

function productPublishRemoteStatus(workflow) {
  return String(workflow?.publication?.remoteStatus || "").trim().toUpperCase();
}

function productPublishOutcome(workflow) {
  const audit = workflow?.publication || {};
  const live = String(workflow?.executionMode || "").toUpperCase() === "LIVE"
    && audit.dryRun === false;
  if (!live) {
    return {
      kind: "dry-run",
      remoteStatus: "",
      stepText: "Dry Run 通过",
      buttonText: "Dry Run 流程已完成"
    };
  }
  const remoteStatus = productPublishRemoteStatus(workflow);
  if (["UNLIST", "DRAFT"].includes(remoteStatus)) return { kind: "unlisted", remoteStatus, stepText: "已上传至待上架", buttonText: "已上传 · 待上架" };
  if (["REVIEWING", "PENDING"].includes(remoteStatus)) {
    return {
      kind: "reviewing",
      remoteStatus,
      stepText: "已提交，平台审核中",
      buttonText: "平台审核中"
    };
  }
  if (["NORMAL", "ACTIVATE"].includes(remoteStatus)) {
    return {
      kind: "listed",
      remoteStatus,
      stepText: "平台已确认上架",
      buttonText: "已上架"
    };
  }
  return {
    kind: "submitted",
    remoteStatus,
    stepText: "已提交，状态待确认",
    buttonText: "平台状态待确认"
  };
}

function productPublishWorkflowError(workflow) {
  return String(workflow?.errorMessage
    || workflow?.imageProcessing?.errorMessage
    || workflow?.publication?.errorMessage
    || "一键上传后台任务执行失败").trim();
}

function stopProductPublishWorkflowPolling({ clearState = false } = {}) {
  productPublishWorkflowPollGeneration += 1;
  if (productPublishWorkflowPollTimer) clearTimeout(productPublishWorkflowPollTimer);
  productPublishWorkflowPollTimer = null;
  if (!clearState) return;
  const trackedCaptureId = productPublishWorkflowState?.captureId || productUploadInFlightCaptureId;
  productPublishWorkflowState = null;
  if (!trackedCaptureId || productUploadInFlightCaptureId === trackedCaptureId) {
    productUploadInFlightCaptureId = "";
  }
}

function renderProductPublishWorkflow(workflow) {
  if (!workflow) return;
  productUploadResultByCapture.set(`${currentUser?.organizationId}:${workflow.captureId}`, workflow);
  if (selectedProductCaptureDetail?.id === workflow.captureId) renderProductUploadResult(selectedProductCaptureDetail);
  const status = String(workflow.status || "").toUpperCase();
  const requestedStep = String(workflow.currentStep || "IMAGES").toUpperCase();
  const steps = ["IMAGES", "APPROVE", "PUBLISH"];
  const currentStep = requestedStep === "STYLE_IMAGES" ? "IMAGES" : steps.includes(requestedStep)
    ? requestedStep : status === "SUCCEEDED" ? "PUBLISH" : "IMAGES";
  const currentIndex = steps.indexOf(currentStep);
  const outcome = productPublishOutcome(workflow);
  const completeText = { IMAGES: "处理完成", APPROVE: "校验通过", PUBLISH: "上传完成" };
  const waitingText = { IMAGES: "等待后台任务", APPROVE: "等待上一步", PUBLISH: "等待上一步" };
  const runningText = {
    IMAGES: requestedStep === "STYLE_IMAGES" ? `风格主图 ${workflow.mainImageStyle?.processedImages || 0} / ${workflow.mainImageStyle?.totalImages || 0}`
      : status === "PENDING" ? "等待后台处理图片" : "后台正在处理图片",
    APPROVE: status === "PENDING" ? "等待后台校验" : "后台正在校验审核",
    PUBLISH: status === "PENDING"
      ? "等待后台上传"
      : marketplaceIntegrationStatus.livePublishingEnabled ? "正在上传官方店铺" : "正在执行 Dry Run"
  };
  steps.forEach((step, index) => {
    if (status === "SUCCEEDED") {
      setProductUploadStep(step, "complete", step === "PUBLISH"
        ? outcome.stepText
        : completeText[step]);
    } else if (status === "FAILED" && index === currentIndex) {
      setProductUploadStep(step, "failed", "失败，请查看原因");
    } else if (index < currentIndex) {
      setProductUploadStep(step, "complete", completeText[step]);
    } else if (index === currentIndex && productPublishWorkflowIsActive(workflow)) {
      setProductUploadStep(step, "running", runningText[step]);
    } else {
      setProductUploadStep(step, "", waitingText[step]);
    }
  });
  const stepsElement = document.querySelector('#product-upload-steps');
  if (stepsElement) {
    let styleProgress = document.querySelector('#product-publish-main-image-progress');
    if (!styleProgress) {
      styleProgress = document.createElement('div');
      styleProgress.id = 'product-publish-main-image-progress';
      styleProgress.setAttribute('aria-live', 'polite');
      stepsElement.after(styleProgress);
    }
    styleProgress.hidden = !workflow.mainImageStyle;
    replaceHtmlPreservingMedia(styleProgress, renderProductMainImageProgress(workflow));
  }
  const button = document.querySelector("#submit-product-publish");
  if (!button) return;
  if (productPublishWorkflowIsActive(workflow)) {
    button.textContent = status === "PENDING" ? "后台任务排队中…" : "后台任务执行中…";
  } else if (status === "SUCCEEDED") {
    button.textContent = outcome.buttonText;
  } else if (productPublishWorkflowMayHaveReachedPlatform(workflow)) {
    button.textContent = "上传结果需人工核查";
  } else {
    button.textContent = marketplaceIntegrationStatus.livePublishingEnabled
      ? "重新确认并一键上传" : "重新确认并执行 Dry Run";
  }
}

async function refreshProductCaptureAfterWorkflow(captureId, organizationId) {
  try {
    const result = await productCaptureApi(`/${encodeURIComponent(captureId)}`);
    if (!currentUser || currentUser.organizationId !== organizationId) return;
    const capture = productCapturePayload(result.data);
    if (capture?.id) updateProductCaptureInMemory(capture);
  } catch (error) {
    if (error?.status !== 401 && selectedProductCaptureId === captureId) {
      showProductNotice("上传流程已结束，但商品状态暂时刷新失败，请稍后刷新列表。", "error");
    }
  }
}

async function finalizeProductPublishWorkflow(workflow, organizationId) {
  if (!workflow?.id || finalizedProductPublishWorkflowIds.has(workflow.id)) return;
  finalizedProductPublishWorkflowIds.add(workflow.id);
  stopProductPublishWorkflowPolling();
  forgetProductPublishWorkflow(workflow.captureId, organizationId);
  if (productUploadInFlightCaptureId === workflow.captureId) productUploadInFlightCaptureId = "";
  const succeeded = String(workflow.status || "").toUpperCase() === "SUCCEEDED";
  const audit = workflow.publication || {};
  const errorLabel = document.querySelector("#product-publish-error");
  const dialog = document.querySelector("#product-publish-dialog");
  if (succeeded) {
    const outcome = productPublishOutcome(workflow);
    const dryRun = outcome.kind === "dry-run";
    const receipt = audit.externalId ? `，平台回执 ${audit.externalId}` : "";
    productPublishAuditMeta.set(workflow.captureId, {
      dryRun,
      status: audit.status || "",
      externalId: audit.externalId || "",
      remoteStatus: outcome.remoteStatus
    });
    productLastNotice = {
      captureId: workflow.captureId,
      type: "success",
      message: outcome.kind === "dry-run"
        ? `Dry Run 已通过${audit.externalId ? `，模拟回执 ${audit.externalId}` : ""}；未向真实店铺提交。`
        : outcome.kind === "reviewing"
          ? `商品已提交，平台正在审核${receipt}；当前不是“已上架”。`
          : outcome.kind === "listed"
            ? `平台已确认商品上架${receipt}。`
            : `商品已提交${receipt}，但平台状态尚未确认；请到 Seller Center 核查。`
    };
    if (dialog?.open) {
      errorLabel.classList.add("is-success");
      errorLabel.textContent = outcome.kind === "dry-run"
        ? "三步流程已完成；本次为 Dry Run，没有向真实店铺提交。"
        : outcome.kind === "reviewing"
          ? "商品已提交到所选店铺，平台正在审核；审核通过前不会显示为已上架。"
          : outcome.kind === "listed"
            ? "平台已回读并确认商品处于上架状态。"
            : "商品已提交到所选店铺，但平台状态尚未确认，请到 Seller Center 核查。";
      errorLabel.hidden = false;
    }
    productPublishIdempotencyKey = "";
    productPublishIdempotencyCaptureId = "";
  } else {
    const message = productPublishWorkflowError(workflow);
    const stageName = ({ IMAGES: "图片去水印", STYLE_IMAGES: "风格主图生成", APPROVE: "资料校验与审核", PUBLISH: "上传店铺" })[
      String(workflow.currentStep || "").toUpperCase()] || "一键上传";
    productLastNotice = { captureId: workflow.captureId, type: "error", message: `${stageName}失败：${message}` };
    if (dialog?.open) {
      errorLabel.classList.remove("is-success");
      errorLabel.textContent = productPublishWorkflowMayHaveReachedPlatform(workflow)
        ? `${stageName}失败：${message}。平台请求可能已经发出，为防止重复刊登，系统不会自动重试；请先到目标店铺核查。`
        : `${stageName}失败：${message}`;
      errorLabel.hidden = false;
    }
    productPublishIdempotencyKey = newProductIdempotencyKey();
    productPublishIdempotencyCaptureId = workflow.captureId;
  }
  renderProductPublishWorkflow(workflow);
  if (dialog?.open) {
    setProductUploadFormBusy(false);
    const button = document.querySelector("#submit-product-publish");
    button.disabled = succeeded || productPublishWorkflowMayHaveReachedPlatform(workflow);
  }
  if (productPublishWorkflowState?.id === workflow.id) productPublishWorkflowState = null;
  await refreshProductCaptureAfterWorkflow(workflow.captureId, organizationId);
  if (selectedProductCaptureDetail?.id === workflow.captureId) {
    renderProductPublishWorkflow(workflow);
    syncProductEditorAccess(selectedProductCaptureDetail);
    syncProductActions(selectedProductCaptureDetail);
  }
}

function acceptProductPublishWorkflow(workflow, organizationId) {
  if (!workflow?.id || !workflow.captureId || !currentUser
      || currentUser.organizationId !== organizationId
      || selectedProductCaptureId !== workflow.captureId) return false;
  productPublishWorkflowState = workflow;
  const dialog = document.querySelector("#product-publish-dialog");
  if (dialog?.open) {
    const platform = productPublishWorkflowPlatform(workflow);
    document.querySelector("#publish-destination-platform").value = platform;
    syncPublishShopOptions(String(workflow.shopId || ""), { requireExplicit: true });
  }
  renderProductPublishWorkflow(workflow);
  if (productPublishWorkflowIsActive(workflow)) {
    productUploadInFlightCaptureId = workflow.captureId;
    rememberProductPublishWorkflow(workflow, organizationId);
    if (dialog?.open) setProductUploadFormBusy(true);
  } else {
    if (productUploadInFlightCaptureId === workflow.captureId) productUploadInFlightCaptureId = "";
    void finalizeProductPublishWorkflow(workflow, organizationId);
  }
  if (selectedProductCaptureDetail?.id === workflow.captureId) {
    syncProductEditorAccess(selectedProductCaptureDetail);
    syncProductActions(selectedProductCaptureDetail);
  }
  return true;
}

function pollProductPublishWorkflow(workflow) {
  if (!productPublishWorkflowIsActive(workflow)) return;
  if (productPublishWorkflowPollTimer) clearTimeout(productPublishWorkflowPollTimer);
  const generation = ++productPublishWorkflowPollGeneration;
  const organizationId = currentUser?.organizationId || "";
  const captureId = workflow.captureId;
  const workflowId = workflow.id;
  const poll = async () => {
    if (generation !== productPublishWorkflowPollGeneration
        || !currentUser || currentUser.organizationId !== organizationId
        || selectedProductCaptureId !== captureId) return;
    let delay = document.hidden ? 5000 : 1500;
    try {
      const result = await api(productPublishWorkflowPath(captureId, workflowId));
      if (generation !== productPublishWorkflowPollGeneration
          || !currentUser || currentUser.organizationId !== organizationId
          || selectedProductCaptureId !== captureId) return;
      const latest = result.data;
      if (!acceptProductPublishWorkflow(latest, organizationId)) return;
      if (!productPublishWorkflowIsActive(latest)) return;
    } catch (error) {
      if (generation !== productPublishWorkflowPollGeneration) return;
      if (error?.status === 401) return;
      if (error?.status === 404) {
        forgetProductPublishWorkflow(captureId, organizationId);
        stopProductPublishWorkflowPolling({ clearState: true });
        if (selectedProductCaptureDetail?.id === captureId) {
          syncProductEditorAccess(selectedProductCaptureDetail);
          syncProductActions(selectedProductCaptureDetail);
        }
        return;
      }
      delay = 3500;
      document.querySelectorAll('#product-upload-result .main-style-workflow-connection, #product-publish-main-image-progress .main-style-workflow-connection').forEach(label => {
        label.textContent = '进度连接中断，正在重连；以上为最近一次状态';
        label.hidden = false;
      });
      const errorLabel = document.querySelector("#product-publish-error");
      if (document.querySelector("#product-publish-dialog")?.open) {
        errorLabel.classList.remove("is-success");
        errorLabel.textContent = "进度读取暂时失败，后台任务仍会继续；系统正在自动重连。";
        errorLabel.hidden = false;
      }
    }
    if (generation === productPublishWorkflowPollGeneration) {
      productPublishWorkflowPollTimer = window.setTimeout(poll, delay);
    }
  };
  productPublishWorkflowPollTimer = window.setTimeout(poll, 800);
}

async function restoreProductPublishWorkflow(captureId) {
  if (!captureId || !currentUser || !hasAnyPermission("product:view", "product:publish")) return;
  if (productPublishWorkflowIsActive(productPublishWorkflowForCapture(captureId))
      && productPublishWorkflowPollTimer) return;
  const generation = ++productPublishWorkflowLookupGeneration;
  const organizationId = currentUser.organizationId || "";
  let workflow = null;
  const storedId = storedProductPublishWorkflowId(captureId, organizationId);
  if (storedId) {
    try {
      const result = await api(productPublishWorkflowPath(captureId, storedId));
      workflow = result.data;
    } catch (error) {
      if (error?.status === 401) return;
      if (error?.status !== 404) return;
      forgetProductPublishWorkflow(captureId, organizationId);
    }
  }
  if (!workflow) {
    try {
      const result = await api(`${productPublishWorkflowPath(captureId)}?limit=20`);
      const workflows = Array.isArray(result.data) ? result.data : [];
      workflow = workflows.find(productPublishWorkflowIsActive) || workflows[0] || null;
    } catch (error) {
      if (error?.status !== 401) {
        // Product detail remains usable even when workflow recovery is temporarily unavailable.
      }
      return;
    }
  }
  if (generation !== productPublishWorkflowLookupGeneration
      || !currentUser || currentUser.organizationId !== organizationId
      || selectedProductCaptureId !== captureId) return;
  if (!workflow) {
    if (productPublishWorkflowState?.captureId === captureId) productPublishWorkflowState = null;
    if (productUploadInFlightCaptureId === captureId) productUploadInFlightCaptureId = "";
    if (selectedProductCaptureDetail?.id === captureId) {
      syncProductEditorAccess(selectedProductCaptureDetail);
      syncProductActions(selectedProductCaptureDetail);
    }
    return;
  }
  if (!acceptProductPublishWorkflow(workflow, organizationId)) return;
  if (productPublishWorkflowIsActive(workflow)) pollProductPublishWorkflow(workflow);
}

function syncPublishShopOptions(preferredShopId = "", {
  requireExplicit = String(selectedProductCaptureDetail?.status || "").toUpperCase() === "PUBLISHED"
} = {}) {
  const select = document.querySelector("#publish-shop-id");
  if (!select) return;
  const platform = document.querySelector("#publish-destination-platform")?.value || "SHOPEE";
  const shops = activeMarketplaceShops(platform).sort((left, right) => {
    const regionDifference = String(left.region || "").localeCompare(String(right.region || ""), "zh-CN");
    if (regionDifference) return regionDifference;
    return String(left.name || left.externalShopId || "").localeCompare(
      String(right.name || right.externalShopId || ""), "zh-CN");
  });
  const previous = preferredShopId || select.value;
  const placeholder = document.createElement("option");
  placeholder.value = "";
  placeholder.textContent = shops.length ? `请选择 ${shops.length} 家可发布店铺中的一家` : "没有可发布的授权店铺";
  select.replaceChildren(placeholder);
  const regionGroups = new Map();
  shops.forEach(shop => {
    const region = shop.region || "地区未返回";
    if (!regionGroups.has(region)) regionGroups.set(region, []);
    regionGroups.get(region).push(shop);
  });
  regionGroups.forEach((regionShops, region) => {
    const group = document.createElement("optgroup");
    group.label = `${region} · ${regionShops.length} 家`;
    regionShops.forEach(shop => {
      const option = document.createElement("option");
      option.value = String(shop.id || "");
      option.textContent = `${shop.name || shop.externalShopId || "未命名店铺"} · ID ${shop.externalShopId || "未返回"}`;
      group.append(option);
    });
    select.append(group);
  });
  const dryRun = !marketplaceIntegrationStatus.livePublishingEnabled;
  if (dryRun) {
    const dryRunGroup = document.createElement("optgroup");
    dryRunGroup.label = "安全演练";
    const dryRunOption = document.createElement("option");
    dryRunOption.value = "dry-run-shop";
    dryRunOption.textContent = "仅执行 Dry Run（不连接店铺）";
    dryRunGroup.append(dryRunOption);
    select.append(dryRunGroup);
  }
  const preferredShop = shops.find(shop => String(shop.id) === String(previous)
    || String(shop.externalShopId) === String(previous));
  if (preferredShop) select.value = String(preferredShop.id);
  else if (previous && [...select.options].some(option => option.value === previous)) select.value = previous;
  else if (shops.length === 1 && !requireExplicit) select.value = shops[0].id;
  else if (!shops.length && dryRun) select.value = "dry-run-shop";
  const count = document.querySelector("#publish-shop-count");
  if (count) count.textContent = `${shops.length} 家可选`;
  syncPublishShopContext();
}

function syncPublishShopContext() {
  const select = document.querySelector("#publish-shop-id");
  const context = document.querySelector("#publish-shop-context");
  if (!select || !context) return;
  const platform = document.querySelector("#publish-destination-platform")?.value || "SHOPEE";
  const shops = activeMarketplaceShops(platform);
  const republishing = String(selectedProductCaptureDetail?.status || "").toUpperCase() === "PUBLISHED";
  if (select.value === "dry-run-shop") {
    context.textContent = "本次仅校验并保存模拟回执，不会使用任何店铺授权。";
    context.className = "is-dry-run";
    return;
  }
  const selected = shops.find(shop => String(shop.id) === String(select.value));
  if (!selected) {
    context.textContent = shops.length > 1
      ? `当前有 ${shops.length} 家 ${marketplacePlatformName(platform)} 店铺，请根据地区和外部 ID 确认目标，避免错发。`
      : shops.length === 1
        ? republishing
          ? "此商品已有平台提交记录，请明确选择目标店铺；当前页面无法根据商品状态判断此前提交的是哪一家。"
          : "已自动选择唯一可发布店铺。"
        : `没有状态为“可发布”的 ${marketplacePlatformName(platform)} 店铺，请先授权或刷新失效授权。`;
    context.className = shops.length ? "" : "is-warning";
    return;
  }
  const prefix = republishing ? "继续提交目标 · " : "";
  context.textContent = `${prefix}${selected.name || selected.externalShopId || "未命名店铺"} · ${selected.region || "地区未返回"} · 外部 ID ${selected.externalShopId || "未返回"} · 最近同步 ${formatMarketplaceShopTime(selected.lastSyncedAt, "尚未同步")}`;
  context.className = "is-selected";
}

let productPublishPrepareVersion = 0;
let productPublishPreparation = null;

function requireTikTokDraftCapability(shop, options) {
  const method = options?.listingMethod;
  const crossBorder = String(shop?.sellerType || '').toUpperCase() === 'CROSS_BORDER';
  if (options?.canCreateDraft !== true || !['LOCAL', 'LOCAL_REPLICATION'].includes(method)
      || (crossBorder && method !== 'LOCAL_REPLICATION')) {
    const error = Error(crossBorder
      ? '跨境刊登模式未就绪：当前店铺尚未确认支持上传到待上架草稿，请重新读取店铺配置或切换店铺'
      : '当前 TikTok 店铺尚未确认支持上传到待上架草稿，请重新读取店铺配置或切换店铺');
    error.draftCapabilityBlocked = true;
    throw error;
  }
  return method;
}

function productPublishPreparationError(platform, shopId) {
  if (platform !== 'TIKTOK_SHOP' || !marketplaceIntegrationStatus.livePublishingEnabled) return '';
  const state = productPublishPreparation;
  if (!state || state.platform !== platform || state.shopId !== shopId
      || state.captureId !== selectedProductCaptureDetail?.id || state.organizationId !== currentUser?.organizationId
      || state.version !== productPublishPrepareVersion) return '目标店铺已变化，请等待重新读取当前店铺配置后再提交';
  if (state.status === 'READY' || (state.status === 'LOCAL_FALLBACK' && state.categoryLeaves)) {
    const selected = state.categoryLeaves?.find(category => category.id === document.querySelector('#publish-category-id').value);
    if (!selected) return '请选择当前店铺返回的有效商品类目';
    return tikTokCategoryPermissionError(selected);
  }
  if (state.status === 'LOCAL_FALLBACK') return '';
  return state.error || '正在读取当前 TikTok 店铺的刊登能力，请稍等片刻';
}
const publishReusableFields = ['warehouse-id', 'package-length', 'package-width', 'package-height'];
function publishPreferenceKey(category = false, capture = selectedProductCaptureDetail,
  shop = document.querySelector('#publish-shop-id').value,
  platform = document.querySelector('#publish-destination-platform').value,
  org = currentUser?.organizationId) {
  if (!org || !shop || shop === 'dry-run-shop') return null;
  const module = typeof commerceDataModule === 'function' ? commerceDataModule() : 'CAPTURE';
  const scope = module === 'CAPTURE' ? ['publish-settings-v1',org] : ['publish-settings-v2',org,module];
  return JSON.stringify([...scope, platform, shop, category ? capture?.categoryName || capture?.id : 'shop']);
}
function readPublishPreference(category = false, ...context) {
  try { return JSON.parse(localStorage.getItem(publishPreferenceKey(category, ...context)) || '{}'); } catch { return {}; }
}
function rememberPublishPreferences() {
  if (!marketplaceIntegrationStatus.livePublishingEnabled) return;
  const common = {};
  publishReusableFields.forEach(name => common[name] = document.querySelector(`#publish-${name}`).value);
  common.logistic = document.querySelector('#publish-logistic-choice').value;
  // Weight, price, SKU overrides and product attributes belong to this product, never to the whole shop.
  const category = {category: document.querySelector('#publish-category-id').value};
  try {
    if (publishPreferenceKey()) localStorage.setItem(publishPreferenceKey(), JSON.stringify(common));
    if (publishPreferenceKey(true)) localStorage.setItem(publishPreferenceKey(true), JSON.stringify(category));
  } catch { /* Browser storage is optional; publication must still work. */ }
}
function originalProductWeight(capture) {
  const data = capture?.normalizedData || {};
  for (const value of [capture?.weightGrams, data.weightGrams, data.weight_grams]) {
    if (Number(value) > 0 && Number.isFinite(Number(value))) return String(value);
  }
  const weight = data.packageWeight || data.package_weight;
  const unit = String(weight?.unit || data.weightUnit || '').toLowerCase();
  const value = Number(weight?.value ?? data.weight);
  if (value > 0 && Number.isFinite(value)) {
    if (['g','gram','grams'].includes(unit)) return String(value);
    if (['kg','kilogram','kilograms'].includes(unit)) return String(Math.round(value * 1000));
  }
  return '';
}
function automaticProductPublishDefaults(capture, common = {}, platform = capture?.sourcePlatform || 'SHOPEE') {
  return {
    publishStock: 999,
    weightGrams: originalProductWeight(capture) || '250',
    autoConvertPrice: true,
    packageLengthCm: common['package-length'] || '30',
    packageWidthCm: common['package-width'] || '25',
    packageHeightCm: common['package-height'] || '2',
    skuStocks: {}, productAttributes: [],
    shopeePayload: { brand: { brand_id: 0, original_brand_name: 'NoBrand' } },
    ...(typeof ProductListingTemplates !== 'undefined' ? ProductListingTemplates.fields(ProductListingTemplates.get(platform)) : {})
  };
}

function destinationCategoryLeaves(categories, platform) {
  const shopee = platform === 'SHOPEE';
  const id = c => String(shopee ? c.category_id : c.id);
  const parent = c => String((shopee ? c.parent_category_id : c.parent_id) || '');
  const names = c => [...new Set((shopee
    ? [c.display_category_name, c.original_category_name]
    : [c.local_name, c.name]).filter(Boolean).map(String))];
  const byId = new Map(categories.map(c => [id(c), c]));
  const parentIds = new Set(categories.map(parent).filter(Boolean));
  const permissions = c => c.permission_statuses == null ? [] : Array.isArray(c.permission_statuses)
    ? c.permission_statuses.map(value => String(value).toUpperCase()) : ['UNKNOWN'];
  return categories.filter(c => (shopee ? c.has_children === false : c.is_leaf === true)
    && !parentIds.has(id(c))).map(c => {
    const ancestors = [c], seen = new Set([id(c)]);
    while (parent(c) && byId.has(parent(c)) && !seen.has(parent(c))) {
      c = byId.get(parent(c)); seen.add(id(c)); ancestors.unshift(c);
    }
    const labels = [0, 1].map(index => ancestors.map(node => names(node)[index] || names(node)[0] || id(node)).join(' > '));
    const leaf = ancestors[ancestors.length - 1];
    return {id: id(leaf), label: labels[0], aliases: [...new Set(labels)], leafNames: names(leaf),
      ...(!shopee ? {permissionStatuses: permissions(leaf),
        permissionPath: ancestors.map(node => ({id: id(node), label: names(node)[0] || id(node), permissionStatuses: permissions(node)}))} : {})};
  });
}
function tikTokCategoryPermissionError(category) {
  const path = category.permissionPath || [category];
  const denied = path.find(node => node.permissionStatuses?.some(status => status !== 'AVAILABLE'));
  if (!denied) return '';
  const statuses = denied.permissionStatuses.filter(status => status !== 'AVAILABLE');
  const reason = statuses.includes('NON_MAIN_CATEGORY')
    ? '该类目不在当前店铺已开通的主营类目范围内'
    : `平台未允许当前店铺使用该类目（${statuses.join('、')}）`;
  return `已匹配最适合的类目“${category.label}”（${category.id}），但${reason}。请更换有该类目权限的店铺，或在 TikTok Shop 后台开通权限后重试。`;
}
function requireTikTokCategoryPermission(category) {
  const message = category && tikTokCategoryPermissionError(category);
  if (message) {
    const error = Error(message);
    error.categoryPermissionBlocked = true;
    throw error;
  }
}
function resolveShopeePublishOptions(capture, options, common = {}, category = {}) {
  const leaves = destinationCategoryLeaves(options?.categories?.category_list || [], 'SHOPEE');
  if (!leaves.length) throw Error('官方未返回可用类目');
  const categoryId = matchDestinationCategory(capture, leaves, category);
  const channels = (options?.logistics?.logistics_channel_list || []).filter(c => c.enabled === true);
  const logistic = channels.some(c => String(c.logistics_channel_id) === common.logistic)
    ? common.logistic : channels.length ? String(channels[0].logistics_channel_id) : '';
  const configured = options?.listingLogistics;
  return { leaves, categoryId, matchedOriginal: Boolean(categoryId), channels, logistic,
    logisticsConfigured: Boolean(configured),
    logisticInfo: configured ? ProductListingTemplates.logisticInfo(channels, configured)
      : logistic ? [{ logistic_id: Number(logistic), enabled: true }] : [] };
}

function resolveTikTokPublishOptions(capture, options, common = {}, category = {}) {
  const leaves = destinationCategoryLeaves(options?.categories?.categories || [], 'TIKTOK_SHOP');
  if (!leaves.length) throw Error('官方未返回可用类目');
  let categoryId = matchDestinationCategory(capture, leaves, category);
  const originalMatch = leaves.find(leaf => leaf.id === categoryId);
  // The user permits gender-category conversion for equivalent everyday garments.
  // Preserve product type, age, sleeves and purpose; never substitute an unrelated authorized category.
  const profile = originalMatch && publishCategoryProfile(originalMatch.label);
  const convertible = new Set(['tshirt', 'shirt', 'hoodie', 'sweater', 'jacket', 'coat', 'jeans', 'trousers', 'shorts', 'socks']);
  const permissionPath = originalMatch?.permissionPath || (originalMatch ? [originalMatch] : []);
  const onlyMainCategoryDenied = permissionPath.some(node => node.permissionStatuses?.includes('NON_MAIN_CATEGORY'))
    && permissionPath.every(node => node.permissionStatuses?.every(status => ['AVAILABLE', 'NON_MAIN_CATEGORY'].includes(status)));
  if (onlyMainCategoryDenied && profile.gender.size === 1 && profile.types.size === 1
      && [...profile.types].every(type => convertible.has(type)) && profile.age !== 'child') {
    const authorized = leaves.filter(leaf => (leaf.permissionPath || [leaf]).every(node =>
      node.permissionStatuses?.length && node.permissionStatuses.every(status => status === 'AVAILABLE')));
    const alternative = matchDestinationCategory(capture, authorized, {}, profile.gender.has('male') ? 'female' : 'male');
    if (alternative) categoryId = alternative;
  }
  requireTikTokCategoryPermission(leaves.find(leaf => leaf.id === categoryId));
  const warehouses = (options?.warehouses?.warehouses || []).filter(w =>
    w.type === 'SALES_WAREHOUSE' && (!w.status || w.status === 'ENABLED' || w.status === 'ACTIVE'));
  const warehouseId = warehouses.find(w => String(w.id) === common['warehouse-id'])?.id
    || warehouses.find(w => w.is_default === true)?.id
    || (warehouses.length === 1 ? warehouses[0].id : '');
  return {leaves, categoryId, matchedOriginal: Boolean(categoryId), genderConverted: Boolean(originalMatch && originalMatch.id !== categoryId), warehouseId: String(warehouseId || '')};
}
function publishCategoryText(value) {
  return String(value || '').normalize('NFKC').toLowerCase()
    .replace(/\[s\]|\(do not delete\)|bd test\s*-\s*/g, '')
    .replace(/[’']/g, '').replace(/\b(?:kemeja|baju)[\s-]+t\b|\bt[\s-]?shirts?\b/g, 'tshirt')
    .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}
function publishCategoryProfile(value) {
  const text = publishCategoryText(value);
  // These are product types, not translations of whole platform category IDs.
  // Keep generic clothing words separate so "Women/Fashion" cannot select a product type.
  const definitions = [
    ['tshirt', 'tops', /\b(?:tshirt|tees?|kemeja t|baju t|kaos|baju tee)\b|t恤|体恤|短袖(?!衬衫)|短袖t/],
    ['shirt', 'tops', /\b(?:shirts?|kemeja)\b|衬衫|襯衫/],
    ['blouse', 'tops', /\b(?:blouses?|blaus)\b|雪纺衫|罩衫/],
    ['tank', 'tops', /\b(?:tanks?(?: tops?)?|camis(?:oles?)?|singlets?)\b|背心|吊带|吊帶/],
    ['polo', 'tops', /\bpolo\b|翻领t恤/],
    ['hoodie', 'tops', /\b(?:hoodies?|sweatshirts?|baju sejuk)\b|卫衣|衛衣/],
    ['sweater', 'tops', /\b(?:sweaters?|cardigans?|knitwear)\b|毛衣|针织衫|針織衫|开衫/],
    ['jacket', 'outerwear', /\b(?:jackets?|jaket)\b|夹克|夾克|外套/],
    ['coat', 'outerwear', /\b(?:coats?|windbreakers?)\b|大衣|风衣|風衣/],
    ['dress', 'dresses', /\b(?:dresses|dress|gaun)\b|连衣裙|連衣裙|洋装/],
    ['skirt', 'bottoms', /\b(?:skirts?|skirt|rok)\b|半身裙|短裙|裙装/],
    ['jeans', 'bottoms', /\b(?:jeans|denim pants|seluar jeans)\b|牛仔裤|牛仔褲/],
    ['trousers', 'bottoms', /\b(?:trousers?|pants|seluar panjang)\b|长裤|長褲|休闲裤/],
    ['shorts', 'bottoms', /\b(?:shorts|seluar pendek)\b|短裤|短褲/],
    ['leggings', 'bottoms', /\bleggings?\b|打底裤|紧身裤/],
    ['bra', 'underwear', /\b(?:bras?|brassieres?)\b|胸罩|文胸/],
    ['underpants', 'underwear', /\b(?:underpants|panties|boxers|seluar dalam)\b|内裤|內褲/],
    ['socks', 'socks', /\b(?:socks?|stoking)\b|袜子|袜类|襪子/],
    ['swimwear', 'swimwear', /\b(?:swimwear|swimsuits?|bikinis?|pakaian renang)\b|泳装|泳衣/],
    ['sneakers', 'footwear', /\b(?:sneakers?|trainers?|running shoes|kasut sukan)\b|运动鞋|跑步鞋/],
    ['sandals', 'footwear', /\b(?:sandals?|sandal|selipar)\b|凉鞋|拖鞋/],
    ['boots', 'footwear', /\bboots?\b|靴子|短靴|长靴/],
    ['handbag', 'bags', /\b(?:handbags?|shoulder bags?|beg tangan)\b|手提包|手袋|单肩包/],
    ['backpack', 'bags', /\b(?:backpacks?|rucksacks?|beg galas)\b|双肩包|背包/],
    ['phonecase', 'electronics', /\b(?:phone cases?|phone covers?|casing telefon|sarung telefon)\b|手机壳|手机套/],
    ['phone', 'electronics', /\b(?:smartphones?|mobile phones?|telefon pintar|telefon bimbit)\b|手机|智能手机/],
    ['charger', 'electronics', /\b(?:chargers?|pengecas)\b|充电器/],
    ['earphones', 'electronics', /\b(?:earphones?|earbuds?|headphones?|fon telinga)\b|耳机|耳機/],
    ['cable', 'electronics', /\b(?:usb cables?|data cables?|kabel usb)\b|数据线|充电线/],
    ['bottle', 'home', /\b(?:water bottles?|drinking bottles?|botol air)\b|水杯|水壶|水瓶/],
    ['bedsheet', 'home', /\b(?:bed ?sheets?|cadar)\b|床单|床單/],
    ['toys', 'toys', /\b(?:toys?|mainan)\b|玩具/],
    ['shampoo', 'beauty', /\b(?:shampoos?|syampu)\b|洗发水|洗髮/],
    ['cleanser', 'beauty', /\b(?:facial cleansers?|face wash|pencuci muka)\b|洗面奶|洁面/]
  ];
  const types = new Set(), families = new Set();
  definitions.forEach(([type, family, pattern]) => {
    if (pattern.test(text)) { types.add(type); families.add(family); }
  });
  if (types.has('polo')) types.delete('tshirt');
  if (types.has('phonecase')) types.delete('phone');
  if (types.has('jeans') || types.has('leggings') || types.has('shorts')) types.delete('trousers');
  if ((types.has('shirt') || types.has('blouse')) && !/\b(?:tshirt|tees?|kaos)\b|t恤|体恤/.test(text)) types.delete('tshirt');
  if (types.has('dress') && /\btshirt dress(?:es)?\b|t恤裙/.test(text)) types.delete('tshirt');
  if (/\b(?:tops?|atasan|baju atasan)\b|上装|上衣|上裝/.test(text)) families.add('tops');
  if (/\b(?:bottoms?)\b|下装|下裝/.test(text)) families.add('bottoms');
  if (/\b(?:shoes?|footwear|kasut)\b|鞋/.test(text)) families.add('footwear');
  if (/\b(?:bags?|beg)\b|箱包/.test(text)) families.add('bags');
  const gender = new Set();
  if (/\b(?:women(?:s|swear)?|womenswear|woman|ladies|lady|female|wanita|perempuan|girls?)\b|女/.test(text)) gender.add('female');
  if (/\b(?:men(?:s|swear)?|menswear|man|male|lelaki|pria|boys?)\b|男/.test(text)) gender.add('male');
  if (/\bunisex\b|男女通用|男女同款/.test(text)) { gender.add('female'); gender.add('male'); }
  const child = /\b(?:kids?|childrens?|children|babies|baby|boys?|girls?|kanak kanak|bayi)\b|童装|童鞋|儿童|婴儿|婴童|男童|女童/.test(text);
  const adult = !child && (/\b(?:adults?|women(?:s|swear)?|womenswear|ladies|men(?:s|swear)?|menswear|wanita|lelaki)\b|女装|男装|女士|男士|成人/.test(text));
  const sleeve = new Set();
  if (/\b(?:short sleeve[ds]?|lengan pendek)\b|短袖/.test(text)) sleeve.add('short');
  if (/\b(?:long sleeve[ds]?|lengan panjang)\b|长袖|長袖/.test(text)) sleeve.add('long');
  if (/\b(?:sleeveless|tanpa lengan)\b|无袖|無袖/.test(text)) sleeve.add('none');
  if (types.has('tank') && types.size === 1) sleeve.add('none');
  const purposes = new Set();
  [
    ['maternity', /\b(?:maternity|pregnancy|pregnant|ibu hamil)\b|孕妇|孕婦|孕装/],
    ['nursing', /\b(?:nursing|breastfeeding|menyusu)\b|哺乳/],
    ['sports', /\b(?:sportswear|activewear|sports|sport|athletic|sukan|yoga)\b|运动|運動|瑜伽/],
    ['pet', /\b(?:pets?|dogs?|cats?|haiwan)\b|宠物|寵物|狗|猫/],
    ['costume', /\b(?:costumes?|cosplay)\b|演出服|角色扮演/]
  ].forEach(([purpose, pattern]) => { if (pattern.test(text)) purposes.add(purpose); });
  return {text, types, families, gender, age: child ? 'child' : adult ? 'adult' : '', sleeve, purposes};
}
function publishCategoryAttributeText(capture) {
  const values = [];
  const visit = (value, depth = 0) => {
    if (depth > 4 || values.length >= 160 || value == null) return;
    if (typeof value === 'string') { values.push(value.slice(0, 250)); return; }
    if (Array.isArray(value)) { value.slice(0, 60).forEach(item => visit(item, depth + 1)); return; }
    if (typeof value === 'object') Object.entries(value).slice(0, 60).forEach(([key, item]) => {
      if (!/^(?:id|.*_id|url|image|images)$/i.test(key)) { values.push(key); visit(item, depth + 1); }
    });
  };
  visit(capture?.attributes); visit(capture?.normalizedData?.attributes);
  visit(capture?.productAttributes); visit(capture?.normalizedData?.productAttributes);
  return values.join(' ');
}
function publishCategoryTokens(value) {
  const generic = new Set(['women','womens','men','mens','ladies','male','female','fashion','clothes','clothing',
    'apparel','pakaian','wanita','lelaki','other','others','lain','dan','and','with','for','the','new','stock',
    'accessories','accessory','products','product','items','general','miscellaneous','其他','其它','配件','其他配件',
    'cotton','polyester','soft','summer','short','long','sleeve','sleeves','ready','white','black','multicoloured']);
  return new Set(publishCategoryText(value).split(/\s+/).filter(word => word.length >= 3 && !generic.has(word))
    .map(word => /^[a-z]+s$/.test(word) && !word.endsWith('ss') ? word.slice(0, -1) : word));
}
function publishCategoryExplicitFacts(capture) {
  const facts = {sleeve: new Set(), neckline: new Set(), condition: new Set(), gender: new Set()};
  const textValues = value => {
    if (Array.isArray(value)) return value.flatMap(textValues);
    if (value && typeof value === 'object') {
      for (const key of ['name', 'value_name', 'valueName', 'value', 'values']) {
        if (Object.hasOwn(value, key)) return textValues(value[key]);
      }
      return [];
    }
    return typeof value === 'string' ? [publishCategoryText(value)] : [];
  };
  const add = (label, raw) => {
    const key = publishCategoryText(label).replace(/\s/g, '');
    for (const value of textValues(raw)) {
      if (/^(?:gender|sex|targetgender|性别|適用性別|适用性别|jeniskelamin|jantina)$/.test(key)) {
        publishCategoryProfile(value).gender.forEach(gender => facts.gender.add(gender));
      }
      if (/^(?:sleevelength|panjanglengan(?:baju)?|袖长|袖長)$/.test(key)) {
        if (/^(?:short sleeve[ds]?|lengan pendek|短袖)$/.test(value)) facts.sleeve.add('short');
        else if (/^(?:long sleeve[ds]?|lengan panjang|长袖|長袖)$/.test(value)) facts.sleeve.add('long');
        else if (/^(?:sleeveless|tanpa lengan|无袖|無袖)$/.test(value)) facts.sleeve.add('none');
      }
      // "Others" is unspecified, not a concrete neckline contradicting the description.
      if (/^(?:collar|neckline|necktype|garisleher|领口|领型|領口|領型)$/.test(key) && value
          && !/^(?:other|others|unknown|not specified|n a|none|lain|lain lain|其他|其它|未指定|未知)$/.test(value)) {
        facts.neckline.add(/^(?:round neck|crew neck|crewneck|leher krew|圆领|圓領)$/.test(value) ? 'round' : 'other');
      }
      if (/^(?:(?:product|item)?condition|keadaan(?:barang)?|kondisibarang|商品状况|商品成色|成色)$/.test(key)) {
        if (/^(?:new|brand new|baru|全新|https? schema org newcondition)$/.test(value)) facts.condition.add('new');
        else if (/^(?:used|pre owned|preowned|second hand|terpakai|二手|https? schema org usedcondition)$/.test(value)) facts.condition.add('used');
        else if (/^(?:refurbished|翻新|https? schema org refurbishedcondition)$/.test(value)) facts.condition.add('refurbished');
      }
    }
  };
  const visit = (value, depth = 0) => {
    if (!value || depth > 4) return;
    if (Array.isArray(value)) { value.slice(0, 160).forEach(entry => visit(entry, depth + 1)); return; }
    if (typeof value !== 'object') return;
    const label = value.name || value.attribute_name || value.attributeName || value.property_name || value.propertyName;
    if (label) {
      for (const key of ['values', 'value', 'value_name', 'valueName']) {
        if (Object.hasOwn(value, key)) { add(label, value[key]); return; }
      }
    } else Object.entries(value).slice(0, 160).forEach(([key, entry]) => add(key, entry));
  };
  for (const source of [capture, capture?.normalizedData]) {
    for (const key of ['attributes', 'productAttributes', 'product_attributes', 'productProperties', 'product_properties']) visit(source?.[key]);
    for (const key of ['condition', 'productCondition', 'product_condition', 'itemCondition', 'item_condition']) add('condition', source?.[key]);
  }
  // Only labelled specifications are facts. Free-form descriptions and marketing titles cannot change product type.
  const description = String(capture?.normalizedData?.originalDescription ?? capture?.description ?? capture?.normalizedData?.description ?? '').slice(0, 100000)
    .replace(/<[^>]*>/g, ' ').replace(/&nbsp;/gi, ' ');
  const fields = /(?:^|[\s;；|])((?:sleeve\s+length|panjang\s+lengan(?:\s+baju)?|袖长|袖長)|(?:collar|neckline|neck\s+type|garis\s+leher|领口|领型|領口|領型))\s*[:：]\s*((?:short|long)\s+sleeve[ds]?|sleeveless|lengan\s+(?:pendek|panjang)|tanpa\s+lengan|短袖|长袖|長袖|无袖|無袖|round\s+neck|crew\s*neck|(?:v|square|scoop)[\s-]+neck|collared|polo|leher\s+krew|圆领|圓領)(?=$|[\s;；,.，。|])/gi;
  for (const field of description.matchAll(fields)) add(field[1], field[2]);
  return facts;
}
function publishCategoryResaleType(value) {
  const text = publishCategoryText(value);
  if (/\brefurbished\b|翻新/.test(text)) return 'refurbished';
  if (/\b(?:pre owned|preowned|second hand|used|terpakai|bekas)\b|二手|中古/.test(text)) return 'used';
  return '';
}
function matchDestinationCategory(capture, leaves, saved = {}, genderOverride = '') {
  const normalize = value => publishCategoryText(value).replace(/\s/g, '');
  const split = value => String(value || '').split(/\s*(?:>|→|›|\/)\s*/).filter(Boolean);
  const path = String(capture?.categoryName || capture?.normalizedData?.categoryName || '');
  const labels = c => c.aliases?.length ? c.aliases : [c.label];
  const leafName = split(path).pop() || '';
  const categoryProfile = publishCategoryProfile(path), sourceLeaf = publishCategoryProfile(leafName);
  const title = String(capture?.normalizedData?.originalTitle ?? capture?.title ?? capture?.normalizedData?.title ?? '');
  const attributes = publishCategoryAttributeText(capture);
  const detailProfile = publishCategoryProfile(`${title} ${attributes}`);
  let sourceTypes = sourceLeaf.types.size ? sourceLeaf.types : detailProfile.types;
  const sourceFamilies = sourceLeaf.families.size ? sourceLeaf.families
    : categoryProfile.families.size ? categoryProfile.families : detailProfile.families;
  let sourceGender = categoryProfile.gender.size ? categoryProfile.gender : detailProfile.gender;
  const sourceAge = categoryProfile.age || detailProfile.age;
  const facts = publishCategoryExplicitFacts(capture);
  const titleProfile = publishCategoryProfile(title);
  // A source seller's broad gender taxonomy must not override an explicit garment audience.
  // Only a title describing the same apparel family can correct it (never incidental print text).
  const apparelFamilies = new Set(['tops', 'outerwear', 'dresses', 'bottoms', 'underwear', 'socks', 'swimwear']);
  const sameGarmentFamily = [...sourceFamilies].some(family => apparelFamilies.has(family) && titleProfile.families.has(family));
  if (sameGarmentFamily && titleProfile.gender.size === 1) {
    if (facts.gender.size && ![...titleProfile.gender].every(gender => facts.gender.has(gender))) return '';
    sourceGender = titleProfile.gender;
  }
  if (facts.gender.size) sourceGender = facts.gender;
  if (genderOverride === 'female' || genderOverride === 'male') sourceGender = new Set([genderOverride]);
  if (facts.condition.size > 1 || (sourceFamilies.has('tops') && (facts.sleeve.size > 1 || facts.neckline.size > 1))) return '';
  const sourceCondition = [...facts.condition][0] || publishCategoryResaleType(path);
  const sourceSleeve = sourceFamilies.has('tops') && facts.sleeve.size
    ? facts.sleeve : sourceLeaf.sleeve.size ? sourceLeaf.sleeve : detailProfile.sleeve;
  // A short-sleeved, round-neck top cannot be a tank. This requires explicit specifications inside a tops taxonomy.
  // It never promotes a phone case or a different garment merely because its marketing text mentions a T-shirt.
  if (sourceFamilies.has('tops') && sourceTypes.size === 1 && sourceTypes.has('tank')
    && facts.sleeve.size === 1 && facts.sleeve.has('short') && facts.neckline.size === 1 && facts.neckline.has('round')) sourceTypes = new Set(['tshirt']);
  const sourcePurposes = new Set([...categoryProfile.purposes, ...detailProfile.purposes]);
  const intersect = (left, right) => [...left].filter(value => right.has(value)).length;
  const sourceTokens = publishCategoryTokens(leafName);
  const contextTokens = publishCategoryTokens(`${path} ${title} ${attributes}`);
  const eligible = leaves.map(candidate => {
    const candidateLeafNames = candidate.leafNames?.length ? candidate.leafNames : labels(candidate).map(label => split(label).pop() || '');
    const leafProfile = publishCategoryProfile(candidateLeafNames.join(' '));
    const profile = publishCategoryProfile(labels(candidate).join(' '));
    const resaleType = publishCategoryResaleType(labels(candidate).join(' '));
    if ((resaleType && resaleType !== sourceCondition)
      || (sourceGender.size && profile.gender.size && !intersect(sourceGender, profile.gender))
      || (sourceAge && profile.age && sourceAge !== profile.age)
      || [...profile.purposes].some(purpose => !sourcePurposes.has(purpose))
      || (sourceSleeve.size && leafProfile.sleeve.size && !intersect(sourceSleeve, leafProfile.sleeve))) return null;
    // Different concrete product types cannot be rescued by a shared word such as "Women" or "Cotton".
    const typeMatches = intersect(sourceTypes, leafProfile.types);
    if (sourceTypes.size && leafProfile.types.size && !typeMatches) return null;
    return {candidate, candidateLeafNames, leafProfile, profile, typeMatches, resaleType};
  }).filter(Boolean);
  const full = path ? eligible.filter(({candidate}) => labels(candidate).some(label => normalize(label) === normalize(path))) : [];
  if (full.length === 1) return String(full[0].candidate.id);
  const ranked = eligible.map(({candidate, candidateLeafNames, leafProfile, profile, typeMatches, resaleType}) => {
    const exactLeaf = leafName && sourceTokens.size && candidateLeafNames.some(name => normalize(name) === normalize(leafName));
    const candidateTokens = publishCategoryTokens(candidateLeafNames.join(' '));
    const wordMatches = intersect(sourceTokens, candidateTokens);
    const lexicalMatch = wordMatches > 0 && wordMatches / Math.max(1, sourceTokens.size) >= 0.6;
    // A destination may expose only a broad leaf such as Tops; never infer its children in reverse.
    const broadMatch = !leafProfile.types.size && leafProfile.families.size
      && (sourceTypes.size || sourceFamilies.size) && intersect(sourceFamilies, leafProfile.families);
    if (!exactLeaf && !typeMatches && !lexicalMatch && !broadMatch) return null;
    let score = exactLeaf ? 100 : typeMatches ? 85 : lexicalMatch ? 65 : 45;
    if (sourceGender.size === 1 && profile.gender.size === 1) score += 24;
    if (sourceAge && sourceAge === profile.age) score += 16;
    if (sourceSleeve.size && intersect(sourceSleeve, leafProfile.sleeve)) score += 16;
    if (intersect(sourcePurposes, profile.purposes)) score += 20;
    if (typeMatches && sourceLeaf.types.size) score += 8;
    if (resaleType && resaleType === sourceCondition) score += 20;
    score += Math.min(12, intersect(contextTokens, publishCategoryTokens(labels(candidate).join(' '))) * 3);
    return {id: String(candidate.id), score};
  }).filter(Boolean).sort((left, right) => right.score - left.score || left.id.localeCompare(right.id));
  // A saved choice needs positive semantic evidence and may only resolve a close match.
  // It cannot turn clothing into an unrecognized household type, or displace a stronger category.
  const remembered = saved.category == null ? null : ranked.find(candidate => candidate.id === String(saved.category));
  if (remembered && ranked[0].score - remembered.score < 12) return remembered.id;
  // Equal or near-equal evidence means the product information cannot distinguish those leaves.
  return ranked.length && (!ranked[1] || ranked[0].score - ranked[1].score >= 12) ? ranked[0].id : '';
}
function resolveDestinationPublishOptions(platform, capture, options, common = {}, category = {}) {
  return platform === 'TIKTOK_SHOP'
    ? resolveTikTokPublishOptions(capture, options, common, category)
    : resolveShopeePublishOptions(capture, options, common, category);
}
function crossPlatformPublishShops() {
  return [...activeMarketplaceShops('SHOPEE'), ...activeMarketplaceShops('TIKTOK_SHOP')];
}

async function prepareSimpleProductPublish() {
  const version = ++productPublishPrepareVersion;
  const captureId = selectedProductCaptureDetail?.id;
  const organizationId = currentUser?.organizationId;
  const platform = document.querySelector('#publish-destination-platform').value;
  const shopId = document.querySelector('#publish-shop-id').value;
  const live = marketplaceIntegrationStatus.livePublishingEnabled;
  const current = () => version === productPublishPrepareVersion
    && captureId === selectedProductCaptureDetail?.id && organizationId === currentUser?.organizationId
    && shopId === document.querySelector('#publish-shop-id').value
    && platform === document.querySelector('#publish-destination-platform').value;
  const preparation = {version, captureId, organizationId, platform, shopId, status:'LOADING', error:''};
  productPublishPreparation = preparation;
  try { if (!ProductListingTemplates.get(platform)) await ProductListingTemplates.ensure(); }
  catch (error) {
    if (current()) {
      preparation.status = 'BLOCKED'; preparation.error = error.message;
      document.querySelector('#publish-auto-summary').textContent = error.message;
      document.querySelector('#publish-auto-summary').dataset.prepareError = error.message;
    }
    return;
  }
  if (!current()) return;
  const common = readPublishPreference();
  const category = readPublishPreference(true);
  const defaults = automaticProductPublishDefaults(selectedProductCaptureDetail, common, platform);
  const field = name => document.querySelector(`#publish-${name}`);
  field('auto-summary').dataset.loading = 'false';
  field('auto-summary').dataset.prepareError = '';
  field('auto-summary').dataset.shopId = shopId;
  field('stock').value = String(defaults.publishStock);
  field('weight-grams').value = defaults.weightGrams;
  field('warehouse-id').value = common['warehouse-id'] || '';
  field('package-length').value = defaults.packageLengthCm;
  field('package-width').value = defaults.packageWidthCm;
  field('package-height').value = defaults.packageHeightCm;
  ['sku-stocks','logistic-info','product-attributes','shopee-payload','sales-attributes','price'].forEach(name => field(name).value = '');
  field('category-id').value = category.category || '';
  field('category-id').hidden = true;
  field('category-choice').hidden = false;
  field('category-choice').required = false;
  field('category-choice').replaceChildren(new Option('正在读取官方类目…', ''));
  field('logistic-choice').replaceChildren(new Option('正在读取店铺物流…', ''));
  field('logistic-choice').disabled = false;
  field('advanced').open = false;
  const shop = activeMarketplaceShops(platform).find(s => String(s.id) === shopId);
  const currency = {MY:'MYR',PH:'PHP',SG:'SGD',TH:'THB',VN:'VND',ID:'IDR',TW:'TWD',BR:'BRL',MX:'MXN',US:'USD',GB:'GBP'}[String(shop?.region || '').toUpperCase()];
  field('price').dataset.currency = currency || '';
  field('price-field').hidden = true;
  field('price-currency').textContent = currency || '';
  field('price').required = false;
  for (const name of ['stock','weight-grams']) field(name).required = live;
  field('category-id').required = false;
  const template = ProductListingTemplates.get(platform);
  field('auto-summary').textContent = `每个 SKU 默认库存 999 · ${template?.enabled ? `${template.name} · ${Number(defaults.weightGrams)/1000} kg · ${defaults.packageLengthCm} × ${defaults.packageWidthCm} × ${defaults.packageHeightCm} cm` : originalProductWeight(selectedProductCaptureDetail) ? '已沿用原商品重量' : '重量默认 250 克/件'} · 各 SKU 原售价按最新汇率自动换算为 ${currency || '目标币种'}`;
  const fallback = message => {
    field('category-choice').hidden = true;
    field('category-id').hidden = false;
    field('category-id').required = live;
    field('category-choice').required = false;
    field('logistic-choice').replaceChildren(new Option('请在高级设置填写物流', ''));
    field('auto-summary').textContent += `。${message}`;
    if (platform === 'SHOPEE') field('advanced').open = true;
  };
  if (!live || !shopId) { preparation.status = live ? 'BLOCKED' : 'READY'; fallback('选择店铺后自动读取类目与物流'); return; }
  try {
    field('auto-summary').dataset.loading = 'true';
    const result = await api(`/api/v1/shops/${encodeURIComponent(shopId)}/publish-options`);
    if (!current()) return;
    const listingMethod = platform === 'TIKTOK_SHOP' ? requireTikTokDraftCapability(shop, result.data) : '';
    const resolved = resolveDestinationPublishOptions(platform, selectedProductCaptureDetail, result.data, common, category);
    field('category-choice').replaceChildren(new Option('请选择商品类目（下次同类商品自动带入）', ''), ...resolved.leaves.map(c => {
      const denied = platform === 'TIKTOK_SHOP' && Boolean(tikTokCategoryPermissionError(c));
      const option = new Option(c.label + (denied ? '（当前店铺无权限）' : ''), c.id);
      option.disabled = denied;
      return option;
    }));
    if (resolved.matchedOriginal) field('auto-summary').textContent += resolved.genderConverted ? ' · 已自动转换为店铺支持的同类男装／女装类目' : ' · 已匹配商品类目';
    field('category-choice').value = resolved.categoryId;
    field('category-id').value = resolved.categoryId;
    field('category-choice').required = true;
    if (platform === 'TIKTOK_SHOP') {
      preparation.categoryLeaves = resolved.leaves;
      field('warehouse-id').value = resolved.warehouseId;
      if (!resolved.warehouseId) throw Error('目标店铺没有唯一或默认发货仓库，请在店铺后台设置默认仓库');
      field('auto-summary').textContent += ' · 已匹配发货仓库 · 保存为草稿';
      if (listingMethod === 'LOCAL_REPLICATION') field('auto-summary').textContent += ' · 已确认跨境店铺刊登模式';
      preparation.status = 'READY';
      return;
    }
    const channels = resolved.channels;
    if (resolved.logisticsConfigured) {
      preparation.logisticInfo = resolved.logisticInfo;
      const selected = new Set(resolved.logisticInfo.filter(channel=>channel.enabled).map(channel=>String(channel.logistic_id)));
      const names = channels.filter(channel=>selected.has(String(channel.logistics_channel_id))).map(channel=>channel.logistics_channel_name);
      field('logistic-choice').replaceChildren(new Option(`已配置 ${names.length} 个物流渠道`, '__CONFIGURED__'));
      field('logistic-choice').disabled = true;
      field('auto-summary').textContent += ` · 物流：${names.join('、')}（可在定制模板配置中修改）`;
    } else {
      field('logistic-choice').replaceChildren(new Option('请选择配送渠道（下次自动带入）',''), ...channels.map(c => new Option(c.logistics_channel_name, String(c.logistics_channel_id))));
      field('logistic-choice').value = resolved.logistic;
    }
    syncSimplePublishLogistics();
    if (!channels.length) { field('advanced').open = true; field('auto-summary').textContent += '。店铺未返回已启用物流，请检查店铺配置。'; }
    preparation.status = 'READY';
  } catch (error) {
    if (!current()) return;
    const localFallback = platform === 'TIKTOK_SHOP' && String(shop?.sellerType || '').toUpperCase() === 'LOCAL'
      && !error.draftCapabilityBlocked && !error.categoryPermissionBlocked
      && !String(error.message || '').startsWith('跨境刊登模式未就绪');
    if (platform !== 'TIKTOK_SHOP' || localFallback) {
      preparation.status = localFallback ? 'LOCAL_FALLBACK' : 'READY';
      fallback(`自动读取失败：${error.message}`);
    } else {
      preparation.status = 'BLOCKED';
      preparation.error = `${shop?.name || shop?.externalShopId || '当前 TikTok 店铺'}：${error.message}`;
      field('category-id').value = '';
      field('category-id').hidden = true;
      field('category-id').required = false;
      field('category-choice').replaceChildren(new Option('当前店铺暂无法提交，请更换店铺或重新读取', ''));
      field('category-choice').required = false;
      field('warehouse-id').value = '';
      field('advanced').open = false;
      field('auto-summary').dataset.prepareError = preparation.error;
      field('auto-summary').textContent = preparation.error;
    }
  } finally {
    if (current()) field('auto-summary').dataset.loading = 'false';
  }
}
function syncSimplePublishLogistics() {
  const id = document.querySelector('#publish-logistic-choice').value;
  document.querySelector('#publish-logistic-info').value = productPublishPreparation?.logisticInfo
    ? JSON.stringify(productPublishPreparation.logisticInfo) : id ? JSON.stringify([{logistic_id:Number(id),enabled:true}]) : '';
}

function syncOfficialPublishFields() {
  const platform = document.querySelector("#publish-destination-platform").value;
  const live = marketplaceIntegrationStatus.livePublishingEnabled;
  const republishing = String(selectedProductCaptureDetail?.status || "").toUpperCase() === "PUBLISHED";
  const stockMax = platform === "TIKTOK_SHOP" ? 99999 : 999999;
  const fields = document.querySelector("#product-official-publish-fields");
  fields.hidden = !live;
  if (live) fields.open = true;
  document.querySelectorAll("[data-tiktok-publish-field]").forEach(field => {
    field.hidden = platform !== "TIKTOK_SHOP";
  });
  document.querySelectorAll("[data-shopee-publish-field]").forEach(field => {
    field.hidden = platform !== "SHOPEE";
  });
  const shopeeLogisticInfo = document.querySelector("#publish-logistic-info");
  shopeeLogisticInfo.required = live && platform === "SHOPEE";
  shopeeLogisticInfo.setAttribute("aria-required", String(shopeeLogisticInfo.required));
  const publishStock = document.querySelector("#publish-stock");
  publishStock.max = String(stockMax);
  publishStock.setAttribute("aria-label", `上架库存，0 到 ${stockMax}`);
  const skuKeys = productCaptureSkus(selectedProductCaptureDetail)
    .map(sku => String(sku.skuKey || "").trim())
    .filter(Boolean);
  const skuStockHelp = document.querySelector("#publish-sku-stocks-help");
  skuStockHelp.textContent = skuKeys.length
    ? `键必须使用当前商品的 SKU Key，例如 ${skuKeys.slice(0, 3).join("、")}${skuKeys.length > 3 ? "…" : ""}；${marketplacePlatformName(platform)} 库存只能是 0–${stockMax} 的整数，留空则全部使用上架库存。`
    : `键必须使用当前商品的 SKU Key；${marketplacePlatformName(platform)} 库存只能是 0–${stockMax} 的整数，留空则全部使用上架库存。`;
  document.querySelector("#product-official-publish-hint").textContent = platform === "SHOPEE"
    ? "默认库存 999 · 自动读取类目与物流"
    : "默认库存 999 · 沿用原商品重量";
  const notice = document.querySelector(".product-dry-run-notice");
  notice.classList.toggle("is-live", live);
  notice.querySelector("span").textContent = live ? "OFFICIAL API" : "DRY RUN";
  document.querySelector("#product-publish-mode-title").textContent = live
    ? "这次会向官方店铺提交刊登" : "这次不会向真实店铺提交";
  document.querySelector("#product-publish-mode-note").textContent = live
    ? "系统将使用店铺 OAuth Token 调用官方商品接口；提交后会回读平台状态，审核中不会显示为已上架。"
    : "系统会按顺序处理图片、完成审核并保存模拟发布审计；当前不会向店铺提交内容。";
  document.querySelector("#product-publish-dialog-mode").textContent = live
    ? "人工确认 · OFFICIAL API" : "人工确认 · Dry Run";
  document.querySelector("#product-publish-dialog-title").textContent = republishing
    ? "提交到其他店铺" : "确认一键上传";
  document.querySelector("#product-publish-dialog-copy").textContent = republishing
    ? live
      ? "此商品已有平台提交记录。请选择另一家状态为“可发布”的目标店铺，核对刊登参数后继续提交。"
      : "此商品已有平台提交记录。本次可改选另一家店铺继续执行 Dry Run，不会影响已有记录。"
    : live
      ? "请再次核对目标平台、授权店铺和刊登参数；确认一次后系统会依次处理图片、审核并调用官方接口提交刊登。"
      : "请再次核对目标平台与店铺。确认一次后系统会依次处理图片、审核和模拟发布，不会向真实店铺提交商品。";
  document.querySelector("#product-publish-confirm-copy").textContent = live
    ? "我已核对商品、SKU、刊登参数与目标店铺，并确认依次处理图片、审核和提交平台刊登。"
    : "我已核对商品、SKU 与目标店铺，并确认依次处理图片、审核和执行 Dry Run。";
  document.querySelector("#submit-product-publish").textContent = live
    ? "确认并提交平台刊登" : "确认一键上传（Dry Run）";
  syncPublishShopOptions();
}

function setProductUploadStep(step, state, text) {
  const item = document.querySelector(`[data-upload-step="${step}"]`);
  if (!item) return;
  item.classList.remove("is-running", "is-complete", "is-failed");
  if (state) item.classList.add(`is-${state}`);
  const status = item.querySelector("em");
  if (status) status.textContent = text;
  const marker = item.querySelector(":scope > span");
  if (marker) marker.textContent = state === "complete" ? "✓" : state === "failed" ? "!" : ({ IMAGES: "1", APPROVE: "2", PUBLISH: "3" })[step];
}

function renderProductUploadSteps(capture) {
  const status = String(capture?.status || "CAPTURED").toUpperCase();
  const images = productCaptureImages(capture);
  const imagesComplete = ["READY", "APPROVED", "PUBLISHED"].includes(status)
    || (images.length > 0 && images.every(image => productImageState(image) === "completed"));
  const approved = ["APPROVED", "PUBLISHED"].includes(status);
  setProductUploadStep("IMAGES", imagesComplete ? "complete" : "", imagesComplete ? "已有结果，将跳过" : "确认后执行");
  setProductUploadStep("APPROVE", approved ? "complete" : "", approved ? "已审核，将跳过" : imagesComplete ? "等待执行" : "等待上一步");
  setProductUploadStep("PUBLISH", "", status === "PUBLISHED" ? "选择其他店铺" : approved ? "等待执行" : "等待上一步");
}

function setProductUploadFormBusy(busy) {
  const dialog = document.querySelector("#product-publish-dialog");
  dialog.dataset.uploading = busy ? "true" : "false";
  dialog.querySelectorAll("button, input, select, textarea").forEach(control => {
    if (busy) {
      // The workflow is durable, so users may close the progress dialog while it
      // continues in the background and reopen it from the product action later.
      if (control.matches("[data-close-dialog]")) return;
      if (!Object.prototype.hasOwnProperty.call(control.dataset, "uploadDisabledBefore")) {
        control.dataset.uploadDisabledBefore = String(control.disabled);
      }
      control.disabled = true;
    } else if (Object.prototype.hasOwnProperty.call(control.dataset, "uploadDisabledBefore")) {
      control.disabled = control.dataset.uploadDisabledBefore === "true";
      delete control.dataset.uploadDisabledBefore;
    }
  });
}

function buildProductPublishPayload(destinationPlatform, shopId, idempotencyKey) {
  const payload = { confirmed: true, destinationPlatform, shopId, idempotencyKey };
  if (!marketplaceIntegrationStatus.livePublishingEnabled) return payload;
  const preparationError = productPublishPreparationError(destinationPlatform, shopId);
  if (preparationError) throw Error(preparationError);
  if (document.querySelector("#publish-auto-summary").dataset.loading === "true") throw Error("正在读取店铺配置，请稍等片刻");
  payload.categoryId = document.querySelector("#publish-category-id").value.trim();
  if (!payload.categoryId) throw Error("请选择商品类目");
  payload.publishStock = parseProductPublishStock(destinationPlatform);
  payload.weightGrams = document.querySelector("#publish-weight-grams").value.trim();
  payload.autoConvertPrice = true;
  payload.packageLengthCm = document.querySelector("#publish-package-length").value.trim();
  payload.packageWidthCm = document.querySelector("#publish-package-width").value.trim();
  payload.packageHeightCm = document.querySelector("#publish-package-height").value.trim();
  payload.skuStocks = parseProductSkuStocks("#publish-sku-stocks", destinationPlatform);
  if (destinationPlatform === "TIKTOK_SHOP") {
    payload.warehouseId = document.querySelector("#publish-warehouse-id").value.trim();
    payload.packageLengthCm = document.querySelector("#publish-package-length").value.trim();
    payload.packageWidthCm = document.querySelector("#publish-package-width").value.trim();
    payload.packageHeightCm = document.querySelector("#publish-package-height").value.trim();
    payload.salesAttributesBySku = parseProductPublishJson("#publish-sales-attributes", "TikTok 多规格映射");
  } else {
    payload.logisticInfo = parseShopeeLogisticInfo();
    payload.productAttributes = parseProductPublishJson("#publish-product-attributes", "Shopee 类目属性", [], "array");
    payload.shopeePayload = { brand: { brand_id: 0, original_brand_name: "NoBrand" }, ...parseProductPublishJson("#publish-shopee-payload", "Shopee 条件必填补充参数", {}, "object") };
  }
  return payload;
}

function productPublishWorkflowMatchesTarget(workflow, captureId, payload) {
  return workflow?.captureId === captureId
    && productPublishWorkflowPlatform(workflow) === normalizedMarketplacePlatform(payload.destinationPlatform)
    && String(workflow.shopId || "") === String(payload.shopId || "");
}

function productPublishWorkflowCreateMayHaveCommitted(error) {
  const status = Number(error?.status || 0);
  return status === 0 || status === 408 || status >= 500;
}

async function createProductPublishWorkflow(captureId, payload, idempotencyKey, {
  requestApi = api, allowTargetRecovery = true, maxAttempts = 2, onRetry,
  isCurrent = () => true, sleep = delay => new Promise(resolve => setTimeout(resolve, delay))
} = {}) {
  const request = () => requestApi(productPublishWorkflowPath(captureId), {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(payload)
  });
  let lastError;
  // A lost response does not tell us whether the POST committed. Replaying the
  // exact body with the exact same key is safe and lets the server return the
  // already-created workflow instead of creating a duplicate publication.
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    if (!isCurrent()) throw new ApiRequestError('当前操作已变化，已停止旧提交', 409);
    try {
      const result = await request();
      if (!result?.data?.id) throw new ApiRequestError("一键上传任务响应不完整，请稍后重试", 502, result);
      return result.data;
    } catch (error) {
      if (!productPublishWorkflowCreateMayHaveCommitted(error) || error?.name === 'AbortError') throw error;
      lastError = error;
      if (attempt < maxAttempts) {
        const delayMs = attempt === 1 ? 2000 : 5000;
        if (!isCurrent()) throw new ApiRequestError('当前操作已变化，已停止旧提交', 409);
        onRetry?.({attempt: attempt + 1, maxAttempts, delayMs});
        await sleep(delayMs);
      }
    }
  }
  if (!isCurrent()) throw new ApiRequestError('当前操作已变化，已停止旧提交', 409);
  try {
    // Bulk requests must recover through the exact persisted key. This list
    // currently does not expose keys, so matching only a shop is insufficient.
    const result = allowTargetRecovery ? await requestApi(`${productPublishWorkflowPath(captureId)}?limit=20`) : null;
    const workflows = Array.isArray(result?.data) ? result.data : [];
    const recovered = workflows.find(workflow => productPublishWorkflowMatchesTarget(workflow, captureId, payload));
    if (recovered?.id) return recovered;
  } catch (lookupError) {
    if (lookupError?.status === 401) throw lookupError;
  }
  throw new ApiRequestError(
    "一键上传任务的提交结果暂时无法确认。后台可能仍在执行；请保持当前页面，点击重试时系统会沿用同一请求，不会重复创建任务。",
    lastError?.status || 0, lastError?.payload || null
  );
}

async function openProductPublishDialog({ background = false } = {}) {
  const capture = selectedProductCaptureDetail;
  const status = String(capture?.status || "").toUpperCase();
  const supported = ["CAPTURED", "DRAFT", "FAILED", "IMAGE_PROCESSING", "READY", "APPROVED", "PUBLISHED"].includes(status);
  if (!capture || !supported || !hasAnyPermission("product:publish")) return;
  if (productEditorDirty || productCaptureSaveInFlightId) {
    showProductNotice("请先保存当前商品修改，再执行一键上传。", "error");
    return;
  }
  if (["CAPTURED", "DRAFT", "FAILED"].includes(status) && !hasAnyPermission("asset:watermark")) {
    showProductNotice("当前商品需要先处理图片，但账号没有去水印权限。", "error");
    return;
  }
  if (["CAPTURED", "DRAFT", "FAILED", "READY"].includes(status) && !hasAnyPermission("product:edit")) {
    showProductNotice("当前商品需要先审核，但账号没有商品编辑/审核权限。", "error");
    return;
  }
  await loadMarketplaceShops({ silent: true });
  const skus = productCaptureSkus(capture);
  const images = productCaptureImages(capture);
  const completedImages = images.filter(image => productImageState(image) === "completed").length;
  const normalized = capture.normalizedData && typeof capture.normalizedData === "object" ? capture.normalizedData : {};
  if (!productPublishIdempotencyKey || productPublishIdempotencyCaptureId !== capture.id) {
    productPublishIdempotencyKey = newProductIdempotencyKey();
    productPublishIdempotencyCaptureId = capture.id;
  }
  document.querySelector("#publish-product-title").setAttribute("translate", (capture.title) ? "no" : "yes");
  document.querySelector("#publish-product-title").textContent = capture.title || "未命名商品";
  document.querySelector("#publish-organization-name").textContent = currentUser?.organizationName || "当前公司";
  document.querySelector("#publish-image-count").textContent = `${completedImages} / ${images.length} 张`;
  document.querySelector("#publish-sku-count").textContent = `${skus.length} 个`;
  const requestedPlatform = String(capture.destinationPlatform || normalized.destinationPlatform || "").toUpperCase();
  const sourcePlatform = String(capture.sourcePlatform || "").toUpperCase();
  document.querySelector("#publish-destination-platform").value = ["SHOPEE", "TIKTOK_SHOP"].includes(requestedPlatform)
    ? requestedPlatform : sourcePlatform === "TIKTOK" ? "TIKTOK_SHOP" : "SHOPEE";
  if (status === "PUBLISHED") document.querySelector("#publish-shop-id").value = "";
  syncPublishShopOptions(status === "PUBLISHED"
    ? "" : capture.destinationShopId || capture.shopId || normalized.shopId || "");
  ["#publish-category-id", "#publish-stock", "#publish-weight-grams", "#publish-warehouse-id",
    "#publish-package-length", "#publish-package-width", "#publish-package-height",
    "#publish-sku-stocks", "#publish-logistic-info", "#publish-product-attributes",
    "#publish-shopee-payload", "#publish-sales-attributes"].forEach(selector => {
      document.querySelector(selector).value = "";
    });
  const skuKeys = skus.map(sku => String(sku.skuKey || "").trim()).filter(Boolean);
  const skuStocks = document.querySelector("#publish-sku-stocks");
  const exampleSkuKey = skuKeys[0] || "SKU_KEY";
  skuStocks.placeholder = JSON.stringify({ [exampleSkuKey]: 20 });
  syncOfficialPublishFields();
  await prepareSimpleProductPublish();
  renderProductUploadSteps(capture);
  document.querySelector("#product-publish-dialog-title").textContent = status === "PUBLISHED"
    ? "提交到其他店铺" : "确认一键上传";
  if (status === "PUBLISHED") {
    document.querySelector("#product-publish-dialog-copy").textContent = marketplaceIntegrationStatus.livePublishingEnabled
      ? "此商品已有平台提交记录。请选择另一家状态为“可发布”的目标店铺，核对刊登参数后继续提交。"
      : "此商品已有平台提交记录。本次可改选另一家店铺继续执行 Dry Run，不会影响已有记录。";
    syncPublishShopContext();
  }
  document.querySelector("#publish-confirmed").checked = false;
  document.querySelector("#product-publish-error").hidden = true;
  document.querySelector("#product-publish-error").classList.remove("is-success");
  setProductUploadFormBusy(false);
  document.querySelector("#submit-product-publish").disabled = false;
  if (!background) document.querySelector("#product-publish-dialog").showModal();
  const trackedWorkflow = productPublishWorkflowForCapture(capture.id);
  if (productPublishWorkflowIsActive(trackedWorkflow)) {
    acceptProductPublishWorkflow(trackedWorkflow, currentUser?.organizationId || "");
    pollProductPublishWorkflow(trackedWorkflow);
  } else {
    if (trackedWorkflow) productPublishWorkflowState = null;
    await restoreProductPublishWorkflow(capture.id);
  }
  return true;
}

function saveProductBulkItems(items = productBulkItems, key = productBulkStorageKey, org = productBulkOrganization) {
  let previous;
  try { previous = JSON.parse(localStorage.getItem(key) || 'null'); } catch (_) {}
  const now = new Date().toISOString();
  const prefix = `heyi:bulk-upload:batch:${encodeURIComponent(org)}:`;
  const batchId = key.startsWith(prefix) ? decodeURIComponent(key.slice(prefix.length)) : previous?.batchId || key;
  localStorage.setItem(key, JSON.stringify({organizationId:org, batchId,
    createdAt:previous?.createdAt || now, updatedAt:now, items}));
}

const productBulkRuns = new Map();
let productBulkViewGeneration = 0;
let productBulkPollGeneration = 0;
let productBulkPollSession = null;

function productBulkApi(path, options = {}, {signal, timeoutMs = 90000, onRetry, isCurrent = () => true, retry = true} = {}) {
  const request = () => {
    if (!isCurrent()) throw new ApiRequestError('当前操作已变化，已停止旧请求', 409);
    return ProductBulkUpload.withDeadline(deadlineSignal => api(path, {...options, signal: deadlineSignal}), {
      signal: signal || options.signal, timeoutMs, timeoutMessage: '平台请求超时，已停止等待；可沿用原任务重试'
    });
  };
  return retry && (!options.method || options.method === 'GET')
    ? ProductBulkUpload.retryRead(request, {onRetry, isCurrent, signal: signal || options.signal}) : request();
}

function productBulkCaptureApi(path, options = {}, {signal, timeoutMs = 90000, onRetry, isCurrent = () => true, retry = true} = {}) {
  const request = () => {
    if (!isCurrent()) throw new ApiRequestError('当前操作已变化，已停止旧请求', 409);
    return ProductBulkUpload.withDeadline(deadlineSignal => productCaptureApi(path, {...options, signal: deadlineSignal}), {
      signal: signal || options.signal, timeoutMs, timeoutMessage: '商品资料读取或同步超时，请稍后重试'
    });
  };
  return retry && (!options.method || options.method === 'GET')
    ? ProductBulkUpload.retryRead(request, {onRetry, isCurrent, signal: signal || options.signal}) : request();
}

function recordProductBulkRetry(item, label, {attempt, maxAttempts, delayMs}, run) {
  item.retryCount = (item.retryCount || 0) + 1;
  item.retryMessage = `${label}暂时失败，${Math.ceil(delayMs / 1000)} 秒后自动重试（第 ${attempt}/${maxAttempts} 次尝试）`;
  if (run) {
    saveProductBulkItems(run.items, run.key, run.org);
    if (productBulkItems === run.items && productBulkStorageKey === run.key) renderProductBulkProgress();
    renderProductBulkHistory();
  } else {
    saveProductBulkItems();
    renderProductBulkProgress();
  }
}

function stopProductBulkPolling() {
  clearTimeout(productBulkPollTimer);
  productBulkPollTimer = null;
  productBulkPollGeneration++;
  productBulkPollSession?.controller.abort();
  productBulkPollSession = null;
}

function productBulkPollIsCurrent(session) {
  return productBulkPollSession === session && session.generation === productBulkPollGeneration
    && currentUser?.organizationId === session.org && productBulkItems === session.items
    && document.querySelector('#product-bulk-dialog').open && !session.controller.signal.aborted;
}

function scheduleProductBulkPolling(session, delay = 3000) {
  if (!productBulkPollIsCurrent(session)) return;
  clearTimeout(productBulkPollTimer);
  productBulkPollTimer = setTimeout(() => {
    productBulkPollTimer = null;
    if (productBulkPollIsCurrent(session)) void pollProductBulkProgress();
  }, delay);
}

function syncProductBulkCapture(item, session) {
  if (!productBulkPollIsCurrent(session) || !item.workflow?.id || productPublishWorkflowIsActive(item.workflow)) return;
  const previous = session.syncs.get(item);
  if (previous?.inFlight || previous?.done || previous?.paused || previous?.retryAt > Date.now()) return;
  if (session.syncActive >= 3) return;
  const sync = {inFlight: true, done: false, retryAt: 0, attempts: (previous?.attempts || 0) + 1};
  session.syncs.set(item, sync);
  session.syncActive++;
  // Detail synchronization is independent of workflow polling. A slow detail
  // request must never prevent another product's terminal status from rendering.
  void productBulkCaptureApi(`/${encodeURIComponent(item.captureId)}`, {}, {
    signal: session.controller.signal, timeoutMs: 30000, retry: false
  }).then(result => {
    if (!productBulkPollIsCurrent(session)) return;
    const capture = productCapturePayload(result?.data);
    if (capture?.id !== item.captureId) throw new ApiRequestError('商品状态同步响应不完整', 502);
    updateProductCaptureInMemory(capture, {preserveSelectedEditor: productEditorDirty || Boolean(productCaptureSaveInFlightId)});
    sync.done = true;
    item.syncError = '';
    item.syncPaused = false;
    item.retryMessage = '';
    renderProductBulkProgress();
  }).catch(error => {
    if (!productBulkPollIsCurrent(session)) return;
    if (error?.status === 401) { stopProductBulkPolling(); return; }
    if (ProductBulkUpload.isRetryableReadError(error) && sync.attempts < 3) {
      const delayMs = sync.attempts === 1 ? 2000 : 5000;
      sync.retryAt = Date.now() + delayMs;
      item.syncError = item.workflow?.status === 'SUCCEEDED'
        ? '上传已成功，状态刷新中，正在自动重试' : '商品状态同步暂时失败，正在自动重试';
      recordProductBulkRetry(item, '同步商品状态', {attempt: sync.attempts + 1, maxAttempts: 3, delayMs});
    } else {
      sync.paused = true;
      item.syncPaused = true;
      item.syncError = item.workflow?.status === 'SUCCEEDED'
        ? `上传已成功，状态刷新暂未完成（已尝试 ${sync.attempts} 次）；可重试进度同步，无需重新上传`
        : `商品状态同步失败，已尝试 ${sync.attempts} 次；请点击重试进度同步`;
    }
    renderProductBulkProgress();
  }).finally(() => {
    sync.inFlight = false;
    session.syncActive--;
    if (productBulkPollIsCurrent(session)) {
      for (const pending of session.items) syncProductBulkCapture(pending, session);
      if (!sync.done && !sync.paused) scheduleProductBulkPolling(session, Math.max(0, sync.retryAt - Date.now()));
    }
  });
}

function productBulkThumbnail(item) {
  const id = item.captureId || item.id;
  const capture = productCaptures.find(candidate => candidate.id === id) || productBulkSelectedCaptures.get(id);
  const image = capture?.images?.[0];
  return safeProductUrl(item.thumbnailUrl || capture?.thumbnailUrl || image?.processedUrl || image?.originalUrl);
}
function renderProductBulkCard(item, shops = [], preview = false) {
  const workflow = item.workflow;
  const state = workflow?.status || item.status || 'WAITING';
  const failed = ['PREPARE_FAILED','CREATE_FAILED','FAILED','REVIEW_REQUIRED'].includes(state);
  const success = state === 'SUCCEEDED';
  const active = ['PREPARING','SUBMITTING','RUNNING'].includes(state);
  const tone = failed ? 'failed' : success ? 'success' : active ? 'active' : 'waiting';
  const status = {WAITING:'等待准备',PREPARING:'自动准备中',PREPARE_FAILED:'准备失败',PREPARED:'准备就绪',SUBMITTING:'正在提交',CREATE_FAILED:'提交失败',PENDING:'排队中',RUNNING:'处理中',SUCCEEDED:workflow?.executionMode === 'DRY_RUN' ? '演练完成' : '已上传 · 待上架',FAILED:'上传失败',REVIEW_REQUIRED:'需要处理'}[state] || state;
  const stage = {IMAGES:'图片检测与处理',STYLE_IMAGES:'生成风格主图',APPROVE:'商品资料确认',PUBLISH:'上传到待上架'}[workflow?.currentStep] || '';
  const images = workflow?.currentStep === 'STYLE_IMAGES' ? workflow?.mainImageStyle : workflow?.imageProcessing;
  const measured = Number.isInteger(images?.totalImages) && images.totalImages > 0;
  const done = measured ? Math.min(images.totalImages, Math.max(0, Number(images.processedImages) || 0)) : 0;
  const percent = success ? 100 : measured ? Math.round(done / images.totalImages * 100) : null;
  const progress = success ? '上传成功' : item.waitMessage || (measured ? `${workflow?.currentStep === 'STYLE_IMAGES' ? '风格主图' : '图片'} ${done} / ${images.totalImages}` : preview ? '选择店铺后自动准备' : stage || status);
  const shopId = workflow?.shopId || item.payload?.shopId || item.shopId;
  const shop = shops.find(candidate => String(candidate.id) === String(shopId));
  const shopName = shop?.name || shop?.shopName || shop?.externalShopId || shopId || '';
  const error = [workflow?.errorMessage || item.error, item.syncError].filter(Boolean).join(' · ');
  const retries = item.retryMessage || (item.retryCount ? `已自动重试 ${item.retryCount} 次` : '');
  const thumb = productBulkThumbnail(item);
  return `<section class="bulk-product-card is-${tone}" ${preview ? `data-bulk-item="${escapeHtml(item.id)}"` : ''}>
    <div class="bulk-product-visual"><span class="bulk-product-placeholder" aria-hidden="true">▧</span>${thumb ? `<img src="${escapeHtml(thumb)}" alt="" loading="lazy" onerror="this.hidden=true">` : ''}
      <span class="bulk-product-badge">${success ? '✓' : failed ? '!' : active ? '◌' : '·'} ${escapeHtml(status)}</span>
      <div class="bulk-product-overlay"><div><span>${escapeHtml(progress)}</span>${percent !== null && !failed ? `<b>${percent}%</b>` : ''}</div><div class="bulk-product-track ${active && percent === null ? 'is-indeterminate' : ''}"><span style="width:${percent === null ? 0 : percent}%"></span></div></div>
    </div><div class="bulk-product-info"><strong title="${escapeHtml(item.title)}" translate="no">${escapeHtml(item.title)}</strong>
      ${shopName ? `<small title="${escapeHtml(shopName)}">${escapeHtml(shopName)}</small>` : '<small>原价换汇 · 库存 999 · 自动匹配类目</small>'}
      ${renderProductMainImageProgress(workflow, true)}
      ${error ? `<details class="bulk-product-error"><summary>${success ? '查看状态同步提示' : '查看失败原因'}</summary><p>${escapeHtml(error)}</p></details>` : ''}
      ${retries ? `<small class="bulk-product-retry">${escapeHtml(retries)}</small>` : ''}</div></section>`;
}

function productBulkShopIds() {
  const select = document.querySelector('#product-bulk-shop');
  return select.multiple ? [...select.selectedOptions].map(option=>option.value).filter(Boolean)
    : select.value ? [select.value] : [];
}

function setProductBulkShopIds(ids) {
  const select = document.querySelector('#product-bulk-shop');
  if (select.multiple) {
    for (const option of select.options) option.selected = ids.includes(option.value);
  } else select.value = ids[0] || '';
}

function renderProductBulkShopPicker(shops) {
  const ids = productBulkShopIds();
  const names = ids.map(id=>shops.find(shop=>String(shop.id) === id)?.name
    || shops.find(shop=>String(shop.id) === id)?.shopName || id);
  document.querySelector('#product-bulk-shop-summary').textContent = names.length
    ? `已选 ${names.length} 家 · ${names.join('、')}` : '请选择目标店铺（可多选）';
  document.querySelector('#product-bulk-shop-options').innerHTML = [...document.querySelector('#product-bulk-shop').options]
    .filter(option=>option.value).map(option=>`<label><input type="checkbox" value="${escapeHtml(option.value)}"
      ${ids.includes(option.value) ? 'checked' : ''}><span>${escapeHtml(option.text || option.label || option.value)}</span></label>`).join('')
    || '<p>暂无可发布的授权店铺</p>';
}

function renderProductBulkProgress() {
  const shops = crossPlatformPublishShops();
  const summary = document.querySelector('#product-bulk-defaults');
  if (summary && typeof ProductListingTemplates !== 'undefined') {
    const shopId = productBulkShopIds()[0];
    const target = shops.find(shop => String(shop.id) === shopId)?.platform;
    const template = target && ProductListingTemplates.get(target);
    summary.textContent = productBulkShopIds().length > 1 ? '各店铺独立应用对应平台模板、类目与物流 · 库存 999 · 原售价换汇'
      : template?.enabled
      ? `${template.name} · ${Number(template.weightGrams)/1000} kg · ${template.packageLengthCm} × ${template.packageWidthCm} × ${template.packageHeightCm} cm · 库存 999 · 自动匹配类目与物流`
      : '自动套用目标平台配置 · 库存 999 · 默认无品牌 · 自动匹配类目与物流 · 原售价换汇';
  }
  replaceHtmlPreservingMedia(document.querySelector("#product-bulk-progress"), productBulkItems.map(item => renderProductBulkCard(item, shops)).join(''));
  const selection = document.querySelector('.product-bulk-selection');
  if (selection) selection.hidden = productBulkItems.length > 0;
  const pending = productBulkItems.some(item => !item.workflow?.id);
  const reconnect = productBulkItems.some(item => item.progressPaused || item.syncPaused);
  const button = document.querySelector('#submit-product-bulk');
  const changeable = productBulkItems.filter(ProductBulkUpload.canChangeShop);
  document.querySelector('#product-bulk-settings').disabled = productBulkBusy || (productBulkItems.length > 0 && !changeable.length);
  globalThis.ProductMainImageStyles?.syncBulkLock(productBulkBusy || (productBulkItems.length > 0 && !changeable.length));
  const shopHint = document.querySelector('#product-bulk-shop-hint');
  if (shopHint) shopHint.textContent = productBulkShopIds().length > 1
    ? `已选择 ${productBulkShopIds().length} 家店铺，每件商品分别上传；进度与结果按店铺独立记录。`
    : changeable.length
    ? `可切换店铺，${changeable.length} 件未提交商品将按新店铺重新准备。${changeable.length < productBulkItems.length ? '已提交商品保留原店铺。' : ''}` : '';
  button.textContent = productBulkBusy ? '正在自动准备并提交…' : productBulkItems.length ? pending ? '重试未提交商品' : reconnect ? '重试进度同步' : '全部商品已提交' : '一键上传所选商品';
  button.disabled = productBulkBusy || ProductBulkSizeChart.isUploading() || (productBulkItems.length > 0 && !pending && !reconnect) || !productBulkShopIds().length;
  const chartShop = shops.find(shop => productBulkShopIds().includes(String(shop.id)) && normalizedMarketplacePlatform(shop.platform) === 'SHOPEE')
    || shops.find(shop => String(shop.id) === productBulkShopIds()[0]);
  ProductBulkSizeChart.render({busy:productBulkBusy,editable:!productBulkItems.length || changeable.length > 0,
    platform:normalizedMarketplacePlatform(chartShop?.platform || 'SHOPEE')});
  renderProductBulkShopPicker(shops);
}

function productBulkSelectedShop() {
  const item = productBulkItems.find(ProductBulkUpload.canChangeShop) || productBulkItems[0];
  return item?.workflow?.shopId || item?.payload?.shopId || item?.shopId || '';
}

function changeProductBulkShop() {
  const ids = productBulkShopIds();
  const restore = () => setProductBulkShopIds([...new Set(productBulkItems.map(item=>item.shopId))]);
  if (productBulkBusy || currentUser?.organizationId !== productBulkOrganization || !hasAnyPermission('product:publish')) {
    if (productBulkItems.length) restore();
    return;
  }
  if (!productBulkItems.length) { renderProductBulkProgress(); return; }
  try {
    if (!ids.length) throw Error('请至少选择一家目标店铺');
    if (ids.some(id=>!crossPlatformPublishShops().some(shop => String(shop.id) === id)
      && !(id === 'dry-run-shop' && !marketplaceIntegrationStatus.livePublishingEnabled)
      && !productBulkItems.some(item=>item.shopId === id && !ProductBulkUpload.canChangeShop(item)))) throw Error('目标店铺当前不可发布，请检查店铺授权');
    const nextItems = ids.length === 1 && new Set(productBulkItems.map(item=>item.shopId)).size === 1
      ? ProductBulkUpload.changeShop(productBulkItems, ids[0], newProductIdempotencyKey())
      : ProductBulkUpload.changeShops(productBulkItems, ids, newProductIdempotencyKey());
    if (nextItems !== productBulkItems) {
      // Persist first so a storage failure cannot lose the original request/key.
      saveProductBulkItems(nextItems);
      stopProductBulkPolling();
      productBulkItems = nextItems;
      document.querySelector('#product-bulk-error').textContent = '';
      void pollProductBulkProgress();
    } else restore();
  } catch (error) {
    restore();
    document.querySelector('#product-bulk-error').textContent = error.message;
  }
  renderProductBulkProgress();
}

async function pollProductBulkProgress() {
  const org = productBulkOrganization, items = productBulkItems;
  if (!document.querySelector('#product-bulk-dialog').open || currentUser?.organizationId !== org) return;
  if (!productBulkPollSession || productBulkPollSession.items !== items || productBulkPollSession.org !== org) {
    stopProductBulkPolling();
    productBulkPollSession = {org, items, generation: productBulkPollGeneration,
      controller: new AbortController(), polling: false, syncs: new Map(), pollErrors: new Map(),
      styleProgressReads: new Set(), syncActive: 0, requested: false};
    for (const item of items) { item.progressPaused = false; item.syncPaused = false; }
  }
  const session = productBulkPollSession;
  if (session.polling) { session.requested = true; return; }
  clearTimeout(productBulkPollTimer);
  productBulkPollTimer = null;
  session.polling = true;
  session.requested = false;
  const needsProgress = item => productPublishWorkflowIsActive(item.workflow)
    || item.workflow?.mainImageStyle && !session.styleProgressReads.has(item.workflow.id);
  const active = items.filter(item => item.workflow?.id && needsProgress(item)
    && !item.progressPaused && !(session.pollErrors.get(item)?.retryAt > Date.now()));
  let cursor = 0;
  for (const item of items) syncProductBulkCapture(item, session);
  try {
    await Promise.all(Array.from({length: Math.min(3, active.length)}, async () => {
      while (cursor < active.length && productBulkPollIsCurrent(session)) {
        const item = active[cursor++];
        const workflowId = item.workflow.id;
        try {
          const result = await productBulkApi(productPublishWorkflowPath(item.captureId, workflowId), {}, {
            signal: session.controller.signal, timeoutMs: 30000, retry: false
          });
          if (!productBulkPollIsCurrent(session)) return;
          if (result?.data?.id !== workflowId || result.data.captureId !== item.captureId) throw Error('上传进度响应不完整');
          session.styleProgressReads.add(workflowId);
          item.workflow = result.data; item.status = result.data.status; item.error = '';
          item.retryMessage = ''; session.pollErrors.delete(item);
          saveProductBulkItems();
          renderProductBulkProgress();
          syncProductBulkCapture(item, session);
        } catch (error) {
          if (!productBulkPollIsCurrent(session)) return;
          if (error?.status === 401) { stopProductBulkPolling(); return; }
          const attempts = (session.pollErrors.get(item)?.attempts || 0) + 1;
          if (ProductBulkUpload.isRetryableReadError(error) && attempts < 3) {
            const delayMs = attempts === 1 ? 2000 : 5000;
            session.pollErrors.set(item, {attempts, retryAt: Date.now() + delayMs});
            item.error = '进度读取暂时失败，后台任务仍会继续';
            recordProductBulkRetry(item, '读取上传进度', {attempt: attempts + 1, maxAttempts: 3, delayMs});
          } else {
            item.progressPaused = true;
            item.error = `进度读取失败，已尝试 ${attempts} 次；后台任务仍会继续，请点击重试进度同步`;
          }
          renderProductBulkProgress();
        }
      }
    }));
  } finally {
    session.polling = false;
    if (productBulkPollIsCurrent(session)) {
      const needsSync = items.some(item => item.workflow?.id && !productPublishWorkflowIsActive(item.workflow)
        && !session.syncs.get(item)?.done && !session.syncs.get(item)?.paused);
      if (session.requested || productBulkBusy || needsSync || items.some(item => item.workflow?.id && needsProgress(item) && !item.progressPaused)) {
        const retryTimes = [...session.pollErrors.values(), ...session.syncs.values()]
          .filter(state => state.retryAt > Date.now()).map(state => state.retryAt - Date.now());
        scheduleProductBulkPolling(session, session.requested ? 0 : Math.min(3000, ...retryTimes));
      }
    }
  }
}

async function openProductBulkUpload(historyKey, captureId) {
  if (!hasAnyPermission("product:publish")) return;
  if (typeof historyKey !== 'string') historyKey = '';
  const directCapture = typeof captureId === 'string' ? productCaptures.find(item => item.id === captureId) : null;
  if (captureId && !productCaptureCanUpload(directCapture)) return;
  if (!historyKey && !directCapture && !productBulkSelectedIds.size) return;
  const org = currentUser?.organizationId;
  const view = ++productBulkViewGeneration;
  let saved = historyKey ? ProductBulkHistory.list(localStorage, org).find(record => record.key === historyKey) : null;
  if (historyKey && !saved) return;
  const selectedIds = saved ? [...new Set(saved.items.map(item => item.captureId))] : directCapture ? [directCapture.id] : [...productBulkSelectedIds];
  await loadMarketplaceShops({silent:true});
  if (!org || currentUser?.organizationId !== org || view !== productBulkViewGeneration) return;
  if (historyKey) {
    saved = ProductBulkHistory.list(localStorage, org).find(record => record.key === historyKey);
    if (!saved) return;
  }
  stopProductBulkPolling();
  productBulkOrganization = org;
  productBulkStorageKey = historyKey || ProductBulkHistory.storageKey(org, newProductIdempotencyKey());
  const run = productBulkRuns.get(productBulkStorageKey);
  productBulkItems = run?.items || saved?.items || [];
  productBulkBusy = Boolean(run);
  ProductBulkSizeChart.open(org,productBulkStorageKey,productBulkItems);
  void globalThis.ProductMainImageStyles?.loadBulk(productBulkItems, {org, view});
  const shops = crossPlatformPublishShops();
  const select = document.querySelector('#product-bulk-shop');
  select.innerHTML = `<option value="">${shops.length ? '请选择目标店铺' : '没有可发布的授权店铺'}</option>` + shops.map(shop => `<option value="${escapeHtml(shop.id)}">${escapeHtml(shop.name || shop.shopName || shop.externalShopId)} · ${escapeHtml(shop.region || '')} · ${escapeHtml(shop.platform === 'TIKTOK_SHOP' ? 'TikTok Shop' : 'Shopee')}</option>`).join('');
  const selected = selectedIds.map(id => productCaptures.find(item => item.id === id) || productBulkSelectedCaptures.get(id)
    || (saved && {id, title:saved.items.find(item => item.captureId === id)?.title})).filter(Boolean);
  const republishing = selected.some(item => item.status === 'PUBLISHED');
  setProductBulkShopIds(shops.length === 1 && !republishing ? [String(shops[0].id)] : []);
  if (!marketplaceIntegrationStatus.livePublishingEnabled && !shops.length) {
    select.innerHTML = '<option value="dry-run-shop">发布演练（不连接店铺）</option>';
    setProductBulkShopIds(['dry-run-shop']);
  }
  document.querySelector('#product-bulk-mode').textContent = marketplaceIntegrationStatus.livePublishingEnabled
    ? republishing ? '请重新选择目标店铺；已提交商品复用现有图片，同店重复提交会核对已有记录。' : '上传到店铺待上架目录，后续上架由你决定。'
    : '当前为发布演练模式，不会在店铺创建商品。';
  document.querySelector('#product-bulk-summary').textContent = `已选择 ${selected.length} 件商品`;
  document.querySelector('#product-bulk-items').innerHTML = selected.map(item => renderProductBulkCard(item, [], true)).join('');
  const restored = productBulkItems.length > 0;
  if (restored) {
    const shopIds = [...new Set([productBulkSelectedShop(), ...productBulkItems.map(item=>item.workflow?.shopId || item.payload?.shopId || item.shopId)].filter(Boolean))];
    for (const shopId of shopIds) if (![...select.options].some(option => option.value === shopId)) select.add(new Option('原任务店铺（保留上传记录）', shopId));
    setProductBulkShopIds(shopIds);
  }
  document.querySelector('#product-bulk-error').textContent = shops.length || !marketplaceIntegrationStatus.livePublishingEnabled || restored ? '' : '请先在店铺管理中连接可发布的 Shopee 或 TikTok Shop 店铺。';
  renderProductBulkProgress();
  if (!document.querySelector('#product-bulk-dialog').open) document.querySelector('#product-bulk-dialog').showModal();
  void pollProductBulkProgress();
}

async function submitProductBulkUpload(event) {
  event.preventDefault();
  if (productBulkBusy || ProductBulkSizeChart.isUploading() || !hasAnyPermission('product:publish')) return;
  const org = productBulkOrganization;
  const key = productBulkStorageKey;
  if (currentUser?.organizationId !== org) return;
  if (productBulkRuns.has(key)) return;
  const reconnect = productBulkItems.some(item => item.progressPaused || item.syncPaused);
  if (reconnect) {
    stopProductBulkPolling();
    for (const item of productBulkItems.filter(item => item.progressPaused || item.syncPaused)) {
      item.error = ''; item.syncError = ''; item.retryMessage = '';
    }
  }
  if (reconnect && productBulkItems.every(item => item.workflow?.id)) {
    void pollProductBulkProgress();
    renderProductBulkProgress();
    return;
  }
  productBulkBusy = true;
  document.querySelector('#product-bulk-error').textContent = '';
  renderProductBulkProgress();
  let run;
  try {
    if (!productBulkItems.length) {
      const items = [...document.querySelectorAll('[data-bulk-item]')].map(row => ({id: row.dataset.bulkItem, title: row.querySelector('strong').textContent}));
      productBulkItems = ProductBulkUpload.plan(items, productBulkShopIds(), newProductIdempotencyKey());
      for (const item of productBulkItems) {
        item.thumbnailUrl = productBulkThumbnail(item);
        item.sizeChartImageKey = ProductBulkSizeChart.key();
        const styleId = document.querySelector('#product-bulk-main-style')?.value;
        if (styleId) item.mainImageStyleTemplateId = styleId;
      }
    }
    const items = productBulkItems;
    run = {items, org, key};
    productBulkRuns.set(key, run);
    const isCurrent = () => currentUser?.organizationId === org && productBulkRuns.get(key) === run;
    const isVisible = () => productBulkItems === items && productBulkStorageKey === key;
    const recordRetry = (item, label, details) => recordProductBulkRetry(item, label, details, run);
    await ProductListingTemplates.ensure(true);
    if (!isCurrent()) return;
    const shopOptions = new Map();
    const readShopOptions = shopId => {
      if (!shopOptions.has(shopId)) {
        // Allow extra time for the official category and logistics reads while
        // keeping a complete request/body deadline for an unresponsive service.
        shopOptions.set(shopId, ProductBulkUpload.retryRead(async () => {
          let result;
          try {
            result = await productBulkApi(`/api/v1/shops/${encodeURIComponent(shopId)}/publish-options`, {}, {
              timeoutMs: 180000, isCurrent, retry: false
            });
          } catch (error) {
            // This endpoint only reads official options. A backend 500 can wrap
            // Shopee logistics.inner_error; retry reads, never workflow POSTs.
            if (Number(error.status) === 500) throw new ApiRequestError(error.message, 503);
            throw error;
          }
          if (!result?.data) throw new ApiRequestError('店铺类目与配送渠道响应不完整', 503);
          const platform = normalizedMarketplacePlatform(crossPlatformPublishShops().find(shop => String(shop.id) === String(shopId))?.platform);
          if (platform === 'SHOPEE') {
            const channels = result.data.logistics?.logistics_channel_list;
            if (!Array.isArray(channels) || channels.length === 0) {
              throw new ApiRequestError('Shopee 暂未返回配送渠道，可能正在同步或接口暂时异常；请稍后重试。此提示不代表店铺未开启物流。', 503);
            }
          }
          return result.data;
        }, {
          maxAttempts: 5, isCurrent,
          onRetry: details => {
            for (const pending of items.filter(item => item.shopId === shopId && item.status === 'PREPARING')) recordRetry(pending, '读取店铺配置', details);
          }
        }));
      }
      return shopOptions.get(shopId);
    };
    const normalizedCaptures = new Map();
    const prepare = async item => {
      item.retryMessage = '';
      if (selectedProductCaptureId === item.captureId && (productEditorDirty || productCaptureSaveInFlightId)) {
        throw Error('此商品有尚未保存的修改，请先保存后重试上传');
      }
      const captureRequest = (path, options) => productBulkCaptureApi(path, options, {
        isCurrent, onRetry: details => recordRetry(item, '读取商品资料', details)
      });
      const result = await captureRequest(`/${encodeURIComponent(item.captureId)}`);
      if (!isCurrent()) return;
      let capture = productCapturePayload(result.data);
      if (capture?.id !== item.captureId) throw Error('商品详情读取失败，请重试');
      const targetShop = crossPlatformPublishShops().find(shop => String(shop.id) === item.shopId);
      const platform = normalizedMarketplacePlatform(targetShop?.platform || item.payload?.destinationPlatform || 'SHOPEE');
      const base = {confirmed: true, destinationPlatform: platform, shopId: item.shopId, idempotencyKey: item.idempotencyKey,
        ...ProductBulkUpload.stylePayload(item),
        ...(platform === 'SHOPEE' && item.sizeChartImageKey ? {sizeChartImageKey:item.sizeChartImageKey} : {})};
      let payload = base;
      if (marketplaceIntegrationStatus.livePublishingEnabled) {
        if (!targetShop) throw Error('目标店铺当前不可发布，请检查店铺授权');
        let shopOptions;
        try {
          shopOptions = await readShopOptions(item.shopId);
          if (!isCurrent()) return;
          if (platform === 'TIKTOK_SHOP') requireTikTokDraftCapability(targetShop, shopOptions);
        } catch (error) {
          throw Error(`${targetShop.name || targetShop.externalShopId || item.shopId}：${error.message}`);
        }
        const common = readPublishPreference(false, capture, item.shopId, platform, org);
        const category = readPublishPreference(true, capture, item.shopId, platform, org);
        let resolved;
        try {
          resolved = resolveDestinationPublishOptions(platform, capture, shopOptions, common, category);
        } catch (error) {
          throw Error(`${targetShop.name || targetShop.externalShopId || item.shopId}：${error.message}`);
        }
        if (!resolved.categoryId) throw Error(`原商品类目“${capture.categoryName || '未采集'}”无法匹配目标店铺类目，未提交此商品`);
        if (platform === 'TIKTOK_SHOP' && !resolved.warehouseId) throw Error('目标店铺没有唯一或默认发货仓库，请在店铺后台设置默认仓库');
        if (platform === 'SHOPEE' && !resolved.logisticInfo.length) throw Error('Shopee 返回的配送渠道均不可用，请核对卖家中心物流设置后重试');
        payload = {...base, ...automaticProductPublishDefaults(capture, common, platform), categoryId: resolved.categoryId,
          ...(platform === 'TIKTOK_SHOP' ? {warehouseId: resolved.warehouseId} : {logisticInfo: resolved.logisticInfo})};
      }
      if (!normalizedCaptures.has(item.captureId)) {
        const normalization = normalizeProductCaptureForUpload(capture, isCurrent, captureRequest);
        normalizedCaptures.set(item.captureId, normalization);
        normalization.catch(()=>{if (normalizedCaptures.get(item.captureId) === normalization) normalizedCaptures.delete(item.captureId);});
      }
      capture = await normalizedCaptures.get(item.captureId);
      if (!isCurrent()) return;
      if (capture?.id) updateProductCaptureInMemory(capture, {preserveSelectedEditor: productEditorDirty || Boolean(productCaptureSaveInFlightId)});
      return payload;
    };
    await ProductBulkUpload.submit(items, {
      save: () => saveProductBulkItems(items, key, org), prepare, isCurrent, concurrency: 3,
      beforeNextShop: async (item, previous) => {
        if (!previous.workflow?.id) return;
        item.waitMessage = '等待同商品前一家店铺完成';
        if (isVisible()) renderProductBulkProgress();
        const started = Date.now();
        try {
          while (isCurrent() && productPublishWorkflowIsActive(previous.workflow)) {
            const result = await productBulkApi(productPublishWorkflowPath(previous.captureId, previous.workflow.id), {}, {
              isCurrent, timeoutMs:30000
            });
            if (!isCurrent()) return;
            if (result?.data?.id !== previous.workflow.id || result.data.captureId !== previous.captureId)
              throw Error('前一家店铺上传进度返回不完整，请重试');
            previous.workflow = result.data; previous.status = result.data.status;
            saveProductBulkItems(items, key, org);
            if (isVisible()) renderProductBulkProgress();
            if (!productPublishWorkflowIsActive(previous.workflow)) break;
            if (Date.now() - started > 30*60*1000) throw Error('前一家店铺仍在处理中，请稍后重试未提交商品');
            await new Promise(resolve=>setTimeout(resolve,3000));
          }
          normalizedCaptures.delete(item.captureId);
        } finally {item.waitMessage = '';}
      },
      create: async (captureId, payload, key) => {
        const item = items.find(candidate => candidate.captureId === captureId && candidate.idempotencyKey === key);
        item.retryMessage = '';
        const workflow = await createProductPublishWorkflow(captureId, payload, key, {
          requestApi: (path, options) => productBulkApi(path, options, {isCurrent}),
          allowTargetRecovery: false, maxAttempts: 3, isCurrent,
          onRetry: details => recordRetry(item, '提交上传任务', details)
        });
        if (isCurrent()) item.retryMessage = '';
        return workflow;
      },
      changed: () => {
        if (isVisible()) {
          renderProductBulkProgress();
          void pollProductBulkProgress();
        }
        renderProductBulkHistory();
      }
    });
    if (isCurrent() && isVisible()) void pollProductBulkProgress();
  } catch (error) {
    if (currentUser?.organizationId === org && productBulkStorageKey === key) document.querySelector('#product-bulk-error').textContent = error.message;
  } finally {
    if (productBulkRuns.get(key) === run) productBulkRuns.delete(key);
    if (currentUser?.organizationId === org && productBulkStorageKey === key) {
      productBulkBusy = false;
      renderProductBulkProgress();
    }
    renderProductBulkHistory();
  }
}

let productBulkHistorySession = null;

function renderProductBulkHistory() {
  const dialog = document.querySelector('#product-bulk-history-dialog');
  if (!dialog?.open || !currentUser?.organizationId || typeof ProductBulkHistory === 'undefined') return;
  document.querySelector('#product-bulk-history-list').innerHTML = ProductBulkHistory.render(
    ProductBulkHistory.list(localStorage, currentUser.organizationId));
}

function stopProductBulkHistoryPolling() {
  if (!productBulkHistorySession) return;
  clearTimeout(productBulkHistorySession.timer);
  productBulkHistorySession.controller.abort();
  productBulkHistorySession = null;
}

async function pollProductBulkHistory(session) {
  const current = () => productBulkHistorySession === session && !session.controller.signal.aborted
    && currentUser?.organizationId === session.org && document.querySelector('#product-bulk-history-dialog').open;
  if (!current()) return;
  const records = ProductBulkHistory.list(localStorage, session.org);
  const pending = records.flatMap(record => record.items.filter(item => item.workflow?.id && productPublishWorkflowIsActive(item.workflow))
    .map(item => ({key:record.key, captureId:item.captureId, workflowId:item.workflow.id})));
  let cursor = 0;
  await Promise.all(Array.from({length:Math.min(3,pending.length)}, async () => {
    while (current() && cursor < pending.length) {
      const target = pending[cursor++];
      try {
        const result = await productBulkApi(productPublishWorkflowPath(target.captureId, target.workflowId), {},
          {signal:session.controller.signal, timeoutMs:30000, retry:false});
        if (!current()) return;
        if (result?.data?.id !== target.workflowId || result.data.captureId !== target.captureId) throw Error('上传进度响应不完整');
        // Merge into the latest batch so a late read cannot undo another item's submission.
        const items = productBulkStorageKey === target.key && productBulkOrganization === session.org
          ? productBulkItems : productBulkRuns.get(target.key)?.items
            || ProductBulkHistory.list(localStorage, session.org).find(record => record.key === target.key)?.items;
        const item = items?.find(item => item.captureId === target.captureId && item.workflow?.id === target.workflowId);
        if (!item) continue;
        item.workflow = result.data; item.status = result.data.status;
        item.error = ''; item.historySyncError = '';
        saveProductBulkItems(items, target.key, session.org);
      } catch (error) {
        if (!current()) return;
        if (error?.status === 401) { stopProductBulkHistoryPolling(); return; }
        // Keep the last confirmed outcome; a network error is not an upload failure.
      }
      renderProductBulkHistory();
    }
  }));
  if (current()) session.timer = setTimeout(() => void pollProductBulkHistory(session), 3000);
}

function openProductBulkHistory() {
  if (!hasAnyPermission('product:publish') || !currentUser?.organizationId) return;
  stopProductBulkHistoryPolling();
  const detail = document.querySelector('#product-bulk-dialog');
  if (detail.open) detail.close();
  const dialog = document.querySelector('#product-bulk-history-dialog');
  if (!dialog.open) dialog.showModal();
  renderProductBulkHistory();
  const session = {org:currentUser.organizationId, controller:new AbortController(), timer:null};
  productBulkHistorySession = session;
  void pollProductBulkHistory(session);
}

document.querySelector('#open-product-bulk-upload').addEventListener('click', () => void openProductBulkUpload());
document.querySelector('#open-product-bulk-history').addEventListener('click', openProductBulkHistory);
document.querySelector('#open-product-bulk-history-from-detail').addEventListener('click', openProductBulkHistory);
document.querySelector('#close-product-bulk-history').addEventListener('click', () => document.querySelector('#product-bulk-history-dialog').close());
document.querySelector('#product-bulk-history-dialog').addEventListener('close', stopProductBulkHistoryPolling);
document.querySelector('#product-bulk-history-list').addEventListener('click', event => {
  const button = event.target.closest('[data-product-bulk-history-key]');
  if (!button) return;
  document.querySelector('#product-bulk-history-dialog').close();
  void openProductBulkUpload(button.dataset.productBulkHistoryKey);
});
document.querySelector('#product-bulk-form').addEventListener('submit', submitProductBulkUpload);
document.querySelector('#product-bulk-shop').addEventListener('change', changeProductBulkShop);
document.querySelector('#product-bulk-shop-options').addEventListener('change', event => {
  if (!event.target.matches('input[type="checkbox"]')) return;
  const select = document.querySelector('#product-bulk-shop');
  if (productBulkBusy || document.querySelector('#product-bulk-settings').disabled) return;
  const option = [...select.options].find(candidate=>candidate.value === event.target.value);
  if (option) option.selected = event.target.checked;
  changeProductBulkShop();
});
document.querySelector('#close-product-bulk').addEventListener('click', () => document.querySelector('#product-bulk-dialog').close());
document.querySelector('#product-bulk-dialog').addEventListener('close', stopProductBulkPolling);
document.querySelector('#product-bulk-dialog').addEventListener('close', () => ProductBulkSizeChart.close());
ProductBulkSizeChart.init({api,getOrganization:()=>currentUser?.organizationId,
  canPublish:()=>hasAnyPermission('product:publish'),changed:renderProductBulkProgress,
  selected:sizeChartImageKey=>{
    for (const item of productBulkItems.filter(ProductBulkUpload.canChangeShop)) item.sizeChartImageKey=sizeChartImageKey;
    if (productBulkItems.length) saveProductBulkItems();
  }});

async function submitProductPublish(event) {
  event.preventDefault();
  if (!selectedProductCaptureId || !hasAnyPermission("product:publish") || productUploadInFlightCaptureId) return;
  const captureId = selectedProductCaptureId;
  const organizationId = currentUser?.organizationId || "";
  const confirmed = document.querySelector("#publish-confirmed").checked;
  const destinationPlatform = document.querySelector("#publish-destination-platform").value;
  const shopId = document.querySelector("#publish-shop-id").value.trim();
  const errorLabel = document.querySelector("#product-publish-error");
  if (!confirmed || !shopId) {
    errorLabel.textContent = "请选择目标店铺，并勾选人工确认。";
    errorLabel.hidden = false;
    return;
  }
  if (marketplaceIntegrationStatus.livePublishingEnabled && shopId === "dry-run-shop") {
    errorLabel.textContent = "真实发布已启用，请重新选择已授权店铺。";
    errorLabel.hidden = false;
    return;
  }
  const button = document.querySelector("#submit-product-publish");
  const idempotencyKey = productPublishIdempotencyKey || newProductIdempotencyKey();
  let payload;
  try {
    payload = buildProductPublishPayload(destinationPlatform, shopId, idempotencyKey);
  } catch (error) {
    errorLabel.classList.remove("is-success");
    errorLabel.textContent = error.message;
    errorLabel.hidden = false;
    return;
  }
  productPublishIdempotencyKey = idempotencyKey;
  productUploadInFlightCaptureId = captureId;
  if (selectedProductCaptureDetail?.id === captureId) {
    syncProductEditorAccess(selectedProductCaptureDetail);
    syncProductActions(selectedProductCaptureDetail);
  }
  setProductUploadFormBusy(true);
  button.textContent = "正在准备一键上传…";
  errorLabel.classList.remove("is-success");
  errorLabel.hidden = true;
  let activeStep = "";
  let workflowAccepted = false;
  try {
    let workingCapture = selectedProductCaptureDetail;
    let status = String(workingCapture?.status || "CAPTURED").toUpperCase();
    if (["CAPTURED", "FAILED", "READY"].includes(status)
        && productSkuPriceNormalizationNeeded(workingCapture)) {
      activeStep = "IMAGES";
      if (!hasAnyPermission("product:edit")) {
        throw new ApiRequestError("SKU 原价或商品价格区间需要自动补齐，但账号没有商品编辑权限", 403);
      }
      setProductUploadStep("IMAGES", "running", "正在同步 SKU 价格…");
      button.textContent = "正在同步规格与价格…";
      let patchResult;
      try {
        patchResult = await productCaptureApi(`/${encodeURIComponent(captureId)}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(productCapturePatchPayload(workingCapture, { forceSkus: true }))
        });
      } catch (patchError) {
        // PATCH itself is versioned but not idempotent. If its response was lost
        // (or another request won the version race), read the latest capture and
        // continue only when the requested normalization is already present.
        if (patchError?.status !== 409 && !productPublishWorkflowCreateMayHaveCommitted(patchError)) {
          throw patchError;
        }
        try {
          const latestResult = await productCaptureApi(`/${encodeURIComponent(captureId)}`);
          const latestCapture = productCapturePayload(latestResult.data);
          if (!latestCapture?.id || productSkuPriceNormalizationNeeded(latestCapture)) throw patchError;
          patchResult = { data: latestCapture };
        } catch (refreshError) {
          if (refreshError === patchError || refreshError?.status === 401) throw refreshError;
          throw patchError;
        }
      }
      if (!currentUser || (currentUser.organizationId || "") !== organizationId) return;
      workingCapture = productCapturePayload(patchResult.data);
      status = String(workingCapture?.status || "CAPTURED").toUpperCase();
      if (workingCapture?.id) updateProductCaptureInMemory(workingCapture);
    }
    activeStep = "";
    renderProductUploadSteps(workingCapture);
    button.textContent = "正在创建后台一键上传任务…";
    const workflow = await createProductPublishWorkflow(captureId, payload, idempotencyKey);
    rememberPublishPreferences();
    if (!currentUser || (currentUser.organizationId || "") !== organizationId) return;
    if (selectedProductCaptureId !== captureId) return;
    workflowAccepted = acceptProductPublishWorkflow(workflow, organizationId);
    if (!workflowAccepted) return;
    if (productPublishWorkflowIsActive(workflow)) pollProductPublishWorkflow(workflow);
  } catch (error) {
    if (error?.status === 401) return;
    if (!currentUser || (currentUser.organizationId || "") !== organizationId
        || selectedProductCaptureId !== captureId) return;
    if (activeStep === "IMAGES") {
      try {
        await selectProductCapture(captureId, { silent: true, force: true });
      } catch (_refreshError) {
        // Keep the normalization error below; the normal refresh control remains available.
      }
    }
    if (activeStep) setProductUploadStep(activeStep, "failed", "失败，请查看原因");
    const stageName = activeStep === "IMAGES" ? "规格与价格同步" : "后台任务创建";
    errorLabel.classList.remove("is-success");
    errorLabel.textContent = `${stageName}失败：${error.message}`;
    errorLabel.hidden = false;
  } finally {
    if (!workflowAccepted && currentUser
        && (currentUser.organizationId || "") === organizationId
        && selectedProductCaptureId === captureId) {
      if (productUploadInFlightCaptureId === captureId) productUploadInFlightCaptureId = "";
      setProductUploadFormBusy(false);
      button.disabled = false;
      button.textContent = marketplaceIntegrationStatus.livePublishingEnabled
        ? "重试并提交平台刊登" : "重试一键上传（Dry Run）";
      if (selectedProductCaptureDetail) {
        syncProductEditorAccess(selectedProductCaptureDetail);
        syncProductActions(selectedProductCaptureDetail);
      }
    }
  }
}

function parseProductPublishJson(selector, label, fallback = {}, expectedType = "object") {
  const value = document.querySelector(selector).value.trim();
  if (!value) return fallback;
  try {
    const parsed = JSON.parse(value);
    const validType = expectedType === "array"
      ? Array.isArray(parsed)
      : parsed && typeof parsed === "object" && !Array.isArray(parsed);
    if (!validType) throw new Error("unexpected JSON type");
    return parsed;
  } catch (_error) {
    throw new ApiRequestError(`${label}必须是 JSON ${expectedType === "array" ? "数组" : "对象"}`, 400);
  }
}

function parseShopeeLogisticInfo() {
  const logisticInfo = parseProductPublishJson("#publish-logistic-info", "Shopee 官方物流渠道", [], "array");
  if (!logisticInfo.length) {
    throw new ApiRequestError("Shopee 官方物流渠道为必填项，请填写至少一个物流渠道", 400);
  }
  return logisticInfo;
}

function parseProductPublishStock(platform) {
  const max = platform === "TIKTOK_SHOP" ? 99999 : 999999;
  const text = document.querySelector("#publish-stock").value.trim();
  if (!/^\d+$/.test(text)) {
    throw new ApiRequestError(`上架库存必须是 0 到 ${max} 的整数`, 400);
  }
  const stock = Number(text);
  if (!Number.isSafeInteger(stock) || stock > max) {
    throw new ApiRequestError(`上架库存必须是 0 到 ${max} 的整数`, 400);
  }
  return stock;
}

function parseProductSkuStocks(selector, platform) {
  const stocks = parseProductPublishJson(selector, "SKU 库存映射", {}, "object");
  const normalized = {};
  const max = platform === "TIKTOK_SHOP" ? 99999 : 999999;
  const allowedKeys = new Set(productCaptureSkus(selectedProductCaptureDetail)
    .map(sku => String(sku.skuKey || "").trim())
    .filter(Boolean));
  Object.entries(stocks).forEach(([rawKey, rawValue]) => {
    const key = rawKey.trim();
    if (!key) throw new ApiRequestError("SKU 库存映射不能包含空键", 400);
    if (allowedKeys.size && !allowedKeys.has(key)) {
      throw new ApiRequestError(`“${key}”不是当前商品的 SKU Key，请使用发布面板提示的实际 Key`, 400);
    }
    const text = typeof rawValue === "string" ? rawValue.trim() : String(rawValue);
    if (!/^\d+$/.test(text)) {
      throw new ApiRequestError(`SKU “${key}”的发布库存必须是 0 到 ${max} 的整数`, 400);
    }
    const stock = Number(text);
    if (!Number.isSafeInteger(stock) || stock > max) {
      throw new ApiRequestError(`SKU “${key}”的发布库存必须是 0 到 ${max} 的整数`, 400);
    }
    normalized[key] = stock;
  });
  return normalized;
}

function stopProductPairingCountdown() {
  if (productPairingCountdownTimer) clearInterval(productPairingCountdownTimer);
  productPairingCountdownTimer = null;
}

function updateProductPairingCountdown() {
  const expiry = document.querySelector("#extension-pairing-expiry");
  const state = document.querySelector("#extension-pairing-state");
  const copy = document.querySelector("#copy-extension-pairing-code");
  if (!productPairingExpiresAt) return;
  const seconds = Math.max(0, Math.ceil((productPairingExpiresAt.getTime() - Date.now()) / 1000));
  if (seconds <= 0) {
    stopProductPairingCountdown();
    productPairingExpiresAt = null;
    state.textContent = "已过期";
    state.className = "is-expired";
    expiry.textContent = "绑定码已过期，请重新生成";
    copy.disabled = true;
    return;
  }
  state.textContent = "等待插件绑定";
  state.className = "is-active";
  expiry.textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")} 后过期 · 仅限当前公司`;
}

async function createExtensionPairingCode() {
  if (!hasAnyPermission("product:collect")) return;
  const button = document.querySelector("#create-extension-pairing-code");
  const state = document.querySelector("#extension-pairing-state");
  button.disabled = true;
  button.textContent = "正在生成…";
  try {
    const result = await productCaptureApi("/extension/pairing-code", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}"
    });
    const pairing = result.data?.pairing || result.data || {};
    if (!pairing.code) throw new ApiRequestError("平台没有返回绑定码，请稍后重试", 502, result);
    const code = String(pairing.code);
    const codeOutput = document.querySelector("#extension-pairing-code");
    codeOutput.textContent = code;
    codeOutput.dataset.code = code;
    document.querySelector("#copy-extension-pairing-code").disabled = false;
    productPairingExpiresAt = pairing.expiresAt ? new Date(pairing.expiresAt) : new Date(Date.now() + 5 * 60 * 1000);
    stopProductPairingCountdown();
    updateProductPairingCountdown();
    productPairingCountdownTimer = window.setInterval(updateProductPairingCountdown, 1000);
  } catch (error) {
    if (error?.status === 401) return;
    state.textContent = error?.status === 403 ? "无绑定权限" : "生成失败";
    state.className = "is-expired";
    document.querySelector("#extension-pairing-expiry").textContent = error.message;
  } finally {
    button.disabled = false;
    button.textContent = "生成绑定码";
  }
}

async function deleteProductCaptureFromList(button) {
  const id = button.dataset.deleteCapture;
  const capture = productCaptures.find(item => item.id === id);
  if (!capture || !window.confirm(`确定删除采集商品“${capture.title || id}”？`)) return;
  button.disabled = true;
  button.textContent = "删除中…";
  try {
    await productCaptureApi(`/${encodeURIComponent(id)}`, {method:"DELETE"});
    productBulkSelectedIds.delete(id);
    productBulkSelectedCaptures.delete(id);
    productLiveImageProgress.delete(id);
    syncProductBulkSelection();
    if (selectedProductCaptureId === id) await selectProductCapture(null);
    await loadProductCaptures(null, {page: Math.max(0, productCapturePage), refreshDetail:false});
  } catch (error) {
    window.alert(error.message || "删除失败，请重试");
    button.disabled = false;
    button.textContent = "删除";
  }
}

document.querySelector("#toggle-product-bulk-selection").addEventListener("click", toggleProductBulkPageSelection);

document.querySelector("#product-capture-list").addEventListener("click", event => {
  const uploadButton = event.target.closest("[data-upload-capture]");
  if (uploadButton) { void openProductBulkUpload('', uploadButton.dataset.uploadCapture); return; }
  const deleteButton = event.target.closest("[data-delete-capture]");
  if (deleteButton) { void deleteProductCaptureFromList(deleteButton); return; }
  const checkbox = event.target.closest("[data-product-bulk-id]");
  if (checkbox) {
    if (checkbox.checked) {
      productBulkSelectedIds.add(checkbox.dataset.productBulkId);
      productBulkSelectedCaptures.set(checkbox.dataset.productBulkId, productCaptures.find(item => item.id === checkbox.dataset.productBulkId));
    } else {
      productBulkSelectedIds.delete(checkbox.dataset.productBulkId);
      productBulkSelectedCaptures.delete(checkbox.dataset.productBulkId);
    }
    syncProductBulkSelection();
    return;
  }
  const row = event.target.closest("[data-product-capture-id]");
  if (!row) return;
  productLastNotice = null;
  window.location.hash = `product-capture?id=${encodeURIComponent(row.dataset.productCaptureId)}`;
});

document.querySelector("#product-status-filter").addEventListener("click", event => {
  const button = event.target.closest("[data-product-status]");
  if (!button) return;
  if (productCaptureSearchDebounceTimer) clearTimeout(productCaptureSearchDebounceTimer);
  productCaptureSearchDebounceTimer = null;
  productCaptureStatusFilter = button.dataset.productStatus;
  document.querySelectorAll("#product-status-filter [data-product-status]").forEach(item => {
    const active = item === button;
    item.classList.toggle("active", active);
    item.setAttribute("aria-pressed", String(active));
  });
  loadProductCaptures(selectedProductCaptureId, { page: 0, silent: true, refreshDetail: false });
});

document.querySelector("#product-capture-search").addEventListener("input", event => {
  productCaptureSearchQuery = event.currentTarget.value;
  if (productCaptureSearchDebounceTimer) clearTimeout(productCaptureSearchDebounceTimer);
  document.querySelector("#product-result-count").textContent = "正在准备搜索…";
  productCaptureSearchDebounceTimer = window.setTimeout(() => {
    productCaptureSearchDebounceTimer = null;
    loadProductCaptures(selectedProductCaptureId, { page: 0, silent: true, refreshDetail: false });
  }, 300);
});

function goToProductCapturePage(page) {
  if (productCaptureListLoading) return;
  loadProductCaptures(null, { page, silent: true, refreshDetail: false });
}
document.querySelector("#load-more-product-captures").addEventListener("click", () => goToProductCapturePage(productCapturePage + 1));
document.querySelector("#previous-product-captures").addEventListener("click", () => goToProductCapturePage(productCapturePage - 1));
document.querySelector("#product-page-numbers").addEventListener("click", event => {
  const button = event.target.closest("[data-capture-page]");
  if (button) goToProductCapturePage(Number(button.dataset.capturePage));
});

document.querySelector("#add-product-sku").addEventListener("click", addProductSku);
document.querySelector("#product-sku-select").addEventListener("change", event => {
  const index = Number(event.currentTarget.value);
  if (Number.isSafeInteger(index)) selectProductSku(index);
});
document.querySelector("#product-sku-search").addEventListener("input", event => {
  syncCurrentProductSkuDraftFromEditor();
  productSkuSearchQuery = event.currentTarget.value;
  renderProductSkuSelector();
  renderCurrentProductSkuEditor();
  if (selectedProductCaptureDetail) syncProductEditorAccess(selectedProductCaptureDetail);
});
document.querySelector("#product-sku-search").addEventListener("keydown", event => {
  if (event.key !== "Escape" || !event.currentTarget.value) return;
  event.preventDefault();
  event.currentTarget.value = "";
  event.currentTarget.dispatchEvent(new Event("input", { bubbles: true }));
});
document.querySelector("#product-sku-previous").addEventListener("click", () => stepProductSku(-1));
document.querySelector("#product-sku-next").addEventListener("click", () => stepProductSku(1));
document.querySelector("#product-sku-workbench").addEventListener("keydown", event => {
  if (!event.altKey || !["ArrowLeft", "ArrowRight"].includes(event.key)) return;
  event.preventDefault();
  stepProductSku(event.key === "ArrowLeft" ? -1 : 1, {
    focusField: event.target.dataset?.productSkuField || ""
  });
});
document.querySelector("#product-sku-editor").addEventListener("click", event => {
  const button = event.target.closest("[data-remove-product-sku]");
  if (!button) return;
  removeProductSku(Number(button.dataset.removeProductSku));
});
document.querySelector("#product-sku-editor").addEventListener("input", event => {
  if (!event.target.matches("[data-product-sku-field]")) return;
  const row = event.currentTarget;
  const index = Number(row.dataset.productSkuIndex);
  const draft = productSkuDraftState[index];
  if (!draft) return;
  const fieldName = event.target.dataset.productSkuField;
  draft.fields[fieldName] = event.target.value;
  draft.edited = true;
  row.dataset.productSkuEdited = "true";
  if (fieldName === "imageUrl") {
    draft.imageEdited = true;
    row.dataset.productSkuImageEdited = "true";
    const imageUrl = safeProductUrl(event.target.value.trim());
    const preview = row.querySelector(".product-sku-image-preview");
    const label = row.querySelector(".product-sku-image-copy small");
    preview.replaceChildren();
    if (imageUrl) {
      const image = document.createElement("img");
      image.src = imageUrl;
      image.alt = `SKU ${index + 1} 图片`;
      preview.append(image);
      label.textContent = "人工补录图片";
    } else {
      preview.textContent = "无图";
      label.textContent = "未提供 SKU 图";
    }
  }
  if (fieldName === "originalPrice") {
    draft.originalPriceEdited = true;
    row.dataset.productSkuOriginalPriceEdited = "true";
  }
  if (fieldName !== "stock") {
    productSkuCompletenessDirty = true;
    document.querySelector("#product-sku-manual-confirm").checked = false;
  }
  productSkuDraftDirty = true;
  productEditorDirty = true;
  if (["variationName", "externalSkuId", "sellerSku"].includes(fieldName)) {
    const label = productSkuDraftLabel(draft, index);
    const option = document.querySelector(`#product-sku-select option[value="${index}"]`);
    if (option) option.textContent = `${index + 1}. ${label}`;
    const heading = row.querySelector(".product-sku-editor-head strong");
    if (heading) heading.textContent = label;
  }
  if (!selectedProductCaptureDetail) return;
  renderProductSkuCompleteness(selectedProductCaptureDetail);
  renderProductReadiness(selectedProductCaptureDetail);
  syncProductActions(selectedProductCaptureDetail);
});
document.querySelector("#product-sku-manual-confirm").addEventListener("change", event => {
  productSkuCompletenessDirty = true;
  productEditorDirty = true;
  if (!selectedProductCaptureDetail) return;
  if (!event.currentTarget.checked) renderProductSkuCompleteness(selectedProductCaptureDetail);
  renderProductReadiness(selectedProductCaptureDetail);
  syncProductActions(selectedProductCaptureDetail);
});

[
  "#product-title-input", "#product-description-input", "#product-category-input",
  "#product-brand-input", "#product-currency-input", "#product-price-min-input",
  "#product-price-max-input"
].forEach(selector => document.querySelector(selector).addEventListener("input", () => {
  productEditorDirty = true;
  if (selectedProductCaptureDetail) syncProductActions(selectedProductCaptureDetail);
}));

ProductListingTemplates.init({api,getUser:()=>currentUser,canEdit:()=>hasAnyPermission('product:edit'),
  getCapture:()=>selectedProductCaptureDetail,onSaved:()=>{
    ProductListingTemplates.preview(selectedProductCaptureDetail); renderProductBulkProgress();
  }});
document.querySelector('#generate-product-ai-title').addEventListener('click', async event => {
  const capture = selectedProductCaptureDetail, org = currentUser?.organizationId;
  const button = event.currentTarget, status = document.querySelector('#product-ai-title-status');
  if (!capture || button.disabled || !hasAnyPermission('product:edit')) return;
  if (productEditorDirty || productCaptureSaveInFlightId) { status.textContent = '请先保存商品修改，再生成标题。'; return; }
  const platform = document.querySelector('#product-template-preview-platform').value;
  button.disabled = true; status.textContent = '正在分析主图、采集图片与商品信息，生成新标题…';
  try {
    const result = await api(`/api/v1/product-captures/${encodeURIComponent(capture.id)}/ai-title`,
      {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({platform}),signal:AbortSignal.timeout(105000)});
    if (org !== currentUser?.organizationId || selectedProductCaptureDetail?.id !== capture.id) return;
    if (!result.data?.title) throw Error('标题生成结果不完整，请重试');
    document.querySelector('#product-title-input').value = result.data.title;
    productEditorDirty = true; syncProductActions(capture);
    status.textContent = '已生成，请核对标题后保存修改。';
  } catch(error) {
    if (org === currentUser?.organizationId && selectedProductCaptureDetail?.id === capture.id) status.textContent = error.message;
  } finally {
    if (org === currentUser?.organizationId && selectedProductCaptureDetail?.id === capture.id) button.disabled = !hasAnyPermission('product:edit');
  }
});
document.querySelector("#refresh-product-captures").addEventListener("click", () =>
  loadProductCaptures(selectedProductCaptureId));
document.querySelector("#save-product-capture").addEventListener("click", saveProductCapture);
document.querySelector("#design-current-product").addEventListener("click", () => {
  const capture=selectedProductCaptureDetail;
  if(!capture||!productAccessCapabilities().edit)return;
  if(productEditorDirty||productCaptureSaveInFlightId){
    document.querySelector("#product-action-note").textContent="请先保存商品修改，再进入图片设计。";return;
  }
  window.history.pushState(null,document.title,`${window.location.pathname}${window.location.search}#commerce-designs`);
  switchWorkspace("productPublish");
  window.dispatchEvent(new CustomEvent("heyi:design-source-selected",{detail:{captureId:capture.id,captureVersion:capture.version}}));
});
window.addEventListener("heyi:design-source-return",async event=>{
  if(!currentUser||!canAccessProductCenter()||typeof event.detail?.captureId!=="string")return;
  window.history.pushState(null,document.title,`${window.location.pathname}${window.location.search}#product-capture`);
  switchWorkspace("productPublish");
  await loadProductCaptures(event.detail.captureId);
});
document.querySelector("#process-product-images").addEventListener("click", processProductImages);
document.querySelector("#approve-product-capture").addEventListener("click", approveProductCapture);
document.querySelector("#product-publish-form").addEventListener("submit", submitProductPublish);
document.querySelector("#product-publish-form").addEventListener("input", () => {
  document.querySelector("#product-publish-error").hidden = true;
});
document.querySelector("#publish-destination-platform").addEventListener("change", () => { syncOfficialPublishFields(); void prepareSimpleProductPublish(); });
document.querySelector("#publish-shop-id").addEventListener("change", () => { syncPublishShopContext(); void prepareSimpleProductPublish(); });
document.querySelector('#publish-category-choice').addEventListener('change', event => { document.querySelector('#publish-category-id').value = event.target.value; });
document.querySelector('#publish-logistic-choice').addEventListener('change', syncSimplePublishLogistics);
document.querySelector('#product-publish-form').addEventListener('invalid', event => { document.querySelector('#product-official-publish-fields').open = true; if (event.target.closest('#publish-advanced')) document.querySelector('#publish-advanced').open = true; }, true);
document.querySelector("#marketplace-shop-status-filter").addEventListener("change", event => {
  marketplaceShopStatusFilter = event.currentTarget.value;
  renderMarketplaceShopList();
});
document.querySelector("#marketplace-shop-panel").addEventListener("click", event => {
  const platformFilter = event.target.closest("[data-shop-platform-filter]");
  if (platformFilter) {
    marketplaceShopPlatformFilter = platformFilter.dataset.shopPlatformFilter;
    renderMarketplaceShopList();
    return;
  }
  const reloadButton = event.target.closest("[data-reload-shops]");
  if (reloadButton) {
    reloadMarketplaceShops(reloadButton);
    return;
  }
  const refreshButton = event.target.closest("[data-refresh-shop]");
  if (refreshButton) {
    refreshMarketplaceShop(refreshButton.dataset.refreshShop);
    return;
  }
  const editButton = event.target.closest('[data-rename-shop], [data-delete-shop]');
  if (editButton) { editMarketplaceShop(editButton.dataset.renameShop || editButton.dataset.deleteShop, !!editButton.dataset.deleteShop); return; }
  const disconnectButton = event.target.closest("[data-disconnect-shop]");
  if (disconnectButton) {
    disconnectMarketplaceShop(disconnectButton.dataset.disconnectShop);
    return;
  }
  const button = event.target.closest("[data-authorize-shop]");
  if (button) startMarketplaceAuthorization(button.dataset.authorizeShop, button);
});
document.querySelector("#product-publish-dialog").addEventListener("close", event => {
  if (event.currentTarget.dataset.uploading === "true") return;
  productPublishIdempotencyKey = "";
  productPublishIdempotencyCaptureId = "";
});
document.querySelector("#create-extension-pairing-code").addEventListener("click", createExtensionPairingCode);
document.querySelector("#copy-extension-pairing-code").addEventListener("click", async event => {
  const code = document.querySelector("#extension-pairing-code").dataset.code || "";
  if (!code) return;
  const copied = await copyTextToClipboard(code);
  event.currentTarget.textContent = copied ? "已复制" : "复制失败";
  window.setTimeout(() => { event.currentTarget.textContent = "复制"; }, 1500);
});

async function loadSystemData() {
  const canReadOrganization = hasAnyPermission(
    "system:organization:view", "system:organization:manage"
  );
  const canReadPeople = hasAnyPermission("system:user:view", "system:user:manage");
  const canReadRoles = hasAnyPermission(
    "system:permission:view", "system:role:manage", "system:user:manage"
  );
  const canReadPermissions = hasAnyPermission(
    "system:permission:view", "system:role:manage"
  );
  const canReadModels = hasAnyPermission("system:model:view", "system:model:manage");
  const platformAdmin = Boolean(currentUser?.platformAdmin);
  const [organizationResult, organizationsResult] = await Promise.all([
    canReadOrganization ? api("/api/v1/system/organization") : Promise.resolve({ data: null }),
    platformAdmin ? api("/api/v1/system/organizations") : Promise.resolve({ data: [] })
  ]);
  organization = organizationResult.data;
  organizations = platformAdmin ? organizationsResult.data : organization ? [organization] : [];
  if (!selectedModelOrganizationId || !organizations.some(item => item.id === selectedModelOrganizationId)) {
    selectedModelOrganizationId = currentUser?.organizationId || organizations[0]?.id || "";
  }
  if (!selectedPeopleOrganizationId || !organizations.some(item => item.id === selectedPeopleOrganizationId)) {
    selectedPeopleOrganizationId = currentUser?.organizationId || organizations[0]?.id || "";
  }
  const modelOrganizationId = selectedModelOrganizationId;
  const peopleOrganizationQuery = platformAdmin && selectedPeopleOrganizationId
    ? `?organizationId=${encodeURIComponent(selectedPeopleOrganizationId)}`
    : "";
  const [
    departmentResult,
    roleResult,
    permissionResult,
    userResult,
    modelResult,
    knowledgeAccessResult
  ] = await Promise.all([
    canReadPeople ? api(`/api/v1/system/departments${peopleOrganizationQuery}`) : Promise.resolve({ data: [] }),
    canReadRoles ? api(`/api/v1/system/roles${peopleOrganizationQuery}`) : Promise.resolve({ data: [] }),
    canReadPermissions ? api("/api/v1/system/permissions") : Promise.resolve({ data: [] }),
    canReadPeople ? api(`/api/v1/system/users${peopleOrganizationQuery}`) : Promise.resolve({ data: [] }),
    canReadModels
      ? api(`/api/v1/system/model-configs?organizationId=${encodeURIComponent(modelOrganizationId)}`)
      : Promise.resolve({ data: [] }),
    canReadPeople && (currentUser?.roles || []).some(role => ["ADMIN", "PLATFORM_ADMIN"].includes(role))
      && selectedPeopleOrganizationId === currentUser?.organizationId
      ? api("/api/v1/knowledge/access/users")
      : Promise.resolve({ data: {} })
  ]);
  departments = departmentResult.data;
  roles = roleResult.data;
  permissions = permissionResult.data;
  users = userResult.data;
  knowledgeWriterAccess = knowledgeAccessResult.data || {};
  modelConfigs = modelResult.data;
  if (!selectedRoleId || !roles.some(role => role.id === selectedRoleId)) {
    selectedRoleId = roles[0]?.id || "";
  }
  configureSystemAccess();
  if (organization) renderOrganization();
  renderOrganizationDirectory();
  renderDepartments();
  renderRoles();
  renderUsers();
  renderModelConfigs();
  populateOrganizationSelectors();
  populateFormOptions();
}

function configureSystemAccess() {
  const peopleVisible = hasAnyPermission("system:user:view", "system:user:manage");
  const rbacVisible = hasAnyPermission("system:permission:view", "system:role:manage");
  const profileVisible = hasAnyPermission(
    "system:organization:view", "system:organization:manage"
  );
  const modelsVisible = hasAnyPermission("system:model:view", "system:model:manage");
  const tabAccess = {
    people: peopleVisible,
    rbac: rbacVisible,
    models: modelsVisible,
    profile: profileVisible
  };
  document.querySelectorAll("[data-system-tab]").forEach(button => {
    button.hidden = !tabAccess[button.dataset.systemTab];
  });
  document.querySelector("#add-user-button").hidden =
    !hasAnyPermission("system:user:manage");
  document.querySelector("#add-department-button").hidden =
    !hasAnyPermission("system:user:manage");
  document.querySelector("#add-role-button").hidden =
    !hasAnyPermission("system:role:manage");
  document.querySelector("#edit-role-button").hidden =
    !hasAnyPermission("system:role:manage");
  document.querySelector("#edit-org-button").hidden =
    !hasAnyPermission("system:organization:manage");
  document.querySelector("#organization-directory").hidden = !currentUser?.platformAdmin;
  document.querySelector("#add-organization-button").hidden = !currentUser?.platformAdmin;
  document.querySelector("#user-organization-field").hidden = !currentUser?.platformAdmin;
  document.querySelector("#model-company-field").hidden = !currentUser?.platformAdmin;
  document.querySelector("#model-organization-field").hidden = !currentUser?.platformAdmin;
  updatePeopleCompanyContextVisibility();
  document.querySelectorAll("[data-add-model-type]").forEach(button => {
    button.hidden = !hasAnyPermission("system:model:manage");
  });

  const active = document.querySelector("[data-system-tab].active");
  if (!active || active.hidden) {
    document.querySelector("[data-system-tab]:not([hidden])")?.click();
  }
}

function renderModelConfigs() {
  const canManage = hasAnyPermission("system:model:manage");
  ["DRIVER", "VIDEO", "IMAGE"].forEach(type => {
    const target = document.querySelector(`[data-model-list="${type}"]`);
    const configs = modelConfigs.filter(config => config.modelType === type && !(type === "VIDEO" && config.provider === "FAST"));
    if (!configs.length) {
      target.innerHTML = '<div class="model-empty compact">暂无接入</div>';
      return;
    }
    target.innerHTML = configs.map(config => {
      const active = config.status === "ACTIVE";
      const isVideo = type === "VIDEO";
      const isDayanFast = config.provider === "DAYAN" && String(config.modelId || "").trim().toLowerCase() === "minimax-h3-fast";
      const providerName = isDayanFast
        ? "低质量 · 高速度（主线路）"
        : ({ DAYAN: "高质量 · 低速度", H3_MULTI: "低质量 · 高速度（备用）", AUTO: "自动识别线路" }[config.provider] || config.provider);
      return `
        <article class="model-config-card ${active ? "" : "disabled"}">
          <div class="model-config-main">
            <div class="model-config-title">
              <strong>${escapeHtml(config.modelId)}</strong>
              ${config.defaultModel ? `<span class="default-tag">${isVideo ? "主用" : "默认"}</span>` : isVideo && active ? '<span class="backup-tag">备用</span>' : ""}
              ${isVideo ? `<span class="provider-tag">${escapeHtml(providerName || "未识别")}</span>` : ""}
              <span class="connection-state ${active ? "active" : ""}">${active ? "已启用" : "已停用"}</span>
            </div>
            <p>${escapeHtml(config.baseUrl)}</p>
            <small>Key ${escapeHtml(config.maskedApiKey)}</small>
          </div>
          ${canManage ? `
            <div class="model-card-actions">
              ${!config.defaultModel && active
                ? `<button class="row-action" data-model-default="${config.id}">${isVideo ? "切为主用" : "默认"}</button>`
                : ""}
              <button class="row-action" data-model-edit="${config.id}">编辑</button>
              <button class="row-action ${active ? "danger" : ""}" data-model-status="${config.id}" data-next-status="${active ? "DISABLED" : "ACTIVE"}">
                ${active ? "停用" : "启用"}
              </button>
            </div>
          ` : ""}
        </article>
      `;
    }).join("");

    target.querySelectorAll("[data-model-edit]").forEach(button => {
      button.addEventListener("click", () => {
        openModelDialog(
          modelConfigs.find(config => config.id === button.dataset.modelEdit)
        );
      });
    });
    target.querySelectorAll("[data-model-default]").forEach(button => {
      button.addEventListener("click", () =>
        updateModelDefault(button.dataset.modelDefault)
      );
    });
    target.querySelectorAll("[data-model-status]").forEach(button => {
      button.addEventListener("click", () =>
        updateModelStatus(button.dataset.modelStatus, button.dataset.nextStatus)
      );
    });
  });
}

function openModelDialog(config = null, modelType = "DRIVER") {
  const form = document.querySelector("#model-form");
  form.reset();
  form.elements.configId.value = config?.id || "";
  form.elements.modelType.value = config?.modelType || modelType;
  form.elements.defaultModel.value = String(config?.defaultModel || false);
  form.elements.organizationId.value = config?.organizationId || selectedModelOrganizationId || currentUser?.organizationId || "";
  form.elements.organizationId.disabled = Boolean(config);
  form.elements.baseUrl.value = config?.baseUrl || "";
  form.elements.modelId.value = config?.modelId || "";
  form.elements.provider.value = config?.provider || "AUTO";
  document.querySelector("#video-provider-field").hidden = form.elements.modelType.value !== "VIDEO";
  form.elements.apiKey.required = !config;
  document.querySelector("#model-dialog-title").textContent =
    config ? "编辑模型连接" : `添加${modelTypeName(modelType)}`;
  document.querySelector("#model-key-help").textContent = config
    ? `当前 Key：${config.maskedApiKey}。留空表示不修改。`
    : "Key 会加密保存，保存后无法查看明文。";
  document.querySelector("#model-form-error").hidden = true;
  document.querySelector("#model-dialog").showModal();
}

function modelTypeName(type) {
  return { DRIVER: "驱动模型", VIDEO: "视频模型", IMAGE: "图片模型" }[type] || "模型";
}

async function updateModelDefault(id) {
  await api(`/api/v1/system/model-configs/${encodeURIComponent(id)}/default`, {
    method: "PATCH"
  });
  await loadSystemData();
}

async function updateModelStatus(id, status) {
  await api(`/api/v1/system/model-configs/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ status })
  });
  await loadSystemData();
}

function renderOrganization() {
  document.querySelector("#org-code").textContent = organization.code.toUpperCase();
  document.querySelector("#org-name").setAttribute("translate", (organization.name) ? "no" : "yes");
  document.querySelector("#org-name").textContent = organization.name;
  document.querySelector("#org-description").textContent =
    organization.description || "补充企业简介，让团队清楚这个工作空间服务于什么业务。";
  document.querySelector("#org-plan").textContent = organization.planCode;
}

function renderOrganizationDirectory() {
  const target = document.querySelector("#organization-list");
  if (!target || !currentUser?.platformAdmin) return;
  document.querySelector("#organization-count").textContent = `${organizations.length} 个公司`;
  target.innerHTML = organizations.map(item => `
    <article class="organization-directory-item ${item.id === currentUser.organizationId ? "current" : ""}">
      <span class="organization-code-chip">${escapeHtml(item.code.toUpperCase())}</span>
      <div><strong translate="no">${escapeHtml(item.name)}</strong><small translate="${item.description ? 'no' : 'yes'}">${escapeHtml(item.description || "尚未填写公司简介")}</small></div>
      <em>${item.status === "ACTIVE" ? "正常" : "已停用"}</em>
    </article>
  `).join("") || '<div class="model-empty compact">还没有可管理的公司。</div>';
}

function populateOrganizationSelectors() {
  const options = organizations.map(item =>
    `<option value="${item.id}">${escapeHtml(item.name)} · ${escapeHtml(item.code.toUpperCase())}</option>`
  ).join("");
  const modelSelect = document.querySelector("#model-organization-select");
  const modelFormSelect = document.querySelector("#model-organization");
  const userSelect = document.querySelector("#user-organization");
  const peopleSelect = document.querySelector("#people-organization-select");
  modelSelect.innerHTML = options;
  modelFormSelect.innerHTML = options;
  userSelect.innerHTML = options;
  peopleSelect.innerHTML = options;
  modelSelect.value = selectedModelOrganizationId;
  modelFormSelect.value = selectedModelOrganizationId;
  userSelect.value = selectedPeopleOrganizationId || currentUser?.organizationId || organizations[0]?.id || "";
  peopleSelect.value = selectedPeopleOrganizationId;
  const selectedModelOrganization = organizations.find(item => item.id === selectedModelOrganizationId);
  const selectedPeopleOrganization = organizations.find(item => item.id === selectedPeopleOrganizationId);
  document.querySelector("#model-company-name").textContent =
    selectedModelOrganization?.name || organization?.name || "当前公司";
  document.querySelector("#people-company-name").textContent =
    selectedPeopleOrganization?.name || organization?.name || "当前公司";
}

function updatePeopleCompanyContextVisibility(tab) {
  const activeTab = tab || document.querySelector("[data-system-tab].active")?.dataset.systemTab || "people";
  document.querySelector("#people-company-context").hidden =
    !currentUser?.platformAdmin || !["people", "rbac"].includes(activeTab);
}

function renderDepartments() {
  const target = document.querySelector("#department-list");
  const allActive = selectedDepartmentId === "";
  target.innerHTML = `
    <button class="department-item ${allActive ? "active" : ""}" data-department-id="">
      <span><strong>全部人员</strong><small>${users.length} 人</small></span>
    </button>
  ` + departments.map(department => {
    const count = users.filter(user => user.departmentId === department.id).length;
    return `
      <div class="department-row">
        <button class="department-item ${selectedDepartmentId === department.id ? "active" : ""}" data-department-id="${department.id}">
          <span><strong translate="no">${escapeHtml(department.name)}</strong><small>${count} 人 · ${escapeHtml(department.code)}</small></span>
        </button>
        ${hasAnyPermission("system:user:manage") ? `
          <button class="department-edit" data-department-edit="${department.id}" aria-label="编辑${escapeHtml(department.name)}" title="编辑部门">✎</button>
          <button class="department-delete" data-department-delete="${department.id}" aria-label="删除${escapeHtml(department.name)}" title="删除部门">×</button>
        ` : ""}
      </div>
    `;
  }).join("");
  target.querySelectorAll("[data-department-id]").forEach(button => {
    button.addEventListener("click", () => {
      selectedDepartmentId = button.dataset.departmentId;
      renderDepartments();
      renderUsers();
    });
  });
  target.querySelectorAll("[data-department-delete]").forEach(button => {
    button.addEventListener("click", async () => {
      const department = departments.find(item =>
        item.id === button.dataset.departmentDelete
      );
      if (!department || !confirm(`确定删除“${department.name}”吗？`)) return;
      try {
        await api(
          `/api/v1/system/departments/${encodeURIComponent(department.id)}`,
          { method: "DELETE" }
        );
        if (selectedDepartmentId === department.id) selectedDepartmentId = "";
        await loadSystemData();
      } catch (error) {
        alert(error.message);
      }
    });
  });
  target.querySelectorAll("[data-department-edit]").forEach(button => {
    button.addEventListener("click", () => {
      const department = departments.find(item =>
        item.id === button.dataset.departmentEdit
      );
      if (department) openDepartmentDialog(department);
    });
  });
}

function openDepartmentDialog(department = null) {
  const form = document.querySelector("#department-form");
  form.reset();
  populateFormOptions();
  form.elements.departmentId.value = department?.id || "";
  form.elements.name.value = department?.name || "";
  form.elements.code.value = department?.code || "";
  if (department) {
    Array.from(form.elements.parentId.options).forEach(option => {
      if (option.value === department.id) option.remove();
    });
    form.elements.parentId.value = department.parentId || "";
  }
  document.querySelector("#department-dialog-title").textContent =
    department ? "编辑部门" : "添加部门";
  document.querySelector("#department-submit-button").textContent =
    department ? "保存修改" : "创建部门";
  document.querySelector("#department-form-error").hidden = true;
  document.querySelector("#department-dialog").showModal();
}

function renderRoles() {
  const list = document.querySelector("#role-list");
  list.innerHTML = roles.map(role => `
    <button class="role-config-item ${selectedRoleId === role.id ? "active" : ""}" data-role-id="${role.id}">
      <span><strong translate="no">${escapeHtml(role.name)}</strong><small>${role.userCount} 人 · ${role.permissionIds.length} 项权限</small></span>
      ${role.builtIn ? '<em>内置</em>' : ""}
    </button>
  `).join("");
  list.querySelectorAll("[data-role-id]").forEach(button => {
    button.addEventListener("click", () => {
      selectedRoleId = button.dataset.roleId;
      renderRoles();
    });
  });
  renderSelectedRole();
}

function renderSelectedRole() {
  const role = roles.find(item => item.id === selectedRoleId);
  document.querySelector("#permission-empty").hidden = Boolean(role);
  document.querySelector("#permission-editor").hidden = !role;
  if (!role) return;
  document.querySelector("#selected-role-code").textContent = role.code;
  document.querySelector("#selected-role-name").textContent = role.name;
  document.querySelector("#selected-role-description").textContent =
    role.description || "企业自定义角色";
  document.querySelector("#selected-role-users").textContent = `${role.userCount} 人`;
  document.querySelector("#selected-role-scope").textContent = dataScopeName(role.dataScope);
  document.querySelector("#selected-role-permissions").textContent = `${role.permissionIds.length} 项`;
  document.querySelector("#selected-role-status").textContent =
    role.status === "ACTIVE" ? "正常" : "已停用";

  const granted = new Set(role.permissionIds);
  const grouped = groupPermissions();
  document.querySelector("#permission-preview").innerHTML = Object.entries(grouped).map(([module, items]) => `
    <section class="permission-group">
      <div><strong>${permissionModuleName(module)}</strong><span>${items.filter(item => granted.has(item.id)).length} / ${items.length}</span></div>
      <ul>${items.map(item => `
        <li class="${granted.has(item.id) ? "granted" : ""}">
          <i>${granted.has(item.id) ? "✓" : "—"}</i>
          <span><strong translate="no">${escapeHtml(item.name)}</strong><small>${escapeHtml(item.code)}</small></span>
          <em>${permissionTypeName(item.resourceType)}</em>
        </li>
      `).join("")}</ul>
    </section>
  `).join("");
}

function renderUsers() {
  const query = document.querySelector("#user-search").value.trim().toLowerCase();
  const filtered = users.filter(user => {
    const inDepartment = !selectedDepartmentId || user.departmentId === selectedDepartmentId;
    const searchable = `${user.displayName} ${user.phone || user.username}`.toLowerCase();
    return inDepartment && (!query || searchable.includes(query));
  });
  const body = document.querySelector("#user-table-body");
  if (!filtered.length) {
    body.innerHTML = '<tr><td colspan="6" class="table-empty">这个范围还没有人员。添加人员后，账号会出现在这里。</td></tr>';
    return;
  }
  body.innerHTML = filtered.map(user => `
    <tr>
      <td><div class="person-cell"><span><strong>${escapeHtml(user.displayName)}</strong><small>${escapeHtml(user.phone || user.username)}</small></span></div></td>
      <td><div class="stacked-cell"><strong>${escapeHtml(user.departmentName)}</strong><small>${escapeHtml(user.jobTitle || "未设置职位")}</small></div></td>
      <td><div class="role-tags">${user.roleNames.map(role => `<span>${escapeHtml(role)}</span>`).join("")}</div></td>
      <td>${Object.hasOwn(knowledgeWriterAccess, user.id) ? `
        <button class="row-action" data-knowledge-writer="${user.id}"
          aria-label="${escapeHtml(user.displayName)}知识库写权限"
          ${!hasAnyPermission("system:user:manage") || user.roleIds?.some(id => roles.some(role => role.id === id && ["ADMIN", "PLATFORM_ADMIN"].includes(role.code))) ? "disabled" : ""}>
          ${knowledgeWriterAccess[user.id] ? "可新建 · 可编辑 · 可删除 · 可上传" : "可阅读 · 可新建 · 可上传"}
        </button>` : "可阅读 · 可新建 · 可上传"}</td>
      <td><span class="account-status account-${user.status.toLowerCase()}"><i></i>${userStatusName(user.status)}</span></td>
      <td><div class="row-actions" ${hasAnyPermission("system:user:manage") ? "" : "hidden"}>
        <button class="row-action" data-user-action="edit" data-user-id="${user.id}">编辑</button>
        ${hasAnyPermission("system:automation:manage") ? `<button class="row-action" data-user-action="automation" data-user-id="${user.id}">自动化</button>` : ""}
        <button class="row-action" data-user-action="password" data-user-id="${user.id}">重置密码</button>
        <button class="row-action danger" data-user-action="status" data-user-id="${user.id}" data-user-status="${user.status}" ${currentUser?.userId === user.id ? "disabled" : ""}>${user.status === "ACTIVE" ? "停用" : "启用"}</button>
      </div></td>
    </tr>
  `).join("");
  body.querySelectorAll("[data-user-action]").forEach(button => {
    button.addEventListener("click", () => {
      const user = users.find(item => item.id === button.dataset.userId);
      if (button.dataset.userAction === "edit") openEditUser(user);
      if (button.dataset.userAction === "automation") openAutomationAccess(user);
      if (button.dataset.userAction === "password") openPasswordReset(user);
      if (button.dataset.userAction === "status") {
        changeUserStatus(
          user.id,
          button.dataset.userStatus === "ACTIVE" ? "DISABLED" : "ACTIVE"
        );
      }
    });
  });
  body.querySelectorAll("[data-knowledge-writer]").forEach(button => {
    button.addEventListener("click", async () => {
      const user = users.find(item => item.id === button.dataset.knowledgeWriter);
      if (!user) return;
      const enabled = knowledgeWriterAccess[user.id] === true;
      if (!confirm(`确定${enabled ? "关闭" : "开启"}“${user.displayName}”的知识库写权限吗？${enabled ? "关闭后仍可阅读、新建和上传资料。" : "开启后还可编辑、删除文章及移除附件。"}`)) return;
      button.disabled = true;
      try {
        const result = await api(`/api/v1/knowledge/access/users/${encodeURIComponent(user.id)}`, {
          method: "PUT", headers: {"Content-Type": "application/json"}, body: JSON.stringify({canWrite: !enabled})
        });
        knowledgeWriterAccess[user.id] = result.data.canWrite;
        await loadSystemData();
      } catch (error) {
        button.disabled = false;
        alert(error.message);
      }
    });
  });
}

async function openAutomationAccess(user) {
  if (!user) return;
  const form = document.querySelector("#automation-access-form");
  const errorBox = document.querySelector("#automation-access-error");
  form.reset();
  form.elements.userId.value = user.id;
  errorBox.hidden = true;
  document.querySelector("#automation-access-user-hint").textContent =
    `为 ${user.displayName} 配置允许使用的自动化。`;
  try {
    const [skillsResult, accessResult] = await Promise.all([
      api("/api/v1/system/automation-skills"),
      api(`/api/v1/system/users/${encodeURIComponent(user.id)}/automation-access`)
    ]);
    managedAutomationSkills = skillsResult.data || [];
    const granted = new Set(accessResult.data?.automationCodes || []);
    document.querySelector("#automation-access-options").innerHTML =
      managedAutomationSkills
        .filter(skill => skill.status === "ACTIVE")
        .map(skill => `
          <label class="choice-item">
            <input type="checkbox" name="automationCodes" value="${escapeHtml(skill.code)}"
              ${granted.has(skill.code) ? "checked" : ""}>
            <span><strong translate="no">${escapeHtml(skill.name)}</strong><small>${escapeHtml(skill.runnerType)}</small></span>
          </label>
        `).join("") || '<div class="empty-state">暂无已启用的自动化。</div>';
    document.querySelector("#automation-access-dialog").showModal();
  } catch (error) {
    alert(error.message);
  }
}

function populateFormOptions() {
  userDialogDepartments = departments;
  userDialogRoles = roles;
  populateUserCreationOptions();
  document.querySelector("#parent-department").innerHTML =
    '<option value="">无上级部门</option>' +
    departments.map(department => `<option value="${department.id}">${escapeHtml(department.name)}</option>`).join("");
  document.querySelector("#edit-user-department").innerHTML = departments
    .map(department => `<option value="${department.id}">${escapeHtml(department.name)}</option>`)
    .join("");
  renderRoleChoices("user-role-options", []);
}

function populateUserCreationOptions() {
  document.querySelector("#user-department").innerHTML = userDialogDepartments
    .map(department => `<option value="${department.id}">${escapeHtml(department.name)}</option>`)
    .join("");
  renderRoleChoices("user-role-options", [], userDialogRoles);
}

async function loadUserOrganizationOptions(organizationId) {
  const query = `?organizationId=${encodeURIComponent(organizationId)}`;
  const [departmentResult, roleResult] = await Promise.all([
    api(`/api/v1/system/departments${query}`),
    api(`/api/v1/system/roles${query}`)
  ]);
  userDialogDepartments = departmentResult.data || [];
  userDialogRoles = roleResult.data || [];
  populateUserCreationOptions();
}

function renderRoleChoices(containerId, selectedIds, availableRoles = roles) {
  const selected = new Set(selectedIds || []);
  document.querySelector(`#${containerId}`).innerHTML = availableRoles
    .filter(role => role.status === "ACTIVE" || selected.has(role.id))
    .map(role => `
      <label class="choice-item">
        <input type="checkbox" name="roleIds" value="${role.id}" ${selected.has(role.id) ? "checked" : ""}>
        <span><strong translate="no">${escapeHtml(role.name)}</strong><small>${dataScopeName(role.dataScope)}</small></span>
      </label>
    `).join("");
}

function groupPermissions() {
  return permissions.reduce((groups, permission) => {
    (groups[permission.module] ||= []).push(permission);
    return groups;
  }, {});
}

function permissionModuleName(module) {
  return {
    DASHBOARD: "工作台",
    CONTENT: "内容生产",
    VIDEO: "视频生产",
    ASSET: "素材中心",
    COMMERCE: "商品与发布",
    FINANCE: "财务 BI",
    AUTOMATION: "自动化",
    ASSISTANT: "智能助手",
    SYSTEM: "系统管理"
  }[module] || module;
}

function permissionTypeName(type) {
  return { MENU: "菜单", API: "接口", ACTION: "操作" }[type] || type;
}

function dataScopeName(scope) {
  return {
    ALL: "全部数据",
    DEPARTMENT: "本部门",
    DEPARTMENT_AND_CHILDREN: "本部门及下级",
    SELF: "仅本人"
  }[scope] || scope;
}

function userStatusName(status) {
  return { ACTIVE: "正常", LOCKED: "已锁定", DISABLED: "已停用" }[status] || status;
}

async function changeUserStatus(userId, status) {
  try {
    await api("/api/v1/system/users/status", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId, status })
    });
    await loadSystemData();
  } catch (error) {
    alert(error.message);
  }
}

function openEditUser(user) {
  if (!user) return;
  const form = document.querySelector("#edit-user-form");
  form.reset();
  form.elements.userId.value = user.id;
  form.elements.displayName.value = user.displayName;
  form.elements.departmentId.value = user.departmentId;
  form.elements.jobTitle.value = user.jobTitle || "";
  form.elements.phone.value = user.phone || "";
  form.elements.email.value = user.email || "";
  renderRoleChoices("edit-user-role-options", user.roleIds);
  document.querySelector("#edit-user-dialog").showModal();
}

function openPasswordReset(user) {
  if (!user) return;
  const form = document.querySelector("#password-form");
  form.reset();
  form.elements.userId.value = user.id;
  document.querySelector("#password-user-hint").textContent =
    `为 ${user.displayName}（${user.phone || user.username}）设置新的初始密码。`;
  document.querySelector("#password-dialog").showModal();
}

function renderPermissionOptions(selectedIds, lockAll = false) {
  const selected = new Set(selectedIds || []);
  document.querySelector("#permission-options").innerHTML =
    Object.entries(groupPermissions()).map(([module, items]) => `
      <section class="permission-option-group">
        <div><strong>${permissionModuleName(module)}</strong><span>${items.length} 项</span></div>
        ${items.map(item => `
          <label>
            <input type="checkbox" name="permissionIds" value="${item.id}"
              ${selected.has(item.id) ? "checked" : ""} ${lockAll ? "disabled" : ""}>
            <span><strong translate="no">${escapeHtml(item.name)}</strong><small>${escapeHtml(item.code)}</small></span>
            <em>${permissionTypeName(item.resourceType)}</em>
          </label>
        `).join("")}
      </section>
    `).join("");
}

function openRoleDialog(role = null) {
  const form = document.querySelector("#role-form");
  form.reset();
  form.elements.roleId.value = role?.id || "";
  form.elements.name.value = role?.name || "";
  form.elements.code.value = role?.code || "";
  form.elements.code.readOnly = Boolean(role);
  form.elements.description.value = role?.description || "";
  form.elements.dataScope.value = role?.dataScope || "SELF";
  form.elements.status.value = role?.status || "ACTIVE";
  form.elements.status.disabled = role?.code === "ADMIN";
  document.querySelector("#role-dialog-title").textContent =
    role ? "编辑角色与权限" : "新增企业角色";
  renderPermissionOptions(role?.permissionIds || [], role?.code === "ADMIN");
  document.querySelector("#role-dialog").showModal();
}

function selectedValues(containerId, name) {
  return Array.from(
    document.querySelectorAll(`#${containerId} input[name="${name}"]:checked`)
  ).map(input => input.value);
}

document.querySelectorAll("[data-system-tab]").forEach(button => {
  button.addEventListener("click", () => {
    const tab = button.dataset.systemTab;
    document.querySelectorAll("[data-system-tab]").forEach(item =>
      item.classList.toggle("active", item === button)
    );
    document.querySelector("#system-people-panel").hidden = tab !== "people";
    document.querySelector("#system-rbac-panel").hidden = tab !== "rbac";
    document.querySelector("#system-profile-panel").hidden = tab !== "profile";
    updatePeopleCompanyContextVisibility(tab);
  });
});

document.querySelector("#user-search").addEventListener("input", renderUsers);
document.querySelector("#refresh-users-button").addEventListener("click", loadSystemData);
document.querySelector("#add-user-button").addEventListener("click", async () => {
  const form = document.querySelector("#user-form");
  const error = document.querySelector("#user-form-error");
  form.reset();
  populateOrganizationSelectors();
  const organizationId = selectedPeopleOrganizationId || currentUser?.organizationId || organizations[0]?.id || "";
  form.elements.organizationId.value = organizationId;
  document.querySelector("#user-dialog").showModal();
  error.hidden = true;
  try {
    await loadUserOrganizationOptions(organizationId);
  } catch (requestError) {
    error.textContent = requestError.message;
    error.hidden = false;
  }
});
document.querySelector("#user-organization").addEventListener("change", async event => {
  const error = document.querySelector("#user-form-error");
  error.hidden = true;
  try {
    await loadUserOrganizationOptions(event.currentTarget.value);
  } catch (requestError) {
    error.textContent = requestError.message;
    error.hidden = false;
  }
});
document.querySelector("#add-department-button").addEventListener("click", () => {
  openDepartmentDialog();
});
document.querySelector("#edit-org-button").addEventListener("click", () => {
  const form = document.querySelector("#organization-form");
  ["name", "logoUrl", "description", "contactName", "contactPhone"].forEach(field => {
    form.elements[field].value = organization[field] || "";
  });
  document.querySelector("#organization-dialog").showModal();
});
document.querySelector("#add-organization-button").addEventListener("click", () => {
  const form = document.querySelector("#create-organization-form");
  form.reset();
  document.querySelector("#create-organization-form-error").hidden = true;
  document.querySelector("#create-organization-dialog").showModal();
});
document.querySelector("#add-role-button").addEventListener("click", () => openRoleDialog());
document.querySelector("#edit-role-button").addEventListener("click", () =>
  openRoleDialog(roles.find(role => role.id === selectedRoleId))
);
document.querySelectorAll("[data-add-model-type]").forEach(button => {
  button.addEventListener("click", () =>
    openModelDialog(null, button.dataset.addModelType)
  );
});
document.querySelector("#model-organization-select").addEventListener("change", async event => {
  selectedModelOrganizationId = event.currentTarget.value;
  try {
    await loadSystemData();
  } catch (requestError) {
    window.alert(requestError.message);
  }
});
document.querySelector("#people-organization-select").addEventListener("change", async event => {
  selectedPeopleOrganizationId = event.currentTarget.value;
  selectedDepartmentId = "";
  selectedRoleId = "";
  try {
    await loadSystemData();
  } catch (requestError) {
    window.alert(requestError.message);
  }
});

document.querySelectorAll("[data-close-dialog]").forEach(button => {
  button.addEventListener("click", () => button.closest("dialog").close());
});

document.querySelector("#user-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = formToObject(form);
  payload.roleIds = selectedValues("user-role-options", "roleIds");
  await submitDialogForm(form, "/api/v1/system/users", "user-form-error", payload);
});

document.querySelector("#edit-user-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = formToObject(form);
  const userId = payload.userId;
  delete payload.userId;
  payload.roleIds = selectedValues("edit-user-role-options", "roleIds");
  await submitDialogForm(
    form,
    `/api/v1/system/users/${encodeURIComponent(userId)}`,
    "edit-user-form-error",
    payload,
    "PUT"
  );
});

document.querySelector("#password-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = formToObject(form);
  const userId = payload.userId;
  delete payload.userId;
  await submitDialogForm(
    form,
    `/api/v1/system/users/${encodeURIComponent(userId)}/reset-password`,
    "password-form-error",
    payload
  );
});

document.querySelector("#automation-access-form").addEventListener("submit", async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const errorBox = document.querySelector("#automation-access-error");
  const userId = form.elements.userId.value;
  const payload = {
    automationCodes: selectedValues("automation-access-options", "automationCodes")
  };
  try {
    await api(`/api/v1/system/users/${encodeURIComponent(userId)}/automation-access`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    document.querySelector("#automation-access-dialog").close();
    if (!document.querySelector("#automation-admin-page").hidden) {
      await loadAutomationAdmin();
    }
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.hidden = false;
  }
});

document.querySelector("#automation-runtime-form").addEventListener("submit", async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const errorBox = document.querySelector("#automation-runtime-error");
  const submitButton = form.querySelector('button[type="submit"]');
  const allowedDomains = String(form.elements.allowedDomains.value || "")
    .split(/[\n,，]+/)
    .map(value => value.trim().toLowerCase())
    .filter(Boolean);
  const payload = {
    browserEngine: form.elements.browserEngine.value,
    browserType: form.elements.browserType.value,
    browserId: form.elements.browserId.value.trim(),
    proxyPolicy: form.elements.proxyPolicy.value,
    requiredRegion: form.elements.requiredRegion.value.trim().toUpperCase(),
    allowedDomains
  };
  submitButton.disabled = true;
  errorBox.hidden = true;
  try {
    await api(`/api/v1/system/automation-skills/${encodeURIComponent(automationRuntimeSkillCode)}/runtime-config`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    document.querySelector("#automation-runtime-dialog").close();
    await loadAutomationAdmin();
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.hidden = false;
  } finally {
    submitButton.disabled = false;
  }
});

document.querySelector("#automation-form-designer").addEventListener("submit", async event => {
  event.preventDefault();
  const errorBox = document.querySelector("#automation-form-error");
  const saveButton = document.querySelector("#save-automation-form");
  const usedFieldNames = new Set();
  const fields = automationFormItems
    .filter(item => item.kind === "field")
    .map((item, index) => {
      let name = String(item.name || "").trim();
      if (!name || usedFieldNames.has(name)) {
        let suffix = index + 1;
        do {
          name = `field${suffix++}`;
        } while (usedFieldNames.has(name));
      }
      usedFieldNames.add(name);
      const field = {
        name,
        label: String(item.label || "").trim() || designerTypeName(item.type),
        type: item.type,
        required: item.required !== false
      };
      if (item.placeholder) field.placeholder = item.placeholder;
      if (Array.isArray(item.options)) field.options = item.options;
      if (item.min != null) field.min = item.min;
      if (item.max != null) field.max = item.max;
      if (item.accept) field.accept = item.accept;
      if (item.maxFileMb != null) field.maxFileMb = item.maxFileMb;
      return field;
    });
  const prerequisites = automationFormItems
    .filter(item => item.kind === "prerequisite")
    .map((item, index) => ({
      label: String(item.label || "").trim() || `确认事项 ${index + 1}`,
      description: String(item.description || "").trim()
    }));
  saveButton.disabled = true;
  errorBox.hidden = true;
  try {
    await api(`/api/v1/system/automation-skills/${encodeURIComponent(automationFormSkillCode)}/form-schema`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ version: 1, fields, prerequisites })
    });
    document.querySelector("#automation-form-dialog").close();
    await loadAutomationAdmin();
    if (!document.querySelector("#automations-page").hidden) {
      await loadAutomationCenter();
    }
  } catch (error) {
    errorBox.textContent = error.message;
    errorBox.hidden = false;
  } finally {
    saveButton.disabled = false;
  }
});

document.querySelector("#refresh-automation-admin")?.addEventListener("click", loadAutomationAdmin);

document.querySelector("#role-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = formToObject(form);
  const roleId = payload.roleId;
  delete payload.roleId;
  if (!roleId) payload.organizationId = selectedPeopleOrganizationId || currentUser?.organizationId || "";
  payload.status = form.elements.status.value;
  payload.permissionIds = selectedValues("permission-options", "permissionIds");
  const path = roleId
    ? `/api/v1/system/roles/${encodeURIComponent(roleId)}`
    : "/api/v1/system/roles";
  await submitDialogForm(
    form,
    path,
    "role-form-error",
    payload,
    roleId ? "PUT" : "POST"
  );
});

document.querySelector("#department-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = formToObject(form);
  const departmentId = payload.departmentId;
  delete payload.departmentId;
  if (!departmentId) payload.organizationId = selectedPeopleOrganizationId || currentUser?.organizationId || "";
  payload.sortOrder = departments.length;
  if (departmentId) delete payload.sortOrder;
  await submitDialogForm(
    form,
    departmentId
      ? `/api/v1/system/departments/${encodeURIComponent(departmentId)}`
      : "/api/v1/system/departments",
    "department-form-error",
    payload,
    departmentId ? "PUT" : "POST"
  );
});

document.querySelector("#organization-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  await submitDialogForm(
    event.currentTarget,
    "/api/v1/system/organization",
    "organization-form-error",
    undefined,
    "PUT"
  );
});

document.querySelector("#create-organization-form").addEventListener("submit", async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = formToObject(form);
  payload.code = String(payload.code || "").trim().toLowerCase();
  await submitDialogForm(
    form,
    "/api/v1/system/organizations",
    "create-organization-form-error",
    payload
  );
});

document.querySelector("#model-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const payload = formToObject(form);
  const configId = payload.configId;
  delete payload.configId;
  payload.defaultModel = payload.defaultModel === "true";
  if (configId) delete payload.modelType;
  const path = configId
    ? `/api/v1/system/model-configs/${encodeURIComponent(configId)}`
    : "/api/v1/system/model-configs";
  await submitDialogForm(
    form,
    path,
    "model-form-error",
    payload,
    configId ? "PUT" : "POST"
  );
});

async function submitDialogForm(form, path, errorId, explicitPayload, method = "POST") {
  const error = document.querySelector(`#${errorId}`);
  error.hidden = true;
  try {
    await api(path, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(explicitPayload || formToObject(form))
    });
    form.closest("dialog").close();
    form.reset();
    await loadSystemData();
  } catch (requestError) {
    error.textContent = requestError.message;
    error.hidden = false;
  }
}

function formToObject(form) {
  return Object.fromEntries(new FormData(form).entries());
}

function selectVideoScriptCreateMode(mode) {
  const manual = mode === "manual";
  document.querySelectorAll("[data-script-create-mode]").forEach(button =>
    button.classList.toggle("active", button.dataset.scriptCreateMode === mode)
  );
  document.querySelector("#script-create-ai").hidden = manual;
  document.querySelector("#script-create-manual").hidden = !manual;
  document.querySelectorAll("#script-create-ai input, #script-create-ai select, #script-create-ai textarea")
    .forEach(field => field.disabled = manual);
  document.querySelectorAll("#script-create-manual input, #script-create-manual select, #script-create-manual textarea")
    .forEach(field => field.disabled = !manual);
  syncVideoScriptClipControlState(manual);
  document.querySelector("#video-script-dialog-title").textContent = manual ? "手动创建脚本" : "上传视频并拆解";
  document.querySelector("#video-script-dialog-copy").textContent = manual
    ? "直接填写固定 9 个中文分镜，保存后用于参考图直提视频。"
    : "AI 会精拆为完整 9 个分镜，并同步生成生产 JSON。";
  document.querySelector("#submit-video-script").textContent = manual ? "保存 9 分镜脚本" : "开始 AI 拆解";
  document.querySelector("#video-script-prototype-note").textContent = manual
    ? "完整填写固定 9 个分镜，保存后可直接创建 AI 视频任务。"
    : "视频上传后将在后台抽帧，并调用当前启用的驱动模型进行拆解。";
}

function selectedScriptPresentationMode() {
  return document.querySelector("#video-script-form input[name=presentationMode]:checked")?.value || "MODEL_WEAR";
}

function syncScriptPresentationModePicker() {
  document.querySelectorAll("#video-script-form .script-presentation-mode-picker .generation-mode-option")
    .forEach(option => option.classList.toggle("active", option.querySelector("input")?.checked));
}

function selectManualAnalysisMode(mode) {
  const productMode = mode === "product";
  document.querySelectorAll("[data-manual-analysis-mode]").forEach(button => {
    const active = button.dataset.manualAnalysisMode === mode;
    button.classList.toggle("active", active);
    button.setAttribute("aria-selected", String(active));
  });
  document.querySelector("#manual-analysis-text-panel").hidden = productMode;
  document.querySelector("#manual-analysis-product-panel").hidden = !productMode;
  const status = document.querySelector("#manual-script-ai-status");
  status.textContent = productMode
    ? "可添加 1—6 张参考图，并逐张指定“图一、图二”是什么、用于什么；AI 会联合理解后生成 9 个分镜。"
    : "AI 会理解文字需求并拆成 9 段；时间由系统固定为 0—15 秒连续均分。";
  status.classList.remove("is-error", "is-success");
  if (productMode) syncManualProductAnalysisAvailability();
}

function renderManualShotRows() {
  const container = document.querySelector("#manual-shot-rows");
  if (!container || container.children.length === 9) return;
  container.innerHTML = Array.from({ length: 9 }, (_, index) => {
    const number = String(index + 1).padStart(2, "0");
    const suggestedStart = manualShotBoundary(index);
    const suggestedEnd = manualShotBoundary(index + 1);
    const defaultStage = index < 3 ? "HOOK" : index < 6 ? "SOLUTION" : index < 8 ? "TRUST" : "CTA";
    return `<article class="manual-shot-row" data-manual-shot="${index}">
      <span class="manual-shot-number">${number}</span>
      <input name="manualStage${index}" type="hidden" value="${defaultStage}">
      <label>开始秒<input name="manualStart${index}" type="number" min="0" max="300" step="0.1" required value="${suggestedStart}"></label>
      <label>结束秒<input name="manualEnd${index}" type="number" min="0.1" max="300" step="0.1" required value="${suggestedEnd}"></label>
      <label class="manual-shot-title">分镜标题<input name="manualTitle${index}" required maxlength="160" placeholder="例如：商品第一眼亮相"></label>
      <label class="manual-shot-scene">画面与动作<textarea name="manualScene${index}" required maxlength="8000" rows="2" placeholder="描述主体、场景、动作与构图，系统将据此生成画面"></textarea></label>
      <label class="manual-shot-narration">画外旁白（模特不说话）<textarea name="manualNarration${index}" maxlength="8000" rows="2" placeholder="例如：旁白：今天给大家看一下；按目标市场语言填写，未指定则留空"></textarea></label>
      <label class="manual-shot-motion">运镜补充<input name="manualMotion${index}" maxlength="8000" placeholder="例如：镜头缓慢向前推进（可选）"></label>
    </article>`;
  }).join("");
}

function manualShotBoundary(index) {
  return Math.round(index * 15 / 9 * 10) / 10;
}

function fillManualScriptDraft(draft) {
  const form = document.querySelector("#video-script-form");
  renderManualShotRows();
  if (draft?.name) form.elements.manualName.value = draft.name;
  if (draft?.visualTone) form.elements.manualVisualTone.value = draft.visualTone;
  if (draft?.detectedTargetMarket) {
    const marketOption = [...form.elements.manualTargetMarket.options]
      .find(option => option.value === draft.detectedTargetMarket);
    if (marketOption) form.elements.manualTargetMarket.value = marketOption.value;
  }
  if (!Array.isArray(draft?.shots) || draft.shots.length !== 9) {
    throw new Error("AI 返回的分镜数量不是 9 个");
  }
  draft.shots.forEach((shot, index) => {
    form.elements[`manualStage${index}`].value = shot.stage || (index < 3 ? "HOOK" : index < 6 ? "SOLUTION" : index < 8 ? "TRUST" : "CTA");
    form.elements[`manualStart${index}`].value = manualShotBoundary(index);
    form.elements[`manualEnd${index}`].value = manualShotBoundary(index + 1);
    form.elements[`manualTitle${index}`].value = shot.title || "";
    form.elements[`manualScene${index}`].value = shot.sceneDescription || "";
    form.elements[`manualNarration${index}`].value = shot.narration || "";
    form.elements[`manualMotion${index}`].value = shot.motionDescription || "";
  });
}

function prepareVideoScriptCreationDialog(mode) {
  videoScriptProgressId = null;
  stopVideoScriptVisualProgress();
  videoScriptVisualProgress = 0;
  videoScriptVisualStatus = "";
  videoScriptVisualKey = "";
  videoScriptVisualServerProgress = 0;
  document.querySelector("#script-active-analysis").hidden = true;
  const outputHost = document.querySelector("#active-script-output");
  if (outputHost) outputHost.hidden = true;
  document.querySelector(".script-create-switch").hidden = false;
  document.querySelector("#submit-video-script").hidden = false;
  document.querySelector("#retry-video-script").hidden = true;
  clearVideoScriptClipEditor(true);
  const orbit = document.querySelector(".active-analysis-orbit");
  orbit?.classList.remove("failed", "done");
  selectVideoScriptCreateMode(mode);
  if (mode === "manual") {
    selectManualAnalysisMode("text");
    renderManualShotRows();
  }
}

const videoScriptStatusNames = {
  QUEUED: "等待处理",
  EXTRACTING_FRAMES: "正在抽帧",
  AI_ANALYZING: "AI 分析中",
  READY: "拆解完成",
  FAILED: "拆解失败",
  DRAFT: "草稿"
};

const videoScriptPresentationNames = {
  MODEL_WEAR: "人物穿搭",
  PRODUCT_ONLY: "纯商品",
  CAST_PRODUCT: "人物＋商品",
  LEGACY_COMPAT: "旧脚本兼容"
};

const processingVideoScriptStatuses = new Set(["QUEUED", "EXTRACTING_FRAMES", "AI_ANALYZING"]);

function normalizeVideoScriptProgress(value, status = "") {
  if (status === "READY") return 100;
  const number = Number(value);
  const ceiling = processingVideoScriptStatuses.has(status) ? 99 : 100;
  return Number.isFinite(number) ? Math.max(0, Math.min(ceiling, number)) : 0;
}

function videoScriptDisplayProgress(script) {
  const key = String(script?.id || "");
  const progress = normalizeVideoScriptProgress(script?.progress, script?.status);
  if (!key) return progress;
  const runId = String(script.analysisRunId || "");
  let state = videoScriptProgressStates.get(key);
  if (state?.retiredRunIds.has(runId) || (state?.runId && !runId)) return state.progress;
  if (!state || (runId && runId !== state.runId)) {
    const retiredRunIds = new Set(state?.retiredRunIds || []);
    if (state?.runId) retiredRunIds.add(state.runId);
    state = { runId, progress, retiredRunIds };
    videoScriptProgressStates.set(key, state);
  } else {
    // Every surface shares the latest observed checkpoint. A delayed list or
    // detail response cannot lower it, and elapsed time never invents progress.
    state.progress = Math.max(state.progress, progress);
  }
  return state.progress;
}

function resetVideoScriptTaskProgress(taskKey, runId = "") {
  const key = String(taskKey || "");
  if (!key) return;
  const state = videoScriptProgressStates.get(key);
  const normalizedRunId = String(runId || "");
  // Polling may already have observed this accepted retry before its POST
  // response arrives. Acknowledging that same run must not rewind it again.
  if (normalizedRunId && (state?.runId === normalizedRunId || state?.retiredRunIds.has(normalizedRunId))) return;
  const retiredRunIds = new Set(state?.retiredRunIds || []);
  if (state?.runId && state.runId !== normalizedRunId) retiredRunIds.add(state.runId);
  videoScriptProgressStates.set(key, { runId: normalizedRunId, progress: 0, retiredRunIds });
  if (videoScriptVisualKey === key) {
    videoScriptVisualProgress = 0;
    videoScriptVisualServerProgress = 0;
    videoScriptVisualStatus = "";
  }
}

function paintVideoScriptProgress(value, taskKey = videoScriptVisualKey) {
  const progress = normalizeVideoScriptProgress(value);
  const key = String(taskKey || "");
  if (!key) return;
  const display = progress.toFixed(progress >= 100 ? 0 : 1);
  const number = document.querySelector("#active-script-progress-number");
  const bar = document.querySelector("#active-script-progress-bar");
  if (videoScriptProgressId === key) {
    if (number) number.innerHTML = `<strong>${display}</strong><small>%</small>`;
    if (bar) bar.style.width = `${progress}%`;
  }
  const inlineNumber = document.querySelector("#storyboard-inline-progress-number");
  const inlineBar = document.querySelector("#storyboard-inline-progress-bar");
  if (selectedVideoScriptId === key) {
    if (inlineNumber) inlineNumber.textContent = display;
    if (inlineBar) inlineBar.style.width = `${progress}%`;
  }
  document.querySelectorAll("[data-video-script-progress-live]").forEach(node => {
    if (node.dataset.videoScriptProgressLive === key) {
      node.textContent = display;
    }
  });
}

function stopVideoScriptVisualProgress() {
  if (videoScriptVisualProgressTimer) window.clearInterval(videoScriptVisualProgressTimer);
  videoScriptVisualProgressTimer = null;
  videoScriptVisualProgressUpdatedAt = 0;
}

function startVideoScriptVisualProgress(status, serverProgress, taskKey = "", analysisRunId = "") {
  const normalizedTaskKey = String(taskKey || "");
  stopVideoScriptVisualProgress();
  videoScriptVisualKey = normalizedTaskKey;
  videoScriptVisualStatus = status;
  videoScriptVisualServerProgress = normalizeVideoScriptProgress(serverProgress, status);
  videoScriptVisualProgress = videoScriptDisplayProgress({
    id: normalizedTaskKey, status, progress: serverProgress, analysisRunId
  });
  paintVideoScriptProgress(videoScriptVisualProgress, normalizedTaskKey);
}

function renderVideoScriptProgress(script) {
  videoScriptProgressId = script?.id || null;
  const activePanel = document.querySelector("#script-active-analysis");
  const createSwitch = document.querySelector(".script-create-switch");
  const presentationPicker = document.querySelector(".script-presentation-mode-picker");
  const aiPanel = document.querySelector("#script-create-ai");
  const manualPanel = document.querySelector("#script-create-manual");
  const submit = document.querySelector("#submit-video-script");
  const retry = document.querySelector("#retry-video-script");
  const importButton = document.querySelector("#import-video-script");
  const outputHost = document.querySelector("#active-script-output");
  if (outputHost) outputHost.hidden = !script;
  if (!script) {
    stopVideoScriptVisualProgress();
    videoScriptVisualProgress = 0;
    videoScriptVisualStatus = "";
    videoScriptVisualKey = "";
    videoScriptVisualServerProgress = 0;
    activePanel.hidden = true;
    createSwitch.hidden = false;
    presentationPicker.hidden = false;
    submit.hidden = false;
    retry.hidden = true;
    importButton.textContent = "＋ 新建脚本";
    selectVideoScriptCreateMode("ai");
    return;
  }

  const status = script.status || "QUEUED";
  const progress = videoScriptDisplayProgress(script);
  const failed = status === "FAILED";
  const ready = status === "READY";
  activePanel.hidden = false;
  createSwitch.hidden = true;
  presentationPicker.hidden = true;
  aiPanel.hidden = true;
  manualPanel.hidden = true;
  submit.hidden = true;
  retry.hidden = !failed;
  importButton.textContent = "＋ 新建脚本";
  document.querySelector("#video-script-dialog-title").textContent = ready ? "视频拆解完成" : failed ? "视频拆解未完成" : "视频拆解处理中";
  document.querySelector("#video-script-dialog-copy").textContent = ready
    ? "分镜和生产 JSON 已经生成，可以关闭窗口查看结果。"
    : failed ? "任务和原视频已保留，可以重试当前任务或删除后重新上传。" : "当前任务会在后台持续处理；你可以关闭窗口并继续上传其他视频。";
  document.querySelector("#active-script-stage").textContent = videoScriptStatusNames[status] || status;
  document.querySelector("#active-script-name").setAttribute("translate", (script.name) ? "no" : "yes");
  document.querySelector("#active-script-name").textContent = script.name || script.sourceFileName || "视频拆解任务";
  document.querySelector("#active-script-message").textContent = failed
    ? (script.errorMessage || "模型没有生成有效分镜，请重试当前任务。")
    : ready ? "拆解成功，结果已经保存到视频脚本库。" : "AI 正在抽帧并理解视频画面，页面每 3 秒自动更新一次。";
  startVideoScriptVisualProgress(status, progress, script.id, script.analysisRunId);
  updateVideoScriptOutput(outputHost, script);
  const orbit = document.querySelector(".active-analysis-orbit");
  orbit.classList.toggle("failed", failed);
  orbit.classList.toggle("done", ready);
  document.querySelector("#video-script-prototype-note").textContent = failed
    ? "系统已保留失败原因和上传文件，不会静默丢失。"
    : ready ? "当前任务已完成，现在可以创建新的视频拆解任务。" : "当前视频正在后台拆解，可关闭窗口继续新建脚本。";
}

function videoScriptStatusClass(status) {
  if (status === "FAILED") return "draft";
  if (status === "READY") return "";
  return "review";
}

function normalizedFuzzyValue(value = "") {
  return String(value).normalize("NFKC").toLocaleLowerCase("zh-CN").replace(/[\s\p{P}\p{S}]+/gu, "");
}

function fuzzySearchMatches(values, query) {
  const needle = normalizedFuzzyValue(query);
  if (!needle) return true;
  const haystack = normalizedFuzzyValue(values.filter(Boolean).join(" "));
  if (haystack.includes(needle)) return true;
  let cursor = 0;
  for (const character of haystack) {
    if (character === needle[cursor]) cursor++;
    if (cursor === needle.length) return true;
  }
  return false;
}

function filteredVideoScripts() {
  return videoScripts.filter(script => fuzzySearchMatches([
    script.name, script.sourceFileName, script.modelId, script.id, script.status,
    videoScriptStatusNames[script.status], script.sourceType
  ], videoScriptSearchQuery));
}

function renderVideoScriptLibrary() {
  const list = document.querySelector("#video-script-list");
  const filtered = filteredVideoScripts();
  const summary = document.querySelector("#video-script-library-summary");
  if (summary) {
    summary.textContent = videoScriptSearchQuery
      ? `${filtered.length} / ${videoScripts.length} 条匹配`
      : `${videoScripts.length} 条脚本 · 最近更新`;
  }
  if (!videoScripts.length) {
    list.innerHTML = '<div class="video-task-empty"><strong>还没有视频脚本</strong><span>上传视频或手动创建第一条脚本。</span></div>';
    return filtered;
  }
  if (!filtered.length) {
    list.innerHTML = `<div class="video-task-empty"><strong>没有匹配脚本</strong><span>换一个名称、状态或 ID 片段。</span></div>`;
    return filtered;
  }
  list.innerHTML = "";
  filtered.forEach(script => {
    const item = document.createElement("button");
    item.className = `script-library-item${script.id === selectedVideoScriptId ? " active" : ""}`;
    item.type = "button";
    item.dataset.videoScriptId = script.id;
    const manualScript = script.sourceType === "MANUAL";
    item.innerHTML = `
      <span class="script-source-badge ${manualScript ? "manual" : "ai"}" aria-hidden="true">${manualScript ? "手" : "AI"}</span>
      <div><strong translate="${script.name ? 'no' : 'yes'}">${escapeHtml(script.name || script.sourceFileName || "未命名脚本")}</strong>
        <small>${escapeHtml(videoScriptPresentationNames[script.presentationMode] || "旧脚本兼容")} · ${manualScript ? "手动脚本" : escapeHtml(script.modelId || "等待驱动模型")} · <span data-video-script-progress-live="${escapeHtml(script.id)}">${videoScriptDisplayProgress(script).toFixed(script.status === "READY" ? 0 : 1)}</span>%</small></div>
      <em class="${videoScriptStatusClass(script.status)}">${escapeHtml(videoScriptStatusNames[script.status] || script.status)}</em>`;
    item.addEventListener("click", async () => {
      selectedVideoScriptId = script.id;
      renderVideoScriptLibrary();
      await loadVideoScriptDetail(script.id);
    });
    list.append(item);
  });
  return filtered;
}

function renderEmptyVideoScriptDetail() {
  selectedVideoScriptDetail = null;
  selectedVideoShot = null;
  const history = document.querySelector("#script-analysis-history");
  if (history) history.hidden = true;
  const detail = document.querySelector("#video-script-detail");
  if (!detail) return;
  detail.classList.remove("is-manual-script");
  document.querySelector("#script-detail-meta").innerHTML = "<span>本公司脚本库</span>";
  document.querySelector("#script-detail-title").setAttribute("translate", (false) ? "no" : "yes");
  document.querySelector("#script-detail-title").textContent = "还没有视频脚本";
  document.querySelector("#script-detail-source").textContent = "上传视频或手动创建后，脚本只会保存在登录账号所属公司。";
  document.querySelector("#script-detail-visual-tone").setAttribute("translate", (false) ? "no" : "yes");
  document.querySelector("#script-detail-visual-tone").textContent = "创建第一条脚本后，这里会展示视觉基调与分镜。";
  document.querySelector("#script-json-title").textContent = "生产 JSON";
  document.querySelector("#script-json-summary").textContent = "等待创建脚本";
  document.querySelector("#script-film-strip").innerHTML = '<div class="video-task-empty"><strong>脚本内容为空</strong><span>点击右上角“新建脚本”开始创建。</span></div>';
  document.querySelector("#script-json-content").textContent = "";
  document.querySelector("#copy-script-json").disabled = true;
  document.querySelector("#script-storyboard-view .shot-editor-card").hidden = true;
  ["#copy-video-script", "#reanalyze-video-script", "#delete-video-script", "#edit-video-script-tone"]
    .forEach(selector => { document.querySelector(selector).hidden = true; });
}

function resetVideoScriptState() {
  videoScriptLoadGeneration += 1;
  stopVideoScriptPolling();
  stopVideoScriptVisualProgress();
  videoScriptProgressStates.clear();
  videoScriptVisualProgress = 0;
  videoScriptVisualStatus = "";
  videoScriptVisualKey = "";
  videoScriptVisualServerProgress = 0;
  activeVideoScript = null;
  videoScriptProgressId = null;
  videoScriptDetailGeneration += 1;
  selectedVideoScriptId = null;
  selectedVideoScriptDetail = null;
  selectedVideoShot = null;
  videoScripts = [];
  renderEmptyVideoScriptDetail();
  renderVideoScriptLibrary();
}

function resetAiVideoTaskState() {
  aiVideoTaskLoadGeneration += 1;
  if (aiVideoTaskPollTimer) clearInterval(aiVideoTaskPollTimer);
  aiVideoTaskPollTimer = null;
  aiVideoTaskPollInFlight = false;
  stopAiVideoElapsedTimer();
  aiVideoTasks = [];
  selectedAiVideoTaskId = null;
  aiVideoTaskDetail = null;
  aiVideoTaskRenderSignature = "";
  flippedAiVideoPromptItems.clear();
  aiVideoPromptDrafts.clear();
  expandedAiVideoItemIds.clear();
  renderAiVideoTaskList();
  renderAiVideoTaskDetail();
}

function resetAiVideoMonitorState() {
  aiVideoMonitorLoadGeneration += 1;
  if (aiVideoMonitorTimer) clearInterval(aiVideoMonitorTimer);
  aiVideoMonitorTimer = null;
  aiVideoMonitorSnapshot = { stats: {}, tasks: [] };
  selectedMonitorTaskId = null;
  selectedMonitorTaskDetail = null;
  renderAiVideoMonitorOverview();
  renderAiVideoMonitorList();
  renderAiVideoMonitorDetail();
}

function resetGeneralVideoState() {
  generalVideoLoadGeneration += 1;
  if (generalVideoPollTimer) clearInterval(generalVideoPollTimer);
  generalVideoPollTimer = null;
  generalVideoPollInFlight = false;
  generalVideoTasks = [];
  expandedGeneralVideoTaskIds.clear();
  generalVideoTaskDetails.clear();
  clearGeneralVideoReferences();
  const form = document.querySelector("#general-video-form");
  form?.reset();
  syncGeneralVideoRouteResolution();
  document.querySelector("#general-prompt-count").textContent = "0 / 20,000";
  const formStatus = document.querySelector("#general-video-form-status");
  formStatus.textContent = "";
  formStatus.className = "general-form-status";
  document.querySelector("#general-video-task-grid").innerHTML = `<div class="general-video-empty">还没有通用生成任务。</div>`;
}

function resetProductCaptureState() {
  productCaptureLoadGeneration += 1;
  productCaptureDetailLoadGeneration += 1;
  productPublishWorkflowLookupGeneration += 1;
  stopProductPublishWorkflowPolling({ clearState: true });
  finalizedProductPublishWorkflowIds.clear();
  if (productCaptureSearchDebounceTimer) clearTimeout(productCaptureSearchDebounceTimer);
  productCaptureSearchDebounceTimer = null;
  if (productCapturePollTimer) clearInterval(productCapturePollTimer);
  productCapturePollTimer = null;
  productCapturePollInFlight = false;
  stopProductPairingCountdown();
  productPairingExpiresAt = null;
  productPublishIdempotencyKey = "";
  productPublishIdempotencyCaptureId = "";
  productUploadInFlightCaptureId = "";
  marketplaceShopLoadGeneration += 1;
  marketplaceShopActionsInFlight.clear();
  marketplaceShops = [];
  marketplaceShopPlatformFilter = "ALL";
  marketplaceShopStatusFilter = "ALL";
  marketplaceIntegrationStatus = {
    shopeeConfigured: false,
    tiktokShopConfigured: false,
    livePublishingEnabled: false,
    publishingProvider: "DRY_RUN"
  };
  productLastNotice = null;
  productSkuCompletenessDirty = false;
  resetProductSkuEditorState();
  productEditorDirty = false;
  productCaptureSaveInFlightId = null;
  productCaptures = [];
  productBulkSelectedIds.clear();
  productBulkSelectedCaptures.clear();
  productLiveImageProgress.clear();
  productBulkItems = [];
  productBulkOrganization = "";
  productBulkBusy = false;
  productBulkRuns.clear();
  productBulkViewGeneration++;
  stopProductBulkPolling();
  stopProductBulkHistoryPolling();
  document.querySelector('#product-bulk-history-dialog')?.close();
  document.querySelector("#product-bulk-dialog")?.close();
  selectedProductCaptureId = null;
  selectedProductCaptureDetail = null;
  productCaptureStatusFilter = "CAPTURED";
  productCaptureSearchQuery = "";
  productCapturePage = -1;
  productCaptureTotalElements = 0;
  productCaptureTotalPages = 0;
  productCaptureStatusCounts = emptyProductCaptureStatusCounts();
  productCaptureStatusCountsKnown = false;
  productCaptureLegacyListResponse = false;
  productCaptureListLoading = false;
  syncProductBulkSelection();
  productCaptureAppendLoading = false;
  productCapturePaginationMessage = "";
  const search = document.querySelector("#product-capture-search");
  if (search) search.value = "";
  document.querySelectorAll("#product-status-filter [data-product-status]").forEach(button => {
    const active = button.dataset.productStatus === "CAPTURED";
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
  document.querySelector("#product-capture-list")?.setAttribute("aria-busy", "false");
  const code = document.querySelector("#extension-pairing-code");
  code.textContent = "— — — — — —";
  code.dataset.code = "";
  document.querySelector("#copy-extension-pairing-code").disabled = true;
  document.querySelector("#extension-pairing-state").textContent = "未生成";
  document.querySelector("#extension-pairing-state").className = "";
  document.querySelector("#extension-pairing-expiry").textContent = "登录后可生成 5 分钟有效的绑定码";
  document.querySelector("#marketplace-shop-list").innerHTML = "<span>正在读取授权店铺…</span>";
  document.querySelector("#marketplace-shop-note").textContent = "";
  renderMarketplaceShops();
  renderProductSummary();
  renderProductCaptureList();
  renderProductCaptureEmpty();
}

function resetCompanyScopedWorkspaceState() {
  window.dispatchEvent(new Event("heyi:company-reset"));
  resetVideoScriptState();
  resetAiVideoTaskState();
  resetAiVideoMonitorState();
  resetGeneralVideoState();
  resetProductCaptureState();
}

function videoScriptRequestIsCurrent(generation, organizationId, pollingGeneration = null) {
  return generation === videoScriptLoadGeneration
    && currentUser?.organizationId === organizationId
    && (pollingGeneration === null || pollingGeneration === videoScriptPollingGeneration);
}

function isVideoScriptProgressDialogOpen() {
  return Boolean(videoScriptProgressId && document.querySelector("#video-script-dialog").open);
}

async function loadVideoScripts(preferredId = null, pollingGeneration = null) {
  const generation = ++videoScriptLoadGeneration;
  const organizationId = currentUser?.organizationId || "";
  const selectionAtRequest = selectedVideoScriptId;
  let refreshed = false;
  try {
    const result = await api("/api/v1/video-scripts");
    if (!videoScriptRequestIsCurrent(generation, organizationId, pollingGeneration)) return;
    videoScripts = (Array.isArray(result?.data) ? result.data : [])
      .filter(script => !script.organizationId || script.organizationId === organizationId);
    activeVideoScript = videoScripts.find(script => processingVideoScriptStatuses.has(script.status)) || null;
    refreshed = true;
    document.querySelector("#import-video-script").textContent = "＋ 新建脚本";
    if (!videoScripts.length) {
      selectedVideoScriptId = null;
      renderVideoScriptLibrary();
      renderEmptyVideoScriptDetail();
      stopVideoScriptPolling();
      stopVideoScriptVisualProgress();
      return;
    }
    // A selection made while the list request was pending belongs to the user.
    const preferredSelection = selectedVideoScriptId !== selectionAtRequest
      ? selectedVideoScriptId : preferredId || selectedVideoScriptId;
    const selectedId = videoScripts.some(script => script.id === preferredSelection)
      ? preferredSelection : videoScripts[0].id;
    selectedVideoScriptId = selectedId;
    renderVideoScriptLibrary();
    if (isVideoScriptProgressDialogOpen()) {
      const progressScript = videoScripts.find(script => script.id === videoScriptProgressId);
      if (progressScript) renderVideoScriptProgress(progressScript);
    }
    await loadVideoScriptDetail(selectedId, generation, organizationId, pollingGeneration);
    // A different task may be open in the dialog; list snapshots omit response text.
    if (isVideoScriptProgressDialogOpen() && videoScriptProgressId !== selectedId) {
      const progressId = videoScriptProgressId;
      const progressDetail = await api(`/api/v1/video-scripts/${encodeURIComponent(progressId)}`);
      if (videoScriptRequestIsCurrent(generation, organizationId, pollingGeneration)
          && isVideoScriptProgressDialogOpen() && videoScriptProgressId === progressId
          && progressDetail?.data?.script) renderVideoScriptProgress(progressDetail.data.script);
    }
  } catch (error) {
    if (videoScriptRequestIsCurrent(generation, organizationId, pollingGeneration)) {
      document.querySelector("#video-script-prototype-note").textContent = error.message;
    }
  } finally {
    // Detail failures must not stop the other scripts from receiving updates.
    if (refreshed && videoScriptRequestIsCurrent(generation, organizationId, pollingGeneration)) {
      if (activeVideoScript) startVideoScriptPolling();
      else stopVideoScriptPolling();
    }
  }
}

function scriptStageClass(stage = "") {
  const normalized = String(stage).toUpperCase();
  if (normalized.includes("HOOK")) return "hook";
  if (normalized.includes("CTA") || normalized.includes("TRUST")) return "cta";
  return "solution";
}

function videoShotPreviewUrl(scriptId, shotId) {
  // Separate local previews from previously cached OSS redirects.
  return `/api/v1/video-scripts/${encodeURIComponent(scriptId)}/shots/${encodeURIComponent(shotId)}/preview?v=20261008-local`;
}

function renderStoryboardBuildingState(script) {
  return '<div class="script-live-output"></div>';
}

function updateVideoScriptOutput(host, script) {
  if (!host) return;
  if (!script) { window.VideoScriptTerminal?.reset(host); return; }
  const progress = videoScriptDisplayProgress(script);
  const state = videoScriptProgressStates.get(String(script.id || ""));
  const runId = String(script.analysisRunId || "");
  // The list, detail and retry requests may finish in a different order. Use
  // the same accepted run as the percentage before replacing visible text.
  if (state?.retiredRunIds.has(runId) || (state?.runId && state.runId !== runId)) return;
  window.VideoScriptTerminal?.update(host, script, progress);
}

function selectRenderedShot(button, shot, scriptId) {
  selectedVideoShot = { ...shot, scriptId };
  document.querySelectorAll("#script-film-strip .film-frame").forEach(candidate => candidate.classList.remove("active"));
  button.classList.add("active");
  document.querySelector("#selected-shot-number").textContent =
    `分镜 ${String(shot.sequence || 1).padStart(2, "0")} · ${Number(shot.startSecond || 0).toFixed(2)}–${Number(shot.endSecond || 0).toFixed(2)} 秒 · 时长 ${Number(shot.durationSeconds || 0).toFixed(2)} 秒`;
  document.querySelector("#selected-shot-title").setAttribute("translate", (shot.title) ? "no" : "yes");
  document.querySelector("#selected-shot-title").textContent = shot.title || `分镜 ${shot.sequence || 1}`;
  document.querySelector("#selected-shot-copy").setAttribute("translate", (shot.sceneDescription) ? "no" : "yes");
  document.querySelector("#selected-shot-copy").textContent = shot.sceneDescription || "暂无画面描述";
  document.querySelector("#selected-shot-size").setAttribute("translate", (shot.shotSize) ? "no" : "yes");
  document.querySelector("#selected-shot-size").textContent = shot.shotSize || "未填写";
  document.querySelector("#selected-shot-camera").setAttribute("translate", (shot.cameraMovement) ? "no" : "yes");
  document.querySelector("#selected-shot-camera").textContent = shot.cameraMovement || "未填写";
  document.querySelector("#selected-shot-transition").setAttribute("translate", (shot.transitionDescription) ? "no" : "yes");
  document.querySelector("#selected-shot-transition").textContent = shot.transitionDescription || (Number(shot.sequence) === 1 ? "无" : "直接硬切");
  document.querySelector("#selected-shot-emotion").setAttribute("translate", (shot.emotion) ? "no" : "yes");
  document.querySelector("#selected-shot-emotion").textContent = shot.emotion || "未填写";
  const preview = document.querySelector("#selected-shot-preview");
  const manualScript = selectedVideoScriptDetail?.script?.sourceType === "MANUAL";
  if (!manualScript && scriptId && shot.id) {
    preview.onerror = () => { preview.hidden = true; };
    preview.onload = () => { preview.hidden = false; };
    preview.src = videoShotPreviewUrl(scriptId, shot.id);
  } else {
    preview.removeAttribute("src");
    preview.hidden = true;
  }
}

async function loadVideoScriptDetail(id, expectedGeneration = videoScriptLoadGeneration,
                                     expectedOrganizationId = currentUser?.organizationId || "",
                                     pollingGeneration = null) {
  if (!id) return;
  const detailGeneration = ++videoScriptDetailGeneration;
  selectedVideoScriptId = id;
  const result = await api(`/api/v1/video-scripts/${encodeURIComponent(id)}`);
  if (detailGeneration !== videoScriptDetailGeneration || selectedVideoScriptId !== id
      || !videoScriptRequestIsCurrent(expectedGeneration, expectedOrganizationId, pollingGeneration)) return;
  const detail = result?.data;
  if (!detail?.script || (detail.script.organizationId
      && detail.script.organizationId !== expectedOrganizationId)) {
    renderEmptyVideoScriptDetail();
    return;
  }
  selectedVideoScriptDetail = detail;
  const script = detail.script;
  if (isVideoScriptProgressDialogOpen() && videoScriptProgressId === script.id) renderVideoScriptProgress(script);
  const shots = Array.isArray(detail.shots) ? detail.shots : [];
  const manualScript = script.sourceType === "MANUAL";
  ["#copy-video-script", "#delete-video-script", "#edit-video-script-tone"]
    .forEach(selector => { document.querySelector(selector).hidden = false; });
  document.querySelector("#video-script-detail").classList.toggle("is-manual-script", manualScript);
  const storyboardTab = document.querySelector('[data-script-view="storyboard"]');
  const jsonTab = document.querySelector('[data-script-view="json"]');
  const tabNote = document.querySelector(".script-view-tabs > span");
  jsonTab.hidden = manualScript;
  tabNote.textContent = manualScript
    ? "保存后版本号递增，并同步更新视频执行分镜"
    : "保存后版本号递增，并同步更新分镜与生图 JSON";
  if (manualScript) {
    storyboardTab.classList.add("active");
    jsonTab.classList.remove("active");
    document.querySelector("#script-storyboard-view").hidden = false;
    document.querySelector("#script-json-view").hidden = true;
  }
  document.querySelector("#script-detail-title").setAttribute("translate", (script.name) ? "no" : "yes");
  document.querySelector("#script-detail-title").textContent = script.name || "视频脚本分析中";
  document.querySelector("#script-detail-meta").innerHTML =
    `<span>${manualScript ? "手动编排" : "AI 视频反推"}</span><span>版本 v${Number(script.version || 1)}</span><span>${shots.length} 个分镜</span>`;
  document.querySelector("#script-detail-source").innerHTML =
    `来源：${escapeHtml(manualScript ? "手动创建" : script.sourceFileName || "上传视频")} · ${escapeHtml(videoScriptStatusNames[script.status] || script.status)} · <span data-video-script-progress-live="${escapeHtml(script.id)}">${videoScriptDisplayProgress(script).toFixed(script.status === "READY" ? 0 : 1)}</span>%`;
  document.querySelector("#script-detail-visual-tone").setAttribute("translate", (script.status !== "FAILED" && (script.visualTone || script.summary)) ? "no" : "yes");
  document.querySelector("#script-detail-visual-tone").textContent = script.status === "FAILED"
    ? `拆解失败：${script.errorMessage || "模型未返回有效结果"}`
    : (script.visualTone || script.summary || "模型正在理解视频画面，请稍候…");
  document.querySelector("#script-json-title").textContent = `生产 JSON · v${Number(script.version || 1)}`;
  document.querySelector("#script-json-summary").textContent = `${shots.length ? "字段校验通过" : "等待生成"} · ${shots.length} 个 shots`;
  const rawScriptJson = detail.scriptJson;
  let displayScriptJson = rawScriptJson || "等待 AI 生成生产 JSON…";
  if (rawScriptJson) {
    try {
      const parsedScriptJson = typeof rawScriptJson === "string"
        ? JSON.parse(rawScriptJson)
        : rawScriptJson;
      displayScriptJson = JSON.stringify(parsedScriptJson, null, 2);
    } catch {
      // 保留模型返回的原始内容，仍通过查看区自动换行展示。
    }
  }
  document.querySelector("#script-json-content").textContent = displayScriptJson;
  document.querySelector("#copy-script-json").disabled = !rawScriptJson;
  const reanalyzeButton = document.querySelector("#reanalyze-video-script");
  const canManageScripts = hasAnyPermission("video:script:manage");
  const canRetry = canManageScripts && script.status === "FAILED" && script.sourceType === "VIDEO_ANALYSIS";
  const canReanalyze = canManageScripts && script.status === "READY" && script.sourceType === "VIDEO_ANALYSIS";
  const canInspectAnalysis = canManageScripts && script.sourceType === "VIDEO_ANALYSIS"
    && (processingVideoScriptStatuses.has(script.status) || script.status === "FAILED");
  reanalyzeButton.hidden = !canReanalyze && !canInspectAnalysis;
  reanalyzeButton.textContent = canInspectAnalysis
    ? (script.status === "FAILED" ? "重试拆解" : "查看拆解进度")
    : shots.length === 9 ? "重新精拆 9 镜" : "补足为 9 镜";
  reanalyzeButton.title = canInspectAnalysis ? "查看当前脚本的拆解状态和失败原因"
    : shots.length === 9 ? "使用当前原视频重新生成完整 9 个分镜"
    : `当前只有 ${shots.length} 个分镜，使用原视频重新精拆为 9 个`;
  const deleteButton = document.querySelector("#delete-video-script");
  const copyButton = document.querySelector("#copy-video-script");
  const editToneButton = document.querySelector("#edit-video-script-tone");
  deleteButton.hidden = !canManageScripts;
  copyButton.hidden = !canManageScripts;
  editToneButton.hidden = !canManageScripts;
  document.querySelector("#edit-video-shot").hidden = !canManageScripts;
  const scriptIsProcessing = processingVideoScriptStatuses.has(script.status);
  deleteButton.disabled = scriptIsProcessing;
  const deleteShotButton = document.querySelector("#delete-video-shot");
  deleteShotButton.hidden = !canManageScripts;
  deleteShotButton.disabled = scriptIsProcessing || shots.length <= 1;
  deleteShotButton.title = shots.length <= 1 ? "至少保留一个分镜" : "删除后自动分配剩余分镜时长，总时长不变";
  copyButton.disabled = scriptIsProcessing || !["READY", "DRAFT"].includes(script.status);
  editToneButton.disabled = scriptIsProcessing;
  deleteButton.title = deleteButton.disabled ? "视频拆解处理中，暂时不能删除" : "删除脚本及其服务器文件";
  copyButton.title = copyButton.disabled ? "脚本处理完成后才能复制" : "创建一个可独立编辑的脚本副本";
  editToneButton.title = editToneButton.disabled ? "视频拆解处理中，暂时不能编辑" : "编辑全局视觉基调";

  if (!isVideoScriptProgressDialogOpen()) {
    startVideoScriptVisualProgress(script.status, script.progress, script.id, script.analysisRunId);
    if (!scriptIsProcessing) stopVideoScriptVisualProgress();
  }
  const strip = document.querySelector("#script-film-strip");
  const shotEditor = document.querySelector("#script-storyboard-view .shot-editor-card");
  const outputHistory = document.querySelector("#script-analysis-history");
  if (outputHistory) {
    outputHistory.hidden = scriptIsProcessing || !shots.length || !script.analysisOutput;
    if (!outputHistory.hidden) updateVideoScriptOutput(document.querySelector("#script-saved-output"), script);
  }
  if (scriptIsProcessing || !shots.length) {
    selectedVideoShot = null;
    shotEditor.hidden = true;
    strip.classList.toggle("is-processing", script.status !== "FAILED");
    strip.classList.toggle("is-error", script.status === "FAILED");
    if (!strip.querySelector(".script-live-output")) strip.innerHTML = renderStoryboardBuildingState(script);
    updateVideoScriptOutput(strip.querySelector(".script-live-output"), script);
    return;
  }
  shotEditor.hidden = false;
  strip.classList.remove("is-processing", "is-error");
  const previouslySelectedShotId = selectedVideoShot?.scriptId === id ? selectedVideoShot.id : null;
  const selectedShotIndex = Math.max(0, shots.findIndex(shot => shot.id === previouslySelectedShotId));
  strip.innerHTML = "";
  shots.forEach((shot, index) => {
    const button = document.createElement("button");
    button.className = `film-frame ${manualScript ? "manual-frame" : "has-preview"} ${scriptStageClass(shot.stage)}${index === selectedShotIndex ? " active" : ""}`;
    button.type = "button";
    button.innerHTML = manualScript
      ? `<span class="manual-shot-kicker"><i>${String(shot.sequence || index + 1).padStart(2, "0")}</i><em>${escapeHtml(shot.stage || "CONTENT")}</em></span><b>${escapeHtml(shot.title || "未命名分镜")}</b><p>${escapeHtml(shot.sceneDescription || "暂未填写画面描述")}</p><small>${escapeHtml(shot.shotSize || "景别待定")} · ${escapeHtml(shot.cameraMovement || "运镜待定")}</small>`
      : `<img class="shot-frame-preview" src="${videoShotPreviewUrl(id, shot.id)}" alt="${escapeHtml(shot.title || `分镜 ${index + 1}`)}原视频画面"><i>${String(shot.sequence || index + 1).padStart(2, "0")}</i><b>${escapeHtml(shot.title || "未命名分镜")}</b><small>${escapeHtml(shot.cameraMovement || shot.shotSize || "镜头待定")}</small>`;
    const timeLabel = document.createElement("span");
    timeLabel.className = "shot-time";
    timeLabel.textContent = `${Number(shot.durationSeconds || 0).toFixed(2)} 秒`;
    button.append(timeLabel);
    if (!manualScript) button.querySelector("img").addEventListener("error", event => {
      button.classList.remove("has-preview");
      event.currentTarget.remove();
    });
    button.addEventListener("click", () => selectRenderedShot(button, shot, id));
    strip.append(button);
  });
  selectRenderedShot(strip.querySelectorAll(".film-frame")[selectedShotIndex], shots[selectedShotIndex], id);
}

document.querySelector("#delete-video-shot").addEventListener("click", async () => {
  const shot = selectedVideoShot;
  const scriptId = selectedVideoScriptDetail?.script?.id;
  const button = document.querySelector("#delete-video-shot");
  const status = document.querySelector("#shot-delete-status");
  if (!shot?.id || !scriptId || button.disabled) return;
  if (!window.confirm(`删除分镜「${shot.title || shot.sequence}」？剩余分镜将按原时长比例重新分配秒数，总时长不变。`)) return;
  const organizationId = currentUser?.organizationId;
  button.disabled = true;
  status.textContent = "正在删除并调整时长…";
  try {
    await api(`/api/v1/video-scripts/${encodeURIComponent(scriptId)}/shots/${encodeURIComponent(shot.id)}`, {method: "DELETE"});
    if (currentUser?.organizationId !== organizationId) return;
    await loadVideoScripts(scriptId);
    status.textContent = "已删除，剩余分镜时长已自动调整";
  } catch (error) { status.textContent = error.message; }
  finally { button.disabled = (selectedVideoScriptDetail?.shots?.length || 0) <= 1
    || processingVideoScriptStatuses.has(selectedVideoScriptDetail?.script?.status); }
});

document.querySelector("#edit-video-shot").addEventListener("click", () => {
  if (!selectedVideoShot?.id || !selectedVideoShot?.scriptId) return;
  const dialog = document.querySelector("#video-shot-edit-dialog");
  const manualScript = selectedVideoScriptDetail?.script?.sourceType === "MANUAL";
  dialog.classList.toggle("is-manual-edit", manualScript);
  document.querySelector("#shot-edit-dialog-copy").textContent = manualScript
    ? "人工分镜无需图片，直接修改画面与动作。"
    : "修改后会同步保存到当前脚本和生图 JSON。";
  if (!manualScript) document.querySelector("#shot-edit-preview").src = videoShotPreviewUrl(selectedVideoShot.scriptId, selectedVideoShot.id);
  document.querySelector("#shot-edit-title").value = selectedVideoShot.title || "";
  document.querySelector("#shot-edit-stage").value = selectedVideoShot.stage || "CONTENT";
  document.querySelector("#shot-edit-description").value = selectedVideoShot.sceneDescription || "";
  document.querySelector("#shot-edit-motion").value = selectedVideoShot.motionDescription || "";
  document.querySelector("#shot-edit-intensity").value = selectedVideoShot.motionIntensity || "";
  document.querySelector("#shot-edit-narration").value = selectedVideoShot.narration || "";
  document.querySelector("#shot-edit-size").value = selectedVideoShot.shotSize || "";
  document.querySelector("#shot-edit-camera").value = selectedVideoShot.cameraMovement || "";
  document.querySelector("#shot-edit-transition").value = selectedVideoShot.transitionDescription || (Number(selectedVideoShot.sequence) === 1 ? "无" : "直接硬切");
  document.querySelector("#shot-edit-emotion").value = selectedVideoShot.emotion || "";
  document.querySelector("#shot-edit-advanced").open = !manualScript;
  document.querySelector("#video-shot-edit-error").hidden = true;
  dialog.showModal();
});

document.querySelector("#video-shot-edit-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (!selectedVideoShot?.id || !selectedVideoShot?.scriptId) return;
  const save = document.querySelector("#save-video-shot");
  const error = document.querySelector("#video-shot-edit-error");
  save.disabled = true;
  save.textContent = "正在保存…";
  error.hidden = true;
  try {
    await api(`/api/v1/video-scripts/${encodeURIComponent(selectedVideoShot.scriptId)}/shots/${encodeURIComponent(selectedVideoShot.id)}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        title: document.querySelector("#shot-edit-title").value.trim(),
        stage: document.querySelector("#shot-edit-stage").value,
        sceneDescription: document.querySelector("#shot-edit-description").value.trim(),
        motionDescription: document.querySelector("#shot-edit-motion").value.trim(),
        motionIntensity: document.querySelector("#shot-edit-intensity").value,
        narration: document.querySelector("#shot-edit-narration").value.trim(),
        shotSize: document.querySelector("#shot-edit-size").value.trim(),
        cameraMovement: document.querySelector("#shot-edit-camera").value.trim(),
        transitionDescription: document.querySelector("#shot-edit-transition").value.trim(),
        emotion: document.querySelector("#shot-edit-emotion").value.trim()
      })
    });
    const scriptId = selectedVideoShot.scriptId;
    document.querySelector("#video-shot-edit-dialog").close();
    await loadVideoScriptDetail(scriptId);
    await loadVideoScripts(scriptId);
  } catch (requestError) {
    error.textContent = requestError.message;
    error.hidden = false;
  } finally {
    save.disabled = false;
    save.textContent = "保存分镜";
  }
});

document.querySelector("#copy-video-script").addEventListener("click", async () => {
  if (!selectedVideoScriptId) return;
  const button = document.querySelector("#copy-video-script");
  if (button.disabled) return;
  button.disabled = true;
  button.textContent = "复制中…";
  try {
    const result = await api(`/api/v1/video-scripts/${encodeURIComponent(selectedVideoScriptId)}/copy`, { method: "POST" });
    const copiedScript = result?.data;
    if (!copiedScript?.id) throw new Error("版本已复制，但没有返回新脚本编号");
    await loadVideoScripts(copiedScript.id);
    button.textContent = "复制完成";
  } catch (error) {
    window.alert(error.message);
    button.textContent = "复制版本";
  } finally {
    button.disabled = false;
    window.setTimeout(() => { button.textContent = "复制版本"; }, 1200);
  }
});

document.querySelector("#edit-video-script-tone").addEventListener("click", () => {
  if (!selectedVideoScriptId || !selectedVideoScriptDetail?.script) return;
  document.querySelector("#video-script-tone-input").value = selectedVideoScriptDetail.script.visualTone || "";
  document.querySelector("#video-script-tone-error").hidden = true;
  document.querySelector("#video-script-tone-dialog").showModal();
});

document.querySelector("#video-script-tone-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (!selectedVideoScriptId) return;
  const scriptId = selectedVideoScriptId;
  const save = document.querySelector("#save-video-script-tone");
  const error = document.querySelector("#video-script-tone-error");
  save.disabled = true;
  save.textContent = "正在保存…";
  error.hidden = true;
  try {
    await api(`/api/v1/video-scripts/${encodeURIComponent(scriptId)}/visual-tone`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ visualTone: document.querySelector("#video-script-tone-input").value.trim() })
    });
    document.querySelector("#video-script-tone-dialog").close();
    await loadVideoScripts(scriptId);
  } catch (requestError) {
    error.textContent = requestError.message;
    error.hidden = false;
  } finally {
    save.disabled = false;
    save.textContent = "保存基调";
  }
});

function startVideoScriptPolling() {
  if (videoScriptStatusTimer || document.querySelector("#video-scripts-page").hidden) return;
  const generation = ++videoScriptPollingGeneration;
  let polling = false;
  const timer = setInterval(async () => {
    if (generation !== videoScriptPollingGeneration || polling || document.hidden) return;
    polling = true;
    try {
      await loadVideoScripts(selectedVideoScriptId, generation);
    } catch {
      // Keep all tasks visible and retry on the next heartbeat.
    } finally {
      polling = false;
    }
  }, 3000);
  videoScriptStatusTimer = timer;
}

function stopVideoScriptPolling() {
  videoScriptPollingGeneration += 1;
  if (videoScriptStatusTimer) clearInterval(videoScriptStatusTimer);
  videoScriptStatusTimer = null;
}

function openVideoScriptProgress(script) {
  renderVideoScriptProgress(script);
  document.querySelector("#video-script-dialog").showModal();
  if (processingVideoScriptStatuses.has(script.status)) startVideoScriptPolling();
}

document.querySelector("#import-video-script").addEventListener("click", async () => {
  renderVideoScriptProgress(null);
  prepareVideoScriptCreationDialog("ai");
  syncScriptPresentationModePicker();
  document.querySelector("#video-script-dialog").showModal();
});

document.querySelectorAll("[data-script-create-mode]").forEach(button => {
  button.addEventListener("click", () => {
    selectVideoScriptCreateMode(button.dataset.scriptCreateMode);
    if (button.dataset.scriptCreateMode === "manual") renderManualShotRows();
  });
});

document.querySelectorAll("#video-script-form input[name=presentationMode]").forEach(input => {
  input.addEventListener("change", syncScriptPresentationModePicker);
});

document.querySelectorAll("[data-manual-analysis-mode]").forEach(button => {
  button.addEventListener("click", () => selectManualAnalysisMode(button.dataset.manualAnalysisMode));
});

function formatVideoScriptClipTime(value) {
  const safeValue = Number.isFinite(Number(value)) ? Math.max(0, Number(value)) : 0;
  const totalTenths = Math.round(safeValue * 10);
  const minutes = Math.floor(totalTenths / 600);
  const seconds = ((totalTenths % 600) / 10).toFixed(1).padStart(4, "0");
  return `${String(minutes).padStart(2, "0")}:${seconds}`;
}

function selectedVideoScriptClipEndSecond() {
  return Math.min(videoScriptClipDuration, Math.max(videoScriptClipStartSecond, videoScriptClipEndSecond));
}

function pauseVideoScriptClipPreview(buttonCopy = "▶ 预览所选片段") {
  const preview = document.querySelector("#script-video-preview");
  const toggle = document.querySelector("#script-video-preview-toggle");
  preview?.pause();
  videoScriptClipPreviewing = false;
  if (toggle) toggle.textContent = buttonCopy;
}

function paintVideoScriptClipSelection(seekPreview = false) {
  const startRange = document.querySelector("#script-video-clip-start");
  const endRange = document.querySelector("#script-video-clip-end");
  const preview = document.querySelector("#script-video-preview");
  const progress = document.querySelector("#script-video-preview-progress");
  const endSecond = selectedVideoScriptClipEndSecond();
  const selectedDuration = Math.max(0, endSecond - videoScriptClipStartSecond);
  const safeDuration = Math.max(0.001, videoScriptClipDuration);
  const startPosition = Math.max(0, Math.min(100, (videoScriptClipStartSecond / safeDuration) * 100));
  const endPosition = Math.max(startPosition, Math.min(100, (endSecond / safeDuration) * 100));
  startRange.value = String(videoScriptClipStartSecond);
  endRange.value = String(endSecond);
  startRange.setAttribute("aria-valuetext", `开始 ${formatVideoScriptClipTime(videoScriptClipStartSecond)}`);
  endRange.setAttribute("aria-valuetext", `结束 ${formatVideoScriptClipTime(endSecond)}，已选 ${selectedDuration.toFixed(1)} 秒`);
  const selection = document.querySelector("#script-video-clip-selection");
  selection.style.left = `${startPosition}%`;
  selection.style.width = `${Math.max(0, endPosition - startPosition)}%`;
  document.querySelector("#script-video-clip-window").textContent =
    `${formatVideoScriptClipTime(videoScriptClipStartSecond)} → ${formatVideoScriptClipTime(endSecond)}`;
  document.querySelector("#script-video-clip-start-value").textContent = formatVideoScriptClipTime(videoScriptClipStartSecond);
  document.querySelector("#script-video-clip-end-value").textContent = formatVideoScriptClipTime(endSecond);
  document.querySelector("#script-video-duration").textContent = formatVideoScriptClipTime(videoScriptClipDuration);
  document.querySelector("#script-video-clip-summary").textContent =
    `已选 ${selectedDuration.toFixed(1)} 秒 · 原视频 ${formatVideoScriptClipTime(videoScriptClipDuration)}`;
  if (seekPreview && preview?.readyState >= 1) {
    preview.currentTime = videoScriptClipStartSecond;
    if (progress) progress.style.width = "0%";
  }
}

function syncVideoScriptClipControlState(manualMode = !document.querySelector("#script-create-manual")?.hidden) {
  const toggle = document.querySelector("#script-video-preview-toggle");
  const ready = Number.isFinite(videoScriptClipDuration) && videoScriptClipDuration > 0;
  document.querySelectorAll("#script-video-clip-start, #script-video-clip-end").forEach(range => {
    range.disabled = manualMode || !ready;
    range.setAttribute("aria-disabled", String(range.disabled));
  });
  toggle.disabled = manualMode || !ready;
  if (manualMode) pauseVideoScriptClipPreview();
}

function clearVideoScriptClipEditor(clearFileInput = false) {
  videoScriptClipLoadGeneration += 1;
  pauseVideoScriptClipPreview();
  const preview = document.querySelector("#script-video-preview");
  if (preview) {
    preview.removeAttribute("src");
    preview.load();
  }
  if (videoScriptClipObjectUrl) URL.revokeObjectURL(videoScriptClipObjectUrl);
  videoScriptClipObjectUrl = "";
  videoScriptClipDuration = 0;
  videoScriptClipStartSecond = 0;
  videoScriptClipEndSecond = 0;
  const editor = document.querySelector("#script-video-clip-editor");
  editor.hidden = true;
  editor.classList.remove("has-error");
  document.querySelector("#script-create-ai")?.classList.remove("has-video-clip");
  document.querySelectorAll("#script-video-clip-start, #script-video-clip-end").forEach(range => {
    range.min = "0";
    range.max = "0";
    range.value = "0";
    range.disabled = true;
    range.setAttribute("aria-valuetext", "尚未选择视频");
  });
  const selection = document.querySelector("#script-video-clip-selection");
  selection.style.left = "0%";
  selection.style.width = "0%";
  document.querySelector("#script-video-preview-progress").style.width = "0%";
  document.querySelector("#script-video-clip-help").textContent = "正在读取视频时长…";
  document.querySelector("#script-video-clip-window").textContent = "00:00.0 → 00:15.0";
  document.querySelector("#script-video-clip-start-value").textContent = "00:00.0";
  document.querySelector("#script-video-clip-end-value").textContent = "00:15.0";
  document.querySelector("#script-video-duration").textContent = "--:--.-";
  document.querySelector("#script-video-clip-summary").textContent = "截取后将仅拆解所选片段";
  const toggle = document.querySelector("#script-video-preview-toggle");
  toggle.disabled = true;
  toggle.textContent = "▶ 预览所选片段";
  if (clearFileInput) {
    document.querySelector("#script-source-video").value = "";
    document.querySelector("#script-video-file-name").textContent = "MP4、MOV · 自由截取最长 15 秒";
  }
}

function showVideoScriptClipError(message) {
  const editor = document.querySelector("#script-video-clip-editor");
  editor.hidden = false;
  editor.classList.add("has-error");
  document.querySelector("#script-video-clip-help").textContent = message;
  document.querySelector("#script-video-clip-summary").textContent = "请更换视频文件后重试";
  syncVideoScriptClipControlState();
}

function loadVideoScriptClip(file) {
  clearVideoScriptClipEditor(false);
  if (!file) {
    document.querySelector("#script-video-file-name").textContent = "MP4、MOV · 自由截取最长 15 秒";
    return;
  }
  const generation = videoScriptClipLoadGeneration;
  const editor = document.querySelector("#script-video-clip-editor");
  const preview = document.querySelector("#script-video-preview");
  document.querySelector("#script-create-ai")?.classList.add("has-video-clip");
  editor.hidden = false;
  document.querySelector("#script-video-clip-help").textContent = "正在读取视频时长…";
  videoScriptClipObjectUrl = URL.createObjectURL(file);
  const objectUrl = videoScriptClipObjectUrl;
  preview.src = objectUrl;
  preview.addEventListener("loadedmetadata", () => {
    if (generation !== videoScriptClipLoadGeneration || objectUrl !== videoScriptClipObjectUrl) return;
    const duration = Number(preview.duration);
    if (!Number.isFinite(duration) || duration <= 0) {
      showVideoScriptClipError("无法读取视频时长");
      return;
    }
    videoScriptClipDuration = duration;
    videoScriptClipStartSecond = 0;
    videoScriptClipEndSecond = Math.min(duration, videoScriptClipMaxDuration);
    document.querySelectorAll("#script-video-clip-start, #script-video-clip-end").forEach(range => {
      range.max = String(Number(duration.toFixed(3)));
    });
    document.querySelector("#script-video-clip-help").textContent =
      "拖动两个端点调整起止位置，选中片段最长 15 秒";
    paintVideoScriptClipSelection(true);
    syncVideoScriptClipControlState();
  }, { once: true });
  preview.addEventListener("error", () => {
    if (generation !== videoScriptClipLoadGeneration || objectUrl !== videoScriptClipObjectUrl) return;
    showVideoScriptClipError("无法预览这个视频文件");
  }, { once: true });
  preview.load();
}

document.querySelector("#script-source-video").addEventListener("change", event => {
  const file = event.target.files?.[0];
  document.querySelector("#script-video-file-name").textContent = file?.name || "MP4、MOV · 自由截取最长 15 秒";
  loadVideoScriptClip(file);
});

function updateVideoScriptClipEndpoint(endpoint, rawValue) {
  if (!videoScriptClipDuration) return;
  const minimumGap = Math.min(0.1, videoScriptClipDuration);
  const candidate = Math.max(0, Math.min(videoScriptClipDuration, Number(rawValue) || 0));
  if (endpoint === "start") {
    videoScriptClipStartSecond = Math.min(candidate, Math.max(0, videoScriptClipEndSecond - minimumGap));
    if (videoScriptClipEndSecond - videoScriptClipStartSecond > videoScriptClipMaxDuration) {
      videoScriptClipEndSecond = Math.min(videoScriptClipDuration, videoScriptClipStartSecond + videoScriptClipMaxDuration);
    }
  } else {
    videoScriptClipEndSecond = Math.max(candidate, Math.min(videoScriptClipDuration, videoScriptClipStartSecond + minimumGap));
    if (videoScriptClipEndSecond - videoScriptClipStartSecond > videoScriptClipMaxDuration) {
      videoScriptClipStartSecond = Math.max(0, videoScriptClipEndSecond - videoScriptClipMaxDuration);
    }
  }
  videoScriptClipStartSecond = Number(videoScriptClipStartSecond.toFixed(3));
  videoScriptClipEndSecond = Number(videoScriptClipEndSecond.toFixed(3));
  pauseVideoScriptClipPreview();
  paintVideoScriptClipSelection(true);
}

document.querySelector("#script-video-clip-start").addEventListener("input", event => {
  if (!event.currentTarget.disabled) updateVideoScriptClipEndpoint("start", event.currentTarget.value);
});

document.querySelector("#script-video-clip-end").addEventListener("input", event => {
  if (!event.currentTarget.disabled) updateVideoScriptClipEndpoint("end", event.currentTarget.value);
});

document.querySelector("#script-video-preview-toggle").addEventListener("click", async () => {
  const preview = document.querySelector("#script-video-preview");
  const toggle = document.querySelector("#script-video-preview-toggle");
  if (toggle.disabled || !videoScriptClipDuration) return;
  if (videoScriptClipPreviewing && !preview.paused) {
    pauseVideoScriptClipPreview("▶ 继续预览");
    return;
  }
  const endSecond = selectedVideoScriptClipEndSecond();
  if (preview.currentTime < videoScriptClipStartSecond || preview.currentTime >= endSecond - 0.05) {
    preview.currentTime = videoScriptClipStartSecond;
  }
  try {
    await preview.play();
    videoScriptClipPreviewing = true;
    toggle.textContent = "Ⅱ 暂停预览";
  } catch {
    document.querySelector("#script-video-clip-help").textContent = "预览未能播放，请确认浏览器支持该视频格式";
  }
});

document.querySelector("#script-video-preview").addEventListener("timeupdate", event => {
  if (!videoScriptClipDuration) return;
  const preview = event.currentTarget;
  const endSecond = selectedVideoScriptClipEndSecond();
  const selectedDuration = Math.max(0.001, endSecond - videoScriptClipStartSecond);
  const progress = ((preview.currentTime - videoScriptClipStartSecond) / selectedDuration) * 100;
  document.querySelector("#script-video-preview-progress").style.width = `${Math.max(0, Math.min(100, progress))}%`;
  if (videoScriptClipPreviewing && preview.currentTime >= endSecond - 0.04) {
    preview.pause();
    preview.currentTime = endSecond;
    videoScriptClipPreviewing = false;
    document.querySelector("#script-video-preview-toggle").textContent = "↻ 重新预览";
  }
});

document.querySelector("#script-video-preview").addEventListener("ended", () => {
  if (!videoScriptClipDuration) return;
  videoScriptClipPreviewing = false;
  document.querySelector("#script-video-preview-progress").style.width = "100%";
  document.querySelector("#script-video-preview-toggle").textContent = "↻ 重新预览";
});

function waitMilliseconds(milliseconds) {
  return new Promise(resolve => window.setTimeout(resolve, milliseconds));
}

async function publicApi(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    cache: options.cache || "no-store",
    credentials: "same-origin",
    headers: { ...(options.headers || {}) }
  });
  const result = await response.json().catch(() => null);
  if (!response.ok) throw new Error(result?.message || "请求暂时无法完成");
  return result;
}

function selectLoginMethod(method, { focus = true } = {}) {
  const normalized = method === "password" ? "password" : "wechat";
  document.querySelector("#wechat-login-panel").hidden = normalized !== "wechat";
  document.querySelector("#password-login-panel").hidden = normalized !== "password";
  loginMethodTabs.forEach(button => {
    const selected = button.dataset.loginMethod === normalized;
    button.classList.toggle("active", selected);
    button.setAttribute("aria-selected", String(selected));
  });
  if (normalized === "password" && focus) {
    window.setTimeout(() => document.querySelector("#organization-code")?.focus(), 0);
  }
}

loginMethodTriggers.forEach(button => {
  button.addEventListener("click", () => selectLoginMethod(button.dataset.loginMethod));
});

function renderWechatQr(status) {
  if (!wechatQrCode || !wechatQrFrame) return;
  const enabled = Boolean(status?.enabled);
  wechatQrFrame.classList.toggle("is-disabled", !enabled);
  wechatLoginButton.hidden = !enabled;
  if (!enabled) {
    wechatQrCode.innerHTML = '<span class="wechat-mark" aria-hidden="true">微</span>';
    return;
  }
  const organizationCode = document.querySelector("#organization-code").value.trim().toLowerCase() || "heyi";
  const loginUrl = new URL("/api/v1/auth/social/wechat/start", window.location.origin);
  loginUrl.searchParams.set("organizationCode", organizationCode);
  if (typeof window.qrcode !== "function") {
    wechatQrCode.innerHTML = '<span class="wechat-mark" aria-hidden="true">微</span><small>加载失败</small>';
    return;
  }
  const qr = window.qrcode(0, "M");
  qr.addData(loginUrl.toString());
  qr.make();
  wechatQrCode.innerHTML = qr.createSvgTag(5, 0);
}

document.querySelector("#organization-code")?.addEventListener("change", () => {
  renderWechatQr(socialProviderStatuses.get("wechat"));
});

async function loadSocialLoginProviders() {
  try {
    const result = await publicApi("/api/v1/auth/social/providers");
    const statuses = new Map((result.data || []).map(item => [item.id, item]));
    socialProviderStatuses = statuses;
    socialLoginButtons.forEach(button => {
      const status = statuses.get(button.dataset.socialProvider);
      button.disabled = !status?.enabled;
      button.dataset.message = status?.message || "正式环境配置后启用";
      button.title = status?.enabled ? `${status.label}登录` : button.dataset.message;
    });
    const wechat = statuses.get("wechat");
    renderWechatQr(wechat);
    socialLoginStatus.textContent = wechat?.enabled ? "扫码即登录" : "微信登录暂未启用";
  } catch (error) {
    renderWechatQr(null);
    socialLoginStatus.textContent = "微信登录尚未配置";
  }
  const wechatAvailable = Boolean(socialProviderStatuses.get("wechat")?.enabled);
  const wechatTab = document.querySelector('[data-login-method="wechat"]');
  if (wechatTab) wechatTab.hidden = !wechatAvailable;
  document.querySelector(".login-method-switch").hidden = !wechatAvailable;
  if (!wechatAvailable) selectLoginMethod("password", { focus: false });
  const googleAvailable = Boolean(socialProviderStatuses.get("google")?.enabled);
  document.querySelector("#social-login-options").hidden = !googleAvailable;
  document.querySelector(".social-login-divider").hidden = !googleAvailable;
}

socialLoginButtons.forEach(button => {
  button.addEventListener("click", () => {
    if (button.disabled) return;
    const organizationCode = document.querySelector("#organization-code").value.trim().toLowerCase();
    if (!/^[a-z0-9_-]{2,40}$/.test(organizationCode)) {
      loginError.textContent = "请先填写有效的公司代码";
      loginError.hidden = false;
      document.querySelector("#organization-code").focus();
      return;
    }
    localStorage.setItem("heyi:last-organization-code", organizationCode);
    const provider = encodeURIComponent(button.dataset.socialProvider);
    window.location.assign(`/api/v1/auth/social/${provider}/start?organizationCode=${encodeURIComponent(organizationCode)}`);
  });
});

async function handleSocialLoginReturn() {
  const hash = window.location.hash || "";
  if (hash.startsWith("#social-error=")) {
    loginError.textContent = decodeURIComponent(hash.slice("#social-error=".length).replace(/\+/g, " "));
    loginError.hidden = false;
    history.replaceState(null, document.title, window.location.pathname);
    return;
  }
  if (hash !== "#social-bind") return;
  try {
    const result = await publicApi("/api/v1/auth/social/pending");
    const pending = result.data;
    if (!pending) throw new Error("绑定请求已失效，请重新选择登录方式");
    document.querySelector("#social-bind-organization").value = pending.organizationCode || "";
    document.querySelector("#social-bind-description").textContent = pending.email
      ? `将 ${pending.providerLabel} 账号 ${pending.email} 绑定到现有企业账号。`
      : `将当前${pending.providerLabel}账号绑定到现有企业账号。`;
    socialBindDialog.showModal();
  } catch (error) {
    loginError.textContent = error.message;
    loginError.hidden = false;
  } finally {
    history.replaceState(null, document.title, window.location.pathname);
  }
}

document.querySelectorAll("[data-social-bind-close]").forEach(button => {
  button.addEventListener("click", () => socialBindDialog.close());
});

socialBindForm.addEventListener("submit", async event => {
  event.preventDefault();
  const submit = document.querySelector("#social-bind-submit");
  const error = document.querySelector("#social-bind-error");
  submit.disabled = true;
  submit.querySelector("span").textContent = "正在验证";
  error.hidden = true;
  try {
    const result = await publicApi("/api/v1/auth/social/bind", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        organizationCode: document.querySelector("#social-bind-organization").value,
        phone: document.querySelector("#social-bind-phone").value.trim(),
        password: document.querySelector("#social-bind-password").value
      })
    });
    socialBindDialog.close();
    socialBindForm.reset();
    await showDashboard(result.data);
  } catch (exception) {
    error.textContent = exception.message;
    error.hidden = false;
  } finally {
    submit.disabled = false;
    submit.querySelector("span").textContent = "验证并绑定";
  }
});

async function waitForManualScriptAnalysis(job, onUpdate) {
  const jobId = String(job?.id || "").trim();
  if (!jobId) throw new Error("后台分析任务创建失败，请重试");
  let consecutiveNetworkErrors = 0;
  for (;;) {
    await waitMilliseconds(1800);
    let snapshot;
    try {
      snapshot = (await api(`/api/v1/video-scripts/manual/analysis-jobs/${encodeURIComponent(jobId)}`))?.data;
      consecutiveNetworkErrors = 0;
    } catch (error) {
      consecutiveNetworkErrors += 1;
      if (consecutiveNetworkErrors >= 20) {
        throw new Error(`后台任务仍在运行，但状态连接持续中断：${error.message}`);
      }
      await waitMilliseconds(Math.min(5000, 500 + consecutiveNetworkErrors * 500));
      continue;
    }
    if (typeof onUpdate === "function") onUpdate(snapshot);
    if (snapshot?.status === "SUCCEEDED") {
      if (!snapshot.result) throw new Error("AI 已完成分析，但没有返回可用分镜");
      return snapshot.result;
    }
    if (snapshot?.status === "FAILED") {
      throw new Error(snapshot.message || "AI 分析失败，请重试");
    }
  }
}

async function optimizedManualReferenceFile(file) {
  if (!file || typeof createImageBitmap !== "function") return file;
  let bitmap;
  try {
    bitmap = await createImageBitmap(file);
    const maxSide = 1920;
    const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.size <= 3 * 1024 * 1024) return file;
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const context = canvas.getContext("2d", { alpha: false });
    context.fillStyle = "#fff";
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise(resolve => canvas.toBlob(resolve, "image/jpeg", .9));
    if (!blob || blob.size >= file.size) return file;
    const outputName = file.name.replace(/\.[^.]+$/, "") + "-ai-analysis.jpg";
    return new File([blob], outputName, { type: "image/jpeg", lastModified: file.lastModified });
  } catch (_) {
    return file;
  } finally {
    bitmap?.close?.();
  }
}

document.querySelector("#analyze-manual-script").addEventListener("click", async () => {
  const form = document.querySelector("#video-script-form");
  const input = document.querySelector("#manual-script-ai-input");
  const button = document.querySelector("#analyze-manual-script");
  const status = document.querySelector("#manual-script-ai-status");
  const prompt = input.value.trim();
  if (!prompt) {
    status.textContent = "请先粘贴业务人员的一整段提示词。";
    status.classList.add("is-error");
    input.focus();
    return;
  }
  button.disabled = true;
  button.textContent = "AI 正在拆分…";
  status.textContent = "正在理解业务需求，并编排为 15 秒内的 9 个连续分镜…";
  status.classList.remove("is-error", "is-success");
  try {
    const result = await api("/api/v1/video-scripts/manual/analysis-jobs/prompt", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        prompt,
        targetMarket: form.elements.manualTargetMarket.value,
        name: form.elements.manualName.value.trim(),
        visualTone: form.elements.manualVisualTone.value.trim(),
        presentationMode: selectedScriptPresentationMode()
      })
    });
    const draft = await waitForManualScriptAnalysis(result?.data, snapshot => {
      status.textContent = snapshot?.message || "AI 正在后台编排 9 个连续分镜…";
    });
    fillManualScriptDraft(draft);
    status.textContent = "已填入 9 个分镜，时间轴连续均分为 0—15 秒；你可以继续手动修改后保存。";
    status.classList.add("is-success");
    document.querySelector("#manual-shot-rows").scrollTop = 0;
  } catch (error) {
    status.textContent = `AI 分析失败：${error.message}`;
    status.classList.add("is-error");
  } finally {
    button.disabled = false;
    button.textContent = "AI 分析并填入";
  }
});

function syncManualProductAnalysisAvailability() {
  const hasImage = manualProductReferences.length > 0;
  const allImagesExplained = hasImage && manualProductReferences.every(reference => reference.description.trim());
  const hasContext = Boolean(document.querySelector("#manual-product-context").value.trim());
  const button = document.querySelector("#analyze-manual-product");
  button.disabled = !(hasImage && allImagesExplained && hasContext);
  button.title = hasImage && allImagesExplained && hasContext
    ? ""
    : "请添加参考图、填写每张图的用途说明和整体脚本目标";
}

function manualProductReferenceLabel(index) {
  return `图${["一", "二", "三", "四", "五", "六"][index] || index + 1}`;
}

function updateManualProductUploadSummary() {
  const totalBytes = manualProductReferences.reduce((sum, reference) => sum + reference.file.size, 0);
  document.querySelector("#manual-product-image-name").textContent = manualProductReferences.length
    ? `已添加 ${manualProductReferences.length}/${manualProductReferenceLimit} 张 · ${readableFileSize(totalBytes)}`
    : "最多 6 张；单张最大 20MB，总计最大 60MB";
  document.querySelector("#manual-product-reference-empty").hidden = manualProductReferences.length > 0;
}

function renderManualProductReferences() {
  const list = document.querySelector("#manual-product-reference-list");
  list.replaceChildren();
  manualProductReferences.forEach((reference, index) => {
    const card = document.createElement("article");
    card.className = "manual-product-reference-card";

    const thumb = document.createElement("div");
    thumb.className = "manual-product-reference-thumb";
    const image = document.createElement("img");
    image.src = reference.previewUrl;
    image.alt = `${manualProductReferenceLabel(index)}预览`;
    const sequence = document.createElement("span");
    sequence.className = "manual-product-reference-index";
    sequence.textContent = String(index + 1).padStart(2, "0");
    thumb.append(image, sequence);

    const copy = document.createElement("div");
    copy.className = "manual-product-reference-copy";
    const head = document.createElement("div");
    head.className = "manual-product-reference-head";
    const label = document.createElement("strong");
    label.textContent = manualProductReferenceLabel(index);
    const fileName = document.createElement("small");
    fileName.textContent = `${reference.file.name} · ${readableFileSize(reference.file.size)}`;
    fileName.title = reference.file.name;
    const controls = document.createElement("div");
    controls.className = "manual-product-reference-controls";
    [["up", "↑", "前移"], ["down", "↓", "后移"], ["remove", "×", "移除"]].forEach(([action, text, title]) => {
      const control = document.createElement("button");
      control.type = "button";
      control.dataset.referenceAction = action;
      control.dataset.referenceId = String(reference.id);
      control.textContent = text;
      control.title = title;
      if ((action === "up" && index === 0) || (action === "down" && index === manualProductReferences.length - 1)) {
        control.disabled = true;
      }
      controls.append(control);
    });
    head.append(label, fileName, controls);

    const description = document.createElement("textarea");
    description.rows = 2;
    description.maxLength = 1200;
    description.dataset.referenceDescription = String(reference.id);
    description.value = reference.description;
    description.placeholder = `${manualProductReferenceLabel(index)}是什么、在脚本中用于什么？例如：产品正面图，用于识别包装与品类。`;
    description.setAttribute("aria-label", `${manualProductReferenceLabel(index)}的内容和用途说明`);
    copy.append(head, description);
    card.append(thumb, copy);
    list.append(card);
  });
  updateManualProductUploadSummary();
  syncManualProductAnalysisAvailability();
}

function clearManualProductReferences() {
  manualProductReferences.forEach(reference => URL.revokeObjectURL(reference.previewUrl));
  manualProductReferences = [];
  renderManualProductReferences();
}

function manualProductProgressState(elapsedSeconds) {
  if (elapsedSeconds < 3) {
    return { percent: 4 + elapsedSeconds * 2.6, step: 0, label: `正在上传并读取 ${manualProductReferences.length} 张参考图` };
  }
  if (elapsedSeconds < 45) {
    return { percent: 12 + (elapsedSeconds - 3) * .58, step: 1, label: "正在按顺序逐图识别内容、结构和用途" };
  }
  if (elapsedSeconds < 150) {
    return { percent: 36 + (elapsedSeconds - 45) * .38, step: 2, label: "正在结合产品说明编排 9 个分镜" };
  }
  if (elapsedSeconds < 210) {
    return { percent: 76 + (elapsedSeconds - 150) * .2, step: 3, label: "正在校验分镜数量与内容完整性" };
  }
  return {
    percent: Math.min(96, 88 + (elapsedSeconds - 210) * .025),
    step: 3,
    label: "模型响应较慢，系统仍在等待或自动重试"
  };
}

function formatProgressElapsed(totalSeconds) {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = Math.floor(totalSeconds % 60);
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function renderManualProductProgress(percent, step, label, elapsedSeconds) {
  const panel = document.querySelector("#manual-product-progress");
  const track = document.querySelector("#manual-product-progress-track");
  const safePercent = Math.max(0, Math.min(100, Math.round(percent)));
  panel.hidden = false;
  document.querySelector("#manual-product-progress-label").textContent = label;
  document.querySelector("#manual-product-progress-number").textContent = String(safePercent);
  document.querySelector("#manual-product-progress-elapsed").textContent = formatProgressElapsed(elapsedSeconds);
  document.querySelector("#manual-product-progress-bar").style.width = `${safePercent}%`;
  track.setAttribute("aria-valuenow", String(safePercent));
  document.querySelectorAll("[data-manual-product-step]").forEach((item, index) => {
    item.classList.toggle("is-done", index < step || safePercent === 100);
    item.classList.toggle("is-active", index === step && safePercent < 100);
  });
}

function startManualProductProgress() {
  if (manualProductProgressTimer) window.clearInterval(manualProductProgressTimer);
  const panel = document.querySelector("#manual-product-progress");
  panel.classList.remove("is-success", "is-error");
  manualProductProgressStartedAt = performance.now();
  const update = () => {
    const elapsedSeconds = Math.max(0, (performance.now() - manualProductProgressStartedAt) / 1000);
    const state = manualProductProgressState(elapsedSeconds);
    renderManualProductProgress(state.percent, state.step, state.label, elapsedSeconds);
  };
  update();
  manualProductProgressTimer = window.setInterval(update, 500);
}

function finishManualProductProgress(succeeded, message) {
  if (manualProductProgressTimer) window.clearInterval(manualProductProgressTimer);
  manualProductProgressTimer = null;
  const elapsedSeconds = manualProductProgressStartedAt
    ? Math.max(0, (performance.now() - manualProductProgressStartedAt) / 1000)
    : 0;
  const panel = document.querySelector("#manual-product-progress");
  panel.classList.toggle("is-success", succeeded);
  panel.classList.toggle("is-error", !succeeded);
  if (succeeded) {
    renderManualProductProgress(100, 3, message || "多图识别与 9 镜编排已完成", elapsedSeconds);
  } else {
    const current = manualProductProgressState(elapsedSeconds);
    renderManualProductProgress(current.percent, current.step, message || "分析未完成", elapsedSeconds);
  }
}

document.querySelector("#manual-product-image").addEventListener("change", event => {
  const status = document.querySelector("#manual-script-ai-status");
  const acceptedTypes = new Set(["image/jpeg", "image/png", "image/webp"]);
  const candidates = [...(event.currentTarget.files || [])];
  let rejectedMessage = "";
  for (const file of candidates) {
    if (manualProductReferences.length >= manualProductReferenceLimit) {
      rejectedMessage = `最多只能添加 ${manualProductReferenceLimit} 张参考图。`;
      break;
    }
    if (!acceptedTypes.has(file.type)) {
      rejectedMessage = `${file.name} 不是支持的 JPG、PNG 或 WebP 图片。`;
      continue;
    }
    if (file.size > manualProductImageByteLimit) {
      rejectedMessage = `${file.name} 超过单张 20MB 限制。`;
      continue;
    }
    const totalBytes = manualProductReferences.reduce((sum, reference) => sum + reference.file.size, 0);
    if (totalBytes + file.size > manualProductTotalByteLimit) {
      rejectedMessage = "所有参考图合计不能超过 60MB。";
      break;
    }
    const duplicate = manualProductReferences.some(reference =>
      reference.file.name === file.name && reference.file.size === file.size
        && reference.file.lastModified === file.lastModified);
    if (duplicate) continue;
    manualProductReferences.push({
      id: ++manualProductReferenceSequence,
      file,
      description: "",
      previewUrl: URL.createObjectURL(file)
    });
  }
  event.currentTarget.value = "";
  renderManualProductReferences();
  if (rejectedMessage) {
    status.textContent = rejectedMessage;
    status.classList.add("is-error");
  } else if (candidates.length) {
    status.textContent = `已添加 ${manualProductReferences.length} 张参考图，请逐张填写内容和用途说明。`;
    status.classList.remove("is-error", "is-success");
  }
  if (!manualProductProgressTimer) {
    const progress = document.querySelector("#manual-product-progress");
    progress.hidden = true;
    progress.classList.remove("is-success", "is-error");
  }
});

document.querySelector("#manual-product-reference-list").addEventListener("input", event => {
  const id = Number(event.target.dataset.referenceDescription || 0);
  const reference = manualProductReferences.find(item => item.id === id);
  if (!reference) return;
  reference.description = event.target.value;
  syncManualProductAnalysisAvailability();
});

document.querySelector("#manual-product-reference-list").addEventListener("click", event => {
  const button = event.target.closest("button[data-reference-action]");
  if (!button) return;
  const index = manualProductReferences.findIndex(item => item.id === Number(button.dataset.referenceId));
  if (index < 0) return;
  const action = button.dataset.referenceAction;
  if (action === "remove") {
    URL.revokeObjectURL(manualProductReferences[index].previewUrl);
    manualProductReferences.splice(index, 1);
  } else if (action === "up" && index > 0) {
    [manualProductReferences[index - 1], manualProductReferences[index]] =
      [manualProductReferences[index], manualProductReferences[index - 1]];
  } else if (action === "down" && index < manualProductReferences.length - 1) {
    [manualProductReferences[index], manualProductReferences[index + 1]] =
      [manualProductReferences[index + 1], manualProductReferences[index]];
  }
  renderManualProductReferences();
});

document.querySelector("#manual-product-context").addEventListener("input", syncManualProductAnalysisAvailability);

document.querySelector("#analyze-manual-product").addEventListener("click", async () => {
  const form = document.querySelector("#video-script-form");
  const input = document.querySelector("#manual-product-image");
  const button = document.querySelector("#analyze-manual-product");
  const status = document.querySelector("#manual-script-ai-status");
  const productContext = document.querySelector("#manual-product-context").value.trim();
  if (!manualProductReferences.length) {
    status.textContent = "请先添加至少一张参考图片。";
    status.classList.add("is-error");
    input.click();
    return;
  }
  const missingDescriptionIndex = manualProductReferences.findIndex(reference => !reference.description.trim());
  if (missingDescriptionIndex >= 0) {
    status.textContent = `请填写${manualProductReferenceLabel(missingDescriptionIndex)}是什么、在脚本中用于什么。`;
    status.classList.add("is-error");
    document.querySelectorAll("[data-reference-description]")[missingDescriptionIndex]?.focus();
    return;
  }
  if (!productContext) {
    status.textContent = "请填写这些参考图要如何组合，以及整体脚本重点。";
    status.classList.add("is-error");
    document.querySelector("#manual-product-context").focus();
    return;
  }
  button.disabled = true;
  button.textContent = "正在逐图分析…";
  status.textContent = `AI 正在按图一至${manualProductReferenceLabel(manualProductReferences.length - 1)}逐张理解内容和用途，并设计 15 秒脚本…`;
  status.classList.remove("is-error", "is-success");
  startManualProductProgress();
  try {
    const payload = new FormData();
    status.textContent = `正在优化 ${manualProductReferences.length} 张参考图，保留识别细节并减少传输等待…`;
    const optimizedFiles = await Promise.all(manualProductReferences.map(reference =>
      optimizedManualReferenceFile(reference.file)));
    manualProductReferences.forEach((reference, index) => {
      payload.append("images", optimizedFiles[index]);
      payload.append("imageContexts", reference.description.trim());
    });
    payload.append("requirements", "");
    payload.append("productContext", productContext);
    payload.append("targetMarket", form.elements.manualTargetMarket.value);
    payload.append("name", form.elements.manualName.value.trim());
    payload.append("visualTone", form.elements.manualVisualTone.value.trim());
    payload.append("presentationMode", selectedScriptPresentationMode());
    const result = await api("/api/v1/video-scripts/manual/analysis-jobs/product", {
      method: "POST",
      body: payload
    });
    const draft = await waitForManualScriptAnalysis(result?.data, snapshot => {
      const elapsedSeconds = Math.max(0, (performance.now() - manualProductProgressStartedAt) / 1000);
      const progress = manualProductProgressState(elapsedSeconds);
      renderManualProductProgress(progress.percent, progress.step,
        snapshot?.message || progress.label, elapsedSeconds);
    });
    fillManualScriptDraft(draft);
    const language = draft?.detectedLanguage ? `，旁白语言：${draft.detectedLanguage}` : "";
    status.textContent = `已联合分析 ${manualProductReferences.length} 张参考图并填入 9 个分镜${language}；请确认图片用途、产品动作和旁白后保存。`;
    status.classList.add("is-success");
    finishManualProductProgress(true, "多图识别与 9 镜编排已完成");
    document.querySelector("#manual-shot-rows").scrollTop = 0;
  } catch (error) {
    status.textContent = `多图分析失败：${error.message}`;
    status.classList.add("is-error");
    finishManualProductProgress(false, `分析未完成：${error.message}`);
  } finally {
    syncManualProductAnalysisAvailability();
    button.textContent = "分析多图并填入";
  }
});

document.querySelector("#video-script-form").addEventListener("submit", async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const dialog = document.querySelector("#video-script-dialog");
  const manual = !document.querySelector("#script-create-manual").hidden;
  const submit = document.querySelector("#submit-video-script");
  const note = document.querySelector("#video-script-prototype-note");
  if (manual) {
    const shots = Array.from(document.querySelectorAll("[data-manual-shot]")).map((row, index) => ({
      stage: form.elements[`manualStage${index}`].value,
      title: form.elements[`manualTitle${index}`].value.trim(),
      startSecond: Number(form.elements[`manualStart${index}`].value),
      endSecond: Number(form.elements[`manualEnd${index}`].value),
      sceneDescription: form.elements[`manualScene${index}`].value.trim(),
      motionDescription: form.elements[`manualMotion${index}`].value.trim(),
      narration: form.elements[`manualNarration${index}`].value.trim()
    }));
    submit.disabled = true;
    submit.textContent = "正在保存 9 个分镜…";
    note.textContent = "正在校验时间轴并生成生产 JSON…";
    try {
      const result = await api("/api/v1/video-scripts/manual", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: form.elements.manualName.value.trim(),
          targetMarket: form.elements.manualTargetMarket.value,
          visualTone: form.elements.manualVisualTone.value.trim(),
          presentationMode: selectedScriptPresentationMode(),
          shots
        })
      });
      const script = result?.data;
      if (!script?.id) throw new Error("脚本保存成功，但没有返回脚本编号");
      form.reset();
      document.querySelector("#manual-shot-rows").innerHTML = "";
      clearManualProductReferences();
      document.querySelector("#manual-product-context").value = "";
      document.querySelector("#manual-product-progress").hidden = true;
      syncManualProductAnalysisAvailability();
      dialog.close();
      await loadVideoScripts(script.id);
      await loadVideoScriptDetail(script.id);
    } catch (error) {
      note.textContent = `保存失败：${error.message}`;
    } finally {
      submit.disabled = false;
      submit.textContent = "保存 9 分镜脚本";
    }
    return;
  }
  const file = document.querySelector("#script-source-video").files?.[0];
  if (!file) {
    note.textContent = "请先选择需要拆解的视频。";
    return;
  }
  if (!Number.isFinite(videoScriptClipDuration) || videoScriptClipDuration <= 0) {
    note.textContent = "正在读取视频时长，请稍后再开始拆解。";
    return;
  }
  const selectedClipDuration = videoScriptClipEndSecond - videoScriptClipStartSecond;
  if (selectedClipDuration <= 0 || selectedClipDuration > videoScriptClipMaxDuration + 0.001) {
    note.textContent = "请选择一段不超过 15 秒的视频。";
    return;
  }
  submit.disabled = true;
  submit.textContent = "正在上传视频…";
  note.textContent = "正在上传视频和所选片段位置，请勿关闭页面…";
  try {
    const payload = new FormData(form);
    payload.set("clipStartSecond", String(Number(videoScriptClipStartSecond.toFixed(3))));
    payload.set("clipEndSecond", String(Number(videoScriptClipEndSecond.toFixed(3))));
    const result = await api("/api/v1/video-scripts/analyze", { method: "POST", body: payload });
    const script = result?.data;
    if (!script?.id) throw new Error("任务创建成功，但没有返回任务编号");
    note.textContent = "视频上传完成，AI 正在后台拆解。";
    form.reset();
    clearVideoScriptClipEditor(true);
    selectedVideoScriptId = script.id;
    dialog.close();
    startVideoScriptPolling();
    await loadVideoScripts(script.id);
  } catch (error) {
    note.textContent = `创建失败：${error.message}`;
  } finally {
    submit.disabled = false;
    submit.textContent = "开始 AI 拆解";
  }
});

document.querySelector("#video-script-dialog").addEventListener("close", async () => {
  clearVideoScriptClipEditor(true);
  videoScriptProgressId = null;
  await loadVideoScripts(selectedVideoScriptId);
});

document.querySelector("#retry-video-script").addEventListener("click", async () => {
  const scriptId = videoScriptProgressId;
  const organizationId = currentUser?.organizationId || "";
  const retry = document.querySelector("#retry-video-script");
  if (!scriptId || retry.disabled) return;
  retry.disabled = true;
  retry.textContent = "正在重新提交…";
  try {
    const result = await api(`/api/v1/video-scripts/${encodeURIComponent(scriptId)}/retry`, { method: "POST" });
    if (currentUser?.organizationId !== organizationId) return;
    resetVideoScriptTaskProgress(scriptId, result?.data?.analysisRunId);
    if (isVideoScriptProgressDialogOpen() && videoScriptProgressId === scriptId && result?.data) {
      renderVideoScriptProgress(result.data);
    }
    startVideoScriptPolling();
    await loadVideoScripts(selectedVideoScriptId || scriptId);
  } catch (error) {
    if (currentUser?.organizationId === organizationId && videoScriptProgressId === scriptId) {
      document.querySelector("#active-script-message").textContent = error.message;
    }
  } finally {
    retry.disabled = false;
    retry.textContent = "重试当前任务";
  }
});

document.querySelector("#reanalyze-video-script").addEventListener("click", async () => {
  const script = selectedVideoScriptDetail?.script;
  if (!script?.id || script.id !== selectedVideoScriptId) return;
  if (processingVideoScriptStatuses.has(script.status) || script.status === "FAILED") {
    openVideoScriptProgress(script);
    return;
  }
  const scriptId = script.id;
  const organizationId = currentUser?.organizationId || "";
  const button = document.querySelector("#reanalyze-video-script");
  const retryFailed = selectedVideoScriptDetail?.script?.status === "FAILED";
  if (!retryFailed && !window.confirm("将使用原视频重新精拆为完整 9 个分镜，成功后替换当前分镜。确认继续？")) return;
  button.disabled = true;
  button.textContent = "正在提交…";
  try {
    const result = await api(`/api/v1/video-scripts/${encodeURIComponent(scriptId)}/reanalyze`, { method: "POST" });
    if (currentUser?.organizationId !== organizationId) return;
    resetVideoScriptTaskProgress(scriptId, result?.data?.analysisRunId);
    if (selectedVideoScriptId === scriptId && result?.data
        && !document.querySelector("#video-script-dialog").open) {
      openVideoScriptProgress(result.data);
    }
    startVideoScriptPolling();
    await loadVideoScripts(selectedVideoScriptId || scriptId);
  } catch (error) {
    if (currentUser?.organizationId === organizationId) window.alert(error.message);
  } finally {
    button.disabled = false;
    if (selectedVideoScriptDetail?.script?.id === scriptId
        && selectedVideoScriptDetail.script.status === "READY") {
      button.textContent = (selectedVideoScriptDetail.shots || []).length === 9 ? "重新精拆 9 镜" : "补足为 9 镜";
    }
  }
});

document.querySelector("#delete-video-script").addEventListener("click", async () => {
  if (!selectedVideoScriptId) return;
  const button = document.querySelector("#delete-video-script");
  if (button.disabled) return;
  const manualScript = selectedVideoScriptDetail?.script?.sourceType === "MANUAL";
  const deleteContents = manualScript ? "中文分镜" : "分镜、JSON 和服务器上的上传文件";
  if (!window.confirm(`确认删除这个视频脚本？${deleteContents}也会一起删除。`)) return;
  button.disabled = true;
  try {
    await api(`/api/v1/video-scripts/${encodeURIComponent(selectedVideoScriptId)}`, { method: "DELETE" });
    if (activeVideoScript?.id === selectedVideoScriptId) activeVideoScript = null;
    selectedVideoScriptId = null;
    renderVideoScriptProgress(null);
    await loadVideoScripts();
  } catch (error) {
    button.disabled = false;
    window.alert(error.message);
  }
});

document.querySelectorAll(".script-library-item").forEach(item => {
  item.addEventListener("click", () => {
    document.querySelectorAll(".script-library-item").forEach(candidate => candidate.classList.remove("active"));
    item.classList.add("active");
  });
});

document.querySelectorAll("[data-script-view]").forEach(button => {
  button.addEventListener("click", () => {
    const json = button.dataset.scriptView === "json";
    document.querySelectorAll("[data-script-view]").forEach(candidate => candidate.classList.toggle("active", candidate === button));
    document.querySelector("#script-storyboard-view").hidden = json;
    document.querySelector("#script-json-view").hidden = !json;
  });
});

document.querySelector("#copy-script-json").addEventListener("click", async event => {
  const button = event.currentTarget;
  const json = document.querySelector("#script-json-content").textContent || "";
  const copied = json && await copyTextToClipboard(json);
  button.textContent = copied ? "已复制" : "复制失败";
  window.setTimeout(() => { button.textContent = "复制 JSON"; }, 1400);
});

document.querySelectorAll(".film-frame").forEach(frame => {
  frame.addEventListener("click", () => {
    document.querySelectorAll(".film-frame").forEach(candidate => candidate.classList.remove("active"));
    frame.classList.add("active");
    const shot = Number(frame.dataset.shot || 1);
    const stage = shot <= 3 ? "Hook" : shot <= 6 ? "Solution" : "Trust & CTA";
    document.querySelector("#selected-shot-number").textContent = `分镜 ${String(shot).padStart(2, "0")} · ${stage}`;
    document.querySelector("#selected-shot-title").setAttribute("translate", (frame.dataset) ? "no" : "yes");
  document.querySelector("#selected-shot-title").textContent = frame.dataset.shotTitle;
    document.querySelector("#selected-shot-copy").setAttribute("translate", (frame.dataset) ? "no" : "yes");
  document.querySelector("#selected-shot-copy").textContent = frame.dataset.shotCopy;
  });
});

const aiVideoTaskStatusNames = {
  DRAFT: "待上传素材",
  READY: "可开始生图",
  IMAGE_GENERATING: "图片生成中",
  IMAGE_READY: "图片已完成",
  VIDEO_GENERATING: "视频生成中",
  VIDEO_READY: "视频已完成",
  FAILED: "生成有失败"
};

const aiVideoGenerationModeNames = {
  MODEL_WEAR: "人物穿搭",
  PRODUCT_ONLY: "商品展示",
  PARTIAL_INTERACT: "局部互动",
  CAST_PRODUCT: "人物＋商品"
};

const aiVideoBackgroundModeNames = {
  SCRIPT_ORIGINAL: "原背景",
  AI_DESIGNED: "AI 搭景"
};

function aiVideoMode(task) {
  return task?.generationMode || "MODEL_WEAR";
}

function aiVideoPrimaryAssetType(task) {
  return aiVideoMode(task) === "MODEL_WEAR" ? "MODEL_IMAGE" : "PRODUCT_SCENE_IMAGE";
}

function aiVideoPrimaryAssetName(task) {
  return aiVideoMode(task) === "MODEL_WEAR" ? "AI 模特图" : "AI 场景主图";
}

const aiVideoItemStatusNames = {
  READY: "等待生成",
  GENERATING_MODEL: "生成模特图",
  GENERATING_STORYBOARD: "生成分镜图",
  IMAGE_READY: "图片已完成",
  VIDEO_SUBMITTING: "正在提交视频",
  VIDEO_GENERATING: "视频生成中",
  VIDEO_READY: "视频已完成",
  VIDEO_FAILED: "视频生成失败",
  FAILED: "生成失败"
};

function aiVideoImagePresentation(task, item, model, storyboard) {
  let steps = {};
  try { steps = JSON.parse(item.imageProgressJson || "{}") || {}; } catch { /* Historical tasks have no telemetry. */ }
  const modelStep = steps[aiVideoPrimaryAssetType(task)];
  const gridStep = steps.STORYBOARD_GRID;
  const labels = { PREPARING: "准备参考图", ANALYSIS_QUEUED: "等待识别", ANALYZING: "商品识别中",
    ANALYSIS_RETRY_WAIT: "识别等待重试", GENERATION_QUEUED: "等待生图请求", GENERATING: "正在生图", DOWNLOADING: "下载图片中",
    RESPONSE_RECEIVED: "已收到生图结果", PROCESSING_RESPONSE: "处理生图结果", SWITCHING_MODEL: "切换备用生图模型",
    VALIDATION_QUEUED: "等待校验", VALIDATING: "正在校验", VALIDATION_RETRY_WAIT: "校验等待重试",
    STORING: "保存图片中", RETRY_WAIT: "等待重试", FAILED: "生成失败", COMPLETED: "保存完成" };
  const retrying = /图片自动恢复等待重试：/.test(item.errorMessage || "")
    || (!model && /RETRY_WAIT$/.test(modelStep?.stage || ""))
    || (!storyboard && /RETRY_WAIT$/.test(gridStep?.stage || ""));
  const pendingDownload = Boolean(item.imageDownloadPending) && !["VIDEO_FAILED", "VIDEO_READY"].includes(item.status);
  const active = pendingDownload || retrying || ["GENERATING_MODEL", "GENERATING_STORYBOARD"].includes(item.status)
    || (task.status === "IMAGE_GENERATING" && item.status === "READY");
  const failed = !pendingDownload && !retrying && ["FAILED", "VIDEO_FAILED"].includes(item.status);
  const state = asset => asset ? "completed" : retrying ? "retrying" : failed ? "failed" : active ? "processing" : "waiting";
  const downloadLabel = `下载图片中 · 已尝试 ${Math.max(0, Number(item.pendingImageDownloadAttempts) || 0)} 次`;
  const modelLabel = model ? "已生成" : pendingDownload && !modelStep && item.pendingImageAssetType === aiVideoPrimaryAssetType(task) ? downloadLabel
    : retrying ? (labels[modelStep?.stage] || "等待重试") : failed ? "生成失败" : active ? (labels[modelStep?.stage] || "等待执行记录") : "等待生成";
  const storyboardLabel = storyboard ? "已生成" : pendingDownload && !gridStep && item.pendingImageAssetType === "STORYBOARD_GRID" ? downloadLabel
    : retrying ? (labels[gridStep?.stage] || "等待重试") : failed ? "生成失败"
    : active && model ? (labels[gridStep?.stage] || "准备分镜素材") : active ? "等待主图" : "等待生成";
  return { active, retrying, modelState: state(model), shotState: state(storyboard), modelLabel, storyboardLabel,
    modelStep, gridStep,
    hint: retrying ? "系统会自动重试，无需重复提交" : failed ? "查看下方失败原因" : active && model
      ? "生成和校验完成后自动展示" : active ? "主图完成后继续生成九宫格" : "九宫格完成后在这里展示" };
}

function aiVideoImageAttemptDetails(step) {
  if (!step) return "历史次数未记录";
  const n = key => Math.max(0, Number(step[key]) || 0);
  const calls = n('generationAttempts');
  return `已记录：生图 ${calls} 次 · 重试 ${Math.max(0, calls - 1)} 次\n识别 ${n('analysisAttempts')} 次 · 校验 ${n('validationAttempts')} 次 · 下载 ${n('downloadAttempts')} 次`;
}

function aiVideoImageAttemptMarkup(step) {
  if (!step) return "";
  return `<div class="image-attempt-details" title="本轮已记录的真实请求次数，含线路内部重试；手动重做后重新计数；历史未记录次数不补算"><span>${escapeHtml(aiVideoImageAttemptDetails(step))}</span><small>步骤更新：${escapeHtml(new Date(step.updatedAt).toLocaleTimeString((globalThis.HoyeeLanguage?.locale || "zh-CN"), { hour12: false }))}</small></div>`;
}

function aiVideoImagePlaceholder(label, hint) {
  return `<span class="image-stage-placeholder" role="status"><span class="image-stage-indicator" aria-hidden="true"></span><strong>${escapeHtml(label)}</strong><small>${escapeHtml(hint)}</small></span>`;
}

function isAiVideoCopyrightError(message = "") {
  return /版权|著作权|知识产权|商标|授权角色|第三方内容.{0,20}相似|相似性.{0,20}防护|供应商相似性|copyright|copyrighted|intellectual property|trademark|protected character|character rights|IP infringement|third[- ]party content.{0,30}similar|content similarity|similarity safeguard/i
    .test(String(message || ""));
}

function isAiVideoConcurrencyError(message = "") {
  return /并发限制|并发已满|限流|请求过于频繁|concurrenc(?:y|t)|concurrency limit exceeded|too many concurrent|rate.?limit|too many requests|429/i
    .test(String(message || ""));
}

function isAiVideoTransientNetworkError(message = "") {
  return /fetch failed|网络超时|响应超时|headers? timeout|body timeout|UND_ERR_(?:HEADERS|BODY)_TIMEOUT|ETIMEDOUT|header parser received no bytes|I\/O error on POST request|unexpected end of file|connection reset/i
    .test(String(message || ""));
}

function isAiVideoReferenceFetchError(message = "") {
  return /参考图片(?:获取|读取|下载|访问)失败|素材暂时无法读取|reference image.{0,30}(?:fetch|download|unavailable)|(?:fetch|download).{0,30}reference image/i
    .test(String(message || ""));
}

function isAiVideoImageDownloadRetry(message = "") {
  return /模型已生成图片.{0,30}结果地址已保存.{0,30}下载失败|系统将继续重试同一地址/i
    .test(String(message || ""));
}

function friendlyAiVideoError(message = "", type = "image") {
  const value = String(message || "").trim();
  const imageStageFailure = /图片生成失败|图片模型|image generation|image model/i.test(value);
  const video = type === "video" && !imageStageFailure;

  if (/STRUCTURE_ANALYSIS_PAUSED|服装结构识别.*缺少必填字段/.test(value)) {
    return "服装结构识别未通过，尚未提交图片生成";
  }
  if (/IMAGE_REQUEST_REJECTED|image request is invalid/i.test(value)) {
    return "图片接口拒绝请求，请检查模型参数";
  }
  if (/IMAGE_RESULT_UNKNOWN/.test(value)) {
    return "图片结果待核对，已停止自动重复生成";
  }
  if (isAiVideoCopyrightError(value)) {
    return "版权限制：素材含受版权、商标或角色授权保护的内容";
  }
  if (isAiVideoConcurrencyError(value)) {
    return `${video ? "视频" : "图片"}并发限制：服务并发已满，系统正在轮询并自动重试`;
  }
  if (isAiVideoTransientNetworkError(value) || isAiVideoReferenceFetchError(value)) {
    return `${video ? "视频" : "图片"}网络超时：服务未按时响应，系统正在自动重试`;
  }
  if (/ACCEPTED_OR_UNKNOWN|retrySafe["']?\s*:\s*false/i.test(value)) {
    return "视频状态暂未确认，请稍后刷新";
  }
  if (/余额不足|额度不足|积分不足|insufficient|quota|credit|balance/i.test(value)) {
    return video ? "视频额度不足，任务未提交" : "图片额度不足，任务未提交";
  }
  if (/审核|敏感|违规|风控|content.?policy|moderation|blocked|rejected/i.test(value)) {
    return "素材未通过审核，请调整后重试";
  }
  if (/图片.*(读取|下载|访问).*失败|image.*(download|fetch|url)|public.?url|素材地址/i.test(value)) {
    return "参考图片读取失败，请重新上传";
  }
  if (/超时|timeout|timed out|deadline/i.test(value)) {
    return video ? "视频生成超时，请稍后重试" : "图片生成超时，请稍后重试";
  }
  if (/429|503|繁忙|拥塞|overload|rate.?limit|too many requests/i.test(value)) {
    return video ? "视频服务繁忙，请稍后重试" : "图片服务繁忙，请稍后重试";
  }
  if (/fetch failed|network|ECONN|ENOTFOUND|502|Bad Gateway|所有已启用的视频线路|厂商生成失败|服务暂时不可用/i.test(value)) {
    return video ? "视频服务暂时不可用，请稍后重试" : "图片服务暂时不可用，请稍后重试";
  }
  return video ? "视频未生成成功，请重试" : "图片未生成成功，请重试";
}

function aiVideoFailureDetail(message = "") {
  const value = String(message || "").trim()
    .replace(/^(?:大衍|高速线路(?:·多模态)?|视频厂商|DAYAN|FAST|H3_MULTI)\s*[：:]\s*/i, "");
  if (!value) return "未收到厂商返回的具体失败原因";
  if (/STRUCTURE_ANALYSIS_PAUSED|IMAGE_REQUEST_REJECTED|IMAGE_RESULT_UNKNOWN/.test(value)) {
    return value;
  }
  if (isAiVideoCopyrightError(value)) {
    return "请更换或移除受版权、商标或角色授权保护的图案后重新生成";
  }
  if (isAiVideoConcurrencyError(value)) {
    return "无需手动操作；页面会持续轮询，后端在获得并发名额后自动重提";
  }
  if (isAiVideoTransientNetworkError(value) || isAiVideoReferenceFetchError(value)) {
    return "无需手动操作；页面会持续轮询，后端会使用同一幂等键自动重试";
  }
  if (/余额不足|额度不足|积分不足|insufficient|quota|credit|balance|厂商拒绝请求|素材暂时无法读取/i.test(value)) {
    return value;
  }
  return friendlyAiVideoErrorHint(value);
}

function friendlyAiVideoErrorHint(message = "") {
  const value = String(message || "").trim();
  if (isAiVideoCopyrightError(value)) {
    return "请更换或移除受版权、商标或角授权保护的图案";
  }
  if (isAiVideoConcurrencyError(value)) {
    return "系统正在轮询并自动重试，无需手动重提";
  }
  if (isAiVideoTransientNetworkError(value) || isAiVideoReferenceFetchError(value)) {
    return "系统正在自动重试网络请求，无需手动重提";
  }
  if (/ACCEPTED_OR_UNKNOWN|retrySafe["']?\s*:\s*false/i.test(value)) {
    return "请先刷新状态，确认未生成后再重试，避免重复扣费";
  }
  if (/余额不足|额度不足|积分不足|insufficient|quota|credit|balance/i.test(value)) {
    return "补充额度后可重新生成";
  }
  if (/审核|敏感|违规|风控|content.?policy|moderation|blocked|rejected/i.test(value)) {
    return "检查素材或提示词后重新生成";
  }
  if (/图片.*(读取|下载|访问).*失败|image.*(download|fetch|url)|public.?url|素材地址/i.test(value)) {
    return "重新上传参考图片后再生成";
  }
  return "可点击下方按钮重新生成视频";
}

function aiVideoTaskStatusClass(status) {
  if (["IMAGE_GENERATING", "VIDEO_GENERATING"].includes(status)) return "generating";
  if (["IMAGE_READY", "VIDEO_READY"].includes(status)) return "done";
  if (status === "FAILED") return "reviewing";
  return "material";
}

function aiVideoAssetUrl(taskId, assetId, version = "", preview = false) {
  const suffix = preview ? "&preview=true" : "";
  return `/api/v1/ai-video-tasks/${encodeURIComponent(taskId)}/assets/${encodeURIComponent(assetId)}?v=${encodeURIComponent(version || "1")}&cache=20261008-local${suffix}`;
}

function reusableMediaKey(node) {
  const source = node?.getAttribute?.("src");
  return source ? `${node.tagName}:${source}` : "";
}

function htmlFragmentPreservingMedia(currentRoot, html) {
  const reusable = new Map();
  currentRoot?.querySelectorAll?.("img[src], video[src]").forEach(node => {
    const key = reusableMediaKey(node);
    if (!key) return;
    if (!reusable.has(key)) reusable.set(key, []);
    reusable.get(key).push(node);
  });
  const template = document.createElement("template");
  template.innerHTML = String(html || "").trim();
  template.content.querySelectorAll("img[src], video[src]").forEach(node => {
    const matches = reusable.get(reusableMediaKey(node));
    const existing = matches?.shift();
    if (existing) node.replaceWith(existing);
  });
  return template.content;
}

function replaceHtmlPreservingMedia(container, html) {
  IncrementalDOM.html(container, html);
}

function aiVideoSourceUrl(taskId, itemId, version = "") {
  return `/api/v1/ai-video-tasks/${encodeURIComponent(taskId)}/items/${encodeURIComponent(itemId)}/source?v=${encodeURIComponent(version || "1")}`;
}

function aiVideoDetailRenderSignature(detail) {
  if (!detail?.task) return "";
  const videoWaiting = detail.task.status === "VIDEO_GENERATING";
  return JSON.stringify({
    task: videoWaiting
      ? [detail.task.id, detail.task.status, detail.task.completedItems, detail.task.totalItems, detail.task.errorMessage, detail.videoSubmittedAt]
      : [detail.task.id, detail.task.status, detail.task.progress, detail.task.completedItems, detail.task.totalItems, detail.task.errorMessage, detail.task.updatedAt],
    items: (detail.items || []).map(item => {
      const itemVideoWaiting = ["VIDEO_SUBMITTING", "VIDEO_GENERATING"].includes(item.status);
      return itemVideoWaiting
        ? [item.id, item.status, item.errorMessage, item.videoPromptText, item.videoProviderTaskId, item.videoSubmittedAt, item.imageProgressJson]
        : [item.id, item.status, item.progress, item.errorMessage, item.videoPromptText, item.videoProviderTaskId, item.updatedAt, item.imageProgressJson];
    }),
    assets: (detail.assets || []).map(asset => [asset.id, asset.itemId, asset.assetType, asset.sizeBytes, asset.createdAt])
  });
}

function aiVideoItemRenderSignature(detail, item) {
  if (!item) return "";
  const videoWaiting = ["VIDEO_SUBMITTING", "VIDEO_GENERATING"].includes(item.status);
  const assets = (detail?.assets || [])
    .filter(asset => asset.itemId === item.id)
    .map(asset => [asset.id, asset.assetType, asset.sizeBytes, asset.createdAt]);
  const state = videoWaiting
    ? [item.id, item.name, item.sku, item.sourceFileName, item.status, item.errorMessage,
        item.videoPromptText, item.videoProviderTaskId, item.videoSubmittedAt]
    : [item.id, item.name, item.sku, item.sourceFileName, item.status, item.progress,
        item.errorMessage, item.videoPromptText, item.videoProviderTaskId,
        item.videoSubmittedAt, item.updatedAt];
  return JSON.stringify([state, assets, item.imageProgressJson]);
}

function changedAiVideoItemIds(previous, next) {
  if (!previous?.task || !next?.task || previous.task.id !== next.task.id) return null;
  if (previous.task.status !== next.task.status
      || Boolean(previous.task.directReferenceVideo) !== Boolean(next.task.directReferenceVideo)) return null;
  const previousItems = new Map((previous.items || []).map(item => [item.id, item]));
  const nextItems = next.items || [];
  if (previousItems.size !== nextItems.length || nextItems.some(item => !previousItems.has(item.id))) return null;
  return new Set(nextItems
    .filter(item => aiVideoItemRenderSignature(previous, previousItems.get(item.id)) !== aiVideoItemRenderSignature(next, item))
    .map(item => item.id));
}

function formatAiVideoElapsed(submittedAt) {
  const started = Date.parse(String(submittedAt || ""));
  if (!Number.isFinite(started)) return "刚刚提交";
  const elapsedSeconds = Math.max(0, Math.floor((Date.now() - started) / 1000));
  if (elapsedSeconds < 60) return `已等待 ${elapsedSeconds} 秒`;
  const elapsedMinutes = Math.floor(elapsedSeconds / 60);
  const seconds = elapsedSeconds % 60;
  if (elapsedMinutes < 60) return `已等待 ${elapsedMinutes} 分 ${String(seconds).padStart(2, "0")} 秒`;
  const hours = Math.floor(elapsedMinutes / 60);
  const minutes = elapsedMinutes % 60;
  return `已等待 ${hours} 小时 ${minutes} 分 ${String(seconds).padStart(2, "0")} 秒`;
}

function stopAiVideoElapsedTimer() {
  if (aiVideoElapsedTimer) clearInterval(aiVideoElapsedTimer);
  aiVideoElapsedTimer = null;
}

function refreshAiVideoElapsedLabels() {
  if (document.hidden) return;
  document.querySelectorAll("[data-video-submitted-at]").forEach(label => {
    label.textContent = formatAiVideoElapsed(label.dataset.videoSubmittedAt);
  });
}

function syncAiVideoElapsedTimer() {
  stopAiVideoElapsedTimer();
  if (aiVideoTaskDetail?.task?.status !== "VIDEO_GENERATING") return;
  refreshAiVideoElapsedLabels();
  aiVideoElapsedTimer = setInterval(refreshAiVideoElapsedLabels, 1000);
}

function filteredAiVideoTasks() {
  return aiVideoTasks.filter(task => fuzzySearchMatches([
    task.name, task.id, task.scriptId, task.scriptName, task.modelId, task.status,
    aiVideoTaskStatusNames[task.status], task.targetMarket, task.productCategory,
    task.resolution, aiVideoGenerationModeNames[aiVideoMode(task)]
  ], aiVideoTaskSearchQuery));
}

async function copyTextToClipboard(text) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (_error) {
      // Fall through to the compatibility path for embedded browsers.
    }
  }
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  textarea.select();
  try {
    return document.execCommand("copy");
  } finally {
    textarea.remove();
  }
}

function renderAiVideoTaskList() {
  const list = document.querySelector("#ai-video-task-list");
  if (!aiVideoTasks.length) {
    IncrementalDOM.html(list, `<div class="video-task-empty"><strong>还没有视频任务</strong><span>点击右上角新建，从 9 分镜脚本开始。</span></div>`);
    return [];
  }
  const filtered = filteredAiVideoTasks();
  if (!filtered.length) {
    IncrementalDOM.html(list, `<div class="video-task-empty"><strong>没有匹配任务</strong><span>换一个任务名、脚本名或 ID 片段。</span></div>`);
    return filtered;
  }
  IncrementalDOM.html(list, filtered.map(task => {
    const active = task.id === selectedAiVideoTaskId;
    const status = aiVideoTaskStatusNames[task.status] || task.status;
    const progress = task.status === "VIDEO_GENERATING"
      ? "≤40 分钟"
      : task.status === "IMAGE_GENERATING" ? "生图中" : active ? "当前" : status;
    const unit = task.directReferenceVideo ? "组视频" : "款商品";
    return `<button class="video-task-item ${active ? "active" : ""}" type="button" data-ai-video-task-id="${escapeHtml(task.id)}">
      <i class="${aiVideoTaskStatusClass(task.status)}"></i>
      <div><strong translate="no">${escapeHtml(task.name)}</strong><small>${escapeHtml(task.resolution || "720p")} · ${escapeHtml(aiVideoGenerationModeNames[aiVideoMode(task)] || aiVideoMode(task))} · ${Number(task.totalItems || 0)} ${unit} · ${escapeHtml(status)}</small></div>
      <em>${escapeHtml(progress)}</em>
    </button>`;
  }).join(""));
  return filtered;
}

function renderAiVideoEmpty(title, note) {
  document.querySelector("#garment-card-grid").innerHTML = `<div class="video-task-empty garment-empty"><strong>${escapeHtml(title)}</strong><span>${escapeHtml(note)}</span></div>`;
}

function syncAiVideoTaskRailHeight() {
  const page = document.querySelector("#ai-video-tasks-page");
  const rail = page?.querySelector(".batch-task-rail");
  const stage = page?.querySelector(".video-batch-stage");
  if (!page || page.hidden || !rail || !stage || window.innerWidth <= 900) {
    rail?.style.removeProperty("--ai-video-task-rail-height");
    return;
  }
  const stageBox = stage.getBoundingClientRect();
  if (stageBox.height <= 0) return;
  const availableHeight = Math.max(420, window.innerHeight - stageBox.top - 18);
  const matchedHeight = Math.min(stageBox.height, availableHeight);
  rail.style.setProperty("--ai-video-task-rail-height", `${Math.round(matchedHeight)}px`);
}

const aiVideoTaskStageResizeObserver = new ResizeObserver(() => syncAiVideoTaskRailHeight());
const aiVideoTaskStage = document.querySelector("#ai-video-tasks-page .video-batch-stage");
if (aiVideoTaskStage) aiVideoTaskStageResizeObserver.observe(aiVideoTaskStage);
window.addEventListener("resize", syncAiVideoTaskRailHeight);

const directReferenceRoleNames = {
  APPEARANCE: "商品 / 主体外观",
  DETAIL: "细节与材质",
  ANGLE: "侧面、背面或特定角度",
  CHARACTER: "人物身份与一致性",
  SCENE: "环境、布景与光线",
  ACTION: "动作、姿态与运镜",
  END_FRAME: "结尾画面与收尾构图",
  OTHER: "其他补充"
};

const replacementTargetNames = {
  PRIMARY: "主商品",
  LEFT: "左侧商品",
  CENTER: "中间商品",
  RIGHT: "右侧商品",
  HELD: "人物手持商品",
  TABLETOP: "桌面商品",
  PERSON: "整个人物",
  FACE: "人物脸部",
  HAIR: "人物发型",
  HEADWEAR: "帽子 / 头饰",
  OUTERWEAR: "人物外套",
  UPPER: "人物上装",
  LOWER: "人物下装",
  DRESS: "连衣裙 / 连体服",
  SHOES: "鞋子",
  BAG: "包袋",
  ACCESSORY: "配饰",
  BACKGROUND_OBJECT: "背景中的指定物品"
};

const replacementScopeTexts = {
  FULL: "完整替换外观、结构、颜色、材质、Logo、文字与图案",
  APPEARANCE: "只替换整体外观、结构、颜色和材质",
  GRAPHIC: "只替换 Logo、文字和图案",
  COLOR: "只替换颜色和材质，保留原对象结构",
  STRUCTURE_ONLY: "只替换版型和结构，保留原颜色、材质与图案",
  COLOR_ONLY: "只替换颜色，保留原结构、材质、Logo、文字与图案",
  MATERIAL_ONLY: "只替换材质和表面纹理，保留原结构、颜色与图案",
  LOGO_ONLY: "只替换 Logo，保留原文字、图案、颜色与结构",
  TEXT_ONLY: "只替换文字内容与排版，保留原 Logo、图案与结构",
  PATTERN_ONLY: "只替换印花或图案，保留原 Logo、文字、颜色与结构",
  DETAIL_ONLY: "只替换指定局部细节，其他区域全部保持不变"
};

function standardReplacementInstruction(target, scope) {
  if (!replacementTargetNames[target] || !replacementScopeTexts[scope]) return "";
  return `替换原视频中的${replacementTargetNames[target]}；${replacementScopeTexts[scope]}；其他人物、物品、背景、动作、镜头和节奏全部保持不变。`;
}

function replacementTemplateValue(instruction, values) {
  return Object.entries(values).find(([, text]) => String(instruction || "").includes(text))?.[0] || "";
}

function directReferenceGroupInstructionValue(references) {
  return references.map((reference, index) =>
    `参考图 ${index + 1}：${String(reference.referenceInstruction || "").trim()}`
  ).join("\n");
}

function parseDirectReferenceGroupInstructions(value, count) {
  const instructions = Array.from({ length: count }, () => "");
  const lines = String(value || "").split(/\r?\n/);
  let activeIndex = -1;
  let matchedNumberedLine = false;
  lines.forEach(line => {
    const match = line.match(/^\s*(?:参考图|图)\s*(\d+)\s*[：:]\s*(.*)$/);
    if (match) {
      const index = Number(match[1]) - 1;
      activeIndex = index >= 0 && index < count ? index : -1;
      if (activeIndex >= 0) {
        matchedNumberedLine = true;
        instructions[activeIndex] = match[2].trim();
      }
      return;
    }
    const continuation = line.trim();
    if (activeIndex >= 0 && continuation) {
      instructions[activeIndex] = `${instructions[activeIndex]} ${continuation}`.trim();
    }
  });
  if (!matchedNumberedLine) {
    const plainLines = lines.map(line => line.trim()).filter(Boolean);
    if (count === 1) instructions[0] = plainLines.join(" ");
    else if (plainLines.length === count) plainLines.forEach((line, index) => { instructions[index] = line; });
  }
  return instructions.map(instruction => instruction.slice(0, 500));
}

function syncDirectReferenceGroupInstructionsToTiles(card) {
  const editor = card?.querySelector("[data-reference-group-instruction]");
  const tiles = [...(card?.querySelectorAll("[data-reference-id]") || [])];
  if (!editor || !tiles.length) return;
  const instructions = parseDirectReferenceGroupInstructions(editor.value, tiles.length);
  tiles.forEach((tile, index) => {
    const input = tile.querySelector("[data-reference-instruction]");
    if (input) input.value = instructions[index] || "";
  });
}

function paintDirectReferenceGroupInstruction(card) {
  const editor = card?.querySelector("[data-reference-group-instruction]");
  const tiles = [...(card?.querySelectorAll("[data-reference-id]") || [])];
  if (!editor) return;
  editor.value = tiles.map((tile, index) =>
    `参考图 ${index + 1}：${tile.querySelector("[data-reference-instruction]")?.value?.trim() || ""}`
  ).join("\n");
  const counter = card.querySelector("[data-reference-group-count]");
  if (counter) counter.textContent = `${editor.value.length.toLocaleString()} 字`;
}

function directReferenceAssets(detail, itemId) {
  return (detail?.assets || [])
    .filter(asset => asset.itemId === itemId && asset.assetType === "VIDEO_REFERENCE_IMAGE")
    .sort((a, b) => Number(a.shotSequence || 99) - Number(b.shotSequence || 99));
}

function directReferencesConfigured(references, requireReplacementMapping = false) {
  return references.length > 0
    && references.every(reference => Boolean(reference.referenceRole))
    && (!requireReplacementMapping || references.every(reference => String(reference.referenceInstruction || "").trim()))
    && references.filter(reference => reference.primaryReference).length === 1;
}

function taskReferencesConfigured(detail, itemId) {
  return directReferencesConfigured(
    directReferenceAssets(detail, itemId),
    Boolean(detail?.task?.useScriptReferenceVideo)
  );
}

function directReferenceContractPreview(references) {
  if (!references.length) return "上传参考图后，系统会在这里生成编号与职责协议。";
  const lines = ["【参考图编号与职责协议】"];
  references.forEach((reference, index) => {
    const role = directReferenceRoleNames[reference.referenceRole] || "待指定用途";
    const primary = reference.primaryReference ? "主参考｜" : "辅助参考｜";
    const instruction = String(reference.referenceInstruction || "").trim();
    lines.push(`参考图${index + 1}：${primary}${role}。${instruction ? `用户说明：${instruction}。` : ""}`);
  });
  lines.push("编号仅对应图片发送顺序；按指定职责引用主体。编号、边框、水印、拼图布局及无关背景不得出现在成片中，参考图之间不得混用未指定的职责。");
  return lines.join("\n");
}

function renumberDirectReferenceTiles(card) {
  const tiles = [...card.querySelectorAll("[data-reference-id]")];
  tiles.forEach((tile, index) => {
    tile.querySelector("header > span").textContent = `参考图 ${index + 1}`;
    tile.querySelector(".direct-reference-preview b").textContent = String(index + 1);
    const up = tile.querySelector('[data-reference-move="up"]');
    const down = tile.querySelector('[data-reference-move="down"]');
    if (up) up.disabled = index === 0;
    if (down) down.disabled = index === tiles.length - 1;
  });
}

const directReferenceAutoSaveTimers = new Map();
const directReferenceAutoSaveChains = new Map();
const directReferenceAutoSaveVersions = new Map();
const directReferenceAutoSaveStates = new Map();

function directReferenceUpdatesFromCard(card) {
  syncDirectReferenceGroupInstructionsToTiles(card);
  return [...card.querySelectorAll("[data-reference-id]")].map((tile, index) => ({
    id: tile.dataset.referenceId,
    order: index + 1,
    role: tile.querySelector("[data-reference-role]")?.value || "",
    instruction: tile.querySelector("[data-reference-instruction]")?.value?.trim() || "",
    primary: Boolean(tile.querySelector("[data-reference-primary]")?.checked)
  }));
}

function directReferenceItemIsEditable(itemId) {
  const item = aiVideoTaskDetail?.items?.find(value => value.id === itemId);
  if (!item || !["READY", "VIDEO_FAILED"].includes(item.status)) return false;
  return !(aiVideoTaskDetail?.assets || []).some(asset => asset.itemId === itemId && asset.assetType === "VIDEO_RESULT");
}

function setDirectReferenceSaveStatus(card, state, label) {
  const itemId = card?.dataset.itemId;
  if (itemId) directReferenceAutoSaveStates.set(itemId, state);
  const status = card?.querySelector("[data-reference-save-status]");
  if (status) {
    status.className = `reference-autosave-status ${state}`;
    const text = status.querySelector("b");
    if (text) text.textContent = label;
  }
  syncDirectReferenceSubmissionAvailability();
}

function syncDirectReferenceSubmissionAvailability() {
  const detail = aiVideoTaskDetail;
  if (!detail?.task?.directReferenceVideo) return;
  const button = document.querySelector("#generate-shot-images");
  const itemIds = new Set(detail.items.map(item => item.id));
  const hasUnsavedChanges = [...directReferenceAutoSaveStates.entries()]
    .some(([itemId, state]) => itemIds.has(itemId) && ["pending", "saving", "failed"].includes(state));
  const generating = ["IMAGE_GENERATING", "VIDEO_GENERATING"].includes(detail.task.status);
  const allConfigured = detail.items.length > 0
    && detail.items.every(item => taskReferencesConfigured(detail, item.id));
  const allVideosReady = detail.items.length > 0
    && detail.items.every(item => detail.assets.some(asset => asset.itemId === item.id && asset.assetType === "VIDEO_RESULT"));
  button.disabled = generating || allVideosReady
    || !["READY", "FAILED", "IMAGE_READY", "VIDEO_READY"].includes(detail.task.status);
  button.classList.toggle("needs-reference-configuration", hasUnsavedChanges || !allConfigured);
}

function scheduleDirectReferenceAutoSave(card, delay = 500) {
  const itemId = card?.dataset.itemId;
  if (!itemId || !selectedAiVideoTaskId || !directReferenceItemIsEditable(itemId)) return;
  const version = (directReferenceAutoSaveVersions.get(itemId) || 0) + 1;
  directReferenceAutoSaveVersions.set(itemId, version);
  window.clearTimeout(directReferenceAutoSaveTimers.get(itemId));
  setDirectReferenceSaveStatus(card, "pending", "等待自动保存");
  const timer = window.setTimeout(() => {
    const payload = directReferenceUpdatesFromCard(card);
    const taskId = selectedAiVideoTaskId;
    const previous = directReferenceAutoSaveChains.get(itemId) || Promise.resolve();
    const next = previous.catch(() => undefined).then(async () => {
      if (selectedAiVideoTaskId !== taskId) return;
      setDirectReferenceSaveStatus(card, "saving", "正在保存…");
      const wasConfigured = taskReferencesConfigured(aiVideoTaskDetail, itemId);
      const result = await api(`/api/v1/ai-video-tasks/${encodeURIComponent(taskId)}/items/${encodeURIComponent(itemId)}/references`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      const saved = Array.isArray(result.data) ? result.data : [];
      if (aiVideoTaskDetail?.assets) {
        aiVideoTaskDetail.assets = aiVideoTaskDetail.assets
          .filter(asset => !(asset.itemId === itemId && asset.assetType === "VIDEO_REFERENCE_IMAGE"))
          .concat(saved);
      }
      const nowConfigured = directReferencesConfigured(saved, Boolean(aiVideoTaskDetail?.task?.useScriptReferenceVideo));
      if (directReferenceAutoSaveVersions.get(itemId) === version) {
        setDirectReferenceSaveStatus(card, "saved", "已自动保存");
      }
      if (wasConfigured !== nowConfigured) renderAiVideoTaskDetail();
    }).catch(error => {
      if (directReferenceAutoSaveVersions.get(itemId) === version) {
        setDirectReferenceSaveStatus(card, "failed", "保存失败，修改后重试");
        document.querySelector("#video-task-action-note").textContent = error.message;
      }
    });
    directReferenceAutoSaveChains.set(itemId, next);
  }, delay);
  directReferenceAutoSaveTimers.set(itemId, timer);
}

function renderAiVideoCardExpandButton(item, expanded) {
  const label = expanded ? "收起详情" : "展开详情";
  const copyAllowed = hasAnyPermission("video:task:manage");
  const copyBlocked = ["IMAGE_GENERATING", "VIDEO_GENERATING"].includes(aiVideoTaskDetail?.task?.status)
    || (aiVideoTaskDetail?.items?.length || 0) >= 30;
  return `${copyAllowed ? `<button class="garment-card-copy-button" type="button" data-item-copy ${copyBlocked ? 'disabled title="生成完成且不足 30 组时可复制"' : ''}>复制</button>` : ""}<button class="garment-card-expand" type="button" data-card-expand aria-expanded="${expanded}" aria-label="${label}：${escapeHtml(item.name || "未命名商品")}"><span>${label}</span><i aria-hidden="true">${expanded ? "↑" : "↓"}</i></button>`;
}

function renderDirectReferenceCard(detail, item, generating, canManageVideoTasks) {
  const task = detail.task;
  const references = directReferenceAssets(detail, item.id);
  const replacementMode = Boolean(task.useScriptReferenceVideo);
  const configured = directReferencesConfigured(references, replacementMode);
  const video = (detail.assets || []).find(asset => asset.itemId === item.id && asset.assetType === "VIDEO_RESULT");
  const providerTaskId = String(item.videoProviderTaskId || "");
  const itemAwaitingAcceptance = item.status === "VIDEO_SUBMITTING" && !providerTaskId;
  const itemVideoWaiting = ["VIDEO_SUBMITTING", "VIDEO_GENERATING"].includes(item.status);
  const configurationLocked = Boolean(video) || item.status === "VIDEO_READY";
  const expanded = expandedAiVideoItemIds.has(item.id);
  if (!expanded) {
    const stateClass = item.status === "VIDEO_FAILED" ? "failed" : itemVideoWaiting ? "running" : configured ? "ready" : "running";
    const stateLabel = video
      ? "视频已完成"
      : item.status === "VIDEO_FAILED" ? "视频生成失败"
        : itemAwaitingAcceptance ? "等待厂商受理" : itemVideoWaiting ? "后台生成中" : configured ? "已配置" : "待配置";
    const specifiedCount = references.filter(reference => reference.referenceRole).length;
    return `<article class="garment-card direct-reference-card selected ${itemVideoWaiting ? "is-video-processing" : ""} ${configurationLocked ? "is-reference-locked" : ""}" data-garment-card data-item-id="${escapeHtml(item.id)}">
      <header class="garment-card-head"><div class="garment-card-copy"><div><strong translate="no">${escapeHtml(item.name)}</strong><small>${references.length} / ${Number(task.referenceImageLimit || 9)} 张${replacementMode ? "对象图" : "参考图"}</small></div><div class="garment-card-tools"><span class="garment-state ${stateClass}">${escapeHtml(stateLabel)}</span>${renderAiVideoCardExpandButton(item, false)}</div></div></header>
      <div class="garment-card-collapsed">
        <div><span><small>素材</small><strong>${references.length} 张</strong></span><span><small>职责</small><strong>${specifiedCount} / ${references.length || 0}</strong></span><span><small>主参考</small><strong>${references.some(reference => reference.primaryReference) ? "已指定" : "未指定"}</strong></span><span><small>成片</small><strong>${video ? "已完成" : itemVideoWaiting ? "生成中" : "待生成"}</strong></span></div>
        <p>${configurationLocked ? "该组视频已完成，参考图配置已锁定。" : replacementMode ? "展开后指定每张图要替换的原视频对象。" : "展开后指定参考图职责、主参考与视频提示词。"}</p>
      </div>
    </article>`;
  }
  const videoUrl = video ? aiVideoAssetUrl(task.id, video.id, video.createdAt) : "";
  const flipped = flippedAiVideoPromptItems.has(item.id);
  const editingPrompt = aiVideoPromptDrafts.has(item.id);
  const canEditReferences = canManageVideoTasks && !generating && !configurationLocked
    && ["READY", "VIDEO_FAILED"].includes(item.status);
  const activePromptEditing = editingPrompt && canEditReferences;
  const promptValue = activePromptEditing ? aiVideoPromptDrafts.get(item.id) : item.videoPromptText || "";
  const disabled = canEditReferences ? "" : "disabled";
  const canDeleteReferences = canEditReferences;
  const roleOptions = Object.entries(directReferenceRoleNames)
    .map(([value, label]) => `<option value="${value}">${escapeHtml(label)}</option>`).join("");
  const replacementTargetOptions = Object.entries(replacementTargetNames)
    .map(([value, label]) => `<option value="${value}">${escapeHtml(label)}</option>`).join("");
  const replacementScopeOptions = Object.entries(replacementScopeTexts)
    .map(([value, label]) => `<option value="${value}">${escapeHtml(label)}</option>`).join("");
  const track = references.map((reference, index) => {
    const url = aiVideoAssetUrl(task.id, reference.id, reference.createdAt, true);
    const fullUrl = aiVideoAssetUrl(task.id, reference.id, reference.createdAt);
    const instruction = String(reference.referenceInstruction || "");
    const replacementTarget = replacementTemplateValue(instruction, replacementTargetNames);
    const replacementScope = replacementTemplateValue(instruction, replacementScopeTexts);
    return `<article class="direct-reference-tile ${reference.primaryReference ? "is-primary" : ""} ${reference.referenceRole ? "is-specified" : "is-pending"}" data-reference-id="${escapeHtml(reference.id)}">
      <header><span>参考图 ${index + 1}</span><div><button type="button" data-reference-move="up" ${index === 0 || !canEditReferences ? "disabled" : ""} aria-label="向前移动参考图 ${index + 1}">←</button><button type="button" data-reference-move="down" ${index === references.length - 1 || !canEditReferences ? "disabled" : ""} aria-label="向后移动参考图 ${index + 1}">→</button>${canDeleteReferences ? `<button class="direct-reference-delete" type="button" data-reference-delete aria-label="删除参考图 ${index + 1}" title="删除这张参考图">×</button>` : ""}</div></header>
      <button class="direct-reference-preview" type="button" data-asset-preview="参考图 ${index + 1}" aria-label="放大查看参考图 ${index + 1}"><img src="${url}" data-full-src="${fullUrl}" alt="参考图 ${index + 1}" loading="lazy"><b aria-hidden="true">${index + 1}</b></button>
      <label class="direct-reference-primary"><input type="radio" name="primary-reference-${escapeHtml(item.id)}" data-reference-primary ${reference.primaryReference ? "checked" : ""} ${disabled}><span>设为主参考</span></label>
      <label><span>这张图负责</span><select data-reference-role ${disabled}><option value="">由用户指定…</option>${reference.referenceRole ? roleOptions.replace(`value="${reference.referenceRole}"`, `value="${reference.referenceRole}" selected`) : roleOptions}</select></label>
      ${replacementMode ? `<label><span>替换原视频对象</span><select data-replacement-target ${disabled}><option value="">请选择对象…</option>${replacementTarget ? replacementTargetOptions.replace(`value="${replacementTarget}"`, `value="${replacementTarget}" selected`) : replacementTargetOptions}</select></label><label><span>替换范围</span><select data-replacement-scope ${disabled}><option value="">请选择范围…</option>${replacementScope ? replacementScopeOptions.replace(`value="${replacementScope}"`, `value="${replacementScope}" selected`) : replacementScopeOptions}</select></label>` : ""}
      <input type="hidden" data-reference-instruction value="${escapeHtml(reference.referenceInstruction || "")}">
    </article>`;
  }).join("");
  const groupInstruction = directReferenceGroupInstructionValue(references);
  const instructionEditor = references.length ? `<section class="direct-reference-language">
    <header><div><span>${replacementMode ? "全部参考图替换说明" : "全部参考图补充说明"}</span><strong>${replacementMode ? "整组提交给视频模型的替换语言" : "集中说明每张图可以参考什么"}</strong></div><em>${replacementMode ? "必填" : "可选"}</em></header>
    <textarea data-reference-group-instruction maxlength="5000" spellcheck="false" placeholder="参考图 1：替换原视频中的主商品，其他内容保持不变。&#10;参考图 2：只替换人物手持商品，保留原动作和镜头。" ${disabled}>${escapeHtml(groupInstruction)}</textarea>
    <footer><span>每张图以“参考图 N：”开头；选择上方对象和范围后会自动生成对应文字。</span><em data-reference-group-count>${groupInstruction.length.toLocaleString()} 字</em></footer>
  </section>` : "";
  const providerButton = providerTaskId
    ? `<button class="copy-provider-task-button" type="button" data-provider-task-copy="${escapeHtml(providerTaskId)}" title="大衍任务 ID：${escapeHtml(providerTaskId)}" aria-label="一键复制大衍任务 ID"><i aria-hidden="true">⧉</i><span>复制 ID</span></button>` : "";
  return `<article class="garment-card direct-reference-card selected is-expanded ${itemVideoWaiting ? "is-video-processing" : ""} ${configurationLocked ? "is-reference-locked" : ""} ${flipped ? "is-prompt-flipped" : ""}" data-garment-card data-item-id="${escapeHtml(item.id)}">
    <div class="garment-card-flip">
      <div class="garment-card-face garment-card-front" aria-hidden="${flipped}">
        <header class="garment-card-head"><div class="garment-card-copy"><div><strong translate="no">${escapeHtml(item.name)}</strong><small>${references.length} / ${Number(task.referenceImageLimit || 9)} 张${replacementMode ? "对象图" : "参考图"}</small></div><div class="garment-card-tools"><button class="garment-prompt-flip" type="button" data-card-flip><i>↻</i><span>提示词</span></button><span class="garment-state ${configured ? "ready" : "running"}">${configured ? "已指定" : "待指定"}</span>${renderAiVideoCardExpandButton(item, true)}</div></div></header>
        <section class="direct-reference-workspace">
          <header><div><span>${replacementMode ? "替换对象轨道" : "参考图轨道"}</span><strong>${replacementMode ? "逐张指定原视频中的替换目标" : "编号就是视频模型的引用顺序"}</strong></div><em>${configurationLocked ? "🔒 已锁定" : configured ? "配置完整" : "请指定每张图"}</em></header>
          <div class="direct-reference-track">${track || `<div class="direct-reference-empty">上传 1–9 张${replacementMode ? "对象图" : "参考图"}开始配置</div>`}</div>
          ${instructionEditor}
          <footer><span>${configurationLocked ? "视频已完成，参考图配置已锁定。" : "编号只用于模型引用，不会写进图片。"}</span><div class="direct-reference-card-actions">${references.length ? `<span class="reference-autosave-status ${configurationLocked ? "locked" : "saved"}" data-reference-save-status><i></i><b>${configurationLocked ? "已锁定" : "已自动保存"}</b></span>` : ""}${canEditReferences && references.length < Number(task.referenceImageLimit || 9) ? `<label class="append-reference-button"><input type="file" accept="image/*" multiple data-reference-append><span>＋ 追加参考图</span></label>` : ""}</div></footer>
        </section>
        <section class="garment-video-output ${video ? "completed" : item.status === "VIDEO_FAILED" ? "failed" : itemVideoWaiting ? "processing" : "waiting"}" data-video-output><header><strong>AI 视频生成结果</strong><div class="garment-video-status-actions"><span>${video ? "已完成" : item.status === "VIDEO_FAILED" ? "视频未生成" : itemAwaitingAcceptance ? "等待受理" : itemVideoWaiting ? "生成中" : "等待生成"}</span>${providerButton}</div></header>${video ? `<button class="garment-video-canvas" type="button" data-asset-preview="AI 视频成片" aria-label="放大播放 AI 视频成片"><video src="${videoUrl}" preload="metadata" playsinline></video><i>▶</i><div><strong>点击预览成片</strong><small>${video.temporaryDownload ? "厂商临时链接，请及时下载" : "已保存到平台，可下载"}</small></div></button>` : `<button class="garment-video-canvas" type="button" disabled>${itemVideoWaiting ? `<img class="video-waiting-cat" src="/assets/waiting-cat.gif" alt="" aria-hidden="true">` : ""}<i aria-hidden="true">${item.status === "VIDEO_FAILED" ? "!" : itemVideoWaiting ? "" : "▶"}</i><div><strong>${item.status === "VIDEO_FAILED" ? escapeHtml(friendlyAiVideoError(item.errorMessage, "video")) : itemAwaitingAcceptance ? "正在提交，等待厂商受理" : itemVideoWaiting ? "视频正在后台生成" : "等待提交参考图生成视频"}</strong><small>${item.status === "VIDEO_FAILED" ? escapeHtml(aiVideoFailureDetail(item.errorMessage)) : itemAwaitingAcceptance ? "厂商返回任务 ID 后才会进入视频生成" : itemVideoWaiting ? "通常 20–40 分钟完成，完成后自动回显" : "配置完成后直接调用视频模型，不经过图片模型"}</small></div><em${!itemAwaitingAcceptance && itemVideoWaiting && item.videoSubmittedAt ? ` class="video-elapsed-time" data-video-submitted-at="${escapeHtml(item.videoSubmittedAt)}"` : ""}>${item.status === "VIDEO_FAILED" ? "未生成" : itemAwaitingAcceptance ? "待受理" : itemVideoWaiting ? formatAiVideoElapsed(item.videoSubmittedAt) : "--:--"}</em></button>`}</section>
      </div>
      <section class="garment-card-face garment-card-back" aria-hidden="${!flipped}">
        <header class="video-prompt-head"><div><strong translate="no">${escapeHtml(item.name)}</strong><small>最终发送：${replacementMode ? "对象替换映射 → 原视频复刻提示词" : "参考图职责协议 → 视频执行提示词"}</small></div><div class="video-prompt-head-actions">${renderAiVideoCardExpandButton(item, true)}<button type="button" data-card-flip ${activePromptEditing ? "disabled" : ""}><i>↻</i> 返回参考图</button></div></header>
        <div class="video-prompt-body"><div class="direct-reference-contract"><span>系统自动附加·可预览</span><pre>${escapeHtml(directReferenceContractPreview(references))}</pre></div><div class="video-prompt-label"><span>视频执行提示词</span><em>${String(promptValue).length.toLocaleString()} / 20,000</em></div><textarea class="video-prompt-editor" data-video-prompt-editor maxlength="20000" spellcheck="false" ${activePromptEditing ? "" : "readonly"}>${escapeHtml(promptValue)}</textarea></div>
        <footer class="video-prompt-actions"><span>${configurationLocked ? "视频已完成，执行提示词仅供查看" : activePromptEditing ? "正在编辑视频动作与分镜提示" : "提交时会自动把上方职责协议放在提示词最前面"}</span><div>${!canEditReferences ? "" : activePromptEditing ? `<button class="quiet-prompt-button" type="button" data-prompt-cancel>取消</button><button class="save-prompt-button" type="button" data-prompt-save>保存修改</button>` : `<button class="edit-prompt-button" type="button" data-prompt-edit>编辑提示词</button>`}</div></footer>
      </section>
    </div>
  </article>`;
}

function renderAiVideoTaskDetail(options = {}) {
  const detail = aiVideoTaskDetail;
  const changedItemIds = options.changedItemIds instanceof Set ? options.changedItemIds : null;
  const startButton = document.querySelector("#generate-shot-images");
  const retryFailedButton = document.querySelector("#retry-failed-videos");
  const upload = document.querySelector("#batch-garment-upload");
  const batchDownloadButton = document.querySelector("#batch-download-videos");
  const canManageVideoTasks = hasAnyPermission("video:task:manage");
  startButton.hidden = !canManageVideoTasks;
  retryFailedButton.hidden = true;
  if (!detail) {
    stopAiVideoElapsedTimer();
    document.querySelector("#ai-video-task-stage").textContent = "等待选择任务";
    document.querySelector("#ai-video-task-title").setAttribute("translate", (false) ? "no" : "yes");
  document.querySelector("#ai-video-task-title").textContent = "从左侧选择任务";
    document.querySelector("#ai-video-task-script").textContent = "新建任务后，脚本版本会冻结在本轮生产中。";
    const emptyTaskProgress = document.querySelector("#ai-video-task-progress");
    emptyTaskProgress.textContent = "0%";
    emptyTaskProgress.classList.remove("running", "video-waiting", "image-preparing");
    document.querySelector("#ai-video-task-market").textContent = "未设置目标市场";
    document.querySelector("#selected-garment-count").textContent = "0";
    document.querySelector("#selected-garment-unit").textContent = "款商品";
    document.querySelector("#batch-upload-label-text").textContent = "＋ 批量上传商品";
    document.querySelector("#batch-group-title").textContent = "还没有衣服素材";
    document.querySelector("#ai-video-task-summary").textContent = "尚未选择任务";
    document.querySelector("#video-task-action-note").textContent = "先新建任务并上传衣服素材。";
    startButton.disabled = true;
    startButton.classList.remove("is-background-processing");
    upload.disabled = true;
    batchDownloadButton.disabled = true;
    batchDownloadButton.textContent = "↓ 批量下载视频";
    renderAiVideoEmpty("等待任务", "选择一个任务后查看衣服素材和生成结果。");
    return;
  }

  const task = detail.task;
  const mode = aiVideoMode(task);
  const isModelWear = mode === "MODEL_WEAR";
  const directReferenceVideo = Boolean(task.directReferenceVideo);
  const primaryAssetType = aiVideoPrimaryAssetType(task);
  const primaryAssetName = aiVideoPrimaryAssetName(task);
  const imageGenerating = task.status === "IMAGE_GENERATING";
  const videoGenerating = task.status === "VIDEO_GENERATING";
  const generating = imageGenerating || videoGenerating;
  const awaitingAcceptanceItems = detail.items.filter(item => item.status === "VIDEO_SUBMITTING" && !String(item.videoProviderTaskId || ""));
  const acceptedGeneratingItems = detail.items.filter(item => item.status === "VIDEO_GENERATING" && String(item.videoProviderTaskId || ""));
  const waitingForProviderAcceptance = videoGenerating && awaitingAcceptanceItems.length > 0 && acceptedGeneratingItems.length === 0;
  const mixedVideoSubmissionState = videoGenerating && awaitingAcceptanceItems.length > 0 && acceptedGeneratingItems.length > 0;
  const statusName = waitingForProviderAcceptance
    ? "等待厂商受理"
    : mixedVideoSubmissionState ? "提交中 · 视频生成中" : aiVideoTaskStatusNames[task.status] || task.status;
  const assetsByItem = new Map();
  detail.assets.forEach(asset => {
    if (!assetsByItem.has(asset.itemId)) assetsByItem.set(asset.itemId, []);
    assetsByItem.get(asset.itemId).push(asset);
  });
  const allImagesReady = detail.items.length > 0 && detail.items.every(item => {
    if (directReferenceVideo) return taskReferencesConfigured(detail, item.id);
    const types = new Set((assetsByItem.get(item.id) || []).map(asset => asset.assetType));
    return types.has(primaryAssetType) && types.has("STORYBOARD_GRID") && types.has("STORYBOARD_FIRST_FRAME");
  });
  document.querySelector("#ai-video-task-stage").textContent = statusName;
  document.querySelector("#ai-video-task-title").setAttribute("translate", (task.name) ? "no" : "yes");
  document.querySelector("#ai-video-task-title").textContent = task.name;
  const musicLabel = task.musicMode === "UPLOADED" ? `已上传配乐 · ${task.musicFileName || "音乐"}` : "AI 轻快旋律 BGM";
  const routeLabel = task.videoRoute === "SPEED" ? "低质量 · 高速度" : "高质量 · 低速度";
  document.querySelector("#ai-video-task-script").innerHTML = `<span class="task-mode-badge resolution">${escapeHtml(task.resolution || "720p")}</span><span class="task-mode-badge">${routeLabel}</span>${directReferenceVideo ? `<span class="task-mode-badge direct">${task.useScriptReferenceVideo ? "原视频替换" : "参考图直提"}</span>` : `<span class="task-mode-badge">${escapeHtml(aiVideoGenerationModeNames[mode] || mode)}</span><span class="task-mode-badge background">${escapeHtml(aiVideoBackgroundModeNames[task.backgroundMode || "SCRIPT_ORIGINAL"])}</span>`}<span class="task-mode-badge music">${escapeHtml(musicLabel)}</span> 使用脚本：${escapeHtml(task.scriptName)} / v${Number(task.scriptVersion || 1)} <b>已冻结</b>`;
  const taskProgress = document.querySelector("#ai-video-task-progress");
  taskProgress.textContent = waitingForProviderAcceptance
    ? "等待受理"
    : mixedVideoSubmissionState ? "提交中" : videoGenerating ? formatAiVideoElapsed(detail.videoSubmittedAt) : imageGenerating ? "准备图片中" : `${Number(task.progress || 0)}%`;
  if (videoGenerating && !waitingForProviderAcceptance && !mixedVideoSubmissionState && detail.videoSubmittedAt) taskProgress.dataset.videoSubmittedAt = detail.videoSubmittedAt;
  else delete taskProgress.dataset.videoSubmittedAt;
  taskProgress.classList.toggle("running", generating);
  taskProgress.classList.toggle("video-waiting", videoGenerating);
  taskProgress.classList.toggle("image-preparing", imageGenerating);
  document.querySelector("#ai-video-task-market").textContent = task.targetMarket || "未设置目标市场";
  const directReferenceCount = directReferenceVideo ? detail.items.reduce((total, item) => total + directReferenceAssets(detail, item.id).length, 0) : 0;
  const configuredGroupCount = directReferenceVideo ? detail.items.filter(item => taskReferencesConfigured(detail, item.id)).length : 0;
  document.querySelector("#selected-garment-count").textContent = String(detail.items.length);
  document.querySelector("#selected-garment-unit").textContent = directReferenceVideo ? "组视频" : "款商品";
  document.querySelector("#batch-group-title").textContent = directReferenceVideo ? `${task.name} · ${detail.items.length} 组 / ${directReferenceCount} 张参考图` : `${task.name} · ${detail.items.length} 款商品`;
  document.querySelector("#ai-video-task-summary").textContent = directReferenceVideo ? `${configuredGroupCount} / ${detail.items.length} 组已配置 · ${statusName}` : `${detail.items.length} 款商品 · ${statusName}`;
  document.querySelector("#batch-upload-label-text").textContent = directReferenceVideo ? "＋ 新建参考图组" : "＋ 批量上传商品";
  upload.disabled = !canManageVideoTasks || generating || !["DRAFT", "READY"].includes(task.status)
    || directReferenceVideo && detail.items.length >= 30;
  document.querySelector("#batch-garment-upload-label").classList.toggle("disabled", upload.disabled);
  const allVideosReady = detail.items.length > 0 && detail.items.every(item => (assetsByItem.get(item.id) || []).some(asset => asset.assetType === "VIDEO_RESULT"));
  const failedVideoItems = detail.items.filter(item => item.status === "VIDEO_FAILED");
  const completedVideoCount = detail.items.filter(item => (assetsByItem.get(item.id) || []).some(asset => asset.assetType === "VIDEO_RESULT")).length;
  batchDownloadButton.disabled = completedVideoCount === 0;
  batchDownloadButton.textContent = completedVideoCount === 1 ? "↓ 下载视频"
    : completedVideoCount > 1 ? `↓ 打包下载视频（${completedVideoCount}）`
      : "↓ 暂无可下载视频";
  const hasFailedVideos = failedVideoItems.length > 0;
  retryFailedButton.hidden = !canManageVideoTasks || !hasFailedVideos;
  retryFailedButton.disabled = generating;
  retryFailedButton.textContent = generating
    ? `自动重试失败项（${failedVideoItems.length}）`
    : `↻ 重试 ${failedVideoItems.length} 个失败任务`;
  startButton.hidden = !canManageVideoTasks || hasFailedVideos;
  const missingRequiredReferences = directReferenceVideo && !allImagesReady;
  startButton.disabled = generating || allVideosReady || detail.items.length === 0
    || !["READY", "FAILED", "IMAGE_READY", "VIDEO_READY"].includes(task.status);
  startButton.classList.toggle("needs-reference-configuration", missingRequiredReferences);
  startButton.classList.toggle("is-background-processing", videoGenerating);
  startButton.textContent = imageGenerating ? "正在准备视频素材"
    : videoGenerating ? "正在后台生成视频"
      : allVideosReady ? "视频已全部完成"
        : task.status === "FAILED" ? "重试视频生成" : directReferenceVideo ? (task.useScriptReferenceVideo ? "提交对象替换视频" : "提交参考图生成视频") : "开始批量生成视频";
  const failedVideoItem = failedVideoItems[0];
  if (hasFailedVideos) {
    document.querySelector("#ai-video-task-summary").textContent = `${completedVideoCount} 款已完成 · ${failedVideoItems.length} 款未完成`;
  }
  document.querySelector("#video-task-action-note").textContent = failedVideoItem
    ? generating
      ? `${failedVideoItems.length} 款提交遇到临时故障，系统会按 30 秒、90 秒、5 分钟自动重试；其他视频继续生成。`
      : `${failedVideoItems.length} 款视频未完成；临时厂商故障会自动重试，也可立即手动重试，已完成的 ${completedVideoCount} 款不会重复生成。`
    : task.errorMessage && !allImagesReady && !directReferenceVideo
    ? friendlyAiVideoError(task.errorMessage, "image")
    : videoGenerating
      ? `视频正在后台生成；并发数 ${Number(detail.activeVideoSubmissions || 0)} / ${Number(detail.videoSubmissionLimit || 500)}，超出上限的请求会自动排队补位。`
      : imageGenerating
        ? `正在准备首帧和九宫格：已完成 ${Number(task.completedItems || 0)} / ${Number(task.totalItems || detail.items.length)} 款；全部完成后自动提交视频。`
      : directReferenceVideo
        ? allImagesReady ? "参考图职责已指定；提交后直接调用视频模型，不调用图片模型。" : "请为每张图指定用途，并选择且只选择一张主参考图。"
        : `点击生成视频后，系统会自动准备${primaryAssetName}、首帧和九宫格，再提交视频模型。`;

  if (!detail.items.length) {
    syncAiVideoElapsedTimer();
    renderAiVideoEmpty(directReferenceVideo ? "新建第一组参考图" : "上传商品素材", directReferenceVideo ? "一次选择 1–9 张图片创建一组视频；可继续新建，单任务最多 30 组。" : "支持 JPG、PNG、WebP；单个任务最多 30 款，每张不超过 20MB。");
    return;
  }

  const currentItemIds = new Set(detail.items.map(item => item.id));
  expandedAiVideoItemIds.forEach(itemId => {
    if (!currentItemIds.has(itemId)) expandedAiVideoItemIds.delete(itemId);
  });
  const garmentCardsByItemId = new Map(detail.items.map(item => {
    if (directReferenceVideo) return [item.id, renderDirectReferenceCard(detail, item, generating, canManageVideoTasks)];
    const assets = assetsByItem.get(item.id) || [];
    const model = assets.find(asset => asset.assetType === primaryAssetType);
    const storyboard = assets.find(asset => asset.assetType === "STORYBOARD_GRID");
    const firstFrame = assets.find(asset => asset.assetType === "STORYBOARD_FIRST_FRAME");
    const video = assets.find(asset => asset.assetType === "VIDEO_RESULT");
    // The task becomes IMAGE_GENERATING before the worker claims each item. Keep the
    // queued preparation phase visibly alive instead of showing a static empty card.
    const pendingImageDownload = Boolean(item.imageDownloadPending);
    const imagePresentation = aiVideoImagePresentation(task, item, model, storyboard);
    const itemImagePreparing = imagePresentation.active;
    const modelState = imagePresentation.modelState;
    const shotState = imagePresentation.shotState;
    const itemState = !isModelWear && item.status === "GENERATING_MODEL"
      ? "生成场景主图"
      : aiVideoItemStatusNames[item.status] || item.status;
    const providerTaskId = String(item.videoProviderTaskId || "");
    const itemAwaitingAcceptance = item.status === "VIDEO_SUBMITTING" && !providerTaskId;
    const itemVideoRetrying = item.status === "VIDEO_FAILED"
      && (isAiVideoTransientNetworkError(item.errorMessage) || isAiVideoReferenceFetchError(item.errorMessage) || isAiVideoConcurrencyError(item.errorMessage));
    const itemVideoWaiting = ["VIDEO_SUBMITTING", "VIDEO_GENERATING"].includes(item.status) || itemVideoRetrying;
    const itemStateLabel = itemAwaitingAcceptance ? "等待厂商受理" : itemVideoRetrying ? "自动重试中" : itemVideoWaiting ? "后台生成中"
      : imagePresentation.retrying ? "图片等待自动重试"
      : itemImagePreparing ? (model ? `九宫格 · ${imagePresentation.storyboardLabel}` : `主图 · ${imagePresentation.modelLabel}`)
      : itemState;
    const source = aiVideoSourceUrl(task.id, item.id, item.createdAt);
    const expanded = expandedAiVideoItemIds.has(item.id);
    if (!expanded) {
      const stateClass = ["FAILED", "VIDEO_FAILED"].includes(item.status) && !pendingImageDownload ? "failed" : generating || pendingImageDownload ? "running" : "ready";
      const collapsedNote = item.errorMessage && !pendingImageDownload && !(model && storyboard)
        ? friendlyAiVideoError(item.errorMessage, item.status === "VIDEO_FAILED" ? "video" : "image")
        : video ? (video.temporaryDownload ? "厂商临时链接，请及时下载。" : "成片已保存到平台，展开后可预览和下载。")
          : itemVideoWaiting ? "视频正在后台生成，展开卡片可查看等待时间和厂商任务。"
            : itemImagePreparing ? `正在准备${primaryAssetName}、首帧和九宫格。` : "展开后查看原图、AI 主图、九宫格和视频提示词。";
      return [item.id, `<article class="garment-card selected ${itemImagePreparing ? "is-image-processing" : ""} ${itemVideoWaiting ? "is-video-processing" : ""}" data-garment-card data-item-id="${escapeHtml(item.id)}">
        <header class="garment-card-head"><div class="garment-card-copy"><div><strong translate="no">${escapeHtml(item.name)}</strong><small translate="no">${escapeHtml(item.sku || item.sourceFileName)}</small></div><div class="garment-card-tools"><span class="garment-state ${stateClass}">${escapeHtml(itemStateLabel)}</span>${renderAiVideoCardExpandButton(item, false)}</div></div></header>
        <button class="garment-card-source-preview real-asset" type="button" data-asset-preview="原始商品图" title="点击放大商品原图" aria-label="放大查看 ${escapeHtml(item.name)} 原始商品图"><img src="${source}" alt="${escapeHtml(item.name)} 原始商品图" loading="lazy"><span>商品原图</span></button>
        <div class="garment-card-collapsed">
          <div><span><small>商品原图</small><strong>已上传</strong></span><span><small>${escapeHtml(primaryAssetName)}</small><strong>${model ? "已完成" : itemImagePreparing ? "生成中" : "待生成"}</strong></span><span><small>4K 九宫格</small><strong>${storyboard ? "已完成" : itemImagePreparing ? "生成中" : "待生成"}</strong></span><span><small>AI 成片</small><strong>${video ? "已完成" : itemVideoWaiting ? "生成中" : "待生成"}</strong></span></div>
          <p>${escapeHtml(collapsedNote)}</p>
        </div>
      </article>`];
    }
    const modelUrl = model ? aiVideoAssetUrl(task.id, model.id, model.createdAt, true) : "";
    const modelFullUrl = model ? aiVideoAssetUrl(task.id, model.id, model.createdAt) : "";
    const storyboardUrl = storyboard ? aiVideoAssetUrl(task.id, storyboard.id, storyboard.createdAt, true) : "";
    const storyboardFullUrl = storyboard ? aiVideoAssetUrl(task.id, storyboard.id, storyboard.createdAt) : "";
    const firstFrameUrl = firstFrame ? aiVideoAssetUrl(task.id, firstFrame.id, firstFrame.createdAt) : "";
    const videoUrl = video ? aiVideoAssetUrl(task.id, video.id, video.createdAt) : "";
    const modelStatusText = imagePresentation.modelLabel;
    const storyboardStatusText = imagePresentation.storyboardLabel;
    const modelCanvas = model
      ? `<img src="${modelUrl}" data-full-src="${modelFullUrl}" alt="${escapeHtml(item.name)} ${escapeHtml(primaryAssetName)}" loading="lazy">`
      : aiVideoImagePlaceholder(modelStatusText, imagePresentation.retrying ? imagePresentation.hint : "主图完成后自动展示");
    const storyboardTiles = storyboard
      ? `<img class="storyboard-grid-image" src="${storyboardUrl}" data-full-src="${storyboardFullUrl}" alt="完整 4K 九宫格分镜图" loading="lazy">`
      : aiVideoImagePlaceholder(storyboardStatusText, imagePresentation.hint);
    const error = item.errorMessage && !(model && storyboard) ? `<div class="garment-error"><strong>${escapeHtml(friendlyAiVideoError(item.errorMessage, "image"))}</strong><p>${escapeHtml(aiVideoFailureDetail(item.errorMessage))}</p></div>` : "";
    const flipped = flippedAiVideoPromptItems.has(item.id);
    const editingPrompt = aiVideoPromptDrafts.has(item.id);
    const promptValue = editingPrompt ? aiVideoPromptDrafts.get(item.id) : item.videoPromptText || "";
    const promptCount = String(promptValue || "").length;
    const providerTaskCopyButton = providerTaskId
      ? `<button class="copy-provider-task-button" type="button" data-provider-task-copy="${escapeHtml(providerTaskId)}" title="大衍任务 ID：${escapeHtml(providerTaskId)}" aria-label="一键复制大衍任务 ID"><i aria-hidden="true">⧉</i><span>复制 ID</span></button>`
      : "";
    return [item.id, `<article class="garment-card selected is-expanded ${itemImagePreparing ? "is-image-processing" : ""} ${itemVideoWaiting ? "is-video-processing" : ""} ${flipped ? "is-prompt-flipped" : ""}" data-garment-card data-item-id="${escapeHtml(item.id)}">
      <div class="garment-card-flip">
        <div class="garment-card-face garment-card-front" aria-hidden="${flipped}">
          <header class="garment-card-head">
            <div class="garment-card-copy"><div><strong translate="no">${escapeHtml(item.name)}</strong><small translate="no">${escapeHtml(item.sku || item.sourceFileName)}</small></div><div class="garment-card-tools"><button class="garment-prompt-flip" type="button" data-card-flip aria-label="查看本款视频提示词"><i>↻</i><span>提示词</span></button><span class="garment-state ${imagePresentation.retrying ? "running" : ["FAILED", "VIDEO_FAILED"].includes(item.status) ? "failed" : itemImagePreparing || itemVideoWaiting ? "running" : "ready"}">${escapeHtml(itemStateLabel)}</span>${renderAiVideoCardExpandButton(item, true)}</div></div>
          </header>
          <div class="garment-download-rail" aria-label="下载生成素材">
            <button type="button" data-download-asset data-url="${firstFrameUrl}" data-filename="${escapeHtml(item.name)}-分镜首帧" ${firstFrame ? "" : "disabled"}><i>↓</i><span>${firstFrame ? "下载首帧（第 01 格）" : "首帧裁切中"}</span></button>
            <button type="button" data-download-asset data-url="${storyboardFullUrl}" data-filename="${escapeHtml(item.name)}-4K分镜九宫格" ${storyboard ? "" : "disabled"}><i>↓</i><span>${storyboard ? "下载九宫格" : "九宫格生成中"}</span></button>
          </div>
          <div class="garment-portrait-grid">
            <section class="garment-output-slot garment-original-output completed"><header><strong>原始商品图</strong><span>已上传</span></header><button class="garment-preview real-asset" type="button" data-asset-preview="原始商品图" title="悬停后滚轮缩放，双击还原" aria-label="放大查看 ${escapeHtml(item.name)} 原图"><img src="${source}" alt="${escapeHtml(item.name)} 原始商品图" loading="lazy"></button></section>
            <div class="garment-output-grid">
              <section class="garment-output-slot model-output ${modelState}" data-model-output><header><strong>${escapeHtml(primaryAssetName)}</strong><div class="storyboard-slot-actions"><span>${modelStatusText}</span>${canManageVideoTasks && model && !generating ? `<button type="button" data-regenerate-first-frame title="使用脚本第 01 分镜真实画面重做首帧，并同步重建九宫格">重做</button>` : ""}</div></header><button class="model-output-canvas real-asset" type="button" ${model ? "" : "disabled"} data-asset-preview="${escapeHtml(primaryAssetName)}" title="用于制作最终九宫格" aria-label="放大查看 ${escapeHtml(primaryAssetName)}">${modelCanvas}</button>${aiVideoImageAttemptMarkup(imagePresentation.modelStep)}</section>
              <section class="garment-output-slot storyboard-output ${shotState}" data-storyboard-output><header><strong>4K 分镜九宫格</strong><div class="storyboard-slot-actions"><span>${storyboardStatusText}</span>${canManageVideoTasks && model && !generating ? `<button type="button" data-regenerate-storyboard title="保留 AI 模特图，重建动作底图并重新执行人物替换">${storyboard ? "重建" : "重新生成"}</button>` : ""}</div></header><button class="mini-storyboard real-assets" type="button" ${storyboard ? "" : "disabled"} data-asset-preview="4K 分镜九宫格" title="9:16 画幅；悬停后滚轮缩放，双击还原" aria-label="放大查看 4K 分镜九宫格">${storyboardTiles}</button>${aiVideoImageAttemptMarkup(imagePresentation.gridStep)}</section>
            </div>
          </div>
          ${error}
          <section class="garment-video-output ${video ? "completed" : item.status === "VIDEO_FAILED" ? "failed" : itemVideoWaiting ? "processing" : "waiting"}" data-video-output><header><strong>AI 视频生成结果</strong><div class="garment-video-status-actions"><span>${video ? "已完成" : item.status === "VIDEO_FAILED" ? "视频未生成" : itemAwaitingAcceptance ? "等待受理" : itemVideoWaiting ? "生成中" : "等待生成"}</span>${providerTaskCopyButton}</div></header>${video ? `<button class="garment-video-canvas" type="button" data-asset-preview="AI 视频成片" aria-label="放大播放 AI 视频成片"><video src="${videoUrl}" preload="metadata" playsinline></video><i>▶</i><div><strong>点击预览成片</strong><small>${video.temporaryDownload ? "厂商临时链接，请及时下载" : "已保存到平台，可下载"}</small></div></button>` : `<button class="garment-video-canvas" type="button" disabled>${itemVideoWaiting ? `<img class="video-waiting-cat" src="/assets/waiting-cat.gif" alt="" aria-hidden="true">` : ""}<i aria-hidden="true">${item.status === "VIDEO_FAILED" ? "!" : itemVideoWaiting ? "" : "▶"}</i><div><strong>${item.status === "VIDEO_FAILED" ? escapeHtml(friendlyAiVideoError(item.errorMessage, "video")) : itemAwaitingAcceptance ? "正在提交，等待厂商受理" : itemVideoWaiting ? "视频正在后台生成" : itemImagePreparing ? "正在自动准备视频素材" : "等待一键生成视频"}</strong><small>${item.status === "VIDEO_FAILED" ? escapeHtml(aiVideoFailureDetail(item.errorMessage)) : itemAwaitingAcceptance ? "厂商返回任务 ID 后才会进入视频生成" : itemVideoWaiting ? "通常 20–40 分钟完成，完成后自动回显" : itemImagePreparing ? "图片正在生成或校验，无需重复点击" : "点击生成后会自动完成图片与视频两阶段"}</small></div><em${!itemAwaitingAcceptance && itemVideoWaiting && item.videoSubmittedAt ? ` class="video-elapsed-time" data-video-submitted-at="${escapeHtml(item.videoSubmittedAt)}"` : ""}>${item.status === "VIDEO_FAILED" ? "未生成" : itemAwaitingAcceptance ? "待受理" : itemVideoWaiting ? formatAiVideoElapsed(item.videoSubmittedAt) : "--:--"}</em></button>`}</section>
        </div>
        <section class="garment-card-face garment-card-back" aria-hidden="${!flipped}">
          <header class="video-prompt-head"><div><strong translate="no">${escapeHtml(item.name)}</strong><small>上传顺序：${isModelWear ? "单人首帧" : "商品首帧"} → 4K 九宫格 → 原始商品图</small></div><div class="video-prompt-head-actions">${renderAiVideoCardExpandButton(item, true)}<button class="copy-prompt-button" type="button" data-prompt-copy aria-label="一键复制本款视频提示词"><i aria-hidden="true">⧉</i><span>一键复制</span></button><button type="button" data-card-flip ${editingPrompt ? "disabled" : ""}><i>↻</i> 返回画面</button></div></header>
          <div class="video-prompt-body">
            <div class="video-prompt-label"><span>本段视频提示词</span><em>${promptCount.toLocaleString()} / 20,000</em></div>
            <textarea class="video-prompt-editor" data-video-prompt-editor maxlength="20000" spellcheck="false" ${editingPrompt ? "" : "readonly"} placeholder="还没有视频提示词，点击编辑后填写。">${escapeHtml(promptValue)}</textarea>
          </div>
          <footer class="video-prompt-actions"><span>${editingPrompt ? "正在编辑，保存后用于本款视频生成" : "已保存为本款独立提示词"}</span><div>${!canManageVideoTasks ? "" : editingPrompt ? `<button class="quiet-prompt-button" type="button" data-prompt-cancel>取消</button><button class="save-prompt-button" type="button" data-prompt-save>保存修改</button>` : `<button class="edit-prompt-button" type="button" data-prompt-edit>编辑提示词</button>`}</div></footer>
        </section>
      </div>
    </article>`];
  }));
  const garmentCardsHtml = [...garmentCardsByItemId.values()].join("");
  const garmentCardGrid = document.querySelector("#garment-card-grid");
  const existingItemIds = [...garmentCardGrid.querySelectorAll(":scope > [data-garment-card]")]
    .map(card => card.dataset.itemId);
  const nextItemIds = detail.items.map(item => item.id);
  const canPatchChangedCards = changedItemIds
    && existingItemIds.length === nextItemIds.length
    && existingItemIds.every((itemId, index) => itemId === nextItemIds[index]);
  if (!canPatchChangedCards) {
    replaceHtmlPreservingMedia(garmentCardGrid, garmentCardsHtml);
  } else if (changedItemIds.size) {
    const staging = document.createElement("div");
    changedItemIds.forEach(itemId => {
      const currentCard = garmentCardGrid.querySelector(`[data-garment-card][data-item-id="${CSS.escape(itemId)}"]`);
      staging.innerHTML = garmentCardsByItemId.get(itemId) || "";
      const nextCard = staging.querySelector(`[data-garment-card][data-item-id="${CSS.escape(itemId)}"]`);
      if (currentCard && nextCard) IncrementalDOM.patch(currentCard,nextCard);
    });
  }
  syncAiVideoElapsedTimer();
}

async function selectAiVideoTask(id, {
  startPolling = true,
  expectedGeneration = aiVideoTaskLoadGeneration,
  expectedOrganizationId = currentUser?.organizationId || ""
} = {}) {
  const selectionChanged = selectedAiVideoTaskId !== id;
  selectedAiVideoTaskId = id;
  if (selectionChanged) {
    aiVideoTaskRenderSignature = "";
    expandedAiVideoItemIds.clear();
    flippedAiVideoPromptItems.clear();
    renderAiVideoTaskList();
  }
  try {
    const result = await api(`/api/v1/ai-video-tasks/${encodeURIComponent(id)}`);
    if (selectedAiVideoTaskId !== id
        || expectedGeneration !== aiVideoTaskLoadGeneration
        || currentUser?.organizationId !== expectedOrganizationId) return;
    if (result.data?.task?.organizationId
        && result.data.task.organizationId !== expectedOrganizationId) return;
    const nextSignature = aiVideoDetailRenderSignature(result.data);
    const shouldRender = nextSignature !== aiVideoTaskRenderSignature;
    const changedItemIds = selectionChanged ? null : changedAiVideoItemIds(aiVideoTaskDetail, result.data);
    aiVideoTaskDetail = result.data;
    const index = aiVideoTasks.findIndex(task => task.id === id);
    if (index >= 0) aiVideoTasks[index] = result.data.task;
    if (shouldRender) {
      aiVideoTaskRenderSignature = nextSignature;
      renderAiVideoTaskList();
      renderAiVideoTaskDetail({ changedItemIds });
    }
    if (startPolling) syncAiVideoTaskPolling();
  } catch (error) {
    if (expectedGeneration !== aiVideoTaskLoadGeneration
        || currentUser?.organizationId !== expectedOrganizationId) return;
    if (startPolling) renderAiVideoEmpty("任务读取失败", error.message);
  }
}

function syncAiVideoTaskPolling() {
  if (aiVideoTaskPollTimer) clearInterval(aiVideoTaskPollTimer);
  aiVideoTaskPollTimer = null;
  if (!shouldPollAiVideoTaskDetail()) return;
  aiVideoTaskPollTimer = setInterval(async () => {
    if (!selectedAiVideoTaskId || document.hidden || aiVideoTaskPollInFlight) return;
    aiVideoTaskPollInFlight = true;
    try {
      await selectAiVideoTask(selectedAiVideoTaskId, { startPolling: false });
      if (!shouldPollAiVideoTaskDetail()) {
        clearInterval(aiVideoTaskPollTimer);
        aiVideoTaskPollTimer = null;
      }
    } finally {
      aiVideoTaskPollInFlight = false;
    }
  }, 5000);
}

function shouldPollAiVideoTaskDetail() {
  if (["IMAGE_GENERATING", "VIDEO_GENERATING"].includes(aiVideoTaskDetail?.task?.status)) return true;
  return (aiVideoTaskDetail?.items || []).some(item =>
    ["FAILED", "VIDEO_FAILED"].includes(item.status)
      && (isAiVideoConcurrencyError(item.errorMessage) || isAiVideoTransientNetworkError(item.errorMessage)));
}

async function loadAiVideoTasks(preferredId = selectedAiVideoTaskId) {
  const generation = ++aiVideoTaskLoadGeneration;
  const organizationId = currentUser?.organizationId || "";
  const list = document.querySelector("#ai-video-task-list");
  list.innerHTML = `<div class="video-task-empty">正在读取任务…</div>`;
  try {
    const result = await api("/api/v1/ai-video-tasks");
    if (generation !== aiVideoTaskLoadGeneration || currentUser?.organizationId !== organizationId) return;
    aiVideoTasks = (result.data || [])
      .filter(task => !task.organizationId || task.organizationId === organizationId);
    const nextSelectedAiVideoTaskId = aiVideoTasks.some(task => task.id === preferredId) ? preferredId : aiVideoTasks[0]?.id || null;
    if (selectedAiVideoTaskId !== nextSelectedAiVideoTaskId) {
      expandedAiVideoItemIds.clear();
      flippedAiVideoPromptItems.clear();
    }
    selectedAiVideoTaskId = nextSelectedAiVideoTaskId;
    renderAiVideoTaskList();
    if (selectedAiVideoTaskId) await selectAiVideoTask(selectedAiVideoTaskId, {
      expectedGeneration: generation,
      expectedOrganizationId: organizationId
    });
    else {
      aiVideoTaskDetail = null;
      renderAiVideoTaskDetail();
    }
  } catch (error) {
    if (generation !== aiVideoTaskLoadGeneration || currentUser?.organizationId !== organizationId) return;
    list.innerHTML = `<div class="video-task-empty"><strong>任务读取失败</strong><span>${escapeHtml(error.message)}</span></div>`;
  }
}

const monitorStatusNames = {
  DRAFT: "草稿", READY: "等待生图", IMAGE_GENERATING: "正在生图", IMAGE_READY: "图片完成",
  VIDEO_GENERATING: "正在生视频", VIDEO_READY: "全部完成", FAILED: "失败",
  GENERATING_MODEL: "生成主图", GENERATING_STORYBOARD: "生成九宫格", VIDEO_SUBMITTING: "提交视频",
  SUCCEEDED: "成功", RUNNING: "处理中", QUEUED: "排队中", SUBMITTING: "提交中",
  REVIEW_REQUIRED: "需要确认", VIDEO_FAILED: "视频失败"
};

const monitorAssetNames = {
  MODEL_IMAGE: "AI 模特主图", PRODUCT_SCENE_IMAGE: "商品场景主图", STORYBOARD_GRID: "4K 分镜九宫格",
  STORYBOARD_GUIDE: "分镜参考图", STORYBOARD_FIRST_FRAME: "视频首帧", VIDEO_REFERENCE_IMAGE: "视频参考图", VIDEO_RESULT: "视频成片"
};

function monitorStatusGroup(status) {
  if (["IMAGE_GENERATING", "VIDEO_GENERATING", "GENERATING_MODEL", "GENERATING_STORYBOARD", "VIDEO_SUBMITTING", "RUNNING", "QUEUED", "SUBMITTING"].includes(status)) return "ACTIVE";
  if (["FAILED", "VIDEO_FAILED", "REVIEW_REQUIRED"].includes(status)) return "FAILED";
  if (["IMAGE_READY", "VIDEO_READY", "SUCCEEDED"].includes(status)) return "COMPLETED";
  return "WAITING";
}

function monitorDuration(start, end) {
  if (!start) return "—";
  const seconds = Math.max(0, Math.round((new Date(end || Date.now()) - new Date(start)) / 1000));
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  return seconds < 3600 ? `${minutes} 分 ${seconds % 60} 秒` : `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
}

function monitorFilteredTasks() {
  const query = document.querySelector("#monitor-search-input").value.trim().toLowerCase();
  return (aiVideoMonitorSnapshot.tasks || []).filter(task => {
    const statusMatches = aiVideoMonitorStatus === "ALL" || monitorStatusGroup(task.status) === aiVideoMonitorStatus;
    const taskTypeLabel = task.taskType === "GENERAL" ? "通用生成 general" : "商品视频 product";
    const haystack = [task.name, task.scriptName, task.modelId, task.id, task.status, task.taskType, taskTypeLabel].filter(Boolean).join(" ").toLowerCase();
    return statusMatches && (!query || haystack.includes(query));
  });
}

function renderAiVideoMonitorOverview() {
  const stats = aiVideoMonitorSnapshot.stats || {};
  document.querySelector("#monitor-total-tasks").textContent = stats.totalTasks || 0;
  document.querySelector("#monitor-active-tasks").textContent = stats.activeTasks || 0;
  document.querySelector("#monitor-image-requests").textContent = stats.imageRequests || 0;
  document.querySelector("#monitor-video-requests").textContent = stats.videoRequests || 0;
  document.querySelector("#monitor-failed-tasks").textContent = stats.failedTasks || 0;
}

function renderAiVideoMonitorList() {
  const tasks = monitorFilteredTasks();
  const list = document.querySelector("#monitor-task-list");
  document.querySelector("#monitor-result-count").textContent = `${tasks.length} 条结果`;
  if (!tasks.length) {
    IncrementalDOM.html(list, `<div class="monitor-empty"><strong>没有匹配任务</strong><span>调整状态或关键词后再试。</span></div>`);
    return;
  }
  IncrementalDOM.html(list, tasks.map(task => {
    const group = monitorStatusGroup(task.status).toLowerCase();
    const taskType = task.taskType === "GENERAL" ? "通用生成" : escapeHtml(task.scriptName || "视频任务");
    return `<button class="monitor-task-row ${task.id === selectedMonitorTaskId ? "active" : ""}" type="button" data-monitor-task-id="${escapeHtml(task.id)}">
      <span class="monitor-task-signal ${group}"><i></i></span>
      <span class="monitor-task-copy"><strong translate="${task.name ? 'no' : 'yes'}">${escapeHtml(task.name || "未命名任务")}</strong><small>${taskType} · ${formatDate(task.updatedAt)}</small></span>
      <span class="monitor-task-meta"><b>${monitorStatusNames[task.status] || escapeHtml(task.status)}</b><small>${task.imageRequests || 0} 图 / ${task.videoRequests || 0} 视频请求</small></span>
      <span class="monitor-task-progress"><i style="--progress:${Math.max(0, Math.min(100, task.progress || 0))}%"></i></span>
    </button>`;
  }).join(""));
}

function renderMonitorAssetPreview(event, task) {
  const taskId = task.id;
  const generalTask = task.taskType === "GENERAL";
  const url = generalTask ? generalVideoAssetUrl(taskId, event) : aiVideoAssetUrl(taskId, event.id, event.createdAt);
  if (String(event.contentType || "").startsWith("video/") || event.type === "VIDEO_RESULT") {
    return `<div class="monitor-asset-preview is-video"><video src="${url}" controls preload="metadata" playsinline></video></div>`;
  }
  if (String(event.contentType || "").startsWith("image/")) {
    const previewUrl = generalTask ? generalVideoAssetUrl(taskId, event, true) : aiVideoAssetUrl(taskId, event.id, event.createdAt, true);
    return `<button class="monitor-asset-preview is-image" type="button" data-asset-preview="${escapeHtml(monitorAssetNames[event.type] || event.type || "生成图片")}" aria-label="放大查看${escapeHtml(monitorAssetNames[event.type] || "生成图片")}"><img src="${previewUrl}" data-full-src="${url}" alt="${escapeHtml(monitorAssetNames[event.type] || "生成图片")}" loading="lazy"></button>`;
  }
  return "";
}

function renderMonitorImageEvents(events, task) {
  if (!events?.length) return `<div class="monitor-sub-empty">还没有生图资产。运行中的请求可从上方任务与单品状态判断。</div>`;
  return `<div class="monitor-media-event-row monitor-image-event-grid">${events.map(event => `<article class="monitor-request-card image-request">
    <header><span class="request-kind">IMG</span><div><strong>${monitorAssetNames[event.type] || escapeHtml(event.type)}</strong><small>${formatDate(event.createdAt)} · ${escapeHtml(event.modelId || "未记录模型")}</small></div><b>已落盘</b></header>
    ${renderMonitorAssetPreview(event, task)}
    <dl><div><dt>资产 ID</dt><dd>${escapeHtml(event.id)}</dd></div><div><dt>文件</dt><dd>${escapeHtml(event.contentType || "—")} · ${event.sizeBytes ? `${(event.sizeBytes / 1024 / 1024).toFixed(2)} MB` : "—"}</dd></div></dl>
    <details><summary>查看本次生图提示词</summary><pre>${escapeHtml(event.prompt || "该资产未保存提示词")}</pre></details>
  </article>`).join("")}</div>`;
}

function renderMonitorVideoRequests(requests, videoPrompt, videoAsset, task) {
  if (!requests?.length) return `<div class="monitor-sub-empty">还没有向视频线路提交请求。</div>`;
  return `<div class="monitor-media-event-row monitor-video-event-grid">${requests.map(request => {
    const group = monitorStatusGroup(request.status).toLowerCase();
    return `<article class="monitor-request-card video-request ${group}">
      <header><span class="request-kind">VID</span><div><strong>第 ${request.attempt} 次尝试</strong><small>${formatDate(request.createdAt)} · ${monitorDuration(request.createdAt, request.completedAt || request.updatedAt)}</small></div><b>${monitorStatusNames[request.status] || escapeHtml(request.status)}</b></header>
      ${videoAsset && ["COMPLETED", "SUCCEEDED"].includes(request.status) ? renderMonitorAssetPreview(videoAsset, task) : ""}
      <dl>
        <div><dt>请求 ID</dt><dd>${escapeHtml(request.id)}</dd></div><div><dt>供应商任务 ID</dt><dd>${escapeHtml(request.providerTaskId || "尚未受理")}</dd></div>
        <div><dt>模型</dt><dd>${escapeHtml(request.modelId || "—")}</dd></div><div><dt>规格</dt><dd>${request.durationSeconds || "—"} 秒 · ${escapeHtml(request.aspectRatio || "—")} · ${escapeHtml(request.resolution || "—")}</dd></div>
        <div><dt>请求状态</dt><dd>${escapeHtml(request.requestState || "—")}</dd></div><div><dt>费用</dt><dd>${request.cost == null ? "—" : escapeHtml(request.cost)}</dd></div>
      </dl>
      ${request.errorMessage ? `<p class="monitor-request-error">${escapeHtml(request.errorMessage)}</p>` : ""}
      <details><summary>查看本次视频提示词与用量</summary><div class="monitor-request-payload"><span>视频提示词</span><pre>${escapeHtml(videoPrompt || "未保存提示词")}</pre><span>用量数据</span><pre>${escapeHtml(request.usage || "暂无")}</pre></div></details>
      ${renderProviderJsonDetails(request)}
    </article>`;
  }).join("")}</div>`;
}

function prettyProviderJson(value) {
  if (!value) return "暂无记录（该请求发生在调试记录功能上线前）";
  try { return JSON.stringify(JSON.parse(value), null, 2); }
  catch (_error) { return String(value); }
}

function renderProviderJsonDetails(request) {
  const sections = [
    ["厂商提交请求 JSON", request.submitRequestJson],
    ["厂商提交响应 JSON", request.submitResponseJson],
    ["厂商轮询请求 JSON", request.queryRequestJson],
    ["厂商轮询响应 JSON", request.queryResponseJson]
  ];
  return `<div class="monitor-provider-json">${sections.map(([title, value]) =>
    `<details><summary>${title}</summary><pre>${escapeHtml(prettyProviderJson(value))}</pre></details>`
  ).join("")}</div>`;
}

function renderAiVideoMonitorDetail() {
  const stage = document.querySelector("#monitor-detail-stage");
  const task = selectedMonitorTaskDetail;
  if (!task) {
    stage.innerHTML = `<div class="monitor-empty monitor-detail-empty"><strong>选择一条任务</strong><span>这里会展示生图和生视频的完整请求轨迹。</span></div>`;
    return;
  }
  const taskItems = task.items || [];
  const generalTask = task.taskType === "GENERAL";
  const allImageEvents = taskItems.flatMap(item => item.imageEvents || []);
  const allVideoRequests = taskItems.flatMap(item => item.videoRequests || []);
  const hasImage = allImageEvents.length > 0;
  const hasVideo = allVideoRequests.length > 0;
  const finished = ["IMAGE_READY", "VIDEO_READY", "FAILED"].includes(task.status);
  const detailHtml = `
    <header class="monitor-detail-head">
      <div><span class="monitor-detail-id">${generalTask ? "GENERAL" : "TASK"} · ${escapeHtml(task.id)}</span><h2 translate="${task.name ? 'no' : 'yes'}">${escapeHtml(task.name || "未命名任务")}</h2><p>${generalTask ? `通用生成 · ${escapeHtml(task.durationSeconds || "—")} 秒` : `${escapeHtml(task.scriptName || "未关联脚本")} · v${task.scriptVersion} · ${escapeHtml(task.generationMode || "—")}`}</p></div>
      <div class="monitor-detail-status ${monitorStatusGroup(task.status).toLowerCase()}"><i></i><span>${monitorStatusNames[task.status] || escapeHtml(task.status)}</span><strong>${task.progress || 0}%</strong></div>
    </header>
    <section class="monitor-trace" aria-label="任务生成轨迹">
      <div class="done"><i></i><span>任务创建</span><small>${formatDate(task.createdAt)}</small></div><b class="done"></b>
      <div class="${task.status === "IMAGE_GENERATING" ? "current" : hasImage ? "done" : ""}"><i></i><span>${generalTask ? "参考图" : "生图"}</span><small>${allImageEvents.length} 个资产</small></div><b class="${hasImage ? "done" : ""}"></b>
      <div class="${task.status === "VIDEO_GENERATING" ? "current" : task.status === "VIDEO_READY" ? "done" : ""}"><i></i><span>生视频</span><small>${allVideoRequests.length} 次请求</small></div><b class="${hasVideo ? "done" : ""}"></b>
      <div class="${task.status === "FAILED" ? "failed" : finished ? "done" : ""}"><i></i><span>${task.status === "FAILED" ? "异常结束" : "任务完成"}</span><small>${finished ? monitorDuration(task.createdAt, task.completedAt || task.updatedAt) : "等待全部完成"}</small></div>
    </section>
    <section class="monitor-task-facts">
      <div><span>任务类型</span><strong>${generalTask ? "通用生成" : "商品视频"}</strong></div><div><span>${generalTask ? "视频时长" : "商品品类"}</span><strong>${generalTask ? `${escapeHtml(task.durationSeconds || "—")} 秒` : escapeHtml(task.productCategory || "未设置")}</strong></div>
      <div><span>画面规格</span><strong>${escapeHtml(task.aspectRatio || "—")} · ${escapeHtml(task.resolution || "—")}</strong></div><div><span>当前模型</span><strong>${escapeHtml(task.modelId || "尚未调用")}</strong></div>
    </section>
    ${task.errorMessage ? `<p class="monitor-task-error"><strong>任务错误</strong>${escapeHtml(task.errorMessage)}</p>` : ""}
    <section class="monitor-items"><header><div><span>请求轨迹</span><h3>${taskItems.length} 个${generalTask ? "生成单元" : "商品单品"}</h3></div><small>展开查看完整参数</small></header>
      ${taskItems.map((item, index) => `<details data-update-key="${escapeHtml(task.id+':'+item.id)}" class="monitor-item" ${index === 0 ? "open" : ""}>
        <summary><span class="monitor-item-index">${String(index + 1).padStart(2, "0")}</span><div><strong translate="${item.name ? 'no' : 'yes'}">${escapeHtml(item.name || item.sourceFileName || "未命名单品")}</strong><small>${escapeHtml(item.sku || "无 SKU")} · 更新于 ${formatDate(item.updatedAt)}</small></div><span class="monitor-item-count">${item.imageEvents?.length || 0} 图 / ${item.videoRequests?.length || 0} 请求</span><b>${monitorStatusNames[item.status] || escapeHtml(item.status)}</b></summary>
        <div class="monitor-item-body">
          ${item.errorMessage ? `<p class="monitor-request-error">${escapeHtml(item.errorMessage)}</p>` : ""}
          <div class="monitor-request-section"><h4><span>${generalTask ? "参考素材" : "生图轨迹"}</span><small>输入与输出资产</small></h4>${renderMonitorImageEvents((item.imageEvents || []).filter(asset => asset.type !== "VIDEO_RESULT"), task)}</div>
          <div class="monitor-request-section"><h4><span>生视频请求</span><small>每次线路提交与重试</small></h4>${renderMonitorVideoRequests(item.videoRequests, item.videoPrompt, (item.imageEvents || []).find(asset => asset.type === "VIDEO_RESULT"), task)}</div>
        </div>
      </details>`).join("")}
    </section>`;
  replaceHtmlPreservingMedia(stage, detailHtml);
}

function captureAiVideoMonitorUiState() {
  const stage = document.querySelector("#monitor-detail-stage");
  return {
    windowX: window.scrollX,
    windowY: window.scrollY,
    stageScrollTop: stage?.scrollTop || 0,
    openDetails: Array.from(stage?.querySelectorAll("details") || []).map(detail => detail.open),
    rowScrollLeft: Array.from(stage?.querySelectorAll(".monitor-media-event-row") || []).map(row => row.scrollLeft)
  };
}

function restoreAiVideoMonitorUiState(state) {
  if (!state) return;
  const restore = () => {
    const stage = document.querySelector("#monitor-detail-stage");
    Array.from(stage?.querySelectorAll("details") || []).forEach((detail, index) => {
      if (index < state.openDetails.length) detail.open = state.openDetails[index];
    });
    Array.from(stage?.querySelectorAll(".monitor-media-event-row") || []).forEach((row, index) => {
      row.scrollLeft = state.rowScrollLeft[index] || 0;
    });
    if (stage) stage.scrollTop = state.stageScrollTop;
    window.scrollTo(state.windowX, state.windowY);
  };
  restore();
  requestAnimationFrame(restore);
}

async function selectAiVideoMonitorTask(id, options = {}) {
  const silent = Boolean(options.silent);
  const expectedGeneration = options.expectedGeneration ?? aiVideoMonitorLoadGeneration;
  const expectedOrganizationId = options.expectedOrganizationId ?? currentUser?.organizationId ?? "";
  const uiState = silent ? captureAiVideoMonitorUiState() : null;
  selectedMonitorTaskId = id;
  if (!silent) renderAiVideoMonitorList();
  const stage = document.querySelector("#monitor-detail-stage");
  if (!silent) stage.innerHTML = `<div class="monitor-empty monitor-detail-empty">正在读取请求明细…</div>`;
  try {
    const result = await api(`/api/v1/ai-video-monitor/${encodeURIComponent(id)}`);
    if (selectedMonitorTaskId !== id
        || expectedGeneration !== aiVideoMonitorLoadGeneration
        || currentUser?.organizationId !== expectedOrganizationId) return;
    if (result.data?.organizationId && result.data.organizationId !== expectedOrganizationId) return;
    selectedMonitorTaskDetail = result.data;
    renderAiVideoMonitorDetail();
    restoreAiVideoMonitorUiState(uiState);
  } catch (error) {
    if (expectedGeneration !== aiVideoMonitorLoadGeneration
        || currentUser?.organizationId !== expectedOrganizationId) return;
    if (!silent) stage.innerHTML = `<div class="monitor-empty monitor-detail-empty"><strong>明细读取失败</strong><span>${escapeHtml(error.message)}</span></div>`;
  }
}

async function loadAiVideoMonitor(options = {}) {
  const generation = ++aiVideoMonitorLoadGeneration;
  const organizationId = currentUser?.organizationId || "";
  const list = document.querySelector("#monitor-task-list");
  if (!options.silent) list.innerHTML = `<div class="monitor-empty">正在读取任务轨迹…</div>`;
  try {
    const result = await api("/api/v1/ai-video-monitor");
    if (generation !== aiVideoMonitorLoadGeneration || currentUser?.organizationId !== organizationId) return;
    aiVideoMonitorSnapshot = result.data || { stats: {}, tasks: [] };
    aiVideoMonitorSnapshot.tasks = (aiVideoMonitorSnapshot.tasks || [])
      .filter(task => !task.organizationId || task.organizationId === organizationId);
    const listScrollTop = list.scrollTop;
    renderAiVideoMonitorOverview();
    const filtered = monitorFilteredTasks();
    const nextId = filtered.some(task => task.id === selectedMonitorTaskId) ? selectedMonitorTaskId : filtered[0]?.id || null;
    const nextSummary = filtered.find(task => task.id === nextId);
    selectedMonitorTaskId = nextId;
    renderAiVideoMonitorList();
    if (options.silent) list.scrollTop = listScrollTop;
    const detailChanged = !selectedMonitorTaskDetail || selectedMonitorTaskDetail.id !== nextId
      || selectedMonitorTaskDetail.updatedAt !== nextSummary?.updatedAt;
    if (nextId && (!options.silent || detailChanged)) {
      await selectAiVideoMonitorTask(nextId, {
        silent: Boolean(options.silent),
        expectedGeneration: generation,
        expectedOrganizationId: organizationId
      });
    } else {
      if (!nextId) {
        selectedMonitorTaskDetail = null;
        renderAiVideoMonitorDetail();
      }
    }
    if (!aiVideoMonitorTimer) {
      aiVideoMonitorTimer = setInterval(() => {
        if (!document.querySelector("#ai-video-monitor-page").hidden && !document.hidden) loadAiVideoMonitor({ silent: true });
      }, 10000);
    }
  } catch (error) {
    if (generation !== aiVideoMonitorLoadGeneration || currentUser?.organizationId !== organizationId) return;
    list.innerHTML = `<div class="monitor-empty"><strong>监控数据读取失败</strong><span>${escapeHtml(error.message)}</span></div>`;
  }
}

document.querySelector("#monitor-task-list").addEventListener("click", event => {
  const task = event.target.closest("[data-monitor-task-id]");
  if (task) selectAiVideoMonitorTask(task.dataset.monitorTaskId);
});

document.querySelector("#monitor-search-input").addEventListener("input", () => {
  selectedMonitorTaskId = null;
  selectedMonitorTaskDetail = null;
  renderAiVideoMonitorList();
  renderAiVideoMonitorDetail();
});

document.addEventListener("keydown", event => {
  const monitorPage = document.querySelector("#ai-video-monitor-page");
  if (monitorPage.hidden) return;
  const search = document.querySelector("#monitor-search-input");
  const editing = event.target.matches("input, textarea, select, [contenteditable='true']");
  if (event.key === "/" && !editing) {
    event.preventDefault();
    search.focus();
  } else if (event.key === "Escape" && event.target === search) {
    search.value = "";
    search.blur();
    search.dispatchEvent(new Event("input"));
  }
});

document.querySelector("#monitor-status-filter").addEventListener("click", event => {
  const button = event.target.closest("[data-monitor-status]");
  if (!button) return;
  aiVideoMonitorStatus = button.dataset.monitorStatus;
  document.querySelectorAll("[data-monitor-status]").forEach(item => item.classList.toggle("active", item === button));
  selectedMonitorTaskId = null;
  selectedMonitorTaskDetail = null;
  renderAiVideoMonitorList();
  renderAiVideoMonitorDetail();
});

document.querySelector("#refresh-ai-video-monitor").addEventListener("click", () => loadAiVideoMonitor());
document.querySelector("#reset-monitor-filters").addEventListener("click", () => {
  aiVideoMonitorStatus = "ALL";
  document.querySelector("#monitor-search-input").value = "";
  document.querySelectorAll("[data-monitor-status]").forEach(item => item.classList.toggle("active", item.dataset.monitorStatus === "ALL"));
  renderAiVideoMonitorList();
});

async function loadAiVideoTaskScripts() {
  const select = document.querySelector("#video-task-script-select");
  const submit = document.querySelector("#video-task-form button[type=submit]");
  select.innerHTML = `<option value="">正在读取脚本…</option>`;
  submit.disabled = true;
  try {
    const result = await api("/api/v1/video-scripts");
    const readyScripts = (result.data || []).filter(script => script.status === "READY");
    const details = await Promise.all(readyScripts.map(script => api(`/api/v1/video-scripts/${encodeURIComponent(script.id)}`).catch(() => null)));
    aiVideoUsableScripts = details.filter(Boolean).map(result => result.data)
      .filter(detail => detail?.shots?.length >= 1 && detail.shots.length <= 9);
    renderAiVideoTaskScriptOptions();
    syncVideoTaskModeForm();
  } catch (error) {
    aiVideoUsableScripts = [];
    select.innerHTML = `<option value="">脚本读取失败</option>`;
    document.querySelector("#video-task-dialog-note").textContent = error.message;
  }
}

function renderAiVideoTaskScriptOptions() {
  const form = document.querySelector("#video-task-form");
  const select = form.elements.scriptId;
  const submit = form.querySelector("button[type=submit]");
  const selectedMode = form.querySelector("input[name=generationMode]:checked")?.value || "MODEL_WEAR";
  const scriptReplica = selectedMode === "SCRIPT_REPLICA";
  const supportedModes = new Set(["MODEL_WEAR", "PRODUCT_ONLY", "CAST_PRODUCT"]);
  const mode = selectedMode;
  const previous = select.value;
  const matching = aiVideoUsableScripts.filter(detail => {
    const scriptMode = String(detail.script.presentationMode || "LEGACY_COMPAT").trim().toUpperCase();
    if (scriptReplica) {
      return detail.script.sourceType === "VIDEO_ANALYSIS" && supportedModes.has(scriptMode);
    }
    return scriptMode === mode;
  });
  const modeName = scriptReplica ? "脚本视频复刻" : videoScriptPresentationNames[mode] || "纯商品";
  select.innerHTML = matching.length
    ? `<option value="">请选择${modeName}脚本</option>${matching.map(detail => {
        const scriptMode = detail.script.presentationMode || "LEGACY_COMPAT";
        const legacy = scriptMode === "LEGACY_COMPAT" ? " · 旧脚本兼容" : "";
        const hasNarration = (detail.shots || []).some(shot => String(shot.narration || "").trim());
        const narration = hasNarration ? " · 含旁白" : "";
        const replicaMode = scriptReplica ? ` · ${videoScriptPresentationNames[scriptMode] || "未知类型"}` : "";
        return `<option value="${escapeHtml(detail.script.id)}" data-source-type="${escapeHtml(detail.script.sourceType || "")}" data-presentation-mode="${escapeHtml(scriptMode)}" data-target-market="${escapeHtml(detail.script.targetMarket || "")}" data-has-narration="${hasNarration}">${escapeHtml(detail.script.name)} / v${Number(detail.script.version || 1)}${replicaMode}${legacy}${narration}</option>`;
      }).join("")}`
    : `<option value="">暂无${modeName}的可用脚本</option>`;
  if (matching.some(detail => detail.script.id === previous)) select.value = previous;
  submit.disabled = matching.length === 0;
  document.querySelector("#video-task-dialog-note").textContent = matching.length
    ? scriptReplica
      ? `只显示 ${matching.length} 个保留了原始视频的完整脚本；复刻按普通视频生成的 2 倍收费。`
      : `只显示 ${matching.length} 个与“${modeName}”匹配的脚本，创建后会冻结当前版本。`
    : scriptReplica
      ? "没有可用的复刻脚本：请先从原视频创建并完成 9 分镜脚本拆解。"
      : `没有可用的${modeName}脚本，请先在视频脚本管理中按该类型创建。`;
}

function selectedScriptGenerationMode(option, fallback = "PRODUCT_ONLY") {
  const mode = String(option?.dataset?.presentationMode || "").trim().toUpperCase();
  return ["MODEL_WEAR", "PRODUCT_ONLY", "CAST_PRODUCT"].includes(mode) ? mode : fallback;
}

function syncVideoTaskModeForm() {
  const form = document.querySelector("#video-task-form");
  const selectedMode = form.querySelector("input[name=generationMode]:checked")?.value || "MODEL_WEAR";
  const scriptVideoReplacement = selectedMode === "SCRIPT_REPLICA";
  renderAiVideoTaskScriptOptions();
  const scriptSelect = form.elements.scriptId;
  const selectedScript = scriptSelect?.selectedOptions?.[0];
  const mode = scriptVideoReplacement ? selectedScriptGenerationMode(selectedScript) : selectedMode;
  const hasScriptNarration = selectedScript?.dataset.hasNarration === "true";
  const marketInput = form.elements.targetMarket;
  marketInput.required = hasScriptNarration;
  if (hasScriptNarration && !marketInput.value.trim() && selectedScript?.dataset.targetMarket) {
    marketInput.value = selectedScript.dataset.targetMarket;
  }
  marketInput.placeholder = hasScriptNarration
    ? "脚本含旁白，请选择或填写目标国家"
    : "例如：菲律宾（可选）";
  const baseDirectReferenceVideo = selectedScript?.dataset.sourceType === "MANUAL" || mode === "CAST_PRODUCT";
  const directReferenceVideo = baseDirectReferenceVideo || scriptVideoReplacement;
  form.querySelectorAll(".generation-mode-option").forEach(option => {
    option.classList.toggle("active", option.querySelector("input")?.checked);
  });
  form.querySelectorAll(".background-mode-option").forEach(option => {
    option.classList.toggle("active", option.querySelector("input")?.checked);
  });
  form.querySelectorAll(".resolution-option").forEach(option => {
    option.classList.toggle("active", option.querySelector("input")?.checked);
  });
  const directModeNote = document.querySelector("#direct-reference-mode-note");
  directModeNote.hidden = !directReferenceVideo;
  directModeNote.querySelector("strong").textContent = scriptVideoReplacement
    ? "原视频定向替换 · 多对象映射"
    : "手动脚本·参考图直接生视频";
  directModeNote.querySelector("small").textContent = scriptVideoReplacement
    ? "跳过图片模型。每组上传 1–9 张对象图，逐张指定要替换原视频中的哪个对象；未指定内容保持原样。"
    : "跳过图片模型。上传 1–9 张图后，由用户为每张图指定用途和主参考，再按编号顺序提交视频模型。";
  directModeNote.querySelector("em").textContent = scriptVideoReplacement ? "2倍收费" : "不生图";
  const combinedOptions = document.querySelector("#cast-product-options");
  combinedOptions.hidden = scriptVideoReplacement || mode !== "CAST_PRODUCT";
  document.querySelector("#custom-voiceover-field").hidden = scriptVideoReplacement || mode !== "CAST_PRODUCT" || form.elements.voiceoverMode.value !== "CUSTOM";
  form.querySelector(".background-mode-picker").hidden = directReferenceVideo;
  const chargePanel = document.querySelector("#script-video-rate-warning");
  const chargeConfirm = form.elements.replicaChargeAccepted;
  chargePanel.hidden = !scriptVideoReplacement;
  chargeConfirm.required = scriptVideoReplacement;
  chargeConfirm.disabled = !scriptVideoReplacement;
  if (!scriptVideoReplacement) chargeConfirm.checked = false;
  form.querySelector("button[type=submit]").textContent = scriptVideoReplacement
    ? "创建复刻任务（2倍收费）"
    : directReferenceVideo ? "创建参考图任务" : "创建并准备素材";
  if (hasScriptNarration) {
    document.querySelector("#video-task-dialog-note").textContent = marketInput.value.trim()
      ? `该脚本含旁白，创建任务时会转换为“${marketInput.value.trim()}”对应语言；动作、镜头和时间不变。`
      : "该脚本含旁白，必须选择或填写目标国家；系统只转换已有旁白，不会新增文案。";
  }
  const category = form.elements.productCategory;
  if (mode === "MODEL_WEAR" && !["服装", "鞋包", "饰品"].includes(category.value)) category.value = "服装";
  if (mode !== "MODEL_WEAR" && category.value === "服装") category.value = "通用商品";
}

document.querySelector("#video-task-form").addEventListener("change", event => {
  if (["generationMode", "videoRoute", "backgroundMode", "resolution", "scriptId", "voiceoverMode"].includes(event.target.name)) syncVideoTaskModeForm();
  if (event.target.name === "music") {
    const file = event.target.files?.[0];
    const field = document.querySelector("#video-task-music-field");
    field.classList.toggle("has-file", Boolean(file));
    document.querySelector("#video-task-music-name").textContent = file
      ? `${file.name} · ${(file.size / 1024 / 1024).toFixed(1)}MB`
      : "未上传时，AI 自动生成轻快、有旋律的纯音乐";
    document.querySelector("#video-task-music-action").textContent = file ? "更换音乐" : "选择音乐";
  }
});

document.querySelector("#create-video-task").addEventListener("click", async () => {
  const dialog = document.querySelector("#video-task-dialog");
  document.querySelector("#video-task-form").reset();
  document.querySelector("#video-task-music-field").classList.remove("has-file");
  document.querySelector("#video-task-music-name").textContent = "未上传时，AI 自动生成轻快、有旋律的纯音乐";
  document.querySelector("#video-task-music-action").textContent = "选择音乐";
  syncVideoTaskModeForm();
  dialog.showModal();
  await loadAiVideoTaskScripts();
});

document.querySelector("#video-task-form").addEventListener("submit", async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const submit = form.querySelector("button[type=submit]");
  const dialogNote = document.querySelector("#video-task-dialog-note");
  const values = new FormData(form);
  const selectedCreationMode = values.get("generationMode") || "MODEL_WEAR";
  const scriptVideoReplacement = selectedCreationMode === "SCRIPT_REPLICA";
  const musicFile = form.elements.music.files?.[0];
  const selectedScript = form.elements.scriptId?.selectedOptions?.[0];
  const generationMode = scriptVideoReplacement
    ? selectedScriptGenerationMode(selectedScript)
    : selectedCreationMode;
  const needsNarrationLocalization = values.get("voiceoverMode") === "SCRIPT"
    && selectedScript?.dataset.hasNarration === "true";
  let createdTaskId = null;
  let createErrorMessage = "";
  let createElapsedSeconds = 0;
  let createProgressTimer = null;
  submit.disabled = true;
  const paintCreateProgress = () => {
    if (needsNarrationLocalization) {
      submit.textContent = `正在转换旁白（${createElapsedSeconds}秒）…`;
      dialogNote.textContent = createElapsedSeconds < 30
        ? "正在调用驱动模型，将脚本旁白转换为目标国家语言；通常需要 10—60 秒，请勿重复提交。"
        : "旁白转换仍在进行，模型响应较慢；完成或失败后这里会显示明确结果。";
    } else {
      submit.textContent = `正在创建（${createElapsedSeconds}秒）…`;
      dialogNote.textContent = "正在冻结 9 分镜脚本并创建任务，请勿重复提交。";
    }
  };
  paintCreateProgress();
  createProgressTimer = window.setInterval(() => {
    createElapsedSeconds += 1;
    paintCreateProgress();
  }, 1000);
  try {
    const result = await api("/api/v1/ai-video-tasks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        scriptId: values.get("scriptId"),
        name: values.get("name"),
        targetMarket: values.get("targetMarket"),
        generationMode,
        backgroundMode: values.get("backgroundMode"),
        videoRoute: values.get("videoRoute") || "QUALITY",
        useScriptReferenceVideo: scriptVideoReplacement,
        productCategory: values.get("productCategory"),
        aspectRatio: values.get("aspectRatio"),
        resolution: values.get("resolution") || "720p"
        ,voiceoverMode: values.get("voiceoverMode") || "SCRIPT"
        ,customVoiceover: values.get("customVoiceover") || ""
        ,videoTitle: values.get("videoTitle") || ""
      })
    });
    createdTaskId = result.data.id;
    if (musicFile) {
      submit.textContent = "正在上传配乐…";
      const musicData = new FormData();
      musicData.append("file", musicFile);
      await api(`/api/v1/ai-video-tasks/${encodeURIComponent(createdTaskId)}/music`, {
        method: "POST",
        body: musicData
      });
    }
    document.querySelector("#video-task-dialog").close();
    await loadAiVideoTasks(createdTaskId);
  } catch (error) {
    if (createdTaskId) {
      try { await api(`/api/v1/ai-video-tasks/${encodeURIComponent(createdTaskId)}`, { method: "DELETE" }); } catch (_) { }
    }
    createErrorMessage = error.message;
  } finally {
    if (createProgressTimer) window.clearInterval(createProgressTimer);
    submit.disabled = false;
    syncVideoTaskModeForm();
    if (createErrorMessage) dialogNote.textContent = createErrorMessage;
  }
});

document.querySelector("#ai-video-task-list").addEventListener("click", event => {
  const button = event.target.closest("[data-ai-video-task-id]");
  if (button) selectAiVideoTask(button.dataset.aiVideoTaskId);
});

document.querySelector("#video-script-search").addEventListener("input", event => {
  videoScriptSearchQuery = event.currentTarget.value;
  renderVideoScriptLibrary();
});

document.querySelector("#video-script-search").addEventListener("keydown", async event => {
  if (event.key === "Escape") {
    event.currentTarget.value = "";
    videoScriptSearchQuery = "";
    renderVideoScriptLibrary();
    return;
  }
  if (event.key !== "Enter") return;
  const first = filteredVideoScripts()[0];
  if (!first) return;
  event.preventDefault();
  selectedVideoScriptId = first.id;
  renderVideoScriptLibrary();
  await loadVideoScriptDetail(first.id);
});

document.querySelector("#ai-video-task-search").addEventListener("input", event => {
  aiVideoTaskSearchQuery = event.currentTarget.value;
  renderAiVideoTaskList();
});

document.querySelector("#ai-video-task-search").addEventListener("keydown", async event => {
  if (event.key === "Escape") {
    event.currentTarget.value = "";
    aiVideoTaskSearchQuery = "";
    renderAiVideoTaskList();
    return;
  }
  if (event.key !== "Enter") return;
  const first = filteredAiVideoTasks()[0];
  if (!first) return;
  event.preventDefault();
  await selectAiVideoTask(first.id);
});

document.querySelector("#batch-garment-upload").addEventListener("change", async event => {
  const input = event.currentTarget;
  const files = [...input.files];
  if (!selectedAiVideoTaskId || !files.length) return;
  const uploadTaskId = selectedAiVideoTaskId;
  const directReferenceVideo = Boolean(aiVideoTaskDetail?.task?.directReferenceVideo);
  const referenceLimit = Number(aiVideoTaskDetail?.task?.referenceImageLimit || 9);
  if (directReferenceVideo && files.length > referenceLimit) {
    document.querySelector("#video-task-action-note").textContent = `每组最多 ${referenceLimit} 张参考图；请减少本次选择数量。`;
    input.value = "";
    return;
  }
  const formData = new FormData();
  files.forEach(file => formData.append("files", file));
  input.disabled = true;
  document.querySelector("#batch-garment-upload-label").classList.add("busy");
  document.querySelector("#video-task-action-note").textContent = directReferenceVideo
    ? `正在创建新视频组并上传 ${files.length} 张参考图…`
    : `正在上传 ${files.length} 张商品图片…`;
  try {
    await uploadFormData(`/api/v1/ai-video-tasks/${encodeURIComponent(uploadTaskId)}/garments`, formData, percent => {
      document.querySelector("#batch-upload-label-text").textContent = `正在上传 ${percent}%`;
      document.querySelector("#video-task-action-note").textContent = `正在上传 ${files.length} 张${directReferenceVideo ? "参考图" : "商品图片"} · ${percent}%`;
    });
    if (selectedAiVideoTaskId === uploadTaskId) await selectAiVideoTask(uploadTaskId);
  } catch (error) {
    document.querySelector("#video-task-action-note").textContent = error.message;
  } finally {
    input.value = "";
    document.querySelector("#batch-garment-upload-label").classList.remove("busy");
    document.querySelector("#batch-upload-label-text").textContent = directReferenceVideo ? "＋ 新建参考图组" : "＋ 批量上传商品";
    input.disabled = aiVideoTaskDetail?.task?.status === "IMAGE_GENERATING";
  }
});

document.querySelector("#batch-download-videos").addEventListener("click", async event => {
  const button = event.currentTarget;
  if (!selectedAiVideoTaskId || button.disabled) return;
  const videos = (aiVideoTaskDetail?.assets || []).filter(asset => asset.assetType === "VIDEO_RESULT");
  if (videos.length === 1) {
    const video = videos[0];
    await downloadImageAsset(button, aiVideoAssetUrl(selectedAiVideoTaskId, video.id, video.createdAt),
      `${aiVideoTaskDetail?.task?.name || "AI 视频"}-成片`);
    return;
  }
  if (!videos.length) return;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = "正在准备 ZIP…";
  const link = document.createElement("a");
  link.href = `/api/v1/ai-video-tasks/${encodeURIComponent(selectedAiVideoTaskId)}/video-results.zip`;
  link.download = "";
  link.target = "_blank";
  link.rel = "noopener";
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => {
    button.disabled = false;
    button.textContent = original;
  }, 2500);
});

document.querySelector("#garment-card-grid").addEventListener("change", async event => {
  const input = event.target.closest("[data-reference-append]");
  if (!input) return;
  const card = input.closest("[data-garment-card]");
  const itemId = card?.dataset.itemId;
  const files = [...input.files];
  if (!selectedAiVideoTaskId || !itemId || !files.length) return;
  if (!directReferenceItemIsEditable(itemId)) {
    document.querySelector("#video-task-action-note").textContent = "视频已完成，参考图配置已锁定。";
    input.value = "";
    return;
  }
  const current = directReferenceAssets(aiVideoTaskDetail, itemId).length;
  const limit = Number(aiVideoTaskDetail?.task?.referenceImageLimit || 9);
  if (current + files.length > limit) {
    document.querySelector("#video-task-action-note").textContent = `这一组已有 ${current} 张，本次最多还能追加 ${Math.max(0, limit - current)} 张。`;
    input.value = "";
    return;
  }
  const formData = new FormData();
  files.forEach(file => formData.append("files", file));
  input.disabled = true;
  document.querySelector("#video-task-action-note").textContent = `正在给当前视频组追加 ${files.length} 张参考图…`;
  try {
    await api(`/api/v1/ai-video-tasks/${encodeURIComponent(selectedAiVideoTaskId)}/garments?itemId=${encodeURIComponent(itemId)}`, { method: "POST", body: formData });
    await selectAiVideoTask(selectedAiVideoTaskId);
  } catch (error) {
    input.disabled = false;
    document.querySelector("#video-task-action-note").textContent = error.message;
  } finally {
    input.value = "";
  }
});

document.querySelector("#garment-card-grid").addEventListener("input", event => {
  if (event.target.matches("[data-reference-role], [data-reference-group-instruction], [data-replacement-target], [data-replacement-scope]")) {
    const tile = event.target.closest("[data-reference-id]");
    const card = event.target.closest("[data-garment-card]");
    if (!card || !directReferenceItemIsEditable(card.dataset.itemId)) return;
    if (event.target.matches("[data-reference-group-instruction]")) {
      syncDirectReferenceGroupInstructionsToTiles(card);
      const counter = card.querySelector("[data-reference-group-count]");
      if (counter) counter.textContent = `${event.target.value.length.toLocaleString()} 字`;
    }
    if (tile && event.target.matches("[data-replacement-target], [data-replacement-scope]")) {
      syncDirectReferenceGroupInstructionsToTiles(card);
      const target = tile.querySelector("[data-replacement-target]")?.value || "";
      const scope = tile.querySelector("[data-replacement-scope]")?.value || "";
      const generated = standardReplacementInstruction(target, scope);
      if (generated) {
        const instruction = tile.querySelector("[data-reference-instruction]");
        if (instruction) instruction.value = generated;
        paintDirectReferenceGroupInstruction(card);
        const role = tile.querySelector("[data-reference-role]");
        if (role && !role.value) {
          role.value = scope === "GRAPHIC" ? "DETAIL" : "APPEARANCE";
          tile.classList.add("is-specified");
          tile.classList.remove("is-pending");
        }
      }
    }
    if (tile && event.target.matches("[data-reference-role]")) {
      tile.classList.toggle("is-specified", Boolean(event.target.value));
      tile.classList.toggle("is-pending", !event.target.value);
    }
    scheduleDirectReferenceAutoSave(card, event.target.matches("[data-reference-group-instruction]") ? 650 : 180);
    document.querySelector("#video-task-action-note").textContent = "参考图设置已修改，系统正在自动保存。";
    return;
  }
  const editor = event.target.closest("[data-video-prompt-editor]");
  const card = editor?.closest("[data-garment-card]");
  if (!editor || !card) return;
  aiVideoPromptDrafts.set(card.dataset.itemId, editor.value);
  const counter = card.querySelector(".video-prompt-label em");
  if (counter) counter.textContent = `${editor.value.length.toLocaleString()} / 20,000`;
});

document.querySelector("#garment-card-grid").addEventListener("change", event => {
  if (!event.target.matches("[data-reference-primary]")) return;
  const card = event.target.closest("[data-garment-card]");
  if (!card || !directReferenceItemIsEditable(card.dataset.itemId)) return;
  card?.querySelectorAll("[data-reference-id]").forEach(tile => {
    tile.classList.toggle("is-primary", Boolean(tile.querySelector("[data-reference-primary]")?.checked));
  });
  scheduleDirectReferenceAutoSave(card, 120);
  document.querySelector("#video-task-action-note").textContent = "主参考已更改，系统正在自动保存。";
});

document.querySelector("#garment-card-grid").addEventListener("wheel", event => {
  const editor = event.target.closest("[data-video-prompt-editor]");
  if (!editor) return;
  event.stopPropagation();
  const atTop = editor.scrollTop <= 0;
  const atBottom = editor.scrollTop + editor.clientHeight >= editor.scrollHeight - 1;
  if ((event.deltaY < 0 && atTop) || (event.deltaY > 0 && atBottom)) event.preventDefault();
}, { passive: false });

document.querySelector("#garment-card-grid").addEventListener("click", async event => {
  const card = event.target.closest("[data-garment-card]");
  const itemId = card?.dataset.itemId;
  if (event.target.closest("[data-item-copy]") && itemId) { openVideoGroupCopy(itemId); return; }
  const cardExpand = event.target.closest("[data-card-expand]");
  if (cardExpand && card && itemId) {
    if (expandedAiVideoItemIds.has(itemId)) {
      expandedAiVideoItemIds.delete(itemId);
      flippedAiVideoPromptItems.delete(itemId);
    } else {
      expandedAiVideoItemIds.add(itemId);
    }
    renderAiVideoTaskDetail({ changedItemIds: new Set([itemId]) });
    document.querySelector(`[data-garment-card][data-item-id="${CSS.escape(itemId)}"] [data-card-expand]`)?.focus();
    return;
  }
  const providerTaskCopy = event.target.closest("[data-provider-task-copy]");
  if (providerTaskCopy) {
    const copied = await copyTextToClipboard(String(providerTaskCopy.dataset.providerTaskCopy || ""));
    const label = providerTaskCopy.querySelector("span");
    if (label) label.textContent = copied ? "已复制" : "复制失败";
    providerTaskCopy.classList.toggle("copied", copied);
    window.setTimeout(() => {
      if (label) label.textContent = "复制 ID";
      providerTaskCopy.classList.remove("copied");
    }, 1600);
    return;
  }
  const referenceMove = event.target.closest("[data-reference-move]");
  if (referenceMove && card) {
    if (!directReferenceItemIsEditable(itemId)) return;
    syncDirectReferenceGroupInstructionsToTiles(card);
    const tile = referenceMove.closest("[data-reference-id]");
    const sibling = referenceMove.dataset.referenceMove === "up" ? tile?.previousElementSibling : tile?.nextElementSibling;
    if (!tile || !sibling?.matches?.("[data-reference-id]")) return;
    if (referenceMove.dataset.referenceMove === "up") tile.parentElement.insertBefore(tile, sibling);
    else tile.parentElement.insertBefore(sibling, tile);
    renumberDirectReferenceTiles(card);
    paintDirectReferenceGroupInstruction(card);
    scheduleDirectReferenceAutoSave(card, 120);
    return;
  }
  const referenceDelete = event.target.closest("[data-reference-delete]");
  if (referenceDelete && card && itemId && selectedAiVideoTaskId) {
    if (!directReferenceItemIsEditable(itemId)) return;
    const tile = referenceDelete.closest("[data-reference-id]");
    const referenceId = tile?.dataset.referenceId;
    if (!referenceId) return;
    const groupReferences = card.querySelectorAll("[data-reference-id]").length;
    const message = groupReferences === 1
      ? "这是该组最后一张参考图，删除后整个视频组也会移除。确认删除？"
      : "确认删除这张参考图？删除后其余图片会自动重新编号。";
    if (!window.confirm(message)) return;
    referenceDelete.disabled = true;
    document.querySelector("#video-task-action-note").textContent = "正在删除参考图并重新编号…";
    try {
      await api(`/api/v1/ai-video-tasks/${encodeURIComponent(selectedAiVideoTaskId)}/items/${encodeURIComponent(itemId)}/references/${encodeURIComponent(referenceId)}`, { method: "DELETE" });
      await selectAiVideoTask(selectedAiVideoTaskId);
      document.querySelector("#video-task-action-note").textContent = groupReferences === 1
        ? "视频组已删除。"
        : "参考图已删除，其余图片已重新编号；如删除的是主参考，请重新指定。";
    } catch (error) {
      referenceDelete.disabled = false;
      document.querySelector("#video-task-action-note").textContent = error.message;
    }
    return;
  }
  const promptCopy = event.target.closest("[data-prompt-copy]");
  if (promptCopy && card) {
    const prompt = String(card.querySelector("[data-video-prompt-editor]")?.value || "").trim();
    if (!prompt) return;
    const copied = await copyTextToClipboard(prompt);
    const label = promptCopy.querySelector("span");
    const originalLabel = label?.textContent || "一键复制";
    if (label) label.textContent = copied ? "已复制" : "复制失败";
    promptCopy.classList.toggle("copied", copied);
    window.setTimeout(() => {
      if (label) label.textContent = originalLabel;
      promptCopy.classList.remove("copied");
    }, 1600);
    return;
  }
  const directDownload = event.target.closest("[data-download-asset]");
  if (directDownload) {
    await downloadImageAsset(
      directDownload,
      String(directDownload.dataset.url || ""),
      String(directDownload.dataset.filename || "赫逸图片")
    );
    return;
  }
  const rebuildStoryboard = event.target.closest("[data-regenerate-storyboard]");
  if (rebuildStoryboard && itemId && selectedAiVideoTaskId) {
    rebuildStoryboard.disabled = true;
    rebuildStoryboard.textContent = "提交中…";
    try {
      await api(`/api/v1/ai-video-tasks/${encodeURIComponent(selectedAiVideoTaskId)}/items/${encodeURIComponent(itemId)}/regenerate-storyboard`, { method: "POST" });
      await selectAiVideoTask(selectedAiVideoTaskId);
    } catch (error) {
      rebuildStoryboard.disabled = false;
      rebuildStoryboard.textContent = "重建";
      window.alert(error.message);
    }
    return;
  }
  const rebuildFirstFrame = event.target.closest("[data-regenerate-first-frame]");
  if (rebuildFirstFrame && itemId && selectedAiVideoTaskId) {
    const confirmed = window.confirm("将使用脚本第 01 分镜真实画面重新生成首帧，并同步重建九宫格。本次会产生 2 次图片模型调用，是否继续？");
    if (!confirmed) return;
    rebuildFirstFrame.disabled = true;
    rebuildFirstFrame.textContent = "提交中…";
    try {
      await api(`/api/v1/ai-video-tasks/${encodeURIComponent(selectedAiVideoTaskId)}/items/${encodeURIComponent(itemId)}/regenerate-first-frame`, { method: "POST" });
      await selectAiVideoTask(selectedAiVideoTaskId);
    } catch (error) {
      rebuildFirstFrame.disabled = false;
      rebuildFirstFrame.textContent = "重做首帧";
      window.alert(error.message);
    }
    return;
  }
  if (event.target.closest("[data-card-flip]") && card && itemId) {
    const flipped = card.classList.toggle("is-prompt-flipped");
    card.querySelector(".garment-card-front")?.setAttribute("aria-hidden", String(flipped));
    card.querySelector(".garment-card-back")?.setAttribute("aria-hidden", String(!flipped));
    if (flipped) flippedAiVideoPromptItems.add(itemId);
    else flippedAiVideoPromptItems.delete(itemId);
    return;
  }
  if (event.target.closest("[data-prompt-edit]") && itemId) {
    if (!directReferenceItemIsEditable(itemId)) return;
    const item = aiVideoTaskDetail?.items?.find(value => value.id === itemId);
    aiVideoPromptDrafts.set(itemId, item?.videoPromptText || "");
    flippedAiVideoPromptItems.add(itemId);
    renderAiVideoTaskDetail();
    document.querySelector(`[data-item-id="${CSS.escape(itemId)}"] [data-video-prompt-editor]`)?.focus();
    return;
  }
  if (event.target.closest("[data-prompt-cancel]") && itemId) {
    aiVideoPromptDrafts.delete(itemId);
    renderAiVideoTaskDetail();
    return;
  }
  const save = event.target.closest("[data-prompt-save]");
  if (save && itemId && selectedAiVideoTaskId) {
    if (!directReferenceItemIsEditable(itemId)) return;
    const prompt = String(aiVideoPromptDrafts.get(itemId) || "").trim();
    if (!prompt) {
      window.alert("视频提示词不能为空");
      return;
    }
    save.disabled = true;
    save.textContent = "正在保存…";
    try {
      const result = await api(`/api/v1/ai-video-tasks/${encodeURIComponent(selectedAiVideoTaskId)}/items/${encodeURIComponent(itemId)}/video-prompt`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt })
      });
      const index = aiVideoTaskDetail.items.findIndex(item => item.id === itemId);
      if (index >= 0) aiVideoTaskDetail.items[index] = result.data;
      aiVideoPromptDrafts.delete(itemId);
      flippedAiVideoPromptItems.add(itemId);
      renderAiVideoTaskDetail();
    } catch (error) {
      save.disabled = false;
      save.textContent = "保存修改";
      window.alert(error.message);
    }
    return;
  }
  const button = event.target.closest("[data-asset-preview]");
  if (!button) return;
  openAssetPreview(button);
});

function openAssetPreview(button) {
  const previewCard = button.closest("[data-garment-card]");
  const title = previewCard?.querySelector(".garment-card-copy strong")?.textContent
    || (button.closest("#product-image-grid") ? document.querySelector("#product-detail-title").textContent : "生成图片");
  const previewImage = button.querySelector("img");
  const previewVideo = button.querySelector("video");
  const singleImage = previewImage && button.querySelectorAll("img").length === 1;
  const preview = previewVideo ? document.createElement("video")
    : singleImage ? document.createElement("div") : button.cloneNode(true);
  if (!previewVideo && singleImage) {
    // Do not inherit thumbnail grid columns, overlays, or an existing zoom.
    preview.className = "asset-preview-image real-asset";
    const image = document.createElement("img");
    image.src = previewImage.currentSrc || previewImage.src;
    image.alt = previewImage.alt || button.dataset.assetPreview || "图片预览";
    if (previewImage.dataset.fullSrc) image.dataset.fullSrc = previewImage.dataset.fullSrc;
    preview.append(image);
  }
  if (previewVideo) {
    preview.className = "asset-preview-video";
    preview.src = previewVideo.currentSrc || previewVideo.src;
    preview.controls = true;
    preview.autoplay = false;
    preview.playsInline = true;
    preview.preload = "metadata";
  } else {
    preview.removeAttribute("data-asset-preview");
    preview.removeAttribute("aria-label");
    preview.setAttribute("tabindex", "-1");
    preview.querySelectorAll("img").forEach(image => {
      image.removeAttribute("loading");
      const fullSource = image.dataset.fullSrc;
      if (!fullSource || fullSource === image.getAttribute("src")) return;
      // Keep the already painted thumbnail visible. Switching src before the 4K
      // OSS image is decoded creates a blank frame and makes the dialog flash.
      const fullResolutionImage = new Image();
      fullResolutionImage.decoding = "async";
      fullResolutionImage.src = fullSource;
      const revealFullResolution = () => {
        if (!image.isConnected) return;
        image.src = fullSource;
        image.classList.add("full-resolution-ready");
      };
      if (typeof fullResolutionImage.decode === "function") {
        fullResolutionImage.decode().then(revealFullResolution).catch(() => {});
      } else {
        fullResolutionImage.addEventListener("load", revealFullResolution, { once: true });
      }
    });
  }
  const outputSlot = button.closest(".garment-output-slot");
  if (outputSlot?.classList.contains("completed")) preview.classList.add("preview-completed");
  if (outputSlot?.classList.contains("processing")) preview.classList.add("preview-processing");
  const download = document.querySelector("#asset-preview-download");
  const downloadUrl = previewImage?.dataset.fullSrc || previewImage?.currentSrc || previewImage?.src
    || previewVideo?.currentSrc || previewVideo?.src || "";
  download.hidden = !downloadUrl;
  download.disabled = false;
  download.textContent = previewVideo ? "↓ 下载视频" : "↓ 下载图片";
  download.dataset.url = downloadUrl;
  download.dataset.filename = `${title}-${button.dataset.assetPreview}`;
  document.querySelector("#asset-preview-kind").textContent = button.dataset.assetPreview;
  document.querySelector("#asset-preview-title").textContent = title;
  const previewBody = document.querySelector("#asset-preview-body");
  const previewDialog = document.querySelector("#asset-preview-dialog");
  previewBody.classList.toggle("is-video", Boolean(previewVideo));
  previewDialog.classList.toggle("is-video", Boolean(previewVideo));
  previewBody.replaceChildren(preview);
  previewDialog.showModal();
}

document.querySelector("#product-image-grid").addEventListener("click", event => {
  const button = event.target.closest("[data-asset-preview]");
  if (button) openAssetPreview(button);
});

document.querySelector("#asset-preview-download").addEventListener("click", async event => {
  const button = event.currentTarget;
  const url = String(button.dataset.url || "");
  await downloadImageAsset(button, url, String(button.dataset.filename || "赫逸图片"));
});

async function downloadImageAsset(button, url, requestedFilename) {
  if (!url || button.disabled) return;
  const idleText = button.textContent;
  button.disabled = true;
  button.textContent = "正在下载…";
  try {
    const filename = String(requestedFilename || "赫逸图片")
      .replace(/[\\/:*?"<>|]+/g, "-")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 80) || "赫逸图片";
    const parsedUrl = new URL(url, window.location.href);
    const platformApi = parsedUrl.origin === window.location.origin && parsedUrl.pathname.startsWith("/api/");
    if (platformApi) {
      parsedUrl.searchParams.set("download", "true");
      parsedUrl.searchParams.set("filename", filename);
    }
    const anchor = document.createElement("a");
    // Keep external signed URLs byte-for-byte intact; their host controls download headers.
    anchor.href = platformApi ? parsedUrl.href : url;
    anchor.download = filename;
    anchor.target = "_blank";
    anchor.rel = "noopener";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    button.textContent = "✓ 已开始下载";
    window.setTimeout(() => {
      if (!button.disabled) button.textContent = idleText;
    }, 1_500);
  } catch (error) {
    button.textContent = "下载失败，重试";
    window.alert(error.message);
  } finally {
    button.disabled = false;
  }
}

function enableAssetWheelZoom(container) {
  container.addEventListener("wheel", event => {
    const viewer = event.target.closest(".real-asset, .real-assets");
    const image = viewer?.querySelector("img");
    if (!viewer || !image) return;
    const current = Number(viewer.dataset.wheelZoom || 1);
    if (current <= 1 && event.deltaY > 0) return;
    event.preventDefault();
    const next = Math.max(1, Math.min(3.5, current + (event.deltaY < 0 ? .25 : -.25)));
    const bounds = viewer.getBoundingClientRect();
    const x = Math.max(0, Math.min(100, ((event.clientX - bounds.left) / bounds.width) * 100));
    const y = Math.max(0, Math.min(100, ((event.clientY - bounds.top) / bounds.height) * 100));
    viewer.dataset.wheelZoom = String(next);
    image.style.transformOrigin = `${x}% ${y}%`;
    image.style.transform = next === 1 ? "" : `scale(${next})`;
    viewer.classList.toggle("wheel-zoomed", next > 1);
  }, { passive: false });
  container.addEventListener("mousemove", event => {
    const viewer = event.target.closest(".real-asset, .real-assets");
    const image = viewer?.querySelector("img");
    if (!viewer || !image || Number(viewer.dataset.wheelZoom || 1) <= 1) return;
    const bounds = viewer.getBoundingClientRect();
    image.style.transformOrigin = `${((event.clientX - bounds.left) / bounds.width) * 100}% ${((event.clientY - bounds.top) / bounds.height) * 100}%`;
  });
  container.addEventListener("dblclick", event => {
    const viewer = event.target.closest(".real-asset, .real-assets");
    const image = viewer?.querySelector("img");
    if (!viewer || !image) return;
    event.preventDefault();
    viewer.dataset.wheelZoom = "1";
    viewer.classList.remove("wheel-zoomed");
    image.style.transform = "";
  });
}

enableAssetWheelZoom(document.querySelector("#garment-card-grid"));
enableAssetWheelZoom(document.querySelector("#asset-preview-body"));

async function startAiVideoGeneration(button, retryFailed = false) {
  if (!selectedAiVideoTaskId) return;
  const directReferenceVideo = Boolean(aiVideoTaskDetail?.task?.directReferenceVideo);
  if (directReferenceVideo && !retryFailed) {
    const itemIds = new Set(aiVideoTaskDetail.items.map(item => item.id));
    const saving = [...directReferenceAutoSaveStates.entries()]
      .some(([itemId, state]) => itemIds.has(itemId) && ["pending", "saving"].includes(state));
    if (saving) {
      document.querySelector("#video-task-action-note").textContent = "参考图设置正在自动保存，请稍等一秒后再提交。";
      return;
    }
    const failedSave = [...directReferenceAutoSaveStates.entries()]
      .some(([itemId, state]) => itemIds.has(itemId) && state === "failed");
    if (failedSave) {
      document.querySelector("#video-task-action-note").textContent = "参考图设置保存失败，请修改任一设置触发重新保存后再提交。";
      return;
    }
    const incomplete = aiVideoTaskDetail.items.find(item =>
      !taskReferencesConfigured(aiVideoTaskDetail, item.id));
    if (incomplete) {
      const references = directReferenceAssets(aiVideoTaskDetail, incomplete.id);
      const primaryCount = references.filter(reference => reference.primaryReference).length;
      document.querySelector("#video-task-action-note").textContent = !references.length
        ? `${incomplete.name} 还没有参考图，请先上传。`
        : references.some(reference => !reference.referenceRole)
          ? `${incomplete.name} 有图片尚未指定用途，请先选择“商品外观、动作、场景”等职责。`
          : aiVideoTaskDetail.task.useScriptReferenceVideo && references.some(reference => !String(reference.referenceInstruction || "").trim())
            ? `${incomplete.name} 有对象图尚未填写替换目标，请说明它要替换原视频中的哪个对象。`
          : primaryCount === 0
            ? `${incomplete.name} 尚未设置主参考图，请先勾选“设为主参考”。`
            : `${incomplete.name} 只能设置一张主参考图，请保留一张。`;
      document.querySelector(`[data-garment-card][data-item-id="${CSS.escape(incomplete.id)}"]`)?.scrollIntoView({ behavior: "smooth", block: "center" });
      return;
    }
  }
  button.disabled = true;
  button.textContent = retryFailed ? "正在重新提交失败任务…" : "正在启动…";
  document.querySelector("#video-task-action-note").textContent = retryFailed
    ? "正在重新提交失败视频；已经完成的成片会原样保留。"
    : directReferenceVideo
      ? "正在按参考图 1–9 的编号顺序直接提交视频模型，不调用图片模型…"
      : "正在启动一键视频生成；系统会先补齐所需图片，再自动提交视频模型…";
  const optimisticImageStart = !retryFailed
    && !directReferenceVideo
    && aiVideoTaskDetail?.task
    && !["IMAGE_READY", "VIDEO_GENERATING", "VIDEO_READY"].includes(aiVideoTaskDetail.task.status);
  if (optimisticImageStart) {
    // Give immediate visual acknowledgement while the start request is in flight.
    // The next detail fetch always replaces this with authoritative server state.
    aiVideoTaskDetail.task.status = "IMAGE_GENERATING";
    aiVideoTaskDetail.task.progress = Math.max(1, Number(aiVideoTaskDetail.task.progress || 0));
    renderAiVideoTaskDetail();
  }
  if (retryFailed && aiVideoTaskDetail?.task) {
    const submittedAt = new Date().toISOString();
    aiVideoTaskDetail.items.forEach(item => {
      if (item.status !== "VIDEO_FAILED") return;
      item.status = "VIDEO_SUBMITTING";
      item.progress = 1;
      item.errorMessage = null;
      item.videoSubmittedAt = submittedAt;
    });
    aiVideoTaskDetail.task.status = "VIDEO_GENERATING";
    aiVideoTaskDetail.task.errorMessage = null;
    aiVideoTaskDetail.videoSubmittedAt ||= submittedAt;
    renderAiVideoTaskDetail();
  }
  try {
    await api(`/api/v1/ai-video-tasks/${encodeURIComponent(selectedAiVideoTaskId)}/start-generation`, { method: "POST" });
    await selectAiVideoTask(selectedAiVideoTaskId);
  } catch (error) {
    if (retryFailed || optimisticImageStart) await selectAiVideoTask(selectedAiVideoTaskId);
    document.querySelector("#video-task-action-note").textContent = error.message;
    button.disabled = false;
    button.textContent = retryFailed ? "↻ 重试失败任务" : directReferenceVideo ? "提交参考图生成视频" : "开始批量生成视频";
  }
}

document.querySelector("#generate-shot-images").addEventListener("click", event => {
  startAiVideoGeneration(event.currentTarget);
});

document.querySelector("#retry-failed-videos").addEventListener("click", event => {
  startAiVideoGeneration(event.currentTarget, true);
});

const generalVideoStatusNames = {
  DRAFT: "待提交",
  READY: "等待生成",
  QUEUED: "排队中",
  SUBMITTING: "正在提交",
  VIDEO_GENERATING: "生成中",
  RUNNING: "生成中",
  VIDEO_READY: "已完成",
  COMPLETED: "已完成",
  SUCCEEDED: "已完成",
  FAILED: "生成失败",
  VIDEO_FAILED: "生成失败"
};

function generalVideoStatusGroup(status = "") {
  if (["VIDEO_READY", "COMPLETED", "SUCCEEDED"].includes(status)) return "completed";
  if (["FAILED", "VIDEO_FAILED"].includes(status)) return "failed";
  if (["VIDEO_GENERATING", "RUNNING", "SUBMITTING", "QUEUED"].includes(status)) return "running";
  return "waiting";
}

function generalVideoTaskPayload(data) {
  return data?.task || data || {};
}

function generalVideoReferenceLimit() {
  return 9;
}

function renderGeneralVideoReferences() {
  const list = document.querySelector("#general-reference-list");
  const mentions = document.querySelector("#general-reference-mentions");
  const limit = generalVideoReferenceLimit();
  const full = generalVideoReferences.length >= limit;
  document.querySelector("#general-reference-count").textContent = `${generalVideoReferences.length} / ${limit}`;
  const drop = document.querySelector("#general-reference-drop");
  const input = document.querySelector("#general-reference-input");
  drop.classList.toggle("is-full", full);
  drop.setAttribute("aria-disabled", String(full || !hasAnyPermission("video:task:manage")));
  input.disabled = full || !hasAnyPermission("video:task:manage");
  if (!generalVideoReferences.length) {
    list.hidden = true;
    list.innerHTML = "";
    mentions.innerHTML = "";
    return;
  }
  list.hidden = false;
  list.innerHTML = generalVideoReferences.map((reference, index) => `
    <article class="general-reference-card" data-general-reference-id="${escapeHtml(reference.id)}">
      <img src="${reference.previewUrl}" alt="${escapeHtml(reference.file.name)}">
      <div><strong translate="no">${escapeHtml(reference.file.name)}</strong><small>${(reference.file.size / 1024 / 1024).toFixed(1)} MB · 参考图 ${index + 1}</small></div>
      <button type="button" data-general-reference-remove aria-label="移除 ${escapeHtml(reference.file.name)}">×</button>
    </article>`).join("");
  mentions.innerHTML = generalVideoReferences.map(reference => `<button type="button" data-general-mention="图片${generalVideoReferences.indexOf(reference) + 1}" title="${escapeHtml(reference.file.name)}">@图片${generalVideoReferences.indexOf(reference) + 1}</button>`).join("");
}

function syncGeneralVideoRouteResolution() {
  const speedSelected = document.querySelector('#general-video-form input[name="videoRoute"]:checked')?.value === "SPEED";
  const qualityInput = document.querySelector('#general-video-form input[name="videoRoute"][value="QUALITY"]');
  const speedInput = document.querySelector('#general-video-form input[name="videoRoute"][value="SPEED"]');
  const resolution720 = document.querySelector('#general-video-form input[name="resolution"][value="720p"]');
  const resolution1080 = document.querySelector('#general-video-form input[name="resolution"][value="1080p"]');
  if (speedSelected && resolution1080.checked) resolution720.checked = true;
  resolution1080.disabled = speedSelected;
  resolution1080.parentElement.title = speedSelected ? "极速线路固定输出 768P；1080p 请使用质量线路" : "";
  qualityInput.parentElement.title = "";
  speedInput.disabled = Boolean(generalVideoReference);
  speedInput.parentElement.title = generalVideoReference ? "视频参考使用质量线路" : "极速线路固定输出 768P";
}

function addGeneralVideoReferences(files) {
  const error = document.querySelector("#general-reference-error");
  if (!hasAnyPermission("video:task:manage")) {
    error.textContent = "当前账号没有创建视频任务的权限。";
    error.hidden = false;
    return;
  }
  const selectedFiles = [...files];
  const supportedImage = file => file.type.startsWith("image/") || /\.(?:jpe?g|png|webp|gif)$/i.test(file.name);
  if (selectedFiles.some(file => !supportedImage(file))) {
    error.textContent = "仅支持 JPG、PNG、WEBP 或 GIF 图片。";
    error.hidden = false;
    return;
  }
  const candidates = selectedFiles;
  const oversized = candidates.find(file => file.size > 20 * 1024 * 1024);
  if (oversized) {
    error.textContent = `${oversized.name} 超过 20 MB，请压缩后重试。`;
    error.hidden = false;
    return;
  }
  const existing = new Set(generalVideoReferences.map(reference => `${reference.file.name}:${reference.file.size}:${reference.file.lastModified}`));
  const unique = candidates.filter(file => !existing.has(`${file.name}:${file.size}:${file.lastModified}`));
  const selectedBytes = generalVideoReferences.reduce((total, reference) => total + reference.file.size, generalVideoReference?.file.size || 0);
  const incomingBytes = unique.reduce((total, file) => total + file.size, 0);
  if (selectedBytes + incomingBytes > 90 * 1024 * 1024) {
    error.textContent = "参考素材合计不能超过 90 MB。";
    error.hidden = false;
    return;
  }
  const available = Math.max(0, generalVideoReferenceLimit() - generalVideoReferences.length);
  unique.slice(0, available).forEach(file => generalVideoReferences.push({
    id: globalThis.crypto?.randomUUID?.() || `ref-${Date.now()}-${Math.random()}`,
    file,
    previewUrl: URL.createObjectURL(file)
  }));
  error.textContent = unique.length > available ? `最多上传 ${generalVideoReferenceLimit()} 张图片。` : "";
  error.hidden = !error.textContent;
  renderGeneralVideoReferences();
}

function clearGeneralVideoReferences() {
  generalVideoReferences.forEach(reference => URL.revokeObjectURL(reference.previewUrl));
  generalVideoReferences = [];
  if (generalVideoReference) URL.revokeObjectURL(generalVideoReference.previewUrl);
  generalVideoReference = null;
  renderGeneralVideoReference();
  document.querySelector("#general-reference-input").value = "";
  renderGeneralVideoReferences();
}

function insertGeneralVideoMention(fileName) {
  const input = document.querySelector("#general-video-prompt");
  const mention = `@${fileName} `;
  const start = generalMentionStart ?? input.selectionStart ?? input.value.length;
  const end = input.selectionEnd ?? start;
  generalMentionStart = null;
  document.querySelector("#general-mention-menu").hidden = true;
  if (input.value.length - (end - start) + mention.length > 20000) return;
  input.setRangeText(mention, start, end, "end");
  input.dispatchEvent(new Event("input", { bubbles: true }));
  input.focus();
}

function generalVideoDetailAssets(detail) {
  return Array.isArray(detail?.assets) ? detail.assets : [];
}

function generalVideoAssetType(asset) {
  return String(asset?.assetType || asset?.type || "").toUpperCase();
}

function generalVideoTaskCard(task) {
  const expanded = expandedGeneralVideoTaskIds.has(task.id);
  const detail = generalVideoTaskDetails.get(task.id);
  const state = generalVideoStatusGroup(task.status);
  const detailTask = generalVideoTaskPayload(detail);
  const assets = generalVideoDetailAssets(detail);
  const video = assets.find(asset => generalVideoAssetType(asset) === "VIDEO_RESULT");
  const references = assets.filter(asset => asset !== video && String(asset.contentType || "").startsWith("image/"));
  const duration = task.durationSeconds || detailTask.durationSeconds || 5;
  const aspectRatio = task.aspectRatio || detailTask.aspectRatio || "—";
  const resolution = task.resolution || detailTask.resolution || "—";
  const prompt = task.prompt || detailTask.prompt || detail?.items?.[0]?.videoPromptText || "";
  const progress = Math.max(0, Math.min(100, Number(task.progress || detailTask.progress || 0)));
  const expandedBody = !expanded ? "" : !detail ? `<div class="general-task-detail-loading"><i></i><span>正在读取生成详情…</span></div>` : `
    <section class="general-task-expanded">
      ${detail.loadError ? `<div class="general-video-result-waiting"><span aria-hidden="true">!</span><strong>详情读取失败</strong><small>${escapeHtml(detail.loadError)}</small></div>` : video ? `<video src="${generalVideoAssetUrl(task.id, video)}" controls preload="metadata" playsinline></video>` : `<div class="general-video-result-waiting"><span aria-hidden="true">${state === "failed" ? "!" : "▶"}</span><strong>${state === "failed" ? escapeHtml(task.errorMessage || detailTask.errorMessage || "生成失败") : "视频尚未完成"}</strong><small>${state === "running" ? `后台生成中 · ${progress}%` : "任务进入线路后，成片会显示在这里"}</small></div>`}
      ${references.length ? `<div class="general-task-references"><span>本次参考</span><div>${references.slice(0, 9).map((asset, index) => `<button type="button" data-asset-preview="参考图 ${index + 1}" aria-label="放大查看参考图 ${index + 1}"><img src="${generalVideoAssetUrl(task.id, asset, true)}" data-full-src="${generalVideoAssetUrl(task.id, asset)}" alt="参考图 ${index + 1}" loading="lazy"></button>`).join("")}</div></div>` : ""}
      ${assets.filter(asset => generalVideoAssetType(asset) === "VIDEO_REFERENCE_VIDEO").map(asset => `<div class="general-task-reference-video"><span>参考视频 · @视频1</span><video src="${generalVideoAssetUrl(task.id, asset)}" controls preload="metadata" playsinline></video></div>`).join("")}
      <div class="general-task-prompt"><span>生成描述</span><p>${escapeHtml(prompt || "未记录提示词")}</p></div>
      ${["DRAFT", "READY", "FAILED"].includes(task.status) && hasAnyPermission("video:task:manage") ? `<button class="general-task-start" type="button" data-general-task-start>${task.status === "FAILED" ? "重新生成" : "开始生成"} <span aria-hidden="true">→</span></button>` : ""}
    </section>`;
  return `<article class="general-video-task-card ${expanded ? "is-expanded" : ""}" data-general-task-id="${escapeHtml(task.id)}">
    <div class="general-task-cover" aria-hidden="true"><span>${aspectRatio}</span><i style="--progress:${progress}%"></i><strong>${state === "completed" ? "PLAY" : state === "running" ? `${progress}%` : "VIDEO"}</strong></div>
    <header><div><span>${task.taskType === "GENERAL" ? "通用生成" : "视频任务"} · ${formatDate(task.updatedAt || task.createdAt)}</span><h3 translate="${task.name ? 'no' : 'yes'}">${escapeHtml(task.name || "未命名任务")}</h3></div><em class="${state}">${generalVideoStatusNames[task.status] || escapeHtml(task.status || "待提交")}</em></header>
    <dl><div><dt>比例</dt><dd>${escapeHtml(aspectRatio)}</dd></div><div><dt>清晰度</dt><dd>${escapeHtml(resolution)}</dd></div><div><dt>时长</dt><dd>${escapeHtml(duration)} 秒</dd></div></dl>
    <button class="general-task-expand" type="button" data-general-task-expand aria-expanded="${expanded}"><span>${expanded ? "收起详情" : "查看详情"}</span><i aria-hidden="true">⌄</i></button>
    ${expandedBody}
  </article>`;
}

function renderGeneralVideoTasks() {
  const grid = document.querySelector("#general-video-task-grid");
  if (!generalVideoTasks.length) {
    IncrementalDOM.html(grid, `<div class="general-video-empty"><strong>还没有通用生成任务</strong><span>上传参考图并写下画面描述，第一条任务会出现在这里。</span></div>`);
    return;
  }
  IncrementalDOM.html(grid, generalVideoTasks.map(generalVideoTaskCard).join(""));
}

async function loadGeneralVideoTaskDetail(id, { silent = false } = {}) {
  if (!silent) renderGeneralVideoTasks();
  try {
    const result = await generalVideoApi(`/${encodeURIComponent(id)}`);
    if (!expandedGeneralVideoTaskIds.has(id)) return;
    generalVideoTaskDetails.set(id, result.data);
    const task = generalVideoTaskPayload(result.data);
    const index = generalVideoTasks.findIndex(item => item.id === id);
    if (index >= 0 && task.id) generalVideoTasks[index] = { ...generalVideoTasks[index], ...task };
    renderGeneralVideoTasks();
  } catch (error) {
    if (!expandedGeneralVideoTaskIds.has(id)) return;
    generalVideoTaskDetails.set(id, { task: generalVideoTasks.find(task => task.id === id), loadError: error.message, assets: [] });
    renderGeneralVideoTasks();
  }
}

async function loadGeneralVideoTasks(options = {}) {
  const generation = ++generalVideoLoadGeneration;
  const organizationId = currentUser?.organizationId || "";
  const grid = document.querySelector("#general-video-task-grid");
  if (!options.silent) grid.innerHTML = `<div class="general-video-empty">正在读取任务…</div>`;
  try {
    const result = await generalVideoApi();
    if (generation !== generalVideoLoadGeneration || currentUser?.organizationId !== organizationId) return;
    const payload = Array.isArray(result.data) ? result.data : result.data?.tasks || [];
    generalVideoTasks = payload.filter(task => !task.organizationId || task.organizationId === organizationId);
    const availableIds = new Set(generalVideoTasks.map(task => task.id));
    [...expandedGeneralVideoTaskIds].forEach(id => { if (!availableIds.has(id)) expandedGeneralVideoTaskIds.delete(id); });
    [...generalVideoTaskDetails.keys()].forEach(id => { if (!availableIds.has(id)) generalVideoTaskDetails.delete(id); });
    renderGeneralVideoTasks();
    await Promise.all([...expandedGeneralVideoTaskIds].map(id => loadGeneralVideoTaskDetail(id, { silent: true })));
    syncGeneralVideoPolling();
  } catch (error) {
    if (generation !== generalVideoLoadGeneration || currentUser?.organizationId !== organizationId) return;
    if (!options.silent || !generalVideoTasks.length) grid.innerHTML = `<div class="general-video-empty"><strong>任务读取失败</strong><span>${escapeHtml(error.message)}</span></div>`;
  }
}

function syncGeneralVideoPolling() {
  if (generalVideoPollTimer) clearInterval(generalVideoPollTimer);
  generalVideoPollTimer = null;
  if (document.querySelector("#general-video-page").hidden) return;
  const active = generalVideoTasks.some(task => ["QUEUED", "SUBMITTING", "VIDEO_GENERATING", "RUNNING"].includes(task.status));
  if (!active) return;
  generalVideoPollTimer = setInterval(() => {
    if (generalVideoPollInFlight || document.hidden || document.querySelector("#general-video-page").hidden) return;
    generalVideoPollInFlight = true;
    loadGeneralVideoTasks({ silent: true }).finally(() => { generalVideoPollInFlight = false; });
  }, 8000);
}

document.querySelector("#general-reference-input").addEventListener("change", event => {
  addGeneralVideoReferences(event.currentTarget.files || []);
  event.currentTarget.value = "";
});

document.querySelector("#general-reference-list").addEventListener("click", event => {
  const button = event.target.closest("[data-general-reference-remove]");
  if (!button) return;
  const card = button.closest("[data-general-reference-id]");
  const index = generalVideoReferences.findIndex(reference => reference.id === card?.dataset.generalReferenceId);
  if (index < 0) return;
  URL.revokeObjectURL(generalVideoReferences[index].previewUrl);
  const prompt = document.querySelector("#general-video-prompt");
  prompt.value = prompt.value.replace(/@图片(\d+)(?!\d)/g, (token, number) => {
    const n = Number(number);
    return n === index + 1 ? "" : n > index + 1 ? `@图片${n - 1}` : token;
  });
  prompt.dispatchEvent(new Event("input", { bubbles: true }));
  generalVideoReferences.splice(index, 1);
  renderGeneralVideoReferences();
});

document.querySelector("#general-reference-mentions").addEventListener("click", event => {
  const button = event.target.closest("[data-general-mention]");
  if (button) insertGeneralVideoMention(button.dataset.generalMention);
});

function renderGeneralVideoReference() {
  const preview = document.querySelector("#general-video-reference-preview");
  const input = document.querySelector("#general-video-reference-input");
  input.disabled = Boolean(generalVideoReference) || !hasAnyPermission("video:task:manage");
  document.querySelector("#general-video-reference-count").textContent = generalVideoReference ? "1 / 1" : "0 / 1";
  preview.hidden = !generalVideoReference;
  preview.innerHTML = generalVideoReference ? `<div class="general-clip-media"><video src="${generalVideoReference.previewUrl}" controls preload="metadata" playsinline></video></div><div class="general-clip-editor"><header><div><strong translate="no">${escapeHtml(generalVideoReference.file.name)}</strong><small>拖动首尾端点，选择最多 15 秒的视频参考</small></div><button type="button" data-general-video-remove aria-label="移除参考视频">×</button></header><div class="general-clip-range video-clip-range"><span class="video-clip-range-track"><i></i></span><input type="range" data-clip-start min="0" max="0" step="0.1" value="0" disabled aria-label="参考视频开始时间"><input type="range" data-clip-end min="0" max="0" step="0.1" value="0" disabled aria-label="参考视频结束时间"></div><div class="video-clip-endpoints"><label>开始 <output data-clip-start-time>00:00.0</output></label><label>结束 <output data-clip-end-time>--:--.-</output></label></div><footer><small data-clip-summary>正在读取视频时长…</small><button type="button" data-general-video-mention>@视频1</button><button type="button" data-clip-preview disabled>▶ 预览所选片段</button></footer></div>` : "";
  if (generalVideoReference) {
    const reference = generalVideoReference;
    const video = preview.querySelector("video");
    video.addEventListener("loadedmetadata", () => {
      if (reference !== generalVideoReference) return;
      if (!Number.isFinite(video.duration) || video.duration <= 0) { preview.querySelector('[data-clip-summary]').textContent = "无法读取视频时长，请更换文件"; return; }
      reference.duration = video.duration;
      reference.clipStart = 0;
      reference.clipEnd = Math.min(15, video.duration);
      preview.querySelectorAll('input[type="range"]').forEach(input => {input.max = String(video.duration);input.disabled = false;});
      preview.querySelector('[data-clip-preview]').disabled = false;
      paintGeneralVideoClip();
    });
    video.addEventListener("timeupdate", () => {
      if (reference === generalVideoReference && reference.previewing && video.currentTime >= reference.clipEnd) {video.pause();reference.previewing = false;}
    });
    video.addEventListener("error", () => {if(reference === generalVideoReference) {reference.duration = null;preview.querySelector('[data-clip-summary]').textContent = "浏览器无法预览此视频，请转换成 MP4 后上传";}});
  }
  syncGeneralVideoRouteResolution();
}

function paintGeneralVideoClip() {
  const r = generalVideoReference;
  if (!r?.duration) return;
  const root = document.querySelector("#general-video-reference-preview");
  root.querySelector('[data-clip-start]').value = String(r.clipStart);
  root.querySelector('[data-clip-end]').value = String(r.clipEnd);
  root.querySelector('[data-clip-start-time]').textContent = formatVideoScriptClipTime(r.clipStart);
  root.querySelector('[data-clip-end-time]').textContent = formatVideoScriptClipTime(r.clipEnd);
  root.querySelector('[data-clip-start]').setAttribute('aria-valuetext', formatVideoScriptClipTime(r.clipStart));
  root.querySelector('[data-clip-end]').setAttribute('aria-valuetext', formatVideoScriptClipTime(r.clipEnd));
  root.querySelector('.video-clip-range-track i').style.cssText = `left:${r.clipStart / r.duration * 100}%;width:${(r.clipEnd-r.clipStart)/r.duration*100}%`;
  root.querySelector('[data-clip-summary]').textContent = `已选 ${(r.clipEnd-r.clipStart).toFixed(1)} 秒 · 原视频 ${formatVideoScriptClipTime(r.duration)}`;
}

document.querySelector("#general-video-reference-preview").addEventListener("input", event => {
  const r = generalVideoReference;
  if (!r?.duration || !event.target.matches('input[type="range"]')) return;
  const value = Number(event.target.value);
  const gap = Math.min(.1, r.duration);
  if (event.target.matches('[data-clip-start]')) {
    r.clipStart = Math.max(0, Math.min(value, r.clipEnd-gap));
    if (r.clipEnd-r.clipStart > 15) r.clipEnd = r.clipStart+15;
  } else {
    r.clipEnd = Math.min(r.duration, Math.max(value, r.clipStart+gap));
    if (r.clipEnd-r.clipStart > 15) r.clipStart = r.clipEnd-15;
  }
  const video = event.currentTarget.querySelector('video');video.pause();r.previewing = false;
  video.currentTime = event.target.matches('[data-clip-start]') ? r.clipStart : Math.max(r.clipStart,r.clipEnd-.1);
  paintGeneralVideoClip();
});

document.querySelector("#general-video-reference-input").addEventListener("change", event => {
  const files = [...event.currentTarget.files];
  event.currentTarget.value = "";
  const error = document.querySelector("#general-reference-error");
  let message = "";
  if (!hasAnyPermission("video:task:manage")) message = "没有创建任务权限。";
  else if (files.length !== 1 || generalVideoReference) message = "参考视频最多 1 条，请先移除已有视频。";
  else if (!/\.(mp4|mov|webm)$/i.test(files[0].name)) message = "视频支持 MP4、MOV、WebM。";
  else if (generalVideoReferences.reduce((n, r) => n + r.file.size, files[0].size) > 90 * 1024 * 1024) message = "参考素材合计不能超过 90 MB。";
  if (message) { error.textContent = message; error.hidden = false; return; }
  generalVideoReference = {file: files[0], previewUrl: URL.createObjectURL(files[0])};
  document.querySelector('#general-video-form input[name="videoRoute"][value="QUALITY"]').checked = true;
  error.hidden = true;
  renderGeneralVideoReference();
});

document.querySelector("#general-video-reference-preview").addEventListener("click", event => {
  if (event.target.closest("[data-clip-preview]") && generalVideoReference?.duration) {
    const video = event.currentTarget.querySelector('video');video.currentTime = generalVideoReference.clipStart;
    generalVideoReference.previewing = true;
    video.play().catch(() => {if(generalVideoReference) generalVideoReference.previewing = false;});
  }
  if (event.target.closest("[data-general-video-mention]")) insertGeneralVideoMention("视频1");
  if (event.target.closest("[data-general-video-remove]")) {
    URL.revokeObjectURL(generalVideoReference.previewUrl); generalVideoReference = null;
    const input = document.querySelector("#general-video-prompt");
    input.value = input.value.replace(/@视频1(?!\d)/g, "");
    input.dispatchEvent(new Event("input", { bubbles: true }));
    renderGeneralVideoReference();
  }
});

function updateGeneralMentionMenu() {
  const input = document.querySelector("#general-video-prompt");
  const menu = document.querySelector("#general-mention-menu");
  const before = input.value.slice(0, input.selectionStart);
  const match = before.match(/@([^@\s]*)$/);
  if (!match) { menu.hidden = true; generalMentionStart = null; return; }
  generalMentionStart = before.length - match[0].length;
  const choices = generalVideoReferences.map((r, i) => ({label: `图片${i + 1}`, name:r.file.name}));
  if (generalVideoReference) choices.push({label:"视频1",name:generalVideoReference.file.name});
  const filtered = choices.filter(c => `${c.label} ${window.HoyeeLanguage?.t(c.label) || c.label} ${c.name}`.toLowerCase().includes(match[1].toLowerCase()));
  menu.innerHTML = filtered.length ? filtered.map(c => `<button type="button" role="option" data-general-mention="${c.label}"><strong>@${c.label}</strong><span translate="no">${escapeHtml(c.name)}</span></button>`).join("") : '<span class="general-mention-empty">没有匹配素材，请先上传图片或视频</span>';
  menu.hidden = false;
}
const generalMentionInput = document.querySelector("#general-video-prompt");
generalMentionInput.addEventListener("input", updateGeneralMentionMenu);
generalMentionInput.addEventListener("click", updateGeneralMentionMenu);
generalMentionInput.addEventListener("keydown", event => {
  const menu = document.querySelector("#general-mention-menu");
  if (menu.hidden) return;
  if (event.key === "Escape") { menu.hidden = true; generalMentionStart = null; event.preventDefault(); }
  if (["ArrowDown", "Enter", "Tab"].includes(event.key)) {
    const first = menu.querySelector("button");
    if (first) { event.preventDefault(); if(event.key === "ArrowDown") first.focus(); else insertGeneralVideoMention(first.dataset.generalMention); }
  }
});
document.querySelector("#general-mention-menu").addEventListener("click", event => {
  const button = event.target.closest("[data-general-mention]");
  if (button) insertGeneralVideoMention(button.dataset.generalMention);
});
document.querySelector("#general-mention-menu").addEventListener("keydown", event => {
  const buttons = [...event.currentTarget.querySelectorAll("button")];
  const index = buttons.indexOf(document.activeElement);
  if (event.key === "Enter" && index >= 0) { event.preventDefault(); insertGeneralVideoMention(buttons[index].dataset.generalMention); }
  if (event.key === "ArrowDown" || event.key === "ArrowUp") { event.preventDefault(); buttons[(index + (event.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length]?.focus(); }
  if (event.key === "Escape") { event.currentTarget.hidden = true; generalMentionStart = null; generalMentionInput.focus(); }
});
document.addEventListener("click", event => {
  if (!event.target.closest("#general-mention-menu, #general-video-prompt")) { document.querySelector("#general-mention-menu").hidden = true; generalMentionStart = null; }
});

const generalReferenceDrop = document.querySelector("#general-reference-drop");
["dragenter", "dragover"].forEach(type => generalReferenceDrop.addEventListener(type, event => {
  event.preventDefault();
  generalReferenceDrop.classList.add("is-dragging");
}));
["dragleave", "drop"].forEach(type => generalReferenceDrop.addEventListener(type, event => {
  event.preventDefault();
  generalReferenceDrop.classList.remove("is-dragging");
}));
generalReferenceDrop.addEventListener("drop", event => addGeneralVideoReferences(event.dataTransfer?.files || []));

document.querySelector("#general-video-prompt").addEventListener("input", event => {
  document.querySelector("#general-prompt-count").textContent = `${event.currentTarget.value.length.toLocaleString()} / 20,000`;
});

document.querySelector("#general-video-form").addEventListener("change", event => {
  if (event.target.matches('input[name="videoRoute"], input[name="resolution"]')) syncGeneralVideoRouteResolution();
});

document.querySelector("#general-video-task-grid").addEventListener("click", async event => {
  const previewButton = event.target.closest("[data-asset-preview]");
  if (previewButton) { openAssetPreview(previewButton); return; }
  const startButton = event.target.closest("[data-general-task-start]");
  if (startButton) {
    const card = startButton.closest("[data-general-task-id]");
    const id = card?.dataset.generalTaskId;
    if (!id) return;
    startButton.disabled = true;
    startButton.textContent = "正在提交…";
    try {
      await generalVideoApi(`/${encodeURIComponent(id)}/start`, { method: "POST" });
      await loadGeneralVideoTasks({ silent: true });
    } catch (error) {
      startButton.disabled = false;
      startButton.textContent = error.message;
    }
    return;
  }
  const button = event.target.closest("[data-general-task-expand]");
  if (!button) return;
  const card = button.closest("[data-general-task-id]");
  const id = card?.dataset.generalTaskId;
  if (!id) return;
  if (expandedGeneralVideoTaskIds.has(id)) {
    expandedGeneralVideoTaskIds.delete(id);
    generalVideoTaskDetails.delete(id);
    renderGeneralVideoTasks();
    return;
  }
  expandedGeneralVideoTaskIds.add(id);
  renderGeneralVideoTasks();
  await loadGeneralVideoTaskDetail(id);
});

document.querySelector("#general-video-refresh").addEventListener("click", () => loadGeneralVideoTasks());

document.querySelector("#general-video-form").addEventListener("submit", async event => {
  event.preventDefault();
  const form = event.currentTarget;
  const status = document.querySelector("#general-video-form-status");
  const button = document.querySelector("#general-video-submit");
  const formData = new FormData(form);
  const prompt = String(formData.get("prompt") || "");
  const limit = generalVideoReferenceLimit();
  if (!prompt.trim()) {
    status.textContent = "请填写提示词。";
    status.className = "general-form-status is-error";
    return;
  }
  if ((!generalVideoReferences.length && !generalVideoReference) || generalVideoReferences.length > limit) {
    status.textContent = generalVideoReferences.length ? `最多使用 ${limit} 张图片。` : "请至少上传 1 张图片或 1 条视频。";
    status.className = "general-form-status is-error";
    return;
  }
  if (generalVideoReference && !generalVideoReference.duration) {status.textContent = "请等待视频时长读取完成，或更换可预览的 MP4 视频。";status.className = "general-form-status is-error";return;}
  const payload = new FormData();
  payload.append("prompt", prompt);
  payload.append("durationSeconds", String(formData.get("durationSeconds") || "5"));
  payload.append("aspectRatio", String(formData.get("aspectRatio") || "9:16"));
  payload.append("resolution", String(formData.get("resolution") || "720p"));
  payload.append("videoRoute", String(formData.get("videoRoute") || "QUALITY"));
  payload.append("autoStart", String(formData.get("autoStart") === "on"));
  generalVideoReferences.forEach(reference => payload.append("files", reference.file, reference.file.name));
  if (generalVideoReference) {
    payload.append("files", generalVideoReference.file, generalVideoReference.file.name);
    payload.append("videoClipStart", String(generalVideoReference.clipStart));
    payload.append("videoClipEnd", String(generalVideoReference.clipEnd));
  }
  button.disabled = true;
  button.innerHTML = `正在创建 <span class="button-spinner" aria-hidden="true"></span>`;
  status.textContent = "正在上传参考图并创建任务…";
  status.className = "general-form-status";
  try {
    const result = await generalVideoApi("", { method: "POST", body: payload });
    const created = generalVideoTaskPayload(result.data);
    clearGeneralVideoReferences();
    form.reset();
    syncGeneralVideoRouteResolution();
    document.querySelector("#general-prompt-count").textContent = "0 / 20,000";
    status.textContent = formData.get("autoStart") === "on" ? "任务已创建，正在进入生成线路。" : "任务已保存，等待开始。";
    status.className = "general-form-status is-success";
    if (created.id) expandedGeneralVideoTaskIds.add(created.id);
    await loadGeneralVideoTasks();
  } catch (error) {
    status.textContent = error.message;
    status.className = "general-form-status is-error";
  } finally {
    button.disabled = false;
    button.innerHTML = `开始生成 <span aria-hidden="true">→</span>`;
  }
});

syncGeneralVideoRouteResolution();

if (window.location.hash === "#product-capture") rememberProductCaptureRoute(true);
loadSocialLoginProviders();
handleSocialLoginReturn();
restoreSession();

let videoGroupCopyTarget = null;
let videoGroupCopyBusy = false;
function openVideoGroupCopy(itemId) {
  const item = aiVideoTaskDetail?.items.find(item => item.id === itemId);
  if (!item || videoGroupCopyBusy) return;
  videoGroupCopyTarget = { taskId: selectedAiVideoTaskId, itemId };
  const count = document.querySelector("#video-group-copy-count");
  count.max = String(30 - aiVideoTaskDetail.items.length);
  count.value = "1";
  document.querySelector("#video-group-copy-name").setAttribute("translate", (item.name) ? "no" : "yes");
  document.querySelector("#video-group-copy-name").textContent = item.name;
  document.querySelector("#video-group-copy-note").textContent = `留在当前任务，最多可复制 ${count.max} 份`;
  document.querySelector("#video-group-copy-error").textContent = "";
  const submit = document.querySelector("#video-group-copy-submit");
  submit.disabled = false;
  submit.textContent = "复制";
  document.querySelector("#video-group-copy-dialog").showModal();
  count.select();
}
document.querySelector("#video-group-copy-cancel").addEventListener("click", () => {
  if (!videoGroupCopyBusy) document.querySelector("#video-group-copy-dialog").close();
});
document.querySelector("#video-group-copy-dialog").addEventListener("cancel", event => {
  if (videoGroupCopyBusy) event.preventDefault();
});
document.querySelector("#video-group-copy-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (!videoGroupCopyTarget || videoGroupCopyBusy) return;
  const input = document.querySelector("#video-group-copy-count");
  const count = Number(input.value);
  if (!Number.isInteger(count) || count < 1 || count > Number(input.max)) return;
  const target = { ...videoGroupCopyTarget };
  const errorNote = document.querySelector("#video-group-copy-error");
  if (["pending", "saving", "failed"].includes(directReferenceAutoSaveStates.get(target.itemId))
      || aiVideoPromptDrafts.has(target.itemId)) {
    errorNote.textContent = "请先保存当前参考图设置和提示词，再复制。";
    return;
  }
  const submit = document.querySelector("#video-group-copy-submit");
  const cancel = document.querySelector("#video-group-copy-cancel");
  videoGroupCopyBusy = true;
  submit.disabled = cancel.disabled = input.disabled = true;
  submit.textContent = "复制中…";
  errorNote.textContent = "";
  let created = false;
  try {
    await api(`/api/v1/ai-video-tasks/${encodeURIComponent(target.taskId)}/items/${encodeURIComponent(target.itemId)}/copies`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ copies: count }) });
    created = true;
    await loadAiVideoTasks(target.taskId);
    document.querySelector("#video-group-copy-dialog").close();
    document.querySelector("#video-task-action-note").textContent = `已复制 ${count} 份，可继续编辑或生成视频。`;
  } catch (error) {
    errorNote.textContent = created ? "副本已创建，请刷新任务查看。" : error.message;
  } finally {
    videoGroupCopyBusy = false;
    cancel.disabled = input.disabled = false;
    submit.disabled = created;
    submit.textContent = created ? "已复制" : "复制";
  }
});
