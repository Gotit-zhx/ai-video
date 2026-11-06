const {test,afterEach}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {webcrypto,createHash}=require('node:crypto');
const {Blob,File}=require('node:buffer');
const {JSDOM}=require('../../scripts/ci/qa/node_modules/jsdom');
const source=fs.readFileSync(path.join(__dirname,'../commerce-print-surface.js'),'utf8');
const windows=[];
afterEach(()=>windows.splice(0).forEach(w=>w.close()));
const clone=value=>JSON.parse(JSON.stringify(value));
const flush=async()=>{await new Promise(r=>setImmediate(r));await new Promise(r=>setImmediate(r));};
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};};
const hash=x=>x.repeat(64);
const bases=Object.fromEntries(['FRONT','BACK','LEFT','RIGHT'].map((s,i)=>[s,hash(String(i+1))]));
const print=(id='one',side='FRONT')=>({id,side,artworkId:hash('a'),x:.2,y:.2,width:.24,height:.24,rotation:0});
const config=()=>({product:'T_SHIRT',sourceImageId:hash('b'),referenceSetId:'refs',baseImages:{...bases},photoPrints:[print()],surfacePrints:[],areas:[],reconstruction:null,
 renderedViews:{FRONT:hash('c')},blankRenderedViews:{FRONT:hash('d')},surfaceUvViews:{FRONT:hash('e')},placementMasks:{FRONT:hash('f')},renderPrintIds:['one'],renderBinding:'binding'});
const ready=()=>({id:'job',status:'READY',referenceSetId:'new-refs',originalImageId:hash('9'),baseImages:{...bases},message:'ready'});
function harness(options={}){
 const dom=new JSDOM('<main id="root"><aside id="sidebar"></aside><div id="host"></div><div id="base"></div></main>',{url:'https://example.test',runScripts:'outside-only'});
 const w=dom.window;windows.push(w);const d=w.document;
 let configuration=clone(options.config||config()),revision=0,organization=options.organization||'org-one',side='FRONT';
 const calls=[],scenes=[],placements=[],timers=[],revoked=[],uploads=[];
 w.structuredClone=structuredClone;w.Blob=Blob;w.File=File;w.FormData=FormData;
 Object.defineProperty(w,'crypto',{value:webcrypto});w.HTMLCanvasElement.prototype.getContext=()=>null;
 w.URL.createObjectURL=()=> 'blob:original';w.URL.revokeObjectURL=url=>revoked.push(url);
 w.setTimeout=fn=>{timers.push(fn);return timers.length;};
 const renderer={async setScene(scene){scenes.push(clone({...scene,artworkUrls:undefined}));if(options.setScene)await options.setScene(scene);},setSelectedPrint(){},setPlacementMode:value=>placements.push(value),
  async exportSurfaceGuides(){if(options.exportGuides)return options.exportGuides();return{printIds:surface.fields().photoPrints.map(p=>p.id),views:Object.fromEntries(Object.keys(bases).map(s=>[s,Object.fromEntries(['image','blank','mask','uv'].map(k=>[k,new Blob(['synthetic-'+k],{type:'image/png'})]))]))};}};
 w.fetch=async url=>{
  if(options.fetch)return options.fetch(url);
  const file=/\/print-patterns\/([^?]+)\?/.exec(url);assert.ok(file,'Only fixed local marker bytes are fetched');
  const bytes=fs.readFileSync(path.join(__dirname,'../assets/print-patterns',file[1]));return{ok:true,arrayBuffer:async()=>bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength)};
 };
 w.api=async(url,request={})=>{
  calls.push({url,request});if(options.api){const result=await options.api(url,request);if(result!==undefined)return result;}
  if(url.endsWith('/base-images')){
   const file=request.body.get('file'),bytes=Buffer.from(await file.arrayBuffer());uploads.push({file,bytes});
   return{data:{id:createHash('sha256').update(bytes).digest('hex')}};
  }
  if(url.endsWith('/rendered-views'))return{data:{...config(),renderBinding:'saved',renderPrintIds:surface.fields().photoPrints.map(p=>p.id)}};
  if(url.includes('/references'))return{data:{...ready(),originalImageId:JSON.parse(request.body||'{}').sourceImageId||createHash('sha256').update(uploads.at(-1).bytes).digest('hex')}};
  throw Error('Unexpected '+url);
 };
 const n=(tag,text='',parent)=>{const e=d.createElement(tag);e.textContent=text;parent?.append(e);return e;};
 const ctx={root:d.querySelector('#root'),sidebar:d.querySelector('#sidebar'),host:d.querySelector('#host'),baseTools:d.querySelector('#base'),n,
  organizationId:()=>organization,revision:()=>revision,configuration:()=>clone(configuration),side:()=>side,setSide:value=>{side=value;},imageAspect:()=>1,
  preview:()=>renderer,ensurePreview:async()=>options.preview?options.preview():renderer,changed(){},setUploading(){},defaultName(){},
  apply:value=>{configuration=clone(value);},applyReferences:images=>{configuration.baseImages=clone(images);},isLocked:()=>false};
 w.eval(source);const surface=w.CommercePrintSurface.mount(ctx);surface.load(configuration);
 return{w,d,ctx,surface,calls,scenes,placements,uploads,revoked,timers,
  fields:()=>clone(surface.fields()),
  async click(id){d.getElementById(id).click();await flush();const until=Date.now()+2000;while(surface.isWorking()&&Date.now()<until)await new Promise(r=>setTimeout(r,2));assert.equal(surface.isWorking(),false,d.body.textContent);await flush();},
  async button(text){const b=[...d.querySelectorAll('button')].find(b=>b.textContent===text);assert.ok(b,text);b.click();await flush();},
  async number(label,value){const input=[...d.querySelectorAll('label')].find(l=>l.textContent===label)?.querySelector('input');assert.ok(input,label);input.value=value;input.dispatchEvent(new w.Event('change'));await flush();},
  async event(type,detail){ctx.host.dispatchEvent(new w.CustomEvent(type,{detail}));await flush();},
  load(value){revision++;configuration=clone(value);surface.load(value);},
  company(){revision++;organization='org-two';configuration={};surface.reset();},
  async advance(){assert.ok(timers.length);timers.shift()();await flush();}
 };
}

