import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../src/content/inject.js", import.meta.url), "utf8");

assert.doesNotMatch(
  source,
  /shadow\.addEventListener\(\s*["']click["'][\s\S]{0,400?},\s*true\s*\)/,
  "A capture-phase ShadowRoot click listener stops the event before the button handler runs"
);
assert.match(
  source,
  /button\.addEventListener\(\s*["']click["'][\s\S]{0,400}?runCapture\(control,\s*container\)/,
  "The capture button must invoke runCapture from its own click handler"
);

assert.match(
  source,
  /if \(isIdempotentDuplicateConflict\(details\)\)/,
  "Capture conflicts must use the explicit idempotency classifier"
);
const helperStart = source.indexOf("  function isIdempotentDuplicateConflict(details)");
const helperEnd = source.indexOf("\n\n  async function readAuthState", helperStart);
assert.ok(helperStart >= 0 && helperEnd > helperStart, "The idempotency classifier must remain testable");
const context = vm.createContext({});
vm.runInContext(`${source.slice(helperStart, helperEnd)}\nthis.classify = isIdempotentDuplicateConflict;`, context);

assert.equal(context.classify({ status: 409, code: "HTTP_409" }), false);
assert.equal(context.classify({ status: 409, message: "该商品已提交，请勿重复采集" }), false);
assert.equal(context.classify({ status: 409, code: "IDEMPOTENT_REPLAY" }), true);
assert.equal(context.classify({ status: 409, code: "idempotency_duplicate" }), true);
assert.equal(context.classify({ status: 400, code: "IDEMPOTENT_REPLAY" }), false);

console.log("Inject click wiring tests passed");
