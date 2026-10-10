/** Browser-only short-sleeve garment draft. Images constrain silhouettes, not hidden truth. */
export const SIDES = ['FRONT', 'BACK', 'LEFT', 'RIGHT'];
const ALGORITHM = 'garment-coarse-v1';
const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
const smooth = x => { x = clamp(x, 0, 1); return x * x * (3 - 2 * x); };
// Background tabs may clamp nested timers to a minute. Cooperative work must
// yield through the scheduler/message queue without becoming a stalled fit.
const yieldFrame = () => globalThis.scheduler?.yield ? globalThis.scheduler.yield() : new Promise(resolve => {
  if (typeof MessageChannel !== 'function') { setTimeout(resolve, 0); return; }
  const channel = new MessageChannel();
  channel.port1.onmessage = () => { channel.port1.close(); channel.port2.close(); resolve(); };
  channel.port2.postMessage(0);
});
const abort = signal => { if (signal?.aborted) throw new DOMException('已取消生成', 'AbortError'); };
const now = () => globalThis.performance?.now() ?? Date.now();
function failure(message, code) { const error = new Error(message); error.code = code; return error; }
function median(values) { const a = [...values].sort((x, y) => x - y); return a[Math.floor(a.length / 2)] || 0; }

export function maskBounds(mask, width, height) {
  let left = width, right = -1, top = height, bottom = -1, area = 0;
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) if (mask[y * width + x]) {
    left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = y; area++;
  }
  return { left, right, top, bottom, width: right - left + 1, height: bottom - top + 1, area };
}

/** Border-connected background removal; never invents a mask for an unreadable/blank input. */
export function segmentForeground(image) {
  const { data, width, height } = image;
  if (!data || width < 24 || height < 24 || data.length !== width * height * 4)
    throw failure('图片分辨率不足，无法识别服装轮廓', 'INVALID_IMAGE');
  const n = width * height, border = [], alphaBorder = [];
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    if (x < 2 || y < 2 || x >= width - 2 || y >= height - 2) {
      const i = (y * width + x) * 4; border.push([data[i], data[i + 1], data[i + 2]]); alphaBorder.push(data[i + 3]);
    }
  }
  const transparent = alphaBorder.filter(a => a < 24).length > alphaBorder.length * .8;
  const color = [0, 1, 2].map(c => median(border.map(v => v[c])));
  const distance = (r, g, b) => Math.hypot(r - color[0], g - color[1], b - color[2]);
  const noise = median(border.map(v => distance(...v)));
  if (!transparent && noise > 35) throw failure('背景较复杂，无法可靠提取单件服装；请先使用纯色底图', 'AMBIGUOUS_BACKGROUND');
  const tolerance = clamp(14 + noise * 2.5, 16, 58);
  const candidates = new Uint8Array(n), background = new Uint8Array(n), queue = new Int32Array(n);
  for (let p = 0; p < n; p++) { const i = p * 4;
    candidates[p] = data[i + 3] < 24 || (!transparent && distance(data[i], data[i + 1], data[i + 2]) <= tolerance) ? 1 : 0;
  }
  let head = 0, tail = 0;
  const enqueue = p => { if (candidates[p] && !background[p]) { background[p] = 1; queue[tail++] = p; } };
  for (let x = 0; x < width; x++) { enqueue(x); enqueue((height - 1) * width + x); }
  for (let y = 0; y < height; y++) { enqueue(y * width); enqueue(y * width + width - 1); }
  while (head < tail) { const p = queue[head++], x = p % width;
    if (x) enqueue(p - 1); if (x < width - 1) enqueue(p + 1); if (p >= width) enqueue(p - width); if (p < n - width) enqueue(p + width);
  }
  const visited = new Uint8Array(n); let largest = [], foregroundArea = 0;
  for (let start = 0; start < n; start++) if (!background[start] && !visited[start]) {
    head = 0; tail = 1; queue[0] = start; visited[start] = 1;
    const add = p => { if (!background[p] && !visited[p]) { visited[p] = 1; queue[tail++] = p; } };
    while (head < tail) { const p = queue[head++], x = p % width;
      if (x) add(p - 1); if (x < width - 1) add(p + 1); if (p >= width) add(p - width); if (p < n - width) add(p + width);
    }
    foregroundArea += tail; if (tail > largest.length) largest = Array.from(queue.subarray(0, tail));
  }
  const mask = new Uint8Array(n); for (const p of largest) mask[p] = 1;
  const bounds = maskBounds(mask, width, height), coverage = largest.length / n;
  if (coverage < .015 || bounds.width < width * .08 || bounds.height < height * .15)
    throw failure('没有识别到完整服装轮廓，请使用清晰的商品纯色底图', 'EMPTY_FOREGROUND');
  if (coverage > .89 || bounds.left === 0 || bounds.top === 0 || bounds.right === width - 1 || bounds.bottom === height - 1)
    throw failure('商品轮廓被裁切或背景未分离，请换用完整的纯色底图', 'CLIPPED_FOREGROUND');
  const dominance = largest.length / Math.max(1, foregroundArea);
  if (dominance < .68) throw failure('图片含多个明显物体，暂不能确定要拟合的商品', 'MULTIPLE_OBJECTS');
  const reliability = clamp((transparent ? .98 : .94 - noise / 180) * dominance, 0, 1);
  return { mask, width, height, bounds, reliability, frame: { left: bounds.left / width, top: bounds.top / height,
    width: bounds.width / width, height: bounds.height / height },
    method: transparent ? 'alpha-border-largest-component' : 'border-color-flood-largest-component' };
}

