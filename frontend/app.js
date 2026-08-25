const loginView = document.querySelector("#login-view");
const dashboardView = document.querySelector("#dashboard-view");
const loginForm = document.querySelector("#login-form");
const loginError = document.querySelector("#login-error");
const loginButton = document.querySelector("#login-button");
const passwordInput = document.querySelector("#password");
let departments = [];
let roles = [];
let permissions = [];
let users = [];
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
let manualProductProgressTimer = null;
let manualProductProgressStartedAt = 0;
let manualProductReferences = [];
let manualProductReferenceSequence = 0;
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
      coreY: stageTop + stageHeight * 0.5,
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

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    cache: options.cache || "no-store",
    credentials: "same-origin",
    headers: { ...(options.headers || {}) }
  });
  if (response.status === 401) {
    currentUser = null;
    showLogin();
    throw new Error("登录已失效，请重新登录");
  }
  const result = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(result?.message || "服务暂时不可用，请稍后重试");
  }
  return result;
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
        currentUser = null;
        showLogin();
        reject(new Error("登录已失效，请重新登录"));
        return;
      }
      if (request.status < 200 || request.status >= 300) {
        reject(new Error(result?.message || "图片上传失败，请稍后重试"));
        return;
      }
      resolve(result);
    });
    request.addEventListener("error", () => reject(new Error("图片上传连接中断，请检查网络后重试")));
    request.addEventListener("abort", () => reject(new Error("图片上传已中断，请重新选择图片")));
    request.send(formData);
  });
}

function showLogin() {
  stopWorkflowEvents();
  resetCompanyScopedWorkspaceState();
  currentUser = null;
  loginView.hidden = false;
  dashboardView.hidden = true;
}

async function showDashboard(user) {
  if (currentUser?.organizationId && currentUser.organizationId !== user?.organizationId) {
    resetCompanyScopedWorkspaceState();
  }
  currentUser = user;
  loginView.hidden = true;
  dashboardView.hidden = false;
  document.querySelector("#current-user").textContent = user.displayName || user.username;
  const topbarOrganization = document.querySelector(".topbar p");
  if (topbarOrganization) topbarOrganization.textContent = user.organizationName || "企业工作空间";
  applyFeatureAccess();
  if (hasAnyPermission("video:monitor:view")) {
    switchWorkspace("aiVideoMonitor");
    await loadAiVideoMonitor();
  } else if (hasAnyPermission("video:script:view", "video:script:manage")) {
    switchWorkspace("videoScripts");
    await loadVideoScripts();
  } else if (hasAnyPermission("video:task:view", "video:task:manage")) {
    switchWorkspace("aiVideoTasks");
    await loadAiVideoTasks();
  } else if (hasAnyPermission("automation:view", "automation:execute")) {
    switchWorkspace("automations");
    await loadAutomationCenter();
  } else if (hasAnyPermission("system:model:view", "system:model:manage")) {
    switchWorkspace("models");
    await loadSystemData();
  } else {
    switchWorkspace("system");
    await loadSystemData();
  }
}

function hasAnyPermission(...codes) {
  const granted = new Set(currentUser?.permissions || []);
  const isAdmin = (currentUser?.roles || []).includes("ADMIN");
  return isAdmin || codes.some(code => granted.has(code));
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
  const canOpenSystem = hasAnyPermission(
    "system:organization:view", "system:organization:manage",
    "system:user:view", "system:user:manage",
    "system:permission:view", "system:role:manage"
  );

  document.querySelector("#nav-video-scripts").hidden = !canViewScripts;
  document.querySelector("#nav-ai-video-tasks").hidden = !canViewVideoTasks;
  document.querySelector("#nav-ai-video-monitor").hidden = !canViewVideoMonitor;
  document.querySelector("#nav-publish").hidden = !hasAnyPermission("publish:view", "publish:manage");
  document.querySelector("#nav-automations").hidden = !canUseAutomation;
  document.querySelector("#nav-automation-admin").hidden = !canViewAutomationAdmin;
  document.querySelector("#nav-models").hidden = !hasAnyPermission("system:model:view", "system:model:manage");
  document.querySelector("#nav-system").hidden = !canOpenSystem;
  document.querySelector("#nav-assistant-test").hidden = !canViewAssistant;

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
  document.querySelector("#skill-converter-card").hidden = !canManageAutomation;
  document.querySelector("#automation-access-card").hidden = !canManageAutomation;
}

async function restoreSession() {
  try {
    const result = await api("/api/v1/auth/me");
    await showDashboard(result.data);
  } catch {
    showLogin();
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
    loginButton.querySelector("span").textContent = "进入平台";
  }
});

document.querySelector("#toggle-password").addEventListener("click", (event) => {
  const visible = passwordInput.type === "text";
  passwordInput.type = visible ? "password" : "text";
  event.currentTarget.textContent = visible ? "显示" : "隐藏";
  event.currentTarget.setAttribute("aria-label", visible ? "显示密码" : "隐藏密码");
});

