const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const settle=async()=>{await tick();await tick();};
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
function harness(initialRows,initialPlan=null){
 const nodes=[],handlers={},requests=[],dispatched=[];let rows=initialRows,plan=initialPlan,requestHook=null,selectedBatch='batch',sources=[{captureId:'a',title:'商品 A'},{captureId:'b',title:'商品 B'}];
 function element(tag='div'){
  const node={tag,children:[],dataset:{},handlers:{},hidden:false,value:'',textContent:'',className:'',
   append(...items){items.forEach(item=>{item.remove();item.parentElement=this;this.children.push(item);});},
   prepend(...items){items.forEach(item=>{item.remove();item.parentElement=this;});this.children.unshift(...items);},
   insertBefore(item,before){item.remove();item.parentElement=this;const index=before?this.children.indexOf(before):this.children.length;this.children.splice(index,0,item);},
   after(item){if(this.parentElement)this.parentElement.insertBefore(item,this.parentElement.children[this.parentElement.children.indexOf(this)+1]||null);},
   before(item){this.parentElement?.insertBefore(item,this);},
   replaceChildren(...items){this.children.forEach(item=>item.parentElement=null);this.children=[];this.append(...items);},add(item){this.append(item);},
   setAttribute(key,value){this[key]=value;},removeAttribute(key){delete this[key];},
   addEventListener(name,fn){this.handlers[name]=fn;},dispatchEvent(event){return this.handlers[event.type]?.({...event,target:event.target||this});},
   close(){this.open=false;},showModal(){this.open=true;},remove(){if(this.parentElement){this.parentElement.children.splice(this.parentElement.children.indexOf(this),1);this.parentElement=null;}},
   querySelectorAll(selector){const all=[];const visit=p=>p.children.forEach(child=>{all.push(child);visit(child);});visit(this);return all.filter(child=>selector.startsWith('.')?child.classList.contains(selector.slice(1)):child.tag===selector);},
   querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
  };
  node.classList={contains:value=>node.className.split(/\s+/).includes(value),
   add:(...values)=>{node.className=[...new Set([...node.className.split(/\s+/).filter(Boolean),...values])].join(' ');},
   remove:(...values)=>{node.className=node.className.split(/\s+/).filter(value=>!values.includes(value)).join(' ');}};
  nodes.push(node);return node;
 }
 const journey=element(),stage=element(),body=element();body.append(journey,stage);
 function Option(label,value){const option=element('option');option.textContent=label;option.value=value;return option;}
 const fire=(name,detail)=>handlers[name]?.forEach(fn=>fn({type:name,detail}));
 const win={addEventListener:(name,fn)=>(handlers[name]||=[]).push(fn),dispatchEvent:event=>{dispatched.push(event);fire(event.type,event.detail);}};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../commerce-batch-artifacts.js'),'utf8'),{
  document:{querySelector:selector=>selector==='.studio-journey'?journey:selector==='.studio-stage-view'?stage:null,createElement:element,body,getElementById:()=>element()},
  window:win,CustomEvent:class{constructor(type,{detail}){this.type=type;this.detail=detail;}},
  api:async(url,options)=>{requests.push({url,options});const hooked=requestHook?.(url,options);if(hooked!==undefined)return hooked;return {data:url.endsWith('/creative-plan')?{sources,plan}:rows};},
  fetch:async url=>{requests.push({url,back:true});return {ok:true,blob:async()=>({})};},URL:{createObjectURL:()=> 'blob:back-proof',revokeObjectURL:()=>{}},
  location:{hash:'#commerce-designs'},Option,MutationObserver:class{observe(){}},Map,Set,Promise,encodeURIComponent,console
 });
 const load=async(id='batch',execution)=>{selectedBatch=id;fire('heyi:workbench-batch-selected',{batchId:id,execution});await settle();};
 return {nodes,load,requests,dispatched,window:win,fire,step:value=>fire('heyi:studio-step-selected',{step:value}),cards:()=>nodes.find(n=>n.className==='batch-artifact-grid').children,
  setRows(value){rows=value;},setPlan(value){plan=value;},setSources(value){sources=value;},hook(fn){requestHook=fn;},
  async poll(status='RUNNING',id=selectedBatch){fire('heyi:execution-state',{run:{batchId:id,status}});await settle();},
  connection(available,id=selectedBatch){fire('heyi:execution-connection',{batchId:id,available});},
  filterSource(value){const select=nodes.find(n=>n.tag==='select');select.value=value;select.handlers.change();}};
}
const row=(kind,captureId='a',variant=1)=>({id:kind+captureId,kind,captureId,variant,status:'READY',imageUrl:'/image/'+kind,message:''});
test('grouped variants appear in design with group and variant labels alongside legacy variants',async()=>{
 const h=harness([row('EXTRACT','a',0),row('VARIATE_G1'),row('VARIATE_G2'),row('VARIATE'),row('MOCKUP_FRONT'),row('VARIATE_G0'),row('VARIATE_G1_EXTRA')]);await h.load();
 assert.deepEqual(h.cards().filter(card=>!card.hidden).map(card=>card.dataset.kind),['EXTRACT']);
 h.step('design');const cards=h.cards().filter(card=>!card.hidden);
 assert.deepEqual(cards.map(card=>card.dataset.kind),['VARIATE_G1','VARIATE_G2','VARIATE']);
 assert.deepEqual(cards.map(card=>card.querySelector('h3').textContent),['图案组 1 · V1','图案组 2 · V1','独立印花变体 · V1']);
 h.step('mockup');assert.deepEqual(h.cards().filter(card=>!card.hidden).map(card=>card.dataset.kind),['MOCKUP_FRONT']);
});
test('source filtering keeps each product group separate and grouped image preview remains usable',async()=>{
 const h=harness([row('VARIATE_G1','a',2),row('VARIATE_G1','b',2)]);await h.load();h.step('design');h.filterSource('b');
 const visible=h.cards().filter(card=>!card.hidden);assert.equal(visible.length,1);assert.equal(visible[0].dataset.captureId,'b');
 const button=visible[0].querySelector('button');assert.equal(button['aria-label'],'放大查看图案组 1 · V2');button.handlers.click();
 const dialog=h.nodes.find(n=>n.tag==='dialog');assert.equal(dialog.open,true);assert.equal(dialog.querySelector('img').alt,'图案组 1 · V2');
 h.filterSource('');assert.equal(h.cards().filter(card=>!card.hidden).length,2);
});
test('rejected generation shows a readable stop reason without a preview image',async()=>{
 const reason='GPT Image 2因第三方内容限制被拒绝；Grok：图片内容未通过服务商审核；本批次已停止';
 const h=harness([{...row('EXTRACT','a',0),status:'REJECTED',imageUrl:null,message:reason}]);await h.load();
 const card=h.cards()[0];assert.equal(card.querySelector('p').textContent,'生成被拒绝');
 assert.ok(card.querySelector('small').textContent.includes(reason));assert.equal(card.querySelector('button'),null);
});

