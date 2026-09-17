const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Run with node --experimental-vm-modules --test frontend/test/commerce-print-model.test.cjs.
// The entire editor executes unchanged; browser surfaces, the surface adapter and imported GPU engine are mocked.
const runtimeTest = typeof vm.SyntheticModule === 'function' ? test : test.skip;
const source = fs.readFileSync(path.join(__dirname, '../commerce-print-editor.js'), 'utf8');
const plain = value => JSON.parse(JSON.stringify(value));
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return {promise, resolve, reject}; };
const tick = () => new Promise(resolve => setImmediate(resolve));
const baseImages = {FRONT:'a'.repeat(64)};
const mesh = (label = 'original', sources = baseImages) => ({
  schemaVersion:1, algorithm:'garment-coarse-v1', sourceImages:{...sources},
  vertices:[[0, 0, 0], [1, 0, 0], [0, 1, 0], [0, 0, 1]], faces:[[0, 1, 2], [0, 3, 1], [0, 2, 3], [1, 3, 2]],
  quality:{status:'NEEDS_REFERENCE', elapsedMs:25, views:[{side:'FRONT', iou:0.96}], missingViews:['BACK', 'LEFT', 'RIGHT'], warnings:[label]}
});
const template = reconstruction => ({
  templateId:'template-1', snapshotId:'snapshot-1', version:1, name:'灰色短袖',
  configuration:{product:'T_SHIRT', areas:[{id:'front-main', side:'FRONT', x:0.3, y:0.3, width:0.3, height:0.4, printWidthMm:240, printHeightMm:300, dpi:300}], baseImages:{...baseImages}, reconstruction}
});

