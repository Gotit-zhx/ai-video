// Design-time only. Requires sharp; generated assets are checked in so neither
// desktop package needs image tooling at install or build time.
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const {execFileSync} = require('node:child_process');
const sharp = require('sharp');

async function main() {
  const svg = await fs.readFile(path.join(__dirname, 'ai-boss.svg'), 'utf8');
  const master = await sharp(Buffer.from(svg)).png().toBuffer();
  await fs.writeFile(path.join(__dirname, 'ai-boss.png'), master);
  const rounded = Buffer.from(`<svg width="1024" height="1024" xmlns="http://www.w3.org/2000/svg">
    <rect x="64" y="64" width="896" height="896" rx="202" fill="white"/></svg>`);
  const app = await sharp(master).resize(896, 896)
    .extend({top: 64, bottom: 64, left: 64, right: 64, background: '#00000000'})
    .composite([{input: rounded, blend: 'dest-in'}]).png().toBuffer();
  await fs.writeFile(path.join(__dirname, 'ai-boss-app.png'), app);

  // Each ICO entry contains a real PNG at its own resolution. A single 256px
  // entry otherwise looks soft in Explorer, title bars and small taskbars.
  const sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256];
  const images = await Promise.all(sizes.map(size => sharp(app).resize(size, size).png().toBuffer()));
  const header = Buffer.alloc(6 + 16 * sizes.length);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(sizes.length, 4);
  let offset = header.length;
  images.forEach((image, index) => {
    const start = 6 + index * 16;
    header[start] = header[start + 1] = sizes[index] === 256 ? 0 : sizes[index];
    header.writeUInt16LE(1, start + 4);
    header.writeUInt16LE(32, start + 6);
    header.writeUInt32LE(image.length, start + 8);
    header.writeUInt32LE(offset, start + 12);
    offset += image.length;
  });
  await fs.writeFile(path.join(__dirname, 'ai-boss.ico'), Buffer.concat([header, ...images]));
  if (process.platform !== 'darwin') throw Error('Use macOS iconutil to regenerate the ICNS too');
  const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'ai-boss-icons-'));
  try {
    const iconset = path.join(temporary, 'AiBoss.iconset');
    await fs.mkdir(iconset);
    for (const size of [16, 32, 128, 256, 512]) {
      for (const scale of [1, 2]) {
        await sharp(app).resize(size * scale, size * scale).png().toFile(
          path.join(iconset, `icon_${size}x${size}${scale === 2 ? '@2x' : ''}.png`));
      }
    }
    execFileSync('iconutil', ['--convert', 'icns', '--output',
      path.join(__dirname, 'ai-boss.icns'), iconset]);
  } finally {
    await fs.rm(temporary, {recursive: true, force: true});
  }
}
main().catch(error => {console.error(error);process.exitCode = 1;});