test('nontransparent paid artwork remains inspectable but is stopped and never counted as ready',async()=>{
 const rejected={...row('VARIATE_G1'),status:'TRANSPARENCY_REJECTED',message:'原始生图已保留，未生成合格透明印花'};
 const h=harness([rejected]);await h.load('batch',{batchId:'batch',status:'FAILED',stage:'VARIATE'});h.step('design');
 const card=h.cards()[0];assert.equal(card.dataset.motion,'stopped');assert.equal(card.querySelector('.artifact-state').textContent,'透明背景不合格');
 const open=card.querySelector('.batch-artifact-open');assert.ok(open);assert.equal(open.querySelector('img').src,rejected.imageUrl);open.handlers.click();
 const dialog=h.nodes.find(n=>n.tag==='dialog');assert.equal(dialog.open,true);assert.equal(dialog.querySelector('img').src,rejected.imageUrl);
 assert.ok(card.querySelector('small').textContent.includes(rejected.message));assert.doesNotMatch(card.querySelector('.artifact-state').textContent,/未生成|已归档/);
 assert.equal(h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail.ready,0);
 const retry=card.querySelector('.artifact-retry');assert.equal(retry.textContent,'重试这张');
 const queued={batchId:'batch',status:'QUEUED',stage:'VARIATE'};h.hook((url,options)=>url.endsWith('/retry')&&options?{data:{execution:queued}}:undefined);
 await retry.handlers.click();assert.equal(h.requests.filter(request=>request.options&&request.url.endsWith('/retry')).length,1);
 assert.equal(card.querySelector('.artifact-retry'),null);
});

test('nontransparent output without a loaded preview is not mislabelled as an ungenerated image',async()=>{
 const h=harness([{...row('VARIATE_G1'),status:'TRANSPARENCY_REJECTED',imageUrl:null}]);
 await h.load('batch',{batchId:'batch',status:'FAILED',stage:'VARIATE'});
 const label=h.cards()[0].querySelector('.artifact-placeholder-label');assert.match(label.textContent,/印花透明背景不合格/);assert.doesNotMatch(label.textContent,/未生成/);
 await h.poll('RUNNING');assert.equal(h.cards()[0].dataset.motion,'stopped');assert.equal(h.cards()[0].querySelector('.artifact-retry'),null);
});

test('invalid image outputs are stopped, explicitly retryable after failure, and never counted as completed',async()=>{
 const h=harness([{...row('VARIATE_G1'),status:'INVALID_IMAGE_REJECTED',imageUrl:null}]);
 await h.load('batch',{batchId:'batch',status:'FAILED',stage:'VARIATE'});
 const card=h.cards()[0];assert.equal(card.dataset.motion,'stopped');assert.equal(card.querySelector('.artifact-state').textContent,'图片输出不完整');
 assert.match(card.querySelector('.artifact-placeholder-label').textContent,/图片输出不完整/);assert.doesNotMatch(card.querySelector('.artifact-placeholder-label').textContent,/未生成/);
 assert.ok(card.querySelector('.artifact-retry'));assert.equal(h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail.ready,0);
 await h.poll('RUNNING');assert.equal(card.dataset.motion,'stopped');assert.equal(card.querySelector('.artifact-retry'),null);
});

test('motion receives frozen SKU color targets and measured color values from the existing reads',async()=>{
 const colors=[{key:'white',label:'White',imageId:'private-id'},{key:'black',label:'Black'}];
 const base={...row('BASE_COLOR'),captureId:'a',colorKey:'black',colorLabel:'Black',check:{observedSourceRgb:'#242629'}};
 const h=harness([base],{renderPolicy:'LOCAL_PHOTO_V1',sources:[{captureId:'a',colorTargets:colors}],variantsPerPrint:1});
 await h.load('batch',{batchId:'batch',status:'RUNNING',stage:'EXTRACT'});
 const detail=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail;
 assert.equal(JSON.stringify(detail.colorSources),JSON.stringify([{captureId:'a',colors:[{key:'white',label:'White'},{key:'black',label:'Black'}]}]));
 assert.equal(detail.previews[0].colorKey,'black');assert.equal(detail.previews[0].colorHex,'#242629');assert.equal(detail.ready,0);
 assert.equal(h.requests.filter(request=>request.url.endsWith('/creative-plan')).length,1);
 await h.poll();assert.equal(h.requests.filter(request=>request.url.endsWith('/creative-plan')).length,1);
});

test('pending artifacts use the private source thumbnail and only confirmed running rows animate',async()=>{
 const pending=(kind,status)=>({...row(kind),status,imageUrl:null});
 const h=harness([pending('EXTRACT','PREPARED'),pending('VARIATE_G1','SUBMITTING'),pending('MOCKUP_FRONT','RESULT_RECEIVED')]);
 await h.load('batch',{batchId:'batch',status:'QUEUED'});
 assert.deepEqual(h.cards().map(card=>card.dataset.motion),['waiting','waiting','waiting']);
 await h.poll('RUNNING');
 assert.deepEqual(h.cards().map(card=>card.dataset.motion),['waiting','running','running']);
 assert.deepEqual(h.cards().map(card=>card.querySelector('.artifact-state').textContent),['待提交','生成中','待归档']);
 for(const card of h.cards()){
  assert.ok(card.classList.contains('batch-artifact-card'));assert.ok(card.dataset.status);
  const visual=card.querySelector('.artifact-visual'),img=visual.querySelector('img');
  assert.equal(img.src,'/api/v1/commerce-batches/batch/execution/artifacts/sources/a/image');
  assert.equal(img.alt,'商品原图 · 商品 A');assert.equal(visual.querySelector('button'),null);
  assert.equal(visual.querySelector('.artifact-placeholder-label').textContent,'商品原图 · 等待生成结果');
  for(const cls of ['artifact-scan','artifact-orbit','artifact-particles'])assert.equal(visual.querySelector('.'+cls)['aria-hidden'],'true');
  assert.equal(visual.querySelector('.artifact-particles').children.length,3);
 }
 const img=h.cards()[0].querySelector('img');img.handlers.error();assert.equal(img.hidden,true);
 assert.ok(h.cards()[0].querySelector('.artifact-placeholder-label'),'failed source image does not erase the honest placeholder label');
});

test('identical polls preserve card, image and animation nodes and publish actual counts each time',async()=>{
 const h=harness([row('EXTRACT'),{...row('VARIATE_G1'),status:'SUBMITTING',imageUrl:null}]);await h.load('batch',{batchId:'batch',status:'RUNNING'});
 const [ready,running]=h.cards(),image=ready.querySelector('img'),visual=running.querySelector('.artifact-visual'),scan=running.querySelector('.artifact-scan');
 assert.ok(ready.classList.contains('is-revealing'));
 ready.handlers.animationend({target:ready.querySelector('.artifact-visual')});assert.ok(!ready.classList.contains('is-revealing'));
 await h.poll('RUNNING');await h.poll('RUNNING');
 assert.equal(h.cards()[0],ready);assert.equal(h.cards()[1],running);assert.equal(ready.querySelector('img'),image);
 assert.equal(running.querySelector('.artifact-visual'),visual);assert.equal(running.querySelector('.artifact-scan'),scan);
 assert.ok(!ready.classList.contains('is-revealing'),'same READY result never replays its reveal');
 const counts=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated');assert.equal(counts.length,3);
 for(const event of counts){assert.equal(event.detail.batchId,'batch');assert.equal(event.detail.ready,1);assert.equal(event.detail.total,2);assert.equal(event.detail.previews.length,2);}
});

