const {
  test: base,
  expect,
} = require("../../../scripts/ci/qa/node_modules/@playwright/test");
const { createFixtureServer } = require("./fixture-server.cjs");
const { createFinanceData } = require("./data.cjs");
const { createErrorAudit } = require("./error-audit.cjs");
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
      const audit = createErrorAudit(fixture.url),
        external = [],
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
        p.on("pageerror", (e) =>
          audit.record({
            kind: "pageerror",
            name: e.name,
            message: e.message,
            stack: e.stack,
            pageUrl: p.url(),
          }),
        );
        p.on("response", (response) => {
          const request = response.request();
          audit.record({
            kind: "response",
            url: response.url(),
            method: request.method(),
            resourceType: request.resourceType(),
            status: response.status(),
            pageUrl: p.url(),
          });
        });
        p.on("requestfailed", (request) =>
          audit.record({
            kind: "requestfailed",
            url: request.url(),
            method: request.method(),
            resourceType: request.resourceType(),
            errorText: request.failure()?.errorText,
            pageUrl: p.url(),
          }),
        );
        p.on("console", (msg) => {
          if (msg.type() !== "error") return;
          pendingConsole.push(
            Promise.all(
              msg
                .args()
                .map((arg) =>
                  arg
                    .evaluate((value) =>
                      value instanceof Error
                        ? {
                            exception: {
                              name: value.name,
                              message: value.message,
                              stack: value.stack,
                            },
                          }
                        : {
                            text:
                              typeof value === "string"
                                ? value
                                : JSON.stringify(value),
                          },
                    )
                    .catch(() => ({ text: msg.text() })),
                ),
            ).then((parts) =>
              audit.record({
                kind: "console",
                message:
                  parts
                    .map(
                      (p) =>
                        p.text ||
                        (p.exception
                          ? p.exception.name + ": " + p.exception.message
                          : ""),
                    )
                    .filter(Boolean)
                    .join(" ") || msg.text(),
                exception: parts.find((p) => p.exception)?.exception,
                source: msg.location(),
                pageUrl: p.url(),
              }),
            ),
          );
        });
      });
      await use(audit);
      await Promise.all(pendingConsole);
      audit.assertClean();
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
  const mapping = [
    "external_id", "date", "currency", "amount",
    "order_id", "statement_id", "category",
  ].includes(label);
  if (mapping)
    await combo.evaluate((input) => {
      const root = input.getRootNode();
      const events = [];
      const listen = (event) => {
        const target = event.target;
        events.push({
          type: event.type,
          target: target.closest(".ant-select-item-option")
            ? "option"
            : target === input ? "combobox" : target.tagName,
          x: event.clientX,
          y: event.clientY,
          scrollY: window.scrollY,
          expanded: input.getAttribute("aria-expanded"),
        });
        if (events.length > 12) events.shift();
      };
      const types = ["pointerdown", "pointerup", "click"];
      for (const type of types) root.addEventListener(type, listen, true);
      input.__selectionTrace = {
        events,
        remove: () => {
          for (const type of types) root.removeEventListener(type, listen, true);
        },
      };
    });
  try {
    await combo.click();
    const listId = await combo.getAttribute("aria-controls");
    const dropdown = page
      .locator(".ant-select-dropdown")
      .filter({ has: page.locator('[id="' + listId + '"]') });
    // rc-motion preparation can look stable before alignment. A click then scrolls
    // the portal and its option can move between pointerdown and pointerup.
    await expect(dropdown).not.toHaveClass(/\bant-slide-up-(?:appear|enter)\b/);
    await dropdown
      .locator(".ant-select-item-option")
      .getByText(option, { exact: true })
      .click();
    await expect(combo).toHaveAttribute("aria-expanded", "false");
    if (mapping) await expect(combo.locator("..")).toHaveText(option);
  } catch (error) {
    if (mapping)
      console.error("mapping selection pointer trace", {
        label,
        option,
        events: await combo
          .evaluate((input) => input.__selectionTrace?.events)
          .catch(() => []),
      });
    throw error;
  } finally {
    if (mapping)
    await combo.evaluate((input) => {
      input.__selectionTrace?.remove();
      delete input.__selectionTrace;
    }).catch(() => {});
  }
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
  // getAnimations() alone can run before rc-motion attaches its animation.
  await expect(dialog).not.toHaveClass(/\bant-zoom-(?:appear|enter)\b/);
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
