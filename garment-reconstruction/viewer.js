import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';

const query = new URLSearchParams(location.search);
const version = query.get('version') === '1' ? 1 : 2;
const artifactFolders = { 1: 'garment-validation-20260911', 2: 'garment-upgrade-20260911' };
const artifactRoot = new URL(`../output/${artifactFolders[version]}/`, import.meta.url);
const multiviewRoot = new URL('../output/garment-multiview-20260911/', import.meta.url);
const detailRoot = new URL('../output/garment-detail-20260911/', import.meta.url);
async function loadGpuRepair() {
  try {
    const response = await fetch(new URL('../output/garment-gpu-repair-latest.json', import.meta.url), { cache: 'no-store' });
    if (!response.ok) return null;
    const data = await response.json();
    if (data.schemaVersion !== 1 || !/^[a-f0-9]{64}$/.test(data.meshSha256 ?? '')) return null;
    for (const key of ['meshUrl', 'reportUrl', 'fitUrl']) {
      const url = new URL(data[key], location.href);
      if (url.origin !== location.origin || !url.pathname.startsWith('/output/garment-gpu-repair-runs/')) return null;
    }
    return data;
  } catch { return null; }
}
const gpuRepair = version === 2 ? await loadGpuRepair() : null;
const standardViews = Object.freeze({
  front: { label: '正面', yaw: 0, pitch: 0 },
  back: { label: '背面', yaw: 180, pitch: 0 },
  right: { label: '右侧', yaw: 90, pitch: 0 },
  left: { label: '左侧', yaw: 270, pitch: 0 },
  top: { label: '俯视', yaw: 0, pitch: 90 },
  bottom: { label: '仰视', yaw: 0, pitch: -90 },
  'front-right': { label: '前右斜视', yaw: 45, pitch: 12 },
  'back-right': { label: '后右斜视', yaw: 135, pitch: 12 },
  'back-left': { label: '后左斜视', yaw: 225, pitch: 12 },
  'front-left': { label: '前左斜视', yaw: 315, pitch: 12 },
});
const modelNames = version === 2 ? {
  'real-before': '上一轮拟合基线',
  'real-after': '升级拟合',
  'sleeve-detail': '袖子细节候选 · 未验收',
  ...(gpuRepair ? { 'gpu-repaired': gpuRepair.modelLabel || '胸部修复 · GPU 拟合' } : {}),
} : {
  'real-before': '初始网格',
  'real-after': '参考拟合',
  'synthetic-target': '受控目标',
  'synthetic-after': '受控拟合',
  'defect-flat': '压扁反例 · 故意错误的测试样本',
  'defect-neck-cap': '封领口反例 · 故意错误的测试样本',
};
const stage = document.querySelector('#stage');
const overlay = document.querySelector('#overlay');
const meshInfo = document.querySelector('#mesh-info');
const select = document.querySelector('#model-select');
select.replaceChildren(...Object.entries(modelNames).map(([name, label]) => {
  const option = document.createElement('option');
  option.value = name;
  option.textContent = label;
  return option;
}));
document.querySelectorAll('[data-version]').forEach((link) => {
  if (Number(link.dataset.version) === version) link.setAttribute('aria-current', 'page');
  else link.removeAttribute('aria-current');
});
document.querySelector('#version-note').textContent = `v${version} · 固定显示尺度`;
document.querySelector('h1').textContent = version === 2 ? '服装几何 · 升级验证' : '服装几何 · 方法验证';
document.title = `服装几何 · v${version} 验证`;
document.querySelector('#summary-title').textContent = `v${version} 运行摘要`;
document.querySelector('#summary-source').href = new URL('summary.json', artifactRoot).href;
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(32, 1, 0.001, 1000);
let renderer;
try {
  renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
} catch (error) {
  overlay.classList.add('error');
  overlay.textContent = `无法初始化 3D 查看器：${error.message}`;
  throw error;
}
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
renderer.setClearColor(0x1c1d20);
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.2;
renderer.domElement.setAttribute('aria-label', '可拖动旋转的服装网格');
stage.prepend(renderer.domElement);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.09;
controls.target.set(0, 0, 0);
scene.add(new THREE.HemisphereLight(0xffffff, 0x555968, 1.4));
for (const [position, intensity] of [[[-3, 5, 4], 3.3], [[4, 1, 3], 1.2], [[0, 4, -4], 2]]) {
  const light = new THREE.DirectionalLight(0xffffff, intensity);
  light.position.set(...position);
  scene.add(light);
}
const grayMaterial = new THREE.MeshStandardMaterial({ color: 0x959595, roughness: 0.84, metalness: 0, side: THREE.DoubleSide });
const wireMaterial = new THREE.MeshBasicMaterial({ color: 0xf6f6f6, wireframe: true, side: THREE.DoubleSide });
const openingMaterial = new THREE.LineBasicMaterial({ color: 0xffffff, depthTest: false, transparent: true, opacity: 0.9 });
let model = null;
let surface = null;
let openingLines = null;
let activeName = Object.hasOwn(modelNames, query.get('model')) ? query.get('model') : gpuRepair ? 'gpu-repaired' : 'real-after';
let mode = 'gray';
let ready = false;
let lastError = null;
let loadSerial = 0;
let whenReady = Promise.resolve(null);
let frameDistance = 3;
// The same fixed framing is used across versions and every model. These are
// display constants, not measured dimensions or per-model normalization.
const modelRadius = 1.0;
const displayCenter = new THREE.Vector3(0, 0.65, 0);
let view = 'front-right';
let viewPitch = 12;
let isFreeView = false;
let multiviewState = { status: 'idle', summary: null, error: null };
let multiviewSerial = 0;
let summarySerial = 0;
let summaryState = { status: 'idle', summary: null, error: null };
let detailFocus = null;
const detailLabels = { 'left-cuff': '左袖口内侧', 'right-cuff': '右袖口内侧', 'left-root': '左袖根', 'right-root': '右袖根', 'left-inner': '左内缝交点', 'right-inner': '右内缝交点' };
const isDetailCandidate = () => activeName === 'sleeve-detail';
const isGpuRepair = () => activeName === 'gpu-repaired';
const isIndependentCandidate = () => isDetailCandidate() || isGpuRepair();
const modelURL = (name) => name === 'gpu-repaired' ? new URL(gpuRepair.meshUrl, location.href)
  : new URL(`${name}.json`, name === 'sleeve-detail' ? detailRoot : artifactRoot);
