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
const print = id => ({id,artworkId:hash('a'),position:[0,.5,.2],normal:[0,0,1],tangent:[1,0,0],width:.24,height:.24,rotation:0,meshPart:'outer'});
const config = (label='first') => ({product:'T_SHIRT',areas:[],sourceImageId:hash('b'),referenceSetId:'reference-'+label,
 baseImages:{...bases},reconstruction:{vertices:[[0,0,0],[1,1,0],[0,1,1]],faces:[[0,1,2]],quality:{label}},surfacePrints:[print('print-'+label)],
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
  const e={tag,tagName:tag.toUpperCase(),children:[],handlers:{},style:{},attrs:{},disabled:false,hidden:false,value:'',files:[],textContent:text,
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
 const renderer={setSurfacePrints:async (...args)=>{if(options.setSurfacePrints)return options.setSurfacePrints(...args);},setSelectedPrint(){},setPlacementMode(value){placements.push(value);},
  exportSurfaceGuides:async()=>options.exportGuides?options.exportGuides():guides(surface.fields().surfacePrints.map(p=>p.id))};
 const api=async(url,request={})=>{requests.push({url,request});if(options.api){const r=await options.api(url,request);if(r!==undefined)return r;}
  if(url.endsWith('/base-images'))return {data:{id:hash('9')}};
  if(url.endsWith('/rendered-views'))return {data:{renderedViews:{FRONT:hash('1')},blankRenderedViews:{FRONT:hash('2')},surfaceUvViews:{FRONT:hash('3')},placementMasks:{FRONT:hash('4')},renderPrintIds:surface.fields().surfacePrints.map(p=>p.id),renderBinding:'new-binding'}};
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
  show3D:async()=>{if(options.show3D)await options.show3D();},changed:()=>edits++,legacyVisible(){},setUploading:value=>uploading.push(value),defaultName(){},
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

test('surface move and size history restores the whole source/model state and invalidates stale renders',async()=>{
 const ui=harness();const before=ui.fields();const width=ui.input('宽度（衣高 %）');width.value='40';await width.dispatchEvent({type:'change'});
 assert.equal(ui.fields().surfacePrints[0].width,.4);assert.deepEqual(ui.fields().renderedViews,{});assert.equal(ui.fields().renderBinding,null);
 await ui.byId('print-surface-undo').click();assert.deepEqual(ui.fields(),before);assert.equal(ui.configuration().reconstruction.quality.label,'first');
 await ui.byId('print-surface-redo').click();assert.equal(ui.fields().surfacePrints[0].width,.4);
 await ui.host.dispatchEvent({type:'print-surface-change',detail:{id:'print-first',print:{position:[.1,.6,.25]},commit:true}});
 assert.deepEqual(ui.fields().surfacePrints[0].position,[.1,.6,.25]);await ui.byId('print-surface-undo').click();assert.deepEqual(ui.fields().surfacePrints[0].position,before.surfacePrints[0].position);
});

test('switching templates clears undo history and cannot restore another template',async()=>{
 const ui=harness();ui.input('旋转 °').value='45';await ui.input('旋转 °').dispatchEvent({type:'change'});assert.equal(ui.byId('print-surface-undo').disabled,false);
 ui.load(config('second'));assert.equal(ui.byId('print-surface-undo').disabled,true);await ui.byId('print-surface-undo').click();assert.equal(ui.fields().referenceSetId,'reference-second');
});

test('late original upload after company switch cannot submit an AI reference job',async()=>{
 const upload=deferred();const ui=harness({api:async(url)=>url.endsWith('/base-images')?upload.promise:undefined});
 const running=ui.surface.uploadSource({name:'shirt.png',size:100},false);await tick();ui.company();upload.resolve({data:{id:hash('9')}});await running;
 assert.equal(ui.requests.filter(r=>r.url.endsWith('/references')).length,0);assert.equal(ui.fields().sourceImageId,null);assert.equal(ui.storage.size,0);
});

test('failed reference creation recovers the same request id instead of creating another attempt',async()=>{
 let calls=0;const ui=harness({api:async(url,request)=>{if(url.endsWith('/references')){calls++;if(calls===1)throw new Error('connection lost');return {data:{id:'job-one',status:'UNKNOWN',stage:'OUTCOME_UNKNOWN',message:'结果待核查',retryable:false}};}}});
 await ui.surface.uploadSource({name:'shirt.png',size:100},false);const first=JSON.parse(ui.requests.find(r=>r.url.endsWith('/references')).request.body);
 await ui.byId('print-reference-resume').click();const submissions=ui.requests.filter(r=>r.url.endsWith('/references'));assert.equal(submissions.length,2);assert.deepEqual(JSON.parse(submissions[1].request.body),first);
 const stored=JSON.parse(ui.storage.get('heyi:print-reference:org-one'));assert.equal(stored.id,'job-one');
 await ui.byId('print-reference-resume').click();assert.equal(ui.requests.filter(r=>r.url.endsWith('/references')).length,2,'known job must be queried by id');
});

test('task recovery storage is isolated by organization',async()=>{
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify({id:'old-org-job'})]]);const ui=harness({storage});ui.company('org-two');await ui.byId('print-reference-resume').click();assert.equal(ui.requests.length,0);
});

test('stopping reference polling ignores the in-flight status response and can resume the same job',async()=>{
 const status=deferred();let polls=0;const ui=harness({api:async(url)=>{if(url.endsWith('/references'))return {data:{id:'job-one',status:'SUBMITTING',message:'生成中'}};if(url.endsWith('/references/job-one')){polls++;return polls===1?status.promise:{data:{id:'job-one',status:'UNKNOWN',message:'待核查'}};}}});
 const running=ui.surface.uploadSource({name:'shirt.png',size:100},false);await tick();await ui.advance();await ui.button('停止等待').click();status.resolve({data:ready()});await running;
 assert.equal(ui.appliedReferences.length,0);await ui.byId('print-reference-resume').click();assert.equal(polls,2);assert.equal(ui.requests.filter(r=>r.url.endsWith('/references')).length,1);
});

test('late render archive response cannot replace the next template renders',async()=>{
 const saved=deferred();const ui=harness({api:async(url)=>url.endsWith('/rendered-views')?saved.promise:undefined});
 const running=ui.surface.prepareSave(ui.configuration());await ui.flush();ui.load(config('second'));
 saved.resolve({data:{renderedViews:{FRONT:hash('0')},blankRenderedViews:{},surfaceUvViews:{},placementMasks:{},renderPrintIds:['print-first'],renderBinding:'stale-first'}});
 await running.catch(()=>{});assert.equal(ui.fields().referenceSetId,'reference-second');assert.equal(ui.fields().renderBinding,'binding-second');assert.deepEqual(ui.fields().renderedViews,{FRONT:hash('c')});
});

test('an old save finally cannot unlock a new source upload after a template switch',async()=>{
 const saved=deferred(),upload=deferred();const ui=harness({api:async(url)=>url.endsWith('/rendered-views')?saved.promise:url.endsWith('/base-images')?upload.promise:undefined});
 const saving=ui.surface.prepareSave(ui.configuration());await ui.flush();ui.load(config('second'));const uploading=ui.surface.uploadSource({name:'second.png',size:100},false);await tick();
 assert.equal(ui.byId('print-surface-artwork').disabled,true);saved.resolve({data:{renderedViews:{},blankRenderedViews:{},surfaceUvViews:{},placementMasks:{},renderPrintIds:[],renderBinding:'old'}});await saving.catch(()=>{});
 assert.equal(ui.byId('print-surface-artwork').disabled,true,'the newer operation must retain its lock');assert.equal(ui.host.style.pointerEvents,'none');
 ui.company();upload.resolve({data:{id:hash('9')}});await uploading;
});

test('surface controls cannot mutate print dimensions while an export save is pending',async()=>{
 const exporting=deferred();const ui=harness({exportGuides:()=>exporting.promise});const running=ui.surface.prepareSave(ui.configuration());await tick();
 const width=ui.input('宽度（衣高 %）');assert.equal(width.disabled,true);width.value='55';await width.dispatchEvent({type:'change'});assert.equal(ui.fields().surfacePrints[0].width,.24);
 exporting.resolve(guides(['print-first']));await running;
});

test('refreshing the template list during save releases the old operation lock without committing stale renders',async()=>{
 const saved=deferred();const ui=harness({api:async(url)=>url.endsWith('/rendered-views')?saved.promise:undefined});
 const saving=ui.surface.prepareSave(ui.configuration());await ui.flush();ui.invalidateRevision();
 saved.resolve({data:{renderedViews:{FRONT:hash('0')},blankRenderedViews:{},surfaceUvViews:{},placementMasks:{},renderPrintIds:[],renderBinding:'stale'}});await saving.catch(()=>{});
 assert.equal(ui.fields().renderBinding,'binding-first');assert.equal(ui.byId('print-surface-artwork').disabled,false);assert.equal(ui.host.style.pointerEvents,'');
});

test('switching during texture preparation stops before exporting a stale renderer',async()=>{
 const textures=deferred();let exports=0,hold=false;const ui=harness({setSurfacePrints:()=>hold?textures.promise:Promise.resolve(),exportGuides:()=>{exports++;return guides(['print-first']);}});
 hold=true;const saving=ui.surface.prepareSave(ui.configuration());await tick();ui.load(config('second'));textures.resolve();await saving.catch(()=>{});
 assert.equal(exports,0);assert.equal(ui.fields().renderBinding,'binding-second');
});

test('late placement-mode activation cannot enable placement in a different template',async()=>{
 const showing=deferred();const ui=harness({show3D:()=>showing.promise});const running=ui.byId('print-surface-place').click();await tick();ui.load(config('second'));showing.resolve();await running;
 assert.equal(ui.placements.includes(true),false);
});

test('an old auto-build cannot consume the next reference task history or status',async()=>{
 const first=deferred(),second=deferred();let creates=0;
 const ui=harness({build:n=>n===1?first.promise:second.promise,api:async(url)=>url.endsWith('/references')?{data:ready('job-'+(++creates))}:undefined});
 const one=ui.surface.uploadSource({name:'first.png',size:100},false);await ui.flush();ui.load(config('second'));
 const two=ui.surface.uploadSource({name:'second.png',size:100},false);await ui.flush();const pendingText=ui.byId('print-reference-status').textContent;
 first.resolve({vertices:[[0,0,0],[1,1,0],[0,1,1]],faces:[[0,1,2]]});await one;
 assert.equal(ui.byId('print-reference-status').textContent,pendingText,'old build may not announce completion for the new build');assert.equal(ui.byId('print-surface-undo').disabled,true);
 second.resolve({vertices:[[0,0,0],[1,1,0],[0,1,1]],faces:[[0,1,2]]});await two;
 assert.equal(ui.fields().referenceSetId,'job-2');await ui.byId('print-surface-undo').click();assert.equal(ui.fields().referenceSetId,'reference-second');
});

test('automatic model replacement locks prior history until the new model is installed',async()=>{
 const build=deferred();const ui=harness({build:()=>build.promise});ui.input('旋转 °').value='30';await ui.input('旋转 °').dispatchEvent({type:'change'});
 assert.equal(ui.byId('print-surface-undo').disabled,false);const running=ui.surface.uploadSource({name:'new-shirt.png',size:100},false);await ui.flush();
 assert.equal(ui.byId('print-surface-undo').disabled,true,'history may not restore an older mesh while the new mesh is being fitted');assert.equal(ui.byId('print-surface-artwork').disabled,true);
 build.resolve({vertices:[[0,0,0],[1,1,0],[0,1,1]],faces:[[0,1,2]]});await running;assert.equal(ui.byId('print-surface-undo').disabled,false);
 await ui.byId('print-surface-undo').click();assert.equal(ui.fields().referenceSetId,'reference-first');assert.equal(ui.fields().surfacePrints[0].rotation,Math.PI/6);
});

test('recovery entry becomes visible after asynchronous login and route activation',()=>{
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify({id:'pending-job'})]]);
 const ui=harness({organization:'',storage});assert.equal(ui.byId('print-reference-resume').hidden,true);
 ui.login('org-one');ui.windowEvent('hashchange');assert.equal(ui.byId('print-reference-resume').hidden,false);
});

