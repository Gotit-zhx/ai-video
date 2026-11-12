const { test, expect, open, seed } = require("./helpers.cjs");
const messages = require("../src/shared/messages.json"),
  catalog = require("../../i18n/catalog.json");
const locales = ["zh", "en", "id", "th", "vi", "ms", "fil"],
  widths = [360, 390, 768, 1024, 1440, 1920];
function t(label, locale) {
  const i = locales.indexOf(locale) - 1;
  return i < 0 ? label : (messages[label] || catalog[label] || [])[i] || label;
}
for (let i = 0; i < 14; i++) {
  const locale = locales[i % 7],
    theme = i < 7 ? "light" : "dark",
    width = widths[(i + (i >= 7 ? 2 : 0)) % 6];
  test(`bounded pair matrix ${locale} ${theme} ${width}px`, async ({
    page,
    fixture,
  }, info) => {
    await page.setViewportSize({ width, height: 900 });
    await open(page, fixture);
    await page
      .getByRole("button", {
        name: theme === "dark" ? "夜间" : "日间",
        exact: true,
      })
      .click();
    await page
      .getByRole("combobox", { name: "Language", exact: true })
      .selectOption(locale);
    await expect(
      page.getByRole("heading", {
        name: t("新版财务 BI", locale),
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      page.locator("[role=img][aria-label=Chart] canvas").first(),
    ).toBeVisible();
    expect(
      await page.evaluate(() => ({
        outer: document.documentElement.scrollWidth,
        viewport: innerWidth,
      })),
    ).toEqual({ outer: width, viewport: width });
    const button = page.getByRole("button", {
      name: t("字段设置", locale),
      exact: true,
    });
    await button.scrollIntoViewIfNeeded();
    await button.click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    expect(
      await dialog.evaluate((n) => n.getRootNode() instanceof ShadowRoot),
    ).toBe(true);
    const bounds = await dialog.boundingBox();
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(width + 1);
    const save = dialog.getByRole("button", {
      name: t("保存字段", locale),
      exact: true,
    });
    await save.scrollIntoViewIfNeeded();
    await expect(save).toBeInViewport();
    await dialog
      .getByRole("button", {
        name: locale === "fil" ? "Close" : t("关闭", locale),
        exact: true,
      })
      .first()
      .click();
    const scroll = page.locator(".ant-table-content").first();
    if (width <= 768) {
      expect(await scroll.evaluate((n) => n.scrollWidth > n.clientWidth)).toBe(
        true,
      );
      await scroll.evaluate((n) => (n.scrollLeft = n.scrollWidth));
      expect(await scroll.evaluate((n) => n.scrollLeft)).toBeGreaterThan(0);
    }
    if (i === 0 || i === 8 || i === 10)
      await page.screenshot({
        path: info.outputPath(`synthetic-${locale}-${theme}-${width}.png`),
        fullPage: true,
      });
  });
}