function harness(options = {}) {
  const nodes = [], requests = [], engines = [], previews = [], surfaceUploads = [], events = new Map(), pending = new Set();
  let rows = options.rows || [template(null)], build = options.build || (async () => mesh('generated'));
  function track(value) { if (value && typeof value.then === 'function') { pending.add(value); value.finally(() => pending.delete(value)).catch(() => {}); } return value; }
  function node(tag) {
    const element = {tag, children:[], handlers:new Map(), attrs:{}, style:{}, dataset:{}, _value:null, _text:'', hidden:false, disabled:false,
      append(...children) { for (const child of children) { child.remove(); child.parentElement = this; this.children.push(child); } },
      insertBefore(child, reference) { child.remove(); child.parentElement = this; this.children.splice(this.children.indexOf(reference), 0, child); },
      replaceChildren(...children) { this.children.forEach(child => { child.parentElement = null; }); this.children = []; this.append(...children); },
      remove() { if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(child => child !== this); this.parentElement = null; },
      setAttribute(key, value) { this.attrs[key] = String(value); }, removeAttribute(key) { delete this.attrs[key]; delete this[key]; },
      addEventListener(type, fn) { if (!this.handlers.has(type)) this.handlers.set(type, []); this.handlers.get(type).push(fn); },
      removeEventListener(type, fn) { this.handlers.set(type, (this.handlers.get(type) || []).filter(handler => handler !== fn)); },
      dispatchEvent(event) { event.target ||= this; const results = (this.handlers.get(event.type) || []).map(fn => track(fn(event))); return Promise.all(results); },
      click() { if (this.disabled) return Promise.resolve(); return this.dispatchEvent({type:'click', button:0}); },
      querySelectorAll(selector) { const matches = []; const visit = item => { item.children.forEach(child => { if (selector === 'button' && child.tag === 'button' || selector === '.print-area' && child.className === 'print-area' || selector === 'option[data-sample]' && child.tag === 'option' && child.dataset.sample) matches.push(child); visit(child); }); }; visit(this); return matches; },
      scrollIntoView() {}, setPointerCapture() {}, getBoundingClientRect() { return {width:500, height:500}; }
    };
    Object.defineProperty(element, 'textContent', {get() { return this._text + this.children.map(child => child.textContent).join(''); }, set(value) { this._text = String(value); this.replaceChildren(); }});
    Object.defineProperty(element, 'value', {get() { return this._value ?? (this.tag === 'select' ? this.children[0]?.value || '' : ''); }, set(value) { this._value = value; }});
    nodes.push(element); return element;
  }
  const root = node('div'); root.id = 'commerce-print-editor';
  const api = async (url, request = {}) => {
    requests.push({url, request});
    if (options.api) { const result = await options.api(url, request); if (result !== undefined) return result; }
    if (request.method === 'POST') {
      const body = JSON.parse(request.body); const row = {...body, templateId:body.templateId || 'template-new', version:body.expectedVersion + 1, snapshotId:'snapshot-saved'};
      rows = [row]; return {data:row};
    }
    return {data:rows};
  };
  const context = vm.createContext({
    document:{getElementById:id => nodes.find(item => item.id === id), createElement:node, createElementNS:(_, tag) => node(tag)},
    window:{addEventListener(type, fn) { if (!events.has(type)) events.set(type, []); events.get(type).push(fn); }, confirm:() => true},
    Event:class { constructor(type) { this.type = type; } },
    crypto:{randomUUID:() => 'key-123456789'}, structuredClone, AbortController,
    FormData:class { constructor() { this.fields = new Map(); } append(key, value) { this.fields.set(key, value); } }, api
  });
  if (options.surface || options.surfaceMount) context.window.CommercePrintSurface = {
    mount(ctx) {
      if (options.surfaceMount) return options.surfaceMount(ctx);
      return {
        fields:() => ({}), load() {}, reset() {}, draw() {}, invalidate() {}, beforeModel() {}, modelChanged() {}, saved() {},
        prepareSave:async configuration => configuration,
        async uploadSource(file, isPlain) {
          surfaceUploads.push({file, isPlain});
          // The production surface adapter applies completed reference images through
          // this editor callback. Exercise its cancellation and invalidation behavior.
          ctx.applyReferences({FRONT:'b'.repeat(64)});
        }
      };
    }
  };
  const imports = new Map();
  async function imported(specifier) {
    if (imports.has(specifier)) return imports.get(specifier);
    if (!specifier.includes('garment-coarse') && options.previewImport) await options.previewImport();
    const exports = specifier.includes('garment-coarse') ? {buildCoarseModel:async (inputs, settings) => { engines.push({inputs:plain(inputs), settings}); return build(inputs, settings); }} : {
      createPreview:() => { const preview = {models:[], surfaceSyncs:[], selections:[], disposed:false, setModel(value) { if (options.rejectModel?.(value)) throw new Error('renderer rejected candidate'); this.models.push(plain(value)); }, setSurfacePrints(values, assets) { this.surfaceSyncs.push({prints:plain(values), assets:[...assets]}); }, setSelectedPrint(id) { this.selections.push(id); }, setWireframe() {}, setVisible() {}, update() {}, reset() {}, dispose() { this.disposed = true; }}; previews.push(preview); return preview; }
    };
    const module = new vm.SyntheticModule(Object.keys(exports), function () { Object.entries(exports).forEach(([key, value]) => this.setExport(key, value)); }, {context});
    await module.link(() => {}); await module.evaluate(); imports.set(specifier, module); return module;
  }
  new vm.Script(source, {filename:'commerce-print-editor.js', importModuleDynamically:imported}).runInContext(context);
  const button = text => nodes.find(item => item.tag === 'button' && item.textContent === text);
  const byId = id => nodes.find(item => item.id === id);
  const saved = nodes.find(item => item.attrs['aria-label'] === '已保存印花模板');
  return {
    nodes, root, requests, engines, previews, surfaceUploads, button, byId, saved, editor:context.window.CommercePrintTemplateEditor,
    selection:() => plain(context.window.CommercePrintTemplateSelection()),
    workflow(snapshotId) { return Promise.all((events.get('heyi:workflow-print-template-selected') || []).map(fn => track(fn({detail:{snapshotId}})))); },
    async rename(value) { const input=nodes.find(item=>item.attrs['aria-label']==='印花模板名称');input.value=value;await input.dispatchEvent({type:'input'}); },
    setBuild(fn) { build = fn; },
    async load() { await button('读取模板').click(); saved.value = rows[0]?.snapshotId || ''; await saved.dispatchEvent({type:'change'}); },
    async save() { await button('保存新版本').click(); return JSON.parse(requests.filter(item => item.request.method === 'POST' && item.url === '/api/v1/commerce-print-templates').at(-1).request.body); },
    async changeSource() { const input = nodes.find(item => item.type === 'file'); input.files = [{size:20, name:'plain.png'}]; input.value = 'C:\\fakepath\\plain.png'; await input.dispatchEvent({type:'change'}); return input; },
    async companyReset() { for (const fn of events.get('heyi:company-reset') || []) await fn(); },
    async settle() { for (let i = 0; i < 5; i++) { await tick(); if (pending.size) await Promise.all([...pending]); } }
  };
}