test('source selection immediately previews the local file then keeps its archived original while AI is pending',async()=>{
 const upload=deferred(),references=deferred();
 const ui=harness({api:async url=>url.endsWith('/base-images')?upload.promise:url.endsWith('/references')?references.promise:undefined});
 const image={name:'new-shirt.png',size:100};const running=ui.surface.uploadSource(image,false);
 assert.equal(ui.objectUrls.length,1);assert.equal(ui.objectUrls[0].file,image);
 assert.equal(ui.byId('print-source-preview').src,ui.objectUrls[0].url);
 assert.equal(ui.byId('print-source-card').hidden,false);assert.equal(ui.byId('print-source-name').textContent,image.name);
 assert.equal(ui.fields().sourceImageId,hash('b'),'an upload preview must not rebind the old fitted model');
 upload.resolve({data:{id:hash('9')}});await ui.flush();
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('9'));
 assert.equal(ui.byId('print-source-name').textContent,image.name);assert.equal(ui.fields().sourceImageId,hash('b'));
 references.resolve({data:{id:'job-one',status:'UNKNOWN',message:'结果待核查'}});await running;
 assert.equal(ui.byId('print-source-card').hidden,false);assert.equal(ui.byId('print-source-preview').hidden,false);
});

for(const status of ['FAILED','UNKNOWN'])test(status+' reference generation preserves uploaded source and recovery metadata without rebinding the current model',async()=>{
 const message=status==='FAILED'?'参考图生成失败':'结果待核查';
 const ui=harness({api:async url=>url.endsWith('/references')?{data:{id:'job-one',status,message,retryable:false}}:undefined});
 const before=ui.configuration();await ui.surface.uploadSource({name:'original-dress.jpg',size:100},false);
 const saved=JSON.parse(ui.storage.get('heyi:print-reference:org-one'));
 assert.equal(saved.fileName,'original-dress.jpg');assert.equal(saved.request.sourceImageId,hash('9'));
 assert.equal(saved.status,status);assert.ok(saved.message.includes(message));assert.equal(saved.id,'job-one');
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('9'));
 assert.equal(ui.byId('print-source-name').textContent,'original-dress.jpg');assert.equal(ui.byId('print-source-card').hidden,false);
 assert.equal(ui.fields().sourceImageId,hash('b'));assert.deepEqual(ui.configuration().reconstruction,before.reconstruction);
 assert.deepEqual(ui.fields().surfacePrints,before.surfacePrints);assert.equal(ui.buildCount(),0);
});

