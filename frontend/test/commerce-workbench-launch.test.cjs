const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
const batch={id:'new-batch',name:'新批次',status:'DRAFT',items:[{id:'item',captureId:'source',captureVersion:1,shopId:'shop',shopName:'店铺'}]};
const run={id:'execution',batchId:batch.id,stage:'EXTRACT',status:'RUNNING',message:'正在提取原印花'};
function harness(execution=run,overrides={}){
 const nodes=[],ids={},events={},requests=[],timers=[],emitted=[],listRead=deferred(),detailRead=deferred();
 function element(tag='div'){
  const classes=new Set(),handlers={};
  const node={tag,children:[],dataset:{},value:'',hidden:false,disabled:false,textContent:'',
   classList:{add:(...v)=>v.forEach(x=>classes.add(x)),remove:(...v)=>v.forEach(x=>classes.delete(x)),toggle:(x,on)=>on?classes.add(x):classes.delete(x),contains:x=>classes.has(x)},
   append(...v){this.children.push(...v);},replaceChildren(...v){this.children=[...v];},
   setAttribute(k,v){this[k]=v;},removeAttribute(k){delete this[k];},
   addEventListener(k,fn){(handlers[k]||=[]).push(fn);},dispatchEvent(e){return Promise.all((handlers[e.type]||[]).map(fn=>fn(e)));},
   click(){return this.dispatchEvent({type:'click'});},close(){this.open=false;},showModal(){this.open=true;},
   querySelectorAll(selector){const all=[];const visit=n=>n.children.forEach(child=>{all.push(child);visit(child);});visit(this);return all.filter(n=>n.tag===selector);},
   querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
  };nodes.push(node);return node;
 }
 const get=id=>ids[id]??=(element(id.includes('select')?'select':'div'));
 get('product-publish-page').hidden=true; // Tests dispatch committed launches; no automatic initial list read.
 const hub=element(),journey=element(),body=element(),nav=element('a');
 const fire=(type,detail)=>Promise.all((events[type]||[]).map(fn=>fn({type,detail})));
 const script=fs.readFileSync(path.join(__dirname,'../commerce-workbench.js'),'utf8');
 vm.runInNewContext(script.slice(0,script.indexOf('\n(() => {\n const panel=')),{
  document:{getElementById:get,createElement:element,body,
   querySelector:s=>s==='.commerce-batch-hub'?hub:s==='.studio-journey'?journey:nav,
   querySelectorAll:s=>s==='#workbench-batch-list button'?get('workbench-batch-list').querySelectorAll('button'):[]},
  window:{addEventListener:(type,fn)=>(events[type]||=[]).push(fn),dispatchEvent:e=>{emitted.push(e);return fire(e.type,e.detail);}},
  Event:class{constructor(type){this.type=type;}},CustomEvent:class{constructor(type,options={}){this.type=type;this.detail=options.detail;}},
  MutationObserver:class{observe(){}},encodeURIComponent,
  CommercePublication:{restore:overrides.restore||(async()=>[])},
  setTimeout:fn=>{timers.push(fn);return timers.length;},clearTimeout(){},
  api:async (url,options)=>{requests.push(url);if(overrides.api){const response=overrides.api(url,options);if(response!==undefined)return response;}if(url==='/api/v1/commerce-batches')return listRead.promise;
   if(url.endsWith('/execution/limits'))return {data:{reservedCalls:1,maxCalls:9}};
   if(url.endsWith('/execution'))return {data:execution};return detailRead.promise;}
 });
 return {nodes,ids,get,fire,requests,timers,emitted,listRead,detailRead,launch:()=>fire('heyi:batch-created',{batchId:batch.id,batch,execution,maxModelCalls:9})};
}
test('committed launch immediately selects the batch and polls execution while the list is still loading',async()=>{
 const h=harness(),pending=h.launch();await tick();
 assert.equal(h.get('workbench-batch-select').value,batch.id);
 assert.equal(h.get('workbench-item-select').value,'item');
 assert.ok(h.get('product-publish-page').classList.contains('has-workbench-batch'));
 assert.ok(h.requests.includes(`/api/v1/commerce-batches/${batch.id}/execution`));
 assert.ok(!h.requests.includes(`/api/v1/commerce-batches/${batch.id}`),'does not wait for an extra batch detail request');
 const box=h.nodes.find(n=>n.className==='workbench-execution');assert.equal(box.hidden,false);
 assert.ok(box.children.some(n=>n.textContent.includes('正在提取原印花')));
 const start=box.children.find(n=>n.textContent==='开始自动执行');assert.equal(start.hidden,true);
 h.listRead.resolve({data:[batch]});await pending;
 assert.equal(h.get('workbench-batch-select').value,batch.id);
});
test('list refresh failure does not lose the already running batch or its progress timer',async()=>{
 const h=harness(),pending=h.launch();h.listRead.reject(new Error('list unavailable'));await pending;await tick();
 assert.match(h.get('workbench-batch-status').textContent,/本批次已创建/);
 assert.equal(h.get('workbench-batch-select').value,batch.id);assert.ok(h.timers.length>0);
 await h.timers.at(-1)();
 const box=h.nodes.find(n=>n.className==='workbench-execution');assert.equal(box.hidden,false);
 assert.ok(box.children.some(n=>n.textContent.includes('正在提取原印花')));
});
test('late launch list response cannot restore the previous organization after a company change',async()=>{
 const h=harness(),pending=h.launch();await h.fire('heyi:company-reset');
 h.listRead.resolve({data:[batch]});await pending;
 assert.equal(h.get('workbench-batch-list').children.length,0);
 assert.ok(!h.get('product-publish-page').classList.contains('has-workbench-batch'));
 assert.equal(h.nodes.find(n=>n.className==='workbench-execution').hidden,true);
});
test('legacy creative-only draft event still reads details and respects company reset during list load',async()=>{
 const h=harness(),pending=h.fire('heyi:batch-created',{batchId:batch.id});
 h.listRead.resolve({data:[batch]});await pending;h.detailRead.resolve({data:batch});await tick();
 assert.ok(h.requests.includes(`/api/v1/commerce-batches/${batch.id}`));
 assert.equal(h.get('workbench-item-select').value,'item');
 const other=harness(),stale=other.fire('heyi:batch-created',{batchId:batch.id});
 await other.fire('heyi:company-reset');other.listRead.resolve({data:[batch]});await stale;
 assert.ok(!other.requests.includes(`/api/v1/commerce-batches/${batch.id}`));
});
test('confirmed model refusal is visibly stopped with both reasons and no resume or recovery action',async()=>{
 const h=harness({...run,status:'FAILED',message:'图片模型拒绝生成：GPT Image 2因第三方内容限制被拒绝；Grok：图片内容未通过服务商审核；本批次已停止'});
 const pending=h.launch();h.listRead.resolve({data:[batch]});await pending;await tick();
 const box=h.nodes.find(n=>n.className==='workbench-execution');
 assert.ok(box.children.some(n=>n.textContent.startsWith('已停止 ·')&&n.textContent.includes('GPT Image 2')&&n.textContent.includes('Grok')));
 for(const label of ['开始自动执行','暂停','恢复执行','核对已存结果并恢复'])assert.equal(box.children.find(n=>n.textContent===label).hidden,true,label);
 assert.doesNotMatch(h.get('workbench-batch-status').textContent,/DRAFT/);
});
test('single artifact retry immediately refreshes the selected execution and ignores another batch',async()=>{
 const h=harness({...run,status:'FAILED'});const pending=h.launch();h.listRead.resolve({data:[batch]});await pending;await tick();
 const box=h.nodes.find(n=>n.className==='workbench-execution');
 await h.fire('heyi:artifact-retry-started',{batchId:'other',execution:{...run,batchId:'other',status:'QUEUED'}});
 assert.ok(box.children.some(n=>n.textContent.startsWith('已停止')));
 await h.fire('heyi:artifact-retry-started',{batchId:batch.id,execution:{...run,status:'QUEUED',message:'已排队重试这张'}});
 assert.ok(box.children.some(n=>n.textContent.includes('已排队重试这张')));
 assert.equal(box.children.find(n=>n.textContent==='暂停').hidden,false);
 assert.ok(h.timers.length>0);
});

