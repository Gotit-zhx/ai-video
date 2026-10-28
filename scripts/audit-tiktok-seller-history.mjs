/**
 * Read-only Seller Center audit. Import this from an ego-browser nodejs round,
 * then pass an agent-controlled, already authenticated order-detail Page.
 * Does not copy cookies, create exports, change orders, or write finance dates.
 */
import { createHash } from "node:crypto";

export const sampleOrder = "586217421074433110";
export const sampleCompletedAt = "2026-10-04T02:28:29+08:00";

export const septemberOrders = Object.freeze(`
585766520348574952 585766481305634280 585764977265706674 585764848011544310
585764757296089087 585764555994138355 585762432859801561 585761436475295624
585760635317815276 585759153529849771 585757874943591564 585756151120364841
585756204777834266 585755547295188131 585755480570627698
`.trim().split(/\s+/));
export const octoberOrders = Object.freeze(`
586208660231587825 586248667916502979 586321253913888042 586321099662722435
586251057227334831 586189886086219428 586248749252117860 586229882290996973
586167707968701523 586182066388174354 586259902499686279 586251991250536234
586255631761900988 586300596333020686 586208583904822634 586260107070178724
586325530678035580 586248933727962163 586321060062397609 586316466496374177
586319134020044042 586204257616430108 586323830825846056 586259679067866925
586250977074906433 586319961778652556 586217421074433110
`.trim().split(/\s+/));

export function summarizeHistory(orderId, history, expectedTime = null) {
  if (typeof orderId !== "string" || !/^\d{16,22}$/.test(orderId))
    throw new Error("Order ID must remain a string");
  const completedLabels = new Set([
    "订单完成", "订单已完成", "已完成", "order completed", "order is completed",
    "order has been completed", "completed",
  ]);
  const events = history.map((event) => {
    const label = String(event.description ?? "").normalize("NFKC")
      .trim().replace(/[。.!！]$/, "").toLowerCase();
    const timestamp = Number(event.timestamp);
    if (!Number.isSafeInteger(timestamp) || timestamp < 1262304000 ||
        timestamp > Math.floor(Date.now() / 1000) + 300)
      throw new Error("Invalid Seller Center event timestamp; no date was inferred");
    // Preserve no buyer names, addresses, event details, media, or unknown labels.
    return {
      type: completedLabels.has(label) ? "ORDER_COMPLETED" : "OTHER",
      timestamp,
      utcTime: new Date(timestamp * 1000).toISOString(),
      labelSha256: createHash("sha256").update(label).digest("hex"),
    };
  });
  const candidates = [...new Set(events.filter(e => e.type === "ORDER_COMPLETED").map(e => e.timestamp))];
  return {
    orderId,
    source: "SELLER_CENTER_ORDER_HISTORY",
    endpoint: "/api/v1/fulfillment/order/history",
    eventCount: events.length,
    completionCandidates: candidates,
    expectedTime,
    matchesExpected: expectedTime === null ? null :
      candidates.length === 1 && candidates[0] === Date.parse(expectedTime) / 1000,
    status: candidates.length === 1 ? "CANDIDATE_FOUND" :
      candidates.length === 0 ? "NO_RECOGNIZED_COMPLETION_EVENT" : "CONFLICTING_COMPLETION_EVENTS",
    events,
  };
}

export function compareRequestedMonth(history, timezone = "Asia/Manila") {
  const expectedMonth = octoberOrders.includes(history.orderId) ? "2026-10" :
    septemberOrders.includes(history.orderId) ? "2026-09" : null;
  if (!expectedMonth) throw new Error("Order is outside the user's requested audit");
  const unique = [...new Set(history.completionCandidates)];
  let completedMonth = null;
  if (history.status === "CANDIDATE_FOUND" && unique.length === 1) {
    const parts = new Intl.DateTimeFormat("en", {
      timeZone: timezone, year: "numeric", month: "2-digit",
    }).formatToParts(new Date(unique[0] * 1000));
    completedMonth = parts.find(p => p.type === "year").value + "-" +
      parts.find(p => p.type === "month").value;
  }
  return {
    ...history, timezone, expectedMonth, completedMonth,
    matchesRequestedMonth: completedMonth === null ? null : completedMonth === expectedMonth,
  };
}

