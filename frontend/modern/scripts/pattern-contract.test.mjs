import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
test("portal identifier patterns compile under HTML Unicode sets and preserve allowed names", () => {
  const html = readFileSync(
    new URL("../../index.html", import.meta.url),
    "utf8",
  );
  const patterns = [...html.matchAll(/pattern="([^"]+)"/g)].map((m) => m[1]);
  for (const pattern of patterns)
    assert.doesNotThrow(() => new RegExp("^(?:" + pattern + ")$", "v"));
  const names = patterns.filter((p) => p.includes("A-Za-z0-9_"));
  assert.equal(names.length, 4);
  for (const pattern of names) {
    const r = new RegExp("^(?:" + pattern + ")$", "v");
    for (const v of ["fixture-user", "fixture_user", "A2"]) assert(r.test(v));
    for (const v of ["a", "a b", "x@x"]) assert(!r.test(v));
  }
});