test('reference POST connection failure leaves the archived source and named recovery request available',async()=>{
 const ui=harness({api:async url=>{if(url.endsWith('/references'))throw new Error('connection lost');}});
 await ui.surface.uploadSource({name:'connection-test.png',size:100},false);
 const stored=JSON.parse(ui.storage.get('heyi:print-reference:org-one'));
 assert.equal(stored.fileName,'connection-test.png');assert.equal(stored.request.sourceImageId,hash('9'));
 assert.ok(stored.status);assert.match(stored.message,/connection lost/);
 assert.equal(ui.byId('print-source-name').textContent,'connection-test.png');assert.equal(ui.byId('print-source-card').hidden,false);
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('9'));
 assert.equal(ui.byId('print-reference-resume').hidden,false);assert.equal(ui.fields().sourceImageId,hash('b'));
});

test('refresh restores the pending upload thumbnail and filename before requesting its existing job',async()=>{
 const pending={id:'job-one',fileName:'recover-me.png',request:{sourceImageId:hash('9'),alreadyPlain:false,requestId:'original-request'},status:'UNKNOWN',message:'结果待核查'};
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify(pending)]]);
 const ui=harness({storage,config:{product:'T_SHIRT',areas:[],baseImages:{},reconstruction:null},api:async url=>url.endsWith('/references/job-one')?{data:{...pending,retryable:false}}:undefined});
 assert.equal(ui.requests.length,0);assert.equal(ui.byId('print-reference-resume').hidden,false);
 assert.equal(ui.byId('print-source-card').hidden,false);assert.equal(ui.byId('print-source-name').textContent,pending.fileName);
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('9'));
 assert.equal(ui.fields().sourceImageId,null);await ui.byId('print-reference-resume').click();
 assert.equal(ui.requests.length,1);assert.ok(ui.requests[0].url.endsWith('/references/job-one'));
 assert.notEqual(ui.requests[0].request.method,'POST');assert.equal(ui.objectUrls.length,0);
});

test('a saved template keeps its own original until the user explicitly resumes a different pending upload',async()=>{
 const pending={id:'job-one',fileName:'other-upload.png',request:{sourceImageId:hash('9'),alreadyPlain:false,requestId:'original-request'},status:'UNKNOWN',message:'结果待核查'};
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify(pending)]]);
 const ui=harness({storage,api:async url=>url.endsWith('/references/job-one')?{data:{...pending,retryable:false}}:undefined});
 const original=ui.configuration().reconstruction;
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('b'));
 assert.notEqual(ui.byId('print-source-name').textContent,pending.fileName);assert.equal(ui.byId('print-reference-resume').hidden,false);
 await ui.byId('print-reference-resume').click();
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('9'));
 assert.equal(ui.byId('print-source-name').textContent,pending.fileName);assert.equal(ui.fields().sourceImageId,hash('b'));
 assert.deepEqual(ui.configuration().reconstruction,original);assert.equal(ui.buildCount(),0);
});

test('ready references remain recoverable until the matching source and reference set are successfully saved',async()=>{
 const ui=harness();await ui.surface.uploadSource({name:'ready-shirt.png',size:100},false);
 const storageKey='heyi:print-reference:org-one',completed=JSON.parse(ui.storage.get(storageKey));
 assert.ok(completed,'READY must remain recoverable until its template has been saved');assert.equal(completed.status,'READY');
 assert.equal(completed.fileName,'ready-shirt.png');assert.equal(ui.byId('print-source-name').textContent,'ready-shirt.png');
 const saved={...ui.configuration(),...ui.fields()};
 await ui.surface.saved({...saved,sourceImageId:hash('8')});assert.ok(ui.storage.has(storageKey),'saving a different source must not discard this task');
 await ui.surface.saved({...saved,referenceSetId:'another-reference'});assert.ok(ui.storage.has(storageKey),'saving a different reference group must not discard this task');
 await ui.surface.saved(saved);assert.equal(ui.storage.has(storageKey),false);
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('9'));
 assert.equal(ui.byId('print-source-card').hidden,false,'saving must preserve the original source preview');
});

test('recovering an already fitted READY job only queries it and preserves the model and surface prints',async()=>{
 const current={...config(),sourceImageId:hash('9'),referenceSetId:'job-one',surfacePrints:[print('carefully-positioned')]};
 const pending={id:'job-one',fileName:'fitted-shirt.png',request:{sourceImageId:hash('9'),alreadyPlain:false,requestId:'original-request'},status:'READY',message:'四面已完成'};
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify(pending)]]);
 const ui=harness({storage,config:current});const before=ui.fields(),mesh=ui.configuration().reconstruction;
 await ui.byId('print-reference-resume').click();await ui.byId('print-reference-resume').click();
 assert.equal(ui.requests.length,2);assert.ok(ui.requests.every(r=>r.url.endsWith('/references/job-one')&&r.request.method!=='POST'));
 assert.equal(ui.buildCount(),0);assert.equal(ui.appliedReferences.length,0);assert.deepEqual(ui.fields(),before);
 assert.deepEqual(ui.configuration().reconstruction,mesh);assert.ok(ui.storage.has('heyi:print-reference:org-one'));
});

test('choosing another source releases the old local blob and shows the new selection immediately',async()=>{
 const ui=harness({api:async url=>{if(url.endsWith('/base-images'))throw new Error('upload interrupted');}});
 await ui.surface.uploadSource({name:'first-file.png',size:100},false);
 const first=ui.objectUrls[0].url;assert.equal(ui.byId('print-source-preview').src,first);
 await ui.surface.uploadSource({name:'second-file.png',size:100},false);
 assert.ok(ui.revokedUrls.includes(first));assert.equal(ui.objectUrls.length,2);
 assert.equal(ui.byId('print-source-preview').src,ui.objectUrls[1].url);
 assert.equal(ui.byId('print-source-name').textContent,'second-file.png');
});

test('template switch releases the selected blob and an old upload cannot overwrite the next template preview',async()=>{
 const upload=deferred();const ui=harness({api:async url=>url.endsWith('/base-images')?upload.promise:undefined});
 const running=ui.surface.uploadSource({name:'uncommitted-shirt.png',size:100},false),blob=ui.objectUrls[0].url;
 const next={...config('second'),sourceImageId:hash('8')};ui.load(next);
 assert.ok(ui.revokedUrls.includes(blob));assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('8'));
 assert.notEqual(ui.byId('print-source-name').textContent,'uncommitted-shirt.png');
 upload.resolve({data:{id:hash('9')}});await running;
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('8'));
 assert.equal(ui.requests.filter(r=>r.url.endsWith('/references')).length,0);
});

