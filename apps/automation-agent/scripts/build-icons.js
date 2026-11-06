const path = require("node:path");
const sharp = require("sharp");
const pngToIcoModule = require("png-to-ico");
const pngToIco = pngToIcoModule.default || pngToIcoModule;
const fs = require("node:fs/promises");

async function main() {
  const projectRoot = path.resolve(__dirname, "..");
  const source = path.join(projectRoot, "src", "renderer", "assets", "hoyee-logo-color.png");
  const symbol = path.join(projectRoot, "src", "renderer", "assets", "hoyee-symbol.png");
  const png = path.join(projectRoot, "build", "icon.png");
  const ico = path.join(projectRoot, "build", "icon.ico");
  const sizes = [16, 24, 32, 48, 64, 128, 256];

  const mark = await sharp(source)
    .extract({ left: 180, top: 130, width: 520, height: 390 })
    .resize(768, 768, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();
  await fs.writeFile(symbol, mark);
  await sharp(mark)
    .resize(1024, 1024, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toFile(png);
  const frames = await Promise.all(sizes.map((size) =>
    sharp(mark).resize(size, size, { fit: "contain" }).png().toBuffer()
  ));
  await fs.writeFile(ico, await pngToIco(frames));
  console.log(`Generated ${png}`);
  console.log(`Generated ${ico}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