function resizeMask(mask, width, height, size) {
  const result = new Uint8Array(size * size);
  const scale = Math.min(size / width, size / height), dx = (size - width * scale) / 2, dy = (size - height * scale) / 2;
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const sx = Math.floor((x + .5 - dx) / scale), sy = Math.floor((y + .5 - dy) / scale);
    if (sx >= 0 && sy >= 0 && sx < width && sy < height) result[y * size + x] = mask[sy * width + sx];
  }
  return result;
}

function project(v, side) { return [side === 'FRONT' ? v[0] : side === 'BACK' ? -v[0] : side === 'LEFT' ? -v[2] : v[2], v[1]]; }
/** Actual triangle coverage, independent of target pixels. Front is +Z; garment-left camera is +X. */
export function rasterizeSilhouette(vertices, faces, side, camera, size) {
  const mask = new Uint8Array(size * size), xy = vertices.map(v => { const p = project(v, side);
    return [p[0] * camera.scale + camera.cx, camera.cy - p[1] * camera.scale]; });
  for (const face of faces) {
    const a = xy[face[0]], b = xy[face[1]], c = xy[face[2]];
    const den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
    if (Math.abs(den) < 1e-9) continue;
    const x0 = Math.max(0, Math.floor(Math.min(a[0], b[0], c[0]))), x1 = Math.min(size - 1, Math.ceil(Math.max(a[0], b[0], c[0])));
    const y0 = Math.max(0, Math.floor(Math.min(a[1], b[1], c[1]))), y1 = Math.min(size - 1, Math.ceil(Math.max(a[1], b[1], c[1])));
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
      const u = ((b[1] - c[1]) * (x + .5 - c[0]) + (c[0] - b[0]) * (y + .5 - c[1])) / den;
      const v = ((c[1] - a[1]) * (x + .5 - c[0]) + (a[0] - c[0]) * (y + .5 - c[1])) / den;
      if (u >= -1e-8 && v >= -1e-8 && u + v <= 1 + 1e-8) mask[y * size + x] = 1;
    }
  }
  return mask;
}

export function silhouetteIoU(target, actual) {
  let intersection = 0, union = 0;
  for (let i = 0; i < target.length; i++) { if (target[i] || actual[i]) union++; if (target[i] && actual[i]) intersection++; }
  return union ? intersection / union : 0;
}

