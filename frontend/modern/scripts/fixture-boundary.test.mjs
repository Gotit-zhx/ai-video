import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createFixtureServer } from "../browser/fixture-server.cjs";
test("local fixture rejects unknown API and private source without forwarding", async () => {
  const server = await createFixtureServer();
  try {
    const unknown = await fetch(server.url + "/api/v1/finance/not-configured");
    assert.equal(unknown.status, 404);
    assert.equal(server.unhandled.length, 1);
    for (const path of [
      "/modern/src/app/FinanceRoot.tsx",
      "/.env",
      "/modern/package.json",
    ])
      assert.equal((await fetch(server.url + path)).status, 404);
    assert.equal(server.errors.length, 0);
  } finally {
    await server.close();
  }
});
test("local fixture serves the actual entry bytes without a rollout override", async () => {
  const server = await createFixtureServer();
  try {
    const actual = readFileSync(
      new URL("../../finance-entry.js", import.meta.url),
      "utf8",
    );
    for (const suffix of ["", "?v=published-entry"])
      assert.equal(
        await fetch(server.url + "/finance-entry.js" + suffix).then((r) =>
          r.text(),
        ),
        actual,
      );
  } finally {
    await server.close();
  }
});
