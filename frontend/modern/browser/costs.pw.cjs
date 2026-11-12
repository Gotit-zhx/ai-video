const {
  test,
  expect,
  open,
  go,
  select,
  close,
  call,
  shadowDialog,
  activateTab,
} = require("./helpers.cjs");
test("cost mapping, manual price, warehouse settings preserve confirmations and versions", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "costs?tab=mapping");
  await page.getByRole("button", { name: "核对成本关联" }).click();
  let d = await shadowDialog(page);
  await d.getByLabel("换算数量", { exact: true }).fill("2.000001");
  await d.getByRole("button", { name: "提交成本关联" }).click();
  expect(fixture.requests.filter((c) => c.method === "PUT")).toHaveLength(0);
  await d.getByLabel("已核对销售规格、单价和计价单位").check();
  await d.getByRole("button", { name: "提交成本关联" }).click();
  expect(
    (await call(fixture, "PUT", "/workspace/costs/s1/mapping")).body,
  ).toMatchObject({
    factor: "2.000001",
    version: 3,
    confirmed: true,
    warehouse: "菲律宾",
    code: "SKU",
  });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("tab", { name: "手动管理", exact: true }).click();
  await page.getByRole("button", { name: "修改手动成本" }).click();
  d = await shadowDialog(page);
  await d.getByLabel("人民币单价", { exact: true }).fill("0");
  await d.getByLabel("按新单价重算历史及后续订单").check();
  await d.getByRole("button", { name: "提交手动成本" }).click();
  await expect(d.getByText("零元价格必须确认真实零成本")).toBeVisible();
  await d.getByLabel("确认真实零成本").check();
  await d.getByRole("button", { name: "提交手动成本" }).click();
  expect(
    (await call(fixture, "PUT", "/workspace/costs/groups/g1/manual/m1")).body,
  ).toMatchObject({
    unitPriceCny: "0",
    version: 2,
    confirmed: true,
    zeroConfirmed: true,
  });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await activateTab(page, "成本表设置");
  await select(page, "计价月份口径", "发货月份");
  await page.getByRole("button", { name: "保存成本设置" }).click();
  expect(
    (await call(fixture, "PUT", "/workspace/costs/s1/settings")).body,
  ).toMatchObject({
    warehouse: "菲律宾",
    version: 2,
    rules: {
      currency: "CNY",
      basis: "shipment",
      unit: "包",
      missing: "pending",
    },
  });
});
test("staged cost review needs historical acknowledgements and exact version", async ({
  page,
  fixture,
}) => {
  fixture.state.cost.inputSafety = { enabled: true };
  await open(page, fixture, "costs?tab=input-review");
  await page.getByRole("button", { name: "查看核对单" }).click();
  const d = await shadowDialog(page);
  await expect(d.getByRole("button", { name: "确认应用" })).toBeDisabled();
  await d.getByLabel("我已核对实际影响范围").check();
  await d.getByLabel("我已确认会影响历史及后续订单").check();
  await d.getByRole("button", { name: "确认应用" }).click();
  expect(
    (await call(fixture, "POST", "/workspace/costs/input-changes/c1/confirm"))
      .body,
  ).toMatchObject({ version: 5 });
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
test("brush all-query principal count and fee total survive pagination; preview then import source", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "expense-ledger");
  await page.getByRole("tab", { name: "刷单成本", exact: true }).click();
  await expect(page.getByText("87", { exact: true })).toBeVisible();
  await expect(page.getByText("432.10", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: /页 2/ }).click();
  await expect
    .poll(
      () =>
        fixture.requests
          .filter((r) => r.path.endsWith("/workspace/charges"))
          .at(-1)?.query.page,
    )
    .toBe("2");
  await expect(page.getByText("87", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "查看刷单来源" }).click();
  let d = await shadowDialog(page);
  await expect(d.getByText("BRUSH-1")).toBeVisible();
  await close(page);
  await page.getByRole("button", { name: "预览并导入刷单来源" }).click();
  d = await shadowDialog(page);
  await d
    .locator("input[type=file]")
    .setInputFiles({
      name: "fixture-brush.csv",
      mimeType: "text/csv",
      buffer: Buffer.from("order,principal\nBRUSH-2,10"),
    });
  await d.getByRole("button", { name: "预览刷单来源" }).click();
  await expect(d.getByText("BRUSH-2")).toBeVisible();
  await d.getByRole("button", { name: "确认导入刷单来源" }).click();
  expect(
    (await call(fixture, "POST", "/workspace/charges/import")).form.groupId,
  ).toBe("g1");
});
test("Qianyi qualified controls, evidence and export retain server receipt semantics", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "qianyi");
  const request = await call(fixture, "GET", "/qianyi/returns");
  expect(request.query).toMatchObject({
    type: "SALES_RETURN",
    returnStatus: "FINISH",
    receiptScope: "RECEIVED",
    receiptStatus: "SIGNED",
    dateField: "RECEIPT",
    currency: "",
  });
  await page.getByRole("button", { name: "查看售后证据" }).click();
  const d = await shadowDialog(page);
  await expect(d.getByText("fixture return signed")).toBeVisible();
  await expect(d.getByText("SKU", { exact: true }).first()).toBeVisible();
  await close(page);
  await select(page, "退回物流状态", "IN_TRANSIT");
  await page.getByRole("button", { name: "查询售后", exact: true }).click();
  expect(
    (await call(fixture, "GET", "/qianyi/returns", 2)).query,
  ).toMatchObject({
    receiptScope: "ALL",
    receiptStatus: "IN_TRANSIT",
    dateField: "RECEIPT",
  });
  const downloaded = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出千易售后" }).click();
  await downloaded;
  expect(
    (await call(fixture, "GET", "/qianyi/returns/export")).query,
  ).toMatchObject({ receiptScope: "ALL", receiptStatus: "IN_TRANSIT" });
});
test("cost and monthly label imports retain source boundaries; removal requires acknowledgement", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "costs?tab=costs");
  await page.getByRole("button", { name: "导入分组成本表" }).click();
  let d = await shadowDialog(page);
  await d
    .getByLabel("选择成本文件")
    .setInputFiles({
      name: "fixture-cost.csv",
      mimeType: "text/csv",
      buffer: Buffer.from("sku,warehouse,month,price\nSKU,菲律宾,2026-10,12"),
    });
  await expect(
    d.getByRole("button", { name: "确认导入成本文件" }),
  ).toBeDisabled();
  await d.getByLabel("已核对分组及来源资料").check();
  await d.getByRole("button", { name: "确认导入成本文件" }).click();
  expect(
    (await call(fixture, "POST", "/workspace/costs/import")).form.groupId,
  ).toBe("g1");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "移除成本文件" }).click();
  d = await shadowDialog(page);
  await expect(d.getByRole("button", { name: "确认删除" })).toBeDisabled();
  await d.getByLabel("我已核对删除影响").check();
  await d.getByRole("button", { name: "确认删除" }).click();
  await call(fixture, "DELETE", "/workspace/costs/groups/g1/files/f1");
  await go(page, "expense-ledger");
  await page
    .getByLabel("选择月度账单")
    .setInputFiles({
      name: "fixture-label.xlsx",
      mimeType:
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      buffer: Buffer.from(
        "synthetic transport fixture; parser is outside browser scope",
      ),
    });
  await page.getByRole("button", { name: "上传月度账单" }).click();
  const label = await call(fixture, "POST", "/workspace/label-costs/import");
  expect(label.form).not.toHaveProperty("groupId");
  await page.getByRole("button", { name: "重新匹配全部账单" }).click();
  await call(fixture, "POST", "/workspace/label-costs/rematch");
});
test("pending cost selection and personal cost export use full company scope", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "costs?tab=orders");
  await page.getByRole("button", { name: "所有店铺待补齐" }).click();
  await expect
    .poll(
      () =>
        fixture.requests
          .filter((r) => r.path.endsWith("/workspace/costs/orders"))
          .at(-1)?.query.costStatus,
    )
    .toBe("PENDING");
  const query = fixture.requests
    .filter((r) => r.path.endsWith("/workspace/costs/orders"))
    .at(-1).query;
  for (const key of ["shopId", "groupId", "platform", "currency"])
    expect(query[key]).toBe("");
  await page.getByRole("button", { name: "订单成本导出" }).click();
  const d = await shadowDialog(page);
  await d.getByRole("button", { name: "创建导出任务" }).click();
  const body = (await call(fixture, "POST", "/cost-exports")).body;
  expect(body.costStatus).toBe("PENDING");
  for (const key of ["shopId", "groupId", "platform", "currency"])
    expect(body[key]).toBe("");
});
