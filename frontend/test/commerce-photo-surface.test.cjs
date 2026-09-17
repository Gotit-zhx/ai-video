const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {Blob,File} = require('node:buffer');
const {webcrypto,createHash} = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname, '../commerce-print-surface.js'), 'utf8');
const clone = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return {promise,resolve,reject}; };
const hash = digit => digit.repeat(64);
const sides = ['FRONT','BACK','LEFT','RIGHT'];
const bases = Object.fromEntries(sides.map((side,i) => [side, hash(String(i+1))]));
const print = id => ({id,artworkId:hash('a'),side:'FRONT',x:.3,y:.25,width:.24,height:.24,rotation:0});
const config = (label='first') => ({product:'T_SHIRT',areas:[],sourceImageId:hash('b'),referenceSetId:'reference-'+label,
 baseImages:{...bases},reconstruction:null,surfacePrints:[],photoPrints:[print('print-'+label)],
 renderedViews:{FRONT:hash('c')},blankRenderedViews:{FRONT:hash('d')},surfaceUvViews:{FRONT:hash('e')},placementMasks:{FRONT:hash('f')},renderPrintIds:['print-'+label],renderBinding:'binding-'+label});
const guides = ids => ({printIds:ids,views:Object.fromEntries(sides.map(side => [side,{image:'image',blank:'blank',mask:'mask',uv:'uv'}]))});
const ready = (id='job-one') => ({id,status:'READY',stage:'READY',referenceSetId:id,originalImageId:hash('9'),baseImages:{...bases},message:'四面已完成'});
const archivedImage=Buffer.from('archived original product image');
const archivedHash=createHash('sha256').update(archivedImage).digest('hex');
const archiveSample={id:'11111111-2222-4333-8444-555555555555',assetKind:'PRODUCT_IMAGE',model:'UPLOAD',status:'READY'};
const historyPanel=ui=>ui.nodes.find(n=>n.className==='print-source-history');
// A valid tiny PNG for optional manual upload and deliberately corrupt fixed-asset responses.
// Thumbnail drawing calls are recorded; formal marker assets come from the fixed PNG files.
const encodedCanvasPng=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=','base64');
const patternPng=kind=>fs.readFileSync(path.join(__dirname,'../assets/print-patterns/'+kind+'.png'));
const patternResponse=kind=>{const png=patternPng(kind);return {ok:true,arrayBuffer:async()=>png.buffer.slice(png.byteOffset,png.byteOffset+png.byteLength)};};

