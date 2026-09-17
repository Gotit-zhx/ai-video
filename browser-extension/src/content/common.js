(() => {
  "use strict";

  const api = globalThis.HeyiCollector = globalThis.HeyiCollector || {};
  const CONTROL_CHARACTERS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;

  api.cleanText = function cleanText(value, maxLength = 2_000) {
    if (value === null || value === undefined) return "";
    return String(value).replace(CONTROL_CHARACTERS, " ").replace(/\s+/g, " ").trim().slice(0, maxLength);
  };

  api.firstText = function firstText(root, selectors, maxLength = 2_000) {
    for (const selector of selectors) {
      let elements = [];
      try {
        elements = root.querySelectorAll(selector);
      } catch {
        continue;
      }
      for (const element of elements) {
        const text = api.cleanText(element.getAttribute?.("content") || element.textContent, maxLength);
        if (text && (element.tagName === "META" || api.isVisible(element))) return { text, selector };
      }
    }
    return { text: "", selector: "" };
  };

  api.isVisible = function isVisible(element) {
    if (!(element instanceof Element)) return false;
    const style = getComputedStyle(element);
    if (style.display === "none" || style.visibility === "hidden" || Number(style.opacity) === 0) return false;
    const rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  };

  api.absoluteUrl = function absoluteUrl(value, base = location.href) {
    try {
      const url = new URL(value, base);
      if (url.protocol !== "https:" || url.username || url.password) return "";
      return url.toString();
    } catch {
      return "";
    }
  };

  api.canonicalUrl = function canonicalUrl(fallback = location.href) {
    const href = document.querySelector('link[rel="canonical"]')?.href;
    return api.absoluteUrl(href || fallback) || fallback;
  };

  api.metaContent = function metaContent(keys) {
    for (const key of keys) {
      const escaped = CSS.escape(key);
      const element = document.querySelector(`meta[property="${escaped}"],meta[name="${escaped}"]`);
      const value = api.cleanText(element?.content, 20_000);
      if (value) return { value, key };
    }
    return { value: "", key: "" };
  };

  api.parseJsonLdProduct = function parseJsonLdProduct(root = document) {
    const scripts = root.querySelectorAll('script[type="application/ld+json"]');
    for (const script of scripts) {
      if (!script.textContent || script.textContent.length > 1_500_000) continue;
      try {
        const parsed = JSON.parse(script.textContent);
        const product = findTypedProduct(parsed, 0);
        if (!product) continue;
        const offer = Array.isArray(product.offers) ? product.offers[0] : product.offers || {};
        const aggregateOffer = offer?.offers?.[0] || offer;
        const images = normalizeStructuredImages(product.image);
        const rating = product.aggregateRating || {};
        return {
          name: api.cleanText(product.name, 255),
          description: api.cleanText(product.description, 20_000),
          brand: api.cleanText(typeof product.brand === "string" ? product.brand : product.brand?.name, 128),
          sku: api.cleanText(product.sku || product.productID, 128),
          currency: api.cleanText(aggregateOffer.priceCurrency || offer.priceCurrency, 8).toUpperCase(),
          priceText: api.cleanText(aggregateOffer.price || offer.lowPrice || offer.highPrice, 120),
          lowPrice: api.toFiniteNumber(offer.lowPrice ?? aggregateOffer.price),
          highPrice: api.toFiniteNumber(offer.highPrice ?? aggregateOffer.price),
          images,
          shopName: api.cleanText(
            aggregateOffer.seller?.name || offer.seller?.name || product.seller?.name,
            160
          ),
          ratingValue: api.toFiniteNumber(rating.ratingValue),
          ratingCount: api.toFiniteNumber(rating.reviewCount ?? rating.ratingCount)
        };
      } catch {
        // Invalid or non-standard JSON-LD is common; DOM extraction remains available.
      }
    }
    return null;
  };

  api.defaultCurrency = function defaultCurrency(platform, hostname = location.hostname) {
    const host = hostname.toLowerCase();
    if (platform === "TIKTOK_SHOP") {
      const region = new URL(location.href).searchParams.get("region")?.toUpperCase();
      if (region === "PH") return "PHP";
      if (region === "MY") return "MYR";
      if (region === "SG") return "SGD";
      if (region === "TH") return "THB";
      if (region === "ID") return "IDR";
      if (region === "VN") return "VND";
    }
    if (host.endsWith(".ph")) return "PHP";
    if (host.endsWith(".com.my")) return "MYR";
    if (host.endsWith(".sg")) return "SGD";
    if (host.endsWith(".co.th")) return "THB";
    if (host.endsWith(".co.id")) return "IDR";
    if (host.endsWith(".vn")) return "VND";
    return null;
  };

  api.detectCurrency = function detectCurrency(text, fallback) {
    const value = String(text || "");
    const rules = [
      [/\bPHP\b|₱/i, "PHP"],
      [/\bMYR\b|\bRM\s*\d/i, "MYR"],
      [/\bSGD\b|S\$/i, "SGD"],
      [/\bTHB\b|฿/i, "THB"],
      [/\bIDR\b|\bRp\s*\d/i, "IDR"],
      [/\bVND\b|₫/i, "VND"],
      [/\bUSD\b|US\$/i, "USD"]
    ];
    return rules.find(([pattern]) => pattern.test(value))?.[1] || fallback || null;
  };

  api.extractPrice = function extractPrice(root, options = {}) {
    const selectors = options.selectors || [];
    const originalSelectors = options.originalSelectors || ["del", "s", "[class*='original-price']"];
    const fallbackCurrency = options.currency || null;
    const currentCandidates = textCandidates(root, selectors, 50)
      .filter(candidate => hasPriceSignal(candidate.text, fallbackCurrency))
      .filter(candidate => !options.rejectContextPattern?.test(candidate.context));
    const originalCandidates = textCandidates(root, originalSelectors, 20)
      .filter(candidate => hasPriceSignal(candidate.text, fallbackCurrency));
    const current = bestPriceCandidate(currentCandidates, fallbackCurrency, {
      referenceMin: options.referenceMin,
      referenceMax: options.referenceMax
    });
    const original = bestPriceCandidate(originalCandidates, current.currency || fallbackCurrency);
    return {
      currency: current.currency || original.currency || fallbackCurrency,
      priceText: current.text,
      priceMin: current.amounts[0] ?? null,
      priceMax: current.amounts.at(-1) ?? current.amounts[0] ?? null,
      originalPriceText: original.text,
      originalPriceMin: original.amounts[0] ?? null,
      originalPriceMax: original.amounts.at(-1) ?? original.amounts[0] ?? null,
      sourceSelector: current.selector,
      originalSourceSelector: original.selector
    };
  };

  api.extractStats = function extractStats(root, options = {}) {
    const sales = firstMatchingText(root, options.salesSelectors || [], /(?:sold|sales|đã bán|terjual|ขายแล้ว|已售|销量)/i);
    const rating = firstMatchingText(root, options.ratingSelectors || [], /(?:rating|reviews?|đánh giá|ulasan|คะแนน|评分|評價)/i);
    const salesFallback = sales.text ? sales : walkForText(root, /(?:sold|đã bán|terjual|ขายแล้ว|已售|销量)\s*[:：]?\s*[\d,.]+\s*[kKmM万]?|[\d,.]+\s*[kKmM万]?\s*(?:sold|đã bán|terjual|已售)/i);
    const ratingFallback = rating.text ? rating : walkForText(root, /(?:^|\s)[0-5](?:[.,]\d{1,2})?\s*(?:\/\s*5|stars?|rating|评分|คะแนน)?/i);
    const ratingCount = firstMatchingText(
      root,
      options.ratingCountSelectors || [],
      /(?:ratings?|reviews?|đánh giá|ulasan|คะแนน|评分|評價)/i
    );
    return {
      salesText: salesFallback.text,
      salesCount: parseCompactCount(salesFallback.text),
      salesSelector: salesFallback.selector,
      ratingText: ratingFallback.text,
      ratingValue: parseRating(ratingFallback.text),
      ratingCount: parseRatingCount(ratingCount.text || ratingFallback.text),
      ratingSelector: ratingFallback.selector
    };
  };

  api.collectImages = function collectImages(root, structuredImages = [], limit = 24, imageSelectors = "picture img, img") {
    const result = [];
    const seen = new Set();
    const add = (value, alt = "", role = "GALLERY") => {
      const url = api.absoluteUrl(value);
      if (!url || seen.has(url)) return;
      seen.add(url);
      result.push({ url, alt: api.cleanText(alt, 300), position: result.length, role });
    };
    structuredImages.forEach((url, index) => add(url, "", index === 0 ? "MAIN" : "GALLERY"));

    const metaImage = api.metaContent(["og:image", "twitter:image"]);
    if (metaImage.value) add(metaImage.value, "", result.length === 0 ? "MAIN" : "GALLERY");
    const images = root.querySelectorAll(imageSelectors);
    for (const image of images) {
      if (result.length >= limit) break;
      const rect = image.getBoundingClientRect();
      if (!api.isVisible(image) || rect.width < 48 || rect.height < 48) continue;
      const srcset = image.getAttribute("srcset") || "";
      const largest = srcset.split(",").map(part => part.trim().split(/\s+/)[0]).filter(Boolean).at(-1);
      add(largest || image.currentSrc || image.src, image.alt, result.length === 0 ? "MAIN" : "GALLERY");
    }
    return result.slice(0, limit);
  };

  api.collectTypedDomImages = function collectTypedDomImages(root, imageSelectors, role, limit = 60) {
    const result = [];
    const seen = new Set();
    let images = [];
    try {
      images = root.querySelectorAll(imageSelectors);
    } catch {
      return result;
    }
    for (const image of images) {
      if (result.length >= limit || excludedListingImage(image)) continue;
      const rect = image.getBoundingClientRect();
      const isDetail = normalizeListingImageRole(role) === "DETAIL";
      // Description images can have zero dimensions until lazy loading runs.
      // Read their declared source without requiring a viewport intersection.
      if (!isDetail && (!api.isVisible(image) || rect.width < 32 || rect.height < 32)) continue;
      const srcset = image.getAttribute("data-srcset") || image.getAttribute("srcset") || "";
      const largest = srcset.split(",").map(part => part.trim().split(/\s+/)[0]).filter(Boolean).at(-1);
      const url = [largest, image.getAttribute("data-src"), image.getAttribute("data-original"),
        image.getAttribute("data-lazy-src"), image.currentSrc, image.src]
        .map(value => value ? api.absoluteUrl(value) : "")
        .find(Boolean);
      if (!url || seen.has(url)) continue;
      seen.add(url);
      result.push({
        url,
        alt: api.cleanText(image.alt, 300),
        role: normalizeListingImageRole(role),
        position: result.length
      });
    }
    return result;
  };

  // Capture keeps up to 120 source assets; each marketplace applies its own
  // publishing limit later. Keep main and SKU images first, and report any
  // overflow explicitly instead of silently losing description assets.
  api.planListingImages = function planListingImages(candidates, limit = 120) {
    const safeLimit = Number.isSafeInteger(limit) && limit > 0 ? Math.min(limit, 120) : 120;
    const rolePriority = { MAIN: 0, VARIANT: 1, GALLERY: 2, DETAIL: 3 };
    const byUrl = new Map();
    for (const [index, raw] of (Array.isArray(candidates) ? candidates : []).entries()) {
      const candidate = typeof raw === "string" ? { url: raw } : raw;
      const url = api.absoluteUrl(candidate?.url || candidate?.originalUrl || candidate?.src);
      if (!url) continue;
      const role = normalizeListingImageRole(candidate?.role);
      const assetKey = listingImageAssetKey(url);
      const existing = byUrl.get(assetKey);
      if (!existing) {
        byUrl.set(assetKey, {
          url,
          originalUrl: url,
          alt: api.cleanText(candidate?.alt || candidate?.altText, 300),
          role,
          roles: new Set([role]),
          firstIndex: index
        });
      } else {
        existing.roles.add(role);
        const preferIncoming = role === "VARIANT" && existing.role !== "VARIANT"
          || existing.role !== "VARIANT" && rolePriority[role] < rolePriority[existing.role];
        if (preferIncoming) {
          existing.url = url;
          existing.originalUrl = url;
          existing.role = role;
        }
        if (!existing.alt) existing.alt = api.cleanText(candidate?.alt || candidate?.altText, 300);
      }
    }

    const unique = [...byUrl.values()];
    const mainCandidates = unique.filter(image => image.roles.has("MAIN"))
      .sort((left, right) => left.firstIndex - right.firstIndex);
    const primary = mainCandidates[0]
      || unique.slice().sort((left, right) => left.firstIndex - right.firstIndex)[0];
    unique.forEach(image => {
      image.role = image === primary ? "MAIN"
        : image.roles.has("VARIANT") ? "VARIANT"
          : image.roles.has("GALLERY") || image.roles.has("MAIN") ? "GALLERY" : "DETAIL";
    });
    unique.sort((left, right) => rolePriority[left.role] - rolePriority[right.role]
      || left.firstIndex - right.firstIndex);
    const selected = unique.slice(0, safeLimit).map((image, position) => ({
      url: image.url,
      originalUrl: image.originalUrl,
      alt: image.alt,
      role: image.role,
      position
    }));
    const roleCounts = countListingImageRoles(unique);
    const selectedRoleCounts = countListingImageRoles(selected);
    const issues = [];
    if (unique.length > selected.length) {
      issues.push("IMAGE_LIMIT_EXCEEDED");
      if (selectedRoleCounts.VARIANT < roleCounts.VARIANT) issues.push("VARIANT_IMAGE_COVERAGE_TRUNCATED");
      if (selectedRoleCounts.GALLERY < roleCounts.GALLERY) issues.push("GALLERY_IMAGE_COVERAGE_TRUNCATED");
      if (selectedRoleCounts.DETAIL < roleCounts.DETAIL) issues.push("DETAIL_IMAGE_COVERAGE_TRUNCATED");
    }
    return {
      images: selected,
      completeness: {
        expectedCount: unique.length,
        capturedCount: selected.length,
        complete: issues.length === 0,
        roleCounts,
        selectedRoleCounts,
        issues
      }
    };
  };

  // ByteDance returns the same TOS asset through interchangeable p16/p19/p21
  // CDN hosts. Treat those delivery aliases as one listing image while keeping
  // a usable HTTPS URL (and preferring a higher-priority role) for publishing.
  function listingImageAssetKey(value) {
    try {
      const url = new URL(value);
      if (!/(?:^|\.)(?:ibyteimg|byteimg)\.com$/i.test(url.hostname)) return url.toString();
      const match = url.pathname.match(/\/(tos-[^/]+\/[A-Za-z0-9_-]{16,})(?:~|$)/i);
      return match ? `bytedance:${match[1]}` : url.toString();
    } catch {
      return String(value || "");
    }
  }

  api.extractVariantHints = function extractVariantHints(root, selectors) {
    const elements = [];
    for (const selector of selectors) {
      let matches = [];
      try {
        matches = root.querySelectorAll(selector);
      } catch {
        continue;
      }
      for (const element of matches) {
        if (!api.isVisible(element) || element.closest("[data-heyi-collector-host]")) continue;
        if (!elements.includes(element)) elements.push(element);
        if (elements.length >= 100) break;
      }
      if (elements.length >= 100) break;
    }
    const groups = new Map();
    for (const element of elements) {
      const container = variationContainer(element, elements) || root;
      const values = groups.get(container) || [];
      const text = api.cleanText(element.getAttribute("aria-label") || element.textContent, 160);
      if (text && !values.includes(text)) values.push(text);
      groups.set(container, values);
    }
    return [...groups.entries()].slice(0, 30).map(([container, values], index) => ({
      name: variationGroupName(container, elements, index),
      values: values.slice(0, 50)
    })).filter(group => group.values.length > 0);
  };

  api.extractKeyValueAttributes = function extractKeyValueAttributes(root) {
    const attributes = {};
    const add = (key, value) => {
      const safeKey = api.cleanText(key, 100);
      const safeValue = api.cleanText(value, 500);
      if (safeKey && safeValue && safeKey !== safeValue && Object.keys(attributes).length < 50) {
        attributes[safeKey] = safeValue;
      }
    };
    for (const row of root.querySelectorAll("table tr")) {
      const cells = [...row.querySelectorAll(":scope > th, :scope > td")];
      if (cells.length >= 2) add(cells[0].textContent, cells.slice(1).map(cell => cell.textContent).join(" "));
    }
    for (const term of root.querySelectorAll("dl dt")) {
      const definition = term.nextElementSibling;
      if (definition?.tagName === "DD") add(term.textContent, definition.textContent);
    }
    return attributes;
  };

  api.extractVisibleSkus = function extractVisibleSkus(root, currency) {
    const selectors = ["[data-sku-id]", "[data-model-id]", "[data-seller-sku]"];
    const result = [];
    const seen = new Set();
    for (const element of root.querySelectorAll(selectors.join(","))) {
      if (!api.isVisible(element)) continue;
      const externalSkuId = api.cleanText(element.dataset.skuId || element.dataset.modelId, 128);
      const sellerSku = api.cleanText(element.dataset.sellerSku, 128);
      const stockAttribute = element.dataset.stock ?? element.dataset.inventory;
      const stockText = api.cleanText(element.textContent, 300).match(/(?:stock|库存|stok|kho|คงเหลือ)\s*[:：]?\s*(\d+)/i)?.[1];
      const stock = api.toFiniteNumber(stockAttribute ?? stockText);
      if (!externalSkuId && !sellerSku) continue;
      const key = `${externalSkuId}|${sellerSku}|${stock ?? ""}`;
      if (seen.has(key)) continue;
      seen.add(key);
      const price = api.extractPrice(element, {
        currency,
        selectors: ["[data-testid*='price' i]", "[data-e2e*='price' i]", "[class*='price']"]
      });
      result.push({
        externalSkuId: externalSkuId || null,
        sellerSku: sellerSku || null,
        variationName: api.cleanText(element.getAttribute("aria-label") || element.textContent, 300) || null,
        price: price.priceMin,
        originalPrice: price.originalPriceMin,
        stock,
        attributes: {}
      });
      if (result.length >= 200) break;
    }
    return result;
  };

  api.applyStructuredSkuImageFallback = function applyStructuredSkuImageFallback(productData, images) {
    if (!productData?.skuCompleteness || !Array.isArray(productData.skus)) return productData;
    const mainImage = images?.find(image => image?.role === "MAIN")?.url || images?.[0]?.url || null;
    if (!mainImage) return productData;
    for (const sku of productData.skus) {
      if (sku.imageUrl) continue;
      sku.imageUrl = mainImage;
      sku.imageSource = "PRODUCT_MAIN";
      sku.imageInherited = true;
    }
    const remainingIssues = (productData.skuCompleteness.issues || [])
      .filter(issue => !/_IMAGE_MISSING$/.test(issue));
    productData.skuCompleteness.issues = remainingIssues;
    productData.skuCompleteness.complete = productData.skuCompleteness.expectedCount > 0
      && productData.skuCompleteness.expectedCount === productData.skus.length
      && productData.skus.every(sku => Boolean(sku.imageUrl))
      && remainingIssues.length === 0;
    return productData;
  };

  api.fieldSource = function fieldSource(field, source, path, confidence) {
    return { field, source, path: api.cleanText(path, 300), confidence };
  };

  api.toFiniteNumber = function toFiniteNumber(value) {
    if (value === null || value === undefined || value === "") return null;
    const number = typeof value === "number" ? value : Number(String(value).replace(/,/g, ""));
    return Number.isFinite(number) && number >= 0 ? number : null;
  };

  api.productCardAncestor = function productCardAncestor(anchor) {
    const preferred = anchor.closest([
      "article", "li", "[data-testid*='product-card']", "[data-e2e*='product-card']",
      "[class*='product-card']", "[class*='product_item']", "[class*='product-item']",
      "[class*='search-item']", "[data-sqe='item']"
    ].join(","));
    if (preferred && reasonableCard(preferred)) return preferred;
    let current = anchor.parentElement;
    for (let depth = 0; current && depth < 6; depth += 1, current = current.parentElement) {
      if (reasonableCard(current) && current.querySelector("img")) return current;
    }
    return anchor.parentElement;
  };

  function reasonableCard(element) {
    const rect = element.getBoundingClientRect();
    return rect.width >= 100 && rect.width <= 900 && rect.height >= 100 && rect.height <= 1_100;
  }

  function findTypedProduct(value, depth) {
    if (!value || typeof value !== "object" || depth > 8) return null;
    if (Array.isArray(value)) {
      for (const item of value.slice(0, 100)) {
        const match = findTypedProduct(item, depth + 1);
        if (match) return match;
      }
      return null;
    }
    const types = Array.isArray(value["@type"]) ? value["@type"] : [value["@type"]];
    if (types.some(type => String(type).toLowerCase() === "product")) return value;
    for (const child of Object.values(value).slice(0, 80)) {
      if (!child || typeof child !== "object") continue;
      const match = findTypedProduct(child, depth + 1);
      if (match) return match;
    }
    return null;
  }

  function normalizeStructuredImages(value) {
    const entries = Array.isArray(value) ? value : value ? [value] : [];
    return entries.map(entry => typeof entry === "string" ? entry : entry?.url || entry?.contentUrl)
      .map(url => api.absoluteUrl(url)).filter(Boolean).slice(0, 24);
  }

  function normalizeListingImageRole(value) {
    const role = api.cleanText(value, 32).toUpperCase();
    if (role === "MAIN" || role === "VARIANT" || role === "DETAIL") return role;
    if (role === "DESCRIPTION") return "DETAIL";
    return "GALLERY";
  }

  function countListingImageRoles(images) {
    const counts = { MAIN: 0, GALLERY: 0, VARIANT: 0, DETAIL: 0 };
    images.forEach(image => { counts[normalizeListingImageRole(image?.role)] += 1; });
    return counts;
  }

  function excludedListingImage(image) {
    if (image.closest?.("[data-heyi-collector-host], [data-testid*='review' i], [data-testid*='rating' i], [class*='review' i], [class*='rating' i], [class*='avatar' i], [class*='profile' i], [class*='logo' i], [class*='seller' i], [class*='shop-info' i], [class*='recommend' i], [class*='captcha' i]")) {
      return true;
    }
    const label = api.cleanText(`${image.alt || ""} ${image.getAttribute?.("aria-label") || ""}`, 500);
    return /\b(?:review|rating|avatar|profile|logo|seller|shop|captcha|qr\s*code)\b/i.test(label);
  }

  function textCandidates(root, selectors, limit) {
    const result = [];
    const seen = new Set();
    for (const selector of selectors) {
      let elements = [];
      try {
        elements = root.querySelectorAll(selector);
      } catch {
        continue;
      }
      for (const element of elements) {
        if (!api.isVisible(element)) continue;
        const text = api.cleanText(element.textContent, 240);
        if (!text || seen.has(text)) continue;
        seen.add(text);
        const parentText = api.cleanText(element.parentElement?.textContent, 500);
        result.push({ text, selector, context: parentText.length <= 400 ? `${text} ${parentText}` : text });
        if (result.length >= limit) return result;
      }
    }
    return result;
  }

  function hasPriceSignal(text, fallbackCurrency) {
    return /\d/.test(text) && (Boolean(api.detectCurrency(text, null)) || Boolean(fallbackCurrency));
  }

  function bestPriceCandidate(candidates, fallbackCurrency, options = {}) {
    const parsed = [];
    for (let index = 0; index < candidates.length; index += 1) {
      const candidate = candidates[index];
      const currency = api.detectCurrency(candidate.text, fallbackCurrency);
      const amounts = parseMoneyAmounts(candidate.text, currency);
      if (amounts.length) parsed.push({ ...candidate, currency, amounts, index });
    }
    if (parsed.length && options.referenceMin != null) {
      const referenceMin = Number(options.referenceMin);
      const referenceMax = Number(options.referenceMax ?? referenceMin);
      parsed.sort((left, right) => referenceScore(right, referenceMin, referenceMax) - referenceScore(left, referenceMin, referenceMax));
    }
    if (parsed.length) return parsed[0];
    return { text: "", selector: "", currency: fallbackCurrency, amounts: [] };
  }

  function referenceScore(candidate, referenceMin, referenceMax) {
    const tolerance = value => Math.max(0.01, Math.abs(value) * 0.0001);
    const hasMin = candidate.amounts.some(value => Math.abs(value - referenceMin) <= tolerance(referenceMin));
    const hasMax = candidate.amounts.some(value => Math.abs(value - referenceMax) <= tolerance(referenceMax));
    const closest = Math.min(...candidate.amounts.map(value => Math.abs(value - referenceMin) / Math.max(1, referenceMin)));
    return (hasMin ? 100 : 0) + (hasMax ? 30 : 0) - closest * 20 - candidate.index * 0.05;
  }

  function parseMoneyAmounts(text, currency) {
    const withoutCompactCounts = String(text).replace(/\d+(?:[.,]\d+)?\s*[kKmM万]\s*(?:sold|sales|已售|销量)?/gi, " ");
    const matches = withoutCompactCounts.match(/\d[\d\s.,]*/g) || [];
    const values = matches.map(value => parseLocaleNumber(value, currency)).filter(value => Number.isFinite(value));
    return [...new Set(values)].slice(0, 4).sort((a, b) => a - b);
  }

  function parseLocaleNumber(raw, currency) {
    let value = raw.replace(/\s/g, "").replace(/^[.,]+|[.,]+$/g, "");
    if (!value) return NaN;
    const zeroDecimalCurrency = currency === "IDR" || currency === "VND";
    const comma = value.lastIndexOf(",");
    const dot = value.lastIndexOf(".");
    if (comma >= 0 && dot >= 0) {
      const decimal = comma > dot ? "," : ".";
      const fractionLength = value.length - Math.max(comma, dot) - 1;
      value = !zeroDecimalCurrency && fractionLength > 0 && fractionLength <= 2
        ? value.replace(decimal === "," ? /\./g : /,/g, "").replace(decimal, ".")
        : value.replace(/[.,]/g, "");
    } else if (comma >= 0 || dot >= 0) {
      const separator = comma >= 0 ? "," : ".";
      const pieces = value.split(separator);
      const fractionLength = pieces.at(-1).length;
      const hasDecimal = !zeroDecimalCurrency && pieces.length === 2 && fractionLength > 0 && fractionLength <= 2;
      value = hasDecimal ? `${pieces[0]}.${pieces[1]}` : pieces.join("");
    }
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 ? number : NaN;
  }

  function firstMatchingText(root, selectors, pattern) {
    const candidates = textCandidates(root, selectors, 30);
    return candidates.find(candidate => pattern.test(candidate.text)) || candidates[0] || { text: "", selector: "" };
  }

  function walkForText(root, pattern) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let inspected = 0;
    while (walker.nextNode() && inspected < 2_500) {
      inspected += 1;
      const parent = walker.currentNode.parentElement;
      const text = api.cleanText(walker.currentNode.nodeValue, 160);
      if (parent && api.isVisible(parent) && text && pattern.test(text)) {
        return { text, selector: "visible-text" };
      }
    }
    return { text: "", selector: "" };
  }

  function parseCompactCount(text) {
    const match = String(text || "").match(/(\d+(?:[.,]\d+)?)\s*([kKmM万]?)/);
    if (!match) return null;
    let number = Number(match[1].replace(",", "."));
    if (!Number.isFinite(number)) return null;
    const suffix = match[2].toLowerCase();
    if (suffix === "k") number *= 1_000;
    else if (suffix === "m") number *= 1_000_000;
    else if (suffix === "万") number *= 10_000;
    return Math.round(number);
  }

  function parseRating(text) {
    const values = (String(text || "").match(/\d+(?:[.,]\d+)?/g) || [])
      .map(value => Number(value.replace(",", "."))).filter(value => value >= 0 && value <= 5);
    return values[0] ?? null;
  }

  function parseRatingCount(text) {
    const value = String(text || "");
    const before = value.match(/(\d+(?:[.,]\d+)?)\s*([kKmM万]?)\+?\s*(?:ratings?|reviews?|đánh giá|ulasan|คะแนน|评分|評價)/i);
    const after = value.match(/(?:ratings?|reviews?|đánh giá|ulasan|คะแนน|评分|評價)\s*[:：]?\s*(\d+(?:[.,]\d+)?)\s*([kKmM万]?)/i);
    return parseCompactCount((before || after)?.slice(1, 3).join("") || "");
  }

  function variationContainer(element, allElements) {
    let current = element.parentElement;
    for (let depth = 0; current && depth < 5; depth += 1, current = current.parentElement) {
      const count = allElements.filter(candidate => current.contains(candidate)).length;
      if (count >= 2 && count <= 50) return current;
    }
    return element.parentElement;
  }

  function variationGroupName(container, allElements, index) {
    const labelSelectors = [":scope > label", ":scope > legend", ":scope > [class*='label']", ":scope > [class*='name']"];
    for (const selector of labelSelectors) {
      const label = container.querySelector(selector);
      if (!label || allElements.includes(label)) continue;
      const text = api.cleanText(label.textContent, 100);
      if (text) return text;
    }
    return `规格 ${index + 1}`;
  }
})();
