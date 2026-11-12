const {
  defineConfig,
} = require("../../../scripts/ci/qa/node_modules/@playwright/test");
const path = require("node:path");
const fs = require("node:fs");
const output = path.resolve(
  __dirname,
  "../../../target/finance-modern-browser",
);
const profile = path.resolve(
  __dirname,
  "../../../.dev/finance-browser-profile",
);
for (const folder of ["app", "local"])
  fs.mkdirSync(path.join(profile, folder), { recursive: true, mode: 0o700 });
module.exports = defineConfig({
  testDir: __dirname,
  testMatch: "**/*.pw.cjs",
  timeout: 45000,
  expect: { timeout: 7000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ["list"],
    ["json", { outputFile: path.join(output, "results.json") }],
  ],
  outputDir: path.join(output, "artifacts"),
  use: {
    headless: true,
    viewport: { width: 1440, height: 1000 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "chromium", use: { browserName: "chromium" } },
    {
      name: "firefox",
      use: {
        browserName: "firefox",
        launchOptions: {
          env: {
            ...process.env,
            MOZ_APP_DATA: path.join(profile, "app"),
            MOZ_LOCAL_APP_DATA: path.join(profile, "local"),
          },
        },
      },
    },
    { name: "webkit", use: { browserName: "webkit" } },
    {
      name: "chromium-mobile",
      testMatch: /(operations|shops|costs|advertising|lifecycle)\.pw\.cjs$/,
      use: {
        browserName: "chromium",
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
});
