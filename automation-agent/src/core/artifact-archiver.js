const archiver = require("archiver");
const fs = require("node:fs");
const fsPromises = require("node:fs/promises");

async function compressTaskOutput(sourceDirectory, destination) {
  const source = await fsPromises.stat(sourceDirectory);
  if (!source.isDirectory()) {
    throw new Error("任务结果目录不存在");
  }
  await fsPromises.rm(destination, { force: true }).catch(() => {});
  await new Promise((resolve, reject) => {
    let settled = false;
    const finish = callback => value => {
      if (settled) return;
      settled = true;
      callback(value);
    };
    const accept = finish(resolve);
    const fail = finish(reject);
    const output = fs.createWriteStream(destination);
    const archive = archiver("zip", {
      zlib: { level: 9 }
    });
    output.on("close", accept);
    output.on("error", fail);
    archive.on("warning", error => {
      if (error.code !== "ENOENT") fail(error);
    });
    archive.on("error", fail);
    archive.pipe(output);
    archive.directory(sourceDirectory, false);
    archive.finalize().catch(fail);
  });
  const result = await fsPromises.stat(destination);
  if (!result.isFile() || result.size <= 22) {
    throw new Error("任务结果 ZIP 为空或生成不完整");
  }
  return {
    path: destination,
    size: result.size
  };
}

module.exports = { compressTaskOutput };
