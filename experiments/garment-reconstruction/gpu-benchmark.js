import { createGpuSilhouetteEvaluator, probeWebGPU } from './gpu-silhouette.js';

const root = '/output/garment-detail-20260911/';
const status = document.querySelector('#status'), output = document.querySelector('#result'), button = document.querySelector('#run');
const stats = values => { const a = [...values].sort((x, y) => x - y); return { min: a[0], median: a[Math.floor(a.length / 2)], p95: a[Math.min(a.length - 1, Math.floor(a.length * .95))], max: a.at(-1), mean: a.reduce((x, y) => x + y, 0) / a.length }; };
const hash = async bytes => [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(x => x.toString(16).padStart(2, '0')).join('');
async function loadMask(path) {
  const response = await fetch(path); if (!response.ok) throw new Error(`目标掩膜 HTTP ${response.status}`);
  const image = await createImageBitmap(await response.blob());
  const canvas = new OffscreenCanvas(512, 512), context = canvas.getContext('2d', { willReadFrequently: true }); context.drawImage(image, 0, 0, 512, 512); image.close();
  const bytes = context.getImageData(0, 0, 512, 512).data;
  return Uint32Array.from({ length: 512 * 512 }, (_, i) => bytes[i * 4] > 127 ? 1 : 0);
}
export async function runBenchmark() {
  button.disabled = true; output.textContent = ''; let evaluator;
  try {
    const capability = await probeWebGPU();
    if (!capability.supported) throw new Error(`${capability.code}: ${capability.message} 此独立 POC 不自动运行 CPU 任务。`);
    status.textContent = '读取实际网格与固定目标掩膜…';
    const [meshResponse, reportResponse] = await Promise.all([fetch(root + 'sleeve-detail.json', { cache: 'no-store' }), fetch(root + 'detail-validation.json', { cache: 'no-store' })]);
    const bytes = await meshResponse.arrayBuffer(), mesh = JSON.parse(new TextDecoder().decode(bytes)), report = await reportResponse.json(), meshSha256 = await hash(bytes);
    if (meshSha256 !== report.mesh.sha256) throw new Error('当前网格与独立报告 SHA-256 不匹配。');
    const cameras = report.views.map(v => ({ id: v.id, ...v.inspectionCamera }));
    const targetPaths = report.views.map(v => v.assets.mask);
    const targetMasks = await Promise.all(targetPaths.map(loadMask));
    const positions = new Float32Array(mesh.vertices.flat()), indices = new Uint32Array(mesh.faces.flat());
    const setupStart = performance.now();
    evaluator = await createGpuSilhouetteEvaluator({ positions, indices, cameras, targetMasks });
    const setupMs = performance.now() - setupStart;
    status.textContent = 'GPU 计算并用 CPU 核对同一 GPU 掩膜…';
    const validation = await evaluator.evaluate({ readbackMasks: true });
    const cpuStart = performance.now();
    const comparisons = validation.masks.map((mask, view) => {
      let intersection = 0, union = 0, mismatch = 0, foreground = 0;
      for (let p = 0; p < mask.length; p++) { const a = mask[p] > 0, b = targetMasks[view][p] > 0; intersection += a && b; union += a || b; mismatch += a !== b; foreground += a; }
      const gpu = validation.scores[view], iou = union ? intersection / union : 0, pixelError = mismatch / mask.length;
      return { id: cameras[view].id, gpu, cpu: { intersection, union, mismatch, foreground, iou, pixelError }, integerCountsExact: intersection === gpu.intersection && union === gpu.union && mismatch === gpu.mismatch && foreground === gpu.foreground, maxFloatDifference: Math.max(Math.abs(iou - gpu.iou), Math.abs(pixelError - gpu.pixelError)) };
    });
    const cpuSameMaskScoringMs = performance.now() - cpuStart;
    if (comparisons.some(c => !c.integerCountsExact || c.maxFloatDifference > 1e-7)) throw new Error('GPU 归约与 CPU 同掩膜核对失败。');
    const edgeEvaluator = await createGpuSilhouetteEvaluator({ positions, indices, cameras: [cameras[0], cameras[0]], targetMasks: [new Uint32Array(512 * 512), new Uint32Array(512 * 512).fill(1)] });
    let edgeCases;
    try {
      const base = (await edgeEvaluator.evaluate()).scores;
      const offscreen = positions.slice(); for (let i = 0; i < offscreen.length; i += 3) offscreen[i] += 10;
      const empty = (await edgeEvaluator.evaluate({ positions: offscreen })).scores;
      const nan = positions.slice(); nan[0] = NaN; let nonfiniteRejected = false;
      try { await edgeEvaluator.evaluate({ positions: nan }); } catch (error) { nonfiniteRejected = error.code === 'INVALID_INPUT'; }
      edgeCases = { emptyTargetRealMesh: base[0], fullTargetRealMesh: base[1], emptyTargetOffscreenMesh: empty[0], fullTargetOffscreenMesh: empty[1], nonfiniteCandidateRejected: nonfiniteRejected,
        passed: base[0].intersection === 0 && base[0].union === base[0].foreground && base[0].mismatch === base[0].foreground && base[1].union === 512 * 512 && base[1].intersection === base[1].foreground && base[1].mismatch === 512 * 512 - base[1].foreground && empty[0].union === 0 && empty[0].iou === 0 && empty[0].pixelError === 0 && empty[1].intersection === 0 && empty[1].union === 512 * 512 && empty[1].iou === 0 && empty[1].pixelError === 1 && nonfiniteRejected };
      if (!edgeCases.passed) throw new Error('空/全目标、画外候选或无效输入检查失败。');
    } finally { edgeEvaluator.destroy(); }
    for (let i = 0; i < 5; i++) await evaluator.evaluate();
    const steadySamples = [];
    for (let i = 0; i < 20; i++) steadySamples.push((await evaluator.evaluate()).timings);
    status.textContent = '测量五个内存候选，包括每次位置上传…';
    const factors = [.99, .995, 1, 1.005, 1.01];
    const candidates = factors.map(factor => { const p = positions.slice(); for (let i = 0; i < p.length; i += 3) p[i] *= factor; return p; });
    const batchSamples = [], candidateScores = [];
    for (let repeat = 0; repeat < 5; repeat++) {
      const start = performance.now(), candidateTimings = [];
      for (let i = 0; i < candidates.length; i++) { const r = await evaluator.evaluate({ positions: candidates[i] }); candidateTimings.push(r.timings); if (repeat === 0) candidateScores.push({ widthScale: factors[i], scores: r.scores }); }
      batchSamples.push({ repeat, fiveCandidateMs: performance.now() - start, candidateTimings });
    }
    const result = { schemaVersion: 1, status: 'POC_VERIFIED_NOT_FULL_FIT', timestamp: new Date().toISOString(), mesh: { sha256: meshSha256, vertices: mesh.vertices.length, faces: mesh.faces.length }, capability, resolution: [512, 512], views: cameras.length, targetPaths, targetScope: 'Fixed existing CPU-rendered masks used as numeric benchmark targets, not real garment ground truth. Reference-mask fit accuracy is not established here.', setupMs, scoreReadbackBytesPerCandidate: cameras.length * 32, validation: { comparisons, passed: true, cpuSameMaskScoringMs, fullMaskReadbackMs: validation.validationReadbackMs }, warmups: 5, steadySamples, summary: { steadyTenViewScoreMs: stats(steadySamples.map(s => s.totalMs)), fiveSequentialCandidatesWithUploadMs: stats(batchSamples.map(s => s.fiveCandidateMs)), candidateUploadBytes: positions.byteLength }, batchSamples, candidateScores, limitations: ['Standard WebGPU; actually tested only on the current adapter, not Windows hardware.', 'GPU mask coverage differs from OpenCV; exact CPU/GPU score agreement here refers to the same GPU-generated mask.', 'Only IoU and normalized XOR pixel error; no boundary P95, segmentation, optimizer or convergence test.', 'Candidates are five bounded in-memory x-scale perturbations; no mesh files are modified.', 'No automatic CPU fallback in this page: unavailable/device-lost conditions display readable failure; existing CPU implementation remains separate.'] };
    result.edgeCases = edgeCases;
    window.gpuBenchmarkResult = result; output.textContent = JSON.stringify(result, null, 2); status.textContent = '完成：CPU 同掩膜核对与边界案例通过。基准不等于完整拟合或同等还原精度。'; return result;
  } catch (error) { const failure = { status: 'UNSUPPORTED_OR_FAILED', code: error.code ?? null, error: String(error) }; window.gpuBenchmarkResult = failure; status.textContent = '未完成 GPU 基准：' + String(error); output.textContent = JSON.stringify(failure, null, 2); return failure; }
  finally { evaluator?.destroy(); button.disabled = false; }
}
window.runGpuBenchmark = runBenchmark;
button.addEventListener('click', runBenchmark);