camera.position.set(1.7, 0, 2.45);

function render() {
  renderer.render(scene, camera);
}

function clearModel() {
  if (model) {
    scene.remove(model);
    model.traverse((object) => object.geometry?.dispose());
  }
  model = null;
  surface = null;
  openingLines = null;
}

function validateMesh(data) {
  if (!Array.isArray(data.vertices) || data.vertices.length < 3) throw new Error('vertices 必须为至少 3 个顶点的 Nx3 数组');
  if (!Array.isArray(data.faces) || data.faces.length === 0) throw new Error('faces 必须为非空 Mx3 数组');
  if (data.vertices.some((v) => !Array.isArray(v) || v.length !== 3 || !v.every(Number.isFinite))) throw new Error('存在无效顶点坐标');
  if (data.faces.some((f) => !Array.isArray(f) || f.length !== 3 || !f.every((i) => Number.isInteger(i) && i >= 0 && i < data.vertices.length))) throw new Error('存在无效三角面索引');
}

function extractOpeningLoops(openings, vertexCount) {
  if (!openings) return [];
  const entries = Array.isArray(openings) ? openings.map((value, i) => [String(i), value]) : Object.entries(openings);
  return entries.map(([name, value]) => {
    const indices = Array.isArray(value) ? value : value?.indices ?? value?.loop ?? value?.vertices;
    if (!Array.isArray(indices) || indices.length < 3 || !indices.every((i) => Number.isInteger(i) && i >= 0 && i < vertexCount)) {
      throw new Error(`开口 ${name} 的索引环无效`);
    }
    return { name, indices };
  });
}

function frameModel() {
  const verticalHalfFov = THREE.MathUtils.degToRad(camera.fov / 2);
  const horizontalHalfFov = Math.atan(Math.tan(verticalHalfFov) * camera.aspect);
  // Fixed reference sphere: selecting a model never refits the camera.
  frameDistance = modelRadius / Math.sin(Math.min(verticalHalfFov, horizontalHalfFov)) * 1.12;
  camera.near = Math.max(modelRadius / 1000, 0.000001);
  camera.far = Math.max(frameDistance * 30, 1);
  camera.updateProjectionMatrix();
  controls.minDistance = modelRadius * 0.2;
  controls.maxDistance = frameDistance * 8;
  setView(view, viewPitch);
}

export function setView(degrees, pitch = 0) {
  const preset = Object.hasOwn(standardViews, degrees) ? standardViews[degrees] : null;
  if (!preset && (!Number.isFinite(Number(degrees)) || !Number.isFinite(Number(pitch)) || Math.abs(Number(pitch)) > 90)) {
    throw new Error('视角必须为标准视角键，或方位角与 −90° 至 90° 的俯仰角');
  }
  const yaw = preset ? preset.yaw : Number(degrees);
  viewPitch = preset ? preset.pitch : Number(pitch);
  const matchingKey = Object.keys(standardViews).find((key) => standardViews[key].yaw === ((yaw % 360) + 360) % 360 && standardViews[key].pitch === viewPitch);
  view = preset ? String(degrees) : matchingKey ?? yaw;
  isFreeView = false;
  detailFocus = null;
  controls.minDistance = modelRadius * 0.2;
  document.querySelectorAll('[data-detail]').forEach((button) => button.setAttribute('aria-pressed', 'false'));
  controls.target.set(0, 0, 0);
  // Flush any pending damping before applying a deterministic capture view.
  const damping = controls.enableDamping;
  controls.enableDamping = false;
  controls.update();
  camera.up.set(0, 1, 0);
  if (Math.abs(viewPitch) === 90) {
    camera.position.set(0, Math.sign(viewPitch) * frameDistance, frameDistance * 0.0001);
  } else {
    const angle = THREE.MathUtils.degToRad(yaw);
    const elevation = THREE.MathUtils.degToRad(viewPitch);
    camera.position.set(Math.sin(angle) * Math.cos(elevation) * frameDistance, Math.sin(elevation) * frameDistance, Math.cos(angle) * Math.cos(elevation) * frameDistance);
  }
  controls.target.set(0, 0, 0);
  camera.lookAt(controls.target);
  controls.update();
  controls.enableDamping = damping;
  document.querySelectorAll('[data-view]').forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.view === String(view))));
  const label = standardViews[view]?.label ?? `方位 ${yaw}°`;
  document.querySelector('#view-name').textContent = `${label} · 俯仰 ${viewPitch}°`;
  renderMultiviewSelection();
  render();
  return camera.position.toArray();
}

