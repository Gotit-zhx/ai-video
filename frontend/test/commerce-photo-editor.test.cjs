const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Run with node --experimental-vm-modules --test frontend/test/commerce-photo-editor.test.cjs.
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
  configuration:{product:'T_SHIRT', areas:[],surfacePrints:[],photoPrints:[{id:'front-print',artworkId:'f'.repeat(64),side:'FRONT',x:.3,y:.3,width:.3,height:.4,rotation:0}], baseImages:{...baseImages}, reconstruction:null}
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

runtimeTest('outer template save keeps photo interactions locked until its POST finishes',async()=>{
 const post=deferred();let adapter,body;const ui=harness({surfaceMount:ctx=>{adapter=ctx;return {fields:()=>({}),draw(){},load(){},reset(){},saved(){},prepareSave:async value=>value};},api:async(url,request)=>{if(request.method==='POST'){body=JSON.parse(request.body);return post.promise;}}});
 await ui.load();const saving=ui.editor.save({name:'保存期间不许改变位置'});await tick();assert.equal(adapter.isLocked(),true);
 post.resolve({data:{...body,templateId:'template-1',snapshotId:'snapshot-saved',version:2}});await saving;assert.equal(adapter.isLocked(),false);
});
