const { test, expect, open, go } = require("./helpers.cjs");
test("lazy chart renders data, recreates for theme and leaves no canvas after navigation", async ({
  page,
  fixture,
}) => {
  await page.clock.setFixedTime("2026-11-12T11:03:00Z");
  const requested = new Set(),
    charts = [];
  page.on("request", (r) => {
    if (r.url().includes("/assets/finance-modern/"))
      requested.add(new URL(r.url()).pathname);
    if (/\/chart-[^/]+\.js$/.test(new URL(r.url()).pathname))
      charts.push(r.url());
  });
  await open(page, fixture, "orders");
  const root = page.locator("html"),
    day = page.getByRole("button", { name: "日间", exact: true }),
    night = page.getByRole("button", { name: "夜间", exact: true });
  await expect(root).toHaveAttribute("data-theme-mode", "auto");
  await expect(root).toHaveAttribute("data-theme", "dark");
  await day.click();
  await expect(day).toHaveAttribute("aria-pressed", "true");
  await expect(root).toHaveAttribute("data-theme-mode", "light");
  await expect(root).toHaveAttribute("data-theme", "light");
  await expect(
    page.getByRole("button", { name: "详情", exact: true }).first(),
  ).toBeVisible();
  expect(charts).toEqual([]);
  expect(
    requested.size,
    "initial record route modern asset requests",
  ).toBeLessThanOrEqual(18);
  await go(page, "overview");
  const canvas = page.locator("[role=img][aria-label=Chart] canvas").first();
  await expect(canvas).toBeVisible();
  expect(charts).toHaveLength(1);
  expect(
    await canvas.evaluate((c) => {
      const p = c.getContext("2d").getImageData(0, 0, c.width, c.height).data;
      return p.some((v, i) => i % 4 === 3 && v > 0);
    }),
  ).toBe(true);
  const before = await canvas.elementHandle();
  await night.click();
  await expect(night).toHaveAttribute("aria-pressed", "true");
  await expect(root).toHaveAttribute("data-theme-mode", "dark");
  await expect(root).toHaveAttribute("data-theme", "dark");
  await expect.poll(() => before.evaluate((n) => n.isConnected)).toBe(false);
  await expect(canvas).toBeVisible();
  await go(page, "orders");
  await expect(page.locator("[role=img][aria-label=Chart] canvas")).toHaveCount(
    0,
  );
});
test("FX implementation identifiers are disclosed as labelled expandable evidence", async ({
  page,
  fixture,
}) => {
  await open(page, fixture);
  await expect(
    page.getByText("fixture-fx-202610", { exact: true }),
  ).toBeHidden();
  await page.getByText("历史汇率依据", { exact: true }).click();
  await expect(
    page.getByText("fixture-fx-202610", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("汇率来源", { exact: true })).toBeVisible();
});