runtimeTest('generated mesh and source hashes persist and restore through normal template actions', async () => {
  const ui = harness(); await ui.load(); await ui.byId('print-model-build').click(); await ui.settle();
  assert.equal(ui.engines.length, 1); assert.equal(ui.engines[0].inputs.FRONT.url, '/api/v1/commerce-print-templates/base-images/' + baseImages.FRONT);
  const saved = await ui.save(); assert.deepEqual(saved.configuration.reconstruction, mesh('generated'));
  await ui.companyReset(); await ui.load(); await ui.button('3D 预览').click(); await ui.settle();
  assert.deepEqual(ui.previews.at(-1).models.at(-1), saved.configuration.reconstruction);
  assert.match(ui.byId('print-model-status').textContent, /仅表示轮廓重合度/);
});

runtimeTest('failed rebuild preserves the previous model and allows retry', async () => {
  const previous = mesh(); const ui = harness({rows:[template(previous)], build:async () => { throw new Error('image 401'); }});
  await ui.load(); await ui.byId('print-model-build').click();
  assert.match(ui.byId('print-model-status').textContent, /image 401.*原模型保留/);
  assert.equal(ui.byId('print-model-build').disabled, false); assert.deepEqual((await ui.save()).configuration.reconstruction, previous);
  ui.setBuild(async () => mesh('retry')); await ui.byId('print-model-build').click(); await ui.settle();
  assert.deepEqual((await ui.save()).configuration.reconstruction, mesh('retry'));
});

runtimeTest('a candidate rejected by the renderer is not committed over the saved model', async () => {
  const previous = mesh(); const ui = harness({rows:[template(previous)], rejectModel:value => value?.quality?.warnings?.[0] === 'generated'});
  await ui.load(); await ui.button('3D 预览').click(); await ui.byId('print-model-build').click(); await ui.settle();
  assert.match(ui.byId('print-model-status').textContent, /renderer rejected candidate/);
  assert.deepEqual(ui.previews.at(-1).models.at(-1), previous);
  assert.deepEqual((await ui.save()).configuration.reconstruction, previous);
});

runtimeTest('cancel aborts the worker and ignores a result returned after cancellation', async () => {
  const work = deferred(); const previous = mesh(); const ui = harness({rows:[template(previous)], build:() => work.promise});
  await ui.load(); const running = ui.byId('print-model-build').click(); await tick();
  await ui.byId('print-model-cancel').click(); assert.equal(ui.engines[0].settings.signal.aborted, true);
  work.resolve(mesh('late cancelled')); await running;
  assert.deepEqual((await ui.save()).configuration.reconstruction, previous); assert.equal(ui.byId('print-model-build').disabled, false);
});

runtimeTest('company change prevents an in-flight result from entering the new company template', async () => {
  const work = deferred(); const ui = harness({rows:[template(mesh())], build:() => work.promise});
  await ui.load(); const running = ui.byId('print-model-build').click(); await tick(); await ui.companyReset();
  assert.equal(ui.engines[0].settings.signal.aborted, true); work.resolve(mesh('previous company')); await running;
  const saved = await ui.save(); assert.equal(saved.configuration.reconstruction, null); assert.deepEqual(saved.configuration.baseImages, {});
  assert.equal(ui.byId('print-model-build').disabled, true); assert.equal(ui.previews.length, 0);
});