test('resume prepares all missing draft options first and only resumes after they are saved',async()=>{
 const gate=deferred(),restored=[];
 const h=harness({...run,stage:'PUBLISH',status:'WAITING_INPUT',message:'发布配置缺失'},
  {restore:(id,options)=>{restored.push({id,options});return gate.promise;},api:url=>url.endsWith('/resume')?{data:{...run,stage:'PUBLISH'}}:undefined});
 h.listRead.resolve({data:[batch]});await h.launch();await tick();
 const resume=h.nodes.find(n=>n.textContent==='恢复执行'),pending=resume.click();await tick();
 assert.equal(restored[0].id,batch.id);assert.equal(restored[0].options.isCurrent(),true);
 assert.ok(!h.requests.some(url=>url.endsWith('/resume')));
 gate.resolve([]);await pending;assert.equal(h.requests.filter(url=>url.endsWith('/resume')).length,1);
});

test('preparation errors remain visible across progress polls and no resume is sent',async()=>{
 const error=new Error('商品1：没有仓库；商品2：没有配送渠道');error.preparationFailed=true;
 const h=harness({...run,stage:'PUBLISH',status:'WAITING_INPUT',message:'自动准备发布参数'}, {restore:async()=>{throw error;}});
 h.listRead.resolve({data:[batch]});await h.launch();await tick();await h.nodes.find(n=>n.textContent==='恢复执行').click();
 await h.timers.at(-1)();const box=h.nodes.find(n=>n.className==='workbench-execution');
 assert.ok(box.children.some(n=>n.textContent===error.message));assert.ok(!h.requests.some(url=>url.endsWith('/resume')));
});