function boundary(mask, size) {
  const list = [];
  for (let y = 1; y < size - 1; y++) for (let x = 1; x < size - 1; x++) { const p = y * size + x;
    if (mask[p] && (!mask[p - 1] || !mask[p + 1] || !mask[p - size] || !mask[p + size])) list.push([x, y]);
  } return list;
}
export function silhouetteMetrics(target, actual, size) {
  const a = boundary(target, size), b = boundary(actual, size), distances = [];
  if (!a.length || !b.length) return { iou: silhouetteIoU(target, actual), boundaryP95: null };
  for (const [from, to] of [[a, b], [b, a]]) for (const p of from) {
    let d = Infinity; for (const q of to) d = Math.min(d, (p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2);
    distances.push(Math.sqrt(d));
  }
  const box = maskBounds(target, size, size); distances.sort((x, y) => x - y);
  return { iou: silhouetteIoU(target, actual), boundaryP95: distances[Math.floor((distances.length - 1) * .95)] / Math.hypot(box.width, box.height) };
}

const DEFAULT_FIT = { width: 1, sleeve: 1, hem: 1, drop: 0, slope: 0, depth: 1, neck: 1, neckline: 0 };
const LIMITS = { width: [.55, 1.35], sleeve: [.3, 1.6], hem: [.75, 1.25], drop: [-.18, .18], slope: [-.35, .35], depth: [.75, 1.8], neck: [.8, 2.1], neckline: [-.04, .15] };
/** Smooth monotone garment-space transform; preserves depth and sleeve attachment. */
export function deformSeed(seed, input = {}) {
  const p = { ...DEFAULT_FIT, ...input };
  for (const key of Object.keys(DEFAULT_FIT)) if (!Number.isFinite(p[key]) || p[key] < LIMITS[key][0] || p[key] > LIMITS[key][1])
    throw failure('拟合参数超出稳定范围', 'INVALID_FIT');
  const half = seed.params.torso_width * .5, length = seed.params.body_length;
  const vertices = seed.vertices.map(([x, y, z]) => {
    const sleeveWeight = smooth((Math.abs(x) - half) / seed.params.sleeve_length);
    const hemWeight = 1 - smooth(y / (length * .58));
    const topWeight = smooth((y / length - .6) / .32), ax = Math.min(Math.abs(x), half), neckHalf = seed.params.neck_width / 2;
    // Positive piecewise-linear collar warp preserves the shoulder endpoints.
    const collarX = ax <= neckHalf ? ax * p.neck : neckHalf * p.neck + (ax - neckHalf) * (half - neckHalf * p.neck) / (half - neckHalf);
    const torsoX = ax + (collarX - ax) * topWeight;
    const nx = Math.sign(x) * (torsoX * p.width + Math.max(0, Math.abs(x) - half) * p.sleeve)
      * (1 + (p.hem - 1) * hemWeight);
    const ny = y - p.drop * sleeveWeight + p.slope * x * smooth((y / length - .2) / .6)
      - p.neckline * (1 - smooth(Math.abs(x) / half)) * topWeight;
    return [nx, ny, z * p.depth];
  });
  // Relative-scale coordinates: height registration is fixed before raster scoring.
  // This preserves aspect/depth relations while keeping the assumed camera framing stable.
  const yMin = Math.min(...vertices.map(v => v[1])), yMax = Math.max(...vertices.map(v => v[1]));
  for (const v of vertices) v[1] = (v[1] - yMin) * length / (yMax - yMin);
  return vertices;
}

/** Numerical/topological checks only. This intentionally does not claim cloth or collision validation. */
export function validateMesh(vertices, faces, openings = {}) {
  const finite = vertices.length >= 3 && vertices.every(v => v.length === 3 && v.every(Number.isFinite));
  if (!finite) return { finite: false, topology: false, openings: false };
  const edges = new Map(); let valid = true, degenerateFaces = 0;
  for (const f of faces) {
    if (f.length !== 3 || f.some(i => !Number.isInteger(i) || i < 0 || i >= vertices.length) || new Set(f).size !== 3) { valid = false; continue; }
    const [a, b, c] = f.map(i => vertices[i]); const u = b.map((v, i) => v - a[i]), v = c.map((v, i) => v - a[i]);
    if (Math.hypot(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]) < 1e-10) degenerateFaces++;
    for (let j = 0; j < 3; j++) { const a = f[j], b = f[(j + 1) % 3], key = a < b ? `${a}:${b}` : `${b}:${a}`;
      edges.set(key, (edges.get(key) || 0) + 1);
    }
  }
  const loops = Object.values(openings); let openingEdges = 0;
  const open = loops.length === 4 && loops.every(loop => {
    if (loop.length < 3) return false;
    let perimeter = 0;
    for (let j = 0; j < loop.length; j++) { const a = loop[j], b = loop[(j + 1) % loop.length], key = a < b ? `${a}:${b}` : `${b}:${a}`;
      if (edges.get(key) !== 1) return false; openingEdges++;
      perimeter += Math.hypot(...vertices[a].map((v, i) => v - vertices[b][i]));
    } return perimeter > 1e-4;
  });
  const boundaryEdges = [...edges.values()].filter(n => n === 1).length;
  return { finite, topology: valid && faces.length > 0 && degenerateFaces === 0 && [...edges.values()].every(n => n <= 2),
    openings: open && openingEdges === boundaryEdges, openingCount: loops.length, degenerateFaces,
    depthPreserved: Math.max(...vertices.map(v => v[2])) - Math.min(...vertices.map(v => v[2])) > .08,
    collisionChecked: false, physicalSimulation: false };
}

function preparedReferences(references, size, seed) {
  return references.map(r => {
    const mask = resizeMask(r.mask, r.width, r.height, size), box = maskBounds(mask, size, size);
    return { ...r, mask, size, camera: { scale: (box.height - 1) / seed.params.body_length,
      cx: (box.left + box.right + 1) / 2, cy: box.bottom + .5 } };
  });
}

/** Upper-outline evidence prevents a symmetric crew neck from inheriting a
 * different garment's slanted collar merely because its side silhouette wins. */
export function garmentStructureEvidence(references) {
  const reference = references.find(r => r.side === 'FRONT') || references.find(r => r.side === 'BACK');
  if (!reference) return { family: 'UNKNOWN', shoulderAsymmetry: null };
  const { mask, width, height } = reference, box = maskBounds(mask, width, height);
  const cx = (box.left + box.right) / 2, tops = [-1, 1].map(sign => {
    const values = [];
    for (let x = box.left; x <= box.right; x++) {
      const t = sign * (x - cx) / box.width;
      if (t < .10 || t > .28) continue;
      for (let y = box.top; y <= box.bottom; y++) if (mask[y * width + x]) { values.push(y); break; }
    }
    values.sort((a, b) => a - b);
    return values.length ? values[Math.floor(values.length * .25)] : null;
  });
  const asymmetry = tops.includes(null) ? null : Math.abs(tops[0] - tops[1]) / box.height;
  return { family: asymmetry == null ? 'UNKNOWN' : asymmetry < .075 ? 'SYMMETRIC_UPPER_OUTLINE' : 'ASYMMETRIC',
    shoulderAsymmetry: asymmetry, evidence: 'opposing-upper-outline-heights', inferred: true };
}

