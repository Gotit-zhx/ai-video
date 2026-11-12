import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { gzipSync } from "node:zlib";
const root = new URL("../../assets/finance-modern/", import.meta.url);
const manifest = JSON.parse(
  await readFile(new URL("manifest.json", root), "utf8"),
);
const sizes = {};
for (const item of Object.values(manifest)) {
  if (!sizes[item.file]) {
    const raw = await readFile(new URL(item.file, root));
    sizes[item.file] = { raw: raw.length, gzip: gzipSync(raw).length };
  }
}
const seen = new Set();
function visit(key) {
  if (seen.has(key)) return;
  seen.add(key);
  for (const child of manifest[key]?.imports || []) visit(child);
}
visit("src/app/build-entry.ts");
const entryFiles = [...seen].map((k) => manifest[k].file),
  entryGzip = entryFiles.reduce((n, k) => n + sizes[k].gzip, 0);
const chart = Object.values(manifest).find(
  (i) => i.src === "src/shared/chart.tsx" || i.name === "chart",
);
assert(chart, "lazy chart chunk is present");
const result = {
  entryRequests: entryFiles.length,
  entryGzip,
  chart: sizes[chart.file],
  totalGzip: Object.values(sizes).reduce((n, s) => n + s.gzip, 0),
  chunks: sizes,
};
console.log(JSON.stringify(result, null, 2));
assert(
  entryFiles.length <= 10,
  "entry graph must stay within 10 module requests",
);
assert(entryGzip <= 430 * 1024, "entry graph must stay within 430 KiB gzip");
assert(
  sizes[chart.file].gzip <= 190 * 1024,
  "lazy line/bar chart chunk must stay within 190 KiB gzip",
);
assert(
  result.totalGzip <= 800 * 1024,
  "all modern assets must stay within 800 KiB gzip",
);
