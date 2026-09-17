import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

async function moduleAt(path) {
  const code = readFileSync(new URL(path, import.meta.url), 'utf8');
  return import(`data:text/javascript;base64,${Buffer.from(`${code}\n//# sourceURL=${path}`).toString('base64')}`);
}
const { buildCapPanels } = await moduleAt('../vendor/three/garment-panels.js');
const { validateMesh, maskBounds, scorePanelModel } = await moduleAt('../vendor/three/garment-coarse.js');

function polygon(points, side) {
  const width = 192, height = 192, mask = new Uint8Array(width * height);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    let inside = false;
    for (let i = 0, j = points.length - 1; i < points.length; j = i++) {
      const a = points[i], b = points[j];
      if ((a[1] > y) !== (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
    }
    mask[y * width + x] = inside ? 1 : 0;
  }
  return { mask, width, height, side, id: `test-${side}`, bounds: maskBounds(mask, width, height) };
}
function refs(waist = 0) {
  const front = [[35, 51], [55, 43], [81, 50], [108, 47], [141, 19], [174, 48], [156, 101],
    [148 + waist, 174], [50 - waist, 172], [49, 110], [20, 66]];
  const side = [[87, 16], [102, 28], [117, 60], [124, 175], [71, 173], [67, 65]];
  return [polygon(front, 'FRONT'), polygon(front.map(([x, y]) => [191 - x, y]), 'BACK'),
    polygon(side, 'LEFT'), polygon(side.map(([x, y]) => [191 - x, y]), 'RIGHT')];
}
const references = refs();
const model = await buildCapPanels(references, { armholes: { topFraction: .79, bottomFraction: .53, halfDepthFraction: .06 } });

test('image-defined panels preserve the measured contour, four real openings and manifold sewing', async () => {
  const checks = validateMesh(model.vertices, model.faces, model.openings);
  assert.equal(checks.topology, true);
  assert.equal(checks.openings, true);
  assert.equal(checks.degenerateFaces, 0);
  assert.deepEqual(Object.keys(model.openings).sort(), ['hem', 'left_cuff', 'neck', 'right_cuff']);
  const scored = await scorePanelModel(model, references);
  assert.ok(scored.quality.views.find(v => v.side === 'FRONT').iou > .97);
  assert.equal(scored.quality.checks.collisionChecked, false);
  assert.equal(scored.quality.dimensionsMeasured, false);
  assert.ok(model.vertices.length + 2400 < 15000);
  assert.ok(model.faces.length + 4800 < 30000);
});

test('independent sleeve openings follow side evidence and hem remains an open supported tube', () => {
  const height = model.params.body_length;
  const range = (ids, axis) => {
    const values = ids.map(i => model.vertices[i][axis]);
    return [Math.min(...values), Math.max(...values)];
  };
  for (const name of ['left_cuff', 'right_cuff']) {
    const y = range(model.openings[name], 1), z = range(model.openings[name], 2);
    assert.ok(Math.abs(y[0] / height - .53) < 1e-5);
    assert.ok(Math.abs(y[1] / height - .79) < 1e-5);
    assert.ok((z[1] - z[0]) / height < .18, 'cuff depth must not grow with torso calibration');
  }
  const hem = range(model.openings.hem, 2);
  assert.ok((hem[1] - hem[0]) / height > .25, 'slanted image bottom must not collapse the physical hem depth');
  assert.ok(model.openings.hem.every(i => model.vertices[i][1] < height * .08));
});

test('triangles are redistributed before depth fitting instead of preserving an ear-clipping fan', () => {
  const distortions = model.faces.map(f => {
    const [a, b, c] = f.map(i => model.vertices[i]);
    const squared = (p, q) => (p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2;
    const doubleArea = Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]));
    return (squared(a, b) + squared(b, c) + squared(c, a)) / Math.max(1e-14, doubleArea * 2 * Math.sqrt(3));
  }).sort((a, b) => a - b);
  assert.ok(distortions[Math.floor(distortions.length * .90)] < 3);
});

test('new source proportions rebuild the panels and aborted generation does not return a draft', async () => {
  const wider = await buildCapPanels(refs(12));
  assert.notDeepEqual(wider.vertices, model.vertices);
  const waistSpan = m => {
    const xs = m.vertices.filter(v => v[1] < m.params.body_length * .12).map(v => v[0]);
    return Math.max(...xs) - Math.min(...xs);
  };
  assert.ok(waistSpan(wider) > waistSpan(model) * 1.10);
  const signal = AbortSignal.abort();
  await assert.rejects(buildCapPanels(references, { signal }), { name: 'AbortError' });
});

test('the same construction works with the high shoulder on the opposite side', async () => {
  const mirrored = references.map(r => {
    const mask = new Uint8Array(r.mask.length);
    for (let y = 0; y < r.height; y++) for (let x = 0; x < r.width; x++) mask[y * r.width + x] = r.mask[y * r.width + r.width - 1 - x];
    return { ...r, mask, bounds: maskBounds(mask, r.width, r.height) };
  });
  const mesh = await buildCapPanels(mirrored), scored = await scorePanelModel(mesh, mirrored);
  assert.equal(scored.quality.checks.openings, true);
  assert.equal(scored.quality.checks.topology, true);
  assert.ok(scored.quality.views.find(v => v.side === 'FRONT').iou > .97);
  assert.ok(mesh.params.neck_width > .1);
});