test('company switch releases local blobs and never restores another tenant source from recovery storage',async()=>{
 const upload=deferred();const ui=harness({api:async url=>url.endsWith('/base-images')?upload.promise:undefined});
 const running=ui.surface.uploadSource({name:'private-source.png',size:100},false),blob=ui.objectUrls[0].url;
 ui.storage.set('heyi:print-reference:org-one',JSON.stringify({id:'private-job',fileName:'private-source.png',request:{sourceImageId:hash('9')},status:'UNKNOWN'}));
 ui.company('org-two');ui.windowEvent('hashchange');
 assert.ok(ui.revokedUrls.includes(blob));assert.equal(ui.byId('print-source-card').hidden,true);
 assert.equal(ui.byId('print-source-preview').hidden,true);assert.ok(!ui.byId('print-source-preview').src);
 assert.notEqual(ui.byId('print-source-name').textContent,'private-source.png');assert.equal(ui.byId('print-reference-resume').hidden,true);
 upload.resolve({data:{id:hash('9')}});await running;assert.equal(ui.byId('print-source-card').hidden,true);
 assert.equal(ui.requests.filter(r=>r.url.endsWith('/references')).length,0);assert.ok(ui.storage.has('heyi:print-reference:org-one'));
});

test('finding an uploaded original only reads samples and hashes the selected image until generation is explicitly requested',async()=>{
 const ui=harness({api:async url=>url.endsWith('/commerce-samples')?{data:[archiveSample,
  {...archiveSample,id:'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',model:'GENERATIVE_MODEL'},
  {...archiveSample,id:'bbbbbbbb-cccc-4ddd-8eee-ffffffffffff',status:'SUBMITTING'}]}:undefined});
 const before=ui.fields(),model=ui.configuration().reconstruction;
 await ui.byId('print-source-history').click();
 assert.equal(ui.requests.length,1);assert.equal(ui.requests[0].url,'/api/v1/commerce-samples');assert.notEqual(ui.requests[0].request.method,'POST');
 assert.equal(ui.fetches.length,0);assert.equal(ui.digests.length,0);assert.equal(historyPanel(ui).children.length,1);
 await historyPanel(ui).children[0].click();
 assert.equal(ui.requests.length,1,'recovering an original must not submit a reference generation request');
 assert.equal(ui.fetches.length,1);assert.equal(ui.fetches[0].url,'/api/v1/commerce-samples/'+archiveSample.id+'/image');
 assert.equal(ui.fetches[0].request.credentials,'same-origin');assert.notEqual(ui.fetches[0].request.method,'POST');
 assert.equal(ui.digests.length,1);assert.equal(ui.digests[0].algorithm,'SHA-256');assert.deepEqual(Buffer.from(ui.digests[0].bytes),archivedImage);
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+archivedHash);
 assert.equal(ui.byId('print-source-card').hidden,false);assert.equal(ui.byId('print-reference-resume').textContent,'生成 3D 模型');
 assert.equal(historyPanel(ui).hidden,true);assert.deepEqual(ui.fields(),before);assert.deepEqual(ui.configuration().reconstruction,model);assert.equal(ui.buildCount(),0);
 const pending=JSON.parse(ui.storage.get('heyi:print-reference:org-one'));assert.equal(pending.request.sourceImageId,archivedHash);assert.equal(pending.notStarted,true);
});

test('recovering a source already associated with a remembered job preserves its request and queries that job instead of creating another',async()=>{
 const old={id:'existing-job',fileName:'remembered-original.png',request:{sourceImageId:archivedHash,alreadyPlain:true,requestId:'do-not-replace'},status:'UNKNOWN',message:'待核查'};
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify(old)]]);
 const ui=harness({storage,api:async url=>url.endsWith('/commerce-samples')?{data:[archiveSample]}:url.endsWith('/references/existing-job')?{data:{id:'existing-job',status:'UNKNOWN',message:'待核查'}}:undefined});
 await ui.byId('print-source-history').click();await historyPanel(ui).children[0].click();
 const recovered=JSON.parse(ui.storage.get('heyi:print-reference:org-one'));
 assert.equal(recovered.id,'existing-job');assert.deepEqual(recovered.request,old.request);assert.equal(recovered.fileName,old.fileName);
 assert.notEqual(recovered.notStarted,true);assert.equal(ui.byId('print-reference-resume').textContent,'继续生成 3D 模型');
 assert.equal(ui.requests.length,1);await ui.byId('print-reference-resume').click();
 assert.equal(ui.requests.length,2);assert.ok(ui.requests[1].url.endsWith('/references/existing-job'));
 assert.ok(ui.requests.every(r=>r.request.method!=='POST'));assert.equal(ui.fields().sourceImageId,hash('b'));assert.equal(ui.buildCount(),0);
});

for(const change of ['template','company'])test('late uploaded-image history response after '+change+' switch cannot repopulate the other context',async()=>{
 const samples=deferred();const ui=harness({api:async url=>url.endsWith('/commerce-samples')?samples.promise:undefined});
 const listing=ui.byId('print-source-history').click();await ui.flush();
 if(change==='company')ui.company('org-two');else ui.load({...config('second'),sourceImageId:hash('8')});
 const fields=ui.fields(),preview=ui.byId('print-source-preview').src,status=ui.byId('print-reference-status').textContent;
 samples.resolve({data:[archiveSample]});await listing;
 assert.equal(historyPanel(ui).hidden,true);assert.equal(historyPanel(ui).children.length,0);
 assert.deepEqual(ui.fields(),fields);assert.equal(ui.byId('print-source-preview').src,preview);assert.equal(ui.byId('print-reference-status').textContent,status);
 assert.equal(ui.fetches.length,0);assert.equal(ui.digests.length,0);assert.equal(ui.storage.size,0);
 assert.equal(ui.requests.length,1);assert.ok(ui.requests.every(r=>r.request.method!=='POST'));
});

for(const change of ['template','company'])test('late recovered image bytes after '+change+' switch cannot replace the preview or create a task',async()=>{
 const response=deferred();const ui=harness({api:async url=>url.endsWith('/commerce-samples')?{data:[archiveSample]}:undefined,fetch:()=>response.promise});
 await ui.byId('print-source-history').click();const recovering=historyPanel(ui).children[0].click();await ui.flush();
 if(change==='company')ui.company('org-two');else ui.load({...config('second'),sourceImageId:hash('8')});
 const fields=ui.fields(),preview=ui.byId('print-source-preview').src,status=ui.byId('print-reference-status').textContent;
 response.resolve({ok:true,arrayBuffer:async()=>archivedImage.buffer.slice(archivedImage.byteOffset,archivedImage.byteOffset+archivedImage.byteLength)});await recovering;
 assert.equal(historyPanel(ui).hidden,true);assert.equal(historyPanel(ui).children.length,0);
 assert.deepEqual(ui.fields(),fields);assert.equal(ui.byId('print-source-preview').src,preview);assert.equal(ui.byId('print-reference-status').textContent,status);
 assert.equal(ui.storage.size,0);assert.equal(ui.requests.length,1);assert.equal(ui.buildCount(),0);
 assert.ok(ui.requests.every(r=>r.request.method!=='POST'));assert.equal(ui.fetches.length,1);
});

