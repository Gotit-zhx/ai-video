(() => {
  "use strict";

  const common = globalThis.HeyiCollector;
  const PRODUCT_PATTERNS = [/-i\.(\d+)\.(\d+)(?:$|[/?])/i, /\/product\/(\d+)\/(\d+)(?:$|[/?])/i];

  const adapter = {
    id: "SHOPEE",

    matchesPage() {
      return /(^|\.)shopee\.(sg|ph|vn)$/.test(location.hostname)
        || /(^|\.)shopee\.com\.my$/.test(location.hostname)
        || /(^|\.)shopee\.co\.(th|id)$/.test(location.hostname);
    },

    blockedReason() {
      return globalThis.HeyiPageGuard.blockedPageReason({
        platform: "SHOPEE",
        url: location.href,
        title: document.title,
        text: document.body?.innerText?.slice(0, 5_000) || "",
        hasVisibleChallenge: hasVisibleChallenge()
      });
    },

    isDetailUrl(value) {
      return Boolean(productIdentity(value));
    },

    productAnchors(root = document) {
      return [...root.querySelectorAll("a[href]")].filter(anchor => productIdentity(anchor.href));
    },

    async extract(root, productUrl, pageType) {
      const identity = productIdentity(productUrl) || {};
      const detail = pageType === "DETAIL";
      const productData = detail ? await common.loadShopeeProductData(identity) : null;
      const structured = detail ? common.parseJsonLdProduct(document) : null;
      const titleResult = titleFrom(root, detail);
      const metaTitle = detail ? common.metaContent(["og:title", "twitter:title"]) : { value: "", key: "" };
      const title = titleResult.text || productData?.title || structured?.name || metaTitle.value;
      const descriptionResult = detail
        ? common.firstText(root, [
          "[data-testid*='description' i]",
          "[class*='product-detail']",
          "[class*='description']",
          "[itemprop='description']"
        ], 20_000)
        : { text: "", selector: "" };
      const metaDescription = detail ? common.metaContent(["og:description", "description"]) : { value: "", key: "" };
      const currency = productData?.currency || structured?.currency || common.defaultCurrency("SHOPEE");
      const price = common.extractPrice(root, {
        currency,
        referenceMin: productData?.priceMin ?? structured?.lowPrice,
        referenceMax: productData?.priceMax ?? structured?.highPrice,
        rejectContextPattern: /(?:upcoming|starts?\s+(?:at|on|in)|preview|sale\s+starts?|即将开始|活動預告|活动预告|马上开始|เริ่ม|sắp\s+diễn\s+ra)/i,
        selectors: [
          "[data-testid*='price' i]",
          "[data-sqe='price']",
          "[itemprop='price']",
          "[class*='product-price']",
          "[class*='price']",
          "[aria-label*='price' i]"
        ],
        originalSelectors: [
          "del", "s", "[class*='original-price']", "[class*='price-before']",
          "[class*='before-discount']", "[class*='original']"
        ]
      });
      if (productData?.priceMin != null) {
        price.priceMin = productData.priceMin;
        price.priceMax = productData.priceMax ?? productData.priceMin;
      } else if (price.priceMin === null && structured?.lowPrice != null) {
        price.priceMin = structured.lowPrice;
        price.priceMax = structured.highPrice ?? structured.lowPrice;
        price.priceText = structured.priceText;
      }
      if (productData?.originalPriceMin != null) {
        price.originalPriceMin = productData.originalPriceMin;
        price.originalPriceMax = productData.originalPriceMax ?? productData.originalPriceMin;
      }
      const stats = common.extractStats(root, {
        salesSelectors: [
          "[data-testid*='sold' i]", "[data-sqe*='sold' i]", "[class*='sold']", "[class*='sales']"
        ],
        ratingSelectors: [
          "[itemprop='ratingValue']", "[data-testid*='rating' i]", "[class*='rating']", "[class*='review']"
        ],
        ratingCountSelectors: [
          "[itemprop='reviewCount']", "[data-testid*='rating-count' i]", "[data-testid*='review-count' i]",
          "[class*='rating-count']", "[class*='review-count']"
        ]
      });
      if (stats.ratingValue === null && structured?.ratingValue != null) stats.ratingValue = structured.ratingValue;
      if (stats.ratingCount === null && structured?.ratingCount != null) stats.ratingCount = structured.ratingCount;
      const galleryImages = common.collectImages(
        root,
        [...(productData?.images || []), ...(structured?.images || [])],
        detail ? 60 : 6,
        detail
          ? "img[alt^='Product image' i], [data-testid*='product-image' i] img, [class*='product-image'] img, [class*='product-gallery'] img, [class*='image-carousel'] img"
          : "picture img, img"
      );
      const detailDomImages = detail ? common.collectTypedDomImages(
        root,
        "[data-testid*='product-description' i] img, [class*='product-description'] img, [class*='description-content'] img, [class*='extended-description'] img, [itemprop='description'] img",
        "DETAIL",
        60
      ) : [];
      const imagePlan = common.planListingImages([
        ...(productData?.imageCandidates || []),
        ...galleryImages,
        ...detailDomImages
      ], detail ? 30 : 3);
      const images = imagePlan.images;
      common.applyStructuredSkuImageFallback(productData, images);
      const domVariantHints = detail ? common.extractVariantHints(root, [
        "button[class*='product-variation']",
        "[class*='product-variation'] button",
        "[data-testid*='variation' i] button",
        "[aria-label*='variation' i]",
        "[class*='tier-variation'] button"
      ]) : [];
      const variantHints = productData?.variantHints?.length ? productData.variantHints : domVariantHints;
      const skus = productData?.skus || (detail ? common.extractVisibleSkus(root, price.currency || currency) : []);
      const skuCompleteness = productData?.skuCompleteness ? {
        ...productData.skuCompleteness,
        issues: [...(productData.skuCompleteness.issues || [])]
      } : {
        expectedCount: 0,
        capturedCount: skus.length,
        variantDimensionCount: domVariantHints.length,
        complete: false,
        source: skus.length ? "VISIBLE_DOM_UNVERIFIED" : "UNAVAILABLE",
        issues: ["AUTHORITATIVE_SKU_DATA_UNAVAILABLE"]
      };
      const skuImageIssues = imagePlan.completeness.issues
        .filter(issue => issue === "VARIANT_IMAGE_COVERAGE_TRUNCATED");
      if (detail && skuImageIssues.length) {
        skuCompleteness.issues = [...new Set([
          ...skuCompleteness.issues,
          ...skuImageIssues
        ])].slice(0, 80);
        skuCompleteness.complete = false;
      }
      const category = detail ? common.firstText(root, [
        "nav[aria-label*='breadcrumb' i]", "[class*='breadcrumb']"
      ], 255) : { text: "", selector: "" };
      const shop = detail ? common.firstText(root, [
        "[data-testid*='shop-name' i]", "[data-testid*='seller-name' i]", "[class*='shop-name']", "[class*='seller-name']"
      ], 160) : { text: "", selector: "" };
      const discount = detail ? common.firstText(root, [
        "[data-testid*='discount' i]", "[class*='discount']", "[class*='price-discount']"
      ], 120) : { text: "", selector: "" };
      const attributes = { ...common.extractKeyValueAttributes(root), ...(productData?.attributes || {}) };

      const fieldSources = [];
      if (title) fieldSources.push(common.fieldSource("title", titleResult.text ? "DOM" : structured?.name ? "JSON_LD" : "META", titleResult.selector || metaTitle.key, titleResult.text ? 0.9 : 0.75));
      if (price.priceText) fieldSources.push(common.fieldSource("price", price.sourceSelector ? "DOM" : "JSON_LD", price.sourceSelector, price.sourceSelector ? 0.85 : 0.75));
      if (images.length) fieldSources.push(common.fieldSource("images", "DOM_META", "visible images / structured metadata", 0.8));
      if (stats.salesText) fieldSources.push(common.fieldSource("sales", "DOM", stats.salesSelector, 0.75));
      if (stats.ratingText || stats.ratingValue !== null) fieldSources.push(common.fieldSource("rating", "DOM", stats.ratingSelector, 0.75));
      if (skus.length) fieldSources.push(common.fieldSource("skus", productData?.source || "VISIBLE_DOM_UNVERIFIED", "models + tier_variations", productData ? 0.98 : 0.45));

      return {
        sourceUrl: productUrl,
        sourcePlatform: "SHOPEE",
        pageType,
        pageUrl: location.href,
        canonicalUrl: detail ? common.canonicalUrl(productUrl) : productUrl,
        locale: document.documentElement.lang || "",
        capturedAt: new Date().toISOString(),
        sourceProductId: identity.productId || structured?.sku || null,
        sourceShopId: identity.shopId || null,
        title,
        description: productData?.description || descriptionResult.text || structured?.description || metaDescription.value,
        categoryName: productData?.categoryName || category.text || null,
        brandName: productData?.brandName || structured?.brand || null,
        shopName: productData?.shopName || structured?.shopName || shop.text || null,
        discountText: productData?.discountText || discount.text || null,
        attributes,
        currency: price.currency || currency,
        priceText: price.priceText,
        priceMin: price.priceMin,
        priceMax: price.priceMax,
        originalPriceText: price.originalPriceText,
        originalPriceMin: price.originalPriceMin,
        originalPriceMax: price.originalPriceMax,
        salesText: stats.salesText,
        salesCount: stats.salesCount,
        ratingText: stats.ratingText,
        ratingValue: stats.ratingValue,
        ratingCount: stats.ratingCount,
        images,
        imageCompleteness: imagePlan.completeness,
        skus,
        skuCompleteness,
        variantHints,
        fieldSources,
        extractionStrategies: [
          titleResult.text || price.sourceSelector ? "VISIBLE_DOM" : null,
          productData?.source || null,
          structured ? "JSON_LD" : null,
          metaTitle.value || metaDescription.value ? "META" : null
        ].filter(Boolean)
      };
    }
  };

  function productIdentity(value) {
    let url;
    try {
      url = new URL(value, location.href);
    } catch {
      return null;
    }
    for (const pattern of PRODUCT_PATTERNS) {
      const match = url.pathname.match(pattern);
      if (match) return { shopId: match[1], productId: match[2] };
    }
    const shopId = url.searchParams.get("shopid") || url.searchParams.get("shop_id");
    const productId = url.searchParams.get("itemid") || url.searchParams.get("item_id");
    if (/^\d+$/.test(shopId || "") && /^\d+$/.test(productId || "")) return { shopId, productId };
    return null;
  }

  function titleFrom(root, detail) {
    const selectors = detail
      ? ["h1[data-testid*='title' i]", "[itemprop='name']", "main h1", "h1"]
      : ["[data-sqe='name']", "[data-testid*='title' i]", "[class*='name']", "[class*='title']"];
    const result = common.firstText(root, selectors, 255);
    if (result.text) return result;
    const anchor = root.matches?.("a[href]") ? root : root.querySelector("a[href]");
    const image = root.querySelector("img[alt]");
    return {
      text: common.cleanText(anchor?.title || anchor?.getAttribute("aria-label") || image?.alt, 255),
      selector: anchor?.title ? "a[title]" : "img[alt]"
    };
  }

  function hasVisibleChallenge() {
    const selectors = [
      "iframe[src*='captcha' i]", "iframe[src*='verify' i]", "[id*='captcha' i]",
      "[class*='captcha' i]", "[data-testid*='captcha' i]", "[class*='puzzle' i]"
    ];
    return [...document.querySelectorAll(selectors.join(","))].some(element => common.isVisible(element));
  }

  common.adapters = common.adapters || [];
  common.adapters.push(adapter);
})();