export async function auditDisplayedOrder(page, orderId = sampleOrder, expectedTime = sampleCompletedAt) {
  return readHistoryFromReferencePage(page, orderId, orderId, expectedTime);
}

async function readHistoryFromReferencePage(page, orderId, displayedOrderId, expectedTime = null) {
  for (const id of [orderId, displayedOrderId]) {
    if (typeof id !== "string" || !/^\d{16,22}$/.test(id))
      throw new Error("Order ID must remain a string");
  }
  const result = await page.evaluate(async ({ orderId, displayedOrderId }) => {
    const allowedHosts = new Set([
      "seller.tiktokshopglobalselling.com", "seller.tiktokglobalshop.com",
      "seller-ph.tiktok.com", "seller-my.tiktok.com", "seller-sg.tiktok.com",
    ]);
    if (!allowedHosts.has(location.hostname) || location.pathname !== "/order/detail")
      throw new Error("Open the authenticated Seller Center order-detail page first");
    if (new URLSearchParams(location.search).get("order_no") !== displayedOrderId)
      throw new Error("Displayed order differs from audit order; no request made");
    const referenceUrl = location.href;
    const chunks = window.webpackChunk_shop_subApp_order;
    if (!Array.isArray(chunks)) throw new Error("Seller Center order application is not loaded");
    let requireModule;
    // Use the official page's initialized read client, including its own session
    // and normal request parameters. No copied credentials or alternative auth.
    chunks.push([[`finance-read-audit-${Date.now()}`], {}, r => { requireModule = r; }]);
    const readHistory = requireModule?.("48574")?.ListSellerMainOrderHistory;
    if (typeof readHistory !== "function")
      throw new Error("Official history client changed; stop instead of guessing");
    const all = [];
    let expectedCount;
    for (let pageIndex = 0; pageIndex < 10; pageIndex++) {
      if (location.href !== referenceUrl) throw new Error("Seller Center page changed during audit");
      const reply = await readHistory({ main_order_id: orderId, offset: all.length, page_size: 50 });
      if (location.href !== referenceUrl) throw new Error("Seller Center page changed during audit");
      if (reply?.code !== 0)
        throw new Error(`Seller Center history failed, code=${String(reply?.code)}; no fallback date`);
      const data = reply.data;
      if (!Array.isArray(data?.order_history) || !Number.isSafeInteger(Number(data.total_count)))
        throw new Error("History response schema changed");
      const count = Number(data.total_count);
      if (count < 0 || count > 500 || (expectedCount !== undefined && count !== expectedCount))
        throw new Error("History changed during pagination or exceeded audit limit; rerun explicitly");
      expectedCount = count;
      if (!data.order_history.length && all.length < expectedCount)
        throw new Error("Incomplete history page; no completion inferred");
      all.push(...data.order_history.map(e => ({
        description: e.description, timestamp: e.timestamp,
      })));
      if (all.length === expectedCount) return all;
      if (all.length > expectedCount) throw new Error("History pagination count mismatch");
    }
    throw new Error("History pagination did not finish");
  }, { orderId, displayedOrderId });
  return summarizeHistory(orderId, result, expectedTime);
}

/**
 * Run at most five requested orders per browser round. Each round first verifies
 * the reference order against the user's screenshot before reading more orders.
 * onResult permits atomic progress snapshots without persisting private details.
 */
export async function auditRequestedBatch(page, orderIds, onResult = async () => {}) {
  const requested = new Set([...septemberOrders, ...octoberOrders]);
  if (!Array.isArray(orderIds) || orderIds.length < 1 || orderIds.length > 5 ||
      new Set(orderIds).size !== orderIds.length || orderIds.some(id => !requested.has(id)))
    throw new Error("Provide one to five distinct orders from the requested audit");
  const reference = await auditDisplayedOrder(page);
  if (!reference.matchesExpected)
    throw new Error("Reference completion does not match the screenshot; batch audit stopped");
  const results = [];
  for (const orderId of orderIds) {
    const result = compareRequestedMonth(orderId === sampleOrder ? reference :
      await readHistoryFromReferencePage(page, orderId, sampleOrder));
    await onResult(result);
    results.push(result);
  }
  return results;
}
