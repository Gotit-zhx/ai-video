import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
export function assertCleanLint(result) {
  if (
    !result ||
    !Array.isArray(result.issues) ||
    !Array.isArray(result.skippedFiles) ||
    result.partial !== false ||
    !result.summary ||
    typeof result.summary !== "object" ||
    result.summary.total !== 0 ||
    result.summary.skipped !== 0 ||
    result.issues.length ||
    result.skippedFiles.length ||
    Object.values(result.summary).some((n) => n !== 0)
  ) {
    throw new Error(
      "AntD gate requires a complete scan with zero issues and zero skipped files",
    );
  }
}
if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const root = fileURLToPath(new URL("..", import.meta.url));
  const result = spawnSync(
    path.join(root, "node_modules/.bin/antd"),
    ["lint", "./src", "--format", "json"],
    {
      cwd: root,
      encoding: "utf8",
      env: { ...process.env, ANTD_NO_AUTO_REPORT: "1" },
    },
  );
  if (result.status !== 0)
    throw new Error(
      "AntD CLI failed: " + (result.stderr || result.error || result.status),
    );
  assertCleanLint(JSON.parse(result.stdout));
  console.log("AntD 6.7.0 complete scan: zero issues, zero skipped files");
}
