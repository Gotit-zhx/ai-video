import { test } from "node:test";
import assert from "node:assert/strict";
import { assertCleanLint } from "./antd-gate.mjs";
const clean = () => ({
  issues: [],
  skippedFiles: [],
  partial: false,
  summary: {
    total: 0,
    deprecated: 0,
    a11y: 0,
    usage: 0,
    performance: 0,
    skipped: 0,
  },
});
test("accepts complete zero-issue CLI result", () =>
  assert.doesNotThrow(() => assertCleanLint(clean())));
for (const [name, change] of Object.entries({
  issue: (r) => r.issues.push({ severity: "warning" }),
  skipped: (r) => r.skippedFiles.push("src/x.tsx"),
  partial: (r) => (r.partial = true),
  summary: (r) => (r.summary.total = 1),
  missing: (r) => delete r.summary,
  unknown: (r) => (r.summary.futureRule = 2),
})) {
  test("rejects exit-zero " + name + " result", () => {
    const r = clean();
    change(r);
    assert.throws(() => assertCleanLint(r));
  });
}
test("rejects malformed output", () =>
  assert.throws(() => assertCleanLint(null)));