test('failed original image download keeps the existing model and does not invent a pending generation task',async()=>{
 const ui=harness({api:async url=>url.endsWith('/commerce-samples')?{data:[archiveSample]}:undefined,fetch:async()=>({ok:false})});
 const before=ui.fields();await ui.byId('print-source-history').click();await historyPanel(ui).children[0].click();
 assert.equal(ui.digests.length,0);assert.deepEqual(ui.fields(),before);assert.equal(ui.storage.size,0);assert.equal(ui.requests.length,1);
 assert.equal(ui.byId('print-source-preview').src,'/api/v1/commerce-print-templates/base-images/'+hash('b'));
 assert.match(ui.byId('print-reference-status').textContent,/原图读取失败/);assert.equal(ui.byId('print-source-history').disabled,false);
});

const patternKinds=['starburst','flower','checker'];
const emptyPrintConfig=()=>({...config(),surfacePrints:[],renderedViews:{},blankRenderedViews:{},surfaceUvViews:{},placementMasks:{},renderPrintIds:[],renderBinding:null});
const usePattern=async(ui,kind='starburst')=>{await ui.byId('print-test-pattern-'+kind).click();return ui.byId('print-test-pattern-use').click();};
const pickPattern=(ui,index=0)=>ui.host.dispatchEvent({type:'print-surface-pick',detail:{position:[index*.1,.5,.2],normal:[0,0,1],tangent:[1,0,0],meshPart:'outer'}});
const artworkImage=ui=>ui.nodes.find(n=>n.className==='print-artwork-thumb');
const changeContext=(ui,change)=>change==='company'?ui.company('org-two'):ui.load({...config('second'),sourceImageId:hash('8')});

test('test pattern choices default to starburst and only the chosen button is pressed',async()=>{
 const ui=harness({config:emptyPrintConfig()});
 for(const kind of patternKinds)assert.equal(ui.byId('print-test-pattern-'+kind).attrs['aria-pressed'],String(kind==='starburst'));
 await ui.byId('print-test-pattern-flower').click();
 for(const kind of patternKinds)assert.equal(ui.byId('print-test-pattern-'+kind).attrs['aria-pressed'],String(kind==='flower'));
 assert.equal(ui.requests.length,0,'choosing a pattern must not upload or generate anything');
 assert.equal(ui.byId('print-test-pattern-use').textContent,'使用测试花纹');
});

test('test pattern archives the fixed transparent PNG bytes without a canvas encoding or AI call',async()=>{
 const ui=harness({config:emptyPrintConfig()}),before=ui.fields();
 await usePattern(ui);
 assert.equal(ui.encodings.length,0);const canvas=ui.canvases[0];assert.ok(canvas.width>=128&&canvas.height>=128);
 assert.ok(canvas.calls.some(c=>c.op==='clearRect'&&c.args[0]===0&&c.args[1]===0&&c.args[2]===canvas.width&&c.args[3]===canvas.height),'clear the transparent canvas before drawing');
 assert.ok(!canvas.calls.some(c=>c.op==='fillRect'&&c.args[0]===0&&c.args[1]===0&&c.args[2]===canvas.width&&c.args[3]===canvas.height),'do not paint an opaque full-canvas background');
 assert.equal(ui.requests.length,1);const upload=ui.requests[0];assert.equal(upload.url,'/api/v1/commerce-print-templates/base-images');assert.equal(upload.request.method,'POST');
 const image=upload.request.body.fields.get('file');assert.ok(image instanceof Blob);assert.equal(image.type,'image/png');assert.ok(image.size>0);
 assert.deepEqual(Buffer.from(await image.arrayBuffer()),patternPng('starburst'),'archive the fixed asset bytes unchanged');
 assert.match(upload.request.body.filenames.get('file')||image.name,/\.png$/);
 assert.equal(artworkImage(ui).src,'/api/v1/commerce-print-templates/base-images/'+hash('9'));assert.equal(ui.placements.at(-1),true);
 assert.deepEqual(ui.fields(),before,'choosing artwork does not place a print until the model is clicked');
 assert.equal(ui.buildCount(),0);assert.equal(ui.fetches.length,1);assert.match(ui.fetches[0].url,/\/assets\/print-patterns\/starburst\.png\?v=91ba0/);assert.equal(ui.storage.size,0);
});

test('fixed marker identity matches existing saved templates and is stable across separate browser contexts',async()=>{
 const legacyHashes=['91ba0bee73369b694cea7345dc7269da99a99fb70b4b94db8de0b18efb17b9e7','a640e3d7cd32283257eb2b9ead481de175247712e73f2597323ccb6d9a2000c0','fe522dfad98c24b65844a62978c6b9c1ccd48a0e4831aced03df6317a7e13a82'];
 const create=()=>harness({config:emptyPrintConfig(),canvasToBlob:()=>assert.fail('formal patterns cannot depend on browser canvas encoding'),api:async(url,request)=>{
  if(url.endsWith('/base-images'))return {data:{id:createHash('sha256').update(Buffer.from(await request.body.fields.get('file').arrayBuffer())).digest('hex')}};
 }});
 const first=create();for(const [index,kind] of patternKinds.entries()){const png=patternPng(kind);assert.equal(png.readUInt32BE(16),512);assert.equal(png.readUInt32BE(20),512);assert.equal(png[25],6,'RGBA PNG preserves transparent surroundings');await usePattern(first,kind);await pickPattern(first,index);}
 assert.deepEqual(first.fields().surfacePrints.map(p=>p.artworkId),legacyHashes);
 await usePattern(first,'checker');await pickPattern(first,3);assert.equal(first.fields().surfacePrints[3].artworkId,legacyHashes[2]);
 const second=create();await usePattern(second,'checker');await pickPattern(second);assert.equal(second.fields().surfacePrints[0].artworkId,legacyHashes[2]);
 assert.equal(first.encodings.length,0);assert.equal(second.encodings.length,0);
});

test('a substituted or corrupt fixed asset never changes a marker group or reaches the archive',async()=>{
 const ui=harness({fetch:async()=>({ok:true,arrayBuffer:async()=>encodedCanvasPng.buffer.slice(encodedCanvasPng.byteOffset,encodedCanvasPng.byteOffset+encodedCanvasPng.byteLength)})}),before=ui.fields();
 await usePattern(ui,'checker');assert.deepEqual(ui.fields(),before);assert.equal(ui.requests.length,0);assert.equal(ui.placements.includes(true),false);
 assert.match(ui.byId('print-surface-status').textContent,/内容不完整/);assert.equal(ui.byId('print-test-pattern-use').disabled,false);
});