test('a newly archived image replaces only its own placeholder and reveals once with a usable preview',async()=>{
 const pending={...row('EXTRACT','a',0),status:'SUBMITTING',imageUrl:null},other=row('VARIATE_G1');
 const h=harness([pending,other]);await h.load('batch',{batchId:'batch',status:'RUNNING'});
 const card=h.cards()[0],unchanged=h.cards()[1],otherImage=unchanged.querySelector('img');
 assert.ok(!card.classList.contains('is-revealing'));h.setRows([{...pending,status:'READY',imageUrl:'/result/extract.png'},other]);await h.poll('RUNNING');
 assert.equal(h.cards()[0],card);assert.equal(card.dataset.motion,'ready');assert.equal(card.dataset.status,'READY');assert.ok(card.classList.contains('is-revealing'));
 assert.equal(card.querySelector('.artifact-placeholder-label'),null);assert.equal(card.querySelector('img').src,'/result/extract.png');
 assert.equal(h.cards()[1],unchanged);assert.equal(unchanged.querySelector('img'),otherImage);
 card.querySelector('button').handlers.click();const dialog=h.nodes.find(node=>node.tag==='dialog');assert.equal(dialog.open,true);assert.equal(dialog.querySelector('img').src,'/result/extract.png');
 card.handlers.animationend({target:card.querySelector('.artifact-orbit')});assert.ok(card.classList.contains('is-revealing'),'unrelated decorative animation cannot finish the reveal');
 card.handlers.animationend({target:card.querySelector('.artifact-visual')});await h.poll('COMPLETED');assert.ok(!card.classList.contains('is-revealing'));
 assert.ok(h.requests.every(request=>!request.options?.method),'animations never submit generation requests');
});

test('execution stop states immediately stop stale submitting rows even while the artifact fetch is pending',async()=>{
 const h=harness([{...row('EXTRACT'),status:'SUBMITTING',imageUrl:null}]);await h.load('batch',{batchId:'batch',status:'RUNNING'});
 const card=h.cards()[0],visual=card.querySelector('.artifact-visual');
 for(const [status,label] of [['FAILED','已停止'],['UNKNOWN','结果需核查'],['WAITING_INPUT','等待处理'],['WAITING_CAPABILITY','等待接入'],['PAUSED','已暂停'],['PAUSING','等待暂停']]){
  const gate=deferred();h.hook(url=>url.endsWith('/artifacts')?gate.promise:undefined);
  h.fire('heyi:execution-state',{run:{batchId:'batch',status:'RUNNING'}});assert.equal(card.dataset.motion,'running');
  h.fire('heyi:execution-state',{run:{batchId:'batch',status}});assert.equal(card.dataset.motion,'stopped');assert.equal(card.querySelector('.artifact-state').textContent,label);
  gate.resolve({data:[{...row('EXTRACT'),status:'SUBMITTING',imageUrl:null}]});await settle();
  assert.equal(card.dataset.motion,'stopped');assert.equal(h.cards()[0],card);assert.equal(card.querySelector('.artifact-visual'),visual);
 }
});

test('row rejection and unknown outcomes remain stopped regardless of an otherwise running execution',async()=>{
 const h=harness(['REJECTED','UNKNOWN','FAILED'].map((status,index)=>({...row('VARIATE_G'+(index+1)),status,imageUrl:null})));
 await h.load('batch',{batchId:'batch',status:'RUNNING'});
 assert.deepEqual(h.cards().map(card=>card.dataset.motion),['stopped','stopped','stopped']);
 assert.deepEqual(h.cards().map(card=>card.querySelector('.artifact-state').textContent),['生成被拒绝','结果需核查','生成已停止']);
});

test('connection loss freezes motion without fabricating failure and matching execution updates restore it',async()=>{
 const h=harness([{...row('EXTRACT'),status:'SUBMITTING',imageUrl:null}]);await h.load('batch',{batchId:'batch',status:'RUNNING'});
 const card=h.cards()[0];h.connection(false,'another-batch');assert.equal(card.dataset.motion,'running');
 h.connection(false);assert.equal(card.dataset.motion,'waiting');assert.equal(card.dataset.status,'SUBMITTING');assert.match(card.querySelector('.artifact-state').textContent,/连接中断/);
 await h.poll('RUNNING','old-batch');assert.equal(card.dataset.motion,'waiting');
 await h.poll('RUNNING');assert.equal(card.dataset.motion,'running');
 h.connection(false);h.connection(true);assert.equal(card.dataset.motion,'running');
 h.hook(url=>url.endsWith('/artifacts')?Promise.reject(new Error('connection unavailable')):undefined);
 await h.poll('RUNNING');assert.equal(card.dataset.motion,'waiting');assert.equal(card.dataset.status,'SUBMITTING');
 h.hook(null);await h.poll('RUNNING');assert.equal(card.dataset.motion,'running');
});

test('batch switches clear private previews, filters and rows and discard responses from the previous batch',async()=>{
 const h=harness([row('EXTRACT')]);await h.load('batch-a',{batchId:'batch-a',status:'RUNNING'});h.filterSource('a');h.cards()[0].querySelector('button').handlers.click();
 const oldCard=h.cards()[0],oldRead=deferred();h.hook(url=>url.includes('/batch-a/')&&url.endsWith('/artifacts')?oldRead.promise:undefined);
 h.fire('heyi:execution-state',{run:{batchId:'batch-a',status:'RUNNING'}});
 h.setRows([{...row('EXTRACT','b'),status:'PREPARED',imageUrl:null}]);h.setSources([{captureId:'b',title:'新批次商品'}]);
 await h.load('batch-b',{batchId:'batch-b',status:'QUEUED'});
 const dialog=h.nodes.find(node=>node.tag==='dialog'),select=h.nodes.find(node=>node.tag==='select');
 assert.equal(dialog.open,false);assert.equal(dialog.querySelector('img').src,undefined);assert.equal(select.value,'');assert.equal(select.hidden,true);
 assert.equal(h.cards().length,1);assert.notEqual(h.cards()[0],oldCard);assert.equal(h.cards()[0].dataset.captureId,'b');
 assert.equal(h.cards()[0].querySelector('img').src,'/api/v1/commerce-batches/batch-b/execution/artifacts/sources/b/image');
 assert.match(h.cards()[0].querySelector('small').textContent,/新批次商品/);
 const eventsBefore=h.dispatched.length;oldRead.resolve({data:[row('EXTRACT','a')]});await settle();
 assert.equal(h.cards()[0].dataset.captureId,'b');assert.equal(h.dispatched.length,eventsBefore,'stale results cannot publish obsolete counts');
 await h.poll('FAILED','batch-a');assert.equal(h.cards()[0].dataset.motion,'waiting');
});

test('scope reset invalidates pending reads and source thumbnail URLs encode batch and capture ids',async()=>{
 const h=harness([{...row('EXTRACT','capture/a ?'),status:'SUBMITTING',imageUrl:null}]);await h.load('batch/a ?', {batchId:'batch/a ?',status:'RUNNING'});
 assert.equal(h.cards()[0].querySelector('img').src,'/api/v1/commerce-batches/batch%2Fa%20%3F/execution/artifacts/sources/capture%2Fa%20%3F/image');
 const gate=deferred();h.hook(url=>url.endsWith('/artifacts')?gate.promise:undefined);h.fire('heyi:execution-state',{run:{batchId:'batch/a ?',status:'RUNNING'}});
 h.fire('heyi:workbench-scope-reset');assert.equal(h.cards().length,0);assert.equal(h.nodes.find(node=>node.className==='batch-artifact-panel').hidden,true);
 const eventsBefore=h.dispatched.length;gate.resolve({data:[row('EXTRACT')]});await settle();assert.equal(h.cards().length,0);assert.equal(h.dispatched.length,eventsBefore);
});

