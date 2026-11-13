const {
  test,
  expect,
  open,
  go,
  close,
  call,
  shadowDialog,
} = require("./helpers.cjs");
test("reader has fields and evidence but no management actions", async ({
  page,
  fixture,
}) => {
  fixture.setUser({
    organizationId: "fixture-company",
    userId: "reader",
    username: "reader",
    permissions: ["finance:view"],
    roles: [],
  });
  await open(page, fixture);
  await expect(page.getByRole("button", { name: "导入账单" })).toHaveCount(0);
  await page.getByRole("button", { name: "字段设置", exact: true }).click();
  const fields = await shadowDialog(page);
  await fields.getByRole("button", { name: "保存字段" }).click();
  await call(fixture, "PUT", "/fields/ALL");
  await go(page, "shops");
  await expect(
    page.getByText("Fixture Shop", { exact: true }).first(),
  ).toBeVisible();
  for (const name of [
    "连接店铺",
    "分组管理",
    "广告账户授权",
    "生成 Shopee 广告授权链接",
    "千易全局配置",
  ])
    await expect(page.getByRole("button", { name, exact: true })).toHaveCount(
      0,
    );
  expect(
    fixture.requests.filter(
      (r) =>
        !["GET", "HEAD"].includes(r.method) && !r.path.includes("/fields/"),
    ),
  ).toHaveLength(0);
});
test("auth expiry clears modern content and uses the real host login flow", async ({
  page,
  fixture,
  audit,
}) => {
  fixture.overrides.set("GET /overview", (c) =>
    audit.injectHttpFailure(c, 401, "fixture session expired"),
  );
  await page.goto(fixture.url + "/?lang=zh&financeUi=modern#finance-overview");
  await expect(page.locator("[data-finance-modern-host]")).toHaveCount(0);
  await expect(page.locator("#login-form")).toBeVisible();
  expect(
    fixture.requests.filter((r) => r.path.endsWith("/overview")),
  ).toHaveLength(1);
});
test("classic and modern tabs keep filters and unsubmitted field drafts independently", async ({
  page,
  context,
  fixture,
}) => {
  await open(page, fixture);
  const classic = await context.newPage();
  await classic.goto(
    fixture.url + "/?lang=zh&financeUi=legacy#finance-overview",
  );
  await expect(classic.locator("#finance-page .fi-head")).toBeVisible();
  await classic
    .locator("#finance-page")
    .getByLabel("开始日期", { exact: true })
    .fill("2026-10-05");
  await classic
    .locator("#finance-page")
    .getByLabel("开始日期", { exact: true })
    .blur();
  await page
    .getByRole("textbox", { name: "开始日期", exact: true })
    .fill("2026-10-09");
  await page.getByRole("textbox", { name: "开始日期", exact: true }).blur();
  await expect(
    classic.locator("#finance-page").getByLabel("开始日期", { exact: true }),
  ).toHaveValue("2026-10-05");
  await expect(
    page.getByRole("textbox", { name: "开始日期", exact: true }),
  ).toHaveValue("2026-10-09");
  await page.getByRole("button", { name: "字段设置", exact: true }).click();
  let d = await shadowDialog(page);
  await d
    .locator("section")
    .first()
    .getByRole("checkbox", { name: "业务日期" })
    .uncheck();
  await classic.getByRole("button", { name: "字段设置", exact: true }).click();
  await expect(
    d.locator("section").first().getByRole("checkbox", { name: "业务日期" }),
  ).not.toBeChecked();
  expect(
    fixture.requests.filter(
      (r) => r.method === "PUT" && r.path.includes("/fields/"),
    ),
  ).toHaveLength(0);
});
test("same-company user change discards a late authorization without replay", async ({
  page,
  fixture,
}) => {
  fixture.overrides.set("POST /shopee-ads/authorize", () => ({
    authorizationUrl: "https://fixture.invalid/late-private-link",
    delay: 800,
  }));
  await open(page, fixture, "shops");
  await page.getByRole("button", { name: "生成 Shopee 广告授权链接" }).click();
  const authorization = await shadowDialog(page);
  await authorization.getByRole("button", { name: "生成授权链接" }).click();
  await call(fixture, "POST", "/shopee-ads/authorize");
  await page.evaluate(() => {
    currentUser = { ...currentUser, userId: "fixture-second-user" };
    document.dispatchEvent(new Event("hoyee:languagechange"));
  });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("button", { name: "生成 Shopee 广告授权链接" }).click();
  await shadowDialog(page);
  await expect(page.getByLabel("Shopee 官方授权链接")).toHaveCount(0);
  await page.waitForTimeout(900);
  await expect(page.getByLabel("Shopee 官方授权链接")).toHaveCount(0);
  expect(
    fixture.requests.filter((r) => r.path.endsWith("/shopee-ads/authorize")),
  ).toHaveLength(1);
});
test("actual classic experience click creates a new tab; bookmark and independent drafts survive", async ({
  page,
  context,
  fixture,
}) => {
  await page.goto(fixture.url + "/?lang=zh#finance-overview");
  await expect(page.locator("#finance-page .fi-head")).toBeVisible();
  await page
    .locator("#finance-page")
    .getByLabel("开始日期", { exact: true })
    .fill("2026-10-05");
  await page
    .locator("#finance-page")
    .getByLabel("开始日期", { exact: true })
    .blur();
  const entry = page.locator("[data-finance-modern-entry]");
  await expect(entry).toHaveCount(1);
  await expect(entry).toHaveAttribute("target", "_blank");
  await expect(entry).toHaveAttribute("rel", "noopener noreferrer");
  const popup = context.waitForEvent("page");
  await entry.click();
  const modern = await popup;
  await expect(
    modern.getByRole("heading", { name: "新版财务 BI", exact: true }),
  ).toBeVisible();
  expect(await modern.evaluate(() => window.opener === null)).toBe(true);
  await expect(
    modern.getByRole("textbox", { name: "开始日期", exact: true }),
  ).toHaveValue("2026-10-05");
  await page.getByRole("button", { name: "字段设置", exact: true }).click();
  const oldDraft = page.locator(".fi-dialog input[type=checkbox]").first();
  await oldDraft.uncheck();
  await modern
    .getByRole("textbox", { name: "开始日期", exact: true })
    .fill("2026-10-09");
  await modern.getByRole("textbox", { name: "开始日期", exact: true }).blur();
  await expect(
    page.locator("#finance-page").getByLabel("开始日期", { exact: true }),
  ).toHaveValue("2026-10-05");
  await expect(oldDraft).not.toBeChecked();
  await modern.reload();
  await expect(
    modern.getByRole("heading", { name: "新版财务 BI", exact: true }),
  ).toBeVisible();
  await expect(
    modern.getByRole("textbox", { name: "开始日期", exact: true }),
  ).toHaveValue("2026-10-05");
  await expect(oldDraft).not.toBeChecked();
  expect(
    fixture.requests.filter(
      (r) => r.method === "PUT" && r.path.includes("/fields/"),
    ),
  ).toHaveLength(0);
});