function harness(options={}) {
 const nodes=[],requests=[],storage=options.storage||new Map(),timers=[],placements=[],appliedReferences=[],uploading=[],pending=new Set(),objectUrls=[],revokedUrls=[],fetches=[],digests=[],canvases=[],encodings=[];
 let revision=0,organization=options.organization??'org-one',configuration=clone(options.config||config()),surface,edits=0,uuid=0,buildCount=0;
 function track(p){if(p&&typeof p.then==='function'){pending.add(p);p.finally(()=>pending.delete(p)).catch(()=>{});}return p;}
 function n(tag,text='',parent){
  const e={tag,tagName:tag.toUpperCase(),children:[],handlers:{},style:{},attrs:{},dataset:{},disabled:false,hidden:false,value:'',files:[],textContent:text,
   append(...children){for(const child of children){child.parentElement=this;this.children.push(child);}},replaceChildren(...children){this.children=[];this.append(...children);},
   setAttribute(k,v){this.attrs[k]=v;},removeAttribute(k){delete this.attrs[k];delete this[k];},
   addEventListener(type,fn){(this.handlers[type]||=[]).push(fn);},
   dispatchEvent(event){event.target ||= this;return Promise.all((this.handlers[event.type]||[]).map(fn=>track(fn(event))));},
   click(){return this.disabled?Promise.resolve():this.dispatchEvent({type:'click'});},
   querySelectorAll(selector){const a=[];const visit=p=>p.children.forEach(c=>{if(selector==='button'&&c.tag==='button')a.push(c);visit(c);});visit(this);return a;}
  };
  if(tag==='canvas'){
   const calls=[],state={fillStyle:'#000000',strokeStyle:'#000000',globalAlpha:1},stack=[];
   const record=(op,args=[])=>calls.push({op,args:[...args],...state});
   const drawing={};
   for(const property of Object.keys(state))Object.defineProperty(drawing,property,{get:()=>state[property],set:value=>{state[property]=value;}});
   for(const op of ['clearRect','translate','rotate','beginPath','moveTo','lineTo','closePath','fill','ellipse','arc','fillRect','stroke','strokeRect','quadraticCurveTo','bezierCurveTo','scale'])drawing[op]=(...args)=>record(op,args);
   drawing.save=()=>{record('save');stack.push({...state});};drawing.restore=()=>{record('restore');Object.assign(state,stack.pop()||{});};
   e.width=300;e.height=150;e.calls=calls;e.getContext=type=>{assert.equal(type,'2d');return drawing;};
   e.toBlob=(callback,mime)=>{
    const blob=new Blob([encodedCanvasPng],{type:mime}),encoding={canvas:e,mime,blob,callback};encodings.push(encoding);
    if(options.canvasToBlob)options.canvasToBlob(encoding);else queueMicrotask(()=>callback(blob));
   };
   canvases.push(e);
  }
  nodes.push(e);parent?.append(e);return e;
 }
 const root=n('div'),sidebar=n('div'),host=n('div'),baseTools=n('div');
 const renderer={setScene:async (...args)=>{if(options.setScene)return options.setScene(...args);},setSelectedPrint(){},setPlacementMode(value){placements.push(value);},
  exportSurfaceGuides:async()=>options.exportGuides?options.exportGuides():guides(surface.fields().photoPrints.map(p=>p.id))};
 const api=async(url,request={})=>{requests.push({url,request});if(options.api){const r=await options.api(url,request);if(r!==undefined)return r;}
  if(url.endsWith('/base-images'))return {data:{id:hash('9')}};
  if(url.endsWith('/rendered-views'))return {data:{renderedViews:{FRONT:hash('1')},blankRenderedViews:{FRONT:hash('2')},surfaceUvViews:{FRONT:hash('3')},placementMasks:{FRONT:hash('4')},renderPrintIds:surface.fields().photoPrints.map(p=>p.id),renderBinding:'new-binding'}};
  return {data:ready()};
 };
 const windowEvents={};
 const context=vm.createContext({window:{addEventListener:(type,fn)=>(windowEvents[type]||=[]).push(fn)},localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)},
  document:{createElement:tag=>n(tag),getElementById:id=>nodes.find(n=>n.id===id)},Blob,File,
  FormData:class{constructor(){this.fields=new Map();this.filenames=new Map();}append(k,v,filename){this.fields.set(k,v);if(filename)this.filenames.set(k,filename);}},crypto:{randomUUID:()=>`request-${++uuid}`,subtle:{digest:async(algorithm,bytes)=>{digests.push({algorithm,bytes});return options.digest?options.digest(algorithm,bytes):webcrypto.subtle.digest(algorithm,bytes);}}},structuredClone,Date,api,
  fetch:async(url,request={})=>{fetches.push({url,request});if(options.fetch){const response=await options.fetch(url,request);if(response!==undefined)return response;}const pattern=/\/assets\/print-patterns\/([a-z]+)\.png\?/.exec(url);if(pattern)return patternResponse(pattern[1]);return{ok:true,arrayBuffer:async()=>archivedImage.buffer.slice(archivedImage.byteOffset,archivedImage.byteOffset+archivedImage.byteLength)};},
  URL:{createObjectURL:file=>{const url='blob:test-source-'+(objectUrls.length+1);objectUrls.push({url,file});return url;},revokeObjectURL:url=>revokedUrls.push(url)},
  setTimeout:fn=>{timers.push(fn);return timers.length;}});
 new vm.Script(source,{filename:'commerce-print-surface.js'}).runInContext(context);
 const ctx={root,sidebar,host,baseTools,n,organizationId:()=>organization,configuration:()=>clone(configuration),revision:()=>revision,preview:()=>renderer,
  side:()=>ctx.currentSide||'FRONT',setSide:value=>{ctx.currentSide=value;},imageAspect:()=>options.aspect||1,isLocked:()=>options.isLocked?.()||false,ensurePreview:async()=>{if(options.ensurePreview)await options.ensurePreview();return renderer;},changed:()=>edits++,legacyVisible(){},setUploading:value=>uploading.push(value),defaultName(){},
  applyReferences:images=>{configuration.baseImages=clone(images);configuration.reconstruction=null;appliedReferences.push(clone(images));edits++;},
  apply:value=>{configuration=clone(value);},build:async()=>{const ticket=revision,before=surface.beforeModel();buildCount++;const result=options.build?await options.build(buildCount):{vertices:[[0,0,0],[1,1,0],[0,1,1]],faces:[[0,1,2]]};if(ticket!==revision)return;configuration.reconstruction=result;surface.modelChanged(before);edits++;}
 };
 surface=context.window.CommercePrintSurface.mount(ctx);surface.load(configuration);
 return {surface,ctx,renderer,root,host,nodes,requests,storage,timers,placements,appliedReferences,uploading,objectUrls,revokedUrls,fetches,digests,canvases,encodings,
  byId:id=>nodes.find(n=>n.id===id),button:text=>nodes.find(n=>n.tag==='button'&&n.textContent===text),input:label=>nodes.find(n=>n.tag==='input'&&n.parentElement?.textContent===label),
  configuration:()=>clone(configuration),setConfiguration:value=>{configuration=clone(value);},fields:()=>clone(surface.fields()),edits:()=>edits,buildCount:()=>buildCount,
  invalidateRevision(){revision++;},
  login(org){organization=org;},windowEvent(type){for(const fn of windowEvents[type]||[])fn();},
  load(value){revision++;configuration=clone(value);surface.load(value);},
  company(value='org-two'){revision++;organization=value;configuration={product:'T_SHIRT',areas:[],baseImages:{},reconstruction:null};surface.reset();},
  async advance(){const next=timers.shift();assert.ok(next,'no pending poll timer');next();await tick();},
  async flush(){await tick();await tick();}
 };
}