test('three fixed test patterns coexist and edits, history, save and reload preserve their individual artwork',async()=>{
 let archives=0;const ui=harness({config:emptyPrintConfig(),api:async url=>url.endsWith('/base-images')?{data:{id:hash(String(++archives))}}:undefined});
 for(const [index,kind] of patternKinds.entries()){await usePattern(ui,kind);await pickPattern(ui,index);}
 const prints=ui.fields().surfacePrints;assert.equal(prints.length,3);assert.equal(new Set(prints.map(p=>p.id)).size,3);
 assert.deepEqual(prints.map(p=>p.artworkId),[hash('1'),hash('2'),hash('3')]);
 assert.equal(ui.encodings.length,0);assert.equal(new Set(ui.fetches.map(e=>e.url)).size,3,'the three choices use distinct fixed assets');
 for(const [label,value] of [['宽度（衣高 %）','42'],['高度（衣高 %）','31'],['旋转 °','60']]){const input=ui.input(label);input.value=value;await input.dispatchEvent({type:'change'});}
 const changed=ui.fields().surfacePrints;assert.deepEqual(changed.slice(0,2),prints.slice(0,2));assert.equal(changed[2].width,.42);assert.equal(changed[2].height,.31);assert.equal(changed[2].rotation,Math.PI/3);
 const selected=ui.byId('print-surface-selection');selected.value=prints[0].id;await selected.dispatchEvent({type:'change'});
 ui.input('宽度（衣高 %）').value='20';await ui.input('宽度（衣高 %）').dispatchEvent({type:'change'});
 assert.equal(ui.fields().surfacePrints[0].width,.2);assert.deepEqual(ui.fields().surfacePrints.slice(1),changed.slice(1));
 await ui.button('删除印花').click();assert.equal(ui.fields().surfacePrints.length,2);
 await ui.byId('print-surface-undo').click();assert.equal(ui.fields().surfacePrints.length,3);assert.equal(ui.fields().surfacePrints[0].artworkId,hash('1'));
 await ui.byId('print-surface-redo').click();assert.equal(ui.fields().surfacePrints.length,2);await ui.byId('print-surface-undo').click();
 const expected=ui.fields().surfacePrints,saved=await ui.surface.prepareSave(ui.configuration());
 const archive=ui.requests.find(r=>r.url.endsWith('/rendered-views'));assert.ok(archive);const payload=JSON.parse(archive.request.body.fields.get('configuration'));
 assert.deepEqual(payload.surfacePrints,expected);assert.deepEqual(saved.surfacePrints,expected);assert.deepEqual(saved.renderPrintIds,expected.map(p=>p.id));
 ui.load(saved);assert.deepEqual(ui.fields().surfacePrints,expected);assert.equal(ui.byId('print-surface-undo').disabled,true);
 assert.equal(ui.requests.filter(r=>r.url.endsWith('/base-images')).length,3);assert.ok(ui.requests.every(r=>r.url.endsWith('/base-images')||r.url.endsWith('/rendered-views')));
});

test('placing and restoring the thirty-second print never permits a thirty-third or an unnecessary pattern upload',async()=>{
 const ui=harness({config:{...config(),surfacePrints:Array.from({length:31},(_,i)=>print('existing-'+i))}});
 await usePattern(ui,'checker');await pickPattern(ui,31);assert.equal(ui.fields().surfacePrints.length,32);
 assert.equal(ui.byId('print-test-pattern-use').disabled,true);assert.equal(ui.byId('print-surface-place').disabled,true);
 const uploads=ui.requests.length,fetches=ui.fetches.length;await ui.byId('print-test-pattern-use').click();await pickPattern(ui,32);
 assert.equal(ui.fields().surfacePrints.length,32);assert.equal(ui.requests.length,uploads);assert.equal(ui.fetches.length,fetches);
 await ui.byId('print-surface-undo').click();assert.equal(ui.fields().surfacePrints.length,31);assert.equal(ui.byId('print-test-pattern-use').disabled,false);
 await ui.byId('print-surface-redo').click();assert.equal(ui.fields().surfacePrints.length,32);assert.equal(ui.byId('print-test-pattern-use').disabled,true);
});

test('failed test-pattern upload keeps existing prints and can retry the selected pattern',async()=>{
 let uploads=0;const ui=harness({api:async url=>{if(url.endsWith('/base-images')&&++uploads===1)throw new Error('pattern archive interrupted');}}),before=ui.fields();
 await usePattern(ui,'checker');assert.deepEqual(ui.fields(),before);assert.equal(ui.placements.includes(true),false);
 assert.match(ui.byId('print-surface-status').textContent,/pattern archive interrupted/);assert.equal(ui.byId('print-test-pattern-use').disabled,false);
 assert.equal(ui.byId('print-test-pattern-checker').attrs['aria-pressed'],'true');assert.equal(ui.host.style.pointerEvents,'');
 await ui.byId('print-test-pattern-use').click();assert.equal(uploads,2);assert.equal(ui.placements.at(-1),true);await pickPattern(ui);
 assert.equal(ui.fields().surfacePrints.length,2);assert.deepEqual(ui.fields().surfacePrints[0],before.surfacePrints[0]);
 assert.equal(ui.fetches[0].url,ui.fetches[1].url);assert.ok(ui.requests.every(r=>r.url.endsWith('/base-images')));
});

test('failed fixed PNG fetch does not upload an empty artifact and releases the pattern controls for retry',async()=>{
 let fetches=0;const ui=harness({fetch:async()=>++fetches===1?{ok:false}:patternResponse('flower')}),before=ui.fields();
 await usePattern(ui,'flower');assert.equal(ui.requests.length,0);assert.deepEqual(ui.fields(),before);
 assert.equal(ui.byId('print-test-pattern-use').disabled,false);assert.equal(ui.host.style.pointerEvents,'');assert.equal(ui.placements.includes(true),false);
 await ui.byId('print-test-pattern-use').click();assert.equal(ui.requests.length,1);assert.equal(ui.placements.at(-1),true);
});

for(const stage of ['asset','archive','preview'])for(const change of ['template','company'])test('late test-pattern '+stage+' after '+change+' switch cannot overwrite artwork, status or placement',{timeout:5000},async()=>{
 const gate=deferred(),entered=deferred();const options={};
 if(stage==='asset')options.fetch=()=>{entered.resolve();return gate.promise;};
 if(stage==='archive')options.api=async url=>{if(url.endsWith('/base-images')){entered.resolve();return gate.promise;}};
 if(stage==='preview')options.show3D=()=>{entered.resolve();return gate.promise;};
 const ui=harness(options);const running=usePattern(ui,'flower');await entered.promise;
 assert.equal(ui.byId('print-test-pattern-use').disabled,true);changeContext(ui,change);
 const before=ui.fields(),artwork=artworkImage(ui).src,status=ui.byId('print-surface-status').textContent,reference=ui.byId('print-reference-status').textContent;
 gate.resolve(stage==='asset'?patternResponse('flower'):stage==='archive'?{data:{id:hash('9')}}:undefined);
 await running;assert.deepEqual(ui.fields(),before);assert.equal(artworkImage(ui).src,artwork);assert.equal(ui.byId('print-surface-status').textContent,status);assert.equal(ui.byId('print-reference-status').textContent,reference);
 assert.equal(ui.placements.includes(true),false);assert.equal(ui.storage.size,0);assert.equal(ui.buildCount(),0);assert.equal(ui.requests.length,stage==='asset'?0:1);
 assert.ok(ui.requests.every(r=>r.url.endsWith('/base-images')));assert.equal(ui.host.style.pointerEvents,'');
});