export function focusDetail(key) {
  if (key === 'whole') return setView('front-right');
  if (!Object.hasOwn(detailLabels, key)) throw new Error(`未知局部视角：${key}`);
  if (!isDetailCandidate() || !ready || !model) throw new Error('请先载入袖子细节候选');
  const data = model.userData.meshData;
  const side = key.startsWith('left') ? 'left' : 'right';
  const isCuff = key.endsWith('cuff');
  const isInner = key.endsWith('inner');
  const innerLayer = isInner ? data.detailLayers?.find((layer) => layer.id === `${side}_inner_longitudinal_hem_junction`) : null;
  if (isInner && (!innerLayer?.vertexRange || !innerLayer.supportNormals?.length)) throw new Error('缺少内缝交点定位数据');
  const rootIds = data.semantic_regions?.[`${side}_sleeve_root`];
  const cuffIds = data.visibleCuffRims?.[`${side}_cuff`] ?? data.openings?.[`${side}_cuff`];
  const indices = isInner ? Array.from({ length: innerLayer.vertexRange[1] - innerLayer.vertexRange[0] }, (_, i) => innerLayer.vertexRange[0] + i) : isCuff ? cuffIds : rootIds;
  if (![indices, rootIds, cuffIds].every((ids) => Array.isArray(ids) && ids.length >= 3 && ids.every((i) => Number.isInteger(i) && data.vertices[i]))) throw new Error('缺少可靠局部索引，无法定位近景');
  const centerOf = (ids) => ids.reduce((sum, i) => sum.add(new THREE.Vector3(...data.vertices[i])), new THREE.Vector3()).divideScalar(ids.length);
  const localTarget = centerOf(indices);
  const radius = Math.max(isInner ? 0.028 : 0, ...indices.map((i) => new THREE.Vector3(...data.vertices[i]).distanceTo(localTarget))) * 1.25;
  const direction = isInner ? innerLayer.supportNormals.reduce((sum, normal) => sum.add(new THREE.Vector3(...normal)), new THREE.Vector3()).normalize().add(new THREE.Vector3(0, -0.35, 0.05)).normalize() : isCuff ? centerOf(cuffIds).sub(centerOf(rootIds)).normalize().add(new THREE.Vector3(0, 0, 0.38)).normalize() : new THREE.Vector3(Math.sign(localTarget.x) * 0.75, 0.12, 1).normalize();
  const halfFov = Math.min(THREE.MathUtils.degToRad(camera.fov / 2), Math.atan(Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)) * camera.aspect));
  const distance = radius / Math.sin(halfFov) * 1.1;
  model.updateMatrixWorld(true);
  const target = model.localToWorld(localTarget.clone());
  const damping = controls.enableDamping;
  controls.enableDamping = false;
  controls.update();
  controls.target.copy(target);
  camera.up.set(0, 1, 0);
  camera.position.copy(target).addScaledVector(direction, distance);
  camera.lookAt(target);
  controls.minDistance = 0.02;
  controls.update();
  controls.enableDamping = damping;
  isFreeView = true;
  detailFocus = { key, target: target.toArray(), localTarget: localTarget.toArray(), distance, radius, source: isInner ? 'detailLayers inner_longitudinal_hem_junction vertexRange and supportNormals' : isCuff ? 'visibleCuffRims (outer visible rim, not true boundary)' : 'semantic_regions sleeve_root', indices: [...indices] };
  document.querySelectorAll('[data-view]').forEach((button) => button.setAttribute('aria-pressed', 'false'));
  document.querySelectorAll('[data-detail]').forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.detail === key)));
  document.querySelector('#view-name').textContent = `${detailLabels[key]} · 推断细节观察，非 1:1 验收`;
  renderMultiviewSelection();
  render();
  return structuredClone(detailFocus);
}

export function setMode(nextMode) {
  if (!['gray', 'wire'].includes(nextMode)) throw new Error('显示模式只支持 gray 或 wire');
  mode = nextMode;
  if (surface) surface.material = mode === 'wire' ? wireMaterial : grayMaterial;
  document.querySelectorAll('[data-mode]').forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.mode === mode)));
  render();
  return mode;
}

export function selectModel(name) {
  if (!Object.hasOwn(modelNames, name)) return Promise.reject(new Error(`未知模型：${name}`));
  const serial = ++loadSerial;
  activeName = name;
  select.value = name;
  if (detailFocus) {
    detailFocus = null;
    isFreeView = true;
    document.querySelector('#view-name').textContent = '自由观察 · 保留切换前相机';
  }
  document.querySelectorAll('[data-detail]').forEach((button) => {
    button.setAttribute('aria-pressed', 'false');
    button.disabled = button.dataset.detail !== 'whole';
  });
  document.querySelector('#detail-controls').hidden = !isDetailCandidate();
  document.querySelector('#outline-section').hidden = isIndependentCandidate();
  ready = false;
  lastError = null;
  clearModel();
  overlay.hidden = false;
  overlay.classList.remove('error');
  overlay.textContent = `正在加载 ${modelNames[name]}…`;
  meshInfo.textContent = '正在读取网格数据…';
  document.querySelector('#model-name').textContent = `v${version} · ${modelNames[name]}`;
  document.querySelector('#model-source').textContent = modelURL(name).pathname;
  updateMultiviewContext();
  render();
  whenReady = (async () => {
    try {
      const response = await fetch(modelURL(name), { cache: 'no-store' });
      if (!response.ok) throw new Error(`HTTP ${response.status}，文件尚未生成或不可访问`);
      const sourceBytes = await response.arrayBuffer();
      const data = JSON.parse(new TextDecoder().decode(sourceBytes));
      const sourceSha256 = await hashBytes(sourceBytes);
      if (serial !== loadSerial) return null;
      validateMesh(data);
      if (name === 'gpu-repaired' && sourceSha256 !== gpuRepair.meshSha256) throw new Error('修复产物与发布清单不匹配');
      const loops = extractOpeningLoops(data.openings, data.vertices.length);
      const geometry = new THREE.BufferGeometry();
      geometry.setAttribute('position', new THREE.Float32BufferAttribute(data.vertices.flat(), 3));
      geometry.setIndex(data.faces.flat());
      geometry.computeVertexNormals();
      geometry.computeBoundingBox();
      geometry.computeBoundingSphere();
      if (!(geometry.boundingSphere.radius > 0)) {
        geometry.dispose();
        throw new Error('网格包围范围为零，无法显示');
      }
      model = new THREE.Group();
      model.name = name;
      model.position.copy(displayCenter).multiplyScalar(-1);
      model.userData.source = modelURL(name).href;
      model.userData.sourceSha256 = sourceSha256;
      model.userData.meshData = data;
      surface = new THREE.Mesh(geometry, mode === 'wire' ? wireMaterial : grayMaterial);
      surface.name = 'garment-surface';
      model.add(surface);
      openingLines = new THREE.Group();
      openingLines.name = 'opening-index-loops';
      openingLines.visible = document.querySelector('#openings-toggle').checked;
      for (const { name: openingName, indices } of loops) {
        const lineGeometry = new THREE.BufferGeometry().setFromPoints(indices.map((index) => new THREE.Vector3(...data.vertices[index])));
        const line = new THREE.LineLoop(lineGeometry, openingMaterial);
        line.name = openingName;
        line.renderOrder = 10;
        openingLines.add(line);
      }
      model.add(openingLines);
      scene.add(model);
      meshInfo.textContent = `${data.vertices.length.toLocaleString()} 顶点 · ${data.faces.length.toLocaleString()} 三角面 · ${loops.length} 个声明的开口环`;
      overlay.hidden = true;
      ready = true;
      document.querySelectorAll('[data-detail]').forEach((button) => { button.disabled = false; });
      updateMultiviewContext();
      if (isDetailCandidate()) renderCandidateSummary();
      if (isGpuRepair()) renderRepairSummary();
      render();
      return model;
    } catch (error) {
      if (serial !== loadSerial) return null;
      clearModel();
      lastError = error.message;
      overlay.classList.add('error');
      overlay.textContent = `产物尚未就绪：${name}.json\n${error.message}\n数据路径：${modelURL(name).pathname}\n请生成对应产物后刷新页面。`;
      overlay.style.whiteSpace = 'pre-line';
      meshInfo.textContent = '未显示网格 · 加载失败';
      updateMultiviewContext();
      render();
      return null;
    }
  })();
  refreshSummary();
  refreshMultiview();
  return whenReady;
}