/** Conditional opposing-view check on a shared world grid under the same fixed cameras. */
export function referenceConsistency(references, seed) {
  const size = 512, worldScale = size / 2.8, cx = size / 2, cy = size * .85;
  const aligned = new Map();
  for (const r of preparedReferences(references, 384, seed)) {
    const mask = new Uint8Array(size * size), sign = r.side === 'BACK' || r.side === 'LEFT' ? -1 : 1;
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
      const sourceX = Math.floor(sign * (x + .5 - cx) / worldScale * r.camera.scale + r.camera.cx);
      const sourceY = Math.floor(r.camera.cy - (cy - y - .5) / worldScale * r.camera.scale);
      if (sourceX >= 0 && sourceY >= 0 && sourceX < r.size && sourceY < r.size) mask[y * size + x] = r.mask[sourceY * r.size + sourceX];
    }
    aligned.set(r.side, mask);
  }
  return [['FRONT', 'BACK'], ['LEFT', 'RIGHT']].filter(([a, b]) => aligned.has(a) && aligned.has(b)).map(([a, b]) => {
    const iou = silhouetteIoU(aligned.get(a), aligned.get(b));
    return { pair: `${a}_${b}`, iou, conditionalMaxMinIoU: (1 + iou) / 2, assumption: 'fixed-opposing-orthographic-cameras-raster-approximation' };
  });
}

function interpolate(values, t) {
  const x = clamp(t, 0, values.length - 1), i = Math.floor(x), f = x - i;
  return values[i] * (1 - f) + values[Math.min(i + 1, values.length - 1)] * f;
}
function blur(values, sigma) {
  const radius = Math.ceil(sigma * 3);
  return values.map((_, i) => { let sum = 0, weight = 0;
    for (let j = -radius; j <= radius; j++) { const w = Math.exp(-j * j / (2 * sigma * sigma)); sum += values[clamp(i + j, 0, values.length - 1)] * w; weight += w; }
    return sum / weight;
  });
}
function pchip(xs, ys, x) {
  const d = xs.slice(1).map((b, i) => (ys[i + 1] - ys[i]) / (b - xs[i]));
  const slopes = ys.map((_, i) => {
    if (i === 0) return d[0]; if (i === ys.length - 1) return d.at(-1);
    if (d[i - 1] * d[i] <= 0) return 0;
    const a = xs[i] - xs[i - 1], b = xs[i + 1] - xs[i], w1 = 2 * b + a, w2 = b + 2 * a;
    return (w1 + w2) / (w1 / d[i - 1] + w2 / d[i]);
  });
  x = clamp(x, xs[0], xs.at(-1)); let i = 0; while (i < xs.length - 2 && x > xs[i + 1]) i++;
  const h = xs[i + 1] - xs[i], t = (x - xs[i]) / h;
  return (2 * t ** 3 - 3 * t * t + 1) * ys[i] + (t ** 3 - 2 * t * t + t) * h * slopes[i]
    + (-2 * t ** 3 + 3 * t * t) * ys[i + 1] + (t ** 3 - t * t) * h * slopes[i + 1];
}

/** Invertible per-height affine cage. Four broad depth controls retain chest/back support. */
export function boundedCage(seed, references) {
  const count = 257, length = seed.params.body_length, edges = [], edgeSet = new Set();
  for (const f of seed.faces) for (let i = 0; i < 3; i++) { const a = f[i], b = f[(i + 1) % 3], key = a < b ? `${a}:${b}` : `${b}:${a}`;
    if (!edgeSet.has(key)) { edgeSet.add(key); edges.push([seed.vertices[a], seed.vertices[b]]); }
  }
  const bounds = Array.from({ length: count }, (_, i) => {
    const y = length * i / (count - 1); let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity;
    for (const [a, b] of edges) if (Math.min(a[1], b[1]) <= y + 1e-7 && Math.max(a[1], b[1]) >= y - 1e-7) {
      const t = Math.abs(b[1] - a[1]) < 1e-10 ? 0 : clamp((y - a[1]) / (b[1] - a[1]), 0, 1);
      const x = a[0] + (b[0] - a[0]) * t, z = a[2] + (b[2] - a[2]) * t;
      x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z);
    } return [x0, x1, z0, z1];
  });
  if (bounds.some(b => b.some(x => !Number.isFinite(x)))) return seed.vertices.map(v => [...v]);
  const refs = preparedReferences(references, 384, seed), fields = [];
  for (const [axis, pair, sides] of [[0, 0, ['FRONT', 'BACK']], [2, 2, ['LEFT', 'RIGHT']]]) {
    let relevant = refs.filter(r => sides.includes(r.side)); if (!relevant.length) continue;
    if (axis === 0 && relevant.some(r => r.primary)) relevant = relevant.filter(r => r.primary);
    const profiles = relevant.map(r => {
      const rows = [];
      for (let y = 0; y < r.size; y++) { let left = r.size, right = -1;
        for (let x = 0; x < r.size; x++) if (r.mask[y * r.size + x]) { left = Math.min(left, x); right = x; }
        if (right >= left) { const sign = r.side === 'BACK' || r.side === 'LEFT' ? -1 : 1;
          const a = sign * (left + .5 - r.camera.cx) / r.camera.scale, b = sign * (right + .5 - r.camera.cx) / r.camera.scale;
          rows.push([(r.camera.cy - y - .5) / r.camera.scale, Math.min(a, b), Math.max(a, b)]);
        }
      } rows.reverse();
      return Array.from({ length: count }, (_, i) => {
        const y = length * i / (count - 1); let j = 0; while (j < rows.length - 2 && rows[j + 1][0] < y) j++;
        const t = clamp((y - rows[j][0]) / Math.max(1e-8, rows[j + 1][0] - rows[j][0]), 0, 1);
        return [1, 2].map(k => rows[j][k] * (1 - t) + rows[j + 1][k] * t);
      });
    });
    let scale = [], offset = [];
    for (let i = 0; i < count; i++) {
      const a = profiles.reduce((sum, p) => sum + p[i][0], 0) / profiles.length, b = profiles.reduce((sum, p) => sum + p[i][1], 0) / profiles.length;
      const s = clamp((b - a) / Math.max(.05, bounds[i][pair + 1] - bounds[i][pair]), .75, 1.35);
      scale.push(s); offset.push(clamp((a + b - s * (bounds[i][pair] + bounds[i][pair + 1])) / 2, -.12, .12));
    }
    const bottom = Math.round(.052 * (count - 1)), top = Math.round(.919 * (count - 1));
    for (let i = 0; i < count; i++) { if (i < bottom) { scale[i] = scale[bottom]; offset[i] = offset[bottom]; }
      if (i > top) { scale[i] = scale[top]; offset[i] = offset[top]; }
    }
    if (axis === 2) {
      const knots = [.074, .37, .667, .911];
      const sample = values => knots.map(k => median(values.filter((_, i) => Math.abs(i / (count - 1) - k) < .067)));
      const s = sample(scale), o = sample(offset);
      scale = scale.map((_, i) => clamp(pchip(knots, s, i / (count - 1)), .75, 1.35));
      offset = offset.map((_, i) => clamp(pchip(knots, o, i / (count - 1)), -.12, .12));
    } else { scale = blur(scale, seed.cageSmoothSigma || 5); offset = blur(offset, seed.cageSmoothSigma || 5); }
    fields.push({ axis, scale, offset });
  }
  return seed.vertices.map(vertex => { const v = [...vertex], y = v[1] / length * (count - 1);
    for (const field of fields) v[field.axis] = v[field.axis] * interpolate(field.scale, y) + interpolate(field.offset, y);
    return v;
  });
}

