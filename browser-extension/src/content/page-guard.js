(() => {
  "use strict";

  function blockedPageReason({ platform, url, title, text, hasVisibleChallenge = false }) {
    const safePlatform = String(platform || "").toUpperCase();
    const safeTitle = normalize(title).slice(0, 300);
    const safeText = normalize(text).slice(0, 5_000);
    let path = "";
    try {
      path = new URL(String(url || "")).pathname.toLowerCase();
    } catch {
      return "当前页面地址无效，请刷新后重试";
    }

    const challengeTitle = /^(?:security check|verify to continue|captcha|robot check|安全验证|访问验证|登录验证)\s*(?:[|·—-]\s*(?:shopee|tiktok(?: shop)?)\s*)?$/i.test(safeTitle);
    const challengeText = /verify to continue|drag the puzzle(?: piece)?|complete (?:the )?security (?:check|verification)|prove (?:that )?you(?:'re| are) human|log in to continue|sign in to continue|滑动滑块|拖动拼图|请完成(?:安全)?验证|完成验证后继续|访问过于频繁|登录验证|请先登录|请登录后继续/i.test(safeText);
    const loginTitle = /^(?:log in|login|sign in|登录)\s*(?:[|·—-]\s*(?:shopee|tiktok(?: shop)?)\s*)?$/i.test(safeTitle);

    const shopeeBlockedPath = /\/(?:verify|captcha)(?:\/|$)|\/traffic\/(?:error|verify)(?:\/|$)|\/(?:buyer\/)?(?:login|signin)(?:\/|$)/i.test(path);
    const tiktokBlockedPath = /\/(?:captcha|verify|challenge)(?:\/|$)|\/(?:login|signin)(?:\/|$)/i.test(path);
    const blockedPath = safePlatform === "SHOPEE" ? shopeeBlockedPath : tiktokBlockedPath;

    if (hasVisibleChallenge || blockedPath || challengeTitle || challengeText || loginTitle) {
      return "请先完成平台验证/登录后刷新";
    }
    return "";
  }

  function normalize(value) {
    return String(value || "").replace(/\s+/g, " ").trim();
  }

  globalThis.HeyiPageGuard = Object.freeze({ blockedPageReason });
})();
