const { test, expect, open, go, shadowDialog } = require("./helpers.cjs");
test("all nine primary routes, Qianyi and aliases use actual host and ESM", async ({
  page,
  fixture,
  audit,
}) => {
  await open(page, fixture);
  await expect(
    page.getByText("145,270.00", { exact: false }).first(),
  ).toBeVisible();
  const routes = [
    ["live", "今日订单金额"],
    ["settlements", "结算明细"],
    ["reconciliation", "平台账单与 API 交易核对；已结算不代表银行到账。"],
    ["orders", "全部订单"],
    ["fee-records", "费用矩阵导出"],
    ["costs", "商品成本表"],
    ["ads", "广告分析"],
    ["shops", "分组管理"],
    ["qianyi", "千易售后与收货核对"],
    ["fees", "费用矩阵导出"],
    ["expense-ledger", "贴单成本"],
  ];
  for (const [route, label] of routes) {
    await go(page, route);
    await expect(page.getByText(label, { exact: true }).first()).toBeVisible();
  }
  await go(page, "orders");
  await page.getByRole("button", { name: "详情", exact: true }).first().click();
  await shadowDialog(page);
  await expect(page.getByText("fixture historical cost")).toBeVisible();
});
test("no modern chunks for classic and nonfinance; return link stays classic", async ({
  page,
  fixture,
  audit,
}) => {
  const modern = [];
  page.on("request", (r) => {
    if (r.url().includes("/assets/finance-modern/")) modern.push(r.url());
  });
  await page.goto(fixture.url + "/?lang=zh#finance-overview");
  await expect(page.locator("#finance-page .fi-head")).toBeVisible();
  expect(modern).toEqual([]);
  await page.goto(fixture.url + "/?lang=zh#home");
  expect(modern).toEqual([]);
  await open(page, fixture);
  await page.getByRole("link", { name: "返回旧版", exact: true }).click();
  await expect(page.locator("#finance-page .fi-head")).toBeVisible();
  expect(new URL(page.url()).searchParams.get("financeUi")).toBe("legacy");
});
test("classic and modern consume identical controlled finance totals and FX facts", async ({
  page,
  context,
  fixture,
  audit,
}) => {
  await open(page, fixture);
  for (const value of ["145,270.00", "50,090.00", "4,920.00"])
    await expect(page.getByText(value, { exact: false }).first()).toBeVisible();
  const classic = await context.newPage();
  await classic.goto(
    fixture.url + "/?lang=zh&financeUi=legacy#finance-overview",
  );
  await expect(classic.locator("#finance-page .fi-head")).toBeVisible();
  for (const value of ["145,270", "50,090", "4,920"])
    await expect(
      classic.getByText(value, { exact: false }).first(),
    ).toBeVisible();
  const reads = fixture.requests.filter(
    (c) => c.path === "/api/v1/finance/overview",
  );
  expect(reads.length).toBeGreaterThanOrEqual(2);
  for (const r of reads) expect(r.query.displayCurrency).toBe("CNY");
});
test("outer portal layout scope stops when the retained modern host leaves finance", async ({
  page,
  fixture,
}) => {
  await open(page, fixture);
  await expect(
    page.locator(
      ".workspace:has(#finance-page:not([hidden]) [data-finance-modern-host])",
    ),
  ).toHaveCount(1);
  expect(
    await page.locator(".topbar").evaluate((n) => getComputedStyle(n).flexWrap),
  ).toBe("wrap");
  await page.evaluate(() => switchWorkspace("assistant"));
  await expect(page.locator("#finance-page")).toBeHidden();
  await expect(page.locator("[data-finance-modern-host]")).toHaveCount(1);
  await expect(
    page.locator(
      ".workspace:has(#finance-page:not([hidden]) [data-finance-modern-host])",
    ),
  ).toHaveCount(0);
  expect(
    await page.locator(".topbar").evaluate((n) => getComputedStyle(n).flexWrap),
  ).toBe("nowrap");
});