test('photo placement sizes, rotation, dragging, and undo preserve normalized coordinates',async()=>{
 const ui=harness(),before=ui.fields();const width=ui.input('宽度（图片 %）');width.value='40';await width.dispatchEvent({type:'change'});
 assert.equal(ui.fields().photoPrints[0].width,.4);assert.deepEqual(ui.fields().renderedViews,{});assert.equal(ui.fields().renderBinding,null);
 await ui.byId('print-surface-undo').click();assert.deepEqual(ui.fields(),before);
 await ui.byId('print-surface-redo').click();assert.equal(ui.fields().photoPrints[0].width,.4);
 await ui.host.dispatchEvent({type:'print-photo-change',detail:{id:'print-first',print:{x:.1,y:.5,rotation:.4},commit:true}});
 assert.equal(ui.fields().photoPrints[0].x,.1);assert.equal(ui.fields().photoPrints[0].rotation,.4);
 await ui.byId('print-surface-undo').click();assert.equal(ui.fields().photoPrints[0].x,.3);
 assert.deepEqual(ui.fields().surfacePrints,[]);assert.equal(ui.fields().reconstruction,null);
});

test('READY four-view job enables editing immediately without calling any model builder',async()=>{
 const ui=harness({config:{product:'T_SHIRT',baseImages:{},areas:[]}});
 await ui.surface.uploadSource({name:'shirt.png',size:100},false);
 assert.equal(ui.buildCount(),0);assert.equal(ui.fields().referenceSetId,'job-one');assert.deepEqual(ui.configuration().baseImages,bases);
 assert.equal(ui.byId('print-test-pattern-use').disabled,false);assert.equal(ui.byId('print-reference-resume').hidden,true);
 assert.match(ui.byId('print-reference-status').textContent,/四面参考已就绪/);
});

