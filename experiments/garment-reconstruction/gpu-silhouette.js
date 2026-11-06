// Standard WebGPU silhouette objective evaluator. The caller owns geometry fitting.
const rasterShader = `
struct Params { rotation: vec4f, image: vec4f };
@group(0) @binding(0) var<uniform> p:Params;
@vertex fn vs(@location(0) pos:vec3f)->@builtin(position) vec4f {
  let x=p.rotation.x*pos.x-p.rotation.y*pos.z;
  let depth=p.rotation.y*pos.x+p.rotation.x*pos.z;
  let y=p.rotation.z*pos.y-p.rotation.w*depth;
  return vec4f((x*p.image.x+p.image.y)/p.image.w*2.0-1.0,
    1.0-(-y*p.image.x+p.image.z)/p.image.w*2.0,0.0,1.0);
}
@fragment fn fs()->@location(0) vec4f { return vec4f(1.0); }
`;
const countShader = `
@group(0) @binding(0) var mask:texture_2d<f32>;
@group(0) @binding(1) var<storage,read> referenceMask:array<u32>;
@group(0) @binding(2) var<storage,read_write> partial:array<u32>;
@group(0) @binding(3) var<storage,read> weights:array<f32>;
@group(0) @binding(4) var<storage,read_write> partialWeights:array<f32>;
var<workgroup> counts:array<atomic<u32>,4>;
var<workgroup> weighted:array<f32,256>;
@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid:vec3u,
        @builtin(local_invocation_index) local:u32,
        @builtin(workgroup_id) group:vec3u) {
  if(local<4u){atomicStore(&counts[local],0u);} weighted[local]=0.0;workgroupBarrier();
  let dim=textureDimensions(mask);let i=gid.x;
  if(i<dim.x*dim.y){
    let rendered=textureLoad(mask,vec2i(vec2u(i%dim.x,i/dim.x)),0).r>0.5;
    let reference=referenceMask[i]>0u;
    if(rendered&&reference){atomicAdd(&counts[0],1u);}
    if(rendered||reference){atomicAdd(&counts[1],1u);}
    if(rendered!=reference){atomicAdd(&counts[2],1u);weighted[local]=weights[i];}
    if(rendered){atomicAdd(&counts[3],1u);}
  }
  workgroupBarrier();
  if(local<4u){partial[group.x*4u+local]=atomicLoad(&counts[local]);}
  for(var stride=128u;stride>0u;stride/=2u){
    if(local<stride){weighted[local]+=weighted[local+stride];}workgroupBarrier();
  }
  if(local==0u){partialWeights[group.x]=weighted[0];}
}
`;
const reduceShader = `
struct Params { view:u32, groups:u32, pixels:u32, pad:u32,
                targetArea:f32, pad1:f32, pad2:f32, pad3:f32 };
struct Score { intersection:u32, unionCount:u32, mismatch:u32, foreground:u32,
               iou:f32, pixelError:f32, weightedLoss:f32, weightedSum:f32 };
@group(0) @binding(0) var<storage,read> partial:array<u32>;
@group(0) @binding(1) var<storage,read_write> scores:array<Score>;
@group(0) @binding(2) var<uniform> p:Params;
@group(0) @binding(3) var<storage,read> partialWeights:array<f32>;
var<workgroup> counts:array<atomic<u32>,4>;
var<workgroup> weighted:array<f32,256>;
@compute @workgroup_size(256) fn main(@builtin(local_invocation_index) local:u32){
  if(local<4u){atomicStore(&counts[local],0u);} workgroupBarrier();
  var sums=vec4u(0u);var weightSum=0.0;
  for(var i=local;i<p.groups;i+=256u){
    sums+=vec4u(partial[i*4u],partial[i*4u+1u],partial[i*4u+2u],partial[i*4u+3u]);
    weightSum+=partialWeights[i];
  }
  atomicAdd(&counts[0],sums.x);atomicAdd(&counts[1],sums.y);
  atomicAdd(&counts[2],sums.z);atomicAdd(&counts[3],sums.w);weighted[local]=weightSum;workgroupBarrier();
  for(var stride=128u;stride>0u;stride/=2u){
    if(local<stride){weighted[local]+=weighted[local+stride];}workgroupBarrier();
  }
  if(local==0u){
    let a=atomicLoad(&counts[0]);let b=atomicLoad(&counts[1]);
    let c=atomicLoad(&counts[2]);let d=atomicLoad(&counts[3]);
    let iou=select(0.0,f32(a)/f32(max(b,1u)),b>0u);
    scores[p.view]=Score(a,b,c,d,iou,f32(c)/f32(p.pixels),weighted[0]/max(p.targetArea,1.0),weighted[0]);
  }
}
`;