export async function refreshSummary() {
  const serial = ++summarySerial;
  const candidate = isDetailCandidate();
  const repair = isGpuRepair();
  const url = repair ? new URL(gpuRepair.fitUrl, location.href)
    : new URL(candidate ? 'detail-validation.json' : 'summary.json', candidate ? detailRoot : artifactRoot);
  const status = document.querySelector('#summary-status');
  const content = document.querySelector('#summary-content');
  document.querySelector('#summary-title').textContent = repair ? '胸部修复 · 实际拟合' : candidate ? '袖子候选 · 独立校验' : `v${version} 运行摘要`;
  document.querySelector('#summary-source').href = url.href;
  document.querySelector('#summary-source').textContent = repair ? '查看实际拟合记录' : candidate ? '查看候选独立校验来源' : '查看来源 summary.json';
  summaryState = { status: 'loading', summary: null, error: null };
  status.textContent = '正在读取实际运行摘要…';
  content.hidden = true;
  content.replaceChildren();
  try {
    const response = await fetch(url, { cache: 'no-store' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const summary = await response.json();
    if (serial !== summarySerial) return null;
    if (repair) {
      if (!['GPU_DRIVEN_CHEST_REPAIR', 'SUPPORTED_CHEST_PREVIEW'].includes(summary.status) || summary.meshSha256 !== gpuRepair.meshSha256) throw new Error('修复报告与产物不匹配');
      summaryState = { status: 'ready', summary, error: null };
      renderRepairSummary();
      return summary;
    }
    if (candidate) {
      if (summary.schemaVersion !== 1 || summary.status !== 'SLEEVE_DETAIL_CANDIDATE_UNVERIFIED' || !/^[a-f0-9]{64}$/i.test(summary.mesh?.sha256 ?? '')) throw new Error('候选独立报告格式无效');
      summaryState = { status: 'ready', summary, error: null };
      renderCandidateSummary();
      return summary;
    }
    summaryState = { status: 'ready', summary, error: null };
    status.textContent = summary.real_garment_accepted === false
      ? (version === 2 ? '完整还原：未验证，未验收通过。' : '整体验收：未通过。当前结果仅为方法可行性实验。')
      : '整体验收结论需核对；此原型尚未完成还原验收。';
    if (summary.synthetic) appendViewTable(content, '受控实验 · 同族模型目标', summary.synthetic.views);
    appendViewTable(content, '商品目标 · AI 生成图 / 非实拍', summary.product_target?.views);
    if (version === 2 && typeof summary.product_target?.all_outline_candidate_thresholds_passed === 'boolean') {
      const labels = { front: '正面', back: '背面', side: '侧面', right: '右侧' };
      const views = summary.product_target.views ?? [];
      const passed = views.filter((item) => item.after?.passed_candidate_threshold === true).map((item) => labels[item.view] ?? item.view);
      const failed = views.filter((item) => item.after?.passed_candidate_threshold === false).map((item) => labels[item.view] ?? item.view);
      if (passed.length) appendText(content, 'p', `原候选轮廓检查通过：${passed.join('、')}。`);
      if (failed.length) appendText(content, 'p', `未通过：${failed.join('、')}，轮廓仍有偏差。`);
      for (const item of views.filter((item) => item.after?.passed_candidate_threshold === false && Number.isFinite(item.after?.boundary_p95_bbox))) {
        appendText(content, 'p', `${labels[item.view] ?? item.view}边界误差 P95：${item.after.boundary_p95_bbox.toFixed(5)}（归一化值）。`);
      }
      appendText(content, 'p', '原候选阈值：IoU ≥ 0.95，边界 P95 ≤ 0.015。');
    }
    appendText(content, 'p', 'IoU 为投影轮廓交并比（0–1）；不代表曲面、局部结构或材质还原质量。');
    if (Array.isArray(summary.defects) && summary.defects.length > 0) {
      appendText(content, 'h3', '注入缺陷的检查结果');
      const defectNames = { 'defect-flat': '衣身压扁至原深度的 2.5%', 'defect-neck-cap': '领口被封盖' };
      const checkNames = {
        torso_collapse_experimental: '衣身深宽比', boundary_loops: '实际开口环数量',
        actual_openings_match: '声明开口与实际边界一致性', opening_declarations: '开口声明',
        edge_orientation: '相邻面朝向', manifold_edges: '流形边', manifold_vertices: '流形顶点',
        connected_surface: '曲面连通性', degenerate_faces: '退化三角面', duplicate_faces: '重复三角面',
      };
      for (const defect of summary.defects) {
        const failures = defect.structural?.failed_checks;
        const rejected = Array.isArray(failures) && failures.length > 0 && defect.structural?.implemented_checks_passed === false;
        const paragraph = appendText(content, 'p', '', 'defect');
        appendText(paragraph, 'strong', `${defectNames[defect.name] ?? defect.name ?? '未命名缺陷'}：${rejected ? '已拒绝' : '未记录拒绝'}`);
        appendText(paragraph, 'span', rejected ? failures.map((name) => checkNames[name] ?? name).join('、') : '现有摘要未给出失败检查，不能据此认可该几何。');
      }
    }
    if (version === 2) {
      const structure = summary.product_target?.structural;
      appendText(content, 'h3', '已实现的结构检查');
      if (structure?.implemented_checks_passed === true) {
        appendText(content, 'p', '已实现的结构检查通过；仅覆盖摘要所列的局部检查范围。');
      } else if (structure?.implemented_checks_passed === false) {
        appendText(content, 'p', `存在未通过检查：${Array.isArray(structure.failed_checks) ? structure.failed_checks.join('、') : '见来源摘要'}。`);
      } else {
        appendText(content, 'p', '结构检查数据尚未就绪。');
      }
      appendText(content, 'h3', '碰撞检查');
      const collision = summary.collision;
      if (collision) {
        const collisionLabels = { passed: '限定检查范围内未检出相交。', failed: '检出三角面相交，限定碰撞检查未通过。', unevaluated: '碰撞检查仍有未决项，不能判定通过。' };
        appendText(content, 'p', collisionLabels[collision.scopeStatus] ?? '碰撞结论尚未就绪。');
        const metrics = [['受测面', collision.testedPairs], ['检出相交', collision.detectedIntersectionPairs], ['未决', collision.unresolvedPairs]]
          .filter(([, value]) => Number.isFinite(value)).map(([label, value]) => `${label} ${value.toLocaleString()} 对`);
        if (metrics.length) appendText(content, 'p', metrics.join(' · '));
        if (collision.pairBudgetExhausted === true) appendText(content, 'p', '检测达到面配对预算，仍有候选面未检查。');
        if (collision.error) appendText(content, 'p', `检测错误：${collision.error}`);
        const reportLink = appendText(content, 'a', '查看碰撞报告', 'source-link');
        reportLink.href = new URL('real-after-collision.json', artifactRoot).href;
        reportLink.target = '_blank';
        reportLink.rel = 'noopener';
      } else {
        appendText(content, 'p', '本轮碰撞检查数据尚未就绪。');
      }
      appendText(content, 'p', '碰撞范围仅为袖子与衣身的非共享索引三角面对；共享顶点邻域、自交、体积包容和布料厚度仍未覆盖。参考图为 AI 生成，相机为冻结的近似校准，完整还原仍未验收。', 'limits');
    } else {
      appendText(content, 'p', '局部穿插未评估：袖子与衣身穿插、布料自交尚无检测。相机为近似校准；细部结构、尺寸和材质还原也未完成验收。', 'limits');
    }
    content.hidden = false;
    return summary;
  } catch (error) {
    if (serial !== summarySerial) return null;
    summaryState = { status: 'not-ready', summary: null, error: error.message };
    status.textContent = `${candidate ? '候选独立校验' : `v${version} 摘要`}尚未就绪（${error.message}）。`;
    return null;
  }
}

function renderRepairSummary() {
  if (!isGpuRepair() || summaryState.status !== 'ready') return;
  const summary = summaryState.summary;
  const status = document.querySelector('#summary-status');
  const content = document.querySelector('#summary-content');
  content.hidden = true; content.replaceChildren();
  if (!model?.userData.sourceSha256 || summary.meshSha256 !== model.userData.sourceSha256) {
    status.textContent = '正在核对修复网格与报告…'; return;
  }
  const supported = !!summary.chestSupport;
  document.querySelector('#summary-title').textContent = supported ? '胸腔支撑 · 结构重建' : '胸部修复 · 实际拟合';
  status.textContent = summary.backend === 'structural_prior' ? '胸腔支撑网格已生成并检查；本次未运行 GPU 搜索。' : summary.backend === 'webgpu' ? 'GPU 已参与本轮形状拟合，最终检查已完成。' : 'GPU 中断后已完整重启 CPU 拟合。';
  appendText(content, 'p', supported ? '胸前中部和两侧由连续椭圆胸腔撑起，向领口及腰部平滑过渡。这是隐形人台形态约束，尚非真实布料模拟。' : '修复侧缝距离场在腋下终止造成的胸前凹带，保留领口与袖口的结构。');
  if (Number.isFinite(summary.fitEvaluations)) appendText(content, 'p', `实际尝试 ${summary.fitEvaluations} 个候选；GPU 评分 ${summary.gpuEvaluatedCandidates} 个；总耗时 ${summary.elapsedSeconds.toFixed(1)} 秒。`);
  if (Number.isFinite(summary.initialLoss) && Number.isFinite(summary.bestLoss)) appendText(content, 'p', `拟合目标 ${summary.initialLoss.toFixed(5)} → ${summary.bestLoss.toFixed(5)}。最终候选另用原 CPU 轮廓及结构标准复检。`);
  if (supported) appendText(content, 'p', '胸腔支撑会改变侧面轮廓；下方评分仍使用原始参考。重新 GPU 拟合会保留支撑约束。');
  if (summary.backDrape) appendText(content, 'p', '后背已收回旧腰部与下摆的过度鼓胀，保留自然弧度；后下摆同步顺形，领口和袖子保持原结构。');
  if (supported && summary.selectedCandidate === 'structural_repair_start') appendText(content, 'p', '最终保留支撑初稿：评分更好的搜索结果未满足全部形态约束，没有替换当前网格。');
  if (Number.isFinite(summary.chest?.relativeReduction)) appendText(content, 'p', `固定截面测量的异常凹带深度减少 ${(summary.chest.relativeReduction * 100).toFixed(1)}%，不代表整件服装还原率。`);
  if (summary.fallbackReason) appendText(content, 'p', `回退原因：${summary.fallbackReason}`);
  appendText(content, 'p', '正、背、左、右逐项结果见上方；上下与斜视仍保留证据不足的状态。完整服装、材质和真实尺寸尚未验收。');
  content.hidden = false;
}

function renderCandidateSummary() {
  if (!isDetailCandidate() || summaryState.status !== 'ready') return;
  const summary = summaryState.summary;
  const status = document.querySelector('#summary-status');
  const content = document.querySelector('#summary-content');
  content.hidden = true;
  content.replaceChildren();
  if (!model?.userData.sourceSha256 || summary.mesh.sha256 !== model.userData.sourceSha256) {
    status.textContent = '候选网格与独立报告尚未完成哈希匹配；暂不显示校验结论。';
    return;
  }
  status.textContent = '袖子 1:1 还原：未验收。完整服装：未验收。';
  appendText(content, 'h3', '新增内容与依据');
  appendText(content, 'p', '局部袖口翻边、袖根及袖口缝线为独立候选。可见位置参考 AI 目标图；布厚、翻折深度及隐藏路径为推断。');
  appendText(content, 'p', '已按 AI 补图追加袖口内侧纵缝与短横缝交点，属于设计假设。');
  appendText(content, 'p', summary.baseVertexAndFacePrefixPreserved === true ? '原 v2 顶点与面前缀保持不变；本候选需独立校验。' : '原基底保持情况需核对。');
  appendText(content, 'h3', '结构与碰撞');
  const failures = summary.structure?.failedChecks;
  appendText(content, 'p', summary.structure?.implementedChecksPassed === false ? `完整候选存在未通过项：${Array.isArray(failures) ? failures.map((key) => key === 'connected_surface' ? '曲面连通性（分离缝线组件）' : key).join('、') : '见报告'}。` : '结构检查范围及结果见独立报告，不能沿用 v2 的单壳结论。');
  appendText(content, 'p', '缝线条为分离闭合组件，未缝合或布尔并入原布面；袖口仅有局部内折，未建立整件双层布料实体。');
  appendText(content, 'p', summary.newLayerCollisionStatus === 'unevaluated' ? '新增层之间及与原布面的碰撞 / 接触：未评估。' : `新增层碰撞状态：${summary.newLayerCollisionStatus ?? '未记录'}；范围见来源。`);
  appendText(content, 'h3', '细节准确度');
  appendText(content, 'p', '所有方位的缝线、翻边及内部细节准确度均未评估。上方分数仅描述独立重算的外轮廓，不能作为 1:1 通过证据。');
  appendText(content, 'h3', 'AI 补充参考 · 非实拍');
  const referenceImages = appendText(content, 'div', '', 'references');
  for (const [path, caption] of [
    ['/output/garment-reference-supplement-20260911/cuff-interior.png', '已用于内缝交点 · 设计假设'],
    ['/output/garment-reference-supplement-20260911/underarm-v2/sleeve-detail-sheet.png', '已审阅设计参考 · 未映射袖根'],
  ]) {
    const figure = appendText(referenceImages, 'figure', '');
    const link = appendText(figure, 'a', '');
    link.href = localArtifactURL(path); link.target = '_blank'; link.rel = 'noopener';
    const img = document.createElement('img'); img.src = link.href; img.alt = `AI 补充参考：${caption}`; link.append(img);
    appendText(figure, 'figcaption', caption);
  }
  appendText(content, 'p', '补图不作为实物证据，也不自动纳入轮廓评分。');
  const url = localArtifactURL(summary.structure?.reportPath);
  if (url) {
    const link = appendText(content, 'a', '查看完整候选结构检查', 'source-link');
    link.href = url; link.target = '_blank'; link.rel = 'noopener';
  }
  content.hidden = false;
}

function updateMultiviewContext() {
  const context = document.querySelector('#multiview-context');
  const expectedHash = multiviewState.summary?.mesh?.sha256;
  const matches = expectedHash && model?.userData.sourceSha256 === expectedHash;
  const candidate = isIndependentCandidate();
  document.querySelector('#multiview-content').hidden = multiviewState.status !== 'ready' || (candidate && !matches);
  context.classList.toggle('mismatch', Boolean(expectedHash && !matches));
  if (expectedHash && model?.userData.sourceSha256) {
    context.textContent = matches ? (candidate ? '独立报告与当前网格 SHA-256 一致。' : '报告与当前显示网格的 SHA-256 一致。') : '当前模型与报告网格不同；参考结论不能套用于当前模型。';
  } else {
    context.textContent = candidate ? '候选只读取独立报告，哈希匹配后显示分数；不沿用 v2 评分或结构结论。' : version === 2 && activeName === 'real-after'
      ? '本轮报告对应 v2 升级网格，网格来源核对尚未完成。'
      : '本轮报告对应 v2 升级网格；当前显示其他模型，报告不能套用于此模型。';
  }
}

export async function refreshMultiview() {
  const serial = ++multiviewSerial;
  const candidate = isIndependentCandidate();
  const repair = isGpuRepair();
  const url = repair ? new URL(gpuRepair.reportUrl, location.href)
    : new URL(candidate ? 'detail-validation.json' : 'summary.json', candidate ? detailRoot : multiviewRoot);
  const status = document.querySelector('#multiview-status');
  const content = document.querySelector('#multiview-content');
  document.querySelector('#multiview-title').textContent = repair ? '胸部修复 · 10 方位复检' : candidate ? '候选 · 10 方位独立校验' : '10 方位校验';
  document.querySelector('#multiview-source').href = url.href;
  document.querySelector('#multiview-source').textContent = candidate ? '查看候选 10 方位报告' : '查看全方位报告来源';
  multiviewState = { status: 'loading', summary: null, error: null };
  status.textContent = '正在读取本轮全方位报告…';
  content.hidden = true;
  content.replaceChildren();
  try {
    const response = await fetch(url, { cache: 'no-store' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const summary = await response.json();
    if (serial !== multiviewSerial) return null;
    const ids = Array.isArray(summary.views) ? summary.views.map((item) => item.id) : [];
    if (summary.schemaVersion !== 1 || summary.status !== (repair ? 'GPU_REPAIR_VALIDATION' : candidate ? 'SLEEVE_DETAIL_CANDIDATE_UNVERIFIED' : 'MULTIVIEW_VALIDATION') || ids.length !== 10 || new Set(ids).size !== 10 || ids.some((id) => !Object.hasOwn(standardViews, id))) {
      throw new Error('报告格式或 10 方位记录不完整');
    }
    if (!/^[a-f0-9]{64}$/i.test(summary.mesh?.sha256 ?? '')) throw new Error('报告缺少有效网格哈希');
    multiviewState = { status: 'ready', summary, error: null };
    status.textContent = repair ? '修复网格已独立复检，完整还原尚未验收。' : candidate ? '仅重算外轮廓；袖子细节准确度未评估，1:1 未验收。' : summary.realGarmentAccepted === false ? '10 方位记录已读取；完整还原未通过验收。' : '10 方位记录已读取；完整还原结论需独立验收。';
    renderMultiviewReport(summary);
    updateMultiviewContext();
    return summary;
  } catch (error) {
    if (serial !== multiviewSerial) return null;
    multiviewState = { status: 'not-ready', summary: null, error: error.message };
    status.textContent = `本轮全方位报告尚未就绪（${error.message}）。10 个视角仍可用于观察网格。`;
    updateMultiviewContext();
    return null;
  }
}

async function hashBytes(bytes) {
  if (!globalThis.crypto?.subtle) return null;
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, '0')).join('');
}

function localArtifactURL(path) {
  if (typeof path !== 'string' || !path) return null;
  const url = new URL(path, location.href);
  return url.origin === location.origin && url.pathname.startsWith('/output/') ? url.href : null;
}

function renderMultiviewReport(summary) {
  const content = document.querySelector('#multiview-content');
  content.replaceChildren();
  const table = appendText(content, 'table', '', 'multiview-table');
  table.setAttribute('aria-label', '10 方位参考检查与投影状态');
  const head = appendText(table, 'thead', '');
  const heading = appendText(head, 'tr', '');
  for (const label of ['方位', isDetailCandidate() ? '外轮廓' : '参考轮廓', '投影']) appendText(heading, 'th', label).scope = 'col';
  const body = appendText(table, 'tbody', '');
  for (const [id, preset] of Object.entries(standardViews)) {
    const item = summary.views.find((entry) => entry.id === id);
    const row = appendText(body, 'tr', '');
    row.dataset.reportRow = id;
    const cell = appendText(row, 'td', '');
    const button = appendText(cell, 'button', preset.label.replace('斜视', ''));
    button.dataset.reportView = id;
    button.addEventListener('click', () => setView(id));
    const reference = item.referenceCheck?.status;
    const referenceLabels = { passed: '轮廓通过', failed: '轮廓未过', unevaluated: '未评估' };
    appendText(row, 'td', referenceLabels[reference] ?? '未记录', reference === 'unevaluated' ? 'unassessed' : reference === 'failed' ? 'failed' : '');
    appendText(row, 'td', item.projectionCheck?.status === 'renderable' ? '可渲染' : item.projectionCheck?.status === 'failed' ? '异常' : '未记录');
  }
  appendText(content, 'p', '“可渲染”仅表示能够生成投影；没有同方位参考图的记录不计为还原通过。', 'report-note');
  if (isDetailCandidate()) appendText(content, 'p', '外轮廓阈值：IoU ≥ 0.95、边界 P95 ≤ 0.015。袖内深处仍有原 v2 的过渡形状，尚未与目标内部匹配。', 'report-note');
  appendText(content, 'div', '').id = 'multiview-detail';
  const links = appendText(content, 'div', '', 'report-links');
  const labels = { structure: '结构报告', collision: '限定碰撞报告', aperture: '开口报告' };
  for (const key of ['structure', 'collision', 'aperture']) {
    const report = summary[key];
    const url = localArtifactURL(report?.reportPath);
    if (url) {
      const link = appendText(links, 'a', `${labels[key]}${report?.status === 'pending' ? '（待完成）' : ''}`, 'source-link');
      link.href = url;
      link.target = '_blank';
      link.rel = 'noopener';
    } else if (report?.status === 'pending') appendText(links, 'span', `${labels[key]}尚未就绪`, 'source-link');
  }
  if (summary.aperture?.status === 'partial_geometry_checks_only') appendText(content, 'p', '开口报告仅完成部分几何检查，不代表开口细节或整件服装还原通过。', 'report-note');
  if (Array.isArray(summary.limitations) && summary.limitations.length) {
    const details = appendText(content, 'details', '');
    appendText(details, 'summary', '本轮检查范围与限制');
    for (const limitation of summary.limitations) appendText(details, 'p', String(limitation), 'report-note');
  }
  renderMultiviewSelection();
}

function renderMultiviewSelection() {
  const detail = document.querySelector('#multiview-detail');
  const summary = multiviewState.summary;
  if (!detail || !summary || multiviewState.status !== 'ready') return;
  const item = summary.views.find((entry) => entry.id === view);
  document.querySelectorAll('[data-report-row]').forEach((row) => row.setAttribute('aria-current', String(!isFreeView && row.dataset.reportRow === view)));
  detail.replaceChildren();
  if (!item || isFreeView) {
    appendText(detail, 'p', detailFocus ? `${detailLabels[detailFocus.key]}近景：隐藏深度与内部形状为推断，局部还原未验收。选择标准方位可查看独立外轮廓记录。` : '当前为自由观察；选择标准方位可查看对应记录。', 'report-note');
    return;
  }
  appendText(detail, 'h3', `${standardViews[item.id].label} · 对应记录`);
  const check = item.referenceCheck ?? {};
  if (isDetailCandidate()) appendText(detail, 'p', '此方位袖子细节准确度：未评估。', 'report-note');
  if (check.reason) appendText(detail, 'p', String(check.reason), 'report-note');
  if (localArtifactURL(check.referencePath)) {
    if (typeof check.usedInGeometryFit === 'boolean') appendText(detail, 'p', check.usedInGeometryFit ? '此参考曾参与几何拟合。' : '此参考未参与几何拟合。', 'report-note');
  } else {
    appendText(detail, 'p', '此方位没有匹配的参考图。', 'report-note');
  }
  const metrics = check.metrics;
  if (Number.isFinite(metrics?.iou)) {
    const metricText = [`IoU ${metrics.iou.toFixed(4)}`];
    if (Number.isFinite(metrics.boundary_p95_bbox)) metricText.push(`边界 P95 ${metrics.boundary_p95_bbox.toFixed(5)}`);
    appendText(detail, 'p', metricText.join(' · '), 'report-note');
  }
  if (item.projectionCheck?.clipped === true) appendText(detail, 'p', '此投影存在裁切，需单独核对。', 'report-note');
  if (Number.isFinite(item.projectionCheck?.foregroundPixels)) appendText(detail, 'p', `投影前景：${item.projectionCheck.foregroundPixels.toLocaleString()} 像素。`, 'report-note');
  const images = appendText(detail, 'div', '', 'references');
  const isObliqueReference = ['top', 'bottom'].includes(item.id) && check.status === 'unevaluated';
  const captions = [
    [check.referencePath, isObliqueReference ? '斜角诊断参考 · 非轴向评分' : 'AI 目标图 · 非实拍'],
    [item.assets?.overlay ?? item.assets?.mask, item.assets?.overlay ? '参考与投影叠图' : '网格投影掩膜'],
  ];
  for (const [path, caption] of captions) {
    const url = localArtifactURL(path);
    if (!url) continue;
    const figure = appendText(images, 'figure', '');
    const link = appendText(figure, 'a', '');
    link.href = url;
    link.target = '_blank';
    link.rel = 'noopener';
    const image = document.createElement('img');
    image.alt = `${standardViews[item.id].label}：${caption}`;
    image.addEventListener('error', () => { link.replaceChildren(); link.removeAttribute('href'); link.textContent = '对应图片尚未就绪'; });
    image.src = url;
    link.append(image);
    appendText(figure, 'figcaption', caption);
  }
}

function appendText(parent, tag, value, className = '') {
  const element = document.createElement(tag);
  element.textContent = value;
  if (className) element.className = className;
  parent.append(element);
  return element;
}

function appendViewTable(parent, title, views) {
  appendText(parent, 'h3', title);
  if (!Array.isArray(views) || views.length === 0) {
    appendText(parent, 'p', '逐视角数据尚未就绪。');
    return;
  }
  const table = document.createElement('table');
  table.setAttribute('aria-label', `${title}的轮廓 IoU 前后对照`);
  const head = document.createElement('thead');
  const header = document.createElement('tr');
  const headings = version === 2 ? ['视角', '上轮', '升级后', '变化'] : ['视角', '拟合前', '拟合后', '变化'];
  for (const label of headings) appendText(header, 'th', label).scope = 'col';
  head.append(header);
  table.append(head);
  const body = document.createElement('tbody');
  const labels = { front: '正面', back: '背面', side: '侧面', right: '右侧', heldout35: '留出 35°' };
  const precision = version === 2 ? 4 : 3;
  for (const item of views) {
    const before = item.before?.iou;
    const after = item.after?.iou;
    const hasPair = Number.isFinite(before) && Number.isFinite(after);
    const delta = hasPair ? after - before : null;
    const row = document.createElement('tr');
    appendText(row, 'td', labels[item.view] ?? item.view ?? '未命名');
    appendText(row, 'td', Number.isFinite(before) ? before.toFixed(precision) : '未记录');
    appendText(row, 'td', Number.isFinite(after) ? after.toFixed(precision) : '未记录');
    appendText(row, 'td', hasPair ? `${delta >= 0 ? '+' : ''}${delta.toFixed(precision)}` : '—');
    body.append(row);
  }
  table.append(body);
  parent.append(table);
}

function resize() {
  const previousFocus = detailFocus?.key;
  const { width, height } = stage.getBoundingClientRect();
  renderer.setSize(width, height, false);
  camera.aspect = width / Math.max(height, 1);
  camera.updateProjectionMatrix();
  frameModel();
  if (previousFocus && isDetailCandidate() && ready) focusDetail(previousFocus);
  render();
}
new ResizeObserver(resize).observe(stage);
resize();
select.addEventListener('change', () => selectModel(select.value));
document.querySelectorAll('[data-mode]').forEach((button) => button.addEventListener('click', () => setMode(button.dataset.mode)));
document.querySelectorAll('[data-view]').forEach((button) => button.addEventListener('click', () => setView(button.dataset.view)));
document.querySelectorAll('[data-detail]').forEach((button) => button.addEventListener('click', () => focusDetail(button.dataset.detail)));
document.querySelector('#multiview-refresh').addEventListener('click', () => { refreshMultiview(); if (isIndependentCandidate()) refreshSummary(); });
const gpuFitURL = new URL('./gpu-fit.html', import.meta.url);
gpuFitURL.port = '8768';
document.querySelector('#gpu-fit-entry').href = gpuFitURL.href;
document.querySelector('#openings-toggle').addEventListener('change', (event) => {
  if (openingLines) openingLines.visible = event.target.checked;
  render();
});
controls.addEventListener('start', () => {
  isFreeView = true;
  detailFocus = null;
  document.querySelectorAll('[data-detail]').forEach((button) => button.setAttribute('aria-pressed', 'false'));
  document.querySelectorAll('[data-view]').forEach((button) => button.setAttribute('aria-pressed', 'false'));
  document.querySelector('#view-name').textContent = '自由观察';
  renderMultiviewSelection();
});
renderer.setAnimationLoop(() => { controls.update(); render(); });

window.validationViewer = {
  selectModel,
  setView,
  setMode,
  focusDetail,
  refreshSummary,
  refreshMultiview,
  capture() { render(); return renderer.domElement.toDataURL('image/png'); },
  get renderer() { return renderer; },
  get model() { return model; },
  get camera() { return camera; },
  get controls() { return controls; },
  get scene() { return scene; },
  get ready() { return ready; },
  get whenReady() { return whenReady; },
  get error() { return lastError; },
  get selectedModel() { return activeName; },
  get mode() { return mode; },
  get version() { return version; },
  get artifactRoot() { return artifactRoot.href; },
  get standardViews() { return structuredClone(standardViews); },
  get currentView() { return { key: isFreeView ? null : view, yaw: standardViews[view]?.yaw ?? view, pitch: viewPitch }; },
  get multiviewState() { return { ...multiviewState }; },
  get summaryState() { return { ...summaryState }; },
  get detailFocus() { return detailFocus ? structuredClone(detailFocus) : null; },
  get framing() { return { center: displayCenter.toArray(), referenceRadius: modelRadius, distance: frameDistance }; },
};
selectModel(activeName);

for (const [comparisonVersion, label] of [[1, '上一轮 v1：初始网格 → 上轮拟合'], [2, '升级 v2：上轮拟合基线 → 升级拟合']]) {
  const url = new URL(`../output/${artifactFolders[comparisonVersion]}/real-comparison.png`, import.meta.url);
  const figure = document.createElement('figure');
  figure.className = 'outline-card';
  const link = document.createElement('a');
  link.href = url.href;
  link.target = '_blank';
  link.rel = 'noopener';
  const image = document.createElement('img');
  image.alt = `${label}的真实投影叠图`;
  image.addEventListener('error', () => {
    link.replaceChildren();
    link.removeAttribute('href');
    link.setAttribute('aria-disabled', 'true');
    link.textContent = `v${comparisonVersion} 轮廓对照图尚未就绪`;
  });
  image.src = url.href;
  link.append(image);
  figure.append(link);
  appendText(figure, 'figcaption', label);
  document.querySelector('#outline-comparisons').append(figure);
}