test('READY recovery restores existing four images by GET without AI resubmission',async()=>{
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify({id:'old-ready',status:'READY',request:{sourceImageId:hash('9'),requestId:'original-request'}})]]);
 const ui=harness({config:{product:'T_SHIRT',baseImages:{},areas:[]},storage});await ui.flush();
 assert.equal(ui.requests.length,1);assert.equal(ui.requests[0].url,'/api/v1/commerce-print-templates/references/old-ready');assert.equal(ui.requests[0].request.method,undefined);
 assert.equal(ui.buildCount(),0);assert.deepEqual(ui.configuration().baseImages,bases);
});

test('new markers can be placed on multiple faces without a reconstruction',async()=>{
 const ui=harness();await ui.byId('print-test-pattern-use').click();
 await ui.host.dispatchEvent({type:'print-photo-pick',detail:{x:.5,y:.5,side:'FRONT'}});
 ui.ctx.setSide('BACK');await ui.byId('print-surface-place').click();await ui.host.dispatchEvent({type:'print-photo-pick',detail:{x:.75,y:.4,side:'BACK'}});
 assert.equal(ui.fields().photoPrints.length,3);assert.equal(ui.fields().photoPrints[1].side,'FRONT');assert.equal(ui.fields().photoPrints[2].side,'BACK');
 assert.equal(ui.fields().photoPrints[1].artworkId,ui.fields().photoPrints[2].artworkId,'shared marker keeps grouping identity across faces');
 assert.equal(ui.fields().photoPrints[2].x,.63);await ui.byId('print-surface-undo').click();assert.equal(ui.fields().photoPrints.length,2);
});

test('four-view saving archives photo coordinates with exact render binding and no mesh',async()=>{
 const ui=harness();const result=await ui.surface.prepareSave(ui.configuration());
 const request=ui.requests.find(r=>r.url.endsWith('/rendered-views'));assert.ok(request);
 const sent=JSON.parse(request.request.body.fields.get('configuration'));assert.deepEqual(sent.surfacePrints,[]);assert.equal(sent.reconstruction,null);assert.deepEqual(sent.areas,[]);assert.equal(sent.photoPrints[0].id,'print-first');
 assert.equal(request.request.body.fields.size,17);assert.equal(result.renderBinding,'new-binding');assert.deepEqual(result.renderPrintIds,['print-first']);
});

test('an old spatial template is gated until explicit conversion and undo restores the gate',async()=>{
 const legacy={...config(),photoPrints:[],surfacePrints:[{id:'old-print',artworkId:hash('a'),position:[0,.5,.2]}]};const ui=harness({config:legacy});
 assert.match(ui.surface.saveBlocker(),/空间印花坐标/);assert.equal(ui.byId('print-test-pattern-use').disabled,true);
 await assert.rejects(ui.surface.prepareSave(ui.configuration()),/空间印花坐标/);assert.equal(ui.requests.length,0);
 await ui.button('在四面图重新设置印花').click();assert.equal(ui.byId('print-test-pattern-use').disabled,false);
 await ui.byId('print-surface-undo').click();assert.match(ui.surface.saveBlocker(),/空间印花坐标/);
});

test('no placement is a clear save blocker',async()=>{
 const ui=harness({config:{...config(),photoPrints:[]}});assert.match(ui.surface.saveBlocker(),/至少一个印花/);
 await assert.rejects(ui.surface.prepareSave(ui.configuration()),/至少一个印花/);assert.equal(ui.requests.length,0);
});

