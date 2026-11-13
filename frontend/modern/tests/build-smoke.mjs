import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
const manifest = JSON.parse(
  await readFile(
    new URL("../../assets/finance-modern/manifest.json", import.meta.url),
    "utf8",
  ),
);
const entry = manifest["src/app/build-entry.ts"];
assert(entry?.isEntry, "build manifest must contain the mount entry");
const built = await import(
  new URL("../../assets/finance-modern/" + entry.file, import.meta.url)
);
assert.equal(
  typeof built.mountFinanceRoot,
  "function",
  "built ESM must retain callable mountFinanceRoot",
);
const css = [
  ...new Set(
    Object.values(manifest).flatMap((item) => [
      ...(item.css || []),
      ...(item.file?.endsWith(".css") ? [item.file] : []),
    ]),
  ),
];
assert.equal(css.length, 1, "one isolated CSS artifact");
console.log("Built ESM mount export and isolated CSS manifest verified");