test('keyed reconciliation handles insertions, removals and order without rebuilding unchanged cards',async()=>{
 const a=row('EXTRACT'),b=row('VARIATE_G1'),c=row('VARIATE_G2');const h=harness([a,b]);await h.load();
 const [cardA,cardB]=h.cards(),imageB=cardB.querySelector('img');h.setRows([c,b,a]);await h.poll();
 assert.equal(h.cards()[1],cardB);assert.equal(h.cards()[2],cardA);assert.equal(cardB.querySelector('img'),imageB);
 h.setRows([b]);await h.poll();assert.equal(h.cards().length,1);assert.equal(h.cards()[0],cardB);assert.equal(cardA.parentElement,null);
});

test('repair controls survive unchanged polling and cannot submit after the active batch changes',async()=>{
 const h=harness([row('BLANK')]);await h.load();
 const actions=h.nodes.find(node=>node.className==='batch-repair-actions'),button=actions.querySelector('button');await h.poll();
 assert.equal(h.nodes.find(node=>node.className==='batch-artifact-panel').querySelector('.batch-repair-actions'),actions);
 const regions=[{x:.1,y:.2,width:.3,height:.4}];h.window.heyiEditPrintRegions=async()=>regions;await button.handlers.click();
 const submitted=h.requests.find(request=>request.options?.method==='POST');assert.ok(submitted.url.endsWith('/execution/artifacts/repairs'));assert.equal(JSON.parse(submitted.options.body).captureId,'a');
 assert.deepEqual(JSON.parse(submitted.options.body).cleanupAreas,regions);assert.equal(button.disabled,false);
 const gate=deferred();h.window.heyiEditPrintRegions=()=>gate.promise;const edit=button.handlers.click();h.setRows([]);await h.load('new-batch');gate.resolve(regions);await edit;
 assert.equal(h.requests.filter(request=>request.options?.method==='POST').length,1,'an old editor cannot submit into the newly selected batch');
});

const rejectedRow=(kind='MOCKUP_LEFT',id='rejected-side')=>({...row(kind),id,status:'REJECTED',imageUrl:null,message:'供应商明确拒绝；本批次已停止，未生成本张图片，不会自动重试'});

test('retry button exists only for rejected artifacts in the currently failed execution',async()=>{
 const rejected=rejectedRow(),ready=row('MOCKUP_FRONT'),unknown={...row('MOCKUP_RIGHT'),status:'UNKNOWN',imageUrl:null},prepared={...row('MOCKUP_BACK'),status:'PREPARED',imageUrl:null};
 const h=harness([rejected,ready,unknown,prepared]);await h.load('batch',{batchId:'batch',status:'RUNNING'});
 assert.ok(h.cards().every(card=>!card.querySelector('.artifact-retry')));
 await h.poll('FAILED');const retry=h.cards()[0].querySelector('.artifact-retry');assert.equal(retry.textContent,'重试这张');assert.equal(retry.disabled,false);
 assert.ok(h.cards().slice(1).every(card=>!card.querySelector('.artifact-retry')));
 for(const state of ['UNKNOWN','WAITING_INPUT','PAUSED','COMPLETED','QUEUED']){await h.poll(state);assert.equal(h.cards()[0].querySelector('.artifact-retry'),null);}
 await h.poll('FAILED');assert.equal(h.cards()[0].querySelector('.artifact-retry'),retry);
 assert.ok(h.requests.every(request=>!request.options?.method),'seeing a refusal never starts an automatic retry');
});

test('explicit single-image retry suppresses double clicks, dispatches the returned run and preserves READY cards',async()=>{
 const failed=rejectedRow(),ready=row('MOCKUP_FRONT');const h=harness([failed,ready]);await h.load('batch',{batchId:'batch',status:'FAILED'});
 const [rejected,finished]=h.cards(),readyImage=finished.querySelector('img'),button=rejected.querySelector('.artifact-retry'),gate=deferred();
 h.hook(url=>url.endsWith('/retry')?gate.promise:undefined);
 const pending=button.handlers.click();assert.equal(button.disabled,true);assert.equal(button.textContent,'正在重试…');
 await button.handlers.click();assert.equal(h.requests.filter(request=>request.options?.method==='POST').length,1);
 assert.equal(rejected.querySelector('small').textContent,`来源 商品 A · ${failed.message}`);
 h.setRows([{...failed,message:failed.message+'。'},ready]);await h.poll('FAILED');assert.equal(rejected.querySelector('.artifact-retry'),button);assert.equal(button.disabled,true,'polling cannot re-enable a pending submission');
 const run={id:'existing-run',batchId:'batch',status:'RUNNING',stage:'MOCKUP'},newJob={...failed,id:'replacement-side',status:'PREPARED',message:'等待单图重试'};
 h.setRows([newJob,ready]);gate.resolve({data:{execution:run,artifactId:'replacement-side'}});await pending;await settle();
 const request=h.requests.find(request=>request.options?.method==='POST');assert.equal(request.url,'/api/v1/commerce-batches/batch/execution/artifacts/rejected-side/retry');assert.equal(request.options.body,'{"confirmed":true}');
 const event=h.dispatched.find(event=>event.type==='heyi:artifact-retry-started');assert.ok(event);assert.equal(JSON.stringify(event.detail),JSON.stringify({batchId:'batch',execution:run}));
 assert.notEqual(h.cards()[0],rejected);assert.equal(h.cards()[0].dataset.status,'PREPARED');assert.equal(h.cards()[1],finished);assert.equal(finished.querySelector('img'),readyImage);
 assert.equal(h.requests.filter(request=>request.options?.method==='POST').length,1,'does not create a batch or regenerate archived images');
});

test('an unconfirmed retry response stays pending verification and another click reuses the same old artifact id',async()=>{
 for(const status of [0,408,502]){
  const failed=rejectedRow();const h=harness([failed]);await h.load('batch',{batchId:'batch',status:'FAILED'});
  const card=h.cards()[0],button=card.querySelector('.artifact-retry');h.hook(url=>url.endsWith('/retry')?Promise.reject(Object.assign(new Error('connection interrupted'),{status})):undefined);
  await button.handlers.click();assert.equal(button.disabled,false);assert.equal(card.dataset.status,'REJECTED');assert.equal(card.querySelector('.artifact-state').textContent,'生成被拒绝');
  assert.match(card.querySelector('.artifact-retry-status').textContent,/状态待核对/);assert.doesNotMatch(card.querySelector('.artifact-retry-status').textContent,/重试失败/);assert.ok(card.querySelector('small').textContent.endsWith(failed.message));
  await h.poll('FAILED');assert.equal(h.requests.filter(request=>request.options?.method==='POST').length,1,'polling never resends the uncertain request');
  assert.match(card.querySelector('.artifact-retry-status').textContent,/状态待核对/);
  h.hook(url=>url.endsWith('/retry')?{data:{execution:{batchId:'batch',status:'RUNNING'},artifactId:'replacement-side'}}:undefined);
  await button.handlers.click();const requests=h.requests.filter(request=>request.options?.method==='POST');assert.equal(requests.length,2);assert.equal(requests[0].url,requests[1].url);assert.equal(requests[0].options.body,requests[1].options.body);
  assert.equal(card.querySelector('.artifact-retry'),null);
 }
});