runtimeTest('source replacement invalidates the saved model and any stale running fit', async () => {
  const work = deferred(); const ui = harness({surface:true, rows:[template(mesh())], build:() => work.promise});
  await ui.load(); const running = ui.byId('print-model-build').click(); await tick(); const input = await ui.changeSource();
  assert.equal(ui.surfaceUploads.length, 1); assert.equal(ui.surfaceUploads[0].file, input.files[0]);
  assert.equal(ui.requests.some(item => item.url.endsWith('/base-images/prepare')), false);
  assert.equal(ui.engines[0].settings.signal.aborted, true); work.resolve(mesh('old source')); await running;
  const saved = await ui.save(); assert.equal(saved.configuration.reconstruction, null); assert.deepEqual(saved.configuration.baseImages, {FRONT:'b'.repeat(64)});
});

runtimeTest('missing surface module preserves the chosen file and never calls the legacy paid upload endpoint', async () => {
  const previous = mesh(); const ui = harness({rows:[template(previous)]});
  await ui.load(); const requestsBefore = ui.requests.length; const input = await ui.changeSource();
  assert.match(ui.root.textContent, /上传组件未加载完成，请刷新页面后重试/);
  assert.equal(input.files.length, 1); assert.equal(input.files[0].name, 'plain.png');
  assert.equal(input.value, 'C:\\fakepath\\plain.png', 'the selected file must remain available after a missing module');
  assert.equal(input.disabled, false); assert.equal(ui.requests.length, requestsBefore, 'missing module must not submit any upload request');
  assert.equal(ui.requests.some(item => item.url.endsWith('/base-images/prepare')), false);
  assert.equal(ui.surfaceUploads.length, 0); assert.equal(ui.engines.length, 0);
  const saved = await ui.save(); assert.deepEqual(saved.configuration.reconstruction, previous); assert.deepEqual(saved.configuration.baseImages, baseImages);
});

runtimeTest('switching templates cancels a pending fit and restores the selected template model', async () => {
  const work = deferred(), next = template(mesh('second template')); next.snapshotId = 'snapshot-2'; next.templateId = 'template-2';
  const ui = harness({rows:[template(mesh()), next], build:() => work.promise});
  await ui.load(); const running = ui.byId('print-model-build').click(); await tick();
  ui.saved.value = 'snapshot-2'; await ui.saved.dispatchEvent({type:'change'});
  assert.equal(ui.engines[0].settings.signal.aborted, true);
  work.resolve(mesh('first template late result')); await running;
  const saved = await ui.save(); assert.equal(saved.templateId, 'template-2'); assert.deepEqual(saved.configuration.reconstruction, next.configuration.reconstruction);
});

runtimeTest('removing a source removes the model from the next saved version', async () => {
  const ui = harness({rows:[template(mesh())]}); await ui.load(); await ui.button('3D 预览').click();
  await ui.button('移除当前面底图').click();
  assert.equal(ui.previews.at(-1).models.at(-1), null); assert.equal(ui.byId('print-model-build').disabled, true);
  assert.equal(ui.nodes.some(item => item.tag === 'p' && item.textContent.startsWith('根据底图生成的粗模')), false, 'fallback model must not retain the source-reconstruction label');
  const saved = await ui.save(); assert.deepEqual(saved.configuration.baseImages, {}); assert.equal(saved.configuration.reconstruction, null);
});

runtimeTest('stale stored mesh is discarded when its source no longer matches the template', async () => {
  const ui = harness({rows:[template(mesh('stale', {FRONT:'c'.repeat(64)}))]}); await ui.load();
  assert.equal((await ui.save()).configuration.reconstruction, null);
});

runtimeTest('unauthenticated reads do not invoke the engine or enable generation without a source', async () => {
  const ui = harness({api:async () => { throw new Error('请先登录'); }});
  await ui.button('读取模板').click(); await ui.byId('print-model-build').click();
  assert.ok(ui.root.children.some(item => item.textContent === '请先登录')); assert.equal(ui.engines.length, 0); assert.equal(ui.byId('print-model-build').disabled, true);
});