export class GpuSilhouetteError extends Error {
  constructor(code, message) { super(message); this.name = 'GpuSilhouetteError'; this.code = code; }
}

export async function probeWebGPU() {
  if (!isSecureContext || !navigator.gpu) return { supported: false, code: 'WEBGPU_UNAVAILABLE', message: '此环境没有可用 WebGPU；请使用现有 CPU 路径。' };
  try {
    const adapter = await navigator.gpu.requestAdapter({ powerPreference: 'high-performance' });
    if (!adapter) return { supported: false, code: 'NO_GPU_ADAPTER', message: '浏览器未提供 GPU 适配器；请使用现有 CPU 路径。' };
    const info = adapter.info ?? {};
    return { supported: true, info: { vendor: info.vendor, architecture: info.architecture, device: info.device, description: info.description, isFallbackAdapter: info.isFallbackAdapter ?? null }, features: [...adapter.features] };
  } catch (error) { return { supported: false, code: 'GPU_PROBE_FAILED', message: String(error) }; }
}

/**
 * positions: Float32Array xyz, indices: Uint32Array triangles.
 * cameras: [{id,yaw,pitch,scale,cx,cy}], targetMasks: array of 0/1 Uint32Array.
 * weights: optional per-view Float32Array of 1+abs(signedDistance); defaults to 1.
 * targetAreas: optional per-view nonzero target-pixel counts; defaults to mask count.
 * Square image POC, max 16 views; caller owns segmentation/camera calibration.
 * evaluate({positions?, readbackMasks?}) uploads an optional same-size candidate.
 * evaluateBatch([{positions}, ...]) returns {results, timings} in input order.
 * Returns GPU IoU, XOR, weightedSum and weightedLoss; no boundary P95 or optimizer.
 */
