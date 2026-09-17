(() => {
  "use strict";

  const common = globalThis.HeyiCollector;
  const PRICE_SCALE = 100_000;
  const MAX_DOCUMENT_BYTES = 8_000_000;
  const MAX_SKUS = 500;

  common.loadShopeeProductData = async function loadShopeeProductData(identity) {
    if (!validIdentity(identity)) return null;
    const candidates = [];
    const fetchPromise = fetchPdp(identity).catch(() => null);
    const bridge = globalThis.HeyiCaptureBridge;
    const bridgePromise = Promise.resolve(
      typeof bridge?.readEntries === "function" ? bridge.readEntries(identity.productId, 1_500)
        : Promise.resolve(bridge?.read?.(identity.productId, 1_500) || []).then(values =>
          (Array.isArray(values) ? values : []).map(value => ({ value })))
    ).catch(() => []);

    appendEmbeddedCandidates(candidates, identity, 0);

    const [fetched, bridgedValues] = await Promise.all([fetchPromise, bridgePromise]);
    if (fetched) {
      const parsed = common.parseShopeeProductData(fetched, identity, "SHOPEE_PDP_API");
      if (parsed) candidates.push({ value: parsed, freshness: 2_000_000 });
    }
    for (const [index, entry] of (Array.isArray(bridgedValues) ? bridgedValues : []).slice(0, 8).entries()) {
      const source = ["SHOPEE_RENDERED_PDP", "SHOPEE_PAGE_STATE"].includes(entry?.source)
        ? entry.source : "SHOPEE_PAGE_RESPONSE";
      const parsed = common.parseShopeeProductData(entry?.value, identity, source);
      // The bridge returns the current SPA generation newest-first. Preserve
      // that order so a later stock/price response wins over an older peer.
      if (parsed) candidates.push({ value: parsed, freshness: 1_000_000 - index });
    }
    // Shopee can append its complete initialState script while the asynchronous
    // PDP/bridge reads above are in flight. Rescan only when every candidate is
    // still SKU-incomplete, otherwise a response captured a moment too early
    // can hide stock that is already present in the page bootstrap.
    if (!candidates.some(candidate => candidate.value.skuCompleteness?.complete === true)) {
      appendEmbeddedCandidates(candidates, identity, 1);
    }
    return selectCandidate(candidates);
  };

  function collectItemVideoUrls(item) {
    const urls=new Set();let visited=0;
    const walk=(value,depth=0)=>{if(depth>8||++visited>1500||!value)return;
      if(typeof value==='string'){if(/^https:\/\//i.test(value)&&(/\.mp4(?:[?#]|$)/i.test(value)||/\.vod\.shopee\.com\//i.test(value)))urls.add(value);return;}
      if(Array.isArray(value)){value.slice(0,50).forEach(v=>walk(v,depth+1));return;}
      if(typeof value==='object')Object.values(value).forEach(v=>walk(v,depth+1));
    };
    [item.video_info_list,item.video_info,item.video,item.videos].forEach(v=>walk(v));
    return globalThis.HeyiVideoSources.deduplicate([...urls]);
  }

  function appendEmbeddedCandidates(candidates, identity, scan) {
    for (const [index, documentValue] of embeddedDocuments(identity).entries()) {
      const parsed = common.parseShopeeProductData(documentValue, identity, "SHOPEE_BOOTSTRAP");
      if (parsed) candidates.push({ value: parsed, freshness: scan - index });
    }
  }

  common.parseShopeeProductData = function parseShopeeProductData(raw, identity, source = "SHOPEE_STRUCTURED_DATA") {
    if (!validIdentity(identity) || !raw || typeof raw !== "object") return null;
    const match = findMatchingItem(raw, identity);
    if (!match) return null;

    const item = match.item;
    const tiers = normalizeTiers(item.tier_variations || item.tierVariations);
    const itemImages = [item.image, ...(Array.isArray(item.images) ? item.images : [])]
      .map(firstImageValue).map(shopeeImageUrl).filter(Boolean);
    const detailImages = richDescriptionImages(item);
    const productMainImage = itemImages[0] || "";
    const rawModels = Array.isArray(item.models) ? item.models.slice(0, MAX_SKUS + 1) : [];
    const issues = [];
    if (!Array.isArray(item.models)) issues.push("MODELS_MISSING");
    if (item.models?.length > MAX_SKUS) issues.push("SKU_LIMIT_EXCEEDED");

    const seenModelIds = new Set();
    const skus = [];
    for (let index = 0; index < rawModels.length && skus.length < MAX_SKUS; index += 1) {
      const model = rawModels[index];
      if (!model || typeof model !== "object" || Array.isArray(model)) {
        issues.push(`SKU_${index + 1}_INVALID`);
        continue;
      }

      const externalSkuId = cleanIdentifier(model.modelid ?? model.model_id ?? model.modelId);
      if (!externalSkuId) {
        issues.push(`SKU_${index + 1}_MODEL_ID_MISSING`);
        continue;
      }
      if (seenModelIds.has(externalSkuId)) {
        issues.push("DUPLICATE_MODEL_ID");
        continue;
      }
      seenModelIds.add(externalSkuId);

      const attributeResult = modelAttributes(model, tiers);
      if (!attributeResult.complete) issues.push(`SKU_${index + 1}_ATTRIBUTES_MISSING`);

      const priceField = ownValue(model, ["price"]);
      const price = shopeeMoney(priceField.value);
      if (!priceField.present || price === null) issues.push(`SKU_${index + 1}_PRICE_MISSING`);

      const originalField = ownValue(model, ["price_before_discount", "priceBeforeDiscount"]);
      let originalPrice = shopeeMoney(originalField.value);
      let originalPriceSource = originalPrice === null ? null : "PLATFORM_ORIGINAL_PRICE";
      // Shopee uses an explicit zero in price_before_discount to mean that the
      // model is not discounted; in that case its real list price is its price.
      if (originalField.present && originalField.value === 0 && price !== null) {
        originalPrice = price;
        originalPriceSource = "NO_DISCOUNT_SAME_AS_PRICE";
      }
      if (!originalField.present || originalPrice === null) {
        issues.push(`SKU_${index + 1}_ORIGINAL_PRICE_MISSING`);
      } else if (price !== null && originalPrice < price) {
        issues.push(`SKU_${index + 1}_ORIGINAL_PRICE_INVALID`);
      }

      const stockField = shopeeStock(model);
      const stock = stockField.value;

      const modelImage = shopeeImageUrl(firstImageValue(model.image || model.model_image || model.modelImage));
      const optionImage = shopeeImageUrl(tierImage(tiers, attributeResult.indexes));
      const imageUrl = modelImage || optionImage || productMainImage;
      const imageSource = modelImage ? "MODEL" : optionImage ? "VARIANT_OPTION" : productMainImage ? "PRODUCT_MAIN" : null;
      if (!imageUrl) issues.push(`SKU_${index + 1}_IMAGE_MISSING`);
      const variationName = common.cleanText(
        attributeResult.values.join(" / ") || model.name || model.model_name,
        300
      ) || null;

      skus.push({
        externalSkuId,
        sellerSku: cleanIdentifier(model.model_sku ?? model.modelSku ?? model.sku ?? model.seller_sku ?? model.sellerSku),
        variationName,
        price,
        originalPrice,
        originalPriceSource,
        stock,
        stockSource: stock !== null ? source : null,
        attributes: attributeResult.attributes,
        imageUrl: imageUrl || null,
        imageSource,
        imageInherited: imageSource === "PRODUCT_MAIN",
        status: common.cleanText(model.model_status ?? model.status, 40) || null
      });
    }

    const expectedCount = Array.isArray(item.models) ? item.models.length : 0;
    const capturedCount = skus.length;
    if (expectedCount === 0) issues.push("NO_REAL_SKU_MODELS");
    if (expectedCount !== capturedCount) issues.push("SKU_COUNT_MISMATCH");

    const priceValues = skus.map(sku => sku.price).filter(value => value !== null);
    const originalValues = skus.map(sku => sku.originalPrice).filter(value => value !== null);
    const itemPriceMin = shopeeMoney(item.price_min ?? item.priceMin ?? item.price);
    const itemPriceMax = shopeeMoney(item.price_max ?? item.priceMax ?? item.price);
    const itemOriginalMin = shopeeMoney(item.price_min_before_discount ?? item.priceMinBeforeDiscount);
    const itemOriginalMax = shopeeMoney(item.price_max_before_discount ?? item.priceMaxBeforeDiscount);

    const envelope = raw?.data && typeof raw.data === "object" ? raw.data : raw;
    const shop = envelope?.shop_detailed || envelope?.shopDetailed || item.shop || {};
    const categories = Array.isArray(item.categories) ? item.categories : [];
    const variantImages = [];
    const seenVariantImages = new Set();
    const addVariantImage = (url, alt) => {
      if (!url || seenVariantImages.has(url)) return;
      seenVariantImages.add(url);
      variantImages.push({ url, role: "VARIANT", alt: common.cleanText(alt, 300) });
    };
    for (const tier of tiers) {
      for (const option of tier.options) {
        const url = shopeeImageUrl(option.image);
        addVariantImage(url, `${tier.name}: ${option.name}`);
      }
    }
    for (const sku of skus) {
      if (!sku.imageUrl || sku.imageInherited) continue;
      addVariantImage(sku.imageUrl, sku.variationName);
    }
    const imageCandidates = [
      ...itemImages.map((url, index) => ({ url, role: index === 0 ? "MAIN" : "GALLERY", alt: "" })),
      ...variantImages,
      ...detailImages.map(url => ({ url, role: "DETAIL", alt: "商品详情图" }))
    ];

    return {
      source,
      videos: collectItemVideoUrls(item),
      productId: String(item.item_id ?? item.itemid),
      shopId: String(item.shop_id ?? item.shopid),
      title: common.cleanText(item.name || item.title, 255),
      description: common.cleanText(item.description, 20_000),
      currency: normalizedCurrency(item.currency),
      shopName: common.cleanText(item.shop_name || item.shopName || shop.name || shop.shop_name, 160),
      categoryName: common.cleanText(categories.map(category => category?.display_name || category?.name).filter(Boolean).join(" > "), 255),
      brandName: common.cleanText(typeof item.brand === "string" ? item.brand : item.brand?.name || item.brand_info?.name, 128),
      discountText: common.cleanText(item.discount || item.discount_text || item.raw_discount, 120),
      ratingValue: normalizedRating(item.item_rating?.rating_star ?? item.itemRating?.ratingStar),
      ratingCount: nonNegativeInteger(item.item_rating?.total_rating_count ?? item.itemRating?.totalRatingCount
        ?? item.item_rating?.rating_count?.[0]),
      priceMin: minimum(priceValues) ?? itemPriceMin,
      priceMax: maximum(priceValues) ?? itemPriceMax ?? itemPriceMin,
      originalPriceMin: minimum(originalValues) ?? itemOriginalMin,
      originalPriceMax: maximum(originalValues) ?? itemOriginalMax ?? itemOriginalMin,
      images: [...new Set(itemImages)].slice(0, 120),
      imageCandidates,
      attributes: normalizeItemAttributes(item.attributes),
      variantHints: tiers.map(tier => ({ name: tier.name, values: tier.options.map(option => option.name) })),
      skus,
      skuCompleteness: {
        expectedCount,
        capturedCount,
        variantDimensionCount: tiers.length,
        complete: expectedCount > 0 && expectedCount === capturedCount && issues.length === 0,
        source,
        issues: [...new Set(issues)].slice(0, 80)
      }
    };
  };

  async function fetchPdp(identity) {
    const endpoint = new URL("/api/v4/pdp/get_pc", location.origin);
    if (endpoint.origin !== location.origin) return null;
    endpoint.searchParams.set("item_id", identity.productId);
    endpoint.searchParams.set("shop_id", identity.shopId);
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 8_000);
    try {
      const response = await fetch(endpoint.toString(), {
        method: "GET",
        credentials: "include",
        cache: "no-store",
        redirect: "error",
        signal: controller.signal
      });
      if (!response.ok) return null;
      const declaredLength = Number(response.headers.get("content-length") || 0);
      if (declaredLength > MAX_DOCUMENT_BYTES) return null;
      const text = await limitedResponseText(response);
      if (!text || text.length > MAX_DOCUMENT_BYTES) return null;
      return JSON.parse(text);
    } finally {
      window.clearTimeout(timeout);
    }
  }

  async function limitedResponseText(response) {
    if (!response.body?.getReader) return response.text();
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let bytes = 0;
    let text = "";
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        bytes += value?.byteLength || 0;
        if (bytes > MAX_DOCUMENT_BYTES) {
          await reader.cancel();
          return "";
        }
        text += decoder.decode(value, { stream: true });
      }
      return text + decoder.decode();
    } finally {
      reader.releaseLock?.();
    }
  }

  function embeddedDocuments(identity) {
    const result = [];
    let inspectedBytes = 0;
    for (const script of document.querySelectorAll("script")) {
      const text = script.textContent || "";
      inspectedBytes += text.length;
      if (inspectedBytes > MAX_DOCUMENT_BYTES * 2 || result.length >= 12) break;
      if (!text || text.length > MAX_DOCUMENT_BYTES) continue;
      if (!text.includes(identity.productId) || !/(?:tier_variations|tierVariations|models)/.test(text)) continue;
      for (const parsed of parseScriptDocuments(text)) {
        result.push(parsed);
        if (result.length >= 12) break;
      }
    }
    return result;
  }

  function parseScriptDocuments(text) {
    const result = [];
    const trimmed = text.trim();
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try {
        result.push(JSON.parse(trimmed));
        return result;
      } catch {
        // Some pages wrap the JSON in a JavaScript assignment.
      }
    }
    const markers = ["__INITIAL_STATE__", "__LOADED_DATA__", "__NEXT_DATA__", "__PRELOADED_STATE__", "initialState"];
    for (const marker of markers) {
      const markerIndex = text.indexOf(marker);
      if (markerIndex < 0) continue;
      const start = findContainerStart(text, markerIndex + marker.length);
      const value = start >= 0 ? balancedContainer(text, start) : "";
      if (!value) continue;
      try {
        result.push(JSON.parse(value));
      } catch {
        // Never evaluate page JavaScript.
      }
    }
    return result;
  }

  function findMatchingItem(root, identity) {
    const stack = [{ value: root, depth: 0 }];
    let inspected = 0;
    let best = null;
    let bestScore = -1;
    while (stack.length && inspected < 25_000) {
      const { value, depth } = stack.pop();
      inspected += 1;
      if (!value || typeof value !== "object" || depth > 14) continue;
      if (!Array.isArray(value) && matchesItem(value, identity)) {
        const score = matchingItemScore(value);
        if (score > bestScore) {
          best = { item: value };
          bestScore = score;
        }
      }
      const children = Array.isArray(value) ? value.slice(0, 600) : Object.values(value).slice(0, 600);
      for (let index = children.length - 1; index >= 0; index -= 1) {
        const child = children[index];
        if (child && typeof child === "object") stack.push({ value: child, depth: depth + 1 });
      }
    }
    return best;
  }

  function matchingItemScore(item) {
    // Bootstrap stores can include a recommendation/summary for this item
    // before its full PDP entry. Never stop at that first abbreviated object.
    const models = Array.isArray(item.models) ? item.models.slice(0, MAX_SKUS + 1) : [];
    return models.length * 10_000 + models.reduce((score, model) => score
      + (model?.price !== null && model?.price !== undefined ? 2 : 0)
      + (model?.price_before_discount !== null && model?.price_before_discount !== undefined ? 1 : 0), 0)
      + (Array.isArray(item.tier_variations || item.tierVariations) ? 100 : 0)
      + Math.min(Array.isArray(item.images) ? item.images.length : 0, 120)
      + (item.description ? 10 : 0);
  }

  function matchesItem(value, identity) {
    const productId = value.item_id ?? value.itemid;
    if (String(productId ?? "") !== identity.productId) return false;
    const shopId = value.shop_id ?? value.shopid;
    // Authoritative Shopee SKU data must identify its own shop. Accepting a
    // missing shop id and filling it from the URL can bind another seller's
    // stale/bootstrap object to the current product page.
    if (!/^\d{1,32}$/.test(String(shopId ?? "")) || String(shopId) !== identity.shopId) return false;
    return Array.isArray(value.models) || Array.isArray(value.tier_variations)
      || (typeof value.name === "string" && ("price" in value || "description" in value));
  }

  function selectCandidate(candidates) {
    if (!Array.isArray(candidates) || candidates.length === 0) return null;
    candidates.sort((left, right) => compareCandidates(right, left));
    const selected = candidates[0].value;
    // Keep the selected response's promotion prices and every explicit stock
    // value (including zero). Fill only absent inventory from the currently
    // rendered, identity-matched PDP, joined by the platform's real model ID.
    for (const { value: rendered } of candidates) {
      if (rendered === selected || rendered.source !== "SHOPEE_RENDERED_PDP"
        || rendered.productId !== selected.productId || rendered.shopId !== selected.shopId) continue;
      const stocks = new Map(rendered.skus.filter(sku => sku.stock !== null)
        .map(sku => [sku.externalSkuId, sku.stock]));
      for (const sku of selected.skus) {
        if (sku.stock !== null && sku.stock !== undefined) continue;
        if (!stocks.has(sku.externalSkuId)) continue;
        sku.stock = stocks.get(sku.externalSkuId);
        sku.stockSource = "SHOPEE_RENDERED_PDP";
      }
      if (selected.ratingValue === null) selected.ratingValue = rendered.ratingValue;
      if (selected.ratingCount === null) selected.ratingCount = rendered.ratingCount;
    }
    return selected;
  }

  function compareCandidates(left, right) {
    const leftComplete = left.value.skuCompleteness?.complete === true ? 1 : 0;
    const rightComplete = right.value.skuCompleteness?.complete === true ? 1 : 0;
    if (leftComplete !== rightComplete) return leftComplete - rightComplete;

    const leftCaptured = left.value.skuCompleteness?.capturedCount || 0;
    const rightCaptured = right.value.skuCompleteness?.capturedCount || 0;
    if (!leftComplete && leftCaptured !== rightCaptured) return leftCaptured - rightCaptured;

    // For equally complete current-page responses, freshness is authoritative:
    // inventory and promotion prices can change while the SPA remains open.
    const fieldDifference = candidateFieldScore(left.value) - candidateFieldScore(right.value);
    if (!leftComplete && fieldDifference !== 0) return fieldDifference;
    if (left.freshness !== right.freshness) return left.freshness - right.freshness;
    return fieldDifference;
  }

  function candidateFieldScore(candidate) {
    const scalarFields = [
      "title", "description", "currency", "shopName", "categoryName", "brandName",
      "priceMin", "priceMax", "originalPriceMin", "originalPriceMax"
    ];
    const scalars = scalarFields.reduce((score, field) => {
      const value = candidate?.[field];
      return score + (value !== null && value !== undefined && value !== "" ? 1 : 0);
    }, 0);
    const skuFields = (candidate?.skus || []).reduce((score, sku) => score
      + (sku.externalSkuId ? 1 : 0)
      + (sku.price !== null && sku.price !== undefined ? 1 : 0)
      + (sku.originalPrice !== null && sku.originalPrice !== undefined ? 1 : 0)
      + (sku.stock !== null && sku.stock !== undefined ? 1 : 0)
      + (sku.imageUrl ? 1 : 0), 0);
    return scalars * 100 + skuFields * 10
      + Math.min(candidate?.imageCandidates?.length || candidate?.images?.length || 0, 120)
      + Math.min(Object.keys(candidate?.attributes || {}).length, 50);
  }

  function normalizeTiers(value) {
    if (!Array.isArray(value)) return [];
    return value.slice(0, 30).map((tier, tierIndex) => {
      const rawOptions = Array.isArray(tier?.options) ? tier.options : [];
      const rawImages = Array.isArray(tier?.images) ? tier.images : [];
      return {
        name: common.cleanText(tier?.name, 100) || `规格 ${tierIndex + 1}`,
        namePresent: Boolean(common.cleanText(tier?.name, 100)),
        options: rawOptions.slice(0, 100).map((option, optionIndex) => ({
          name: common.cleanText(
            typeof option === "string" ? option : option?.name || option?.value || option?.option,
            160
          ),
          image: firstImageValue(
            typeof option === "object" ? option?.image || option?.image_url || option?.imageUrl : null
          ) || firstImageValue(rawImages[optionIndex])
        }))
      };
    });
  }

  function modelAttributes(model, tiers) {
    if (tiers.length === 0) return { attributes: {}, values: [], indexes: [], complete: true };
    const indexes = model.tier_index || model.tierIndex || model.extinfo?.tier_index || model.extInfo?.tierIndex;
    if (!Array.isArray(indexes) || indexes.length !== tiers.length) {
      return { attributes: {}, values: [], indexes: [], complete: false };
    }
    const attributes = {};
    const values = [];
    let complete = true;
    tiers.forEach((tier, tierIndex) => {
      const optionIndex = Number(indexes[tierIndex]);
      const option = Number.isInteger(optionIndex) && optionIndex >= 0 ? tier.options[optionIndex] : null;
      if (!tier.namePresent || !option?.name) complete = false;
      if (option?.name) {
        attributes[tier.name] = option.name;
        values.push(option.name);
      }
    });
    return { attributes, values, indexes, complete };
  }

  function tierImage(tiers, indexes) {
    for (let tierIndex = 0; tierIndex < tiers.length; tierIndex += 1) {
      const optionIndex = Number(indexes?.[tierIndex]);
      const image = tiers[tierIndex]?.options?.[optionIndex]?.image;
      if (image) return image;
    }
    return "";
  }

  function normalizeItemAttributes(value) {
    const result = {};
    if (!Array.isArray(value)) return result;
    for (const attribute of value.slice(0, 50)) {
      const key = common.cleanText(attribute?.name || attribute?.display_name || attribute?.key, 100);
      const raw = attribute?.value ?? attribute?.display_value ?? attribute?.values;
      const display = Array.isArray(raw)
        ? raw.map(item => typeof item === "string" ? item : item?.name || item?.value).filter(Boolean).join(", ")
        : typeof raw === "object" ? raw?.name || raw?.value : raw;
      const safeValue = common.cleanText(display, 500);
      if (key && safeValue) result[key] = safeValue;
    }
    return result;
  }

  function richDescriptionImages(item) {
    const containers = [
      item.extended_description,
      item.extendedDescription,
      item.rich_text_description,
      item.richTextDescription,
      item.description_info,
      item.descriptionInfo,
      item.description
    ].filter(value => value && (typeof value === "object" || typeof value === "string"));
    const raw = [];
    for (const container of containers) collectDescriptionImageValues(container, raw, false, 0);
    return [...new Set(raw.map(shopeeImageUrl).filter(Boolean))].slice(0, 120);
  }

  function collectDescriptionImageValues(value, result, imageContext, depth) {
    if (result.length >= 120 || value === null || value === undefined || depth > 12) return;
    if (typeof value === "string") {
      if (imageContext) result.push(value);
      for (const match of value.matchAll(/(?:src|data-src)\s*=\s*["']([^"']+)["']/gi)) {
        result.push(match[1]);
        if (result.length >= 120) break;
      }
      return;
    }
    if (Array.isArray(value)) {
      value.slice(0, 300).forEach(child => collectDescriptionImageValues(child, result, imageContext, depth + 1));
      return;
    }
    if (typeof value !== "object") return;
    for (const [key, child] of Object.entries(value).slice(0, 300)) {
      const childImageContext = imageContext || /(?:^|_)(?:image|images|img|picture|photo)(?:_|$)/i.test(key)
        || /(?:image|picture|photo)/i.test(key);
      collectDescriptionImageValues(child, result, childImageContext, depth + 1);
      if (result.length >= 120) break;
    }
  }

  function shopeeMoney(value) {
    if (value === null || value === undefined || (typeof value === "string" && !value.trim())) return null;
    if (typeof value === "string" && !/^\d+(?:\.\d+)?$/.test(value.trim())) return null;
    const raw = Number(value);
    if (!Number.isFinite(raw) || raw < 0) return null;
    const decoded = raw / PRICE_SCALE;
    return Number.isFinite(decoded) ? Math.round(decoded * 1_000_000) / 1_000_000 : null;
  }

  function shopeeImageUrl(value) {
    const raw = common.cleanText(firstImageValue(value), 1_000);
    if (!raw) return "";
    const absolute = /^(?:https:\/\/|\/)/i.test(raw) ? common.absoluteUrl(raw) : "";
    if (absolute) return absolute;
    if (!/^[A-Za-z0-9_-]{8,500}$/.test(raw)) return "";
    const market = location.hostname.endsWith(".com.my") ? "my"
      : location.hostname.endsWith(".co.th") ? "th"
        : location.hostname.endsWith(".co.id") ? "id"
          : location.hostname.split(".").at(-1);
    return `https://down-${market}.img.susercontent.com/file/${raw}`;
  }

  function firstImageValue(value) {
    if (typeof value === "string") return value;
    if (Array.isArray(value)) return firstImageValue(value[0]);
    if (!value || typeof value !== "object") return "";
    return firstImageValue(value.url || value.image_url || value.imageUrl || value.url_list || value.urls);
  }

  function ownValue(object, keys) {
    for (const key of keys) {
      if (Object.prototype.hasOwnProperty.call(object, key)) return { present: true, value: object[key] };
    }
    return { present: false, value: null };
  }

  function firstParsedValue(object, keys, parser) {
    let present = false;
    for (const key of keys) {
      if (!Object.prototype.hasOwnProperty.call(object, key)) continue;
      present = true;
      const value = parser(object[key]);
      if (value !== null) return { present: true, value };
    }
    return { present, value: null };
  }

  function shopeeStock(model) {
    const direct = firstParsedValue(
      model,
      ["stock", "normal_stock", "normalStock", "available_stock", "availableStock"],
      nonNegativeInteger
    );
    if (direct.value !== null) return direct;

    let present = direct.present;
    const stockInfoV2 = model.stock_info_v2 || model.stockInfoV2;
    if (stockInfoV2 && typeof stockInfoV2 === "object" && !Array.isArray(stockInfoV2)) {
      present = true;
      const summary = stockInfoV2.summary_info || stockInfoV2.summaryInfo;
      if (summary && typeof summary === "object" && !Array.isArray(summary)) {
        const available = firstParsedValue(
          summary,
          ["total_available_stock", "totalAvailableStock"],
          nonNegativeInteger
        );
        if (available.value !== null) return { present: true, value: available.value };
      }
    }

    const legacyStockInfo = model.stock_info || model.stockInfo;
    const legacyTotal = sumCompleteStockArray(legacyStockInfo, ["current_stock", "currentStock"]);
    if (legacyTotal.present) {
      present = true;
      if (legacyTotal.value !== null) return { present: true, value: legacyTotal.value };
    }

    const priceStocks = model.price_stocks || model.priceStocks;
    if (Array.isArray(priceStocks)) {
      present = true;
      const totals = [];
      for (const priceStock of priceStocks) {
        if (!priceStock || typeof priceStock !== "object" || Array.isArray(priceStock)) {
          return { present: true, value: null };
        }
        const breakdown = priceStock?.stock_breakdown_by_location || priceStock?.stockBreakdownByLocation;
        const total = sumCompleteStockArray(breakdown, ["available_stock", "availableStock"]);
        // Every price group and every location inside it must be authoritative.
        // Ignoring an incomplete group can turn an observed zero in another
        // group into a fabricated zero for the whole SKU.
        if (!total.present || total.value === null) return { present: true, value: null };
        totals.push(total.value);
      }
      // Price/promotion entries can repeat the same location inventory. Only
      // accept the fallback when every complete entry agrees; never add entries
      // across price groups or guess when Shopee returns conflicting totals.
      if (totals.length && totals.every(value => value === totals[0])) {
        return { present: true, value: totals[0] };
      }
    }
    return { present, value: null };
  }

  function sumCompleteStockArray(values, keys) {
    if (!Array.isArray(values)) return { present: false, value: null };
    if (values.length === 0) return { present: true, value: null };
    let total = 0;
    for (const value of values) {
      if (!value || typeof value !== "object" || Array.isArray(value)) {
        return { present: true, value: null };
      }
      const parsed = firstParsedValue(value, keys, nonNegativeInteger);
      if (!parsed.present || parsed.value === null || total > 1_000_000_000 - parsed.value) {
        return { present: true, value: null };
      }
      total += parsed.value;
    }
    return { present: true, value: total };
  }

  function normalizedCurrency(value) {
    const currency = common.cleanText(value, 8).toUpperCase();
    return /^[A-Z]{3}$/.test(currency) ? currency : null;
  }

  function normalizedRating(value) {
    if (value === null || value === undefined || value === "" || typeof value === "boolean") return null;
    const rating = Number(value);
    return Number.isFinite(rating) && rating >= 0 && rating <= 5 ? rating : null;
  }

  function cleanIdentifier(value) {
    const result = common.cleanText(value, 128);
    return /^[A-Za-z0-9._:-]+$/.test(result) ? result : null;
  }

  function nonNegativeInteger(value) {
    if (value === null || value === undefined || value === "") return null;
    const number = Number(value);
    return Number.isSafeInteger(number) && number >= 0 && number <= 1_000_000_000 ? number : null;
  }

  function validIdentity(identity) {
    return /^\d{1,32}$/.test(identity?.productId || "") && /^\d{1,32}$/.test(identity?.shopId || "");
  }

  function minimum(values) {
    return values.length ? Math.min(...values) : null;
  }

  function maximum(values) {
    return values.length ? Math.max(...values) : null;
  }

  function findContainerStart(text, offset) {
    const object = text.indexOf("{", offset);
    const array = text.indexOf("[", offset);
    if (object < 0) return array;
    if (array < 0) return object;
    return Math.min(object, array);
  }

  function balancedContainer(text, start) {
    const opening = text[start];
    const closing = opening === "{" ? "}" : opening === "[" ? "]" : "";
    if (!closing) return "";
    let depth = 0;
    let string = false;
    let escaped = false;
    for (let index = start; index < text.length && index - start <= MAX_DOCUMENT_BYTES; index += 1) {
      const character = text[index];
      if (string) {
        if (escaped) escaped = false;
        else if (character === "\\") escaped = true;
        else if (character === '"') string = false;
        continue;
      }
      if (character === '"') string = true;
      else if (character === opening) depth += 1;
      else if (character === closing) {
        depth -= 1;
        if (depth === 0) return text.slice(start, index + 1);
      }
    }
    return "";
  }
})();
