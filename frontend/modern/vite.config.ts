import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig(({ mode }) => ({
  base: "/assets/finance-modern/",
  plugins: [react()],
  define:
    mode === "test"
      ? {}
      : { "process.env.NODE_ENV": JSON.stringify("production") },
  build: {
    outDir: "../assets/finance-modern",
    emptyOutDir: false,
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
