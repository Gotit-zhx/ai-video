const { test, expect, open } = require("./helpers.cjs");
test("360px headline financial amount keeps its decimal digits on one line", async ({
  page,
  fixture,
}) => {
  await page.setViewportSize({ width: 360, height: 900 });
  await open(page, fixture);
  const amount = page
    .locator(".finance-metric h3")
    .filter({ hasText: "145,270.00" });
  await expect(amount).toBeVisible();
  const lines = await amount.evaluate((n) => {
    const walker = document.createTreeWalker(n, NodeFilter.SHOW_TEXT);
    let text;
    while ((text = walker.nextNode())) {
      const i = text.textContent.indexOf("145,270.00");
      if (i >= 0) {
        const range = document.createRange();
        range.setStart(text, i);
        range.setEnd(text, i + 10);
        return range.getClientRects().length;
      }
    }
    return 0;
  });
  expect(lines).toBe(1);
});