test('definite retry request rejection leaves the original refusal visible and the retry control usable',async()=>{
 const failed=rejectedRow();const h=harness([failed]);await h.load('batch',{batchId:'batch',status:'FAILED'});
 h.hook(url=>url.endsWith('/retry')?Promise.reject(Object.assign(new Error('当前模型调用上限不足'),{status:409})):undefined);
 const card=h.cards()[0],button=card.querySelector('.artifact-retry');await button.handlers.click();
 assert.equal(button.disabled,false);assert.equal(card.querySelector('.artifact-retry-status').textContent,'本次重试未启动：当前模型调用上限不足');assert.ok(card.querySelector('small').textContent.endsWith(failed.message));
 assert.equal(h.dispatched.filter(event=>event.type==='heyi:artifact-retry-started').length,0);
});

test('late single-image retry responses cannot restore a previous batch or company scope',async()=>{
 for(const reset of ['batch','scope']){
  const h=harness([rejectedRow()]);await h.load('batch-a',{batchId:'batch-a',status:'FAILED'});
  const oldCard=h.cards()[0],button=oldCard.querySelector('.artifact-retry'),gate=deferred();h.hook(url=>url.endsWith('/retry')?gate.promise:undefined);
  const pending=button.handlers.click();
  if(reset==='batch'){h.setRows([row('EXTRACT','b')]);await h.load('batch-b',{batchId:'batch-b',status:'COMPLETED'});}else h.fire('heyi:workbench-scope-reset');
  gate.resolve({data:{execution:{batchId:'batch-a',status:'RUNNING'},artifactId:'replacement'}});await pending;await settle();
  assert.equal(h.dispatched.filter(event=>event.type==='heyi:artifact-retry-started').length,0);
  if(reset==='batch'){assert.equal(h.cards().length,1);assert.equal(h.cards()[0].dataset.captureId,'b');assert.equal(h.cards()[0].querySelector('.artifact-retry-status'),null);}else assert.equal(h.cards().length,0);
  await button.handlers.click();assert.equal(h.requests.filter(request=>request.options?.method==='POST').length,1,'a detached button cannot submit into the next scope');
 }
});

test('a retry success without a matching execution is treated as unconfirmed and identifiers are encoded',async()=>{
 const failed=rejectedRow('MOCKUP_RIGHT','artifact/a ?');const h=harness([failed]);await h.load('batch/a ?',{batchId:'batch/a ?',status:'FAILED'});
 h.hook(url=>url.endsWith('/retry')?{data:{execution:{batchId:'wrong-batch',status:'RUNNING'},artifactId:'replacement'}}:undefined);
 const card=h.cards()[0],button=card.querySelector('.artifact-retry');await button.handlers.click();
 assert.equal(h.requests.find(request=>request.options?.method==='POST').url,'/api/v1/commerce-batches/batch%2Fa%20%3F/execution/artifacts/artifact%2Fa%20%3F/retry');
 assert.match(card.querySelector('.artifact-retry-status').textContent,/状态待核对/);assert.equal(button.disabled,false);assert.equal(h.dispatched.filter(event=>event.type==='heyi:artifact-retry-started').length,0);
});

test('position guides are collapsed separately and never inflate generated result counts',async()=>{
 const h=harness([row('MOCKUP_FRONT'),row('PLACEMENT_GUIDE_FRONT')]);await h.load();h.step('mockup');
 assert.equal(h.cards().length,1);assert.equal(h.cards()[0].dataset.kind,'MOCKUP_FRONT');
 const details=h.nodes.find(node=>node.className==='batch-position-guides');assert.equal(details.hidden,false);assert.ok(!details.open);
 assert.match(details.querySelector('summary').textContent,/1 张/);
 const guide=details.querySelector('article');assert.equal(guide.dataset.kind,'PLACEMENT_GUIDE_FRONT');assert.ok(!guide.classList.contains('is-revealing'));
 const counts=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail;assert.equal(counts.ready,1);assert.equal(counts.total,1);assert.equal(counts.previews.length,2);
 h.step('design');assert.equal(details.hidden,true);h.fire('heyi:workbench-scope-reset');assert.equal(details.open,false);
});
test('each SKU color is visible in its result label and preview name',async()=>{
 const h=harness([{...row('MOCKUP_FRONT'),colorKey:'pink',colorLabel:'粉色'}]);await h.load();h.step('mockup');
 const card=h.cards()[0];assert.match(card.querySelector('h3').textContent,/粉色/);assert.match(card.querySelector('button')['aria-label'],/粉色/);
});

const localPlan={renderPolicy:'LOCAL_PHOTO_V1',outputPolicy:'SOURCE_SKU_COLORS',variantsPerPrint:2,
 sources:[{captureId:'a',colorTargets:[{key:'black',label:'黑色'},{key:'white',label:'白色'},{key:'blue',label:'蓝色'}]}]};
test('local photo gallery counts only product outputs and folds reusable masters and bases',async()=>{
 const h=harness([row('EXTRACT','a',0),row('VARIATE_G1'),row('VARIATE_G2'),row('MATTE_G1'),row('MATTE_G2'),
  {...row('BASE_COLOR','a',0),id:'base-black',colorKey:'black',colorLabel:'黑色'},
  {...row('MOCKUP_FRONT'),id:'product-black',colorKey:'black',colorLabel:'黑色'},
  {...row('MOCKUP_FRONT'),id:'product-white',colorKey:'white',colorLabel:'白色',status:'PREPARED',imageUrl:null}],localPlan);
 await h.load('batch',{batchId:'batch',status:'RUNNING',stage:'MOCKUP'});h.step('mockup');
 const visible=h.cards().filter(card=>!card.hidden);assert.equal(visible.length,2);
 assert.ok(visible.every(card=>card.dataset.kind==='MOCKUP_FRONT'));assert.match(visible[0].querySelector('h3').textContent,/商品主图 · 本地套版/);
 assert.doesNotMatch(visible[0].querySelector('h3').textContent,/AI/);
 const materials=h.nodes.find(node=>node.className==='batch-position-guides batch-intermediate-assets');
 assert.equal(materials.hidden,false);assert.ok(!materials.open);assert.match(materials.querySelector('summary').textContent,/3 张/);
 assert.equal(materials.querySelectorAll('article').length,3);
 assert.match(materials.querySelectorAll('article')[0].querySelector('h3').textContent,/透明母版 · 图案组 1/);
 const count=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail;
 assert.equal(count.renderPolicy,'LOCAL_PHOTO_V1');assert.equal(count.ready,1);assert.equal(count.total,6);assert.equal(count.previews.length,8);
 const panel=h.nodes.find(node=>node.className==='batch-artifact-panel');assert.match(panel.querySelector('p').textContent,/已归档 1 \/ 6 张商品主图/);
 h.step('design');assert.equal(h.cards().filter(card=>!card.hidden).length,2);
 assert.ok(h.cards().filter(card=>!card.hidden).every(card=>card.querySelector('h3').textContent.startsWith('印花原稿')));
 assert.match(panel.querySelectorAll('p').find(node=>node.textContent.startsWith('当前显示')).textContent,/非商品成品/);
 assert.match(materials.querySelector('summary').textContent,/2 张/);
 h.step('publish');assert.equal(materials.hidden,true);h.fire('heyi:workbench-scope-reset');assert.equal(materials.open,false);assert.equal(materials.querySelectorAll('article').length,0);
});

