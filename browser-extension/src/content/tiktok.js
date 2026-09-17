(() => {
  "use strict";

  const common = globalThis.HeyiCollector;

  const adapter = {
    id: "TIKTOK_SHOP",

    matchesPage() {
      const host = location.hostname.toLowerCase();
      return host === "tiktok.com" || host.endsWith(".tiktok.com");
    },

    blockedReason() {
      return globalThis.HeyiPageGuard.blockedPageReason({
        platform: "TIKTOK_SHOP",
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
      const selectors = [
        "a[href*='/view/product/']",
        "a[href*='/shop/pdp/']",
        "a[href*='/shop/product/']",
        "a[href*='/pdp/']",
        "a[href*='product_id=']"
      ];
      return [...root.querySelectorAll(selectors.join(","))].filter(anchor => productIdentity(anchor.href));
    },

    async extract(root, productUrl, pageType) {
      const identity = productIdentity(productUrl) || {};
      const detail = pageType === "DETAIL";
      const productData = detail ? await common.loadTikTokProductData(identity) : null;
      const structured = detail ? common.parseJsonLdProduct(document) : null;
      const titleResult = titleFrom(root, detail);
      const metaTitle = detail ? common.metaContent(["og:title", "twitter:title"]) : { value: "", key: "" };
      const title = titleResult.text || productData?.title || structured?.name || metaTitle.value;
      const descriptionResult = detail ? common.firstText(root, [
        "[data-e2e*='description' i]", "[data-testid*='description' i]",
        "[class*='product-description']", "[itemprop='description']"
      ], 20_000) : { text: "", selector: "" };
      const metaDescription = detail ? common.metaContent(["og:description", "description"]) : { value: "", key: "" };
      const currency = productData?.currency || structured?.currency || common.defaultCurrency("TIKTOK_SHOP");
      const price = common.extractPrice(root, {
        currency,
        referenceMin: productData?.priceMin ?? structured?.lowPrice,
        referenceMax: productData?.priceMax ?? structured?.highPrice,
        selectors: [
          "[data-e2e*='product-price' i]", "[data-testid*='product-price' i]",
          "[data-e2e='price']", "[itemprop='price']", "[class*='sale-price']",
          "[class*='product-price']", "[class*='price']"
        ],
        originalSelectors: [
          "del", "s", "[data-e2e*='original-price' i]", "[data-testid*='original-price' i]",
          "[class*='original-price']", "[class*='list-price']"
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
          "[data-e2e*='sold' i]", "[data-testid*='sold' i]", "[class*='sold']", "[class*='sales']"
        ],
        ratingSelectors: [
          "[itemprop='ratingValue']", "[data-e2e*='rating' i]", "[data-testid*='rating' i]",
          "[class*='rating']", "[class*='review']"
        ],
        ratingCountSelectors: [
          "[itemprop='reviewCount']", "[data-e2e*='rating-count' i]", "[data-testid*='rating-count' i]",
          "[data-e2e*='review-count' i]", "[data-testid*='review-count' i]", "[class*='review-count']"
        ]
      });
      if (stats.ratingValue === null && structured?.ratingValue != null) stats.ratingValue = structured.ratingValue;
      if (stats.ratingCount === null && structured?.ratingCount != null) stats.ratingCount = structured.ratingCount;
      if (productData?.salesCount != null) stats.salesCount = productData.salesCount;
      if (productData?.ratingValue != null) stats.ratingValue = productData.ratingValue;
      if (productData?.ratingCount != null) stats.ratingCount = productData.ratingCount;
      const galleryImages = common.collectImages(
        root,
        [...(productData?.images || []), ...(structured?.images || [])],
        detail ? 120 : 6,
        detail
          ? "[data-e2e*='product-image' i] img, [data-testid*='product-image' i] img, [class*='product-image'] img, [class*='product-gallery'] img, [class*='sku-image'] img"
          : "picture img, img"
      );
      const detailDomImages = detail ? common.collectTypedDomImages(
        root,
        "[data-e2e*='product-description' i] img, [data-testid*='product-description' i] img, [class*='product-description'] img, [class*='description-content'] img, [class*='extended-description'] img, [itemprop='description'] img",
        "DETAIL",
        120
      ) : [];
      const responseSkuImages = (productData?.skus || []).map(sku => ({
        url: sku?.imageUrl,
        alt: sku?.variationName || "",
        role: sku?.imageInherited || sku?.imageSource === "PRODUCT_MAIN" ? "MAIN" : "VARIANT"
      }));
      const imagePlan = common.planListingImages([
        ...galleryImages,
        ...responseSkuImages,
        ...(productData?.detailImages || []).map(url => ({ url, role: "DETAIL", alt: "商品详情图" })),
        ...detailDomImages
      ], detail ? 120 : 3);
      const images = imagePlan.images;
      common.applyStructuredSkuImageFallback(productData, images);
      const domVariantHints = detail ? common.extractVariantHints(root, [
        "[data-e2e*='sku' i] button", "[data-testid*='sku' i] button",
        "[data-e2e*='variation' i] button", "[data-testid*='variation' i] button",
        "[class*='sku'] button", "[class*='variation'] button", "[class*='variant'] button"
      ]) : [];
      const variantHints = productData?.variantHints?.length ? productData.variantHints : domVariantHints;
      const skus = productData?.skus || (detail ? common.extractVisibleSkus(root, price.currency || currency) : []);
      const skuCompleteness = productData?.skuCompleteness || {
        expectedCount: 0,
        capturedCount: skus.length,
        variantDimensionCount: domVariantHints.length,
        complete: false,
        source: skus.length ? "VISIBLE_DOM_UNVERIFIED" : "UNAVAILABLE",
        issues: ["AUTHORITATIVE_SKU_DATA_UNAVAILABLE"]
      };
      const imageCompleteness = completeTikTokImageEvidence(
        imagePlan.completeness,
        images,
        skus
      );
      // Shop's mobile/translated PDP renders taxonomy links below recommendations,
      // outside <main> and without breadcrumb class/ARIA attributes.
      const category = detail ? common.extractTikTokCategory(document, productUrl)
        : { text: "", selector: "" };
      const shop = detail ? common.firstText(root, [
        "[data-e2e*='shop-name' i]", "[data-testid*='shop-name' i]", "[class*='shop-name']", "[class*='seller-name']"
      ], 160) : { text: "", selector: "" };
      const discount = detail ? common.firstText(root, [
        "[data-e2e*='discount' i]", "[data-testid*='discount' i]", "[class*='discount']"
      ], 120) : { text: "", selector: "" };
      const attributes = { ...common.extractKeyValueAttributes(root), ...(productData?.attributes || {}) };

      const fieldSources = [];
      if (productData?.categoryName || category.text) fieldSources.push(common.fieldSource(
        "categoryName", productData?.categoryName ? productData.source : "DOM",
        productData?.categoryName ? "product category" : category.selector, 0.95));

      if (title) fieldSources.push(common.fieldSource("title", titleResult.text ? "DOM" : structured?.name ? "JSON_LD" : "META", titleResult.selector || metaTitle.key, titleResult.text ? 0.9 : 0.75));
      if (price.priceText) fieldSources.push(common.fieldSource("price", price.sourceSelector ? "DOM" : "JSON_LD", price.sourceSelector, price.sourceSelector ? 0.85 : 0.75));
      if (images.length) fieldSources.push(common.fieldSource("images", "DOM_META", "visible images / structured metadata", 0.8));
      if (stats.salesText) fieldSources.push(common.fieldSource("sales", "DOM", stats.salesSelector, 0.75));
      if (stats.ratingText || stats.ratingValue !== null) fieldSources.push(common.fieldSource("rating", "DOM", stats.ratingSelector, 0.75));
      if (skus.length) fieldSources.push(common.fieldSource("skus", productData?.source || "VISIBLE_DOM_UNVERIFIED", "product.skus / sku_infos", productData ? 0.96 : 0.4));

      return {
        sourceUrl: productUrl,
        sourcePlatform: "TIKTOK_SHOP",
        pageType,
        pageUrl: location.href,
        canonicalUrl: detail ? common.canonicalUrl(productUrl) : productUrl,
        locale: document.documentElement.lang || "",
        capturedAt: new Date().toISOString(),
        sourceProductId: identity.productId || structured?.sku || null,
        sourceShopId: identity.shopId || productData?.shopId || null,
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
        imageCompleteness,
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

  common.extractTikTokCategory = function extractTikTokCategory(root, productUrl) {
    const empty = { text: "", selector: "" };
    let page;
    try { page = new URL(productUrl); } catch { return empty; }
    if (!productIdentity(productUrl)) return empty;
    const selectors = "a[href*='/c/']";
    const links = [];
    for (const anchor of root.querySelectorAll(selectors)) {
      let url;
      try { url = new URL(anchor.href || anchor.getAttribute("href"), page); } catch { continue; }
      if (url.origin !== page.origin || !/^\/[a-z]{2}\/c\/[^/]+\/\d{3,32}\/?$/i.test(url.pathname)) continue;
      if (!common.isVisible(anchor)) continue;
      const name = common.cleanText(anchor.textContent, 100);
      if (!name || links.some(link => link.id === url.pathname)) continue;
      links.push({id: url.pathname, name, element: anchor});
    }
    // Category menus contain many unrelated links. Only accept a compact path
    // whose links share a container without unrelated product or category cards.
    if (links.length > 0 && links.length <= 8) {
      let container = links[0].element.parentElement;
      for (let depth = 0; container && depth < 6; depth++, container = container.parentElement) {
        if (!links.every(link => container.contains(link.element))) continue;
        const otherLinks = [...container.querySelectorAll("a[href]")].filter(a =>
          !links.some(link => link.element === a));
        if (otherLinks.length > 1) break;
        const onlyHome = otherLinks.every(a => {
          try { const u = new URL(a.href, page); return u.origin === page.origin && /^\/[a-z]{2}\/?$/i.test(u.pathname); }
          catch { return false; }
        });
        if (onlyHome) return {text: links.map(link => link.name).join(" > "), selector: selectors};
        break;
      }
    }
    const named = common.firstText(root, [
      "nav[aria-label*='breadcrumb' i]", "[data-e2e*='breadcrumb' i]",
      "[data-testid*='breadcrumb' i]", "[class*='breadcrumb' i]"
    ], 255);
    return named.text ? named : empty;
  };

  function productIdentity(value) {
    let url;
    try {
      url = new URL(value, location.href);
    } catch {
      return null;
    }
    let path;
    try {
      path = decodeURIComponent(url.pathname);
    } catch {
      path = url.pathname;
    }
    const direct = path.match(/\/view\/product\/(\d{6,32})/i);
    if (direct) return { productId: direct[1], shopId: url.searchParams.get("shop_id") || null };
    const pdp = path.match(/\/(?:(?:[a-z]{2}(?:-[a-z]{2})?)\/)?(?:shop\/)?(?:pdp|product)\/(?:[^/?]*?[-/])?(\d{8,32})(?:$|[/?-])/i);
    const queryValue = url.searchParams.get("product_id") || url.searchParams.get("productId");
    const queryId = /^\d{6,32}$/.test(queryValue || "") ? queryValue : null;
    const productId = queryId || pdp?.[1];
    return productId ? { productId, shopId: url.searchParams.get("shop_id") || null } : null;
  }

  function titleFrom(root, detail) {
    const selectors = detail
      ? [
        "h1[data-e2e*='product-title' i]", "h1[data-testid*='product-title' i]",
        "[itemprop='name']", "main h1", "h1"
      ]
      : [
        "[data-e2e*='product-title' i]", "[data-testid*='product-title' i]",
        "[class*='product-title']", "[class*='title']"
      ];
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
      "[class*='captcha' i]", "[data-e2e*='captcha' i]", "[data-testid*='captcha' i]",
      "[class*='secsdk-captcha' i]", "[class*='puzzle' i]"
    ];
    return [...document.querySelectorAll(selectors.join(","))].some(element => common.isVisible(element));
  }

  function completeTikTokImageEvidence(completeness, images, skus) {
    const selectedUrls = new Set((images || []).map(image => common.absoluteUrl(image?.url)).filter(Boolean));
    const requiredSkuUrls = new Set((skus || []).map(sku => common.absoluteUrl(sku?.imageUrl)).filter(Boolean));
    const coveredSkuImageCount = [...requiredSkuUrls].filter(url => selectedUrls.has(url)).length;
    const issues = [...new Set([
      ...(completeness?.issues || []),
      ...(coveredSkuImageCount < requiredSkuUrls.size ? ["VARIANT_IMAGE_COVERAGE_TRUNCATED"] : [])
    ])];
    return {
      ...(completeness || {}),
      requiredSkuImageCount: requiredSkuUrls.size,
      coveredSkuImageCount,
      complete: completeness?.complete === true
        && coveredSkuImageCount === requiredSkuUrls.size
        && issues.length === 0,
      issues
    };
  }

  common.adapters = common.adapters || [];
  common.adapters.push(adapter);
})();