/** Pure mesh/mask entry point, also used by Node tests; reference masks are never changed during search. */
export async function fitCoarseFromMasks(seed, references, { onProgress, signal, seedCandidates = [] } = {}) {
  const started = now(); abort(signal);
  if (!references.length || !references.some(r => r.side === 'FRONT' || r.side === 'BACK'))
    throw failure('至少需要一张正面或背面的完整商品底图', 'MISSING_FRONT_BACK');
  for (const r of references) if (!SIDES.includes(r.side) || r.mask.length !== r.width * r.height || !maskBounds(r.mask, r.width, r.height).area)
    throw failure('参考轮廓为空或无效', 'INVALID_MASK');
  const structureEvidence = garmentStructureEvidence(references);
  let cageVertices = null, cageScore = -1;
  for (const candidate of [seed, ...seedCandidates]) {
    if (candidate.geometryVersion === 'offshoulder-prior-supported' && structureEvidence.family === 'SYMMETRIC_UPPER_OUTLINE') continue;
    abort(signal); const vertices = boundedCage(candidate, references), refs = preparedReferences(references, 112, candidate);
    const structure = validateMesh(vertices, candidate.faces, candidate.openings);
    if (!structure.finite || !structure.topology || !structure.openings || !structure.depthPreserved) continue;
    const score = refs.reduce((sum, r) => sum + (r.weight || 1) * silhouetteIoU(r.mask, rasterizeSilhouette(vertices, candidate.faces, r.side, r.camera, r.size)), 0) / refs.reduce((sum,r)=>sum+(r.weight||1),0);
    if (score > cageScore) { cageScore = score; seed = candidate; cageVertices = vertices; }
    await yieldFrame();
  }
  const low = preparedReferences(references, 112, seed), high = preparedReferences(references, 384, seed);
  let best = { ...DEFAULT_FIT }, evaluations = 0;
  const score = p => {
    const vertices = deformSeed(seed, p); evaluations++;
    return low.reduce((total, r) => total + (r.weight || 1) * silhouetteIoU(r.mask, rasterizeSilhouette(vertices, seed.faces, r.side, r.camera, r.size)), 0) / low.reduce((sum,r)=>sum+(r.weight||1),0);
  };
  let bestScore = score(best), baselineScore = bestScore;
  const hasDepth = references.some(r => r.side === 'LEFT' || r.side === 'RIGHT');
  const keys = ['width', 'sleeve', 'hem', 'drop', 'slope', 'neck', 'neckline', ...(hasDepth ? ['depth'] : [])];
  const steps = { width: .24, sleeve: .4, hem: .16, drop: .10, slope: .16, depth: .32, neck: .6, neckline: .08 };
  for (let round = 0; round < 4; round++) for (const key of keys) {
    abort(signal);
    const origin = best[key], step = steps[key] / (2 ** round);
    for (const direction of [-1, 1]) {
      const neckUpper = Math.min(LIMITS.neck[1], .88 * seed.params.torso_width / seed.params.neck_width);
      const value = clamp(origin + step * direction, LIMITS[key][0], key === 'neck' ? neckUpper : LIMITS[key][1]), trial = { ...best, [key]: value };
      if (value === best[key]) continue;
      const candidateScore = score(trial);
      if (candidateScore > bestScore + .0001) { bestScore = candidateScore; best = trial; }
    }
    onProgress?.({ stage: 'fitting', progress: .25 + .55 * (round * keys.length + keys.indexOf(key) + 1) / (4 * keys.length),
      message: '正在拟合衣身、袖口和支撑体积', evaluations });
    await yieldFrame();
  }
  abort(signal); onProgress?.({ stage: 'checking', progress: .86, message: '正在逐面校验轮廓与开口' });
  let useCage = cageScore >= bestScore;
  let vertices = useCage ? cageVertices : deformSeed(seed, best);
  if (!vertices) throw failure('没有通过结构校验的候选模型，已保留原模型', 'INVALID_MESH');
  vertices = vertices.map(v => v.map(x => Math.round(x * 1e7) / 1e7));
  let checks = validateMesh(vertices, seed.faces, seed.openings);
  if ((!checks.finite || !checks.topology || !checks.openings || !checks.depthPreserved) && !useCage && cageVertices) {
    useCage = true; vertices = cageVertices.map(v => v.map(x => Math.round(x * 1e7) / 1e7));
    checks = validateMesh(vertices, seed.faces, seed.openings);
  }
  if (useCage) bestScore = cageScore;
  if (!checks.finite || !checks.topology || !checks.openings || !checks.depthPreserved)
    throw failure('候选模型结构校验未通过，已保留原模型', 'INVALID_MESH');
  const views = [];
  for (const r of high) {
    abort(signal); const metrics = silhouetteMetrics(r.mask, rasterizeSilhouette(vertices, seed.faces, r.side, r.camera, r.size), r.size);
    const baselineIoU = silhouetteIoU(r.mask, rasterizeSilhouette(seed.vertices, seed.faces, r.side, r.camera, r.size));
    views.push({ side: r.side, ...metrics, baselineIoU, segmentation: r.reliability ?? null, frame: r.frame ?? null,
      camera: { ...r.camera, projection: 'orthographic', calibration: 'frozen-bbox-height-assumption', rasterSize: r.size },
      meetsSilhouetteTarget: metrics.iou >= .95 && metrics.boundaryP95 != null && metrics.boundaryP95 <= .015 });
    await yieldFrame();
  }
  const missingViews = SIDES.filter(side => !references.some(r => r.side === side));
  const warnings = ['快速粗版仅拟合服装轮廓；褶皱、缝线、层叠肩带和不可见结构尚未还原。', '相机采用固定正交假设；识别分数不等于实物还原度。'];
  const consistency = referenceConsistency(references, seed);
  if (consistency.some(pair => pair.conditionalMaxMinIoU < .95)) warnings.push('对向参考图存在轮廓冲突：按当前固定正交相机假设，相关两面无法同时达到 0.95；需先核对姿态和视角。');
  if (missingViews.length) warnings.push('缺少方向继续使用有支撑的衣身结构，未对缺失视图计分。');
  if (views.some(v => v.iou < .8)) warnings.push('部分视图与短袖服装基础结构差异较大，建议补充同姿态的纯色参考图。');
  if (!hasDepth) warnings.push('未提供侧面参考，胸背厚度来自服装结构预设。');
  const quality = { status: missingViews.length || views.some(v => v.iou < .8 || (v.segmentation != null && v.segmentation < .8)) ? 'NEEDS_REFERENCE' : 'COARSE',
    views, missingViews, checks, warnings, referenceConsistency: consistency, elapsedMs: Math.round(now() - started), targetIoU: .95,
    allViewsMeetTarget: missingViews.length === 0 && views.every(v => v.meetsSilhouetteTarget),
    fitResolution: 112, validationResolution: 384, evaluations, baselineFitIoU: baselineScore, fittedFitIoU: bestScore,
    segmentationMethod: 'border-connected-background-largest-object',
    fitParameters: useCage ? { minScale: .75, maxScale: 1.35, maxTranslation: .12, depthControlCount: 4 } : best,
    structureEvidence, seedFamily: seed.geometryVersion?.startsWith('cap-dolman') ? 'cap-dolman-source-color-panels' : seed.geometryVersion === 'offshoulder-prior-supported' ? 'offshoulder-from-earlier-reference' : 'supported-short-sleeve-open-shell',
    fitMethod: useCage ? 'bounded-positive-cage-four-depth-controls' : 'bounded-semantic-parameters', representation: 'single-layer-open-shell', dimensionsMeasured: false };
  if (seed.geometryVersion === 'offshoulder-prior-supported') quality.warnings.push('本次选用此前灰色斜肩服装建立的结构先验，按当前图片重新拟合。');
  return { schemaVersion: 1, algorithm: ALGORITHM, sourceImages: Object.fromEntries(references.map(r => [r.side, r.id])),
    vertices, faces: seed.faces.map(f => [...f]), quality };
}