runtimeTest('first lazy 3D preview synchronizes saved surface artwork and selection without another click or edit', async () => {
  const waiting = deferred(), row = template(mesh('saved with artwork'));
  row.configuration.sourceImageId = 'b'.repeat(64);
  row.configuration.surfacePrints = ['star', 'flower', 'checker'].map((id, index) => ({
    id, artworkId:String(index + 1).repeat(64), position:[index * .1, .5, .2], normal:[0, 0, 1], tangent:[1, 0, 0],
    width:.2 + index * .05, height:.3, rotation:index * .1, meshPart:'body'
  }));
  let draws = 0, changedCalls = 0;
  const ui = harness({rows:[row], previewImport:() => waiting.promise, surfaceMount:ctx => {
    let saved = {}, selected = null;
    const changed = ctx.changed; ctx.changed = () => { changedCalls++; changed(); };
    const adapter = {
      fields:() => ({sourceImageId:saved.sourceImageId}),
      load(config) { saved = plain(config); selected = saved.surfacePrints?.[0]?.id || null; adapter.draw(); },
      draw() {
        draws++; const preview = ctx.preview(); if (!preview) return;
        preview.setSurfacePrints(saved.surfacePrints || [], new Map((saved.surfacePrints || []).map(print => [print.artworkId, '/api/v1/commerce-print-templates/base-images/' + print.artworkId])));
        preview.setSelectedPrint(selected);
      },
      reset() {}, invalidate() {}, beforeModel() {}, modelChanged() {}, saved() {}, prepareSave:async config => config
    };
    return adapter;
  }});
  await ui.load(); const drawsBefore = draws, requestsBefore = ui.requests.length;
  const opening = ui.button('3D 预览').click(); await tick();
  assert.equal(ui.previews.length, 0, 'the first renderer import is deliberately still pending');
  waiting.resolve(); await opening; await ui.settle();
  assert.equal(ui.previews.length, 1); assert.equal(draws, drawsBefore + 1, 'renderer creation synchronizes once without a draw/import loop');
  const preview = ui.previews[0];
  assert.deepEqual(preview.models.at(-1), row.configuration.reconstruction);
  assert.deepEqual(preview.surfaceSyncs.at(-1).prints, row.configuration.surfacePrints);
  assert.deepEqual(preview.surfaceSyncs.at(-1).assets, row.configuration.surfacePrints.map(print => [print.artworkId, '/api/v1/commerce-print-templates/base-images/' + print.artworkId]));
  assert.equal(preview.selections.at(-1), 'star'); assert.equal(changedCalls, 0);
  assert.equal(ui.requests.length, requestsBefore, 'opening the renderer must not save or regenerate the template');
  assert.equal(ui.engines.length, 0);
});

runtimeTest('workflow selection exposes the saved snapshot, marks edits dirty, and follows the new saved version',async()=>{
  const ui=harness({rows:[template(mesh())]});await ui.load();
  assert.deepEqual(ui.selection(),{snapshotId:'snapshot-1',name:'灰色短袖',dirty:false});
  await ui.rename('新版型');assert.deepEqual(ui.selection(),{snapshotId:'snapshot-1',name:'新版型',dirty:true});
  await ui.save();assert.deepEqual(ui.selection(),{snapshotId:'snapshot-saved',name:'新版型',dirty:false});
  assert.equal(ui.saved.value,'snapshot-saved');
  assert.equal(ui.saved.children.filter(option=>option.value==='snapshot-saved').length,1);
  assert.equal(ui.saved.children.some(option=>option.value==='snapshot-1'),false,'latest list entry must not continue pointing to the prior version');
});

runtimeTest('workflow loads its exact archived print snapshot rather than substituting the latest template version',async()=>{
  const latest={...template(mesh('latest')),snapshotId:'snapshot-latest',version:8},older={...template(mesh('frozen old')),snapshotId:'snapshot-old',version:2,name:'原固定版'};
  const ui=harness({rows:[latest],api:async url=>url==='/api/v1/commerce-print-templates/snapshots/snapshot-old'?{data:older}:undefined});await ui.load();
  await ui.workflow('snapshot-old');assert.deepEqual(ui.selection(),{snapshotId:'snapshot-old',name:'原固定版',dirty:false});
  assert.equal(ui.saved.value,'snapshot-old');assert.ok(ui.requests.some(request=>request.url==='/api/v1/commerce-print-templates/snapshots/snapshot-old'));
  const body=await ui.save();assert.equal(body.expectedVersion,2);assert.deepEqual(body.configuration.reconstruction,older.configuration.reconstruction);
});

