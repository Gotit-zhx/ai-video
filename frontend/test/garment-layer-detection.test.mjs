import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
async function moduleAt(path) {
  const code = readFileSync(new URL(path, import.meta.url), 'utf8');
  return import(`data:text/javascript;base64,${Buffer.from(`${code}\n//# sourceURL=${path}`).toString('base64')}`);
}
const { segmentForeground, fitCoarseFromMasks, finalizeLayeredModel, validateMesh, rasterizeSilhouette, silhouetteIoU, maskBounds } = await moduleAt('../vendor/three/garment-coarse.js');
const { detectLayeredGarment } = await moduleAt('../vendor/three/garment-layer-detection.js');
const { addGarmentLayers } = await moduleAt('../vendor/three/garment-layered.js');
const seed = JSON.parse(readFileSync(new URL('../vendor/three/garment-seed-dolman.json', import.meta.url)));

function fixture(withLayers = true) {
  const width = 256, height = 256, data = new Uint8ClampedArray(width * height * 4).fill(255);
  const draw = (points, color) => {
    for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
      let inside = false;
      for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
        const a = points[i], b = points[j];
        if (((a[1] > y) !== (b[1] > y)) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
      }
      if (inside) data.set([...color, 255], (y * width + x) * 4);
    }
  };
  if (withLayers) {
    draw([[77, 44], [90, 42], [102, 76], [87, 76]], [160, 171, 184]);
    draw([[61, 211], [194, 213], [196, 231], [60, 228]], [160, 171, 184]);
  }
  draw([[50, 63], [85, 72], [125, 67], [165, 30], [224, 68], [202, 126], [194, 218], [61, 216], [63, 127], [30, 82]], [220, 197, 130]);
  return { width, height, data };
}

test('distinct upper strap and lower hem trigger layers; a plain colored shirt does not', () => {
  const image = fixture(), foreground = segmentForeground(image), result = detectLayeredGarment(image, foreground);
  assert.equal(result.detected, true);
  assert.equal(result.spec.strapSide, 'RIGHT');
  assert.ok(result.spec.strapWidthFraction > .035 && result.spec.strapWidthFraction < .10);
  assert.ok(result.spec.strapBottomX > result.spec.strapTopX);
  assert.ok(result.spec.strapBottomY > result.spec.strapTopY);
  assert.ok(result.outerMask.reduce((a, b) => a + b, 0) < foreground.mask.reduce((a, b) => a + b, 0));
  const plain = fixture(false);
  assert.equal(detectLayeredGarment(plain, segmentForeground(plain)).detected, false);
});

test('cool-gray sampling variation does not fragment the visible under-hem into unrelated pieces', () => {
  const image = fixture();
  for (let y = 216; y < 231; y++) for (let x = 60; x < 197; x++) {
    const i = (y * image.width + x) * 4;
    if (image.data[i] === 160 && x % 24 < 2) image.data.set([153, 167, 186, 255], i);
  }
  const detected = detectLayeredGarment(image, segmentForeground(image));
  assert.equal(detected.detected, true);
  assert.equal(detected.spec.strapSide, 'RIGHT');
  assert.ok(detected.spec.hemFraction > .015);
});

test('dolman family has bounded height and four real oval openings without the prior shoulder spike', () => {
  const checks = validateMesh(seed.vertices, seed.faces, seed.openings);
  assert.equal(checks.topology, true); assert.equal(checks.openings, true);
  assert.ok(Math.abs(Math.max(...seed.vertices.map(v => v[1])) - 1.35) < 1e-6);
  for (const name of ['left_cuff', 'right_cuff']) {
    const points = seed.openings[name].map(i => seed.vertices[i]);
    const span = axis => Math.max(...points.map(v => v[axis])) - Math.min(...points.map(v => v[axis]));
    assert.ok(span(2) / span(1) < .75, `${name} should be an oval cap opening`);
  }
});

test('complete layered mesh is rescored against full input; outer-only overlap is kept separately', async () => {
  const image = fixture(), original = segmentForeground(image), detected = detectLayeredGarment(image, original);
  const reference = { ...original, side: 'FRONT', id: 'synthetic-layered-source' };
  const b = maskBounds(detected.outerMask, image.width, image.height);
  const outerReference = { ...reference, mask: detected.outerMask, bounds: b, frame: { left: b.left / 256, top: b.top / 256, width: b.width / 256, height: b.height / 256 } };
  const outer = await fitCoarseFromMasks(seed, [outerReference]);
  const result = finalizeLayeredModel(outer, seed, [reference], detected.spec, addGarmentLayers);
  assert.deepEqual(Object.keys(result).sort(), ['algorithm', 'faces', 'quality', 'schemaVersion', 'sourceImages', 'vertices']);
  assert.equal(result.quality.meshParts.length, 3);
  assert.equal(result.quality.checks.openings, true);
  assert.equal(result.quality.views[0].outerIoU, outer.quality.views[0].iou);
  const target = new Uint8Array(384 * 384);
  for (let y = 0; y < 384; y++) for (let x = 0; x < 384; x++) target[y * 384 + x] = original.mask[Math.floor((y + .5) * 256 / 384) * 256 + Math.floor((x + .5) * 256 / 384)];
  const rendered = rasterizeSilhouette(result.vertices, result.faces, 'FRONT', result.quality.views[0].camera, 384);
  assert.equal(result.quality.views[0].iou, silhouetteIoU(target, rendered));
  assert.ok(result.vertices.length > outer.vertices.length);
  assert.equal(result.quality.allViewsMeetTarget, false);
});