/** Validate the actual sewn panels without a second silhouette deformation. */
export async function scorePanelModel(seed, references, { onProgress, signal } = {}) {
  const started = now(), checks = validateMesh(seed.vertices, seed.faces, seed.openings);
  if (!checks.finite || !checks.topology || !checks.openings || !checks.depthPreserved)
    throw failure('衣片模型结构校验未通过，已保留原模型', 'INVALID_PANEL_MESH');
  const views = [];
  for (const r of preparedReferences(references, 384, seed)) {
    abort(signal);
    const metrics = silhouetteMetrics(r.mask, rasterizeSilhouette(seed.vertices, seed.faces, r.side, r.camera, r.size), r.size);
    views.push({ side: r.side, ...metrics, segmentation: r.reliability ?? null, frame: r.frame ?? null,
      camera: { ...r.camera, projection: 'orthographic', calibration: 'frozen-bbox-height-assumption', rasterSize: r.size },
      meetsSilhouetteTarget: metrics.iou >= .95 && metrics.boundaryP95 != null && metrics.boundaryP95 <= .015 });
    onProgress?.({ stage: 'checking', progress: .80 + .12 * views.length / references.length, message: '校验实际衣片轮廓与四个开口' });
    await yieldFrame();
  }
  const missingViews = SIDES.filter(side => !references.some(r => r.side === side)), consistency = referenceConsistency(references, seed);
  const warnings = ['按正面衣片边界建立前后曲面，胸背由侧面支撑；不可见接缝仍为推测。',
    '侧面保留严格 90° 正交评分与真实袖口；生成参考若带透视或偏转，轮廓分数会受影响。'];
  if (consistency.some(pair => pair.conditionalMaxMinIoU < .95)) warnings.push('对向参考存在轮廓冲突，未通过扭曲衣片或封住袖口来提高分数。');
  if (missingViews.length) warnings.push('缺少方向的结构未完成图片校准。');
  return { schemaVersion: 1, algorithm: ALGORITHM, sourceImages: Object.fromEntries(references.map(r => [r.side, r.id])),
    vertices: seed.vertices, faces: seed.faces,
    quality: { status: missingViews.length || views.some(v => v.iou < .8) ? 'NEEDS_REFERENCE' : 'COARSE',
      views, missingViews, checks, warnings, referenceConsistency: consistency, elapsedMs: Math.round(now() - started),
      targetIoU: .95, allViewsMeetTarget: !missingViews.length && views.every(v => v.meetsSilhouetteTarget),
      validationResolution: 384, fitMethod: 'image-contour-sewn-panels-cotangent-support',
      seedFamily: seed.geometryVersion?.startsWith('image-defined-round-neck') ? 'image-defined-round-neck-panels' : seed.geometryVersion?.startsWith('image-defined-symmetric-top') ? 'image-defined-symmetric-top-panels' : 'image-defined-cap-panels', representation: 'sewn-front-back-panels',
      panelEvidence: seed.panelEvidence, dimensionsMeasured: false } };
}

