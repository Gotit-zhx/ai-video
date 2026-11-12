import { test } from "node:test";
import assert from "node:assert/strict";
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
test("experience link override is confined to served fixture bytes", async () => {
  const server = await createFixtureServer();
  try {
    const read = () =>
      fetch(server.url + "/finance-entry.js").then((r) => r.text());
    assert.match(await read(), /enabled:false/);
    server.setExperience(true);
    assert.match(await read(), /enabled:true/);
    server.setExperience(false);
    assert.match(await read(), /enabled:false/);
  } finally {
    await server.close();
  }
});