test('late original upload after company switch cannot submit reference generation',async()=>{
 const upload=deferred(),ui=harness({api:async url=>url.endsWith('/base-images')?upload.promise:undefined});
 const running=ui.surface.uploadSource({name:'shirt.png',size:100},false);await tick();ui.company();upload.resolve({data:{id:hash('9')}});await running;
 assert.equal(ui.requests.filter(r=>r.url.endsWith('/references')).length,0);assert.equal(ui.fields().sourceImageId,null);assert.equal(ui.storage.size,0);
});

test('UNKNOWN recovery only queries original job and never repeats the create request',async()=>{
 let count=0;const ui=harness({api:async(url)=>{if(url.endsWith('/references')){count++;return {data:{id:'unknown-one',status:'UNKNOWN',message:'结果待核查',retryable:false}};}if(url.endsWith('/references/unknown-one'))return {data:{id:'unknown-one',status:'UNKNOWN',message:'仍在核对',retryable:false}};}});
 await ui.surface.uploadSource({name:'shirt.png',size:100},false);await ui.byId('print-reference-resume').click();await ui.byId('print-reference-resume').click();
 assert.equal(count,1);assert.equal(ui.requests.filter(r=>r.url.endsWith('/references/unknown-one')).length,2);assert.equal(ui.buildCount(),0);
});

test('failed create reuses original request id when outcome has no known job id',async()=>{
 let calls=0;const ui=harness({api:async url=>{if(url.endsWith('/references')){if(++calls===1)throw new Error('connection lost');return {data:{id:'recovered',status:'UNKNOWN',message:'待核查'}};}}});
 await ui.surface.uploadSource({name:'shirt.png',size:100},false);await ui.byId('print-reference-resume').click();
 const requests=ui.requests.filter(r=>r.url.endsWith('/references'));assert.equal(requests.length,2);assert.deepEqual(JSON.parse(requests[0].request.body),JSON.parse(requests[1].request.body));
});

test('late render result cannot overwrite the next loaded photo template',async()=>{
 const saved=deferred(),ui=harness({api:async url=>url.endsWith('/rendered-views')?saved.promise:undefined});const running=ui.surface.prepareSave(ui.configuration());await ui.flush();ui.load(config('second'));
 saved.resolve({data:{renderBinding:'stale',renderedViews:{}}});await assert.rejects(running,/模板已切换/);assert.equal(ui.fields().renderBinding,'binding-second');assert.equal(ui.fields().photoPrints[0].id,'print-second');
});

test('export locks placement and dimensions until saved',async()=>{
 const exporting=deferred(),ui=harness({exportGuides:()=>exporting.promise});const running=ui.surface.prepareSave(ui.configuration());await ui.flush();
 const input=ui.input('宽度（图片 %）');assert.equal(input.disabled,true);input.value='80';await input.dispatchEvent({type:'change'});assert.equal(ui.fields().photoPrints[0].width,.24);
 await ui.host.dispatchEvent({type:'print-photo-change',detail:{id:'print-first',print:{x:.9},commit:true}});assert.equal(ui.fields().photoPrints[0].x,.3);
 exporting.resolve(guides(['print-first']));await running;assert.equal(input.disabled,false);
});

test('loading another template clears history and source recovery remains organization-isolated',async()=>{
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify({id:'old-org-job'})]]),ui=harness({storage});ui.input('旋转 °').value='40';await ui.input('旋转 °').dispatchEvent({type:'change'});
 ui.load(config('second'));assert.equal(ui.byId('print-surface-undo').disabled,true);ui.company();await ui.byId('print-reference-resume').click();assert.equal(ui.requests.length,0);
});

test('selecting a print does not reset its renderer scene and cancel the pointer gesture',async()=>{
 let scenes=0;const ui=harness({setScene:async()=>{scenes++;}});await ui.flush();const before=scenes;
 await ui.host.dispatchEvent({type:'print-photo-pick',detail:{existing:true,id:'print-first'}});await ui.flush();
 assert.equal(scenes,before,'selection-only changes must use setSelectedPrint, not setScene');
});

