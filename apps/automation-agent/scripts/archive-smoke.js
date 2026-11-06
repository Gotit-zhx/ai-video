const crypto = require("node:crypto");
const os = require("node:os");
const path = require("node:path");
const { compressTaskOutput } = require("../src/core/artifact-archiver");

async function main() {
  const source = path.resolve(__dirname, "../../../frontend/vendor");
  const destination = path.join(
    os.tmpdir(),
    `heyi-archive-smoke-${crypto.randomUUID()}.zip`
  );
  const result = await compressTaskOutput(source, destination);
  console.log(JSON.stringify({
    success: true,
    size: result.size,
    destination: result.path
  }));
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
