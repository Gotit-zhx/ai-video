import { createGpuSilhouetteEvaluator, probeWebGPU, GpuSilhouetteError } from './gpu-silhouette.js';

const startButton = document.querySelector('#start');
const statusElement = document.querySelector('#status');
const progressElement = document.querySelector('#progress');
const detailsElement = document.querySelector('#details');
const resultLink = document.querySelector('#result-link');
const state = { phase: 'probing', capability: null, jobId: null, completedTasks: 0, evaluatedCandidates: 0, serviceStatus: null, error: null, lastGpuError: null, lastBatch: null };
let evaluator = null, running = false;

function show(message) {
  statusElement.textContent = message;
  progressElement.textContent = state.jobId ? `任务 ${state.jobId} · 已处理 ${state.completedTasks} 个任务 / ${state.evaluatedCandidates} 个候选` : '';
  detailsElement.textContent = JSON.stringify(state, null, 2);
}

async function request(path, body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 25000);
  try {
    const response = await fetch(path, { method: body === undefined ? 'GET' : 'POST', headers: body === undefined ? undefined : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store', signal: controller.signal });
    const data = await response.json();
    if (!response.ok) throw new Error(`本地服务 HTTP ${response.status}: ${data.error ?? data.message ?? response.statusText}`);
    return data;
  } finally { clearTimeout(timeout); }
}

function configArguments(config) {
  if (!config || !Array.isArray(config.positions) || !Array.isArray(config.indices) || !Array.isArray(config.targetMasks) || !Array.isArray(config.weights)) throw new GpuSilhouetteError('INVALID_CONFIG', '本地服务的 GPU 配置不完整。');
  // Validate index numbers before Uint32 conversion can truncate/wrap them.
  if (!config.indices.every(value => Number.isInteger(value) && value >= 0 && value < config.positions.length / 3)) throw new GpuSilhouetteError('INVALID_CONFIG', '配置中存在非整数或越界索引。');
  return { width: config.width, height: config.height, cameras: config.cameras, positions: new Float32Array(config.positions), indices: new Uint32Array(config.indices), targetMasks: config.targetMasks.map(mask => new Uint32Array(mask)), weights: config.weights.map(weights => new Float32Array(weights)), targetAreas: config.targetAreas };
}

function showResult(serviceStatus) {
  const path = serviceStatus?.resultUrl;
  if (typeof path !== 'string') return;
  const url = new URL(path, location.href);
  if (url.origin === location.origin) { resultLink.href = url.href; resultLink.hidden = false; }
}

const ready = (async () => {
  state.capability = await probeWebGPU();
  state.phase = state.capability.supported ? 'ready' : 'unsupported';
  startButton.disabled = !state.capability.supported;
  show(state.capability.supported ? 'WebGPU 可用。点击按钮后由本地服务发起实际拟合。' : state.capability.message);
  return state.capability;
})();

export async function startGpuFitJob() {
  const capability = await ready;
  if (running) throw new GpuSilhouetteError('BUSY', '当前拟合仍在运行。');
  if (!capability.supported) throw new GpuSilhouetteError(capability.code, capability.message);
  running = true; startButton.disabled = true; resultLink.hidden = true;
  Object.assign(state, { phase: 'starting', jobId: null, completedTasks: 0, evaluatedCandidates: 0, serviceStatus: null, error: null, lastGpuError: null, lastBatch: null });
  show('正在请求本地拟合任务…');
  try {
    const start = await request('/api/gpu-fit/start', {});
    if (!start.jobId) throw new Error('本地服务未返回 jobId。');
    state.jobId = start.jobId; state.phase = 'running';
    while (running) {
      const task = await request(`/api/gpu-fit/task?jobId=${encodeURIComponent(state.jobId)}`);
      if (task.type === 'finished') {
        state.serviceStatus = task.status ?? await request(`/api/gpu-fit/status?jobId=${encodeURIComponent(state.jobId)}`);
        state.phase = state.serviceStatus.state === 'failed' || state.serviceStatus.error ? 'failed' : 'finished';
        showResult(state.serviceStatus);
        show(state.phase === 'finished' ? (state.serviceStatus.backend === 'cpu_fallback' ? '本地服务在 GPU 失败后用 CPU 重启并完成拟合；详见任务报告。' : '本地拟合流程已结束；最终结果与 CPU 验收见任务报告。') : '本地拟合未完成，详见运行记录。');
        break;
      }
      if (task.type === 'idle') { if (task.status) state.serviceStatus = task.status; show('等待本地服务生成下一组候选…'); continue; }
      if (!task.id) throw new Error('GPU 任务缺少 id。');
      try {
        if (task.type === 'configure') {
          show('正在向 GPU 配置网格、目标与距离权重…');
          evaluator?.destroy(); evaluator = null;
          evaluator = await createGpuSilhouetteEvaluator(configArguments(task.config));
          await request('/api/gpu-fit/result', { jobId: state.jobId, id: task.id, ok: true, adapter: evaluator.probe });
        } else if (task.type === 'evaluate') {
          if (!evaluator) throw new GpuSilhouetteError('NOT_CONFIGURED', '尚未收到 GPU 配置。');
          if (!Array.isArray(task.candidates) || task.candidates.some(candidate => !Array.isArray(candidate.positions))) throw new GpuSilhouetteError('INVALID_TASK', '候选位置数据不完整。');
          const batch = await evaluator.evaluateBatch(task.candidates.map(candidate => ({ positions: new Float32Array(candidate.positions) })));
          const results = batch.results.map(result => ({ scores: result.scores, timings: result.timings }));
          state.evaluatedCandidates += results.length;
          state.lastBatch = { candidates: results.length, timings: batch.timings, meanWeightedLosses: results.map(result => result.scores.reduce((sum, score) => sum + score.weightedLoss, 0) / result.scores.length) };
          await request('/api/gpu-fit/result', { jobId: state.jobId, id: task.id, ok: true, results });
        } else throw new GpuSilhouetteError('UNKNOWN_TASK', `未知 GPU 任务类型：${task.type}`);
        state.completedTasks++;
        if (state.completedTasks % 10 === 0) state.serviceStatus = await request(`/api/gpu-fit/status?jobId=${encodeURIComponent(state.jobId)}`);
        show('GPU 已提交分数，本地服务继续拟合…');
      } catch (error) {
        await request('/api/gpu-fit/result', { jobId: state.jobId, id: task.id, ok: false, code: error.code ?? 'GPU_CLIENT_ERROR', error: String(error) });
        state.lastGpuError = { code: error.code ?? 'GPU_CLIENT_ERROR', message: String(error) };
        evaluator?.destroy(); evaluator = null;
        show('GPU 任务失败，已通知本地服务；等待服务决定是否以 CPU 重启。');
      }
    }
    return structuredClone(state);
  } catch (error) {
    state.phase = 'failed'; state.error = { code: error.code ?? 'CLIENT_OR_SERVICE_ERROR', message: String(error) };
    show('拟合连接或 GPU 执行失败：' + String(error));
    return structuredClone(state);
  } finally { running = false; evaluator?.destroy(); evaluator = null; startButton.disabled = !state.capability?.supported; }
}

startButton.addEventListener('click', startGpuFitJob);
window.gpuFitClient = { start: startGpuFitJob, ready, get state() { return structuredClone(state); }, get running() { return running; } };
