const {
  test,
  expect,
  open,
  go,
  select,
  close,
  call,
  shadowDialog,
} = require("./helpers.cjs");
const upload = {
  name: "fixture.csv",
  mimeType: "text/csv",
  buffer: Buffer.from(
    "external_id,date,currency,amount\nFIXTURE-IMPORT,2026-10-01,PHP,12",
  ),
};
test("personal field save, mapped bill preview and commit, binary report export", async ({
  page,
  fixture,
}) => {
  await open(page, fixture);
  await page.getByRole("button", { name: "字段设置", exact: true }).click();
  let d = await shadowDialog(page);
  await d
    .locator("section")
    .first()
    .getByRole("checkbox", { name: "业务日期" })
    .uncheck();
  await d.getByRole("button", { name: "保存字段" }).click();
  expect((await call(fixture, "PUT", "/fields/ALL")).body).toEqual({
    visible: ["external_id", "amount"],
    exported: ["external_id", "amount"],
  });
  await expect(d).toHaveCount(0);
  await page.getByRole("button", { name: "导入账单" }).click();
  d = await shadowDialog(page);
  await select(page, "* 店铺", "Fixture Shop");
  await d.locator("input[type=file]").setInputFiles(upload);
  await d.getByRole("button", { name: "校验并预览" }).click();
  await expect(d.getByText("预览行数: 1")).toBeVisible();
  for (const id of ["external_id", "date", "currency", "amount"])
    await select(page, id, id);
  await d.getByRole("button", { name: "确认导入" }).click();
  const imported = await call(fixture, "POST", "/imports");
  expect(imported.form).toMatchObject({
    kind: "BILL",
    shopId: "s1",
    mapping:
      '{"external_id":"external_id","date":"date","currency":"currency","amount":"amount"}',
  });
  expect(imported.form.file.text).toContain("FIXTURE-IMPORT");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "导出财务报表" }).click();
  await shadowDialog(page);
  await select(page, "导出类型", "平台退款明细");
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "开始导出" }).click();
  expect((await download).suggestedFilename()).toBe("fixture.csv");
  const exported = await call(fixture, "GET", "/export");
  expect(exported.query).toMatchObject({
    view: "REFUND",
    start: "2026-10-01",
    end: "2026-10-31",
    shopId: "s1",
    groupId: "g1",
    fxSnapshot: "fixture-fx-202610",
    orderScope: "SETTLED",
  });
});
test("uncertain export creation never replays; explicit recovery preserves body and idempotency key", async ({
  page,
  fixture,
  audit,
}) => {
  let submitted = 0;
  fixture.overrides.set("POST /fee-exports", (c) =>
    ++submitted === 1
      ? { fixtureError: "fixture result unknown", status: 503 }
      : fixture.handle(c),
  );
  audit.expectedErrors.push(/503/);
  await open(page, fixture, "fee-records");
  await page.getByRole("button", { name: "费用矩阵导出", exact: true }).click();
  const d = await shadowDialog(page);
  await d.getByRole("button", { name: "创建导出任务" }).click();
  await expect(
    d.getByText("上次提交结果待确认，不会自动重复创建"),
  ).toBeVisible();
  expect(submitted).toBe(1);
  await expect(d.getByRole("button", { name: "创建导出任务" })).toBeDisabled();
  await d.getByRole("button", { name: "刷新任务状态" }).click();
  expect(submitted).toBe(1);
  await d.getByRole("button", { name: "确认使用同一请求重试" }).click();
  await call(fixture, "POST", "/fee-exports", 2);
  const calls = fixture.requests.filter(
    (r) => r.path.endsWith("/fee-exports") && r.method === "POST",
  );
  expect(calls[1].body).toEqual(calls[0].body);
  expect(calls[1].headers["idempotency-key"]).toBe(
    calls[0].headers["idempotency-key"],
  );
  const download = page.waitForEvent("download");
  await d.getByRole("button", { name: /^下\s*载$/ }).click();
  await download;
  await call(fixture, "GET", "/fee-exports/j1/download");
});
test("cancelled download aborts its request and navigation discards late reads", async ({
  page,
  fixture,
}) => {
  fixture.overrides.set("GET /export", () => ({
    binary: Buffer.from("delayed synthetic"),
    delay: 1500,
  }));
  await open(page, fixture);
  await page.getByRole("button", { name: "导出财务报表" }).click();
  await page.getByRole("button", { name: "开始导出" }).click();
  await call(fixture, "GET", "/export");
  await page.getByRole("button", { name: "取消导出" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect
    .poll(
      () => fixture.requests.find((r) => r.path.endsWith("/export"))?.aborted,
    )
    .toBe(true);
  fixture.overrides.set("GET /records", () => ({
    items: [{ id: "late", order_id: "LATE-SHOULD-NOT-APPEAR" }],
    total: 1,
    delay: 1000,
  }));
  await go(page, "orders");
  await call(fixture, "GET", "/records");
  await go(page, "shops");
  await expect(page.getByRole("button", { name: "分组管理" })).toBeVisible();
  await expect(page.getByText("LATE-SHOULD-NOT-APPEAR")).toHaveCount(0);
  await expect
    .poll(
      () => fixture.requests.find((r) => r.path.endsWith("/records"))?.aborted,
    )
    .toBe(true);
});