export async function createGpuSilhouetteEvaluator({ positions, indices, cameras, targetMasks, weights, targetAreas, width = 512, height = 512 }) {
  const probe = await probeWebGPU();
  if (!probe.supported) throw new GpuSilhouetteError(probe.code, probe.message);
  if (width !== height || width % 64 || width < 64 || width > 2048 || !Array.isArray(cameras) || cameras.length < 1 || cameras.length > 16) throw new GpuSilhouetteError('INVALID_INPUT', 'POC 支持 64–2048、64 的整数倍正方形画幅，1–16 个方位。');
  if (!(positions instanceof Float32Array) || !(indices instanceof Uint32Array) || positions.length < 9 || indices.length < 3 || positions.length % 3 || indices.length % 3 || !positions.every(Number.isFinite) || !indices.every(i => i < positions.length / 3) || !Array.isArray(targetMasks) || targetMasks.length !== cameras.length || targetMasks.some(m => !(m instanceof Uint32Array) || m.length !== width * height)) throw new GpuSilhouetteError('INVALID_INPUT', '顶点、三角索引或目标掩膜格式不匹配，坐标须有限且索引不得越界。');
  cameras = cameras.map(camera => ({ ...camera, yaw: camera.yaw ?? 0, pitch: camera.pitch ?? 0 }));
  if (cameras.some(camera => ![camera.yaw, camera.pitch, camera.scale, camera.cx, camera.cy].every(Number.isFinite) || camera.scale <= 0)) throw new GpuSilhouetteError('INVALID_INPUT', '相机参数须有限，scale 必须大于零。');
  weights ??= targetMasks.map(mask => new Float32Array(mask.length).fill(1));
  targetAreas ??= targetMasks.map(mask => mask.reduce((sum, value) => sum + (value > 0 ? 1 : 0), 0));
  if (!Array.isArray(weights) || weights.length !== cameras.length || weights.some(values => !(values instanceof Float32Array) || values.length !== width * height || !values.every(value => Number.isFinite(value) && value >= 0)) || !Array.isArray(targetAreas) || targetAreas.length !== cameras.length || !targetAreas.every(area => Number.isFinite(area) && area >= 0)) throw new GpuSilhouetteError('INVALID_INPUT', 'weights 必须为逐视角非负有限 Float32Array，targetAreas 必须为非负有限值。');
  const adapter = await navigator.gpu.requestAdapter({ powerPreference: 'high-performance' });
  if (!adapter) throw new GpuSilhouetteError('NO_GPU_ADAPTER', 'GPU 适配器不可用；请使用 CPU 路径。');
  let device;
  try { device = await adapter.requestDevice(); } catch (error) { throw new GpuSilhouetteError('GPU_DEVICE_REQUEST_FAILED', String(error)); }
  let lost = null, destroyed = false, busy = false;
  const errors = [];
  device.lost.then(info => { if (!destroyed) lost = info.message || info.reason; });
  device.addEventListener('uncapturederror', event => errors.push(String(event.error)));
  const resources = [], track = value => (resources.push(value), value);
  try {
  const buffer = (size, usage, data) => { const b = track(device.createBuffer({ size, usage })); if (data) device.queue.writeBuffer(b, 0, data); return b; };
  const count = cameras.length, pixels = width * height, groups = Math.ceil(pixels / 256), scoreBytes = count * 32;
  const vertex = buffer(positions.byteLength, GPUBufferUsage.VERTEX | GPUBufferUsage.COPY_DST, positions);
  const index = buffer(indices.byteLength, GPUBufferUsage.INDEX | GPUBufferUsage.COPY_DST, indices);
  const scoreBuffer = buffer(scoreBytes, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_SRC);
  const scoreReadback = buffer(scoreBytes, GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST);
  const rasterModule = device.createShaderModule({ code: rasterShader });
  const raster = await device.createRenderPipelineAsync({ layout: 'auto', vertex: { module: rasterModule, entryPoint: 'vs', buffers: [{ arrayStride: 12, attributes: [{ shaderLocation: 0, offset: 0, format: 'float32x3' }] }] }, fragment: { module: rasterModule, entryPoint: 'fs', targets: [{ format: 'rgba8unorm' }] }, primitive: { topology: 'triangle-list', cullMode: 'none' } });
  const compile = async (code, label) => {
    const module = device.createShaderModule({ code, label });
    const info = await module.getCompilationInfo();
    const failures = info.messages.filter(message => message.type === 'error');
    if (failures.length) { resources.forEach(resource => resource.destroy()); destroyed = true; device.destroy(); throw new GpuSilhouetteError('SHADER_ERROR', failures.map(message => `${label}:${message.lineNum} ${message.message}`).join('; ')); }
    return module;
  };
  const countPipeline = await device.createComputePipelineAsync({ layout: 'auto', compute: { module: await compile(countShader, 'count'), entryPoint: 'main' } });
  const reducePipeline = await device.createComputePipelineAsync({ layout: 'auto', compute: { module: await compile(reduceShader, 'reduce'), entryPoint: 'main' } });
  const bind = (pipeline, entries) => device.createBindGroup({ layout: pipeline.getBindGroupLayout(0), entries: entries.map((resource, binding) => ({ binding, resource })) });
  const views = cameras.map((camera, viewIndex) => {
    const yaw = camera.yaw * Math.PI / 180, pitch = camera.pitch * Math.PI / 180;
    const uniform = buffer(32, GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST, new Float32Array([Math.cos(yaw), Math.sin(yaw), Math.cos(pitch), Math.sin(pitch), camera.scale, camera.cx, camera.cy, width]));
    const reductionParams = new ArrayBuffer(32);
    new Uint32Array(reductionParams).set([viewIndex, groups, pixels, 0]);
    new Float32Array(reductionParams)[4] = targetAreas[viewIndex];
    const reductionUniform = buffer(32, GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST, reductionParams);
    const texture = track(device.createTexture({ size: [width, height], format: 'rgba8unorm', usage: GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_SRC }));
    const textureView = texture.createView();
    const target = buffer(pixels * 4, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST, targetMasks[viewIndex]);
    const partial = buffer(groups * 16, GPUBufferUsage.STORAGE);
    const weightBuffer = buffer(pixels * 4, GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST, weights[viewIndex]);
    const partialWeights = buffer(groups * 4, GPUBufferUsage.STORAGE);
    return { texture, textureView, rasterBind: bind(raster, [{ buffer: uniform }]), countBind: bind(countPipeline, [textureView, { buffer: target }, { buffer: partial }, { buffer: weightBuffer }, { buffer: partialWeights }]), reduceBind: bind(reducePipeline, [{ buffer: partial }, { buffer: scoreBuffer }, { buffer: reductionUniform }, { buffer: partialWeights }]) };
  });
  await device.queue.onSubmittedWorkDone();
  const check = () => { if (destroyed || lost || errors.length) throw new GpuSilhouetteError(destroyed ? 'DESTROYED' : lost ? 'DEVICE_LOST' : 'GPU_ERROR', lost || errors.join('; ') || 'GPU evaluator 已销毁。'); };
  return {
    probe,
    get status() { return { available: !destroyed && !lost && !errors.length, lost, errors: [...errors] }; },
    async evaluate({ positions: candidate, readbackMasks = false } = {}) {
      check();
      if (busy) throw new GpuSilhouetteError('BUSY', '同一 evaluator 按顺序提交候选。');
      if (candidate && (!(candidate instanceof Float32Array) || candidate.byteLength !== positions.byteLength || !candidate.every(Number.isFinite))) throw new GpuSilhouetteError('INVALID_INPUT', '候选顶点数必须与初始化网格一致，所有坐标须有限。');
      busy = true;
      try {
        const start = performance.now();
        if (candidate) device.queue.writeBuffer(vertex, 0, candidate);
        const uploadCallMs = performance.now() - start, encodeStart = performance.now();
        const encoder = device.createCommandEncoder();
        for (const view of views) {
          const pass = encoder.beginRenderPass({ colorAttachments: [{ view: view.textureView, clearValue: { r: 0, g: 0, b: 0, a: 1 }, loadOp: 'clear', storeOp: 'store' }] });
          pass.setPipeline(raster); pass.setBindGroup(0, view.rasterBind); pass.setVertexBuffer(0, vertex); pass.setIndexBuffer(index, 'uint32'); pass.drawIndexed(indices.length); pass.end();
          const compute = encoder.beginComputePass();
          compute.setPipeline(countPipeline); compute.setBindGroup(0, view.countBind); compute.dispatchWorkgroups(groups);
          compute.setPipeline(reducePipeline); compute.setBindGroup(0, view.reduceBind); compute.dispatchWorkgroups(1); compute.end();
        }
        encoder.copyBufferToBuffer(scoreBuffer, 0, scoreReadback, 0, scoreBytes);
        const command = encoder.finish(), encodeMs = performance.now() - encodeStart, submitStart = performance.now();
        device.queue.submit([command]);
        // mapAsync resolves after all writes/copies to this buffer are complete.
        await scoreReadback.mapAsync(GPUMapMode.READ);
        const completedAndScoreMapMs = performance.now() - submitStart;
        const raw = scoreReadback.getMappedRange().slice(0); scoreReadback.unmap();
        const integers = new Uint32Array(raw), floats = new Float32Array(raw);
        const scores = cameras.map((camera, i) => ({ id: camera.id, intersection: integers[i * 8], union: integers[i * 8 + 1], mismatch: integers[i * 8 + 2], foreground: integers[i * 8 + 3], iou: floats[i * 8 + 4], pixelError: floats[i * 8 + 5], weightedLoss: floats[i * 8 + 6], weightedSum: floats[i * 8 + 7] }));
        if (scores.some(score => !Number.isFinite(score.weightedLoss) || !Number.isFinite(score.weightedSum))) throw new GpuSilhouetteError('NONFINITE_GPU_SCORE', 'GPU 加权归约溢出或产生非有限值。');
        const timings = { uploadCallMs, encodeMs, completedAndScoreMapMs, totalMs: performance.now() - start, uploadedBytes: candidate?.byteLength ?? 0, scoreReadbackBytes: scoreBytes };
        let masks = null, validationReadbackMs = null;
        if (readbackMasks) {
          const validationStart = performance.now();
          const readback = device.createBuffer({ size: pixels * 4 * count, usage: GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST });
          const copy = device.createCommandEncoder();
          views.forEach((view, i) => copy.copyTextureToBuffer({ texture: view.texture }, { buffer: readback, offset: pixels * 4 * i, bytesPerRow: width * 4, rowsPerImage: height }, { width, height }));
          device.queue.submit([copy.finish()]); await readback.mapAsync(GPUMapMode.READ);
          const rgba = new Uint8Array(readback.getMappedRange());
          masks = cameras.map((_, i) => { const m = new Uint8Array(pixels); for (let p = 0; p < pixels; p++) m[p] = rgba[(i * pixels + p) * 4] > 0 ? 1 : 0; return m; });
          readback.unmap(); readback.destroy(); validationReadbackMs = performance.now() - validationStart;
        }
        check();
        return { scores, timings, masks, validationReadbackMs };
      } catch (error) {
        if (error instanceof GpuSilhouetteError) throw error;
        throw new GpuSilhouetteError(lost ? 'DEVICE_LOST' : 'GPU_EVALUATION_FAILED', lost || String(error));
      } finally { busy = false; }
    },
    async evaluateBatch(candidates) {
      if (!Array.isArray(candidates) || candidates.length < 1 || candidates.length > 64) throw new GpuSilhouetteError('INVALID_INPUT', '每批支持 1–64 个候选。');
      const start = performance.now(), results = [];
      for (const candidate of candidates) results.push(await this.evaluate(candidate));
      return { results, timings: { totalMs: performance.now() - start, candidateCount: results.length, execution: 'sequential GPU evaluations in input order' } };
    },
    destroy() { if (!destroyed) { destroyed = true; resources.forEach(resource => resource.destroy()); device.destroy(); } },
  };
  } catch (error) {
    destroyed = true; resources.forEach(resource => resource.destroy()); device.destroy();
    throw error instanceof GpuSilhouetteError ? error : new GpuSilhouetteError('GPU_INITIALIZATION_FAILED', String(error));
  }
}