test('an old pattern archive completion cannot unlock a newer pending pattern operation',{timeout:5000},async()=>{
 const old=deferred(),next=deferred(),oldEntered=deferred(),nextEntered=deferred();let uploads=0;
 const ui=harness({api:async url=>{if(url.endsWith('/base-images')){if(++uploads===1){oldEntered.resolve();return old.promise;}nextEntered.resolve();return next.promise;}}});
 const first=usePattern(ui,'starburst');await oldEntered.promise;ui.load(config('second'));const second=usePattern(ui,'flower');await nextEntered.promise;
 const currentStatus=ui.byId('print-surface-status').textContent;old.resolve({data:{id:hash('1')}});await first;
 assert.equal(ui.byId('print-test-pattern-use').disabled,true);assert.equal(ui.byId('print-surface-artwork').disabled,true);assert.equal(ui.host.style.pointerEvents,'none');
 assert.equal(ui.byId('print-surface-status').textContent,currentStatus);assert.equal(ui.placements.includes(true),false);
 next.resolve({data:{id:hash('2')}});await second;assert.equal(artworkImage(ui).src,'/api/v1/commerce-print-templates/base-images/'+hash('2'));assert.equal(ui.placements.at(-1),true);assert.equal(ui.byId('print-test-pattern-use').disabled,false);
});

test('repeated clicks during one PNG fetch or upload create only one archive request',{timeout:5000},async()=>{
 const asset=deferred(),upload=deferred(),assetEntered=deferred(),uploadEntered=deferred();const ui=harness({fetch:()=>{assetEntered.resolve();return asset.promise;},api:async url=>{if(url.endsWith('/base-images')){uploadEntered.resolve();return upload.promise;}}});
 const running=usePattern(ui,'checker');await assetEntered.promise;await ui.byId('print-test-pattern-use').click();await ui.byId('print-test-pattern-flower').click();
 assert.equal(ui.fetches.length,1);assert.equal(ui.encodings.length,0);assert.equal(ui.byId('print-test-pattern-checker').attrs['aria-pressed'],'true');
 asset.resolve(patternResponse('checker'));await uploadEntered.promise;await ui.byId('print-test-pattern-use').click();assert.equal(ui.requests.length,1);
 upload.resolve({data:{id:hash('9')}});await running;assert.equal(ui.requests.length,1);assert.equal(ui.placements.at(-1),true);
});

test('a test pattern cannot be archived or placed before a model exists',async()=>{
 const ui=harness({config:{product:'T_SHIRT',areas:[],baseImages:{},reconstruction:null,surfacePrints:[]}});
 assert.equal(ui.byId('print-test-pattern-use').disabled,true);await usePattern(ui,'flower');await pickPattern(ui);
 assert.equal(ui.encodings.length,0);assert.equal(ui.requests.length,0);assert.equal(ui.fields().surfacePrints.length,0);assert.equal(ui.placements.includes(true),false);
});

test('optional manual artwork upload still archives the chosen PNG and uses the same placement path',async()=>{
 const ui=harness(),before=ui.fields(),input=ui.byId('print-surface-artwork');
 const image=new File([encodedCanvasPng],'my-artwork.png',{type:'image/png'});input.files=[image];input.value=image.name;
 await input.dispatchEvent({type:'change'});assert.equal(ui.requests.length,1);assert.equal(ui.requests[0].url,'/api/v1/commerce-print-templates/base-images');
 assert.equal(ui.requests[0].request.body.fields.get('file'),image);assert.equal(ui.encodings.length,0);assert.equal(input.value,'');assert.equal(ui.placements.at(-1),true);
 assert.deepEqual(ui.fields(),before);await pickPattern(ui);assert.equal(ui.fields().surfacePrints.length,before.surfacePrints.length+1);
 assert.deepEqual(ui.fields().surfacePrints[0],before.surfacePrints[0]);assert.equal(ui.fields().surfacePrints.at(-1).artworkId,hash('9'));assert.equal(ui.buildCount(),0);
});

test('deleting the selected pattern switches the artwork preview and subsequent placement to the remaining selected pattern',async()=>{
 let archives=0;const ui=harness({config:emptyPrintConfig(),api:async url=>url.endsWith('/base-images')?{data:{id:hash(String(++archives))}}:undefined});
 await usePattern(ui,'starburst');await pickPattern(ui,0);await usePattern(ui,'flower');await pickPattern(ui,1);
 const [starburst,flower]=ui.fields().surfacePrints;assert.equal(ui.byId('print-surface-selection').value,flower.id);
 assert.equal(artworkImage(ui).src,'/api/v1/commerce-print-templates/base-images/'+flower.artworkId);
 await ui.button('删除印花').click();assert.deepEqual(ui.fields().surfacePrints,[starburst]);assert.equal(ui.byId('print-surface-selection').value,starburst.id);
 assert.equal(artworkImage(ui).src,'/api/v1/commerce-print-templates/base-images/'+starburst.artworkId);
 await ui.byId('print-surface-place').click();assert.equal(ui.placements.at(-1),true);await pickPattern(ui,2);
 const prints=ui.fields().surfacePrints;assert.equal(prints.length,2);assert.deepEqual(prints[0],starburst);assert.notEqual(prints[1].id,starburst.id);
 assert.equal(prints[1].artworkId,starburst.artworkId);assert.notEqual(prints[1].artworkId,flower.artworkId);assert.equal(archives,2);
});

