/*
 * Direct vanilla mount of nikdelvin/liquid-glass's LiquidGlass component.
 * Upstream: https://github.com/nikdelvin/liquid-glass
 * Copyright (c) 2025 Nikita Stadnik — MIT License
 */
(() => {
  "use strict";

  const getDisplacementMap = ({ height, width, radius, depth }) =>
    "data:image/svg+xml;utf8," + encodeURIComponent(`<svg height="${height}" width="${width}" viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">
      <style>.mix { mix-blend-mode: screen; }</style>
      <defs>
        <linearGradient id="Y" x1="0" x2="0" y1="${Math.ceil((radius / height) * 15)}%" y2="${Math.floor(100 - (radius / height) * 15)}%">
          <stop offset="0%" stop-color="#0F0"/><stop offset="100%" stop-color="#000"/>
        </linearGradient>
        <linearGradient id="X" x1="${Math.ceil((radius / width) * 15)}%" x2="${Math.floor(100 - (radius / width) * 15)}%" y1="0" y2="0">
          <stop offset="0%" stop-color="#F00"/><stop offset="100%" stop-color="#000"/>
        </linearGradient>
      </defs>
      <rect x="0" y="0" height="${height}" width="${width}" fill="#808080"/>
      <g filter="blur(2px)">
        <rect x="0" y="0" height="${height}" width="${width}" fill="#000080"/>
        <rect x="0" y="0" height="${height}" width="${width}" fill="url(#Y)" class="mix"/>
        <rect x="0" y="0" height="${height}" width="${width}" fill="url(#X)" class="mix"/>
        <rect x="${depth}" y="${depth}" height="${height - 2 * depth}" width="${width - 2 * depth}" fill="#808080" rx="${radius}" ry="${radius}" filter="blur(${depth}px)"/>
      </g>
    </svg>`);

  const getDisplacementFilter = ({ height, width, radius, depth, strength = 100, chromaticAberration = 0 }) =>
    "data:image/svg+xml;utf8," + encodeURIComponent(`<svg height="${height}" width="${width}" viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg">
      <defs><filter id="displace" color-interpolation-filters="sRGB">
        <feImage x="0" y="0" height="${height}" width="${width}" href="${getDisplacementMap({ height, width, radius, depth })}" result="displacementMap"/>
        <feDisplacementMap transform-origin="center" in="SourceGraphic" in2="displacementMap" scale="${strength + chromaticAberration * 2}" xChannelSelector="R" yChannelSelector="G"/>
        <feColorMatrix type="matrix" values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0" result="displacedR"/>
        <feDisplacementMap in="SourceGraphic" in2="displacementMap" scale="${strength + chromaticAberration}" xChannelSelector="R" yChannelSelector="G"/>
        <feColorMatrix type="matrix" values="0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0" result="displacedG"/>
        <feDisplacementMap in="SourceGraphic" in2="displacementMap" scale="${strength}" xChannelSelector="R" yChannelSelector="G"/>
        <feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0" result="displacedB"/>
        <feBlend in="displacedR" in2="displacedG" mode="screen"/><feBlend in2="displacedB" mode="screen"/>
      </filter></defs>
    </svg>`) + "#displace";

  const supportsBackdropFilterUrl = (() => {
    const testEl = document.createElement("div");
    testEl.style.cssText = "backdrop-filter: url(#test)";
    return testEl.style.backdropFilter === "url(#test)" || testEl.style.backdropFilter === 'url("#test")';
  })();

  const mounted = new WeakSet();
  const scheduled = new Set();
  const pending = new WeakSet();
  const filterSignatures = new WeakMap();
  const maxNativeFilterArea = 500000;
  let animationFrame = 0;

  function redrawGlass(glass) {
    const liquidGlass = glass.querySelector(":scope > .lg-filter-layer > .glass-box");
    if (!liquidGlass) return;
    const rect = glass.getBoundingClientRect();
    const width = Math.round(rect.width);
    const height = Math.round(rect.height);
    if (width < 2 || height < 2) return;
    const blur = parseFloat(glass.dataset.blur || "0");
    const chromaticAberration = parseFloat(glass.dataset.cab || "0");
    const depth = parseFloat(glass.dataset.depth || "10");
    const strength = parseFloat(glass.dataset.strength || "100");
    const saturate = parseFloat(glass.dataset.saturate || (glass.classList.contains("button") ? "1.2" : "1.5"));
    const brightness = parseFloat(glass.dataset.brightness || (glass.classList.contains("button") ? "1.6" : "1.1"));
    const radius = parseFloat(getComputedStyle(glass).borderTopLeftRadius || "0");
    const useNativeFilter = supportsBackdropFilterUrl && width * height <= maxNativeFilterArea;
    const signature = [width, height, radius, depth, strength, chromaticAberration, blur, saturate, brightness, useNativeFilter].join(":");
    if (filterSignatures.get(glass) === signature) return;
    filterSignatures.set(glass, signature);
    glass.classList.toggle("lg-performance-fallback", !useNativeFilter);
    if (useNativeFilter) {
      liquidGlass.style.backdropFilter = `blur(${blur / 2}px) url('${getDisplacementFilter({
        height, width, radius, depth, strength, chromaticAberration
      })}') blur(${blur}px) brightness(${brightness}) saturate(${saturate})`;
      liquidGlass.style.webkitBackdropFilter = "";
    } else {
      const fallback = `blur(14px) brightness(${brightness}) saturate(${Math.min(saturate, 1.35)})`;
      liquidGlass.style.backdropFilter = fallback;
      liquidGlass.style.webkitBackdropFilter = fallback;
    }
  }

  function schedule(glass) {
    if (document.hidden || !glass.isConnected) return;
    scheduled.add(glass);
    if (animationFrame) return;
    animationFrame = requestAnimationFrame(() => {
      scheduled.forEach(redrawGlass);
      scheduled.clear();
      animationFrame = 0;
    });
  }

  const resizeObserver = new ResizeObserver(entries => {
    entries.forEach(entry => schedule(entry.target));
  });

  function mount(glass) {
    if (mounted.has(glass)) return;
    const isButton = glass.dataset.button !== undefined || glass.classList.contains("button");
    if (!isButton && glass.parentElement?.closest("[data-liquid-glass]")) return;
    if (!glass.getClientRects().length) {
      if (!pending.has(glass)) {
        pending.add(glass);
        visibilityObserver.observe(glass);
      }
      return;
    }
    mounted.add(glass);
    glass.classList.add("liquid-glass");
    if (getComputedStyle(glass).position === "static") glass.classList.add("lg-positioned");
    if (glass.dataset.button !== undefined) glass.classList.add("button");

    const overlay = document.createElement("span");
    overlay.className = "lg-overlay-bg";
    overlay.style.background = glass.dataset.noMorph !== undefined
      ? "rgba(255, 255, 255, 0.1)"
      : glass.classList.contains("button") ? "rgba(255, 255, 255, 0.1)" : "rgba(0, 0, 0, 0.3)";

    const filterLayer = document.createElement("span");
    filterLayer.className = "lg-filter-layer";
    const box = document.createElement("span");
    box.className = `glass-box glass-${glass.dataset.color || "transparent"}`;
    filterLayer.append(box);
    glass.prepend(filterLayer);
    glass.prepend(overlay);

    resizeObserver.observe(glass);
    schedule(glass);
  }

  const visibilityObserver = new IntersectionObserver(entries => entries.forEach(entry => {
    if (!entry.isIntersecting) return;
    visibilityObserver.unobserve(entry.target);
    mount(entry.target);
  }), { rootMargin: "160px" });

  function scan(root = document) {
    if (root instanceof Element && root.matches("[data-liquid-glass]")) mount(root);
    root.querySelectorAll?.("[data-liquid-glass]").forEach(mount);
  }

  const profiles = [
    {
      name: "dark-surface",
      selector: ".brand-stage, .welcome-strip, .assistant-console, #video-scripts-page .film-strip",
      options: { color: "black" }
    },
    {
      name: "script-surface",
      selector: [
        "#video-scripts-page .script-library-rail",
        "#video-scripts-page .script-library-rail > header",
        "#ai-video-tasks-page .video-task-rail",
        "#ai-video-tasks-page .batch-task-rail > header",
        "#video-scripts-page .script-library-item",
        "#video-scripts-page .script-detail-stage",
        "#video-scripts-page .script-detail-head",
        "#video-scripts-page .visual-anchor-card",
        "#video-scripts-page .script-view-tabs",
        "#video-scripts-page .storyboard-workspace",
        "#video-scripts-page .shot-editor-card",
        "#video-scripts-page .script-json-view",
        "#video-scripts-page .script-json-view > header"
      ].join(", "),
      options: { color: "transparent" }
    },
    {
      name: "video-task-surface",
      selector: [
        "#ai-video-tasks-page .video-batch-stage",
        "#ai-video-tasks-page .video-batch-head",
        "#ai-video-tasks-page .batch-group-bar",
        "#ai-video-tasks-page .garment-board",
        "#ai-video-tasks-page .garment-board > header",
        "#ai-video-tasks-page .garment-card",
        "#ai-video-tasks-page .garment-card-front",
        "#ai-video-tasks-page .garment-output-slot",
        "#ai-video-tasks-page .garment-video-output",
        "#ai-video-tasks-page .batch-action-dock"
      ].join(", "),
      options: { color: "transparent" }
    },
    {
      name: "dialog-surface",
      selector: ".manage-dialog > form, .automation-form-dialog > form, .automation-run-dialog > form, .asset-preview-shell",
      options: { color: "transparent" }
    },
    {
      name: "automation-home-surface",
      selector: [
        "#automations-page .automation-heading",
        "#automations-page .automation-assistant-link",
        "#automations-page .automation-skill-card",
        "#automations-page .automation-skill-grid > article",
        "#automations-page .automation-history-card",
        "#automations-page .automation-empty-state",
        "#automations-page .automation-job-list > article",
        "#automations-page .job-list > article"
      ].join(", "),
      options: { color: "transparent" }
    },
    {
      name: "automation-admin-surface",
      selector: [
        "#automation-admin-page .automation-heading",
        "#automation-admin-page .skill-converter-card",
        "#automation-admin-page .skill-source-menu",
        "#automation-admin-page .managed-skill-row",
        "#automation-admin-page .automation-access-card",
        "#automation-admin-page .automation-admin-list > article"
      ].join(", "),
      options: { color: "transparent" }
    },
    {
      name: "surface",
      selector: [
        ".login-card", ".pipeline", ".current-task-banner",
        ".summary-grid > article", ".workbench-card", ".boundary-note",
        ".script-library-rail", "#video-scripts-page .script-library-rail > header",
        ".script-detail-stage", ".script-detail-head",
        ".script-create-panel", ".visual-anchor-card", ".script-view-tabs",
        ".storyboard-workspace", ".shot-editor-card", ".script-json-view",
        "#video-scripts-page .script-json-view > header",
        ".storyboard-building", ".storyboard-build-error", ".video-task-rail",
        ".video-batch-stage", ".video-batch-head", ".batch-group-bar", ".garment-board",
        ".garment-card",
        ".garment-output-slot", ".garment-video-output", ".batch-action-dock",
        ".automation-heading", ".automation-assistant-link", ".automation-control-bar",
        ".automation-access-card", ".automation-live-stage", ".automation-start-notice",
        ".automation-prerequisites", ".automation-business-results", ".automation-result-panel",
        ".automation-plan-preview", ".automation-skill-grid > article", ".skill-converter-card",
        ".automation-runtime-grid > aside", ".automation-runtime-grid > section",
        ".model-output",
        ".assistant-chat-card", ".assistant-trace-card", ".assistant-status-strip",
        ".assistant-composer", ".assistant-token-monitor", ".assistant-examples button",
        ".generated-image-card", ".form-designer-palette", ".form-designer-canvas",
        ".form-designer-canvas-wrap", ".form-designer-settings", ".designer-field-card",
        ".asset-preview-shell > header", ".asset-preview-shell > footer",
        ".garment-upload-card", ".garment-card-front", ".garment-board > header",
        ".garment-preview", ".model-output-canvas", ".mini-storyboard", ".garment-video-canvas",
        ".shot-production-card", ".source-thumbnails > *", ".task-group-tabs button",
        ".batch-group-tabs button", ".video-prompt-actions", ".video-prompt-editor",
        ".automation-prerequisites label", ".automation-plan-preview dl > div",
        ".automation-result-unit", ".automation-event", ".managed-skill-row",
        ".skill-source-menu", ".resolution-cost-warning",
        ".form-grid > label", ".choice-grid > label", ".permission-options > label",
        ".job-list > article", ".department-list > *", ".role-config-list > *",
        ".managed-skill-list > *", ".automation-admin-list > *",
        ".automation-event-stream > *", ".assistant-message", ".assistant-trace-list > *"
      ].join(", "),
      options: { color: "white", noMorph: true }
    },
    {
      name: "button-dark",
      selector: ".new-job-button, .batch-start-button, .primary-button, .save-prompt-button",
      options: { color: "black", button: true }
    }
  ];

  function applyProfile(element, profile) {
    if (!(element instanceof Element) || mounted.has(element) || element.dataset.lgProfile) return;
    element.dataset.liquidGlass = "";
    element.dataset.lgProfile = profile.name;
    element.dataset.blur = "0";
    element.dataset.cab = "2";
    element.dataset.depth = "10";
    element.dataset.strength = "100";
    Object.entries(profile.options).forEach(([key, value]) => {
      if (value === true) element.dataset[key] = "";
      else element.dataset[key] = value;
    });
  }

  function applyProfiles(root = document) {
    profiles.forEach(profile => {
      if (root instanceof Element && root.matches(profile.selector)) applyProfile(root, profile);
      root.querySelectorAll?.(profile.selector).forEach(element => applyProfile(element, profile));
    });
  }

  function init() {
    applyProfiles();
    scan();
    new MutationObserver(records => records.forEach(record => record.addedNodes.forEach(node => {
      if (!(node instanceof Element)) return;
      applyProfiles(node);
      scan(node);
    }))).observe(document.body, { childList: true, subtree: true });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init, { once: true });
  else init();
})();