test('local review reports never invent a pass or display NaN when numeric metrics are absent',async()=>{
 for(const status of ['PHOTO_COMPOSITED_REVIEW_REQUIRED','LOCAL_PHOTO_REVIEW_REQUIRED']) {
  const candidate={...row('MOCKUP'),check:{status,productionReady:false,limitation:'袖子位置需人工核对'}};
  const h=harness([candidate,row('MATTE')],localPlan);await h.load();h.step('mockup');
  const check=h.cards()[0].querySelector('.batch-artifact-check');assert.equal(check.textContent,'本地套版已归档 · 自动检查已完成');
  assert.equal(check.title,'袖子位置需人工核对');assert.doesNotMatch(check.textContent,/通过|NaN|合格/);
 }
});

test('archived local product cards do not demand manual review before draft upload',async()=>{
 const message='商品主图已本地套版 · 黑色；保留底图与共享母版，待质量复核';
 const ready={...row('MOCKUP_FRONT'),message};
 const failed={...row('MOCKUP_BACK'),status:'FAILED',imageUrl:null,message:'套版失败：透明母版不完整，待质量复核'};
 const h=harness([ready,failed],localPlan);await h.load('batch',{batchId:'batch',stage:'PUBLISH',status:'QUEUED'});h.step('publish');
 assert.equal(h.cards()[0].querySelector('small').textContent,'来源 商品 A · 商品主图已本地套版 · 黑色；保留底图与共享母版，可查看成品效果');
 assert.doesNotMatch(h.cards()[0].querySelector('small').textContent,/待质量复核|已上传|检查通过/);
 assert.ok(h.cards()[1].querySelector('small').textContent.includes(failed.message),'real failure details remain visible');
 assert.equal(ready.message,message,'rendering does not modify archived source messages');
});

test('local auxiliary artifacts alone cannot be reported as finished product pictures',async()=>{
 const h=harness([row('EXTRACT'),row('VARIATE'),row('MATTE'),row('BASE_COLOR')],localPlan);await h.load();
 const count=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail;
 assert.equal(count.ready,0);assert.equal(count.total,6);
 h.step('mockup');assert.equal(h.cards().filter(card=>!card.hidden).length,0);
 assert.match(h.nodes.find(node=>node.className==='batch-artifact-panel').querySelector('p').textContent,/已归档 0 \/ 6 张商品主图/);
});

test('old frozen execution keeps AI view labels and archival counts after switching away from local output',async()=>{
 const h=harness([row('MOCKUP_FRONT'),row('MATTE')],localPlan);await h.load();h.step('mockup');
 assert.match(h.cards()[0].querySelector('h3').textContent,/本地套版/);
 h.setPlan({renderPolicy:null});h.setRows([row('EXTRACT'),row('VARIATE'),row('MOCKUP_FRONT'),row('MOCKUP_LEFT')]);await h.load('historical',{batchId:'historical',status:'COMPLETED'});h.step('mockup');
 assert.ok(h.cards().filter(card=>!card.hidden).every(card=>card.querySelector('h3').textContent.includes('AI 套版候选')));
 const count=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail;assert.equal(count.renderPolicy,null);assert.equal(count.ready,4);assert.equal(count.total,4);
 assert.equal(h.nodes.find(node=>node.className==='batch-position-guides batch-intermediate-assets').hidden,true);
});

test('saving a draft rendering upgrade reloads its policy and source quantities without a generation request',async()=>{
 const h=harness([],{renderPolicy:null});await h.load();
 h.setPlan(localPlan);h.fire('heyi:creative-plan-state',{batchId:'batch',version:2,renderPolicy:'LOCAL_PHOTO_V1',expectedOutputs:6});await settle();
 const count=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail;assert.equal(count.renderPolicy,'LOCAL_PHOTO_V1');assert.equal(count.total,6);
 assert.equal(h.requests.filter(request=>request.url.endsWith('/creative-plan')).length,2);
 assert.ok(h.requests.every(request=>!request.options?.method));
 h.fire('heyi:creative-plan-state',{batchId:'other',version:2,renderPolicy:null});h.fire('heyi:creative-plan-state',{batchId:'batch',version:0,renderPolicy:null});await settle();
 assert.equal(h.requests.filter(request=>request.url.endsWith('/creative-plan')).length,2);
});
test('a same-policy variant change refreshes output totals even when an artifact poll is pending',async()=>{
 const h=harness([],localPlan);await h.load();
 const gate=deferred();let held=false;h.hook(url=>{if(url.endsWith('/artifacts')&&!held){held=true;return gate.promise;}});
 h.fire('heyi:execution-state',{run:{batchId:'batch',status:'QUEUED'}});
 h.setPlan({...localPlan,variantsPerPrint:4});h.fire('heyi:creative-plan-state',{batchId:'batch',version:2,renderPolicy:'LOCAL_PHOTO_V1',expectedOutputs:12});
 gate.resolve({data:[]});await settle();await settle();
 const count=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail;assert.equal(count.total,12);assert.equal(count.ready,0);
 assert.equal(h.requests.filter(request=>request.url.endsWith('/creative-plan')).length,2);
});
test('local extract stage exposes already prepared color and cleaned bases as intermediate assets',async()=>{
 const rows=[row('EXTRACT'),row('BLANK'),{...row('BASE_COLOR'),check:{mode:'SOURCE_PHOTO_COLOR',productionReady:false}}];
 const h=harness(rows,localPlan);await h.load('batch',{batchId:'batch',status:'RUNNING',stage:'EXTRACT'});h.step('extract');
 assert.deepEqual(h.cards().filter(card=>!card.hidden).map(card=>card.dataset.kind),['EXTRACT']);
 const materials=h.nodes.find(node=>node.className==='batch-position-guides batch-intermediate-assets');
 assert.equal(materials.hidden,false);assert.ok(!materials.open);assert.match(materials.querySelector('summary').textContent,/2 张/);
 assert.equal(materials.querySelectorAll('article').filter(card=>!card.hidden).length,2);
 assert.match(materials.querySelectorAll('article')[1].querySelector('.batch-artifact-check').textContent,/颜色底图.*需复核/);
 assert.equal(h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail.ready,0);
 const old=harness(rows,{renderPolicy:null});await old.load();old.step('extract');
 assert.deepEqual(old.cards().filter(card=>!card.hidden).map(card=>card.dataset.kind),['EXTRACT']);
 assert.equal(old.nodes.find(node=>node.className==='batch-position-guides batch-intermediate-assets').hidden,true);
});

