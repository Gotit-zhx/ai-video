import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const code = readFileSync(new URL('../vendor/three/garment-coarse.js', import.meta.url));
const { segmentForeground, fitCoarseFromMasks, deformSeed, validateMesh, rasterizeSilhouette, silhouetteIoU, buildCoarseModel, boundedCage, referenceConsistency } = await import(`data:text/javascript;base64,${Buffer.from(`${code}\n//# sourceURL=garment-coarse.js`).toString('base64')}`);
const seed = JSON.parse(readFileSync(new URL('../vendor/three/garment-seed.json', import.meta.url)));
const offshoulder = JSON.parse(readFileSync(new URL('../vendor/three/garment-seed-offshoulder.json', import.meta.url)));
const size = 192;
function imageFrom(mask, width = size, height = size, alpha = false) {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let i = 0; i < width * height; i++) { const color = mask[i] ? 110 : 255;
    data.set([color, color, color, alpha && !mask[i] ? 0 : 255], i * 4);
  } return { width, height, data };
}
function reference(params = {}, side = 'FRONT', id = 'fixture') {
  const mask = rasterizeSilhouette(deformSeed(seed, params), seed.faces, side, { scale: 70, cx: 96, cy: 154 }, size);
  return { ...segmentForeground(imageFrom(mask)), side, id };
}

test('blank, cropped and multiple-object inputs are rejected instead of becoming a successful mesh', async () => {
  assert.throws(() => segmentForeground(imageFrom(new Uint8Array(size * size))), { code: 'EMPTY_FOREGROUND' });
  assert.throws(() => segmentForeground(imageFrom(new Uint8Array(size * size).fill(1))), { code: 'EMPTY_FOREGROUND' });
  const mask = new Uint8Array(size * size);
  for (let y = 30; y < 140; y++) for (let x = 0; x < 40; x++) mask[y * size + x] = 1;
  assert.throws(() => segmentForeground(imageFrom(mask)), { code: 'CLIPPED_FOREGROUND' });
  mask.fill(0); for (let y = 40; y < 140; y++) for (let x = 25; x < 65; x++) { mask[y * size + x] = 1; mask[y * size + x + 90] = 1; }
  assert.throws(() => segmentForeground(imageFrom(mask)), { code: 'MULTIPLE_OBJECTS' });
  await assert.rejects(buildCoarseModel({}), { code: 'MISSING_FRONT_BACK' });
  await assert.rejects(buildCoarseModel({ TOP: { id: 'top', url: '/test' } }), { code: 'INVALID_SOURCE_IMAGES' });
  await assert.rejects(buildCoarseModel({ FRONT: { url: '/test' } }), { code: 'INVALID_SOURCE_IMAGES' });
  await assert.rejects(fitCoarseFromMasks(seed, []), { code: 'MISSING_FRONT_BACK' });
  await assert.rejects(fitCoarseFromMasks(seed, [{ side: 'FRONT', width: size, height: size, mask: new Uint8Array(size * size) }]), { code: 'INVALID_MASK' });
});

test('background segmentation retains actual transparent silhouette and ignores detached dust', () => {
  const ref = reference(), alpha = imageFrom(ref.mask, size, size, true);
  const result = segmentForeground(alpha);
  assert.equal(result.method, 'alpha-border-largest-component');
  assert.equal(silhouetteIoU(ref.mask, result.mask), 1);
  const dust = imageFrom(ref.mask); dust.data.set([0, 0, 0, 255], (10 * size + 10) * 4);
  assert.equal(silhouetteIoU(ref.mask, segmentForeground(dust).mask), 1);
});

test('changing source silhouette changes fit, improves actual overlap and never mutates the seed', async () => {
  const original = JSON.stringify(seed);
  const first = await fitCoarseFromMasks(seed, [reference({ width: .82, sleeve: .7 }, 'FRONT', 'first')]);
  const second = await fitCoarseFromMasks(seed, [reference({ width: 1.16, sleeve: 1.2 }, 'FRONT', 'second')]);
  assert.notDeepEqual(first.vertices, second.vertices);
  assert.deepEqual(first.sourceImages, { FRONT: 'first' });
  assert.deepEqual(second.sourceImages, { FRONT: 'second' });
  assert.equal(JSON.stringify(seed), original);
  for (const model of [first, second]) {
    assert.ok(model.quality.views[0].iou > model.quality.views[0].baselineIoU);
    assert.ok(model.quality.views[0].iou > .9);
    assert.equal(model.quality.status, 'NEEDS_REFERENCE');
    assert.deepEqual(model.quality.missingViews, ['BACK', 'LEFT', 'RIGHT']);
    assert.equal(model.quality.allViewsMeetTarget, false);
    assert.equal(model.quality.checks.openingCount, 4);
    assert.equal(model.quality.checks.depthPreserved, true);
    assert.equal(model.quality.validationResolution, 384);
    assert.deepEqual(model.faces, seed.faces);
  }
});

test('asymmetric family is selected by masks; topology and side silhouettes remain measured', async () => {
  const refs = ['FRONT', 'BACK', 'LEFT', 'RIGHT'].map(side => {
    const mask = rasterizeSilhouette(offshoulder.vertices, offshoulder.faces, side, { scale: 115, cx: 96, cy: 174 }, size);
    return { ...segmentForeground(imageFrom(mask)), side, id: side };
  });
  const result = await fitCoarseFromMasks(seed, refs, { seedCandidates: [offshoulder] });
  assert.equal(result.quality.seedFamily, 'offshoulder-from-earlier-reference');
  assert.equal(result.quality.views.length, 4);
  assert.ok(result.quality.views.every(v => v.iou > .93));
  assert.deepEqual(result.faces, offshoulder.faces);
  assert.equal(validateMesh(result.vertices, result.faces, offshoulder.openings).openings, true);
});

test('bounded cage preserves depth when only frontal images are supplied; invalid mesh and cancellation fail', async () => {
  const vertices = boundedCage(seed, [reference()]);
  assert.deepEqual(vertices.map(v => v[2]), seed.vertices.map(v => v[2]));
  assert.deepEqual(vertices.map(v => v[1]), seed.vertices.map(v => v[1]));
  assert.equal(validateMesh([[0, NaN, 0]], [[0, 1, 2]]).finite, false);
  assert.equal(validateMesh(seed.vertices, [[0, 0, 1]], seed.openings).topology, false);
  const controller = new AbortController(); controller.abort();
  await assert.rejects(fitCoarseFromMasks(seed, [reference()], { signal: controller.signal }), { name: 'AbortError' });
});

test('opposite references report conditional silhouette conflict instead of assuming extra views agree', () => {
  const equal = referenceConsistency([reference({}, 'FRONT'), reference({}, 'BACK')], seed);
  assert.ok(equal[0].iou > .98);
  const conflict = referenceConsistency([reference({ width: .7, sleeve: .6 }, 'FRONT'), reference({}, 'BACK')], seed);
  assert.equal(conflict[0].pair, 'FRONT_BACK');
  assert.ok(conflict[0].conditionalMaxMinIoU < .95);
  assert.equal(conflict[0].conditionalMaxMinIoU, (1 + conflict[0].iou) / 2);
  assert.deepEqual(referenceConsistency([reference()], seed), []);
});
