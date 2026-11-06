import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

class MockElement {
  constructor(text, parentText = text) {
    this.textContent = text;
    this.parentElement = { textContent: parentText };
    this.tagName = "DIV";
  }

  getAttribute() { return null; }
  getBoundingClientRect() { return { width: 120, height: 24 }; }
  closest() { return null; }
}

const current = new MockElement("₱159–269", "Current price ₱159–269");
const preview = new MockElement("₱139", "Sale starts tomorrow · upcoming price ₱139");
const original = new MockElement("₱318–538", "Original price ₱318–538");
const rating = new MockElement("4.8", "4.8 rating");
const ratingCount = new MockElement("279 Ratings");
const sold = new MockElement("1K+ Sold");
const selectorValues = new Map([
  [".current", [preview, current]],
  [".original", [original]],
  [".rating", [rating]],
  [".rating-count", [ratingCount]],
  [".sold", [sold]]
]);
const root = { querySelectorAll: selector => selectorValues.get(selector) || [] };
const context = vm.createContext({
  URL,
  location: { href: "https://shopee.ph/example-i.123.456", hostname: "shopee.ph" },
  document: {},
  Element: MockElement,
  getComputedStyle: () => ({ display: "block", visibility: "visible", opacity: "1" }),
  CSS: { escape: value => value },
  NodeFilter: { SHOW_TEXT: 4 }
});
vm.runInContext(readFileSync(new URL("../src/content/common.js", import.meta.url), "utf8"), context);
const common = context.HeyiCollector;

const price = common.extractPrice(root, {
  currency: "PHP",
  selectors: [".current"],
  originalSelectors: [".original"],
  referenceMin: 159,
  referenceMax: 269,
  rejectContextPattern: /(?:upcoming|sale\s+starts?)/i
});
assert.equal(price.priceText, "₱159–269");
assert.equal(price.priceMin, 159);
assert.equal(price.priceMax, 269);
assert.equal(price.originalPriceText, "₱318–538");
assert.equal(price.originalPriceMin, 318);
assert.equal(price.originalPriceMax, 538);

const stats = common.extractStats(root, {
  salesSelectors: [".sold"],
  ratingSelectors: [".rating"],
  ratingCountSelectors: [".rating-count"]
});
assert.equal(stats.ratingValue, 4.8);
assert.equal(stats.ratingCount, 279);
assert.equal(stats.salesCount, 1_000);

console.log("Price and stats parser tests passed");