runtimeTest('while a linked snapshot loads the outer getter exposes the requested snapshot as dirty, never the previous clean selection',async()=>{
  const gate=deferred(),next={...template(mesh('requested')),snapshotId:'snapshot-requested',name:'关联版型'};
  const ui=harness({rows:[template(mesh())],api:async url=>url.endsWith('/snapshots/snapshot-requested')?gate.promise:undefined});await ui.load();
  const waiting=ui.workflow('snapshot-requested');assert.equal(ui.selection().snapshotId,'snapshot-requested');assert.equal(ui.selection().dirty,true);
  gate.resolve({data:next});await waiting;assert.deepEqual(ui.selection(),{snapshotId:'snapshot-requested',name:'关联版型',dirty:false});
});

runtimeTest('a linked snapshot response after company reset cannot restore another company model or selection',async()=>{
  const gate=deferred(),old={...template(mesh('other company')),snapshotId:'snapshot-foreign'};
  const ui=harness({rows:[template(mesh())],api:async url=>url.endsWith('/snapshots/snapshot-foreign')?gate.promise:undefined});await ui.load();
  const waiting=ui.workflow('snapshot-foreign');await ui.companyReset();gate.resolve({data:old});await waiting;
  assert.equal(ui.selection().snapshotId,null);assert.equal(ui.selection().name,'');assert.equal(ui.saved.children.length,0);
  assert.equal((await ui.save()).configuration.reconstruction,null);
});

runtimeTest('new edits during linked snapshot loading are preserved and cannot be announced as clean requested content',async()=>{
  const previous=template(mesh('user work')),gate=deferred(),next={...template(mesh('late replacement')),snapshotId:'snapshot-next',name:'不能覆盖'};
  const ui=harness({rows:[previous],api:async url=>url.endsWith('/snapshots/snapshot-next')?gate.promise:undefined});await ui.load();
  const waiting=ui.workflow('snapshot-next');await ui.rename('用户刚编辑');gate.resolve({data:next});await waiting;
  assert.equal(ui.selection().dirty,true);assert.equal(ui.selection().name,'用户刚编辑');
  assert.equal(ui.saved.value,'snapshot-1');assert.equal(ui.saved.children.some(option=>option.value==='snapshot-next'),false);
  assert.deepEqual((await ui.save()).configuration.reconstruction,previous.configuration.reconstruction);
});

runtimeTest('a later workflow choice wins when prior snapshot downloads finish out of order',async()=>{
  const first=deferred(),second=deferred(),one={...template(mesh('one')),snapshotId:'snapshot-one',name:'第一套'},two={...template(mesh('two')),snapshotId:'snapshot-two',name:'第二套'};
  const ui=harness({rows:[template(mesh())],api:async url=>url.endsWith('/snapshots/snapshot-one')?first.promise:url.endsWith('/snapshots/snapshot-two')?second.promise:undefined});await ui.load();
  const a=ui.workflow('snapshot-one'),b=ui.workflow('snapshot-two');second.resolve({data:two});await b;first.resolve({data:one});await a;
  assert.deepEqual(ui.selection(),{snapshotId:'snapshot-two',name:'第二套',dirty:false});assert.equal(ui.saved.value,'snapshot-two');
});

runtimeTest('a failed linked snapshot keeps the requested association dirty and never falls back to the previous snapshot',async()=>{
  const ui=harness({rows:[template(mesh())],api:async url=>{if(url.endsWith('/snapshots/missing'))throw new Error('not found');}});await ui.load();await ui.workflow('missing');
  assert.equal(ui.selection().snapshotId,'missing');assert.equal(ui.selection().dirty,true);assert.equal(ui.saved.value,'snapshot-1');
  assert.match(ui.root.textContent,/关联模板读取失败/);
});

