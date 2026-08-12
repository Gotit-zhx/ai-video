const path = require("node:path");
const sharp = require("sharp");
const pngToIcoModule = require("png-to-ico");
const pngToIco = pngToIcoModule.default || pngToIcoModule;
const fs = require("node:fs/promises");

async function main() {
  const projectRoot = path.resolve(__dirname, "..");
  const source = path.join(projectRoot, "build", "icon.svg");
  const png = path.join(projectRoot, "build", "icon.png");
  const ico = path.join(projectRoot, "build", "icon.ico");
  const sizes = [16, 24, 32, 48, 64, 128, 256];

  await sharp(source).resize(1024, 1024).png().toFile(png);
  const frames = await Promise.all(sizes.map((size) =>
    sharp(source).resize(size, size).png().toBuffer()
  ));
  await fs.writeFile(ico, await pngToIco(frames));
  console.log(`Generated ${png}`);
  console.log(`Generated ${ico}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
