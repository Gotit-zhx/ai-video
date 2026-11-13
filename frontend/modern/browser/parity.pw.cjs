const { test, expect, open, go, call, shadowDialog } = require("./helpers.cjs");
test("classic and modern binary exports freeze the same query and historical FX receipt", async ({
  page,
  context,
  fixture,
}) => {
  await open(page, fixture, "overview", "lang=zh&financeUi=modern");
  await page.getByRole("button", { name: "导出财务报表" }).click();
  const exportDialog = await shadowDialog(page);
  let download = page.waitForEvent("download");
  await exportDialog.getByRole("button", { name: "开始导出" }).click();
  await download;
  const modern = (await call(fixture, "GET", "/export")).query;
  const classic = await context.newPage();
  await classic.goto(
    fixture.url + "/?lang=zh&financeUi=legacy#finance-overview",
  );
  await classic.getByRole("button", { name: "导出报表", exact: true }).click();
  download = classic.waitForEvent("download");
  await classic.getByRole("button", { name: "下载报表" }).click();
  await download;
  const old = (await call(fixture, "GET", "/export", 2)).query;
  for (const key of [
    "view",
    "start",
    "end",
    "groupId",
    "shopId",
    "platform",
    "currency",
    "displayCurrency",
    "fxSnapshot",
    "orderScope",
    "format",
  ])
    expect(old[key] || "").toBe(modern[key] || "");
});
test("classic and modern brush and advertising read identical server totals without page arithmetic", async ({
  page,
  context,
  fixture,
}) => {
  await open(page, fixture, "expense-ledger");
  await page.getByRole("tab", { name: "刷单成本", exact: true }).click();
  await expect(page.getByText("87", { exact: true })).toBeVisible();
  await expect(page.getByText("432.10", { exact: false })).toBeVisible();
  const classic = await context.newPage();
  await classic.goto(
    fixture.url + "/?lang=zh&financeUi=legacy#finance-expense-ledger",
  );
  await classic.getByRole("button", { name: "刷单成本", exact: true }).click();
  await expect(classic.getByText("87", { exact: true })).toBeVisible();
  await expect(
    classic.getByText("432.10", { exact: false }).first(),
  ).toBeVisible();
  const reads = fixture.requests.filter((r) =>
    r.path.endsWith("/workspace/charges"),
  );
  expect(reads.length).toBeGreaterThanOrEqual(2);
  for (const r of reads) expect(r.query.groupId).toBe("g1");
  await go(page, "ads");
  await expect(
    page.getByRole("button", { name: "查看 / 核实分配" }),
  ).toBeVisible();
  await classic.evaluate(() => (location.hash = "finance-ads"));
  await expect(
    classic.getByText("25.00", { exact: false }).first(),
  ).toBeVisible();
  expect(
    fixture.requests.filter((r) => r.path.endsWith("/advertising/spend"))
      .length,
  ).toBeGreaterThanOrEqual(2);
});