test('front card flips to actual cached back and returns without a second request',async()=>{
 const art={...row('MOCKUP_FRONT'),colorLabel:'Black',backImageUrl:'/api/back-image'};
 const h=harness([art],{renderPolicy:'LOCAL_PHOTO_V1'});await h.load();h.step('mockup');
 const card=h.cards()[0],flip=card.querySelector('.artifact-flip');assert.ok(flip);
 await flip.handlers.click();assert.equal(card.querySelector('.artifact-visual').dataset.side,'back');
 card.querySelector('.batch-artifact-open').handlers.click();assert.equal(h.nodes.find(n=>n.tag==='dialog').children[1].src,'blob:back-proof');
 await flip.handlers.click();assert.equal(card.querySelector('.artifact-visual').dataset.side,'front');
 await flip.handlers.click();assert.equal(h.requests.filter(r=>r.back).length,1);
});

test('shared Grok background is a base material and never an artwork or product output',async()=>{
 const h=harness([row('SCENE_BACKGROUND','a',0),row('MATTE_G1'),row('BASE_COLOR'),row('MOCKUP_FRONT')],{...localPlan,renderPolicy:'GROK_SCENE_V1'});await h.load('batch',{batchId:'batch',status:'RUNNING',stage:'MOCKUP'});h.step('mockup');
 const materials=h.nodes.find(node=>node.className==='batch-position-guides batch-intermediate-assets'),background=materials.querySelectorAll('article').find(card=>card.dataset.kind==='SCENE_BACKGROUND');assert.ok(background);assert.match(background.querySelector('h3').textContent,/提取商品背景风格 · 同商品多款多色共用/);assert.match(background.querySelector('small').textContent,/同商品共用背景/);
 const counts=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail;assert.equal(counts.ready,1);assert.equal(counts.total,6);assert.equal(counts.renderPolicy,'GROK_SCENE_V1');assert.match(h.nodes.find(node=>node.className==='batch-artifact-panel').querySelector('p').textContent,/提取商品背景风格/);
 assert.match(h.nodes.find(node=>node.className==='batch-artifact-panel').querySelector('p').textContent,/场景氛围、配色、材质与光线重新设计背景/);
 h.filterSource('b');assert.equal(background.hidden,true,'another product must not show this source-specific background');h.step('design');assert.equal(background.hidden,true);
});

test('scene generation state is labeled as background work and UNKNOWN does not count as ready',async()=>{
 const background={...row('SCENE_BACKGROUND','a',0),status:'SUBMITTING',imageUrl:null};const h=harness([background],{...localPlan,renderPolicy:'GROK_SCENE_V1'});await h.load('batch',{batchId:'batch',status:'RUNNING',stage:'MOCKUP'});h.step('mockup');
 const materials=h.nodes.find(node=>node.className==='batch-position-guides batch-intermediate-assets'),card=materials.querySelectorAll('article')[0];assert.equal(card.querySelector('.artifact-state').textContent,'Grok 场景背景准备中');assert.equal(h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail.ready,0);
 h.setRows([{...background,status:'UNKNOWN',message:'背景生成结果待核查'}]);await h.poll('UNKNOWN');assert.equal(card.querySelector('.artifact-state').textContent,'结果需核查');assert.equal(card.dataset.motion,'stopped');assert.equal(h.requests.filter(request=>request.options).length,0);
});

test('cached backgrounds still belong to each selected extraction source independently',async()=>{
 const a={...row('SCENE_BACKGROUND','a',0),imageUrl:'/same-cached-background'},b={...row('SCENE_BACKGROUND','b',0),imageUrl:'/same-cached-background'};
 const h=harness([a,b],{...localPlan,renderPolicy:'GROK_SCENE_V1'});await h.load();h.step('mockup');const materials=h.nodes.find(node=>node.className==='batch-position-guides batch-intermediate-assets');
 h.filterSource('a');assert.deepEqual(materials.querySelectorAll('article').filter(card=>!card.hidden).map(card=>card.dataset.captureId),['a']);
 h.filterSource('b');assert.deepEqual(materials.querySelectorAll('article').filter(card=>!card.hidden).map(card=>card.dataset.captureId),['b']);assert.equal(h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail.ready,0);
});

test('drape reuse counts only final DRAPED_FRONT and folds precise layouts and the shared master into process materials',async()=>{
 const ready={...row('DRAPED_FRONT','a',1),colorLabel:'黑色'};
 const waiting={...row('DRAPED_FRONT','a',2),id:'waiting-drape',status:'PREPARED',imageUrl:null};
 const rows=[row('MOCKUP_FRONT','a',1),row('DRAPE_MASTER','a',0),row('DRAPE_GUIDE','a',0),row('MATTE_G1'),ready,waiting];
 const h=harness(rows,{...localPlan,renderPolicy:'GROK_DRAPE_REUSE_V1'});await h.load('batch',{batchId:'batch',status:'RUNNING',stage:'MOCKUP'});h.step('mockup');
 assert.deepEqual(h.cards().map(card=>card.dataset.kind),['DRAPED_FRONT','DRAPED_FRONT']);
 const material=h.nodes.find(n=>n.className==='batch-position-guides batch-intermediate-assets');
 assert.deepEqual(material.querySelectorAll('article').map(card=>card.dataset.kind),['MOCKUP_FRONT','DRAPE_MASTER','DRAPE_GUIDE','MATTE_G1']);
 assert.match(material.querySelectorAll('article')[0].querySelector('h3').textContent,/搭放前定位图/);
 const counts=h.dispatched.filter(e=>e.type==='heyi:artifacts-updated').at(-1).detail;assert.equal(counts.ready,1);assert.equal(counts.total,6);
 assert.match(h.cards()[0].querySelector('h3').textContent,/商品主图 · 自然搭放/);
 h.filterSource('b');assert.ok(h.cards().every(card=>card.hidden));assert.ok(material.querySelectorAll('article').every(card=>card.hidden));
});
test('natural drape front retains the existing backend back-image flip and an old scene batch still counts its precise front',async()=>{
 const art={...row('DRAPED_FRONT'),backImageUrl:'/saved-drape-back'};
 const h=harness([art,row('MOCKUP_FRONT')],{...localPlan,renderPolicy:'GROK_DRAPE_REUSE_V1'});await h.load();h.step('mockup');
 const card=h.cards()[0],flip=card.querySelector('.artifact-flip');assert.ok(flip);await flip.handlers.click();
 assert.equal(card.querySelector('img').src,'blob:back-proof');assert.equal(h.requests.filter(r=>r.back).length,1);await flip.handlers.click();assert.equal(card.querySelector('img').src,art.imageUrl);
 h.setPlan({...localPlan,renderPolicy:'GROK_SCENE_V1'});h.setRows([row('MOCKUP_FRONT')]);await h.load('historical');h.step('mockup');
 assert.equal(h.cards()[0].dataset.kind,'MOCKUP_FRONT');assert.equal(h.dispatched.filter(e=>e.type==='heyi:artifacts-updated').at(-1).detail.ready,1);
});

test('a stopped READY drape mother exposes explicit regeneration without offering it for ready SKU images',async()=>{
 const h=harness([row('DRAPE_MASTER'),row('DRAPED_FRONT')]);
 await h.load('batch',{batchId:'batch',status:'WAITING_INPUT',stage:'MOCKUP'});
 const mother=h.cards().find(c=>c.querySelector('.artifact-retry'));
 assert.ok(mother);assert.equal(mother.querySelector('.artifact-retry').textContent,'按模板重做母图');
 assert.equal(h.cards().filter(c=>c.querySelector('.artifact-retry')).length,1);
 assert.ok(h.requests.every(r=>!r.options?.method));
});

