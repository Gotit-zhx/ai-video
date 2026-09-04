import assert from "node:assert/strict";
import "../src/content/page-guard.js";

const { blockedPageReason } = globalThis.HeyiPageGuard;

assert.equal(blockedPageReason({
  platform: "TIKTOK_SHOP",
  url: "https://shop.tiktok.com/view/product/1729999999999999999",
  title: "Security Check",
  text: "Verify to continue Drag the puzzle piece"
}), "请先完成平台验证/登录后刷新");

assert.equal(blockedPageReason({
  platform: "SHOPEE",
  url: "https://shopee.ph/verify/traffic/error?url=/sample-i.1.2",
  title: "Shopee",
  text: ""
}), "请先完成平台验证/登录后刷新");

assert.equal(blockedPageReason({
  platform: "SHOPEE",
  url: "https://shopee.ph/buyer/login",
  title: "Login | Shopee",
  text: ""
}), "请先完成平台验证/登录后刷新");

assert.equal(blockedPageReason({
  platform: "SHOPEE",
  url: "https://shopee.ph/security-check-tool-i.123.456",
  title: "Security Check Tool for Bags",
  text: "A normal product description without verification instructions."
}), "");

assert.equal(blockedPageReason({
  platform: "TIKTOK_SHOP",
  url: "https://shop.tiktok.com/view/product/1729999999999999999",
  title: "Everyday Backpack",
  text: "4.8 rating · 1.2k sold",
  hasVisibleChallenge: false
}), "");

assert.equal(blockedPageReason({
  platform: "TIKTOK_SHOP",
  url: "https://shop.tiktok.com/view/product/1729999999999999999",
  title: "Everyday Backpack",
  text: "",
  hasVisibleChallenge: true
}), "请先完成平台验证/登录后刷新");

console.log("page-guard tests passed");
