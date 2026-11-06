const { FeikuaClient } = require("../../apps/automation-agent/src/core/feikua-client");

async function main() {
  const client = new FeikuaClient();
  const stores = await client.listStores();
  const running = stores.filter(store =>
    Number(store.debugger_port) > 0
    && (["running", "started"].includes(String(store.status || "").toLowerCase())
      || Boolean(store.is_running || store.running))
  );
  if (!running.length) {
    console.log(JSON.stringify({ connected: true, runningStores: 0, observation: "SKIPPED" }));
    return;
  }
  const result = await client.executeBrowserAction({
    type: "OBSERVE",
    storeName: String(running[0].shop_id),
    includeScreenshot: false
  });
  console.log(JSON.stringify({
    connected: true,
    runningStores: running.length,
    observation: "OK",
    hasPage: Boolean(result.url),
    elementCount: result.elements?.length || 0,
    textLength: result.text?.length || 0
  }));
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