/** Score all actual cloth parts against the original full masks with the frozen outer-fit cameras. */
export function finalizeLayeredModel(outer, seed, references, spec, decorate) {
  const model = decorate(outer, spec);
  const refs = preparedReferences(references, 384, seed);
  const views = outer.quality.views.map(view => {
    const ref = refs.find(r => r.side === view.side);
    const metrics = silhouetteMetrics(ref.mask, rasterizeSilhouette(model.vertices, model.faces, view.side, view.camera, 384), 384);
    return { ...view, ...metrics, outerIoU: view.iou, frame: ref.frame,
      meetsSilhouetteTarget: metrics.iou >= .95 && metrics.boundaryP95 != null && metrics.boundaryP95 <= .015 };
  });
  const checks = validateMesh(model.vertices, model.faces, seed.openings);
  if (!checks.finite || !checks.topology || !checks.openings || !checks.depthPreserved)
    throw failure('分层服装模型结构校验未通过，已保留原模型', 'INVALID_LAYERED_MESH');
  model.quality = { ...model.quality, views, checks,
    status: model.quality.missingViews.length || views.some(v => v.iou < .8) ? 'NEEDS_REFERENCE' : 'COARSE',
    allViewsMeetTarget: model.quality.missingViews.length === 0 && views.every(v => v.meetsSilhouetteTarget),
    metricScope: 'complete-layered-mesh-raster-against-input-masks-frozen-cameras',
    representation: 'outer-shell-inner-cloth-and-finite-width-strap',
    warnings: ['已根据输入图分别建立外层、内层下摆和有宽度的肩带；隐藏接缝与内层走向仍为推测。',
      '轮廓分数是完整分层模型对输入图的重投影；褶皱、面料和实物尺寸尚未校准。',
      ...model.quality.warnings.filter(w => w.includes('对向参考') || w.includes('90°'))] };
  return { schemaVersion: model.schemaVersion, algorithm: model.algorithm, sourceImages: model.sourceImages,
    vertices: model.vertices, faces: model.faces, quality: model.quality };
}

async function readImage(url, signal) {
  const parsed = new URL(url, globalThis.location?.href);
  if (globalThis.location && parsed.origin !== globalThis.location.origin) throw failure('请使用已保存到系统的商品底图', 'EXTERNAL_IMAGE');
  const response = await fetch(parsed.href, { credentials: 'same-origin', signal });
  if (!response.ok) throw failure(`读取商品底图失败（${response.status}）`, 'IMAGE_FETCH_FAILED');
  const blob = await response.blob(); abort(signal);
  const bitmap = await createImageBitmap(blob);
  try {
    const size = 512, scale = Math.min(size / bitmap.width, size / bitmap.height);
    const width = Math.max(1, Math.round(bitmap.width * scale)), height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = typeof OffscreenCanvas === 'function' ? new OffscreenCanvas(width, height) : document.createElement('canvas');
    canvas.width = width; canvas.height = height;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    // Segment before letterboxing: real alpha is retained and rectangular images are not stretched.
    context.drawImage(bitmap, 0, 0, width, height);
    const imageData = context.getImageData(0, 0, width, height);
    return { ...segmentForeground(imageData), imageData };
  } finally { bitmap.close(); }
}