document.querySelector("#logout-button").addEventListener("click", async () => {
  try {
    await api("/api/v1/auth/logout", { method: "POST" });
  } finally {
    loginForm.reset();
    showLogin();
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

function switchWorkspace(page) {
  const isSystem = page === "system";
  const isModels = page === "models";
  const isAssistant = page === "assistant";
  const isAutomations = page === "automations";
  const isAutomationAdmin = page === "automationAdmin";
  const isVideoScripts = page === "videoScripts";
  const isAiVideoTasks = page === "aiVideoTasks";
  const isAiVideoMonitor = page === "aiVideoMonitor";
  if (!isAiVideoTasks) setWorkspaceBackdrop("");
  document.querySelector("#dashboard-page").hidden = true;
  document.querySelector("#system-page").hidden = !isSystem;
  document.querySelector("#models-page").hidden = !isModels;
  document.querySelector("#assistant-test-page").hidden = !isAssistant;
  document.querySelector("#automations-page").hidden = !isAutomations;
  document.querySelector("#automation-admin-page").hidden = !isAutomationAdmin;
  document.querySelector("#video-scripts-page").hidden = !isVideoScripts;
  document.querySelector("#ai-video-tasks-page").hidden = !isAiVideoTasks;
  document.querySelector("#ai-video-monitor-page").hidden = !isAiVideoMonitor;
  document.querySelector("#nav-system").classList.toggle("active", isSystem && !isModels);
  document.querySelector("#nav-models").classList.toggle("active", isModels);
  document.querySelector("#nav-assistant-test").classList.toggle("active", isAssistant);
  document.querySelector("#nav-automations").classList.toggle("active", isAutomations);
  document.querySelector("#nav-automation-admin").classList.toggle("active", isAutomationAdmin);
  document.querySelector("#nav-video-scripts").classList.toggle("active", isVideoScripts);
  document.querySelector("#nav-ai-video-tasks").classList.toggle("active", isAiVideoTasks);
  document.querySelector("#nav-ai-video-monitor").classList.toggle("active", isAiVideoMonitor);
  document.querySelector(".topbar h2").textContent =
    isVideoScripts ? "视频脚本管理" : isAiVideoTasks ? "AI 视频任务" : isAiVideoMonitor ? "AI 任务监控" : isAutomationAdmin ? "自动化管理" : isAutomations ? "自动化中心" : isAssistant ? "助手联调" : isModels ? "模型接入" : isSystem ? "企业系统配置" : "内容运营工作台";
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
  if (isAiVideoTasks) requestAnimationFrame(syncAiVideoTaskRailHeight);
  scheduleSlidingSwitchSync();
}

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
  return new Intl.NumberFormat("zh-CN").format(Math.max(0, Number(value || 0)));
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
    <li class="${item.status}"><i></i><div><strong>${escapeHtml(item.title)}</strong><p>${escapeHtml(item.detail)}</p></div></li>
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
        <strong>${escapeHtml(skill.name)}</strong>
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
      <header><div><h3>${escapeHtml(skill.name)}</h3><small>${escapeHtml(skill.runnerType)} · v${escapeHtml(skill.version)}</small></div><span class="job-status status-completed">已授权</span></header>
      <p>${escapeHtml(skill.description || "本机自动化 Skill")}</p>
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
        <span>${escapeHtml(field.label || field.name)}</span>
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
          ${item.description ? `<small>${escapeHtml(item.description)}</small>` : ""}</span>
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
    list.innerHTML = '<div class="empty-state automation-empty-state compact"><i aria-hidden="true">✓</i><strong>这里还很安静</strong><span>运行第一项自动化后，进度与结果会保存在这里。</span></div>';
    return;
  }
  list.innerHTML = jobs.map(job => `
    <button class="job-row automation-job-button" data-view-automation-job="${escapeHtml(job.id)}">
      <div class="job-icon">↯</div>
      <div class="job-main"><strong>${escapeHtml(job.automationCode)}</strong><span>${escapeHtml(job.progressMessage || "等待执行")}</span></div>
      <span class="job-status status-${job.status.toLowerCase()}">${escapeHtml(job.status)}</span>
      <time>${job.progressPercent}%</time>
    </button>
  `).join("");
  list.querySelectorAll("[data-view-automation-job]").forEach(button => {
    button.addEventListener("click", () => {
      const job = automationJobs.find(item => item.id === button.dataset.viewAutomationJob);
      if (job) openAutomationJob(job);
    });
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
  document.querySelector("#automation-input-snapshot").innerHTML =
    Object.entries(parameters || {}).map(([key, value]) =>
      `<div><dt>${escapeHtml(fieldLabels[key] || key)}</dt><dd>${escapeHtml(
        String(value).startsWith("heyi-attachment:")
          ? String(value).split(":").slice(2).join(":")
          : String(value)
      )}</dd></div>`
    ).join("") || "<div><dd>没有额外输入参数</dd></div>";
  document.querySelector("#automation-event-count").textContent =
    `${events.length} 条`;
  const stream = document.querySelector("#automation-event-stream");
  stream.innerHTML = events.length
    ? events.map(event => `
      <article class="automation-event ${String(event.eventType).toLowerCase()}">
        <div><span>${automationSourceName(event.source)}</span><time>${formatAutomationTime(event.createdAt)}</time></div>
        <p>${escapeHtml(event.message)}</p>
        ${event.payload && ["MODEL_DIAGNOSIS", "MODEL_RECOVERY"].includes(event.eventType)
          ? `<pre>${escapeHtml(JSON.stringify(event.payload, null, 2))}</pre>`
          : ""}
      </article>
    `).join("")
    : '<div class="automation-event-empty">正在等待本机助手返回第一条消息…</div>';
  stream.scrollTop = stream.scrollHeight;
  const resultPanel = document.querySelector("#automation-result-panel");
  const result = job.output || (job.errorMessage ? { error: job.errorMessage } : null);
  resultPanel.hidden = !result;
  const artifact = job.output?.artifact;
  const delivery = job.output?.delivery;
  document.querySelector("#automation-result-download").innerHTML = artifact?.id
    ? `<a href="/api/v1/automations/attachments/${encodeURIComponent(artifact.id)}" download="${escapeHtml(artifact.name || "任务结果.zip")}">下载任务结果</a>
       <span>${escapeHtml(artifact.name || "任务结果.zip")} · 仅保留 ${artifact.retentionDays || 3} 天</span>`
    : delivery?.status === "FAILED"
      ? `<span class="automation-delivery-error">业务执行已经完成，但结果包生成失败。本机结果已保留，请重试交付或联系管理员。${escapeHtml(delivery.message || "")}</span>`
      : "";
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
  document.querySelector("#automation-result-units").innerHTML = units.map(unit => {
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
    return `<article class="automation-result-unit ${statusClass}">
      <div><strong>${escapeHtml(unit.unitName || "未命名执行项")}</strong><small>${escapeHtml(unit.platform || unit.unitType || "")}</small></div>
      <div><p>${escapeHtml(description)}</p>${output ? `<small>${escapeHtml(output)}</small>` : ""}</div>
      <span>${escapeHtml(statusName)}</span>
    </article>`;
  }).join("");
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
    bar.innerHTML = `<span>控制请求已发送，正在等待本机助手确认…</span>`;
    return;
  }
  bar.innerHTML = controls.length
    ? `<span>任务控制</span><div>${controls.map(([action, label, className]) =>
        `<button type="button" class="${className}" data-automation-control="${action}" data-job-id="${escapeHtml(job.id)}">${label}</button>`
      ).join("")}</div>`
    : "";
  bar.querySelectorAll("[data-automation-control]").forEach(button => {
    button.addEventListener("click", async () => {
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
    });
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
  return new Date(value).toLocaleTimeString("zh-CN", { hour12: false });
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
        <div class="job-main"><strong>${escapeHtml(job.title)}</strong><span>${typeNames[job.type] || job.type} · ${escapeHtml(job.jobNo)}</span></div>
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
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
  }).format(new Date(value));
}

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
    modelResult
  ] = await Promise.all([
    canReadPeople ? api(`/api/v1/system/departments${peopleOrganizationQuery}`) : Promise.resolve({ data: [] }),
    canReadRoles ? api(`/api/v1/system/roles${peopleOrganizationQuery}`) : Promise.resolve({ data: [] }),
    canReadPermissions ? api("/api/v1/system/permissions") : Promise.resolve({ data: [] }),
    canReadPeople ? api(`/api/v1/system/users${peopleOrganizationQuery}`) : Promise.resolve({ data: [] }),
    canReadModels
      ? api(`/api/v1/system/model-configs?organizationId=${encodeURIComponent(modelOrganizationId)}`)
      : Promise.resolve({ data: [] })
  ]);
  departments = departmentResult.data;
  roles = roleResult.data;
  permissions = permissionResult.data;
  users = userResult.data;
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
      <div><strong>${escapeHtml(item.name)}</strong><small>${escapeHtml(item.description || "尚未填写公司简介")}</small></div>
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
          <span><strong>${escapeHtml(department.name)}</strong><small>${count} 人 · ${escapeHtml(department.code)}</small></span>
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
      <span><strong>${escapeHtml(role.name)}</strong><small>${role.userCount} 人 · ${role.permissionIds.length} 项权限</small></span>
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
          <span><strong>${escapeHtml(item.name)}</strong><small>${escapeHtml(item.code)}</small></span>
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
    body.innerHTML = '<tr><td colspan="5" class="table-empty">这个范围还没有人员。添加人员后，账号会出现在这里。</td></tr>';
    return;
  }
  body.innerHTML = filtered.map(user => `
    <tr>
      <td><div class="person-cell"><span><strong>${escapeHtml(user.displayName)}</strong><small>${escapeHtml(user.phone || user.username)}</small></span></div></td>
      <td><div class="stacked-cell"><strong>${escapeHtml(user.departmentName)}</strong><small>${escapeHtml(user.jobTitle || "未设置职位")}</small></div></td>
      <td><div class="role-tags">${user.roleNames.map(role => `<span>${escapeHtml(role)}</span>`).join("")}</div></td>
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
            <span><strong>${escapeHtml(skill.name)}</strong><small>${escapeHtml(skill.runnerType)}</small></span>
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
        <span><strong>${escapeHtml(role.name)}</strong><small>${dataScopeName(role.dataScope)}</small></span>
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
            <span><strong>${escapeHtml(item.name)}</strong><small>${escapeHtml(item.code)}</small></span>
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
      <label class="manual-shot-narration">对白 / 旁白<textarea name="manualNarration${index}" maxlength="8000" rows="2" placeholder="例如：对白：今天给大家看一下；未指定则留空"></textarea></label>
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
  stopVideoScriptVisualProgress();
  videoScriptVisualProgress = 0;
  videoScriptVisualStatus = "";
  paintVideoScriptProgress(0);
  document.querySelector("#script-active-analysis").hidden = true;
  document.querySelector(".script-create-switch").hidden = false;
  document.querySelector("#submit-video-script").hidden = false;
  document.querySelector("#retry-video-script").hidden = true;
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
  LEGACY_COMPAT: "旧脚本兼容"
};

const blockingVideoScriptStatuses = new Set(["QUEUED", "EXTRACTING_FRAMES", "AI_ANALYZING", "FAILED"]);

function paintVideoScriptProgress(value) {
  const progress = Math.max(0, Math.min(100, value));
  const number = document.querySelector("#active-script-progress-number");
  const bar = document.querySelector("#active-script-progress-bar");
  if (number) number.innerHTML = `<strong>${progress.toFixed(progress >= 100 ? 0 : 1)}</strong><small>%</small>`;
  if (bar) bar.style.width = `${progress}%`;
  const inlineNumber = document.querySelector("#storyboard-inline-progress-number");
  const inlineBar = document.querySelector("#storyboard-inline-progress-bar");
  if (selectedVideoScriptId === videoScriptVisualKey) {
    if (inlineNumber) inlineNumber.textContent = progress.toFixed(progress >= 100 ? 0 : 1);
    if (inlineBar) inlineBar.style.width = `${progress}%`;
  }
  document.querySelectorAll("[data-video-script-progress-live]").forEach(node => {
    if (!videoScriptVisualKey || node.dataset.videoScriptProgressLive === videoScriptVisualKey) {
      node.textContent = progress.toFixed(progress >= 100 ? 0 : 1);
    }
  });
}

function stopVideoScriptVisualProgress() {
  if (videoScriptVisualProgressTimer) window.clearInterval(videoScriptVisualProgressTimer);
  videoScriptVisualProgressTimer = null;
  videoScriptVisualProgressUpdatedAt = 0;
}

const videoScriptVisualStageConfig = {
  QUEUED: { ceiling: 18, serverLead: 13 },
  EXTRACTING_FRAMES: { ceiling: 52, serverLead: 12 },
  AI_ANALYZING: { ceiling: 94, serverLead: 1.5 }
};

function startVideoScriptVisualProgress(status, serverProgress, taskKey = "") {
  const normalizedServerProgress = Math.max(0, Math.min(100, Number(serverProgress || 0)));
  const normalizedTaskKey = String(taskKey || "");
  const taskChanged = normalizedTaskKey && normalizedTaskKey !== videoScriptVisualKey;
  const terminal = status === "READY" || status === "FAILED";
  const taskRestarted = !terminal
    && normalizedTaskKey === videoScriptVisualKey
    && (["READY", "FAILED"].includes(videoScriptVisualStatus)
      || normalizedServerProgress + 10 < videoScriptVisualServerProgress);
  if (taskChanged || taskRestarted) {
    stopVideoScriptVisualProgress();
    videoScriptVisualProgress = normalizedServerProgress;
  }

  videoScriptVisualKey = normalizedTaskKey;
  videoScriptVisualStatus = status;
  videoScriptVisualServerProgress = normalizedServerProgress;

  if (terminal) {
    stopVideoScriptVisualProgress();
    videoScriptVisualProgress = normalizedServerProgress;
    paintVideoScriptProgress(videoScriptVisualProgress);
    return;
  }

  paintVideoScriptProgress(videoScriptVisualProgress);
  if (videoScriptVisualProgressTimer) return;

  videoScriptVisualProgressUpdatedAt = performance.now();
  videoScriptVisualProgressTimer = window.setInterval(() => {
    const now = performance.now();
    const elapsedSeconds = Math.min(2, Math.max(0.25, (now - videoScriptVisualProgressUpdatedAt) / 1000));
    videoScriptVisualProgressUpdatedAt = now;
    const stage = videoScriptVisualStageConfig[videoScriptVisualStatus]
      || { ceiling: 98, serverLead: 1 };
    const ceiling = Math.min(stage.ceiling, videoScriptVisualServerProgress + stage.serverLead);
    const remaining = ceiling - videoScriptVisualProgress;
    if (remaining <= 0.02) return;

    // The backend owns the real stage checkpoints. Between checkpoints the UI
    // advances asymptotically, so it stays visibly alive without reaching the
    // next stage or claiming completion before the backend does.
    const incrementPerSecond = Math.max(0.015, Math.min(0.9, remaining * 0.08));
    videoScriptVisualProgress = Math.min(ceiling, videoScriptVisualProgress + incrementPerSecond * elapsedSeconds);
    paintVideoScriptProgress(videoScriptVisualProgress);
  }, 1000);
}

function renderVideoScriptProgress(script) {
  const activePanel = document.querySelector("#script-active-analysis");
  const createSwitch = document.querySelector(".script-create-switch");
  const presentationPicker = document.querySelector(".script-presentation-mode-picker");
  const aiPanel = document.querySelector("#script-create-ai");
  const manualPanel = document.querySelector("#script-create-manual");
  const submit = document.querySelector("#submit-video-script");
  const retry = document.querySelector("#retry-video-script");
  const importButton = document.querySelector("#import-video-script");
  if (!script) {
    stopVideoScriptVisualProgress();
    videoScriptVisualProgress = 0;
    videoScriptVisualStatus = "";
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
  const progress = Math.max(0, Math.min(100, Number(script.progress || 0)));
  const failed = status === "FAILED";
  const ready = status === "READY";
  activePanel.hidden = false;
  createSwitch.hidden = true;
  presentationPicker.hidden = true;
  aiPanel.hidden = true;
  manualPanel.hidden = true;
  submit.hidden = true;
  retry.hidden = !failed;
  importButton.textContent = ready ? "＋ 新建脚本" : "查看拆解进度";
  document.querySelector("#video-script-dialog-title").textContent = ready ? "视频拆解完成" : failed ? "视频拆解未完成" : "视频拆解处理中";
  document.querySelector("#video-script-dialog-copy").textContent = ready
    ? "分镜和生产 JSON 已经生成，可以关闭窗口查看结果。"
    : failed ? "任务和原视频已保留，可以重试当前任务或删除后重新上传。" : "当前任务会持续保留；完成前不会接受第二个视频。";
  document.querySelector("#active-script-stage").textContent = videoScriptStatusNames[status] || status;
  document.querySelector("#active-script-name").textContent = script.name || script.sourceFileName || "视频拆解任务";
  document.querySelector("#active-script-message").textContent = failed
    ? (script.errorMessage || "模型没有生成有效分镜，请重试当前任务。")
    : ready ? "拆解成功，结果已经保存到视频脚本库。" : "AI 正在抽帧并理解视频画面，页面每 3 秒自动更新一次。";
  startVideoScriptVisualProgress(status, progress, script.id);
  const orbit = document.querySelector(".active-analysis-orbit");
  orbit.classList.toggle("failed", failed);
  orbit.classList.toggle("done", ready);
  document.querySelector("#video-script-prototype-note").textContent = failed
    ? "系统已保留失败原因和上传文件，不会静默丢失。"
    : ready ? "当前任务已完成，现在可以创建新的视频拆解任务。" : "处理中请等待，无需重复点击或重新上传。";
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
    item.innerHTML = `
      <div><strong>${escapeHtml(script.name || script.sourceFileName || "未命名脚本")}</strong>
        <small>${escapeHtml(videoScriptPresentationNames[script.presentationMode] || "旧脚本兼容")} · ${script.sourceType === "MANUAL" ? "手动脚本" : escapeHtml(script.modelId || "等待驱动模型")} · <span data-video-script-progress-live="${escapeHtml(script.id)}">${script.id === videoScriptVisualKey ? videoScriptVisualProgress.toFixed(1) : Number(script.progress || 0)}</span>%</small></div>
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
  const detail = document.querySelector("#video-script-detail");
  if (!detail) return;
  detail.classList.remove("is-manual-script");
  document.querySelector("#script-detail-meta").innerHTML = "<span>本公司脚本库</span>";
  document.querySelector("#script-detail-title").textContent = "还没有视频脚本";
  document.querySelector("#script-detail-source").textContent = "上传视频或手动创建后，脚本只会保存在登录账号所属公司。";
  document.querySelector("#script-detail-visual-tone").textContent = "创建第一条脚本后，这里会展示视觉基调与分镜。";
  document.querySelector("#script-film-strip").innerHTML = '<div class="video-task-empty"><strong>脚本内容为空</strong><span>点击右上角“新建脚本”开始创建。</span></div>';
  document.querySelector("#script-json-content").textContent = "";
  document.querySelector("#script-storyboard-view .shot-editor-card").hidden = true;
  ["#copy-video-script", "#reanalyze-video-script", "#delete-video-script", "#edit-video-script-tone"]
    .forEach(selector => { document.querySelector(selector).hidden = true; });
}

