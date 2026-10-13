import { spawnSync } from "node:child_process";

const files = [
  "scripts/check.mjs",
  "scripts/page-guard.test.mjs",
  "scripts/background-sanitize.test.mjs",
  "scripts/logout-revocation.test.mjs",
  "scripts/pending-revocation-storage.test.mjs",
  "scripts/revocation-service-worker.test.mjs",
  "scripts/inject-click.test.mjs",
  "scripts/image-planner.test.mjs",
  "scripts/observer-generation.test.mjs",
  "src/shared/constants.js",
  "src/shared/video-source.js",
  "scripts/video-source.test.mjs",
  "src/background/storage.js",
  "src/background/api.js",
  "src/background/service-worker.js",
  "src/popup/popup.js",
  "src/content/page-guard.js",
  "src/content/common.js",
  "src/content/shopee-data.js",
  "src/content/tiktok-data.js",
  "src/content/tiktok-observer-main.js",
  "src/content/tiktok-observer-client.js",
  "src/content/shopee.js",
  "src/content/tiktok.js",
  "src/content/inject.js"
];

for (const file of files) {
  const result = spawnSync(process.execPath, ["--check", file], { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status || 1);
}

const tests = spawnSync(process.execPath, ["scripts/page-guard.test.mjs"], { stdio: "inherit" });
if (tests.status !== 0) process.exit(tests.status || 1);

const skuTests = spawnSync(process.execPath, ["scripts/sku-parser.test.mjs"], { stdio: "inherit" });
if (skuTests.status !== 0) process.exit(skuTests.status || 1);

const sanitizeTests = spawnSync(process.execPath, ["scripts/background-sanitize.test.mjs"], { stdio: "inherit" });
if (sanitizeTests.status !== 0) process.exit(sanitizeTests.status || 1);

const logoutTests = spawnSync(process.execPath, ["scripts/logout-revocation.test.mjs"], { stdio: "inherit" });
if (logoutTests.status !== 0) process.exit(logoutTests.status || 1);

const pendingRevocationStorageTests = spawnSync(process.execPath, ["scripts/pending-revocation-storage.test.mjs"], { stdio: "inherit" });
if (pendingRevocationStorageTests.status !== 0) process.exit(pendingRevocationStorageTests.status || 1);

const revocationWorkerTests = spawnSync(process.execPath, ["scripts/revocation-service-worker.test.mjs"], { stdio: "inherit" });
if (revocationWorkerTests.status !== 0) process.exit(revocationWorkerTests.status || 1);

const priceTests = spawnSync(process.execPath, ["scripts/price-parser.test.mjs"], { stdio: "inherit" });
if (priceTests.status !== 0) process.exit(priceTests.status || 1);

const observerTests = spawnSync(process.execPath, ["scripts/observer-guard.test.mjs"], { stdio: "inherit" });
if (observerTests.status !== 0) process.exit(observerTests.status || 1);

const observerGenerationTests = spawnSync(process.execPath, ["scripts/observer-generation.test.mjs"], { stdio: "inherit" });
if (observerGenerationTests.status !== 0) process.exit(observerGenerationTests.status || 1);

const injectClickTests = spawnSync(process.execPath, ["scripts/inject-click.test.mjs"], { stdio: "inherit" });
if (injectClickTests.status !== 0) process.exit(injectClickTests.status || 1);

const imagePlannerTests = spawnSync(process.execPath, ["scripts/image-planner.test.mjs"], { stdio: "inherit" });
if (imagePlannerTests.status !== 0) process.exit(imagePlannerTests.status || 1);

console.log(`Checked ${files.length} JavaScript files plus page-guard, price, SKU, image-planner, background-sanitize, pending-revocation storage/API/worker, observer identity/generation, and inject-click tests.`);

const directLoginTests = spawnSync(process.execPath, ["scripts/direct-login.test.mjs"], { stdio: "inherit" });
if (directLoginTests.status !== 0) process.exit(directLoginTests.status || 1);

const mediaArchiveTests = spawnSync(process.execPath, ["scripts/media-archive.test.mjs"], { stdio: "inherit" });
if (mediaArchiveTests.status !== 0) process.exit(mediaArchiveTests.status || 1);

const videoSourceTests = spawnSync(process.execPath, ["scripts/video-source.test.mjs"], { stdio: "inherit" });
if (videoSourceTests.status !== 0) process.exit(videoSourceTests.status || 1);

for (const name of ["shopee-size-chart.test.mjs", "tiktok-metadata.test.mjs", "tiktok-category-dom.test.mjs", "tiktok-mobile-pdp.test.mjs", "version-gate.test.mjs"]) {
  const result = spawnSync(process.execPath, [`scripts/${name}`], { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status || 1);
}
