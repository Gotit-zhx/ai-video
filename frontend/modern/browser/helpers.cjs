const {
  test: base,
  expect,
} = require("../../../scripts/ci/qa/node_modules/@playwright/test");
const { createFixtureServer } = require("./fixture-server.cjs");
const { createFinanceData } = require("./data.cjs");
const test = base.extend({
  fixture: async ({}, use) => {
    const data = createFinanceData();
    const overrides = new Map();
    const server = await createFixtureServer({
      finance: (c) =>
        overrides.has(c.method + " " + c.path)
          ? overrides.get(c.method + " " + c.path)(c)
          : data.handle(c),
    });
    await use({ ...server, ...data, overrides });
    await server.close();
    expect(server.unhandled, "unhandled API calls").toEqual([]);
    expect(server.errors, "fixture failures").toEqual([]);
  },
  audit: [
    async ({ context, fixture }, use) => {
      const errors = [],
        external = [],
        expectedErrors = [],
        pendingConsole = [];
      await context.route("**/*", async (route) => {
        const url = new URL(route.request().url());
        if (
          url.origin !== fixture.url &&
          !["data:", "blob:"].includes(url.protocol)
        ) {
          external.push(url.origin + url.pathname);
          await route.abort();
        } else await route.continue();
      });
      context.on("page", (p) => {
        p.on("pageerror", (e) => errors.push(e.message));
        p.on("console", (msg) => {
          if (msg.type() === "error") {
            // Firefox exposes Error objects as JSHandle@object in text().
            // Preserve their actual message so only the injected failure is allowed.
            pendingConsole.push(
              Promise.all(
                msg
                  .args()
                  .map((arg) =>
                    arg
                      .evaluate((value) =>
                        value instanceof Error
                          ? value.name + ": " + value.message
                          : typeof value === "string"
                            ? value
                            : JSON.stringify(value),
                      )
                      .catch(() => msg.text()),
                  ),
              ).then((parts) => {
                errors.push(
                  (parts.filter(Boolean).join(" ") || msg.text()) +
                    " " +
                    msg.location().url,
                );
              }),
            );
          }
        });
      });
      await use({ errors, expectedErrors });
      await Promise.all(pendingConsole);
      for (const pattern of expectedErrors) {
        for (let index = errors.length - 1; index >= 0; index--)
          if (pattern.test(errors[index])) errors.splice(index, 1);
      }
      expect(errors, "browser console/page errors").toEqual([]);
      expect(external, "external requests are forbidden").toEqual([]);
    },
    { auto: true },
  ],
});
const seed =
  "lang=zh&financeUi=modern&start=2026-10-01&end=2026-10-31&groupId=g1&shopId=s1&displayCurrency=CNY";
async function open(page, fixture, route = "overview", query = seed) {
  await page.goto(fixture.url + "/?" + query + "#finance-" + route);
  await expect(page.locator("[data-finance-modern-host]")).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "新版财务 BI", exact: true }),
  ).toBeVisible();
}
async function go(page, route) {
  await page.evaluate((route) => {
    location.hash = "finance-" + route;
  }, route);
}
async function select(page, label, option) {
  const combo = page.getByRole("combobox", { name: label, exact: true });
  await combo.click();
  const listId = await combo.getAttribute("aria-controls");
  const dropdown = page
    .locator(".ant-select-dropdown")
    .filter({ has: page.locator('[id=\"' + listId + '\"]') });
  await dropdown
    .locator(".ant-select-item-option")
    .getByText(option, { exact: true })
    .click();
  await expect(combo).toHaveAttribute("aria-expanded", "false");
}
async function close(page) {
  await page
    .getByRole("dialog")
    .last()
    .getByRole("button", { name: /Close|关闭/ })
    .first()
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
}
async function call(fixture, method, path, n = 1) {
  await expect
    .poll(
      () =>
        fixture.requests.filter(
          (r) => r.method === method && r.path === "/api/v1/finance" + path,
        ).length,
    )
    .toBe(n);
  return fixture.requests
    .filter((r) => r.method === method && r.path === "/api/v1/finance" + path)
    .at(-1);
}
async function shadowDialog(page) {
  const dialog = page.getByRole("dialog").last();
  await expect(dialog).toBeVisible();
  await dialog.evaluate(async (n) => {
    await Promise.all(n.getAnimations().map((a) => a.finished.catch(() => {})));
  });
  expect(
    await dialog.evaluate((n) => n.getRootNode() instanceof ShadowRoot),
  ).toBe(true);
  expect(
    await page.evaluate(
      () =>
        document.querySelectorAll(".ant-modal,.ant-drawer,.ant-select-dropdown")
          .length,
    ),
  ).toBe(0);
  return dialog;
}
module.exports = {
  test,
  expect,
  open,
  go,
  select,
  close,
  call,
  shadowDialog,
  seed,
};
module.exports.activateTab = async function (page, name) {
  const tab = page.getByRole("tab", { name, exact: true });
  const clipped = await tab.evaluate((n) => {
    const a = n.getBoundingClientRect(),
      b = n.closest(".ant-tabs-nav-wrap").getBoundingClientRect();
    return a.left < b.left || a.right > b.right;
  });
  if (clipped && (await page.locator(".ant-tabs-nav-more").isVisible())) {
    await page.locator(".ant-tabs-nav-more").hover();
    await page.locator(".ant-tabs-nav-more").click();
    await page.getByRole("option", { name, exact: true }).click();
  } else await tab.click();
  await expect(tab).toHaveAttribute("aria-selected", "true");
};
