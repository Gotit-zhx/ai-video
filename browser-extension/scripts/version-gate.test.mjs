import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const stored = new Map();
globalThis.chrome = {
  runtime: { getManifest: () => ({ version: "0.2.6" }) },
  storage: { local: {
    get: async key => ({ [key]: stored.get(key) }),
    set: async values => Object.entries(values).forEach(([key, value]) => stored.set(key, value)),
    remove: async key => stored.delete(key),
    setAccessLevel: async () => {}
  } }
};
const { writeAuth, readAuth } = await import("../src/background/storage.js");
const { createCapture, refreshCurrentUser } = await import("../src/background/api.js");
await writeAuth({ baseUrl: "https://hoyee.org.cn", accessToken: "existing-login-retained-after-upgrade",
  expiresAt: Date.now() + 3600000, permissions: ["product:collect"] });
globalThis.fetch = async (url, options) => {
  assert.equal(new URL(url).origin, "https://hoyee.org.cn");
  assert.equal(options.headers.get("X-Heyi-Extension-Version"), "0.2.6");
  return new Response(JSON.stringify({ code: "EXTENSION_UPDATE_REQUIRED", message: "请更新插件" }), {status:426});
};
for (const action of [refreshCurrentUser, () => createCapture({ title: "Test" }, "test")]) {
  await assert.rejects(action, error => error.status === 426 && error.code === "EXTENSION_UPDATE_REQUIRED");
  assert.ok(await readAuth(), "an upgrade requirement must not discard a valid login");
}

// Run popup handlers with a lightweight DOM and verify there is no URL input,
// both login paths use the official origin, and update errors replace the form.
const html = readFileSync(new URL("../src/popup/popup.html", import.meta.url), "utf8");
assert.doesNotMatch(html, /id="platform-url"/);
assert.match(html, /hoyee\.org\.cn\/downloads\/hoyee-assistant-latest\.zip/);
const nodes = new Map();
function node() {
  return { value: "", textContent: "", hidden: false, disabled: false, handlers: {},
    classList: { toggle() {}, add() {}, remove() {} },
    addEventListener(type, handler) { this.handlers[type] = handler; },
    replaceChildren() {}, appendChild() {} };
}
const sent = [];
const state = { authenticated:true, user:{displayName:"Test"}, permissions:["product:collect"] };
const popup = vm.createContext({
  DEFAULT_PLATFORM_URL:"https://hoyee.org.cn",
  MESSAGE_TYPES:{GET_STATE:"state",LOGIN:"login",SAVE_PLATFORM:"save",OPEN_PLATFORM:"open",LOGOUT:"logout"},
  originPattern: url => url + "/*", Date, Intl, navigator:{platform:"Test"},
  document:{ querySelector: id => { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); }, createElement:node },
  chrome: { permissions: { contains:async()=>true }, runtime:{sendMessage:async message => {
    sent.push(message); return {ok:true,data:state};
  }}}
});
const popupSource = readFileSync(new URL("../src/popup/popup.js", import.meta.url), "utf8")
  .replace(/^import[\s\S]*?from\s+["'][^"']+["'];\n/gm, "");
vm.runInContext(popupSource, popup);
await new Promise(setImmediate);
nodes.get("#login-account").value="test";
nodes.get("#login-password").value="not-a-real-password";
await nodes.get("#pairing-form").handlers.submit({preventDefault(){}});
await nodes.get("#open-platform").handlers.click();
assert.ok(sent.filter(message=>["login","save","open"].includes(message.type))
  .every(message=>message.baseUrl==="https://hoyee.org.cn"));
assert.equal(nodes.get("#login-password").value, "");
popup.chrome.runtime.sendMessage=async()=>({ok:false,error:{status:426,code:"EXTENSION_UPDATE_REQUIRED",message:"请更新插件"}});
await popup.loadState(true);
assert.equal(nodes.get("#update-required").hidden,false);
assert.equal(nodes.get("#pairing-form").hidden,true);
assert.equal(nodes.get("#pair-button").disabled,true);
assert.equal(nodes.get("#platform-state").textContent,"需要更新");

// A rejected capture switches to an update prompt and a second click cannot
// make another extraction/submission attempt.
const inject = readFileSync(new URL("../src/content/inject.js", import.meta.url), "utf8");
const start = inject.indexOf("  async function runCapture(control, root)");
const end = inject.indexOf("\n\n  function isIdempotentDuplicateConflict", start);
let calls=0, prompt="";
const page = vm.createContext({
  upgradeRequired:"",captureState:new Map(),authenticated:true,MESSAGE_CAPTURE:"capture",
  adapter:{blockedReason:()=>"",extract:async()=>({title:"Test",videos:[]})},
  ensureAuthenticated:async()=>true,setUrlState:()=>{},isIdempotentDuplicateConflict:()=>false,
  HeyiVideoSources:{deduplicate:()=>[]},
  document:{body:{},querySelector:()=>null,querySelectorAll:()=>[]},
  chrome:{runtime:{sendMessage:async()=>{calls++;return {ok:false,error:{status:426,kind:"UPDATE",message:"请更新插件"}};}}},
  showUpgradeRequired:message=>{prompt=message;page.upgradeRequired=message;}
});
vm.runInContext(inject.slice(start,end),page);
await page.runCapture({productUrl:"https://shop.tiktok.com/view/product/123456789",pageType:"DETAIL"},{});
await page.runCapture({productUrl:"https://shop.tiktok.com/view/product/123456789",pageType:"DETAIL"},{});
assert.equal(prompt,"请更新插件");
assert.equal(calls,1);
console.log("Version gate: live build headers, preserved login, official-only popup, visible upgrade and retry blocking passed.");