runtimeTest('selecting an unlinked workflow cannot silently attach the previous print snapshot',async()=>{
  const previous=template(mesh()),next={...template(mesh('next workflow')),templateId:'template-next',snapshotId:'snapshot-next'};
  const ui=harness({rows:[previous,next]});await ui.load();await ui.workflow(null);
  assert.equal(ui.selection().snapshotId,null);assert.equal(ui.selection().dirty,false,'unlinking a saved drawing does not invent a content edit');
  const body=await ui.save();assert.deepEqual(body.configuration.reconstruction,previous.configuration.reconstruction,'unlinking preserves the editable model');
  await ui.workflow('snapshot-next');assert.equal(ui.selection().snapshotId,'snapshot-next');assert.equal(ui.selection().dirty,false);
});

runtimeTest('choosing another workflow during an inner save retains the unresolved association until explicitly reloaded',async()=>{
  const gate=deferred(),requested={...template(mesh('new workflow')),snapshotId:'snapshot-requested',name:'请求的新模板'};
  let savedBody;const ui=harness({rows:[template(mesh())],api:async(url,request)=>{
    if(request.method==='POST'){savedBody=JSON.parse(request.body);return gate.promise;}
    if(url.endsWith('/snapshots/snapshot-requested'))return {data:requested};
  }});await ui.load();await ui.rename('先保存当前编辑');
  const saving=ui.button('保存新版本').click();await tick();assert.equal(ui.selection().dirty,true);
  await ui.workflow('snapshot-requested');assert.equal(ui.selection().snapshotId,'snapshot-requested');
  assert.equal(ui.requests.some(request=>request.url.endsWith('/snapshots/snapshot-requested')),false,'a busy editor must not replace its active save input');
  gate.resolve({data:{...savedBody,templateId:'template-1',snapshotId:'snapshot-saved',version:2}});await saving;
  assert.equal(ui.selection().snapshotId,'snapshot-requested');assert.equal(ui.selection().dirty,true,'saving a different model cannot satisfy the requested workflow association');
  assert.equal(ui.saved.value,'snapshot-saved');await ui.workflow('snapshot-requested');
  assert.deepEqual(ui.selection(),{snapshotId:'snapshot-requested',name:'请求的新模板',dirty:false});
});

runtimeTest('generating a replacement model keeps the outer getter dirty through generation and until it is saved',async()=>{
  const work=deferred(),ui=harness({rows:[template(mesh())],build:()=>work.promise});await ui.load();
  const running=ui.byId('print-model-build').click();await tick();assert.equal(ui.selection().snapshotId,'snapshot-1');assert.equal(ui.selection().dirty,true);
  work.resolve(mesh('new geometry'));await running;await ui.settle();assert.equal(ui.selection().dirty,true);
  await ui.save();assert.equal(ui.selection().dirty,false);assert.equal(ui.selection().snapshotId,'snapshot-saved');
});

runtimeTest('a new unsaved print draft is not overwritten when a workflow asks to load another snapshot',async()=>{
  const requested={...template(mesh('would overwrite draft')),snapshotId:'snapshot-requested'};
  const ui=harness({api:async url=>url.endsWith('/snapshots/snapshot-requested')?{data:requested}:undefined});
  await ui.rename('未保存的新草稿');await ui.workflow('snapshot-requested');
  assert.equal(ui.selection().name,'未保存的新草稿');assert.equal(ui.selection().dirty,true);
  assert.equal(ui.requests.some(request=>request.url.endsWith('/snapshots/snapshot-requested')),false);
});

runtimeTest('a pristine editor can load a workflow association before any manual template selection',async()=>{
  const requested={...template(mesh('initial association')),snapshotId:'snapshot-requested'};
  const ui=harness({api:async url=>url.endsWith('/snapshots/snapshot-requested')?{data:requested}:undefined});
  await ui.workflow('snapshot-requested');assert.equal(ui.selection().snapshotId,'snapshot-requested');assert.equal(ui.selection().dirty,false);
});

