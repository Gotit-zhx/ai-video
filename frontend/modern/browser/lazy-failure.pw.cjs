const { test, expect, open, go } = require("./helpers.cjs");
test("failed lazy page chunk keeps a usable legacy escape without replay", async ({
  page,
  context,
  fixture,
  audit,
}) => {
  await context.route("**/CostsPage-*.js", (route) => {
    audit.expectModuleFailure(route.request());
    return route.abort("failed");
  });
  await open(page, fixture);
  await go(page, "costs");
  await expect(
    page.getByText("新版财务页面加载失败", { exact: true }),
  ).toBeVisible();
  const fallback = page.getByRole("link", { name: "返回旧版", exact: true });
  await expect(fallback).toBeVisible();
  expect(fixture.requests.filter((r) => r.method !== "GET")).toHaveLength(0);
  await fallback.click();
  await expect(page.locator("#finance-page .fi-head")).toBeVisible();
  expect(new URL(page.url()).searchParams.get("financeUi")).toBe("legacy");
});
