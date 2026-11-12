import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { parseSync } from "vite";

function scopedLocaleCatalog() {
  const sourceRoot = path.resolve(import.meta.dirname, "src");
  const catalogFile = path.resolve(import.meta.dirname, "../i18n/catalog.json");
  return {
    name: "scoped-ui-translations",
    enforce: "pre" as const,
    apply: "build" as const,
    load(id: string) {
      if (id.split("?")[0] !== catalogFile) return;
      const labels = new Set(["确认", "取消", "关闭", "保存", "刷新"]);
      function collect(directory: string) {
        for (const item of readdirSync(directory, { withFileTypes: true })) {
          const file = path.join(directory, item.name);
          if (item.isDirectory()) collect(file);
          else if (/\.(?:ts|tsx)$/.test(item.name)) {
            const source = readFileSync(file, "utf8");
            const syntax = parseSync(file, source);
            function visit(value: unknown) {
              if (Array.isArray(value)) { value.forEach(visit); return; }
              if (!value || typeof value !== "object") return;
              const node = value as Record<string, unknown>;
              if (node.type === "Literal" && typeof node.value === "string" && /[\u3400-\u9fff]/u.test(node.value)) labels.add(node.value);
              Object.values(node).forEach(visit);
            }
            visit(syntax.program);
          }
        }
      }
      collect(sourceRoot);
      const catalog = JSON.parse(readFileSync(catalogFile, "utf8")) as Record<string, string[]>;
      return JSON.stringify(Object.fromEntries([...labels].filter(key => catalog[key]).map(key => [key, catalog[key]])));
    }
  };
}

export default defineConfig(({ mode }) => ({
  base: "/assets/finance-modern/",
  plugins: [react(), scopedLocaleCatalog()],
  define:
    mode === "test"
      ? {}
      : { "process.env.NODE_ENV": JSON.stringify("production") },
  build: {
    outDir: "../../.dev/finance-modern-build",
    emptyOutDir: true,
    manifest: "manifest.json",
    cssCodeSplit: false,
    sourcemap: false,
    minify: "oxc",
    rollupOptions: {
      input: "src/app/build-entry.ts",
      preserveEntrySignatures: "strict",
      output: {
        assetFileNames: "assets/[name]-[hash][extname]",
        chunkFileNames: "assets/[name]-[hash].js",
        entryFileNames: "assets/[name]-[hash].js",
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    css: true,
    restoreMocks: true,
    clearMocks: true,
  },
}));
