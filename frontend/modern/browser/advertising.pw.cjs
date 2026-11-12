const {
  test,
  expect,
  open,
  call,
  shadowDialog,
  close,
} = require("./helpers.cjs");
test("source allocation sends exact original components and fingerprint; unknown write stays blocked", async ({
  page,
  fixture,
  audit,
}) => {
  fixture.overrides.set("PUT /advertising/spend/ad1/allocations", () => ({
    fixtureError: "fixture allocation result unknown",
    status: 503,
  }));
  audit.expectedErrors.push(/503/);
  await open(page, fixture, "ads");
  await page.getByRole("button", { name: "查看 / 核实分配" }).click();
  const d = await shadowDialog(page);
  await expect(d.getByText("fixture-fingerprint")).toBeVisible();
  await d.getByRole("button", { name: "保存核实金额" }).click();
  expect(
    (await call(fixture, "PUT", "/advertising/spend/ad1/allocations")).body,
  ).toEqual({
    organizationId: "fixture-company",
    fingerprint: "fixture-fingerprint",
    allocations: [
      { shop_id: "s1", amount: "25", cash_amount: "20", credit_amount: "5" },
    ],
  });
  await expect(d.getByRole("button", { name: "保存核实金额" })).toBeDisabled();
  await close(page);
  expect(
    fixture.requests.filter((r) => r.path.endsWith("/allocations")),
  ).toHaveLength(1);
});
test("funding import previews then commits original account identity and no fabricated mapping", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "ads");
  await page.getByRole("button", { name: "导入资金消耗" }).click();
  const d = await shadowDialog(page);
  await d.getByLabel("真实平台账户 ID").fill("fixture-account");
  await d.getByLabel("账户名称").fill("Fixture account");
  await d
    .locator("input[type=file]")
    .setInputFiles({
      name: "fixture-funding.csv",
      mimeType: "text/csv",
      buffer: Buffer.from("date,amount\n2026-10-01,12"),
    });
  await d.getByRole("button", { name: "校验并预览" }).click();
  await expect(d.getByText("预览行数: 1")).toBeVisible();
  expect(
    (await call(fixture, "POST", "/advertising/imports")).form,
  ).toMatchObject({
    preview: "true",
    accountId: "fixture-account",
    organizationId: "fixture-company",
  });
  await d.getByRole("button", { name: "确认导入" }).click();
  const form = (await call(fixture, "POST", "/advertising/imports", 2)).form;
  expect(form).toMatchObject({
    preview: "false",
    accountId: "fixture-account",
    organizationId: "fixture-company",
  });
  expect(form).not.toHaveProperty("mapping");
});
