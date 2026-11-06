(() => {
  "use strict";

  const common = globalThis.HeyiCollector;
  const MAX_DOCUMENT_BYTES = 8_000_000;
  const MAX_DESCRIPTION_LENGTH = 20_000;
  const MAX_DESCRIPTION_NODES = 2_000;
  const MAX_SKUS = 500;

  common.loadTikTokProductData = async function loadTikTokProductData(identity) {
    if (!validProductId(identity?.productId)) return null;
    const inputs = [];

    for (const [index, value] of embeddedDocuments(identity.productId).entries()) {
      inputs.push({ value, source: "TIKTOK_BOOTSTRAP", sourceRank: 0, freshness: -index });
    }

    for (const [index, entry] of (await bridgeEntries(identity.productId)).slice(0, 8).entries()) {
      inputs.push({
        value: entry.value,
        source: "TIKTOK_PAGE_RESPONSE",
        sourceRank: 1,
        freshness: Number.isSafeInteger(entry.sequence) ? entry.sequence : -index
      });
    }

    const priceFragments = inputs.flatMap(input => promotionPriceFragments(input.value, identity.productId)
      .map(fragment => ({ ...fragment, sourceRank: input.sourceRank, freshness: input.freshness })));
    const candidates = [];
    for (const input of inputs) {
      const match = findMatchingProduct(input.value, identity.productId);
      if (!match) continue;
      const merged = productWithPromotionPrices(match, priceFragments, identity.productId, input);
      const parsed = common.parseTikTokProductData(merged, identity, input.source);
      if (parsed) candidates.push({ value: parsed, sourceRank: input.sourceRank, freshness: input.freshness });
    }
    return selectCandidate(candidates);
  };

  async function bridgeEntries(productId) {
    const bridge = globalThis.HeyiCaptureBridge;
    try {
      if (typeof bridge?.readEntries === "function") {
        const entries = await bridge.readEntries(productId, 1_500);
        return Array.isArray(entries)
          ? entries.filter(entry => entry && typeof entry === "object" && "value" in entry)
          : [];
      }
      const values = await bridge?.read?.(productId, 1_500) || [];
      return Array.isArray(values) ? values.map(value => ({ value, sequence: null })) : [];
    } catch {
      return [];
    }
  }

  common.parseTikTokProductData = function parseTikTokProductData(raw, identity, source = "TIKTOK_STRUCTURED_DATA") {
    if (!validProductId(identity?.productId) || !raw || typeof raw !== "object") return null;
    const match = findMatchingProduct(raw, identity.productId);
    if (!match) return null;
    const product = match.product;
    const productContext = match.context;
    const productShopId = cleanIdentifier(
      product.shop_id ?? product.shopId ?? product.seller_id ?? product.sellerId ?? product.seller?.id
    );
    if (identity.shopId && productShopId && identity.shopId !== productShopId) return null;

    const rawSkus = productSkuArray(product);
    const definitions = productAttributeDefinitions(product);
    const allProductImages = productImages(product);
    const productMainImage = allProductImages[0] || null;
    const issues = [];
    if (!rawSkus) issues.push("SKUS_MISSING");
    if (rawSkus?.length > MAX_SKUS) issues.push("SKU_LIMIT_EXCEEDED");
    const expectedCount = rawSkus?.length || 0;
    const skus = [];
    const seen = new Set();

    for (let index = 0; index < Math.min(expectedCount, MAX_SKUS); index += 1) {
      const sku = rawSkus[index];
      if (!sku || typeof sku !== "object" || Array.isArray(sku)) {
        issues.push(`SKU_${index + 1}_INVALID`);
        continue;
      }
      const externalSkuId = cleanIdentifier(sku.sku_id ?? sku.skuId ?? sku.id);
      if (!externalSkuId) {
        issues.push(`SKU_${index + 1}_ID_MISSING`);
        continue;
      }
      if (seen.has(externalSkuId)) {
        issues.push("DUPLICATE_MODEL_ID");
        continue;
      }
      seen.add(externalSkuId);

      const attributeResult = tikTokAttributes(sku, definitions);
      if (!attributeResult.complete) issues.push(`SKU_${index + 1}_ATTRIBUTES_MISSING`);
      const promotionPrice = promotionPriceForSku(productContext, externalSkuId);
      const price = skuPrice(sku, false, promotionPrice);
      let originalPrice = skuPrice(sku, true, promotionPrice);
      let originalPriceSource = originalPrice === null ? null : "PLATFORM_ORIGINAL_PRICE";
      if ((originalPrice === null || originalPrice === 0) && price !== null && explicitlyNotDiscounted(sku, promotionPrice)) {
        originalPrice = price;
        originalPriceSource = "NO_DISCOUNT_SAME_AS_PRICE";
      }
      const stock = skuStock(sku);
      if (price === null) issues.push(`SKU_${index + 1}_PRICE_MISSING`);
      if (originalPrice === null) issues.push(`SKU_${index + 1}_ORIGINAL_PRICE_MISSING`);

      const directImage = firstHttpsImage([
        sku.sku_origin_image, sku.skuOriginImage, sku.sku_image, sku.skuImage, sku.sku_img, sku.image
      ]);
      const attributeImage = firstAttributeImage(sku) || propertyDefinitionImage(sku, definitions);
      const imageUrl = directImage || attributeImage || productMainImage;
      const imageSource = directImage ? "SKU" : attributeImage ? "VARIANT_OPTION" : productMainImage ? "PRODUCT_MAIN" : null;
      if (!imageUrl) issues.push(`SKU_${index + 1}_IMAGE_MISSING`);

      skus.push({
        externalSkuId,
        sellerSku: cleanIdentifier(sku.seller_sku ?? sku.sellerSku ?? sku.sku_code ?? sku.skuCode),
        variationName: common.cleanText(
          attributeResult.values.join(" / ") || sku.sku_name || sku.skuName || sku.name,
          300
        ) || null,
        price,
        originalPrice,
        originalPriceSource,
        stock,
        attributes: attributeResult.attributes,
        imageUrl,
        imageSource,
        imageInherited: imageSource === "PRODUCT_MAIN",
        status: common.cleanText(sku.status ?? sku.sku_status ?? sku.skuStatus, 40) || null
      });
    }

    if (expectedCount === 0) issues.push("NO_REAL_SKUS");
    if (expectedCount !== skus.length) issues.push("SKU_COUNT_MISMATCH");
    const variantDimensionCount = definitions.length
      || skus.reduce((maximum, sku) => Math.max(maximum, Object.keys(sku.attributes).length), 0);
    if (variantDimensionCount > 0
      && skus.some(sku => Object.keys(sku.attributes).length !== variantDimensionCount)) {
      issues.push("SKU_ATTRIBUTE_DIMENSION_MISMATCH");
    }
    const prices = skus.map(value => value.price).filter(value => value !== null);
    const originals = skus.map(value => value.originalPrice).filter(value => value !== null);

    return {
      source,
      productId: String(product.product_id ?? product.productId ?? product.id),
      shopId: productShopId,
      title: common.cleanText(product.title || product.product_name || product.productName || product.name, 255),
      description: productDescription(product),
      currency: productCurrency(product, productContext),
      shopName: common.cleanText(product.shop_name || product.shopName || product.seller?.name || product.shop?.name
        || productContext?.seller_model?.shop_name || productContext?.seller_model?.name
        || productContext?.sellerModel?.shopName || productContext?.sellerModel?.name
        || productContext?.shop_model?.name || productContext?.shopModel?.name, 160),
      categoryName: common.cleanText(categoryText(product, productContext), 255),
      brandName: common.cleanText(product.brand_name || product.brandName || product.brand?.name, 128),
      discountText: common.cleanText(product.discount || product.discount_text || product.discountText, 120),
      salesCount: nonNegativeNumber(product.sold_count ?? product.soldCount),
      ratingValue: boundedNumber(
        productContext?.review_model?.product_overall_score ?? productContext?.reviewModel?.productOverallScore,
        0,
        5
      ),
      ratingCount: nonNegativeNumber(
        productContext?.review_model?.product_review_count ?? productContext?.reviewModel?.productReviewCount
      ),
      priceMin: prices.length ? Math.min(...prices) : null,
      priceMax: prices.length ? Math.max(...prices) : null,
      originalPriceMin: originals.length ? Math.min(...originals) : null,
      originalPriceMax: originals.length ? Math.max(...originals) : null,
      images: allProductImages,
      detailImages: productDetailImages(product),
      attributes: productAttributes(product, productContext),
      variantHints: definitions.map(group => ({ name: group.name, values: group.values.map(value => value.name) })),
      skus,
      skuCompleteness: {
        expectedCount,
        capturedCount: skus.length,
        variantDimensionCount,
        complete: expectedCount > 0 && expectedCount === skus.length && issues.length === 0,
        source,
        issues: [...new Set(issues)].slice(0, 80)
      }
    };
  };

  function embeddedDocuments(productId) {
    const result = [];
    let inspectedBytes = 0;
    const selectors = [
      "script#__NEXT_DATA__", "script#__UNIVERSAL_DATA_FOR_REHYDRATION__", "script#__MODERN_ROUTER_DATA__",
      "script[id*='INITIAL_STATE']", "script[id*='REHYDRATION']", "script[type='application/json']"
    ];
    for (const script of document.querySelectorAll(selectors.join(","))) {
      const text = script.textContent || "";
      inspectedBytes += text.length;
      if (inspectedBytes > MAX_DOCUMENT_BYTES * 2 || result.length >= 12) break;
      if (!text || text.length > MAX_DOCUMENT_BYTES || !text.includes(productId)) continue;
      try {
        result.push(JSON.parse(text));
      } catch {
        // Only strict JSON is accepted; page JavaScript is never evaluated.
      }
    }
    return result;
  }

  function findMatchingProduct(root, productId) {
    const stack = [{ value: root, depth: 0 }];
    let inspected = 0;
    let best = null;
    while (stack.length && inspected < 35_000) {
      const { value, depth } = stack.pop();
      inspected += 1;
      if (!value || typeof value !== "object" || depth > 16) continue;
      if (!Array.isArray(value)) {
        const productInfo = value.product_info || value.productInfo;
        const productContext = productInfo && typeof productInfo === "object" && !Array.isArray(productInfo)
          ? productInfo : value;
        const nestedProduct = productContext.product_model || productContext.productModel;
        const nestedId = nestedProduct?.product_id ?? nestedProduct?.productId;
        if (nestedProduct && productSkuArray(nestedProduct) && String(nestedId ?? "") === productId) {
          // TikTok's PDP component puts its category breadcrumb alongside
          // product_info. Carry that sibling only after its product ID matches.
          const context = productContext === value ? value : {
            ...productContext,
            category_info: productContext.category_info || productContext.categoryInfo
              || value.category_info || value.categoryInfo || null
          };
          return { product: nestedProduct, context };
        }
        const candidateId = value.product_id ?? value.productId ?? (productSkuArray(value) ? value.id : null);
        if (String(candidateId ?? "") === productId) {
          if (productSkuArray(value)) return { product: value, context: value };
          best ||= { product: value, context: value };
        }
      }
      const children = Array.isArray(value) ? value.slice(0, 700) : Object.values(value).slice(0, 700);
      for (let index = children.length - 1; index >= 0; index -= 1) {
        const child = children[index];
        if (child && typeof child === "object") stack.push({ value: child, depth: depth + 1 });
      }
    }
    return best;
  }

  function productSkuArray(product) {
    const direct = product.sku_infos || product.skuInfos || product.skus || product.sku_list || product.skuList;
    if (Array.isArray(direct)) return direct;
    const containers = [product.sku_info, product.skuInfo, product.sku_data, product.skuData, product.product_skus];
    for (const value of containers) {
      if (!value || typeof value !== "object") continue;
      const nested = value.sku_infos || value.skuInfos || value.skus || value.sku_list || value.skuList;
      if (Array.isArray(nested)) return nested;
    }
    return null;
  }

  function productAttributeDefinitions(product) {
    const source = product.sale_properties || product.saleProperties || product.sale_props || product.saleProps || product.sales_attributes
      || product.salesAttributes || product.sku_properties || product.skuProperties || [];
    if (!Array.isArray(source)) return [];
    return source.slice(0, 30).map((group, index) => {
      const values = group?.property_values || group?.propertyValues || group?.values || group?.options
        || group?.sale_prop_values || group?.salePropValues || [];
      return {
        id: cleanIdentifier(group?.id ?? group?.prop_id ?? group?.property_id ?? group?.sku_property_id),
        name: common.cleanText(group?.name || group?.prop_name || group?.property_name || group?.sku_property_name, 100) || `规格 ${index + 1}`,
        namePresent: Boolean(common.cleanText(group?.name || group?.prop_name || group?.property_name || group?.sku_property_name, 100)),
        values: Array.isArray(values) ? values.slice(0, 100).map(value => ({
          id: cleanIdentifier(value?.id ?? value?.value_id ?? value?.prop_value_id
            ?? value?.property_value_id ?? value?.sku_property_value_id),
          name: common.cleanText(typeof value === "string" ? value : value?.name || value?.value_name
            || value?.property_value_name || value?.sku_property_value_name || value?.prop_value || value?.value, 160),
          image: firstHttpsImage(value?.image || value?.sku_img || value?.skuImg)
        })) : []
      };
    }).filter(group => group.name !== "-");
  }

  function tikTokAttributes(sku, definitions) {
    const attributes = {};
    const values = [];
    const direct = sku.sku_sale_props || sku.property_pairs || sku.propertyPairs || sku.sales_attributes || sku.salesAttributes || sku.sku_sales_attrs
      || sku.skuSalesAttrs || sku.attributes || sku.properties;
    if (Array.isArray(direct) && direct.length) {
      let complete = true;
      for (const item of direct.slice(0, 30)) {
        const key = common.cleanText(item?.sku_property_name || item?.skuPropertyName || item?.name
          || item?.attribute_name || item?.property_name || item?.prop_name, 100);
        const value = common.cleanText(item?.sku_property_value_name || item?.skuPropertyValueName
          || item?.value_name || item?.attribute_value || item?.property_value || item?.prop_value || item?.value, 160);
        if (key === "-") continue;
        if (!key || !value) complete = false;
        if (key && value) {
          attributes[key] = value;
          values.push(value);
        }
      }
      return { attributes, values, complete };
    }

    const rawIds = sku.sale_prop_value_ids || sku.sku_sale_prop_ids || sku.skuSalePropIds || sku.sales_attr_value_ids || sku.salesAttrValueIds;
    const ids = Array.isArray(rawIds)
      ? rawIds.map(cleanIdentifier).filter(Boolean)
      : common.cleanText(rawIds, 2_000).split(/[_,;|]/).map(cleanIdentifier).filter(Boolean);
    if (ids.length) {
      let complete = true;
      for (const id of ids) {
        let matched = false;
        for (const definition of definitions) {
          const option = definition.values.find(value => value.id === id);
          if (!option) continue;
          matched = true;
          if (!definition.namePresent || !option.name) complete = false;
          if (option.name) {
            attributes[definition.name] = option.name;
            values.push(option.name);
          }
          break;
        }
        if (!matched) complete = false;
      }
      return { attributes, values, complete };
    }
    return { attributes, values, complete: definitions.length === 0 };
  }

  function skuPrice(sku, original, promotionPrice) {
    const price = sku.price_info || sku.priceInfo || sku.price || {};
    const values = original
      ? [promotionPrice?.origin_price_decimal, promotionPrice?.originPriceDecimal,
        promotionPrice?.original_price, promotionPrice?.originalPrice, promotionPrice?.origin_price, promotionPrice?.originPrice,
        sku.original_price, sku.originalPrice, sku.origin_price, sku.originPrice, sku.list_price, sku.listPrice,
        price.origin_price_decimal, price.originPriceDecimal,
        price.original_price, price.originalPrice, price.origin_price, price.originPrice, price.list_price, price.listPrice]
      : [promotionPrice?.sale_price_decimal, promotionPrice?.salePriceDecimal,
        promotionPrice?.sale_price, promotionPrice?.salePrice, promotionPrice?.price,
        sku.sale_price, sku.salePrice, sku.price_value, sku.priceValue,
        price.sale_price_decimal, price.salePriceDecimal,
        price.sale_price, price.salePrice, price.amount, price.price, price.current_price, price.currentPrice];
    for (const value of values) {
      const result = exactMoney(value);
      if (result !== null) return result;
    }
    return null;
  }

  function promotionPriceForSku(context, skuId) {
    const priceModel = context?.promotion_model?.promotion_product_price
      || context?.promotionModel?.promotionProductPrice
      || context?.promotion_product_price || context?.promotionProductPrice;
    const values = priceModel?.skus_price || priceModel?.skusPrice;
    if (Array.isArray(values)) {
      return values.find(value => cleanIdentifier(value?.sku_id ?? value?.skuId ?? value?.id) === skuId) || null;
    }
    if (values && typeof values === "object") {
      const direct = values[skuId];
      if (direct && typeof direct === "object") return direct;
      return Object.values(values).find(value => cleanIdentifier(value?.sku_id ?? value?.skuId ?? value?.id) === skuId) || null;
    }
    return null;
  }

  function exactMoney(value) {
    if (value === null || value === undefined || value === "") return null;
    if (typeof value === "number") return Number.isFinite(value) && value >= 0 ? value : null;
    if (typeof value === "string") {
      const normalized = value.trim().replace(/,/g, "");
      if (!/^\d+(?:\.\d+)?$/.test(normalized)) return null;
      const number = Number(normalized);
      return Number.isFinite(number) ? number : null;
    }
    if (typeof value !== "object" || Array.isArray(value)) return null;
    const raw = value.price_val ?? value.priceVal ?? value.amount ?? value.value;
    const decimals = value.price_decimal ?? value.priceDecimal ?? value.decimal_places ?? value.decimalPlaces;
    if (raw !== null && raw !== undefined && decimals !== null && decimals !== undefined) {
      const integer = Number(raw);
      const places = Number(decimals);
      if (Number.isSafeInteger(integer) && Number.isInteger(places) && places >= 0 && places <= 8) {
        return integer / (10 ** places);
      }
      return null;
    }
    return exactMoney(raw);
  }

  function skuStock(sku) {
    const direct = sku.stock ?? sku.available_stock ?? sku.availableStock ?? sku.inventory_quantity ?? sku.inventoryQuantity
      ?? sku.sku_quantity?.available_quantity ?? sku.skuQuantity?.availableQuantity;
    const parsed = nonNegativeInteger(direct);
    if (parsed !== null) return parsed;
    const containers = [sku.inventory, sku.inventories, sku.stock_infos, sku.stockInfos];
    for (const container of containers) {
      if (!Array.isArray(container) || container.length === 0) continue;
      const values = container.map(entry => nonNegativeInteger(
        entry?.available_stock ?? entry?.availableStock ?? entry?.quantity ?? entry?.available_quantity ?? entry?.availableQuantity
      ));
      if (values.every(value => value !== null)) return values.reduce((sum, value) => sum + value, 0);
    }
    return null;
  }

  function productCurrency(product, context) {
    const priceModel = context?.promotion_model?.promotion_product_price
      || context?.promotionModel?.promotionProductPrice || {};
    const priceEntries = priceModel.skus_price || priceModel.skusPrice;
    const firstPriceEntry = Array.isArray(priceEntries)
      ? priceEntries[0]
      : priceEntries && typeof priceEntries === "object" ? Object.values(priceEntries)[0] : null;
    const firstSku = productSkuArray(product)?.[0] || {};
    const candidates = [product.currency, product.currency_code, product.currencyCode,
      product.price?.currency, product.price_info?.currency, priceModel.currency_name, priceModel.currencyName,
      firstPriceEntry?.currency_name, firstPriceEntry?.currencyName, firstPriceEntry?.currency,
      firstSku.price?.currency, firstSku.price?.currency_name, firstSku.price_info?.currency];
    for (const value of candidates) {
      const result = common.cleanText(typeof value === "object" ? value?.code : value, 8).toUpperCase();
      if (/^[A-Z]{3}$/.test(result)) return result;
    }
    return null;
  }

  function productDescription(product) {
    const value = product.description ?? product.product_description ?? product.productDescription ?? product.desc_blocks;
    if (value === null || value === undefined) return "";
    if (typeof value !== "string") return richDescriptionText(value);

    const plainText = cleanDescriptionText(value);
    const candidate = value.trim();
    if (!candidate.startsWith("[") && !candidate.startsWith("{")) return plainText;
    try {
      const parsed = JSON.parse(candidate);
      return parsed && typeof parsed === "object" ? richDescriptionText(parsed) : plainText;
    } catch {
      // A normal description may legitimately begin with '[' or '{'.
      return plainText;
    }
  }

  function richDescriptionText(value) {
    const state = { parts: [], nodes: 0, length: 0, seen: new WeakSet() };
    collectDescriptionText(value, state, 0);
    return cleanDescriptionText(state.parts.join("\n"));
  }

  function collectDescriptionText(value, state, depth) {
    if (state.length >= MAX_DESCRIPTION_LENGTH || state.nodes >= MAX_DESCRIPTION_NODES || depth > 20) return;
    if (typeof value === "string") {
      addDescriptionPart(value, state);
      return;
    }
    if (!value || typeof value !== "object" || state.seen.has(value)) return;
    state.seen.add(value);
    state.nodes += 1;

    if (Array.isArray(value)) {
      for (const child of value) collectDescriptionText(child, state, depth + 1);
      return;
    }

    const type = String(value.type ?? value.field_type ?? value.fieldType ?? value.kind ?? "").toLowerCase();
    if (/(?:^|[_-])(image|photo|picture|video|media)(?:$|[_-])/.test(type)) return;

    // TikTok text blocks commonly repeat the same text in `text` and `sub[].t`.
    // Prefer the block's direct text so it is emitted once, then only traverse a
    // known child container when no direct text exists.
    for (const key of ["text", "plain_text", "plainText", "t", "value", "content"]) {
      if (typeof value[key] !== "string") continue;
      if (addDescriptionPart(value[key], state)) return;
    }
    for (const key of ["content", "contents", "children", "nodes", "blocks", "items", "sub", "paragraphs", "sections", "body", "description"]) {
      const child = value[key];
      if (!child || typeof child !== "object") continue;
      const previousLength = state.length;
      collectDescriptionText(child, state, depth + 1);
      if (state.length > previousLength) return;
    }
  }

  function addDescriptionPart(value, state) {
    const part = cleanDescriptionText(value, MAX_DESCRIPTION_LENGTH - state.length).trim();
    if (!part || /^(?:https?:)?\/\/\S+$/i.test(part)) return false;
    if (state.parts.length && state.length < MAX_DESCRIPTION_LENGTH) state.length += 1;
    state.parts.push(part);
    state.length += part.length;
    return true;
  }

  function cleanDescriptionText(value, maxLength = MAX_DESCRIPTION_LENGTH) {
    return String(value).replace(/\r\n?/g, "\n")
      .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, " ")
      .slice(0, Math.max(0, maxLength));
  }

  function productImages(product) {
    const candidates = [product.main_image, product.mainImage, product.images, product.product_images, product.productImages];
    const result = [];
    for (const candidate of candidates) collectImageUrls(candidate, result);
    return [...new Set(result)].slice(0, 120);
  }

  function productDetailImages(product) {
    let value = product.description ?? product.product_description ?? product.productDescription ?? product.desc_blocks;
    if (typeof value === "string") {
      const text = value.trim();
      if (text.length > MAX_DOCUMENT_BYTES || (!text.startsWith("[") && !text.startsWith("{"))) return [];
      try { value = JSON.parse(text); } catch { return []; }
    }
    const state = { images: [], nodes: 0, seen: new WeakSet() };
    collectDescriptionImages(value, state, 0);
    collectImageUrls(product.size_guide?.image, state.images);
    return [...new Set(state.images)].slice(0, 120);
  }

  function collectDescriptionImages(value, state, depth) {
    if (!value || typeof value !== "object" || state.images.length >= 120
      || state.nodes >= MAX_DESCRIPTION_NODES || depth > 20 || state.seen.has(value)) return;
    state.seen.add(value);
    state.nodes += 1;
    if (Array.isArray(value)) {
      for (const child of value.slice(0, MAX_DESCRIPTION_NODES)) collectDescriptionImages(child, state, depth + 1);
      return;
    }
    const type = String(value.type ?? value.field_type ?? value.fieldType ?? value.kind ?? "").toLowerCase();
    if (/(?:^|[_-])(?:video|review|recommendation)(?:$|[_-])/.test(type)) return;
    const imageNode = /(?:^|[_-])(?:image|img|photo|picture)(?:$|[_-])/.test(type);
    if (imageNode) collectImageUrls(value, state.images);
    for (const key of ["image", "images", "image_info", "imageInfo", "picture", "photo"]) {
      const image = value[key];
      collectImageUrls(image, state.images);
      if (image && typeof image === "object" && !Array.isArray(image)) {
        collectImageUrls(image.image_url ?? image.imageUrl ?? image.src, state.images);
      }
    }
    if (imageNode) collectImageUrls(value.image_url ?? value.imageUrl ?? value.src, state.images);
    // Stay inside the matched product's rich description. Arbitrary object
    // traversal can pick up review/recommendation pictures or video posters.
    for (const key of ["content", "contents", "children", "nodes", "blocks", "items", "sub", "paragraphs", "sections", "body", "description"]) {
      collectDescriptionImages(value[key], state, depth + 1);
    }
  }

  function collectImageUrls(value, result, depth = 0) {
    if (result.length >= 120 || depth > 4 || !value) return;
    if (typeof value === "string") {
      const url = absoluteTikTokImageUrl(value);
      if (url) result.push(url);
      return;
    }
    if (Array.isArray(value)) {
      for (const entry of value.slice(0, 120)) collectImageUrls(entry, result, depth + 1);
      return;
    }
    if (typeof value === "object") {
      // url_list contains interchangeable, fully-qualified CDN delivery URLs.
      // A sibling url/uri may only be a TOS storage key ("tos-...") and must
      // never be resolved relative to the current product page.
      const deliveryUrl = firstHttpsImage(value.url_list || value.urlList || value.urls);
      if (deliveryUrl) {
        result.push(deliveryUrl);
        return;
      }
      collectImageUrls(value.url || value.uri, result, depth + 1);
    }
  }

  function absoluteTikTokImageUrl(value) {
    const raw = common.cleanText(value, 8_000);
    if (!raw) return "";
    const candidate = raw.startsWith("//") ? `https:${raw}` : raw;
    if (!/^https:\/\//i.test(candidate)) return "";
    return common.absoluteUrl(candidate);
  }

  function firstHttpsImage(value) {
    const result = [];
    collectImageUrls(value, result);
    return result[0] || null;
  }

  function firstAttributeImage(sku) {
    const attributes = sku.sku_sale_props || sku.property_pairs || sku.propertyPairs || sku.sales_attributes || sku.salesAttributes || sku.sku_sales_attrs
      || sku.skuSalesAttrs || sku.attributes || sku.properties;
    if (!Array.isArray(attributes)) return null;
    for (const attribute of attributes) {
      const image = firstHttpsImage(attribute?.sku_img || attribute?.skuImg || attribute?.image);
      if (image) return image;
    }
    return null;
  }

  function propertyDefinitionImage(sku, definitions) {
    const pairs = sku.sku_sale_props || sku.property_pairs || sku.propertyPairs;
    if (!Array.isArray(pairs)) {
      const ids = common.cleanText(sku.sale_prop_value_ids, 2_000).split("_");
      for (const definition of definitions) {
        const option = definition.values.find(value => ids.includes(value.id));
        if (option?.image) return option.image;
      }
      return null;
    }
    for (const pair of pairs) {
      const propertyId = cleanIdentifier(pair?.sku_property_id ?? pair?.skuPropertyId ?? pair?.property_id ?? pair?.prop_id);
      const valueId = cleanIdentifier(pair?.sku_property_value_id ?? pair?.skuPropertyValueId ?? pair?.property_value_id ?? pair?.prop_value_id);
      const definition = definitions.find(value => !propertyId || value.id === propertyId);
      const option = definition?.values.find(value => value.id === valueId);
      if (option?.image) return option.image;
    }
    return null;
  }

  function explicitlyNotDiscounted(sku, promotionPrice) {
    const price = sku.price_info || sku.priceInfo || sku.price || {};
    if (sku.is_discounted === false || sku.isDiscounted === false
      || price.is_discounted === false || price.isDiscounted === false) return true;
    const rates = [sku.discount_rate, sku.discountRate, price.discount_rate, price.discountRate];
    if (rates.some(value => value !== null && value !== undefined && Number(value) === 0)) return true;
    if (promotionPrice && Object.prototype.hasOwnProperty.call(promotionPrice, "origin_price_decimal")) {
      return promotionPrice.origin_price_decimal === null || promotionPrice.origin_price_decimal === ""
        || Number(promotionPrice.origin_price_decimal) === 0;
    }
    if (promotionPrice && Object.prototype.hasOwnProperty.call(promotionPrice, "originPriceDecimal")) {
      return promotionPrice.originPriceDecimal === null || promotionPrice.originPriceDecimal === ""
        || Number(promotionPrice.originPriceDecimal) === 0;
    }
    for (const key of ["original_price", "originalPrice", "origin_price", "originPrice"]) {
      if (promotionPrice && Object.prototype.hasOwnProperty.call(promotionPrice, key)) {
        return promotionPrice[key] === null || promotionPrice[key] === "" || Number(promotionPrice[key]) === 0;
      }
    }
    const originalKeys = ["original_price", "originalPrice", "origin_price", "originPrice", "list_price", "listPrice"];
    return originalKeys.some(key => Object.prototype.hasOwnProperty.call(price, key)
      && (price[key] === null || price[key] === "" || Number(price[key]) === 0));
  }

  function categoryText(product, context) {
    // Only inspect category fields belonging to the matched product envelope;
    // never walk recommendation categories elsewhere in the page response.
    const sources = [product, product?.category_info, product?.categoryInfo,
      context?.category_model, context?.categoryModel, context?.category_info, context?.categoryInfo, context];
    for (const source of sources) {
      if (!source || typeof source !== "object" || Array.isArray(source)) continue;
      const fields = [source.categories, source.category_path, source.categoryPath,
        source.category_list, source.categoryList, source.category, source.category_info, source.categoryInfo,
        source.category_name, source.categoryName, source.recommended_categories, source.recommendedCategories];
      // category_model itself may be a single category descriptor.
      if (source === context?.category_model || source === context?.categoryModel) fields.push(source);
      for (const value of fields) {
        const text = categoryValueText(value);
        if (text) return text;
      }
    }
    return "";
  }

  function categoryValueText(value) {
    const values = Array.isArray(value) ? value.slice(0, 12) : [value];
    return values.map(entry => {
      const label = typeof entry === "string" ? entry
        : entry?.category_name || entry?.categoryName || entry?.name;
      return typeof label === "string" ? common.cleanText(label, 255) : "";
    }).filter(Boolean).join(" > ");
  }

  function productAttributes(product, context) {
    const result = {};
    for (const owner of [product, context]) {
      if (!owner || typeof owner !== "object") continue;
      const sources = [owner.product_properties, owner.productProperties,
        owner.product_attributes, owner.productAttributes, owner.specifications, owner.attributes];
      for (const source of sources) {
        if (!source || typeof source !== "object") continue;
        const entries = Array.isArray(source) ? source.slice(0, 50)
          : Object.entries(source).slice(0, 50).map(([key, value]) => ({ key, value }));
        for (const entry of entries) {
          const key = common.cleanText(entry?.property_name || entry?.propertyName || entry?.name
            || entry?.attribute_name || entry?.attributeName || entry?.key, 100);
          const rawValue = entry?.property_value ?? entry?.propertyValue ?? entry?.property_values
            ?? entry?.propertyValues ?? entry?.value_name ?? entry?.valueName ?? entry?.attribute_value
            ?? entry?.attributeValue ?? entry?.values ?? entry?.value;
          const value = attributeValueText(rawValue);
          if (!key || !value || ["__proto__", "constructor", "prototype"].includes(key)
            || Object.prototype.hasOwnProperty.call(result, key) || Object.keys(result).length >= 50) continue;
          result[key] = value;
        }
      }
    }
    return result;
  }

  function attributeValueText(raw) {
    const values = Array.isArray(raw) ? raw.slice(0, 50) : [raw];
    return common.cleanText(values.map(value => {
      const label = value && typeof value === "object"
        ? value.property_value_name ?? value.propertyValueName ?? value.value_name ?? value.valueName
          ?? value.name ?? value.value
        : value;
      return ["string", "number", "boolean"].includes(typeof label) ? String(label) : "";
    }).filter(Boolean).join(", "), 500);
  }

  function promotionPriceFragments(root, productId) {
    const result = [];
    const seenModels = new Set();
    const stack = [{ value: root, depth: 0, productScope: null }];
    let inspected = 0;
    while (stack.length && inspected < 35_000 && result.length < 40) {
      const { value, depth, productScope } = stack.pop();
      inspected += 1;
      if (!value || typeof value !== "object" || depth > 16) continue;
      if (Array.isArray(value)) {
        for (let index = Math.min(value.length, 700) - 1; index >= 0; index -= 1) {
          const child = value[index];
          if (child && typeof child === "object") stack.push({ value: child, depth: depth + 1, productScope });
        }
        continue;
      }

      const scope = explicitProductId(value) || productScope;
      const priceModel = promotionPriceModel(value);
      if (priceModel && !seenModels.has(priceModel)) {
        seenModels.add(priceModel);
        const entries = priceEntries(priceModel);
        if (entries?.size && (!scope || scope === productId)) {
          result.push({ model: priceModel, entries, productScope: scope });
        }
      }
      for (const child of Object.values(value).slice(0, 700).reverse()) {
        if (child && typeof child === "object") stack.push({ value: child, depth: depth + 1, productScope: scope });
      }
    }
    return result;
  }

  function explicitProductId(value) {
    const direct = value?.product_id ?? value?.productId;
    if (validProductId(String(direct ?? ""))) return String(direct);
    const nested = value?.product_model || value?.productModel;
    const nestedId = nested?.product_id ?? nested?.productId;
    return validProductId(String(nestedId ?? "")) ? String(nestedId) : null;
  }

  function promotionPriceModel(value) {
    const promotion = value?.promotion_model || value?.promotionModel;
    const nested = promotion?.promotion_product_price || promotion?.promotionProductPrice;
    if (nested && typeof nested === "object" && !Array.isArray(nested)) return nested;
    const direct = value?.promotion_product_price || value?.promotionProductPrice;
    if (direct && typeof direct === "object" && !Array.isArray(direct)) return direct;
    if ((value?.skus_price || value?.skusPrice) && typeof value === "object" && !Array.isArray(value)) return value;
    return null;
  }

  function priceEntries(priceModel) {
    const raw = priceModel?.skus_price || priceModel?.skusPrice;
    if (!raw || typeof raw !== "object") return null;
    const source = Array.isArray(raw) ? raw.slice(0, MAX_SKUS + 1) : Object.entries(raw).slice(0, MAX_SKUS + 1);
    if (source.length === 0 || source.length > MAX_SKUS) return null;
    const entries = new Map();
    for (const item of source) {
      const keyed = !Array.isArray(raw);
      const key = keyed ? cleanIdentifier(item[0]) : null;
      const entry = keyed ? item[1] : item;
      if (!entry || typeof entry !== "object" || Array.isArray(entry)) return null;
      const embeddedId = cleanIdentifier(entry.sku_id ?? entry.skuId ?? entry.id);
      if (key && embeddedId && key !== embeddedId) return null;
      const skuId = key || embeddedId;
      if (!skuId || entries.has(skuId) || !hasPriceEvidence(entry)) return null;
      entries.set(skuId, entry);
    }
    return entries;
  }

  function hasPriceEvidence(entry) {
    const saleKeys = ["sale_price_decimal", "salePriceDecimal", "sale_price", "salePrice", "price"];
    const originalKeys = ["origin_price_decimal", "originPriceDecimal", "original_price", "originalPrice"];
    return saleKeys.some(key => Object.prototype.hasOwnProperty.call(entry, key) && exactMoney(entry[key]) !== null)
      || originalKeys.some(key => Object.prototype.hasOwnProperty.call(entry, key)
        && (entry[key] === null || entry[key] === "" || exactMoney(entry[key]) !== null));
  }

  function productWithPromotionPrices(match, fragments, productId, baseMetadata) {
    const context = match.context && typeof match.context === "object" && !Array.isArray(match.context)
      ? match.context
      : {};
    const envelope = { ...context, product_model: match.product };
    const rawSkus = productSkuArray(match.product) || [];
    const skuIds = new Set(rawSkus.map(sku => cleanIdentifier(sku?.sku_id ?? sku?.skuId ?? sku?.id)).filter(Boolean));
    const skuById = new Map(rawSkus.map(sku => [cleanIdentifier(sku?.sku_id ?? sku?.skuId ?? sku?.id), sku])
      .filter(([skuId]) => skuId));
    if (skuIds.size === 0) return envelope;

    const compatible = fragments.filter(fragment => (!fragment.productScope || fragment.productScope === productId)
      && [...fragment.entries.keys()].every(skuId => skuIds.has(skuId)))
      .sort((left, right) => right.sourceRank - left.sourceRank || right.freshness - left.freshness);
    const mergedEntries = new Map();
    for (const fragment of compatible) {
      for (const [skuId, entry] of fragment.entries) {
        // The newest complete entry for an SKU is authoritative. Do not fill
        // omitted fields from an older response, which could revive stale prices.
        if (mergedEntries.has(skuId)) continue;
        const value = promotionEntryForBase(entry, fragment, baseMetadata, skuById.get(skuId));
        if (value) mergedEntries.set(skuId, value);
      }
    }
    if (mergedEntries.size === 0) return envelope;

    const existingPromotion = context.promotion_model || context.promotionModel;
    const existingPriceModel = existingPromotion?.promotion_product_price || existingPromotion?.promotionProductPrice;
    const newestPriceModel = compatible[0]?.model;
    envelope.promotion_model = {
      ...(existingPromotion && typeof existingPromotion === "object" ? existingPromotion : {}),
      promotion_product_price: {
        ...(existingPriceModel && typeof existingPriceModel === "object" ? existingPriceModel : {}),
        ...(newestPriceModel && typeof newestPriceModel === "object" ? newestPriceModel : {}),
        skus_price: Object.fromEntries(mergedEntries)
      }
    };
    return envelope;
  }

  function promotionEntryForBase(entry, fragment, baseMetadata, sku) {
    if (compareFreshness(fragment, baseMetadata) >= 0) return entry;
    const value = { ...entry };
    if (skuPrice(sku || {}, false, null) !== null) {
      for (const key of ["sale_price_decimal", "salePriceDecimal", "sale_price", "salePrice", "price"]) delete value[key];
    }
    if (skuPrice(sku || {}, true, null) !== null) {
      for (const key of [
        "origin_price_decimal", "originPriceDecimal", "original_price", "originalPrice", "origin_price", "originPrice"
      ]) delete value[key];
    }
    return hasPriceEvidence(value) ? value : null;
  }

  function compareFreshness(left, right) {
    const rank = (left?.sourceRank || 0) - (right?.sourceRank || 0);
    return rank || (left?.freshness || 0) - (right?.freshness || 0);
  }

  function selectCandidate(candidates) {
    if (!Array.isArray(candidates) || candidates.length === 0) return null;
    candidates.sort((left, right) => compareCandidates(right, left));
    const selected = candidates[0].value;
    const result = { ...selected, attributes: { ...selected.attributes } };
    for (const { value: candidate } of candidates.slice(1)) {
      if (candidate.productId !== selected.productId || !sameSkuIdentity(selected, candidate)
        || (selected.shopId && candidate.shopId && selected.shopId !== candidate.shopId)) continue;
      // Detail responses may omit static metadata that was in bootstrap. Fill
      // only those fields; keep SKU dimensions, prices and stock as one snapshot.
      for (const key of ["categoryName", "brandName"]) {
        if (!result[key] && candidate[key]) result[key] = candidate[key];
      }
      if (!result.description && !result.detailImages?.length) {
        result.description = candidate.description;
        result.detailImages = candidate.detailImages;
      }
      for (const [key, value] of Object.entries(candidate.attributes || {})) {
        if (!Object.prototype.hasOwnProperty.call(result.attributes, key)
          && Object.keys(result.attributes).length < 50) result.attributes[key] = value;
      }
    }
    return result;
  }

  function compareCandidates(left, right) {
    const sameSkus = sameSkuIdentity(left.value, right.value);
    if (sameSkus && left.sourceRank !== right.sourceRank) return left.sourceRank - right.sourceRank;
    if (sameSkus && left.freshness !== right.freshness) return left.freshness - right.freshness;
    const leftComplete = left.value.skuCompleteness?.complete === true ? 1 : 0;
    const rightComplete = right.value.skuCompleteness?.complete === true ? 1 : 0;
    if (leftComplete !== rightComplete) return leftComplete - rightComplete;
    const leftCaptured = left.value.skuCompleteness?.capturedCount || 0;
    const rightCaptured = right.value.skuCompleteness?.capturedCount || 0;
    if (!leftComplete && leftCaptured !== rightCaptured) return leftCaptured - rightCaptured;
    // For equivalent product snapshots, current-page response order is more
    // authoritative than optional title/image richness: stock and prices move.
    if (left.sourceRank !== right.sourceRank) return left.sourceRank - right.sourceRank;
    if (left.freshness !== right.freshness) return left.freshness - right.freshness;
    return candidateFieldScore(left.value) - candidateFieldScore(right.value);
  }

  function sameSkuIdentity(left, right) {
    const leftIds = (left?.skus || []).map(sku => sku.externalSkuId).filter(Boolean).sort();
    const rightIds = (right?.skus || []).map(sku => sku.externalSkuId).filter(Boolean).sort();
    return leftIds.length > 0 && leftIds.length === rightIds.length
      && leftIds.every((value, index) => value === rightIds[index]);
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
      + Math.min(candidate?.images?.length || 0, 30)
      + Math.min(Object.keys(candidate?.attributes || {}).length, 50);
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

  function nonNegativeNumber(value) {
    if (value === null || value === undefined || value === "") return null;
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 ? number : null;
  }

  function boundedNumber(value, minimum, maximum) {
    const number = nonNegativeNumber(value);
    return number !== null && number >= minimum && number <= maximum ? number : null;
  }

  function validProductId(value) {
    return /^\d{6,32}$/.test(value || "");
  }
})();