function resetVideoScriptState() {
  videoScriptLoadGeneration += 1;
  stopVideoScriptPolling();
  stopVideoScriptVisualProgress();
  activeVideoScript = null;
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

function resetCompanyScopedWorkspaceState() {
  resetVideoScriptState();
  resetAiVideoTaskState();
  resetAiVideoMonitorState();
}

async function loadVideoScripts(preferredId = null) {
  const generation = ++videoScriptLoadGeneration;
  const organizationId = currentUser?.organizationId || "";
  try {
    const result = await api("/api/v1/video-scripts");
    if (generation !== videoScriptLoadGeneration || currentUser?.organizationId !== organizationId) return;
    videoScripts = (Array.isArray(result?.data) ? result.data : [])
      .filter(script => !script.organizationId || script.organizationId === organizationId);
    const currentResult = await api("/api/v1/video-scripts/current");
    if (generation !== videoScriptLoadGeneration || currentUser?.organizationId !== organizationId) return;
    activeVideoScript = currentResult?.data || null;
    document.querySelector("#import-video-script").textContent = activeVideoScript ? "查看拆解进度" : "＋ 新建脚本";
    if (!videoScripts.length) {
      selectedVideoScriptId = null;
      renderVideoScriptLibrary();
      renderEmptyVideoScriptDetail();
      return;
    }
    const selectedId = videoScripts.some(script => script.id === preferredId) ? preferredId : videoScripts[0].id;
    selectedVideoScriptId = selectedId;
    renderVideoScriptLibrary();
    await loadVideoScriptDetail(selectedId, generation, organizationId);
    if (activeVideoScript && activeVideoScript.status !== "FAILED" && !videoScriptStatusTimer) {
      startVideoScriptPolling(activeVideoScript.id);
    }
  } catch (error) {
    document.querySelector("#video-script-prototype-note").textContent = error.message;
  }
}

function scriptStageClass(stage = "") {
  const normalized = String(stage).toUpperCase();
  if (normalized.includes("HOOK")) return "hook";
  if (normalized.includes("CTA") || normalized.includes("TRUST")) return "cta";
  return "solution";
}

function videoShotPreviewUrl(scriptId, shotId) {
  return `/api/v1/video-scripts/${encodeURIComponent(scriptId)}/shots/${encodeURIComponent(shotId)}/preview`;
}

function renderStoryboardBuildingState(script) {
  const status = script.status || "QUEUED";
  const progress = Math.max(0, Math.min(100, Number(script.progress || 0)));
  const stageName = videoScriptStatusNames[status] || "正在处理";
  const slots = Array.from({ length: 9 }, (_, index) => {
    const stage = index < 3 ? "hook" : index < 6 ? "solution" : "cta";
    return `<span class="storyboard-build-slot ${stage}"><b>${String(index + 1).padStart(2, "0")}</b><i></i></span>`;
  }).join("");
  return `
    <section class="storyboard-building" role="status" aria-live="polite">
      <header>
        <div class="storyboard-building-copy">
          <strong>模型正在生成完整 9 个分镜</strong>
          <span>后端阶段节点之间缓慢预估推进；模型返回完整结果后才会进入 100%</span>
        </div>
        <output class="storyboard-building-percent"><strong id="storyboard-inline-progress-number">${progress.toFixed(1)}</strong><span>%</span></output>
      </header>
      <div class="storyboard-building-meter"><span id="storyboard-inline-progress-bar" style="width:${progress}%"></span></div>
      <div class="storyboard-build-reel" aria-hidden="true">${slots}</div>
      <footer><span><i></i>${escapeHtml(stageName)}</span><small>后台持续运行，可以离开此页面</small></footer>
    </section>`;
}

function selectRenderedShot(button, shot, scriptId) {
  selectedVideoShot = { ...shot, scriptId };
  document.querySelectorAll("#script-film-strip .film-frame").forEach(candidate => candidate.classList.remove("active"));
  button.classList.add("active");
  document.querySelector("#selected-shot-number").textContent =
    `分镜 ${String(shot.sequence || 1).padStart(2, "0")} · ${shot.stage || "CONTENT"}`;
  document.querySelector("#selected-shot-title").textContent = shot.title || `分镜 ${shot.sequence || 1}`;
  document.querySelector("#selected-shot-copy").textContent = shot.sceneDescription || "暂无画面描述";
  document.querySelector("#selected-shot-size").textContent = shot.shotSize || "未填写";
  document.querySelector("#selected-shot-camera").textContent = shot.cameraMovement || "未填写";
  document.querySelector("#selected-shot-transition").textContent = shot.transitionDescription || (Number(shot.sequence) === 1 ? "无" : "直接硬切");
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
                                     expectedOrganizationId = currentUser?.organizationId || "") {
  if (!id) return;
  selectedVideoScriptId = id;
  const result = await api(`/api/v1/video-scripts/${encodeURIComponent(id)}`);
  if (expectedGeneration !== videoScriptLoadGeneration
      || currentUser?.organizationId !== expectedOrganizationId) return;
  const detail = result?.data;
  if (!detail?.script || (detail.script.organizationId
      && detail.script.organizationId !== expectedOrganizationId)) {
    renderEmptyVideoScriptDetail();
    return;
  }
  selectedVideoScriptDetail = detail;
  const script = detail.script;
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
  document.querySelector("#script-detail-title").textContent = script.name || "视频脚本分析中";
  document.querySelector("#script-detail-meta").innerHTML =
    `<span>${manualScript ? "手动编排" : "AI 视频反推"}</span><span>版本 v${Number(script.version || 1)}</span><span>${shots.length} 个分镜</span>`;
  document.querySelector("#script-detail-source").textContent =
    `来源：${manualScript ? "手动创建" : script.sourceFileName || "上传视频"} · ${videoScriptStatusNames[script.status] || script.status} · ${Number(script.progress || 0)}%`;
  document.querySelector("#script-detail-visual-tone").textContent = script.status === "FAILED"
    ? `拆解失败：${script.errorMessage || "模型未返回有效结果"}`
    : (script.visualTone || script.summary || "模型正在理解视频画面，请稍候…");
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
  const reanalyzeButton = document.querySelector("#reanalyze-video-script");
  const canManageScripts = hasAnyPermission("video:script:manage");
  const canReanalyze = canManageScripts && script.status === "READY" && script.sourceType === "VIDEO_ANALYSIS";
  reanalyzeButton.hidden = !canReanalyze;
  reanalyzeButton.textContent = shots.length === 9 ? "重新精拆 9 镜" : "补足为 9 镜";
  reanalyzeButton.title = shots.length === 9
    ? "使用当前原视频重新生成完整 9 个分镜"
    : `当前只有 ${shots.length} 个分镜，使用原视频重新精拆为 9 个`;
  const deleteButton = document.querySelector("#delete-video-script");
  const copyButton = document.querySelector("#copy-video-script");
  const editToneButton = document.querySelector("#edit-video-script-tone");
  deleteButton.hidden = !canManageScripts;
  copyButton.hidden = !canManageScripts;
  editToneButton.hidden = !canManageScripts;
  document.querySelector("#edit-video-shot").hidden = !canManageScripts;
  const scriptIsProcessing = ["QUEUED", "EXTRACTING_FRAMES", "AI_ANALYZING"].includes(script.status);
  deleteButton.disabled = scriptIsProcessing;
  copyButton.disabled = scriptIsProcessing || !["READY", "DRAFT"].includes(script.status);
  editToneButton.disabled = scriptIsProcessing;
  deleteButton.title = deleteButton.disabled ? "视频拆解处理中，暂时不能删除" : "删除脚本及其服务器文件";
  copyButton.title = copyButton.disabled ? "脚本处理完成后才能复制" : "创建一个可独立编辑的脚本副本";
  editToneButton.title = editToneButton.disabled ? "视频拆解处理中，暂时不能编辑" : "编辑全局视觉基调";

  const strip = document.querySelector("#script-film-strip");
  const shotEditor = document.querySelector("#script-storyboard-view .shot-editor-card");
  if (!shots.length) {
    selectedVideoShot = null;
    shotEditor.hidden = true;
    strip.classList.toggle("is-processing", script.status !== "FAILED");
    strip.classList.toggle("is-error", script.status === "FAILED");
    strip.innerHTML = script.status === "FAILED"
      ? `<section class="storyboard-build-error"><span>!</span><div><strong>这次拆解没有完成</strong><p>${escapeHtml(script.errorMessage || "模型没有生成有效分镜，可以重试当前任务。")}</p></div></section>`
      : renderStoryboardBuildingState(script);
    if (script.status !== "FAILED") startVideoScriptVisualProgress(script.status, script.progress, script.id);
    return;
  }
  shotEditor.hidden = false;
  strip.classList.remove("is-processing", "is-error");
  strip.innerHTML = "";
  shots.forEach((shot, index) => {
    const button = document.createElement("button");
    button.className = `film-frame ${manualScript ? "manual-frame" : "has-preview"} ${scriptStageClass(shot.stage)}${index === 0 ? " active" : ""}`;
    button.type = "button";
    button.innerHTML = manualScript
      ? `<span class="manual-shot-kicker"><i>${String(shot.sequence || index + 1).padStart(2, "0")}</i><em>${escapeHtml(shot.stage || "CONTENT")}</em></span><b>${escapeHtml(shot.title || "未命名分镜")}</b><p>${escapeHtml(shot.sceneDescription || "暂未填写画面描述")}</p><small>${escapeHtml(shot.shotSize || "景别待定")} · ${escapeHtml(shot.cameraMovement || "运镜待定")}</small>`
      : `<img class="shot-frame-preview" src="${videoShotPreviewUrl(id, shot.id)}" alt="${escapeHtml(shot.title || `分镜 ${index + 1}`)}原视频画面"><i>${String(shot.sequence || index + 1).padStart(2, "0")}</i><b>${escapeHtml(shot.title || "未命名分镜")}</b><small>${escapeHtml(shot.cameraMovement || shot.shotSize || "镜头待定")}</small>`;
    if (!manualScript) button.querySelector("img").addEventListener("error", () => button.classList.remove("has-preview"));
    button.addEventListener("click", () => selectRenderedShot(button, shot, id));
    strip.append(button);
  });
  selectRenderedShot(strip.querySelector(".film-frame"), shots[0], id);
}

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

function startVideoScriptPolling(id) {
  stopVideoScriptPolling();
  const generation = ++videoScriptPollingGeneration;
  let polling = false;
  const timer = setInterval(async () => {
    if (generation !== videoScriptPollingGeneration) return;
    if (polling || document.hidden) return;
    polling = true;
    try {
      const result = await api(`/api/v1/video-scripts/${encodeURIComponent(id)}/status`);
      const status = result?.data;
      const progressView = activeVideoScript?.id === id
        ? { ...activeVideoScript, ...status }
        : status;
      renderVideoScriptProgress(progressView);
      await loadVideoScripts(id);
      if (generation === videoScriptPollingGeneration
          && (status?.status === "READY" || status?.status === "FAILED")) {
        clearInterval(timer);
        if (videoScriptStatusTimer === timer) videoScriptStatusTimer = null;
        if (status.status === "READY") activeVideoScript = null;
      }
    } catch {
      // Keep the current task visible and retry on the next heartbeat.
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

document.querySelector("#import-video-script").addEventListener("click", async () => {
  const result = await api("/api/v1/video-scripts/current");
  activeVideoScript = result?.data || null;
  renderVideoScriptProgress(activeVideoScript);
  document.querySelector("#video-script-dialog").showModal();
  if (activeVideoScript && activeVideoScript.status !== "FAILED") {
    startVideoScriptPolling(activeVideoScript.id);
  }
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

document.querySelector("#script-source-video").addEventListener("change", event => {
  document.querySelector("#script-video-file-name").textContent = event.target.files?.[0]?.name || "MP4、MOV，建议 60 秒以内";
});

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
    const result = await api("/api/v1/video-scripts/manual/analyze", {
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
    fillManualScriptDraft(result?.data);
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
    manualProductReferences.forEach(reference => {
      payload.append("images", reference.file);
      payload.append("imageContexts", reference.description.trim());
    });
    payload.append("requirements", "");
    payload.append("productContext", productContext);
    payload.append("targetMarket", form.elements.manualTargetMarket.value);
    payload.append("name", form.elements.manualName.value.trim());
    payload.append("visualTone", form.elements.manualVisualTone.value.trim());
    payload.append("presentationMode", selectedScriptPresentationMode());
    const result = await api("/api/v1/video-scripts/manual/analyze-product", {
      method: "POST",
      body: payload
    });
    const draft = result?.data;
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
  submit.disabled = true;
  submit.textContent = "正在上传视频…";
  note.textContent = "正在上传视频，请勿关闭页面…";
  try {
    const payload = new FormData(form);
    const result = await api("/api/v1/video-scripts/analyze", { method: "POST", body: payload });
    const script = result?.data;
    if (!script?.id) throw new Error("任务创建成功，但没有返回任务编号");
    note.textContent = "视频上传完成，AI 正在后台拆解。";
    form.reset();
    document.querySelector("#script-video-file-name").textContent = "MP4、MOV，建议 60 秒以内";
    activeVideoScript = script;
    renderVideoScriptProgress(script);
    dialog.close();
    startVideoScriptPolling(script.id);
    const refreshResults = await Promise.allSettled([
      loadVideoScripts(script.id),
      loadVideoScriptDetail(script.id)
    ]);
    if (refreshResults.some(result => result.status === "rejected")) {
      console.warn("视频拆解任务已创建，但页面数据刷新未完全成功，将由轮询继续同步。", refreshResults);
    }
  } catch (error) {
    note.textContent = `创建失败：${error.message}`;
  } finally {
    submit.disabled = false;
    submit.textContent = "开始 AI 拆解";
  }
});

document.querySelector("#video-script-dialog").addEventListener("close", async () => {
  await loadVideoScripts(activeVideoScript?.id || selectedVideoScriptId);
});

document.querySelector("#retry-video-script").addEventListener("click", async () => {
  if (!activeVideoScript?.id) return;
  const retry = document.querySelector("#retry-video-script");
  retry.disabled = true;
  retry.textContent = "正在重新提交…";
  try {
    const result = await api(`/api/v1/video-scripts/${encodeURIComponent(activeVideoScript.id)}/retry`, { method: "POST" });
    activeVideoScript = result?.data || activeVideoScript;
    renderVideoScriptProgress(activeVideoScript);
    await loadVideoScripts(activeVideoScript.id);
    startVideoScriptPolling(activeVideoScript.id);
  } catch (error) {
    document.querySelector("#active-script-message").textContent = error.message;
  } finally {
    retry.disabled = false;
    retry.textContent = "重试当前任务";
  }
});

document.querySelector("#reanalyze-video-script").addEventListener("click", async () => {
  if (!selectedVideoScriptId) return;
  const button = document.querySelector("#reanalyze-video-script");
  if (!window.confirm("将使用原视频重新精拆为完整 9 个分镜，成功后替换当前分镜。确认继续？")) return;
  button.disabled = true;
  button.textContent = "正在提交…";
  try {
    const result = await api(`/api/v1/video-scripts/${encodeURIComponent(selectedVideoScriptId)}/reanalyze`, { method: "POST" });
    activeVideoScript = result?.data || null;
    renderVideoScriptProgress(activeVideoScript);
    document.querySelector("#video-script-dialog").showModal();
    await loadVideoScripts(selectedVideoScriptId);
    await loadVideoScriptDetail(selectedVideoScriptId);
    startVideoScriptPolling(selectedVideoScriptId);
  } catch (error) {
    window.alert(error.message);
  } finally {
    button.disabled = false;
    button.textContent = "补足为 9 镜";
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

document.querySelectorAll(".film-frame").forEach(frame => {
  frame.addEventListener("click", () => {
    document.querySelectorAll(".film-frame").forEach(candidate => candidate.classList.remove("active"));
    frame.classList.add("active");
    const shot = Number(frame.dataset.shot || 1);
    const stage = shot <= 3 ? "Hook" : shot <= 6 ? "Solution" : "Trust & CTA";
    document.querySelector("#selected-shot-number").textContent = `分镜 ${String(shot).padStart(2, "0")} · ${stage}`;
    document.querySelector("#selected-shot-title").textContent = frame.dataset.shotTitle;
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
  PARTIAL_INTERACT: "局部互动"
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

function friendlyAiVideoError(message = "", type = "image") {
  const value = String(message || "").trim();
  const imageStageFailure = /图片生成失败|图片模型|image generation|image model/i.test(value);
  const video = type === "video" && !imageStageFailure;

  if (isAiVideoCopyrightError(value)) {
    return "版权限制：素材含受版权、商标或角色授权保护的内容";
  }
  if (isAiVideoConcurrencyError(value)) {
    return `${video ? "视频" : "图片"}并发限制：服务并发已满，系统正在轮询并自动重试`;
  }
  if (isAiVideoTransientNetworkError(value)) {
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
  if (isAiVideoCopyrightError(value)) {
    return "请更换或移除受版权、商标或角色授权保护的图案后重新生成";
  }
  if (isAiVideoConcurrencyError(value)) {
    return "无需手动操作；页面会持续轮询，后端在获得并发名额后自动重提";
  }
  if (isAiVideoTransientNetworkError(value)) {
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
  if (isAiVideoTransientNetworkError(value)) {
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
  return `/api/v1/ai-video-tasks/${encodeURIComponent(taskId)}/assets/${encodeURIComponent(assetId)}?v=${encodeURIComponent(version || "1")}${suffix}`;
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
  container.replaceChildren(htmlFragmentPreservingMedia(container, html));
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
        ? [item.id, item.status, item.errorMessage, item.videoPromptText, item.videoProviderTaskId, item.videoSubmittedAt]
        : [item.id, item.status, item.progress, item.errorMessage, item.videoPromptText, item.videoProviderTaskId, item.updatedAt];
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
  return JSON.stringify([state, assets]);
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
    list.innerHTML = `<div class="video-task-empty"><strong>还没有视频任务</strong><span>点击右上角新建，从 9 分镜脚本开始。</span></div>`;
    return [];
  }
  const filtered = filteredAiVideoTasks();
  if (!filtered.length) {
    list.innerHTML = `<div class="video-task-empty"><strong>没有匹配任务</strong><span>换一个任务名、脚本名或 ID 片段。</span></div>`;
    return filtered;
  }
  list.innerHTML = filtered.map(task => {
    const active = task.id === selectedAiVideoTaskId;
    const status = aiVideoTaskStatusNames[task.status] || task.status;
    const progress = task.status === "VIDEO_GENERATING"
      ? "≤40 分钟"
      : task.status === "IMAGE_GENERATING" ? `${Number(task.progress || 0)}%` : active ? "当前" : status;
    const unit = task.directReferenceVideo ? "组视频" : "款商品";
    return `<button class="video-task-item ${active ? "active" : ""}" type="button" data-ai-video-task-id="${escapeHtml(task.id)}">
      <i class="${aiVideoTaskStatusClass(task.status)}"></i>
      <div><strong>${escapeHtml(task.name)}</strong><small>${escapeHtml(task.resolution || "720p")} · ${escapeHtml(aiVideoGenerationModeNames[aiVideoMode(task)] || aiVideoMode(task))} · ${Number(task.totalItems || 0)} ${unit} · ${escapeHtml(status)}</small></div>
      <em>${escapeHtml(progress)}</em>
    </button>`;
  }).join("");
  return filtered;
}

function renderAiVideoEmpty(title, note) {
  document.querySelector("#garment-card-grid").innerHTML = `<div class="video-task-empty garment-empty"><strong>${escapeHtml(title)}</strong><span>${escapeHtml(note)}</span></div>`;
}

function setWorkspaceBackdrop(source) {
  const dashboard = document.querySelector("#dashboard-view");
  if (!dashboard) return;
  if (!source) {
    dashboard.style.removeProperty("--workspace-backdrop-image");
    return;
  }
  dashboard.style.setProperty("--workspace-backdrop-image", `url(${JSON.stringify(String(source))})`);
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
aiVideoTaskStageResizeObserver.observe(document.querySelector("#ai-video-tasks-page .video-batch-stage"));
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

function directReferenceAssets(detail, itemId) {
  return (detail?.assets || [])
    .filter(asset => asset.itemId === itemId && asset.assetType === "VIDEO_REFERENCE_IMAGE")
    .sort((a, b) => Number(a.shotSequence || 99) - Number(b.shotSequence || 99));
}

function directReferencesConfigured(references) {
  return references.length > 0
    && references.every(reference => Boolean(reference.referenceRole))
    && references.filter(reference => reference.primaryReference).length === 1;
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
    && detail.items.every(item => directReferencesConfigured(directReferenceAssets(detail, item.id)));
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
      const wasConfigured = directReferencesConfigured(directReferenceAssets(aiVideoTaskDetail, itemId));
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
      const nowConfigured = directReferencesConfigured(saved);
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

function renderDirectReferenceCard(detail, item, generating, canManageVideoTasks) {
  const task = detail.task;
  const references = directReferenceAssets(detail, item.id);
  const configured = directReferencesConfigured(references);
  const video = (detail.assets || []).find(asset => asset.itemId === item.id && asset.assetType === "VIDEO_RESULT");
  const videoUrl = video ? aiVideoAssetUrl(task.id, video.id, video.createdAt) : "";
  const providerTaskId = String(item.videoProviderTaskId || "");
  const itemAwaitingAcceptance = item.status === "VIDEO_SUBMITTING" && !providerTaskId;
  const itemVideoWaiting = ["VIDEO_SUBMITTING", "VIDEO_GENERATING"].includes(item.status);
  const flipped = flippedAiVideoPromptItems.has(item.id);
  const editingPrompt = aiVideoPromptDrafts.has(item.id);
  const configurationLocked = Boolean(video) || item.status === "VIDEO_READY";
  const canEditReferences = canManageVideoTasks && !generating && !configurationLocked
    && ["READY", "VIDEO_FAILED"].includes(item.status);
  const activePromptEditing = editingPrompt && canEditReferences;
  const promptValue = activePromptEditing ? aiVideoPromptDrafts.get(item.id) : item.videoPromptText || "";
  const disabled = canEditReferences ? "" : "disabled";
  const canDeleteReferences = canEditReferences;
  const roleOptions = Object.entries(directReferenceRoleNames)
    .map(([value, label]) => `<option value="${value}">${escapeHtml(label)}</option>`).join("");
  const track = references.map((reference, index) => {
    const url = aiVideoAssetUrl(task.id, reference.id, reference.createdAt, true);
    const fullUrl = aiVideoAssetUrl(task.id, reference.id, reference.createdAt);
    return `<article class="direct-reference-tile ${reference.primaryReference ? "is-primary" : ""} ${reference.referenceRole ? "is-specified" : "is-pending"}" data-reference-id="${escapeHtml(reference.id)}">
      <header><span>参考图 ${index + 1}</span><div><button type="button" data-reference-move="up" ${index === 0 || !canEditReferences ? "disabled" : ""} aria-label="向前移动参考图 ${index + 1}">←</button><button type="button" data-reference-move="down" ${index === references.length - 1 || !canEditReferences ? "disabled" : ""} aria-label="向后移动参考图 ${index + 1}">→</button>${canDeleteReferences ? `<button class="direct-reference-delete" type="button" data-reference-delete aria-label="删除参考图 ${index + 1}" title="删除这张参考图">×</button>` : ""}</div></header>
      <button class="direct-reference-preview" type="button" data-asset-preview="参考图 ${index + 1}" aria-label="放大查看参考图 ${index + 1}"><img src="${url}" data-full-src="${fullUrl}" alt="参考图 ${index + 1}" loading="lazy"><b aria-hidden="true">${index + 1}</b></button>
      <label class="direct-reference-primary"><input type="radio" name="primary-reference-${escapeHtml(item.id)}" data-reference-primary ${reference.primaryReference ? "checked" : ""} ${disabled}><span>设为主参考</span></label>
      <label><span>这张图负责</span><select data-reference-role ${disabled}><option value="">由用户指定…</option>${reference.referenceRole ? roleOptions.replace(`value="${reference.referenceRole}"`, `value="${reference.referenceRole}" selected`) : roleOptions}</select></label>
      <label><span>补充说明 <em>可选</em></span><input data-reference-instruction maxlength="500" value="${escapeHtml(reference.referenceInstruction || "")}" placeholder="例如：只参考袖口走线，不参考背景" ${disabled}></label>
    </article>`;
  }).join("");
  const providerButton = providerTaskId
    ? `<button class="copy-provider-task-button" type="button" data-provider-task-copy="${escapeHtml(providerTaskId)}" title="大衍任务 ID：${escapeHtml(providerTaskId)}" aria-label="一键复制大衍任务 ID"><i aria-hidden="true">⧉</i><span>复制 ID</span></button>` : "";
  return `<article class="garment-card direct-reference-card selected ${itemVideoWaiting ? "is-video-processing" : ""} ${configurationLocked ? "is-reference-locked" : ""} ${flipped ? "is-prompt-flipped" : ""}" data-garment-card data-item-id="${escapeHtml(item.id)}">
    <div class="garment-card-flip">
      <div class="garment-card-face garment-card-front" aria-hidden="${flipped}">
        <header class="garment-card-head"><div class="garment-card-copy"><div><strong>${escapeHtml(item.name)}</strong><small>${references.length} / ${Number(task.referenceImageLimit || 9)} 张参考图</small></div><div class="garment-card-tools"><button class="garment-prompt-flip" type="button" data-card-flip><i>↻</i><span>提示词</span></button><span class="garment-state ${configured ? "ready" : "running"}">${configured ? "已指定" : "待指定"}</span></div></div></header>
        <section class="direct-reference-workspace">
          <header><div><span>参考图轨道</span><strong>编号就是视频模型的引用顺序</strong></div><em>${configurationLocked ? "🔒 已锁定" : configured ? "配置完整" : "请指定每张图"}</em></header>
          <div class="direct-reference-track">${track || `<div class="direct-reference-empty">上传 1–9 张参考图开始配置</div>`}</div>
          <footer><span>${configurationLocked ? "视频已完成，参考图配置已锁定。" : "编号只用于模型引用，不会写进图片。"}</span><div class="direct-reference-card-actions">${references.length ? `<span class="reference-autosave-status ${configurationLocked ? "locked" : "saved"}" data-reference-save-status><i></i><b>${configurationLocked ? "已锁定" : "已自动保存"}</b></span>` : ""}${canEditReferences && references.length < Number(task.referenceImageLimit || 9) ? `<label class="append-reference-button"><input type="file" accept="image/*" multiple data-reference-append><span>＋ 追加参考图</span></label>` : ""}</div></footer>
        </section>
        <section class="garment-video-output ${video ? "completed" : item.status === "VIDEO_FAILED" ? "failed" : itemVideoWaiting ? "processing" : "waiting"}" data-video-output><header><strong>AI 视频生成结果</strong><div class="garment-video-status-actions"><span>${video ? "已完成" : item.status === "VIDEO_FAILED" ? "视频未生成" : itemAwaitingAcceptance ? "等待受理" : itemVideoWaiting ? "生成中" : "等待生成"}</span>${providerButton}</div></header>${video ? `<button class="garment-video-canvas" type="button" data-asset-preview="AI 视频成片" aria-label="放大播放 AI 视频成片"><video src="${videoUrl}" preload="metadata" playsinline></video><i>▶</i><div><strong>点击预览成片</strong><small>已保存到平台，可下载</small></div></button>` : `<button class="garment-video-canvas" type="button" disabled>${itemVideoWaiting ? `<img class="video-waiting-cat" src="/assets/waiting-cat.gif" alt="" aria-hidden="true">` : ""}<i aria-hidden="true">${item.status === "VIDEO_FAILED" ? "!" : itemVideoWaiting ? "" : "▶"}</i><div><strong>${item.status === "VIDEO_FAILED" ? escapeHtml(friendlyAiVideoError(item.errorMessage, "video")) : itemAwaitingAcceptance ? "正在提交，等待厂商受理" : itemVideoWaiting ? "视频正在后台生成" : "等待提交参考图生成视频"}</strong><small>${item.status === "VIDEO_FAILED" ? escapeHtml(aiVideoFailureDetail(item.errorMessage)) : itemAwaitingAcceptance ? "厂商返回任务 ID 后才会进入视频生成" : itemVideoWaiting ? "通常 20–40 分钟完成，完成后自动回显" : "配置完成后直接调用视频模型，不经过图片模型"}</small></div><em${!itemAwaitingAcceptance && itemVideoWaiting && item.videoSubmittedAt ? ` class="video-elapsed-time" data-video-submitted-at="${escapeHtml(item.videoSubmittedAt)}"` : ""}>${item.status === "VIDEO_FAILED" ? "未生成" : itemAwaitingAcceptance ? "待受理" : itemVideoWaiting ? formatAiVideoElapsed(item.videoSubmittedAt) : "--:--"}</em></button>`}</section>
      </div>
      <section class="garment-card-face garment-card-back" aria-hidden="${!flipped}">
        <header class="video-prompt-head"><div><strong>${escapeHtml(item.name)}</strong><small>最终发送：参考图职责协议 → 视频执行提示词</small></div><div class="video-prompt-head-actions"><button type="button" data-card-flip ${activePromptEditing ? "disabled" : ""}><i>↻</i> 返回参考图</button></div></header>
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
    setWorkspaceBackdrop("");
    stopAiVideoElapsedTimer();
    document.querySelector("#ai-video-task-stage").textContent = "等待选择任务";
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
    if (directReferenceVideo) return directReferencesConfigured(directReferenceAssets(detail, item.id));
    const types = new Set((assetsByItem.get(item.id) || []).map(asset => asset.assetType));
    return types.has(primaryAssetType) && types.has("STORYBOARD_GRID") && types.has("STORYBOARD_FIRST_FRAME");
  });
  document.querySelector("#ai-video-task-stage").textContent = statusName;
  document.querySelector("#ai-video-task-title").textContent = task.name;
  const musicLabel = task.musicMode === "UPLOADED" ? `已上传配乐 · ${task.musicFileName || "音乐"}` : "AI 轻快旋律 BGM";
  const routeLabel = task.videoRoute === "SPEED" ? "低质量 · 高速度" : "高质量 · 低速度";
  document.querySelector("#ai-video-task-script").innerHTML = `<span class="task-mode-badge resolution">${escapeHtml(task.resolution || "720p")}</span><span class="task-mode-badge">${routeLabel}</span>${directReferenceVideo ? `<span class="task-mode-badge direct">参考图直提</span>` : `<span class="task-mode-badge">${escapeHtml(aiVideoGenerationModeNames[mode] || mode)}</span><span class="task-mode-badge background">${escapeHtml(aiVideoBackgroundModeNames[task.backgroundMode || "SCRIPT_ORIGINAL"])}</span>`}<span class="task-mode-badge music">${escapeHtml(musicLabel)}</span> 使用脚本：${escapeHtml(task.scriptName)} / v${Number(task.scriptVersion || 1)} <b>已冻结</b>`;
  const taskProgress = document.querySelector("#ai-video-task-progress");
  taskProgress.textContent = waitingForProviderAcceptance
    ? "等待受理"
    : mixedVideoSubmissionState ? "提交中" : videoGenerating ? formatAiVideoElapsed(detail.videoSubmittedAt) : `${Number(task.progress || 0)}%`;
  if (videoGenerating && !waitingForProviderAcceptance && !mixedVideoSubmissionState && detail.videoSubmittedAt) taskProgress.dataset.videoSubmittedAt = detail.videoSubmittedAt;
  else delete taskProgress.dataset.videoSubmittedAt;
  taskProgress.classList.toggle("running", generating);
  taskProgress.classList.toggle("video-waiting", videoGenerating);
  taskProgress.classList.toggle("image-preparing", imageGenerating);
  document.querySelector("#ai-video-task-market").textContent = task.targetMarket || "未设置目标市场";
  const directReferenceCount = directReferenceVideo ? detail.items.reduce((total, item) => total + directReferenceAssets(detail, item.id).length, 0) : 0;
  const configuredGroupCount = directReferenceVideo ? detail.items.filter(item => directReferencesConfigured(directReferenceAssets(detail, item.id))).length : 0;
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
  batchDownloadButton.textContent = completedVideoCount > 0
    ? `↓ 批量下载视频（${completedVideoCount}）`
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
  startButton.textContent = imageGenerating ? `正在准备视频素材 ${Number(task.progress || 0)}%`
    : videoGenerating ? "正在后台生成视频"
      : allVideosReady ? "视频已全部完成"
        : task.status === "FAILED" ? "重试视频生成" : directReferenceVideo ? "提交参考图生成视频" : "开始批量生成视频";
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
    setWorkspaceBackdrop("");
    syncAiVideoElapsedTimer();
    renderAiVideoEmpty(directReferenceVideo ? "新建第一组参考图" : "上传商品素材", directReferenceVideo ? "一次选择 1–9 张图片创建一组视频；可继续新建，单任务最多 30 组。" : "支持 JPG、PNG、WebP；单个任务最多 30 款，每张不超过 20MB。");
    return;
  }

  setWorkspaceBackdrop(aiVideoSourceUrl(task.id, detail.items[0].id, detail.items[0].createdAt));
  const garmentCardsByItemId = new Map(detail.items.map(item => {
    if (directReferenceVideo) return [item.id, renderDirectReferenceCard(detail, item, generating, canManageVideoTasks)];
    const assets = assetsByItem.get(item.id) || [];
    const model = assets.find(asset => asset.assetType === primaryAssetType);
    const storyboard = assets.find(asset => asset.assetType === "STORYBOARD_GRID");
    const firstFrame = assets.find(asset => asset.assetType === "STORYBOARD_FIRST_FRAME");
    const video = assets.find(asset => asset.assetType === "VIDEO_RESULT");
    // The task becomes IMAGE_GENERATING before the worker claims each item. Keep the
    // queued preparation phase visibly alive instead of showing a static empty card.
    const itemImagePreparing = imageGenerating
      && !["FAILED", "VIDEO_FAILED", "VIDEO_READY"].includes(item.status);
    const modelState = model ? "completed" : itemImagePreparing ? "processing" : "waiting";
    const shotState = storyboard ? "completed" : itemImagePreparing ? "processing" : "waiting";
    const itemState = !isModelWear && item.status === "GENERATING_MODEL"
      ? "生成场景主图"
      : aiVideoItemStatusNames[item.status] || item.status;
    const source = aiVideoSourceUrl(task.id, item.id, item.createdAt);
    const modelUrl = model ? aiVideoAssetUrl(task.id, model.id, model.createdAt, true) : "";
    const modelFullUrl = model ? aiVideoAssetUrl(task.id, model.id, model.createdAt) : "";
    const storyboardUrl = storyboard ? aiVideoAssetUrl(task.id, storyboard.id, storyboard.createdAt, true) : "";
    const storyboardFullUrl = storyboard ? aiVideoAssetUrl(task.id, storyboard.id, storyboard.createdAt) : "";
    const firstFrameUrl = firstFrame ? aiVideoAssetUrl(task.id, firstFrame.id, firstFrame.createdAt) : "";
    const videoUrl = video ? aiVideoAssetUrl(task.id, video.id, video.createdAt) : "";
    const modelStatusText = model
      ? "已生成"
      : item.status === "GENERATING_MODEL"
        ? "正在生成"
        : itemImagePreparing ? "正在准备" : "等待生成";
    const storyboardStatusText = storyboard
      ? "已生成"
      : item.status === "GENERATING_STORYBOARD"
        ? "正在生成"
        : itemImagePreparing && !model ? "准备分镜" : itemImagePreparing ? "正在排队" : "等待生成";
    const modelCanvas = model
      ? `<img src="${modelUrl}" data-full-src="${modelFullUrl}" alt="${escapeHtml(item.name)} ${escapeHtml(primaryAssetName)}" loading="lazy">`
      : `<i></i><small>${item.status === "GENERATING_MODEL" ? `正在生成${primaryAssetName}` : itemImagePreparing ? `正在识别商品并准备${primaryAssetName}` : `${primaryAssetName}会输出到这里`}</small>`;
    const storyboardTiles = storyboard
      ? `<img class="storyboard-grid-image" src="${storyboardUrl}" data-full-src="${storyboardFullUrl}" alt="完整 4K 九宫格分镜图" loading="lazy">`
      : Array.from({ length: 9 }, (_, index) => `<i aria-label="分镜 ${index + 1} 等待生成"></i>`).join("");
    const error = item.errorMessage && !(model && storyboard) ? `<p class="garment-error">${escapeHtml(friendlyAiVideoError(item.errorMessage, "image"))}</p>` : "";
    const flipped = flippedAiVideoPromptItems.has(item.id);
    const editingPrompt = aiVideoPromptDrafts.has(item.id);
    const promptValue = editingPrompt ? aiVideoPromptDrafts.get(item.id) : item.videoPromptText || "";
    const promptCount = String(promptValue || "").length;
    const providerTaskId = String(item.videoProviderTaskId || "");
    const itemAwaitingAcceptance = item.status === "VIDEO_SUBMITTING" && !providerTaskId;
    const itemVideoWaiting = ["VIDEO_SUBMITTING", "VIDEO_GENERATING"].includes(item.status);
    const itemStateLabel = itemAwaitingAcceptance ? "等待厂商受理" : itemVideoWaiting ? "后台生成中" : `${itemState} · ${Number(item.progress || 0)}%`;
    const providerTaskCopyButton = providerTaskId
      ? `<button class="copy-provider-task-button" type="button" data-provider-task-copy="${escapeHtml(providerTaskId)}" title="大衍任务 ID：${escapeHtml(providerTaskId)}" aria-label="一键复制大衍任务 ID"><i aria-hidden="true">⧉</i><span>复制 ID</span></button>`
      : "";
    return [item.id, `<article class="garment-card selected ${itemImagePreparing ? "is-image-processing" : ""} ${itemVideoWaiting ? "is-video-processing" : ""} ${flipped ? "is-prompt-flipped" : ""}" data-garment-card data-item-id="${escapeHtml(item.id)}">
      <div class="garment-card-flip">
        <div class="garment-card-face garment-card-front" aria-hidden="${flipped}">
          <header class="garment-card-head">
            <div class="garment-card-copy"><div><strong>${escapeHtml(item.name)}</strong><small>${escapeHtml(item.sku || item.sourceFileName)}</small></div><div class="garment-card-tools"><button class="garment-prompt-flip" type="button" data-card-flip aria-label="查看本款视频提示词"><i>↻</i><span>提示词</span></button><span class="garment-state ${["FAILED", "VIDEO_FAILED"].includes(item.status) ? "failed" : generating ? "running" : "ready"}">${escapeHtml(itemStateLabel)}</span></div></div>
          </header>
          <div class="garment-download-rail" aria-label="下载生成素材">
            <button type="button" data-download-asset data-url="${firstFrameUrl}" data-filename="${escapeHtml(item.name)}-分镜首帧" ${firstFrame ? "" : "disabled"}><i>↓</i><span>${firstFrame ? "下载首帧（第 01 格）" : "首帧裁切中"}</span></button>
            <button type="button" data-download-asset data-url="${storyboardFullUrl}" data-filename="${escapeHtml(item.name)}-4K分镜九宫格" ${storyboard ? "" : "disabled"}><i>↓</i><span>${storyboard ? "下载九宫格" : "九宫格生成中"}</span></button>
          </div>
          <div class="garment-portrait-grid">
            <section class="garment-output-slot garment-original-output completed"><header><strong>原始商品图</strong><span>已上传</span></header><button class="garment-preview real-asset" type="button" data-asset-preview="原始商品图" title="悬停后滚轮缩放，双击还原" aria-label="放大查看 ${escapeHtml(item.name)} 原图"><img src="${source}" alt="${escapeHtml(item.name)} 原始商品图" loading="lazy"></button></section>
            <div class="garment-output-grid">
              <section class="garment-output-slot model-output ${modelState}" data-model-output><header><strong>${escapeHtml(primaryAssetName)}</strong><div class="storyboard-slot-actions"><span>${modelStatusText}</span>${canManageVideoTasks && model && !generating ? `<button type="button" data-regenerate-first-frame title="使用脚本第 01 分镜真实画面重做首帧，并同步重建九宫格">重做</button>` : ""}</div></header><button class="model-output-canvas real-asset" type="button" data-asset-preview="${escapeHtml(primaryAssetName)}" title="用于制作最终九宫格" aria-label="放大查看 ${escapeHtml(primaryAssetName)}">${modelCanvas}</button></section>
              <section class="garment-output-slot storyboard-output ${shotState}" data-storyboard-output><header><strong>4K 分镜九宫格</strong><div class="storyboard-slot-actions"><span>${storyboardStatusText}</span>${canManageVideoTasks && model && !generating ? `<button type="button" data-regenerate-storyboard title="保留 AI 模特图，重建动作底图并重新执行人物替换">${storyboard ? "重建" : "重新生成"}</button>` : ""}</div></header><button class="mini-storyboard real-assets" type="button" data-asset-preview="4K 分镜九宫格" title="9:16 画幅；悬停后滚轮缩放，双击还原" aria-label="放大查看 4K 分镜九宫格">${storyboardTiles}</button></section>
            </div>
          </div>
          ${error}
          <section class="garment-video-output ${video ? "completed" : item.status === "VIDEO_FAILED" ? "failed" : itemVideoWaiting ? "processing" : "waiting"}" data-video-output><header><strong>AI 视频生成结果</strong><div class="garment-video-status-actions"><span>${video ? "已完成" : item.status === "VIDEO_FAILED" ? "视频未生成" : itemAwaitingAcceptance ? "等待受理" : itemVideoWaiting ? "生成中" : "等待生成"}</span>${providerTaskCopyButton}</div></header>${video ? `<button class="garment-video-canvas" type="button" data-asset-preview="AI 视频成片" aria-label="放大播放 AI 视频成片"><video src="${videoUrl}" preload="metadata" playsinline></video><i>▶</i><div><strong>点击预览成片</strong><small>已保存到平台，可下载</small></div></button>` : `<button class="garment-video-canvas" type="button" disabled>${itemVideoWaiting ? `<img class="video-waiting-cat" src="/assets/waiting-cat.gif" alt="" aria-hidden="true">` : ""}<i aria-hidden="true">${item.status === "VIDEO_FAILED" ? "!" : itemVideoWaiting ? "" : "▶"}</i><div><strong>${item.status === "VIDEO_FAILED" ? escapeHtml(friendlyAiVideoError(item.errorMessage, "video")) : itemAwaitingAcceptance ? "正在提交，等待厂商受理" : itemVideoWaiting ? "视频正在后台生成" : imageGenerating ? "正在自动准备视频素材" : "等待一键生成视频"}</strong><small>${item.status === "VIDEO_FAILED" ? escapeHtml(aiVideoFailureDetail(item.errorMessage)) : itemAwaitingAcceptance ? "厂商返回任务 ID 后才会进入视频生成" : itemVideoWaiting ? "通常 20–40 分钟完成，完成后自动回显" : "点击生成后会自动完成图片与视频两阶段"}</small></div><em${!itemAwaitingAcceptance && itemVideoWaiting && item.videoSubmittedAt ? ` class="video-elapsed-time" data-video-submitted-at="${escapeHtml(item.videoSubmittedAt)}"` : ""}>${item.status === "VIDEO_FAILED" ? "未生成" : itemAwaitingAcceptance ? "待受理" : itemVideoWaiting ? formatAiVideoElapsed(item.videoSubmittedAt) : "--:--"}</em></button>`}</section>
        </div>
        <section class="garment-card-face garment-card-back" aria-hidden="${!flipped}">
          <header class="video-prompt-head"><div><strong>${escapeHtml(item.name)}</strong><small>上传顺序：${isModelWear ? "单人首帧" : "商品首帧"} → 4K 九宫格 → 原始商品图</small></div><div class="video-prompt-head-actions"><button class="copy-prompt-button" type="button" data-prompt-copy aria-label="一键复制本款视频提示词"><i aria-hidden="true">⧉</i><span>一键复制</span></button><button type="button" data-card-flip ${editingPrompt ? "disabled" : ""}><i>↻</i> 返回画面</button></div></header>
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
      staging.replaceChildren(htmlFragmentPreservingMedia(currentCard, garmentCardsByItemId.get(itemId) || ""));
      const nextCard = staging.querySelector(`[data-garment-card][data-item-id="${CSS.escape(itemId)}"]`);
      if (currentCard && nextCard) currentCard.replaceWith(nextCard);
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
    selectedAiVideoTaskId = aiVideoTasks.some(task => task.id === preferredId) ? preferredId : aiVideoTasks[0]?.id || null;
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
  STORYBOARD_GUIDE: "分镜参考图", STORYBOARD_FIRST_FRAME: "视频首帧", VIDEO_RESULT: "视频成片"
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
    const haystack = [task.name, task.scriptName, task.modelId, task.id, task.status].filter(Boolean).join(" ").toLowerCase();
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
    list.innerHTML = `<div class="monitor-empty"><strong>没有匹配任务</strong><span>调整状态或关键词后再试。</span></div>`;
    return;
  }
  list.innerHTML = tasks.map(task => {
    const group = monitorStatusGroup(task.status).toLowerCase();
    return `<button class="monitor-task-row ${task.id === selectedMonitorTaskId ? "active" : ""}" type="button" data-monitor-task-id="${escapeHtml(task.id)}">
      <span class="monitor-task-signal ${group}"><i></i></span>
      <span class="monitor-task-copy"><strong>${escapeHtml(task.name || "未命名任务")}</strong><small>${escapeHtml(task.scriptName || "未关联脚本")} · ${formatDate(task.updatedAt)}</small></span>
      <span class="monitor-task-meta"><b>${monitorStatusNames[task.status] || escapeHtml(task.status)}</b><small>${task.imageRequests || 0} 图 / ${task.videoRequests || 0} 视频请求</small></span>
      <span class="monitor-task-progress"><i style="--progress:${Math.max(0, Math.min(100, task.progress || 0))}%"></i></span>
    </button>`;
  }).join("");
}

function renderMonitorAssetPreview(event, taskId) {
  const url = aiVideoAssetUrl(taskId, event.id, event.createdAt);
  if (String(event.contentType || "").startsWith("video/") || event.type === "VIDEO_RESULT") {
    return `<div class="monitor-asset-preview is-video"><video src="${url}" controls preload="metadata" playsinline></video></div>`;
  }
  if (String(event.contentType || "").startsWith("image/")) {
    const previewUrl = aiVideoAssetUrl(taskId, event.id, event.createdAt, true);
    return `<button class="monitor-asset-preview is-image" type="button" data-asset-preview="${escapeHtml(monitorAssetNames[event.type] || event.type || "生成图片")}" aria-label="放大查看${escapeHtml(monitorAssetNames[event.type] || "生成图片")}"><img src="${previewUrl}" data-full-src="${url}" alt="${escapeHtml(monitorAssetNames[event.type] || "生成图片")}" loading="lazy"></button>`;
  }
  return "";
}

function renderMonitorImageEvents(events, taskId) {
  if (!events?.length) return `<div class="monitor-sub-empty">还没有生图资产。运行中的请求可从上方任务与单品状态判断。</div>`;
  return `<div class="monitor-media-event-row monitor-image-event-grid">${events.map(event => `<article class="monitor-request-card image-request">
    <header><span class="request-kind">IMG</span><div><strong>${monitorAssetNames[event.type] || escapeHtml(event.type)}</strong><small>${formatDate(event.createdAt)} · ${escapeHtml(event.modelId || "未记录模型")}</small></div><b>已落盘</b></header>
    ${renderMonitorAssetPreview(event, taskId)}
    <dl><div><dt>资产 ID</dt><dd>${escapeHtml(event.id)}</dd></div><div><dt>文件</dt><dd>${escapeHtml(event.contentType || "—")} · ${event.sizeBytes ? `${(event.sizeBytes / 1024 / 1024).toFixed(2)} MB` : "—"}</dd></div></dl>
    <details><summary>查看本次生图提示词</summary><pre>${escapeHtml(event.prompt || "该资产未保存提示词")}</pre></details>
  </article>`).join("")}</div>`;
}

function renderMonitorVideoRequests(requests, videoPrompt, videoAsset, taskId) {
  if (!requests?.length) return `<div class="monitor-sub-empty">还没有向视频线路提交请求。</div>`;
  return `<div class="monitor-media-event-row monitor-video-event-grid">${requests.map(request => {
    const group = monitorStatusGroup(request.status).toLowerCase();
    return `<article class="monitor-request-card video-request ${group}">
      <header><span class="request-kind">VID</span><div><strong>第 ${request.attempt} 次尝试</strong><small>${formatDate(request.createdAt)} · ${monitorDuration(request.createdAt, request.completedAt || request.updatedAt)}</small></div><b>${monitorStatusNames[request.status] || escapeHtml(request.status)}</b></header>
      ${videoAsset && ["COMPLETED", "SUCCEEDED"].includes(request.status) ? renderMonitorAssetPreview(videoAsset, taskId) : ""}
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
  const allImageEvents = task.items.flatMap(item => item.imageEvents || []);
  const allVideoRequests = task.items.flatMap(item => item.videoRequests || []);
  const hasImage = allImageEvents.length > 0;
  const hasVideo = allVideoRequests.length > 0;
  const finished = ["IMAGE_READY", "VIDEO_READY", "FAILED"].includes(task.status);
  const detailHtml = `
    <header class="monitor-detail-head">
      <div><span class="monitor-detail-id">TASK · ${escapeHtml(task.id)}</span><h2>${escapeHtml(task.name || "未命名任务")}</h2><p>${escapeHtml(task.scriptName || "未关联脚本")} · v${task.scriptVersion} · ${escapeHtml(task.generationMode || "—")}</p></div>
      <div class="monitor-detail-status ${monitorStatusGroup(task.status).toLowerCase()}"><i></i><span>${monitorStatusNames[task.status] || escapeHtml(task.status)}</span><strong>${task.progress || 0}%</strong></div>
    </header>
    <section class="monitor-trace" aria-label="任务生成轨迹">
      <div class="done"><i></i><span>任务创建</span><small>${formatDate(task.createdAt)}</small></div><b class="done"></b>
      <div class="${hasImage ? "done" : monitorStatusGroup(task.status) === "ACTIVE" ? "current" : ""}"><i></i><span>生图</span><small>${allImageEvents.length} 个资产</small></div><b class="${hasImage ? "done" : ""}"></b>
      <div class="${hasVideo ? "done" : task.status === "VIDEO_GENERATING" ? "current" : ""}"><i></i><span>生视频</span><small>${allVideoRequests.length} 次请求</small></div><b class="${hasVideo ? "done" : ""}"></b>
      <div class="${finished ? "done" : ""}"><i></i><span>${task.status === "FAILED" ? "异常结束" : "任务完成"}</span><small>${monitorDuration(task.createdAt, task.completedAt || task.updatedAt)}</small></div>
    </section>
    <section class="monitor-task-facts">
      <div><span>目标市场</span><strong>${escapeHtml(task.targetMarket || "未设置")}</strong></div><div><span>商品品类</span><strong>${escapeHtml(task.productCategory || "未设置")}</strong></div>
      <div><span>画面规格</span><strong>${escapeHtml(task.aspectRatio || "—")} · ${escapeHtml(task.resolution || "—")}</strong></div><div><span>当前模型</span><strong>${escapeHtml(task.modelId || "尚未调用")}</strong></div>
    </section>
    ${task.errorMessage ? `<p class="monitor-task-error"><strong>任务错误</strong>${escapeHtml(task.errorMessage)}</p>` : ""}
    <section class="monitor-items"><header><div><span>请求轨迹</span><h3>${task.items.length} 个商品单品</h3></div><small>展开单品查看完整参数</small></header>
      ${task.items.map((item, index) => `<details class="monitor-item" ${index === 0 ? "open" : ""}>
        <summary><span class="monitor-item-index">${String(index + 1).padStart(2, "0")}</span><div><strong>${escapeHtml(item.name || item.sourceFileName || "未命名单品")}</strong><small>${escapeHtml(item.sku || "无 SKU")} · 更新于 ${formatDate(item.updatedAt)}</small></div><span class="monitor-item-count">${item.imageEvents?.length || 0} 图 / ${item.videoRequests?.length || 0} 请求</span><b>${monitorStatusNames[item.status] || escapeHtml(item.status)}</b></summary>
        <div class="monitor-item-body">
          ${item.errorMessage ? `<p class="monitor-request-error">${escapeHtml(item.errorMessage)}</p>` : ""}
          <div class="monitor-request-section"><h4><span>生图轨迹</span><small>输出资产及真实提示词</small></h4>${renderMonitorImageEvents((item.imageEvents || []).filter(asset => asset.type !== "VIDEO_RESULT"), task.id)}</div>
          <div class="monitor-request-section"><h4><span>生视频请求</span><small>每次线路提交与重试</small></h4>${renderMonitorVideoRequests(item.videoRequests, item.videoPrompt, (item.imageEvents || []).find(asset => asset.type === "VIDEO_RESULT"), task.id)}</div>
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
      .filter(detail => detail?.shots?.length === 9);
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
  const mode = form.querySelector("input[name=generationMode]:checked")?.value || "MODEL_WEAR";
  const previous = select.value;
  const matching = aiVideoUsableScripts.filter(detail => {
    const scriptMode = detail.script.presentationMode || "LEGACY_COMPAT";
    return scriptMode === "LEGACY_COMPAT" || scriptMode === mode;
  });
  const modeName = mode === "MODEL_WEAR" ? "人物穿搭" : "纯商品";
  select.innerHTML = matching.length
    ? `<option value="">请选择${modeName}脚本</option>${matching.map(detail => {
        const scriptMode = detail.script.presentationMode || "LEGACY_COMPAT";
        const legacy = scriptMode === "LEGACY_COMPAT" ? " · 旧脚本兼容" : "";
        return `<option value="${escapeHtml(detail.script.id)}" data-source-type="${escapeHtml(detail.script.sourceType || "")}" data-presentation-mode="${escapeHtml(scriptMode)}">${escapeHtml(detail.script.name)} / v${Number(detail.script.version || 1)}${legacy}</option>`;
      }).join("")}`
    : `<option value="">暂无${modeName}的完整 9 分镜脚本</option>`;
  if (matching.some(detail => detail.script.id === previous)) select.value = previous;
  submit.disabled = matching.length === 0;
  document.querySelector("#video-task-dialog-note").textContent = matching.length
    ? `只显示 ${matching.length} 个与“${modeName}”匹配的脚本，创建后会冻结当前版本。`
    : `没有可用的${modeName}脚本，请先在视频脚本管理中按该类型创建。`;
}

function syncVideoTaskModeForm() {
  const form = document.querySelector("#video-task-form");
  const mode = form.querySelector("input[name=generationMode]:checked")?.value || "MODEL_WEAR";
  renderAiVideoTaskScriptOptions();
  const scriptSelect = form.elements.scriptId;
  const selectedScript = scriptSelect?.selectedOptions?.[0];
  const hasSourceVideo = selectedScript?.dataset.sourceType === "VIDEO_ANALYSIS";
  const directReferenceVideo = selectedScript?.dataset.sourceType === "MANUAL";
  const referencePicker = document.querySelector("#script-video-reference-picker");
  const referenceToggle = form.elements.useScriptReferenceVideo;
  const canUseReferenceVideo = mode === "PRODUCT_ONLY" && hasSourceVideo;
  form.querySelectorAll(".generation-mode-option").forEach(option => {
    option.classList.toggle("active", option.querySelector("input")?.checked);
  });
  form.querySelectorAll(".background-mode-option").forEach(option => {
    option.classList.toggle("active", option.querySelector("input")?.checked);
  });
  form.querySelectorAll(".resolution-option").forEach(option => {
    option.classList.toggle("active", option.querySelector("input")?.checked);
  });
  document.querySelector("#direct-reference-mode-note").hidden = !directReferenceVideo;
  form.querySelector(".background-mode-picker").hidden = directReferenceVideo;
  referencePicker.hidden = directReferenceVideo || mode !== "PRODUCT_ONLY";
  referencePicker.classList.toggle("unavailable", mode === "PRODUCT_ONLY" && !hasSourceVideo);
  referenceToggle.disabled = !canUseReferenceVideo;
  if (!canUseReferenceVideo) referenceToggle.checked = false;
  document.querySelector("#script-video-reference-note").textContent = hasSourceVideo
    ? "把脚本原视频作为动作、运镜与转场参考一起提交"
    : "所选脚本没有可提交的原始参考视频";
  document.querySelector(".script-video-reference-option").classList.toggle("active", referenceToggle.checked);
  document.querySelector("#script-video-rate-warning").hidden = !referenceToggle.checked;
  form.querySelector("button[type=submit]").textContent = directReferenceVideo ? "创建参考图任务" : "创建并准备素材";
  const category = form.elements.productCategory;
  if (mode === "MODEL_WEAR" && !["服装", "鞋包", "饰品"].includes(category.value)) category.value = "服装";
  if (mode !== "MODEL_WEAR" && category.value === "服装") category.value = "通用商品";
}

document.querySelector("#video-task-form").addEventListener("change", event => {
  if (["generationMode", "videoRoute", "backgroundMode", "resolution", "scriptId", "useScriptReferenceVideo"].includes(event.target.name)) syncVideoTaskModeForm();
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
  const values = new FormData(form);
  const musicFile = form.elements.music.files?.[0];
  let createdTaskId = null;
  submit.disabled = true;
  submit.textContent = "正在创建…";
  try {
    const result = await api("/api/v1/ai-video-tasks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        scriptId: values.get("scriptId"),
        name: values.get("name"),
        targetMarket: values.get("targetMarket"),
        generationMode: values.get("generationMode"),
        backgroundMode: values.get("backgroundMode"),
        videoRoute: values.get("videoRoute") || "QUALITY",
        useScriptReferenceVideo: values.get("useScriptReferenceVideo") === "true",
        productCategory: values.get("productCategory"),
        aspectRatio: values.get("aspectRatio"),
        resolution: values.get("resolution") || "720p"
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
    document.querySelector("#video-task-dialog-note").textContent = error.message;
  } finally {
    submit.disabled = false;
    syncVideoTaskModeForm();
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

document.querySelector("#batch-download-videos").addEventListener("click", event => {
  const button = event.currentTarget;
  if (!selectedAiVideoTaskId || button.disabled) return;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = "正在准备 ZIP…";
  const link = document.createElement("a");
  link.href = `/api/v1/ai-video-tasks/${encodeURIComponent(selectedAiVideoTaskId)}/video-results.zip`;
  link.download = "";
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
  if (event.target.matches("[data-reference-role], [data-reference-instruction]")) {
    const tile = event.target.closest("[data-reference-id]");
    const card = event.target.closest("[data-garment-card]");
    if (!card || !directReferenceItemIsEditable(card.dataset.itemId)) return;
    if (tile && event.target.matches("[data-reference-role]")) {
      tile.classList.toggle("is-specified", Boolean(event.target.value));
      tile.classList.toggle("is-pending", !event.target.value);
    }
    scheduleDirectReferenceAutoSave(card, event.target.matches("[data-reference-instruction]") ? 650 : 180);
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
    const tile = referenceMove.closest("[data-reference-id]");
    const sibling = referenceMove.dataset.referenceMove === "up" ? tile?.previousElementSibling : tile?.nextElementSibling;
    if (!tile || !sibling?.matches?.("[data-reference-id]")) return;
    if (referenceMove.dataset.referenceMove === "up") tile.parentElement.insertBefore(tile, sibling);
    else tile.parentElement.insertBefore(sibling, tile);
    renumberDirectReferenceTiles(card);
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
  const previewCard = button.closest("[data-garment-card]");
  const title = previewCard?.querySelector(".garment-card-copy strong")?.textContent || "生成图片";
  const previewImage = button.querySelector("img");
  const previewVideo = button.querySelector("video");
  const preview = previewVideo ? document.createElement("video") : button.cloneNode(true);
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
    const separator = url.includes("?") ? "&" : "?";
    const anchor = document.createElement("a");
    anchor.href = `${url}${separator}download=true&filename=${encodeURIComponent(filename)}`;
    anchor.download = filename;
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
      !directReferencesConfigured(directReferenceAssets(aiVideoTaskDetail, item.id)));
    if (incomplete) {
      const references = directReferenceAssets(aiVideoTaskDetail, incomplete.id);
      const primaryCount = references.filter(reference => reference.primaryReference).length;
      document.querySelector("#video-task-action-note").textContent = !references.length
        ? `${incomplete.name} 还没有参考图，请先上传。`
        : references.some(reference => !reference.referenceRole)
          ? `${incomplete.name} 有图片尚未指定用途，请先选择“商品外观、动作、场景”等职责。`
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

restoreSession();