test('model detail photos display by source and variant in mockup and publication, not as process assets',async()=>{
 const approved={...row('MODEL_DETAIL_1','a',2),check:{passed:true,reason:'Matches the new garment'}};
 const pending={...row('MODEL_DETAIL_2','a',2),check:{status:'REVIEW_RETRY_PENDING'}};
 const other={...row('MODEL_DETAIL_1','b',1),id:'other-detail',check:{passed:false,reason:'印花不一致'}};
 const plan={...localPlan,renderPolicy:'GROK_DRAPE_REUSE_V1',sources:[{...localPlan.sources[0],detailImageIds:['detail-a','detail-b']}]};
 const h=harness([row('DRAPED_FRONT'),approved,pending,other,row('DRAPE_MASTER','a',0)],plan);
 await h.load('batch',{batchId:'batch',status:'RUNNING',stage:'MOCKUP'});
 h.step('mockup');h.filterSource('a');
 const visible=h.cards().filter(card=>!card.hidden);
 assert.deepEqual(visible.map(card=>card.dataset.kind),['DRAPED_FRONT','MODEL_DETAIL_1','MODEL_DETAIL_2']);
 assert.equal(visible[1].querySelector('h3').textContent,'模特实穿详情 · 第 1 张 · V2');
 assert.equal(visible[1].querySelector('.batch-artifact-check').textContent,'模特实穿详情已检查 · 与当前新款一致');
 assert.equal(visible[2].querySelector('.batch-artifact-check').textContent,'详情图已保存 · 商品一致性检查中');
 visible[1].querySelector('.batch-artifact-open').handlers.click();
 assert.equal(h.nodes.find(node=>node.tag==='dialog').querySelector('img').src,approved.imageUrl);
 const materials=h.nodes.find(node=>node.className==='batch-position-guides batch-intermediate-assets');
 assert.deepEqual(materials.querySelectorAll('article').map(card=>card.dataset.kind),['DRAPE_MASTER']);
 h.step('publish');assert.equal(h.cards().filter(card=>!card.hidden).length,3);
 h.filterSource('b');const b=h.cards().filter(card=>!card.hidden);assert.equal(b.length,1);
 assert.match(b[0].querySelector('.batch-artifact-check').textContent,/详情图需纠正.*印花不一致/);
 h.step('design');assert.equal(h.cards().filter(card=>!card.hidden).length,0);
 assert.ok(h.requests.every(request=>!request.options?.method),'viewing details never submits a generation');
});

test('model detail totals use frozen detail count times variants independently of colors',async()=>{
 const plan={...localPlan,renderPolicy:'GROK_DRAPE_REUSE_V1',sources:[{...localPlan.sources[0],detailImageIds:['d1','d2']}]};
 const h=harness([row('DRAPED_FRONT'),{...row('MODEL_DETAIL_1'),check:{passed:true}},
  {...row('MODEL_DETAIL_2'),status:'PREPARED',imageUrl:null},row('DRAPE_MASTER')],plan);
 await h.load();h.step('mockup');
 const count=h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail;
 assert.equal(count.total,10,'6 color fronts + 4 model detail photos');assert.equal(count.ready,2);
 const status=h.nodes.find(node=>node.className==='batch-artifact-panel').querySelector('p').textContent;
 assert.match(status,/已归档 2 \/ 10 张主图与详情图/);
 assert.match(status,/每款按原详情数量生成.*每组最多4张.*全部检查通过后进入待上架/);
 assert.doesNotMatch(status,/每个来源商品 1 次额外 Grok 调用/);
 h.setPlan(localPlan);h.setRows([row('MOCKUP_FRONT')]);await h.load('legacy');
 assert.equal(h.dispatched.filter(event=>event.type==='heyi:artifacts-updated').at(-1).detail.total,6,'changing batches clears detail counts');
});

test('detail pass labels require a real boolean and do not invent success from a string',async()=>{
 for(const passed of ['false','true',1,null]){
  const h=harness([{...row('MODEL_DETAIL_1'),check:{passed}}],localPlan);
  await h.load();h.step('mockup');
  assert.equal(h.cards()[0].querySelector('.batch-artifact-check').textContent,'详情图已保存 · 商品一致性检查中');
 }
});

test('pending detail resumes in the same card and its eventual pass renders without a second submit',async()=>{
 const pending={...row('MODEL_DETAIL_1'),status:'RESULT_RECEIVED',imageUrl:null};
 const h=harness([pending],localPlan);await h.load('batch',{batchId:'batch',status:'RUNNING',stage:'MOCKUP'});h.step('mockup');
 const card=h.cards()[0];assert.equal(card.dataset.motion,'running');
 h.setRows([{...pending,status:'READY',imageUrl:'/archived-detail',check:{passed:true}}]);await h.poll('RUNNING');
 assert.equal(h.cards()[0],card);assert.equal(card.dataset.motion,'ready');
 assert.equal(card.querySelector('img').src,'/archived-detail');
 assert.equal(card.querySelector('.batch-artifact-check').textContent,'模特实穿详情已检查 · 与当前新款一致');
 assert.ok(h.requests.every(request=>!request.options?.method));
});


test('grid originals are process materials and do not inflate independently split detail totals',async()=>{
 const plan={renderPolicy:'GROK_DRAPE_REUSE_V1',variantsPerPrint:1,sources:[{captureId:'a',colorTargets:[{key:'pink'}],detailImageIds:['d1','d2','d3']}]};
 const h=harness([{...row('MODEL_DETAIL_GRID_1'),check:{gridSplit:{status:'DONE',childCount:3}}},row('DRAPED_FRONT'),row('MODEL_DETAIL_1'),row('MODEL_DETAIL_2'),row('MODEL_DETAIL_3')],plan);
 await h.load();h.step('mockup');
 assert.match(h.nodes.find(n=>n.className==='batch-artifact-panel').children.find(n=>n.tag==='p').textContent,/已归档 4 \/ 4/);
 const originals=h.nodes.find(n=>n.className==='batch-artifact-grid batch-intermediate-grid');
 assert.ok(originals.children.some(card=>card.dataset.kind==='MODEL_DETAIL_GRID_1'));
 assert.match(originals.children.find(card=>card.dataset.kind==='MODEL_DETAIL_GRID_1').textContent||originals.children.find(card=>card.dataset.kind==='MODEL_DETAIL_GRID_1').querySelector('h3').textContent,/四宫格/);
});

test('detail unknown states explain automatic recovery and stop implying it is running after exhaustion',async()=>{
 const h=harness([{...row('MODEL_DETAIL_7'),status:'UNKNOWN',imageUrl:null}]);await h.load('batch',{batchId:'batch',status:'UNKNOWN'});
 const card=h.cards()[0];assert.ok(card.querySelectorAll('p').some(n=>n.textContent==='正在自动核对与恢复'));
 await h.poll('FAILED');assert.ok(card.querySelectorAll('p').some(n=>n.textContent==='恢复已停止 · 已保留请求'));
});