test('undoing a reference replacement can save the prior valid photos and placements',async()=>{
 const ui=harness();await ui.surface.uploadSource({name:'replacement.png',size:100},false);assert.equal(ui.fields().referenceSetId,'job-one');
 await ui.byId('print-surface-undo').click();assert.equal(ui.fields().referenceSetId,'reference-first');assert.equal(ui.fields().photoPrints[0].id,'print-first');assert.equal(ui.surface.saveBlocker(),null);
 await ui.surface.prepareSave(ui.configuration());assert.equal(ui.fields().renderBinding,'new-binding');
});

const assertInsidePhoto=(p,aspect=1)=>{
 const c=Math.abs(Math.cos(p.rotation)),s=Math.abs(Math.sin(p.rotation)),w=p.width*c+p.height*s/aspect,h=p.width*s*aspect+p.height*c,cx=p.x+p.width/2,cy=p.y+p.height/2;
 for(const distance of [cx-w/2,cy-h/2,1-cx-w/2,1-cy-h/2,p.x,p.y,1-p.x-p.width,1-p.y-p.height])assert.ok(distance>=-1e-9,'rotated rectangle exceeds its photo: '+JSON.stringify(p));
};

test('numeric rotation and dimensions keep every corner in a non-square photo',async()=>{
 const starting={...config(),photoPrints:[{...print('print-first'),x:.65,y:.7,width:.34,height:.29}]},ui=harness({config:starting,aspect:1.7});
 for(const [label,value]of [['旋转 °','45'],['宽度（图片 %）','100'],['高度（图片 %）','100'],['旋转 °','135']]){const input=ui.input(label);input.value=value;await input.dispatchEvent({type:'change'});assertInsidePhoto(ui.fields().photoPrints[0],1.7);}
});

test('overall size slider constrains rotated print corners and undo restores its prior size',async()=>{
 const starting={...config(),photoPrints:[{...print('print-first'),x:.05,y:.1,rotation:Math.PI/4}]},ui=harness({config:starting});
 const scale=ui.nodes.find(n=>n.attrs['aria-label']==='印花整体大小');scale.value='100';await scale.dispatchEvent({type:'input'});await scale.dispatchEvent({type:'change'});assertInsidePhoto(ui.fields().photoPrints[0]);
 await ui.byId('print-surface-undo').click();assert.equal(ui.fields().photoPrints[0].width,.24);
});

test('switching faces edits only the visible face and restores its previous selection',async()=>{
 const ui=harness({config:{...config(),photoPrints:[print('front-one'),{...print('back-one'),side:'BACK'},print('front-two')]}}),select=ui.byId('print-surface-selection');
 select.value='front-two';await select.dispatchEvent({type:'change'});ui.ctx.setSide('BACK');ui.surface.draw();assert.equal(select.value,'back-one');
 const width=ui.input('宽度（图片 %）');width.value='40';await width.dispatchEvent({type:'change'});assert.equal(ui.fields().photoPrints.find(p=>p.id==='front-two').width,.24);assert.equal(ui.fields().photoPrints.find(p=>p.id==='back-one').width,.4);
 ui.ctx.setSide('FRONT');ui.surface.draw();assert.equal(select.value,'front-two');ui.ctx.setSide('LEFT');ui.surface.draw();assert.equal(select.value,'');assert.equal(width.disabled,true);
});

test('outer template POST lock prevents changes after rendering has finished',async()=>{
 let locked=false;const ui=harness({isLocked:()=>locked});locked=true;ui.surface.draw();const width=ui.input('宽度（图片 %）');assert.equal(width.disabled,true);width.value='80';await width.dispatchEvent({type:'change'});
 await ui.host.dispatchEvent({type:'print-photo-change',detail:{id:'print-first',print:{x:.9},commit:true}});assert.equal(ui.fields().photoPrints[0].width,.24);assert.equal(ui.fields().photoPrints[0].x,.3);assert.equal(ui.byId('print-test-pattern-use').disabled,true);
 locked=false;ui.surface.draw();assert.equal(width.disabled,false);assert.equal(ui.byId('print-test-pattern-use').disabled,false);
});