test('a committed corner resize changes only its print and preserves model-unit dimensions through history, save and reload',async()=>{
 const model={vertices:[[0,-1,0],[1,1.5,0],[0,-1,1]],faces:[[0,1,2]]};
 let archives=0;const ui=harness({config:{...emptyPrintConfig(),reconstruction:model},api:async url=>url.endsWith('/base-images')?{data:{id:hash(String(++archives))}}:undefined});
 for(const [index,kind] of patternKinds.entries()){await usePattern(ui,kind);await pickPattern(ui,index);}
 const before=ui.fields().surfacePrints,target=before[1],resized={...target,width:.875,height:1.125};
 assert.equal(new Set(before.map(p=>p.artworkId)).size,3);assert.equal(ui.byId('print-surface-selection').value,before[2].id);
 await ui.host.dispatchEvent({type:'print-surface-change',detail:{id:target.id,print:resized,previous:target,commit:true,reason:'resize'}});
 assert.deepEqual(ui.fields().surfacePrints,[before[0],resized,before[2]]);assert.equal(ui.byId('print-surface-selection').value,target.id);
 assert.equal(ui.input('宽度（衣高 %）').value,'35.0');assert.equal(ui.input('高度（衣高 %）').value,'45.0');
 assert.deepEqual(ui.configuration().reconstruction,model);
 await ui.byId('print-surface-undo').click();assert.deepEqual(ui.fields().surfacePrints,before);
 await ui.byId('print-surface-redo').click();assert.deepEqual(ui.fields().surfacePrints,[before[0],resized,before[2]]);
 const saved=await ui.surface.prepareSave(ui.configuration()),archive=ui.requests.find(r=>r.url.endsWith('/rendered-views'));
 assert.ok(archive);assert.deepEqual(JSON.parse(archive.request.body.fields.get('configuration')).surfacePrints,[before[0],resized,before[2]]);
 assert.deepEqual(saved.surfacePrints,[before[0],resized,before[2]]);ui.load(saved);
 assert.deepEqual(ui.fields().surfacePrints,[before[0],resized,before[2]]);assert.deepEqual(ui.configuration().reconstruction,model);
 ui.byId('print-surface-selection').value=target.id;await ui.byId('print-surface-selection').dispatchEvent({type:'change'});
 assert.equal(ui.input('宽度（衣高 %）').value,'35.0');assert.equal(ui.input('高度（衣高 %）').value,'45.0');
});

test('cancelled corner resize events preserve print state, render binding and empty history',async()=>{
 const ui=harness(),before=ui.fields(),target=before.surfacePrints[0],edits=ui.edits();
 for(const reason of ['resize','resize-cancel','cancel']){
  await ui.host.dispatchEvent({type:'print-surface-change',detail:{id:target.id,print:{...target,width:.5,height:.7},previous:target,commit:false,reason}});
  assert.deepEqual(ui.fields(),before);assert.equal(ui.fields().renderBinding,'binding-first');
  assert.equal(ui.byId('print-surface-undo').disabled,true);assert.equal(ui.byId('print-surface-redo').disabled,true);
 }
 assert.equal(ui.edits(),edits);assert.equal(ui.requests.length,0);assert.equal(ui.input('宽度（衣高 %）').value,'24.0');assert.equal(ui.input('高度（衣高 %）').value,'24.0');
 await ui.byId('print-surface-undo').click();assert.deepEqual(ui.fields(),before);
});

test('numeric and committed resize boundaries agree at one to one-hundred-fifty percent of a non-unit model height',async()=>{
 const ui=harness({config:{...config(),reconstruction:{vertices:[[0,-1,0],[1,1.5,0],[0,-1,1]],faces:[[0,1,2]]}}});
 const width=ui.input('宽度（衣高 %）'),height=ui.input('高度（衣高 %）');
 assert.equal(width.min,'1');assert.equal(height.min,'1');assert.equal(width.max,'150');assert.equal(height.max,'150');
 width.value='1';await width.dispatchEvent({type:'change'});height.value='150';await height.dispatchEvent({type:'change'});
 const numeric=ui.fields().surfacePrints[0];assert.equal(numeric.width,.025);assert.equal(numeric.height,3.75);
 assert.equal(width.value,'1.0');assert.equal(height.value,'150.0');
 const resized={...numeric,width:3.75,height:.025};
 await ui.host.dispatchEvent({type:'print-surface-change',detail:{id:numeric.id,print:resized,previous:numeric,commit:true,reason:'resize'}});
 assert.deepEqual(ui.fields().surfacePrints[0],resized);assert.equal(width.value,'150.0');assert.equal(height.value,'1.0');
 const legal=ui.fields();
 for(const input of [width,height])for(const value of ['0.99','150.01']){input.value=value;await input.dispatchEvent({type:'change'});assert.deepEqual(ui.fields(),legal);}
 assert.equal(width.value,'150.0');assert.equal(height.value,'1.0');
 await ui.byId('print-surface-undo').click();assert.deepEqual(ui.fields().surfacePrints[0],numeric,'rejected inputs must not add history entries');
 await ui.byId('print-surface-redo').click();assert.deepEqual(ui.fields().surfacePrints[0],resized);
});


test('a recovered original without READY references cannot be saved as a blank snapshot',async()=>{
 const record={fileName:'shirt.png',notStarted:true,request:{sourceImageId:hash('9'),requestId:'original-request'}};
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify(record)]]);
 const ui=harness({config:{product:'T_SHIRT',areas:[],baseImages:{},reconstruction:null},storage});
 await assert.rejects(ui.surface.prepareSave(ui.configuration()),/商品图已保留，请完成建模后保存/);
 assert.equal(ui.requests.length,0);assert.equal(JSON.parse(storage.get('heyi:print-reference:org-one')).request.requestId,'original-request');
 assert.equal(ui.byId('print-source-preview').hidden,false,'the original stays available for recovery');
});

test('failed replacement references cannot silently archive the previous original and model',async()=>{
 const ui=harness({api:async url=>url.endsWith('/references')?{data:{id:'failed-source',status:'FAILED',message:'参考未完成',retryable:true}}:undefined});
 const previous=ui.configuration();await ui.surface.uploadSource({name:'replacement.png',size:100},false);
 assert.equal(ui.surface.hasUnfinishedSource(),true);
 await assert.rejects(ui.surface.prepareSave(ui.configuration()),/商品图已保留，请完成建模后保存/);
 assert.deepEqual(ui.configuration(),previous);assert.equal(ui.requests.filter(request=>request.url.endsWith('/rendered-views')).length,0);
 assert.ok(ui.storage.get('heyi:print-reference:org-one'),'retry metadata must remain available');
});

test('READY references without a successful model cannot be saved or lose the original',async()=>{
 const ui=harness({build:async()=>null});await ui.surface.uploadSource({name:'shirt.png',size:100},false);
 assert.equal(ui.fields().sourceImageId,hash('9'));assert.equal(ui.configuration().reconstruction,null);
 await assert.rejects(ui.surface.prepareSave(ui.configuration()),/商品图已保留，请完成建模后保存/);
 assert.equal(ui.requests.filter(request=>request.url.endsWith('/rendered-views')).length,0);
 assert.ok(ui.storage.get('heyi:print-reference:org-one'));
});

test('legacy base-image-only templates remain saveable beside unrelated source recovery history',async()=>{
 const storage=new Map([['heyi:print-reference:org-one',JSON.stringify({request:{sourceImageId:hash('9'),requestId:'unrelated'}})]]);
 const legacy={product:'T_SHIRT',areas:[],baseImages:{...bases},reconstruction:null},ui=harness({config:legacy,storage});
 assert.equal(ui.surface.saveBlocker(),null);
 const saved=await ui.surface.prepareSave(ui.configuration());
 assert.deepEqual(saved.baseImages,bases);assert.equal(saved.sourceImageId,null);assert.equal(ui.requests.length,0);
 assert.ok(storage.get('heyi:print-reference:org-one'),'saving a different legacy template must not consume the original recovery entry');
});