test('unrelated upload wait does not overwrite saved publication parameters',async()=>{
 let restored=0;const h=harness({...run,stage:'PUBLISH',status:'WAITING_CAPABILITY',message:'店铺接口暂时不可用'},
  {restore:async()=>{restored++;},api:url=>url.endsWith('/resume')?{data:run}:undefined});
 h.listRead.resolve({data:[batch]});await h.launch();await tick();await h.nodes.find(n=>n.textContent==='恢复执行').click();
 assert.equal(restored,0);assert.equal(h.requests.filter(url=>url.endsWith('/resume')).length,1);
});

test('completed upload is labelled as a draft rather than a live product',async()=>{
 const h=harness({...run,stage:'PUBLISH',status:'COMPLETED',message:'店铺接口已接受草稿'});
 h.listRead.resolve({data:[batch]});await h.launch();await tick();
 const box=h.nodes.find(n=>n.className==='workbench-execution');assert.ok(box.children.some(n=>n.textContent.startsWith('已上传草稿')));
 assert.ok(!box.children.some(n=>n.textContent.includes('上架完成')));
});

test('incomplete legacy upload options trigger automatic preparation on resume',async()=>{
 let restores=0;const h=harness({...run,stage:'PUBLISH',status:'WAITING_INPUT',message:'草稿上传：发布配置不完整：包裹重量无效'},
  {restore:async()=>{restores++;},api:url=>url.endsWith('/resume')?{data:run}:undefined});
 h.listRead.resolve({data:[batch]});await h.launch();await tick();await h.nodes.find(n=>n.textContent==='恢复执行').click();
 assert.equal(restores,1);assert.equal(h.requests.filter(url=>url.endsWith('/resume')).length,1);
});

test('starting a legacy draft prepares upload options while creative-only drafts keep their existing start path',async()=>{
 for(const creativeOnly of [false,true]){
  let restores=0;const h=harness(null,{restore:async()=>{restores++;},api:(url,options)=>url.endsWith('/execution')&&options?.method==='POST'?{data:run}:undefined});
  await h.fire('heyi:workbench-batch-selected',{batchId:batch.id,creativeOnly});await tick();
  await h.fire('heyi:creative-plan-state',{batchId:batch.id,version:1,ready:true});
  const start=h.nodes.find(n=>n.textContent==='开始自动执行');assert.equal(start.disabled,false);await start.click();
  assert.equal(restores,creativeOnly?0:1);
 }
});

