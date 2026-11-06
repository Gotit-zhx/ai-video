import { test } from "node:test";
import assert from "node:assert/strict";
import {
  summarizeHistory, sampleOrder, sampleCompletedAt, compareRequestedMonth,
  auditRequestedBatch, octoberOrders, septemberOrders,
} from "./audit-tiktok-seller-history.mjs";

const seconds = value => Date.parse(value) / 1000;
test("uses the completion event, not the later update or earlier payment", () => {
  const result = summarizeHistory(sampleOrder, [
    { description: "订单完成", timestamp: seconds(sampleCompletedAt) },
    { description: "订单更新", timestamp: seconds("2026-10-14T02:28:29+08:00") },
    { description: "结算付款", timestamp: seconds("2026-09-30T12:11:52+08:00") },
  ], sampleCompletedAt);
  assert.equal(result.matchesExpected, true);
  assert.deepEqual(result.completionCandidates, [seconds(sampleCompletedAt)]);
});
test("no inferred date when a completion event is absent or conflicting", () => {
  assert.equal(summarizeHistory(sampleOrder, [
    { description: "配送完成", timestamp: seconds(sampleCompletedAt) },
  ]).status, "NO_RECOGNIZED_COMPLETION_EVENT");
  const conflict = summarizeHistory(sampleOrder, [
    { description: "Order completed", timestamp: seconds(sampleCompletedAt) },
    { description: "Order completed", timestamp: seconds("2026-10-05T02:28:29+08:00") },
  ], sampleCompletedAt);
  assert.equal(conflict.status, "CONFLICTING_COMPLETION_EVENTS");
  assert.equal(conflict.matchesExpected, false);
});
test("preserves long IDs, removes unneeded sensitive fields, rejects invalid timestamps", () => {
  const result = summarizeHistory(sampleOrder, [
    { description: "buyer@example.com updated the address", detail: "PRIVATE",
      timestamp: seconds(sampleCompletedAt) },
  ]);
  assert.equal(result.orderId, sampleOrder);
  assert.doesNotMatch(JSON.stringify(result), /buyer@example|PRIVATE/);
  assert.throws(() => summarizeHistory(Number(sampleOrder), []));
  assert.throws(() => summarizeHistory(sampleOrder, [{ description: "订单完成", timestamp: null }]));
});
test("keeps all 27 October orders and 15 unique September orders in scope", () => {
  assert.equal(octoberOrders.length, 27);
  assert.equal(septemberOrders.length, 15);
  assert.equal(new Set([...septemberOrders, ...octoberOrders]).size, 42);
});
test("month attribution follows the shop timezone at midnight, with no missing-date fallback", () => {
  const result = compareRequestedMonth(summarizeHistory(sampleOrder, [
    { description: "订单完成", timestamp: seconds("2026-09-30T16:01:00Z") },
  ]));
  assert.equal(result.completedMonth, "2026-10");
  assert.equal(result.matchesRequestedMonth, true);
  const absent = compareRequestedMonth(summarizeHistory(sampleOrder, []));
  assert.equal(absent.completedMonth, null);
  assert.equal(absent.matchesRequestedMonth, null);
});
test("batch stops before other orders when the reference differs; preserves partial results", async () => {
  let requests = 0;
  await assert.rejects(auditRequestedBatch({
    evaluate: async () => { requests++; return [{ description: "订单完成",
      timestamp: seconds("2026-09-30T12:11:52+08:00") }]; },
  }, octoberOrders.slice(0, 5)), /Reference completion/);
  assert.equal(requests, 1);
  const saved = [];
  requests = 0;
  await assert.rejects(auditRequestedBatch({
    evaluate: async () => {
      requests++;
      if (requests === 3) throw new Error("Session expired");
      return [{ description: "订单完成", timestamp: seconds(sampleCompletedAt) }];
    },
  }, octoberOrders.slice(0, 5), async result => saved.push(result)), /Session expired/);
  assert.equal(saved.length, 1);
  assert.equal(saved[0].orderId, octoberOrders[0]);
  assert.equal(saved[0].matchesRequestedMonth, true);
  await assert.rejects(auditRequestedBatch({}, Array(6).fill(sampleOrder)), /one to five/);
});