test('photo coordinates, four-side identity, undo and redo retain the saved source',async()=>{
 const h=harness();await flush();const before=h.fields();
 await h.number('宽度（图片 %）',40);assert.equal(h.fields().photoPrints[0].width,.4);assert.deepEqual(h.fields().renderedViews,{});
 await h.click('print-surface-undo');assert.deepEqual(h.fields(),before);
 await h.click('print-surface-redo');assert.equal(h.fields().photoPrints[0].width,.4);assert.deepEqual(h.fields().surfacePrints,[]);
});
test('switching templates clears history and prevents another template from being restored',async()=>{
 const h=harness();await h.number('宽度（图片 %）',40);h.load({...config(),sourceImageId:hash('8'),photoPrints:[print('other')]});
 await h.click('print-surface-undo');assert.equal(h.fields().sourceImageId,hash('8'));assert.equal(h.fields().photoPrints[0].id,'other');
});
test('legacy spatial templates remain explicitly blocked until the user converts them',async()=>{
 const h=harness({config:{...config(),photoPrints:[],surfacePrints:[{id:'old'}]}});assert.match(h.surface.saveBlocker(),/历史模板/);
 await assert.rejects(h.surface.prepareSave(config()),/历史模板/);
 await h.button('在四面图重新设置印花');assert.deepEqual(h.fields().photoPrints,[]);assert.deepEqual(h.fields().surfacePrints,[]);
});
test('pointer changes commit once while cancelled moves leave saved coordinates intact',async()=>{
 const h=harness();const before=h.fields();
 await h.event('print-photo-change',{id:'one',print:{x:.45},commit:false});assert.deepEqual(h.fields(),before);
 await h.event('print-photo-change',{id:'one',print:{x:.45},commit:true});assert.equal(h.fields().photoPrints[0].x,.45);
 await h.click('print-surface-undo');assert.deepEqual(h.fields(),before);
});
test('invalid numeric bounds never mutate placement and rotation keeps it inside the photo',async()=>{
 const h=harness();const before=h.fields();
 for(const n of [0,101,-1]){await h.number('宽度（图片 %）',n);assert.deepEqual(h.fields(),before);}
 await h.number('旋转 °',45);const p=h.fields().photoPrints[0];assert.ok(p.x>=0&&p.y>=0&&p.x+p.width<=1&&p.y+p.height<=1);
});
test('fixed marker files are verified and archived unchanged without an AI request',async()=>{
 const h=harness();await h.click('print-test-pattern-flower');await h.click('print-test-pattern-use');
 assert.equal(h.uploads.length,1);assert.deepEqual(h.uploads[0].bytes,fs.readFileSync(path.join(__dirname,'../assets/print-patterns/flower.png')));
 assert.equal(h.calls.filter(c=>c.url.includes('/references')).length,0);assert.equal(h.placements.at(-1),true);
 await h.event('print-photo-pick',{x:.5,y:.6});assert.equal(h.fields().photoPrints.length,2);assert.equal(h.fields().photoPrints[1].side,'FRONT');
});
test('corrupt fixed marker bytes cannot reach the archive or change saved prints',async()=>{
 const h=harness({fetch:async()=>({ok:true,arrayBuffer:async()=>new Uint8Array([0,1,2]).buffer})});const before=h.fields();
 await h.click('print-test-pattern-use');assert.equal(h.calls.length,0);assert.deepEqual(h.fields(),before);assert.match(h.d.body.textContent,/内容不完整/);
});
test('repeated marker clicks coalesce and a late download after company change is discarded',async()=>{
 const pending=deferred(),h=harness({fetch:()=>pending.promise});
 h.d.getElementById('print-test-pattern-use').click();h.d.getElementById('print-test-pattern-use').click();await flush();
 h.company();const bytes=fs.readFileSync(path.join(__dirname,'../assets/print-patterns/starburst.png'));
 pending.resolve({ok:true,arrayBuffer:async()=>bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength)});await flush();
 assert.equal(h.calls.length,0);assert.deepEqual(h.fields().photoPrints,[]);
});
test('thirty-two placements prevent another marker upload or pointer insertion',async()=>{
 const h=harness({config:{...config(),photoPrints:Array.from({length:32},(_,i)=>print('p'+i))}});
 await h.click('print-test-pattern-use');await h.event('print-photo-pick',{x:.5,y:.5});assert.equal(h.calls.length,0);assert.equal(h.fields().photoPrints.length,32);
});
test('each side retains its own selected placement',async()=>{
 const h=harness({config:{...config(),photoPrints:[print('front'),print('back','BACK')]}});
 h.ctx.setSide('BACK');h.surface.draw();await h.number('宽度（图片 %）',38);
 assert.equal(h.fields().photoPrints[0].width,.24);assert.equal(h.fields().photoPrints[1].width,.38);
 h.ctx.setSide('FRONT');h.surface.draw();assert.equal(h.d.getElementById('print-surface-selection').value,'front');
});
test('late original upload cannot submit a paid reference request after company reset',async()=>{
 const pending=deferred(),h=harness({api:(url)=>url.endsWith('/base-images')?pending.promise:undefined});
 const upload=h.surface.uploadSource(new File(['original'],'shirt.png',{type:'image/png'}),false);await flush();h.company();
 pending.resolve({data:{id:hash('9')}});await upload;assert.equal(h.calls.length,1);assert.deepEqual(h.fields().baseImages,undefined);assert.ok(h.revoked.includes('blob:original'));
});
test('an uncertain reference POST keeps the same request id for an explicit retry',async()=>{
 let attempts=0;const h=harness({api:(url)=>{if(url.endsWith('/references')&&++attempts===1)throw Error('connection lost');}});
 await h.surface.uploadSource(new File(['original'],'shirt.png'),false);const first=JSON.parse(h.calls.find(c=>c.url.endsWith('/references')).request.body);
 assert.match(h.surface.saveBlocker(),/完成四面参考/);await h.click('print-reference-resume');
 const posts=h.calls.filter(c=>c.url.endsWith('/references'));assert.equal(posts.length,2);assert.equal(JSON.parse(posts[1].request.body).requestId,first.requestId);
});
for(const status of ['FAILED','UNKNOWN'])test(status+' reference results retain the uploaded source without inventing success',async()=>{
 const h=harness({api:url=>url.endsWith('/references')?{data:{...ready(),status,referenceSetId:null,message:'not complete',retryable:false}}:undefined});
 await h.surface.uploadSource(new File(['original'],'shirt.png'),false);
 assert.match(h.surface.saveBlocker(),/完成四面参考/);assert.equal(h.fields().referenceSetId,'refs');
 const saved=JSON.parse(h.w.localStorage.getItem('heyi:print-reference:org-one'));assert.equal(saved.status,status);
});
test('recovery metadata stays scoped to the active company',async()=>{
 const h=harness({api:url=>url.endsWith('/references')?{data:{...ready(),status:'UNKNOWN'}}:undefined});
 await h.surface.uploadSource(new File(['original'],'shirt.png'),false);assert.ok(h.w.localStorage.getItem('heyi:print-reference:org-one'));
 h.company();assert.equal(h.d.getElementById('print-reference-resume').hidden,true);assert.equal(h.w.localStorage.getItem('heyi:print-reference:org-two'),null);
});
test('ready references remain recoverable until their matching source and reference id are saved',async()=>{
 const h=harness();await h.surface.uploadSource(new File(['original'],'shirt.png'),false);
 assert.equal(h.fields().referenceSetId,'new-refs');assert.ok(h.w.localStorage.getItem('heyi:print-reference:org-one'));
 h.surface.saved({...h.fields(),sourceImageId:hash('0')});assert.ok(h.w.localStorage.getItem('heyi:print-reference:org-one'));
 h.surface.saved(h.fields());assert.equal(h.w.localStorage.getItem('heyi:print-reference:org-one'),null);
});
test('save locks edits, binds all exported print IDs and discards a late response after switching',async()=>{
 const pending=deferred(),h=harness({api:url=>url.endsWith('/rendered-views')?pending.promise:undefined});await flush();
 const saving=h.surface.prepareSave(config());await flush();assert.equal(h.surface.isWorking(),true);
 await h.event('print-photo-change',{id:'one',print:{x:.8},commit:true});assert.equal(h.fields().photoPrints[0].x,.2);
 h.load({...config(),sourceImageId:hash('7')});pending.resolve({data:{renderBinding:'stale'}});
 await assert.rejects(saving,/模板已切换/);assert.equal(h.fields().sourceImageId,hash('7'));assert.notEqual(h.fields().renderBinding,'stale');
});
test('changed print IDs or invisible artwork block the archive before a render upload',async()=>{
 for(const guides of [{printIds:['other'],views:{}},{printIds:['one'],invisiblePrintIds:['one'],views:{}}]){
  const h=harness({exportGuides:async()=>guides});await assert.rejects(h.surface.prepareSave(config()));
  assert.equal(h.calls.filter(c=>c.url.endsWith('/rendered-views')).length,0);
 }
});
test('a clean save includes each face and returns the archived binding',async()=>{
 const h=harness();const result=await h.surface.prepareSave(config());assert.equal(result.renderBinding,'saved');
 const form=h.calls.find(c=>c.url.endsWith('/rendered-views')).request.body;
 for(const side of Object.keys(bases))for(const kind of ['image','blank','mask','uv'])assert.ok(form.has(kind+'_'+side));
 assert.equal(JSON.parse(form.get('configuration')).photoPrints[0].id,'one');
});
test('source-only or empty templates cannot be saved as a completed print layout',async()=>{
 const h=harness({config:{baseImages:{...bases},photoPrints:[]}});assert.match(h.surface.saveBlocker(),/至少一个印花/);
 h.load({});assert.match(h.surface.saveBlocker(),/先准备商品参考图/);
});
