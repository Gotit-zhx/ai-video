const {
  test,
  expect,
  open,
  go,
  call,
  shadowDialog,
  activateTab,
} = require("./helpers.cjs");
const reads = (fixture, path) =>
  fixture.requests.filter(
    (r) => r.method === "GET" && r.path === "/api/v1/finance" + path,
  );
test("record pages use direct page queries and keep server totals and FX receipts", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "settlements");
  await expect(page.getByText("ORDER-1", { exact: true })).toBeVisible();
  for (const [route, view, kind] of [
    ["settlements", "settlements", "SETTLEMENT"],
    ["fee-records", "fees", "FEE"],
    ["orders", "orders", "ORDER"],
    ["reconciliation", "reconciliation", "BILL"],
  ]) {
    await go(page, route);
    await expect
      .poll(() =>
        reads(fixture, "/workspace/query").some(
          (r) => r.query.view === view && r.query.kind === kind,
        ),
      )
      .toBe(true);
  }
  expect(reads(fixture, "/overview")).toHaveLength(0);
  expect(reads(fixture, "/records")).toHaveLength(0);
  expect(reads(fixture, "/reconciliation")).toHaveLength(0);
});
test("advertising main and advertising-only ledger use separate supported page views", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "ads");
  await expect(
    page.getByRole("button", { name: "查看 / 核实分配" }),
  ).toBeVisible();
  expect(reads(fixture, "/workspace/query")[0].query).toMatchObject({
    view: "ads",
    kind: "AD",
    spendPage: "1",
  });
  await activateTab(page, "平台账单广告");
  await expect
    .poll(() =>
      reads(fixture, "/workspace/query").some(
        (r) => r.query.view === "ads-support" && r.query.kind === "FEE",
      ),
    )
    .toBe(true);
  for (const path of ["/overview", "/advertising/spend", "/ads", "/records"])
    expect(reads(fixture, path)).toHaveLength(0);
});
test("thin cost navigation leaves scope intact and management loads full settings lazily", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "costs");
  const context = await call(fixture, "GET", "/workspace/costs/context");
  const labels = await call(fixture, "GET", "/workspace/label-costs");
  expect(labels.query.shopId).toBe(context.query.shopId);
  expect(labels.query.groupId).toBe(context.query.groupId);
  expect(reads(fixture, "/workspace/costs")).toHaveLength(0);
  await activateTab(page, "订单成本");
  await call(fixture, "GET", "/workspace/costs/orders");
  expect(reads(fixture, "/workspace/costs")).toHaveLength(0);
  await activateTab(page, "成本表设置");
  await expect(
    page.getByRole("button", { name: "保存成本设置" }),
  ).toBeVisible();
  expect(reads(fixture, "/workspace/costs")).toHaveLength(1);
  await go(page, "costs?tab=input-review");
  await expect(
    page.getByRole("tab", { name: "月度贴单账单", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
  expect(reads(fixture, "/workspace/costs")).toHaveLength(1);
  expect(reads(fixture, "/workspace/costs/input-changes")).toHaveLength(0);
});
test("one explicit 404 selects the cached legacy page flow for the same owner", async ({
  page,
  fixture,
  audit,
}) => {
  fixture.overrides.set("GET /workspace/query", (c) =>
    audit.injectHttpFailure(c, 404, "Local legacy workspace endpoint"),
  );
  await open(page, fixture, "settlements");
  await expect(page.getByText("ORDER-1", { exact: true })).toBeVisible();
  await go(page, "fee-records");
  await expect(page.getByText("ORDER-1", { exact: true })).toBeVisible();
  expect(reads(fixture, "/workspace/query")).toHaveLength(1);
  expect(reads(fixture, "/records").length).toBeGreaterThanOrEqual(2);
  expect(reads(fixture, "/overview").length).toBeGreaterThanOrEqual(1);
});
test("status heartbeat preserves typed search and field dialog without rereading monetary pages", async ({
  page,
  fixture,
}) => {
  await page.clock.install();
  await open(page, fixture, "settlements");
  await expect(page.getByText("ORDER-1", { exact: true })).toBeVisible();
  await call(fixture, "GET", "/workspace/status");
  await page
    .getByRole("textbox", { name: "精确编号查询" })
    .fill("UNSUBMITTED-ORDER");
  const money = reads(fixture, "/workspace/query").length,
    initial = reads(fixture, "/workspace/status").length;
  await page.clock.fastForward(16000);
  await expect
    .poll(() => reads(fixture, "/workspace/status").length)
    .toBeGreaterThan(initial);
  await expect(page.getByRole("textbox", { name: "精确编号查询" })).toHaveValue(
    "UNSUBMITTED-ORDER",
  );
  expect(reads(fixture, "/workspace/query")).toHaveLength(money);
  await page.getByRole("button", { name: "字段设置" }).click();
  const dialog = await shadowDialog(page);
  const field = dialog.getByRole("checkbox").first();
  await expect(field).toBeVisible();
  const checked = await field.isChecked();
  await field.setChecked(!checked);
  const count = reads(fixture, "/workspace/status").length;
  await page.clock.fastForward(32000);
  await expect(page.getByRole("dialog")).toBeVisible();
  if (checked) await expect(field).not.toBeChecked();
  else await expect(field).toBeChecked();
  expect(reads(fixture, "/workspace/status")).toHaveLength(count);
  expect(reads(fixture, "/workspace/query")).toHaveLength(money);
});
