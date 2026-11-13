const {
  test,
  expect,
  open,
  select,
  close,
  call,
  shadowDialog,
} = require("./helpers.cjs");
test("groups and shop configuration, typed deletion and sync use real Shadow dialogs", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "shops");
  await page.getByRole("button", { name: "分组管理" }).click();
  let d = await shadowDialog(page);
  await d.getByLabel("新分组名称").fill("Created Group");
  await d.getByRole("button", { name: "添加分组" }).click();
  expect((await call(fixture, "POST", "/groups")).body).toEqual({
    name: "Created Group",
  });
  await d.getByLabel("分组名称 Created Group").fill("Renamed Group");
  await d
    .getByRole("button", { name: /^保\s*存$/ })
    .last()
    .click();
  expect((await call(fixture, "PUT", "/groups/g2")).body).toEqual({
    name: "Renamed Group",
  });
  await close(page);
  await page
    .getByRole("button", { name: /^配\s*置$/ })
    .first()
    .click();
  d = await shadowDialog(page);
  await d.getByLabel("店铺别名").fill("Saved Alias");
  await d.getByLabel("店铺结算币种").fill("php");
  await d.getByRole("button", { name: "保存配置" }).click();
  expect((await call(fixture, "PUT", "/shops/s1")).body).toMatchObject({
    alias: "Saved Alias",
    currency: "PHP",
    enabled: true,
    groupId: "g1",
    timezone: "Asia/Manila",
  });
  await page
    .getByRole("button", { name: "立即同步", exact: true })
    .first()
    .click();
  await call(fixture, "POST", "/shops/s1/sync");
  await page
    .getByRole("button", { name: /^移\s*除$/ })
    .first()
    .click();
  d = await shadowDialog(page);
  await d.getByLabel("输入店铺名称确认删除").fill(" Saved Alias");
  await expect(d.getByRole("button", { name: "永久删除" })).toBeDisabled();
  await d.getByLabel("输入店铺名称确认删除").fill("Saved Alias");
  await d.getByRole("button", { name: "永久删除" }).click();
  await call(fixture, "DELETE", "/shops/s1");
});
test("shop TikTok and Shopee advertising authorization are explicit and never open providers", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "shops");
  await page.getByRole("button", { name: "连接店铺", exact: true }).click();
  let d = await shadowDialog(page);
  await expect(d.getByRole("button", { name: "生成授权链接" })).toBeDisabled();
  await select(page, "店铺分组（必选）", "Fixture Group");
  await select(page, "授权平台", "TikTok Shop");
  await d.getByRole("button", { name: "生成授权链接" }).click();
  expect((await call(fixture, "POST", "/shops/authorize")).body).toEqual({
    platform: "TIKTOK_SHOP",
    market: "ROW",
    groupId: "g1",
    shopId: "",
  });
  await expect(d.getByLabel("官方授权链接")).toHaveValue(
    "https://fixture.invalid/authorization?state=synthetic",
  );
  await close(page);
  await page.getByRole("button", { name: "广告账户授权", exact: true }).click();
  d = await shadowDialog(page);
  await d.getByLabel("广告账户 ID", { exact: true }).fill("adv-fixture");
  await d.getByRole("button", { name: "生成授权链接" }).click();
  expect((await call(fixture, "POST", "/ads/authorize")).body).toMatchObject({
    advertiserId: "adv-fixture",
  });
  await close(page);
  await page.getByRole("button", { name: "生成 Shopee 广告授权链接" }).click();
  d = await shadowDialog(page);
  expect(
    fixture.requests.filter((c) => c.path.endsWith("/shopee-ads/authorize")),
  ).toHaveLength(0);
  await d.getByRole("button", { name: "生成授权链接" }).click();
  expect((await call(fixture, "POST", "/shopee-ads/authorize")).body).toEqual({
    organizationId: "fixture-company",
  });
  await expect(
    d.getByRole("link", { name: "在当前设备打开授权" }),
  ).toHaveAttribute("rel", "noopener noreferrer");
  await close(page);
});
test("nonempty TikTok account pause and update, Shopee pause, Qianyi identity validation", async ({
  page,
  fixture,
}) => {
  await open(page, fixture, "shops");
  await expect(
    page.getByText("Fixture Advertiser", { exact: true }).first(),
  ).toBeVisible();
  await page.getByRole("button", { name: /^暂\s*停$/ }).click();
  expect((await call(fixture, "PUT", "/ads/adv1")).body).toEqual({
    enabled: false,
  });
  await page.getByRole("button", { name: "更新授权", exact: true }).click();
  let d = await shadowDialog(page);
  await expect(d.getByLabel("广告账户 ID", { exact: true })).toHaveValue(
    "adv1",
  );
  await expect(d.getByLabel("广告账户 ID", { exact: true })).toBeDisabled();
  await d.getByRole("button", { name: "生成授权链接" }).click();
  expect((await call(fixture, "POST", "/ads/authorize")).body).toMatchObject({
    advertiserId: "adv1",
    shopId: "s1",
  });
  await close(page);
  await page.getByRole("button", { name: "千易全局配置", exact: true }).click();
  d = await shadowDialog(page);
  await expect(d.getByLabel("千易 App ID")).toHaveValue("fixture-public-id");
  await d.getByRole("button", { name: "保存全局配置" }).click();
  expect((await call(fixture, "PUT", "/qianyi")).body).toEqual({
    environment: "CN",
    appId: "fixture-public-id",
    appSecret: "",
    syncStart: "2026-10-01",
    enabled: false,
  });
  await d.getByLabel("千易 App ID").fill("changed-fixture-id");
  await d.getByRole("button", { name: "保存全局配置" }).click();
  await expect(
    d.getByText("更换环境或 App ID 必须重新输入 Secret"),
  ).toBeVisible();
  expect(
    fixture.requests.filter(
      (r) => r.method === "PUT" && r.path.endsWith("/qianyi"),
    ),
  ).toHaveLength(1);
  await close(page);
  await page.goto(
    fixture.url +
      "/?lang=zh&financeUi=modern&groupId=g1&shopId=s2#finance-shops",
  );
  await expect(page.getByText("Fixture CPC", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "暂停 Shopee 广告采集" }).click();
  expect((await call(fixture, "PUT", "/shopee-ads/9001")).body).toEqual({
    organizationId: "fixture-company",
    enabled: false,
  });
});