runtimeTest('company reset clears old edit history so the new company workflow can load normally',async()=>{
  const requested={...template(mesh('new company')),snapshotId:'snapshot-requested',name:'新公司模板'};
  const ui=harness({rows:[template(mesh())],api:async url=>url.endsWith('/snapshots/snapshot-requested')?{data:requested}:undefined});
  await ui.load();await ui.rename('旧公司未保存');await ui.companyReset();await ui.workflow('snapshot-requested');
  assert.deepEqual(ui.selection(),{snapshotId:'snapshot-requested',name:'新公司模板',dirty:false});
});


runtimeTest('unified save reuses an unchanged snapshot while rename and independent save create versions',async()=>{
  let prepared=0;
  const ui=harness({surfaceMount:()=>({fields:()=>({}),draw(){},load(){},reset(){},saved(){},prepareSave:async configuration=>{prepared++;return configuration;}})});
  await ui.load();
  const original=await ui.editor.save({name:'灰色短袖'});
  assert.equal(original.snapshotId,'snapshot-1');assert.equal(prepared,0);
  assert.equal(ui.requests.filter(request=>request.request.method==='POST').length,0);
  const renamed=await ui.editor.save({name:'新模板名称'});
  assert.equal(renamed.snapshotId,'snapshot-saved');assert.equal(renamed.name,'新模板名称');assert.equal(prepared,1);
  assert.equal(ui.requests.filter(request=>request.request.method==='POST').length,1);
  const retry=await ui.editor.save({name:'新模板名称'});
  assert.equal(retry.snapshotId,renamed.snapshotId);assert.equal(prepared,1,'retry after an outer save failure must reuse the archived surface guides');
  assert.equal(ui.requests.filter(request=>request.request.method==='POST').length,1);
  await ui.button('保存新版本').click();
  assert.equal(prepared,2);assert.equal(ui.requests.filter(request=>request.request.method==='POST').length,2,'the independent legacy action still creates a new version');
});

runtimeTest('failed linked snapshot blocks unified save and offers a working nondestructive retry',async()=>{
  let attempts=0;const requested={...template(mesh('next model')),snapshotId:'snapshot-next',name:'下一模板'};
  const ui=harness({api:async url=>{if(url.endsWith('/snapshots/snapshot-next')){if(++attempts===1)throw new Error('连接暂时中断');return {data:requested};}}});
  await ui.load();await ui.workflow('snapshot-next');
  await assert.rejects(ui.editor.save({name:'新关联'}),/关联版型尚未载入/);
  assert.equal(ui.requests.filter(request=>request.request.method==='POST').length,0);
  assert.equal(ui.selection().name,'灰色短袖');assert.equal(ui.saved.value,'snapshot-1','failed association must not replace the current model');
  assert.equal(ui.byId('print-linked-retry').hidden,false);assert.equal(ui.byId('print-linked-retry').disabled,false);assert.equal(ui.editor.getState().busy,false);
  await ui.byId('print-linked-retry').click();
  assert.equal(attempts,2);assert.deepEqual(ui.selection(),{snapshotId:'snapshot-next',name:'下一模板',dirty:false});
  assert.equal(ui.byId('print-linked-retry').parentElement.hidden,true);
});

runtimeTest('linked retry with unsaved edits archives the current model before loading the requested one',async()=>{
  const previous=template(mesh('user edited model')),requested={...template(mesh('different model')),snapshotId:'snapshot-next',name:'下一模板'};
  const ui=harness({rows:[previous],api:async url=>url.endsWith('/snapshots/snapshot-next')?{data:requested}:undefined});
  await ui.load();await ui.rename('保留我的改动');await ui.workflow('snapshot-next');
  assert.equal(ui.requests.some(request=>request.url.endsWith('/snapshots/snapshot-next')),false);
  assert.equal(ui.byId('print-linked-retry').hidden,true);assert.equal(ui.byId('print-linked-save-retry').hidden,false);
  await ui.byId('print-linked-save-retry').click();
  const saved=JSON.parse(ui.requests.find(request=>request.request.method==='POST').request.body);
  assert.equal(saved.name,'保留我的改动');assert.deepEqual(saved.configuration.reconstruction,previous.configuration.reconstruction);
  assert.deepEqual(ui.selection(),{snapshotId:'snapshot-next',name:'下一模板',dirty:false});
});