export async function buildCoarseModel(sourceImages, { onProgress, signal } = {}) {
  const started = now(); abort(signal);
  if (sourceImages && Object.entries(sourceImages).some(([side, image]) => !SIDES.includes(side) || !image?.id || !image?.url))
    throw failure('商品底图信息不完整，请重新保存后生成', 'INVALID_SOURCE_IMAGES');
  const supplied = SIDES.filter(side => sourceImages?.[side]?.url && sourceImages?.[side]?.id);
  if (!supplied.some(side => side === 'FRONT' || side === 'BACK')) throw failure('请先保存正面或背面的纯色底图', 'MISSING_FRONT_BACK');
  onProgress?.({ stage: 'loading', progress: .03, message: '读取商品底图与服装结构' });
  const references = [];
  for (const side of supplied) {
    abort(signal); onProgress?.({ stage: 'segmenting', progress: .07 + .16 * references.length / supplied.length, message: `识别${{ FRONT: '正面', BACK: '背面', LEFT: '左侧', RIGHT: '右侧' }[side]}完整轮廓` });
    references.push({ ...(await readImage(sourceImages[side].url, signal)), side, id: sourceImages[side].id });
    await yieldFrame();
  }
  let result;
  const { detectLayeredGarment, outerMaskForColor } = await import('./garment-layer-detection.js');
  const front = references.find(r => r.side === 'FRONT');
  const layers = front ? detectLayeredGarment(front.imageData, front) : { detected: false };
  const structureEvidence = garmentStructureEvidence(references);
  if (layers.detected) {
    onProgress?.({ stage: 'structure', progress: .24, message: '已识别分层衣片，建立斜领、袖口、内层与肩带' });
    const outerReferences = references.map(r => {
      const mask = r.side === 'FRONT' ? layers.outerMask : outerMaskForColor(r.imageData, r, layers.spec.outerColor);
      if (!mask) throw failure('无法可靠区分外层衣片，请补充同款一致视图', 'AMBIGUOUS_LAYERS');
      const b = maskBounds(mask, r.width, r.height);
      return { ...r, mask, bounds: b, frame: { left: b.left / r.width, top: b.top / r.height, width: b.width / r.width, height: b.height / r.height } };
    });
    const { buildCapPanels } = await import('./garment-panels.js');
    const { detectSideArmhole } = await import('./garment-armhole-detection.js');
    const holes = references.filter(r => r.side === 'LEFT' || r.side === 'RIGHT').flatMap(r => {
      const detected = detectSideArmhole(r.imageData, r, layers.spec.outerColor);
      if (!detected.detected) return [];
      const outer = outerReferences.find(o => o.side === r.side), relative = detected.outerRelative;
      return [{ top: 1 - relative.top, bottom: 1 - relative.bottom,
        halfDepth: relative.width * outer.bounds.width / outer.bounds.height / 2 }];
    });
    const average = key => holes.reduce((sum, h) => sum + h[key], 0) / holes.length;
    const armholes = holes.length ? { topFraction: clamp(average('top'), .67, .90), bottomFraction: clamp(average('bottom'), .37, .65),
      halfDepthFraction: clamp(average('halfDepth'), .04, .10) } : undefined;
    const panels = await buildCapPanels(outerReferences, { onProgress, signal, armholes });
    const outer = await scorePanelModel(panels, outerReferences, { onProgress, signal });
    abort(signal);
    const { addGarmentLayers } = await import('./garment-layered.js');
    result = finalizeLayeredModel(outer, panels, references, layers.spec, addGarmentLayers);
  } else if (front && structureEvidence.family === 'SYMMETRIC_UPPER_OUTLINE') {
    onProgress?.({ stage: 'structure', progress: .24, message: '识别两侧袖口，保留上衣边界并建立连续胸背支撑' });
    const { buildCapPanels } = await import('./garment-panels.js');
    const panels = await buildCapPanels(references, { onProgress, signal, construction: 'symmetric-top' });
    result = await scorePanelModel(panels, references, { onProgress, signal });
    result.quality.structureEvidence = structureEvidence;
    result.quality.warnings.push('上部侧面可能被袖子遮挡，胸背厚度由下部无遮挡衣身估计；不把袖子宽度当作胸背尺寸。');
    result.quality.warnings.push('对称上轮廓不代表已识别领型；本次保留输入上边界，隐藏领口与袖筒形态仍为推测。');
  } else {
    const [seed, alternateSeed] = await Promise.all(['./garment-seed.json', './garment-seed-offshoulder.json'].map(async path => {
      const response = await fetch(new URL(path, import.meta.url), { signal });
      if (!response.ok) throw failure('服装结构文件加载失败，请刷新重试', 'SEED_FETCH_FAILED');
      return response.json();
    }));
    result = await fitCoarseFromMasks(seed, references, { onProgress, signal, seedCandidates: [alternateSeed] });
  }
  result.quality.elapsedMs = Math.round(now() - started);
  onProgress?.({ stage: 'complete', progress: 1, message: '服装结构已生成并校验' });
  return result;
}