test('local rebuild is adjacent to resume and only offered for stopped composition or publication stages',async()=>{
 for(const [stage,status,visible]of [['MOCKUP','WAITING_INPUT',true],['REVIEW','PAUSED',true],['PUBLISH','FAILED',true],['EXTRACT','FAILED',false],['MOCKUP','RUNNING',false],['PUBLISH','COMPLETED',false],['MOCKUP','UNKNOWN',false]]){
  const h=harness({...run,stage,status});h.listRead.resolve({data:[batch]});await h.launch();await tick();
  const box=h.nodes.find(n=>n.className==='workbench-execution'),button=h.nodes.find(n=>n.textContent==='重新套版');
  assert.equal(button.hidden,!visible,`${stage}/${status}`);assert.equal(box.children[box.children.indexOf(button)-1].textContent,'恢复执行');
  assert.equal(button.title,'复用已有印花，重新处理透明边缘并套版，不重新生图');
 }
});

test('neckline recovery does not add a manual button to the batch workspace',async()=>{
 const h=harness({...run,stage:'PUBLISH',status:'WAITING_INPUT'});
 h.listRead.resolve({data:[batch]});await h.launch();await tick();
 assert.equal(h.nodes.some(n=>n.textContent==='修复领口并重做搭放图'),false);
});

test('local rebuild prepares options first, suppresses duplicate clicks and reuses a persistent key after a lost response',async()=>{
 const gate=deferred(),writes=[];let restores=0;
 const queued={...run,stage:'MOCKUP',status:'QUEUED',message:'复用已有印花重新套版'};
 const h=harness({...run,stage:'PUBLISH',status:'WAITING_INPUT'}, {restore:()=>{restores++;return gate.promise;},api:(url,options)=>{
  if(url.endsWith('/rebuild-local')){writes.push(options);if(writes.length===1)throw new Error('网络响应丢失');return{data:{execution:queued}};}
 }});
 h.listRead.resolve({data:[batch]});await h.launch();await tick();const button=h.nodes.find(n=>n.textContent==='重新套版');
 const pending=button.click();await button.click();assert.equal(restores,1);assert.equal(writes.length,0);
 gate.resolve([]);await pending;await h.timers.at(-1)();await button.click();
 assert.equal(writes.length,2);assert.equal(writes[0].headers['Idempotency-Key'],`commerce-local-rebuild-v2-${batch.id}`);
 assert.equal(writes[1].headers['Idempotency-Key'],writes[0].headers['Idempotency-Key']);
 assert.ok(h.emitted.some(event=>event.type==='heyi:execution-state'&&event.detail.run===queued),'the artifact listener receives the refreshed run');
 assert.equal(button.hidden,true);
});

test('a company change during local rebuild preparation prevents the rebuild request',async()=>{
 const gate=deferred(),h=harness({...run,stage:'MOCKUP',status:'FAILED'}, {restore:()=>gate.promise});
 h.listRead.resolve({data:[batch]});await h.launch();await tick();const pending=h.nodes.find(n=>n.textContent==='重新套版').click();
 await h.fire('heyi:company-reset');gate.resolve([]);await pending;
 assert.ok(!h.requests.some(url=>url.endsWith('/rebuild-local')));
});

test('drape budget note uses source count and never multiplies its scene allowance by product outputs',async()=>{
 const h=harness(),pending=h.launch();h.listRead.resolve({data:[batch]});await pending;await tick();
 await h.fire('heyi:creative-plan-state',{batchId:batch.id,version:1,ready:false,renderPolicy:'GROK_DRAPE_REUSE_V1',sourceCount:2,expectedOutputs:48});
 const note=h.nodes.find(n=>n.tag==='small'&&n.textContent.includes('自然搭放另按'));
 assert.ok(note);assert.match(note.textContent,/本批最多 2 次/);assert.doesNotMatch(note.textContent,/48 次/);assert.match(note.textContent,/透明印花使用 IMG2.0/);
 await h.fire('heyi:creative-plan-state',{batchId:batch.id,version:1,ready:false,renderPolicy:'GROK_SCENE_V1'});assert.doesNotMatch(note.textContent,/自然搭放/);
});
