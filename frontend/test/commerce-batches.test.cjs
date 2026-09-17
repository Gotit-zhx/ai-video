const test=require('node:test'), assert=require('node:assert/strict');
const fs=require('node:fs'), vm=require('node:vm'), path=require('node:path');
const launchResult=(name='每日批次',status='QUEUED',maxModelCalls=24)=>({data:{batch:{id:'batch',name,status:'DRAFT'},execution:{id:'execution-1',batchId:'batch',stage:'CAPTURE',status,message:'任务状态'},maxModelCalls}});
const prepared=targets=>targets.map(target=>({...target,request:{confirmed:true,autoConvertPrice:true,publishStock:999,weightGrams:'250',packageLengthCm:'30',packageWidthCm:'25',packageHeightCm:'2'}}));
function setup(failFirst=false,configuration={},overrides={}){
  const calls=[],nodes={},events=new Map(),dispatched=[];let writes=0;
  function element(tag='div'){
    return {tag,children:[],handlers:{},value:'',textContent:'',disabled:false,checked:false,
      append(e){this.children.push(e);if(this.id==='commerce-batch-template'&&!this.value)this.value=e.value;},
      replaceChildren(){this.children=[];this.value='';},reset(){},
      addEventListener(name,fn){this.handlers[name]=fn;},
      querySelectorAll(query){
        const all=[];const walk=e=>e.children.forEach(c=>{all.push(c);walk(c);});walk(this);
        if(query.includes(':checked'))return all.filter(e=>e.name==='shop'&&e.checked);
        if(query.includes('checkbox'))return all.filter(e=>e.type==='checkbox');
        return all.filter(e=>['input','select','button'].includes(e.tag));
      }};
  }
  ['form','status','template','template-summary','render-policy','render-policy-field','render-policy-hint','artwork-picker','shop-count','shops','products','more','search','query','selected-count','refresh','detail','list'].forEach(id=>{
    nodes[id]=element();nodes[id].id='commerce-batch-'+id;
  });
  nodes.template.tag='select';
  nodes['render-policy'].tag='select';
  nodes['render-policy'].children=['GROK_SCENE_V1','GROK_DRAPE_REUSE_V1'].map(value=>({...element('option'),value}));
  nodes.form.children=[nodes.shops,nodes.products,nodes.template,nodes['render-policy']];
  nodes.form.elements={namedItem:()=>({value:'每日批次'})};
  const template={snapshotId:'snapshot',name:'跨平台模板',version:1,configuration:{platform:'SHOPEE',market:'PH',shopCount:2,shopIds:['s1','s2'],designsPerProduct:3,printTemplateSnapshotId:'print-snapshot',...configuration}};
  const printTemplates=overrides.printTemplates||[{snapshotId:'print-snapshot',name:'白T印花版型',version:2,configuration:{photoPrints:[{id:'flower',side:'FRONT',x:.45,y:.25,width:.1,height:.1},{id:'star',side:'BACK',x:.4,y:.3,width:.2,height:.2}]}}];
  const api=async(url,options)=>{
    calls.push({url,options});
    if(overrides.onCall){const response=overrides.onCall(url,options);if(response!==undefined)return await response;}
    if(options){writes++;if(failFirst&&writes===1)throw new Error('network failed');return url.endsWith('/create-and-start')?launchResult():{data:{id:'batch',name:'每日批次'}};}
    if(url==='/api/v1/commerce-templates')return {data:overrides.templates||[template]};
    if(url==='/api/v1/commerce-print-templates')return {data:printTemplates};
    if(url.startsWith('/api/v1/commerce-print-templates/snapshots/'))return {data:overrides.snapshots?.[url.split('/').pop()]||printTemplates.find(row=>row.snapshotId===url.split('/').pop())};
    if(url==='/api/v1/shops')return {data:overrides.shops||[{id:'s1',name:'店1',platform:'SHOPEE',region:'PH',status:'ACTIVE'},{id:'s2',name:'店2',platform:'TIKTOK_SHOP',region:'MY',status:'ACTIVE'},{id:'other-market',platform:'SHOPEE',region:'SG',status:'ACTIVE'},{id:'expired',platform:'TIKTOK_SHOP',region:'MY',status:'EXPIRED'},{id:'unsupported',platform:'AMAZON',region:'SG',status:'ACTIVE'},{id:'unsupported-market',platform:'TIKTOK_SHOP',region:'US',status:'ACTIVE'}]};
    if(url==='/api/v1/commerce-batches')return {data:[]};
    if(url.includes('/design-assets/candidates'))return {data:[{designId:'design-1',contentSha256:'a'.repeat(64),variantIndex:1,mode:'STOCK_MARKETING'}]};
    if(url.endsWith('/design-assets'))return {data:[]};
    if(url.endsWith('/review'))return {data:null};
    if(url.endsWith('/quota-reservations'))return {data:[{shopId:'s1',listingDate:'2026-09-08',timeZone:'Asia/Manila'}]};
    if(url.includes('product-captures'))return {data:{page:0,totalPages:1,content:overrides.products||[1,2,3,4].map(i=>({id:'p'+i,title:'商品'+i,sourcePlatform:'SHOPEE',status:'CAPTURED'}))}};
    return {data:{id:'batch',name:'每日批次',status:'DRAFT',templateSnapshotId:'snapshot',items:[{id:'item-1',captureId:'p1',captureVersion:3,shopName:'店1',plannedDay:1,status:'DRAFT'}]}};
  };
  let uuid=0;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../commerce-batches.js'),'utf8'),{
    document:{getElementById:id=>nodes[id.replace('commerce-batch-','')],createElement:element},api,
    crypto:{randomUUID:()=>`${overrides.uuidPrefix||'request'}-${++uuid}`},encodeURIComponent,console,currentUser:overrides.currentUser,sessionStorage:overrides.storage,
    AbortController,setTimeout:overrides.setTimeout||setTimeout,clearTimeout:overrides.clearTimeout||clearTimeout,
    CommercePublication:{prepare:overrides.prepare|| (async targets=>prepared(targets))},CommerceArtworkPicker:overrides.artworkPicker,
    addEventListener:(name,handler)=>events.set(name,handler),dispatchEvent:event=>dispatched.push(event),
    CustomEvent:class{constructor(type,options){this.type=type;this.detail=options.detail;}}});
  async function choose(targets=['s1','s2'],productIds=null){
    await nodes.refresh.handlers.click();
    nodes.shops.children.forEach(label=>label.children.filter(e=>e.tag==='input').forEach(e=>{e.checked=targets.includes(e.value);e.handlers.change();}));
    nodes.products.children.forEach(label=>label.children.filter(e=>e.tag==='input').forEach(e=>{e.checked=!productIds||productIds.includes(e.value);e.handlers.change();}));
  }
  const openDetails=()=>events.get('heyi:workbench-batch-selected')({detail:{batchId:'batch'}});
  return {nodes,calls,choose,events,dispatched,template,openDetails};
}
test('asset binding requires loaded preview and confirmation and pins selected output',async()=>{
  const s=setup();await s.choose();await s.nodes.form.handlers.submit({preventDefault(){}});await s.openDetails();
  const all=()=>{const a=[];const walk=e=>{a.push(e);e.children.forEach(walk);};walk(s.nodes.detail);return a;};
  const load=all().find(e=>e.textContent==='读取已审核图片与绑定记录');
  const item=all().find(e=>e.tag==='select');item.value='item-1';await load.handlers.click();
  const bind=all().find(e=>e.textContent==='固定这张图片');assert.equal(bind.disabled,true);
  const img=all().find(e=>e.tag==='img');img.handlers.load();
  await bind.handlers.click();assert.equal(s.calls.filter(c=>c.options&&c.url.endsWith('/design-assets')).length,0);
  const card=all().find(e=>e.tag==='article');card.children.find(e=>e.tag==='label'&&e.children.some(c=>c.type==='checkbox')).children.find(e=>e.type==='checkbox').checked=true;
  await bind.handlers.click();const write=s.calls.find(c=>c.options&&c.url.endsWith('/design-assets'));
  assert.deepEqual(JSON.parse(write.options.body),{itemId:'item-1',designId:'design-1',contentSha256:'a'.repeat(64),imagePosition:1,confirmed:true});
});
test('retains selected shops across platforms and markets and distributes each product only once',async()=>{
  const s=setup();await s.choose();
  assert.equal(s.nodes.shops.children.length,3);
  await s.nodes.form.handlers.submit({preventDefault(){}});
  const write=s.calls.find(c=>c.options);
  assert.equal(write.url,'/api/v1/commerce-batches/create-and-start');
  const body=JSON.parse(write.options.body);
  assert.equal(body.printTemplateSnapshotId,'print-snapshot');
  assert.equal(body.confirmed,true);assert.equal(body.maxModelCalls,undefined);
  assert.deepEqual(body.targets.map(t=>t.shopId),['s1','s2','s1','s2']);
  assert.equal(new Set(body.targets.map(t=>t.captureId)).size,4);
  assert.match(s.nodes.status.textContent,/已排队/);assert.match(s.nodes.status.textContent,/自动检查后上传草稿/);
  assert.match(s.nodes.status.textContent,/上限 24 次/);assert.match(s.nodes.status.textContent,/每张最多 3 次尝试/);
  assert.match(s.nodes.status.textContent,/成功即停止/);assert.match(s.nodes.status.textContent,/不会为用满上限补生成/);
  const retryNote=s.nodes['template-summary'].children.find(e=>e.textContent.includes('IMG2.0'));
  assert.match(retryNote.textContent,/每张最多 3 次尝试/);assert.match(retryNote.textContent,/计入本批次调用上限/);
  assert.ok(!s.calls.some(c=>c.url.includes('publish-workflows')));
});
test('legacy template without shop ids offers supported shops without using its first market as a filter',async()=>{
  const s=setup(false,{shopIds:[]});await s.choose();
  const inputs=s.nodes.shops.querySelectorAll('input[type="checkbox"]');
  assert.deepEqual(inputs.map(i=>i.value),['s1','s2','other-market']);
  inputs.find(i=>i.value==='other-market').checked=false;
  await s.nodes.form.handlers.submit({preventDefault(){}});
  const body=JSON.parse(s.calls.find(c=>c.options).options.body);
  assert.deepEqual(body.targets.map(t=>t.shopId),['s1','s2','s1','s2']);
});
test('inactive saved selections cannot filter or auto-select the current supported shops',async()=>{
  const s=setup(false,{shopIds:['expired','unsupported','unsupported-market']});await s.choose();
  assert.deepEqual(s.nodes.shops.querySelectorAll('input[type="checkbox"]').map(input=>input.value),['s1','s2','other-market']);
  await s.nodes.form.handlers.submit({preventDefault(){}});
  assert.equal(s.calls.filter(c=>c.options).length,1);
});
test('batch details read and display persisted reservation dates',async()=>{
  const s=setup();await s.choose();await s.nodes.form.handlers.submit({preventDefault(){}});await s.openDetails();
  const text=[];const walk=e=>{text.push(e.textContent);e.children.forEach(walk);};walk(s.nodes.detail);
  assert.ok(text.some(t=>t.includes('2026-09-08（Asia/Manila）· 1 条')));
  assert.ok(s.calls.some(c=>!c.options&&c.url.endsWith('/quota-reservations')));
});
test('unchanged request retries use the same idempotency key',async()=>{
  const s=setup(true);await s.choose();
  await s.nodes.form.handlers.submit({preventDefault(){}});
  await s.nodes.form.handlers.submit({preventDefault(){}});
  const writes=s.calls.filter(c=>c.options);
  assert.equal(writes.length,2);
  assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);
  assert.equal(writes[0].options.body,writes[1].options.body);
});
test('review requires confirmation and evidence, submits pinned item version without publishing',async()=>{
  const s=setup();await s.choose();await s.nodes.form.handlers.submit({preventDefault(){}});await s.openDetails();
  const all=[];const walk=e=>e.children.forEach(c=>{all.push(c);walk(c);});walk(s.nodes.detail);
  const submit=all.find(e=>e.textContent==='保存审核依据（不发布）');
  assert.ok(submit);
  await submit.handlers.click();
  assert.equal(s.calls.filter(c=>c.options&&c.url.endsWith('/review')).length,0);
  const label=all.find(e=>e.children.some(c=>c.textContent==='我已逐条复核，确认依据真实；提交后不可修改'));
  label.children.find(e=>e.type==='checkbox').checked=true;
  await submit.handlers.click();
  assert.equal(s.calls.filter(c=>c.options&&c.url.endsWith('/review')).length,0);
  all.filter(e=>e.tag==='textarea').forEach((e,i)=>e.value=['授权记录 R-1','供应 SKU-1','复核 Q-1',''][i]);
  await submit.handlers.click();
  const write=s.calls.find(c=>c.options&&c.url.endsWith('/review'));
  assert.deepEqual(JSON.parse(write.options.body),{confirmed:true,items:[{itemId:'item-1',captureVersion:3,rightsEvidence:'授权记录 R-1',supplySku:'供应 SKU-1',qualityReview:'复核 Q-1',productionEvidence:''}]});
  assert.ok(!s.calls.some(c=>c.url.includes('publish-workflows')));
});

const submit=s=>s.nodes.form.handlers.submit({preventDefault(){}});
const summary=s=>s.nodes['template-summary'].children.map(child=>child.textContent).join(' ');
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return{promise,resolve,reject};};
const flush=()=>new Promise(resolve=>setImmediate(resolve));

test('unified template loads its bound photo layout without selecting any historical shop',async()=>{
 const s=setup(false,{shopCount:50,shopIds:['s1','expired'],designsPerProduct:7});await s.nodes.refresh.handlers.click();
 assert.equal(s.nodes.shops.querySelectorAll('input[name="shop"]:checked').length,0);
 assert.match(summary(s),/跨平台模板 · 每组 7 款/);assert.match(summary(s),/四面图片 · 2处印花位置/);
 assert.match(summary(s),/先自动匹配店铺类目、仓库和包裹参数/);assert.match(summary(s),/裂变和套版/);assert.match(summary(s),/上传到店铺草稿/);
 assert.equal(s.calls.filter(call=>call.url.endsWith('/snapshots/print-snapshot')).length,1);
});

test('changing the unified template changes its bound layout while retaining chosen shops',async()=>{
 const first={snapshotId:'snapshot',name:'跨平台模板',version:1,configuration:{designsPerProduct:3,shopCount:2,shopIds:['s1'],printTemplateSnapshotId:'print-snapshot'}};
 const next={snapshotId:'next',name:'另一套裂变规则',version:1,configuration:{designsPerProduct:9,shopCount:50,shopIds:['expired'],printTemplateSnapshotId:'print-next'}};
 const printTemplates=[{snapshotId:'print-snapshot',name:'T恤版型',version:2,configuration:{photoPrints:[{id:'x'}]}},{snapshotId:'print-next',name:'卫衣版型',version:3,configuration:{photoPrints:[{id:'a'},{id:'b'},{id:'c'}]}}];
 const second=setup(false,{}, {templates:[first,next],printTemplates});await second.choose(['s2','other-market']);const originalInputs=second.nodes.shops.querySelectorAll('input[type="checkbox"]');
 second.nodes.template.value='next';await second.nodes.template.handlers.change();assert.match(summary(second),/每组 9 款/);assert.match(summary(second),/四面图片 · 3处印花位置/);
 assert.equal(second.nodes.shops.querySelectorAll('input[type="checkbox"]')[0],originalInputs[0]);assert.deepEqual(second.nodes.shops.querySelectorAll('input[name="shop"]:checked').map(input=>input.value),['s2','other-market']);
 await submit(second);const body=JSON.parse(second.calls.find(call=>call.options).options.body);assert.equal(body.templateSnapshotId,'next');assert.equal(body.printTemplateSnapshotId,'print-next');
 assert.deepEqual(body.targets.map(target=>target.shopId),['s2','other-market','s2','other-market']);
});

test('historical linked print version is loaded and submitted exactly with a legacy summary',async()=>{
 const old={snapshotId:'print-old',name:'旧版版型',version:1,configuration:{photoPrints:[],surfacePrints:[{id:'p'}],areas:[]}};
 const s=setup(false,{printTemplateSnapshotId:'print-old'},{snapshots:{'print-old':old}});await s.choose();assert.match(summary(s),/历史印花布局 · 1处印花位置/);
 assert.equal(s.calls.filter(call=>call.url==='/api/v1/commerce-print-templates/snapshots/print-old').length,1);
 await submit(s);assert.equal(JSON.parse(s.calls.find(call=>call.options).options.body).printTemplateSnapshotId,'print-old');
});

test('unlinked workflow must be completed in template configuration and never substitutes another layout',async()=>{
 const s=setup(false,{printTemplateSnapshotId:null});await s.choose();assert.equal(s.calls.filter(call=>call.url.includes('/snapshots/')).length,0);
 await submit(s);assert.match(s.nodes.status.textContent,/尚未绑定印花位置.*模板配置.*保存/);assert.equal(s.calls.filter(call=>call.options).length,0);
 assert.match(summary(s),/尚未绑定印花位置/);
});

test('unreadable binding blocks launch and can retry only its exact snapshot',async()=>{
 let attempts=0;const s=setup(false,{printTemplateSnapshotId:'unavailable'},{onCall:url=>{if(url.endsWith('/snapshots/unavailable')){if(++attempts===1)return Promise.reject(new Error('没有访问权限'));return {data:{snapshotId:'unavailable',configuration:{photoPrints:[{id:'p'}]}}};}}});
 await s.choose();assert.match(s.nodes.status.textContent,/读取失败.*重试.*模板配置/);await submit(s);assert.equal(s.calls.filter(call=>call.options).length,0);
 const retry=s.nodes['template-summary'].children.find(node=>node.textContent==='重试读取');assert.ok(retry);await retry.handlers.click();assert.match(summary(s),/四面图片 · 1处印花位置/);
 await submit(s);assert.equal(JSON.parse(s.calls.find(call=>call.options).options.body).printTemplateSnapshotId,'unavailable');assert.equal(attempts,2);
});

test('one to fifty shops are allowed independently of workflow planning and products cover every shop',async()=>{
 const shops=Array.from({length:51},(_,i)=>({id:`shop-${i}`,name:`店 ${i}`,status:'ACTIVE',platform:'SHOPEE',region:'MY'}));
 const products=Array.from({length:55},(_,i)=>({id:`product-${i}`,title:`商品 ${i}`}));
 const s=setup(false,{shopCount:2},{shops,products});await s.choose(shops.map(shop=>shop.id));await submit(s);
 assert.match(s.nodes.status.textContent,/1至50家/);assert.equal(s.calls.filter(call=>call.options).length,0);
 const inputs=s.nodes.shops.querySelectorAll('input[type="checkbox"]');inputs[50].checked=false;inputs[50].handlers.change();await submit(s);
 const body=JSON.parse(s.calls.find(call=>call.options).options.body);assert.equal(new Set(body.targets.map(target=>target.shopId)).size,50);
 assert.equal(new Set(body.targets.map(target=>target.captureId)).size,55);
 const small=setup();await small.choose(['s1','s2','other-market'],['p1','p2']);await submit(small);
 assert.match(small.nodes.status.textContent,/商品数量不能少于/);assert.equal(small.calls.filter(call=>call.options).length,0);
 const single=setup(false,{shopCount:50});await single.choose(['s1'],['p1']);await submit(single);assert.equal(single.calls.filter(call=>call.options).length,1);
});

test('changing the selected template after an unknown launch creates a new key for its bound layout',async()=>{
 const printTemplates=['first','second'].map(snapshotId=>({snapshotId,name:snapshotId,version:1,configuration:{photoPrints:[{id:'x'}]}}));
 const templates=['first','second'].map(value=>({snapshotId:'workflow-'+value,name:value,configuration:{printTemplateSnapshotId:value}}));
 const s=setup(true,{}, {templates,printTemplates});await s.choose();await submit(s);s.nodes.template.value='workflow-second';await s.nodes.template.handlers.change();await submit(s);
 const writes=s.calls.filter(call=>call.options);assert.notEqual(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);assert.equal(JSON.parse(writes[1].options.body).printTemplateSnapshotId,'second');
});

test('late historical snapshot cannot overwrite a newer unified template selection',async()=>{
 const pending=deferred(),first={snapshotId:'first',name:'旧规则',version:1,configuration:{designsPerProduct:1,printTemplateSnapshotId:'old'}},second={snapshotId:'second',name:'新规则',version:1,configuration:{designsPerProduct:8,printTemplateSnapshotId:'print-snapshot'}};
 const s=setup(false,{}, {templates:[first,second],onCall:url=>url.endsWith('/snapshots/old')?pending.promise:undefined});const reading=s.nodes.refresh.handlers.click();await flush();assert.match(summary(s),/正在读取模板/);
 s.nodes.template.value='second';await s.nodes.template.handlers.change();pending.resolve({data:{snapshotId:'old',name:'迟到版型',version:1,configuration:{surfacePrints:[]}}});await reading;
 assert.match(summary(s),/新规则 · 每组 8 款/);assert.match(summary(s),/四面图片 · 2处印花位置/);assert.doesNotMatch(summary(s),/迟到|没有印花位置/);
});

test('late old-company refresh failure and print lookup cannot replace new company data',async()=>{
 for(const lateLookup of [false,true]){
  const pending=deferred();let first=true;
  const target=lateLookup?'/api/v1/commerce-print-templates/snapshots/old':'/api/v1/commerce-templates';
  const s=setup(false,{printTemplateSnapshotId:lateLookup?'old':'print-snapshot'},{onCall:url=>{if(url===target&&first){first=false;return pending.promise;}}});
  const old=s.nodes.refresh.handlers.click();await flush();s.events.get('heyi:company-reset')();s.template.configuration.printTemplateSnapshotId='print-snapshot';
  await s.nodes.refresh.handlers.click();const expected=summary(s);
  if(lateLookup)pending.resolve({data:{snapshotId:'old',name:'另一公司模型',version:1,configuration:{areas:[]}}});else pending.reject(new Error('旧公司网络错误'));
  await old;assert.equal(summary(s),expected);assert.match(summary(s),/四面图片 · 2处印花位置/);assert.doesNotMatch(s.nodes.status.textContent,/旧公司|另一公司/);
  assert.equal(s.nodes.refresh.disabled,false);
 }
});

test('late old-company create cannot unlock or report over the current company submission',async()=>{
 const first=deferred(),second=deferred();let count=0;
 const s=setup(false,{}, {onCall:(url,options)=>url==='/api/v1/commerce-batches/create-and-start'&&options?(++count===1?first.promise:second.promise):undefined});
 await s.choose();const old=submit(s);await flush();s.events.get('heyi:company-reset')();await s.choose();const fresh=submit(s);await flush();
 assert.equal(s.nodes.template.disabled,true);first.reject(new Error('旧批次断开'));await old;
 assert.equal(s.nodes.template.disabled,true);assert.equal(s.nodes.refresh.disabled,true);assert.doesNotMatch(s.nodes.status.textContent,/旧批次/);
 second.resolve(launchResult('当前公司批次'));await fresh;assert.match(s.nodes.status.textContent,/当前公司批次/);assert.equal(s.nodes.template.disabled,false);
 assert.equal(s.dispatched.filter(event=>event.type==='heyi:batch-created').length,1);
});

test('create-and-start sends explicit confirmation then immediately selects the returned run without legacy detail reads',async()=>{
 const s=setup();await s.choose(['s2'],['p1','p3']);await submit(s);
 const writes=s.calls.filter(call=>call.options);assert.equal(writes.length,1);assert.equal(writes[0].url,'/api/v1/commerce-batches/create-and-start');
 const targets=[{captureId:'p1',shopId:'s2'},{captureId:'p3',shopId:'s2'}];
 assert.deepEqual(JSON.parse(writes[0].options.body),{name:'每日批次',templateSnapshotId:'snapshot',targets,printTemplateSnapshotId:'print-snapshot',confirmed:true,renderPolicy:'GROK_DRAPE_REUSE_V1',publicationRequests:prepared(targets)});
 assert.ok(!s.calls.some(call=>call.url==='/api/v1/commerce-batches/batch'||call.url.endsWith('/quota-reservations')||call.url.endsWith('/review')));
 const created=s.dispatched.find(event=>event.type==='heyi:batch-created');assert.equal(created.detail.batchId,'batch');assert.equal(created.detail.batch.id,'batch');
 assert.equal(created.detail.execution.id,'execution-1');assert.equal(created.detail.execution.status,'QUEUED');assert.equal(created.detail.maxModelCalls,24);
 assert.match(s.nodes.status.textContent,/已排队/);assert.doesNotMatch(s.nodes.status.textContent,/创作已完成/);
});

test('loading the launch form never selects products or historical shops on behalf of the user',async()=>{
 const s=setup();await s.nodes.refresh.handlers.click();
 assert.ok(s.nodes.products.querySelectorAll('input[type="checkbox"]').every(input=>!input.checked));
 assert.ok(s.nodes.shops.querySelectorAll('input[type="checkbox"]').every(input=>!input.checked));
 await submit(s);assert.equal(s.calls.filter(call=>call.options).length,0);assert.match(s.nodes.status.textContent,/1至500款商品/);
 const product=s.nodes.products.querySelectorAll('input[type="checkbox"]')[0];product.checked=true;product.handlers.change();
 await submit(s);assert.equal(s.calls.filter(call=>call.options).length,0);assert.match(s.nodes.status.textContent,/1至50家/);
});

test('a failed launch preserves every selection and retries the identical confirmed body with the same key',async()=>{
 const s=setup(true);await s.choose(['s2','other-market'],['p2','p4']);
 const selected=()=>({workflow:s.nodes.template.value,shops:s.nodes.shops.querySelectorAll('input[name="shop"]:checked').map(input=>input.value),products:s.nodes.products.querySelectorAll('input[type="checkbox"]').filter(input=>input.checked).map(input=>input.value)});
 const before=selected();await submit(s);assert.deepEqual(selected(),before);assert.equal(s.dispatched.length,0);assert.equal(s.nodes.template.disabled,false);
 assert.match(s.nodes.status.textContent,/network failed/);await submit(s);
 const writes=s.calls.filter(call=>call.options);assert.equal(writes.length,2);assert.equal(writes[0].options.body,writes[1].options.body);
 assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);assert.equal(JSON.parse(writes[1].options.body).confirmed,true);
});

test('repeated clicks during launch create one request and one execution selection event',async()=>{
 const pending=deferred(),s=setup(false,{}, {onCall:(url,options)=>url.endsWith('/create-and-start')&&options?pending.promise:undefined});
 await s.choose();const launching=submit(s);await submit(s);await submit(s);
 assert.equal(s.calls.filter(call=>call.options).length,1);assert.equal(s.nodes.template.disabled,true);assert.equal(s.dispatched.length,0);
 pending.resolve(launchResult('正在运行的批次','RUNNING',80));await launching;
 assert.equal(s.dispatched.filter(event=>event.type==='heyi:batch-created').length,1);assert.match(s.nodes.status.textContent,/正在执行/);assert.match(s.nodes.status.textContent,/80 次/);
 await submit(s);assert.equal(s.calls.filter(call=>call.options).length,1);
});

test('persisted waiting or unknown runs are shown as pending, only explicit COMPLETED is called complete',async()=>{
 const labels={UNKNOWN:'执行结果待核查',WAITING_INPUT:'等待补充资料',WAITING_CAPABILITY:'等待所需功能',PAUSED:'执行已暂停',FAILED:'执行遇到问题',COMPLETED:'创作已完成'};
 for(const [state,label]of Object.entries(labels)){
  const s=setup(false,{}, {onCall:(url,options)=>url.endsWith('/create-and-start')&&options?launchResult('状态批次',state):undefined});await s.choose();await submit(s);
  assert.ok(s.nodes.status.textContent.includes(label));if(state!=='COMPLETED')assert.doesNotMatch(s.nodes.status.textContent,/创作已完成/);
  const event=s.dispatched.find(event=>event.type==='heyi:batch-created');assert.equal(event.detail.execution.status,state);assert.match(s.nodes.status.textContent,/自动检查后上传草稿/);
 }
});

test('missing or mismatched launch result retains the form and key until a confirmed existing execution is returned',async()=>{
 for(const mutation of [result=>result.data.execution=null,result=>result.data.execution.batchId='other-batch',result=>result.data.execution.status='',result=>result.data.maxModelCalls=1001]){
  let result=launchResult();mutation(result);
  const s=setup(false,{}, {onCall:(url,options)=>url.endsWith('/create-and-start')&&options?result:undefined});await s.choose();await submit(s);
  assert.equal(s.dispatched.length,0);assert.match(s.nodes.status.textContent,/结果尚未确认/);assert.equal(s.nodes.products.querySelectorAll('input[type="checkbox"]').filter(input=>input.checked).length,4);
  result=launchResult();await submit(s);const writes=s.calls.filter(call=>call.options);
  assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);assert.equal(writes[0].options.body,writes[1].options.body);assert.equal(s.dispatched.length,1);
 }
});

test('late old-company successful launch cannot clear new selections or enter its execution monitor',async()=>{
 const pending=deferred();let first=true;
 const s=setup(false,{}, {onCall:(url,options)=>{if(url.endsWith('/create-and-start')&&options&&first){first=false;return pending.promise;}}});
 await s.choose(['s1'],['p1']);const old=submit(s);s.events.get('heyi:company-reset')();await s.choose(['s2'],['p3']);
 pending.resolve(launchResult('旧公司批次','RUNNING'));await old;
 assert.equal(s.dispatched.length,0);assert.doesNotMatch(s.nodes.status.textContent,/旧公司/);assert.equal(s.nodes.template.disabled,false);
 assert.deepEqual(s.nodes.products.querySelectorAll('input[type="checkbox"]').filter(input=>input.checked).map(input=>input.value),['p3']);
 assert.deepEqual(s.nodes.shops.querySelectorAll('input[name="shop"]:checked').map(input=>input.value),['s2']);
 await submit(s);assert.equal(s.dispatched.length,1);
});

test('search, same-value edits, switching templates away and back, and dialog reopen preserve an unconfirmed launch key',async()=>{
 const templates=[{snapshotId:'snapshot',name:'初始规则',version:1,configuration:{designsPerProduct:3,printTemplateSnapshotId:'print-snapshot'}},{snapshotId:'other-workflow',name:'另一规则',version:1,configuration:{designsPerProduct:4,printTemplateSnapshotId:'other-print'}}];
 const printTemplates=['print-snapshot','other-print'].map(snapshotId=>({snapshotId,name:snapshotId,version:1,configuration:{surfacePrints:[{id:'p'}]}}));
 for(const action of ['search','same-value','workflow-roundtrip','reopen']){
  const s=setup(true,{}, {templates,printTemplates});await s.choose();await submit(s);
  if(action==='search'){s.nodes.query.value='T恤';s.nodes.form.handlers.input?.({target:s.nodes.query});await s.nodes.search.handlers.click();}
  if(action==='same-value'){s.nodes.template.value='snapshot';await s.nodes.template.handlers.change();s.nodes.form.handlers.input?.({target:s.nodes.template});}
  if(action==='workflow-roundtrip'){for(const value of ['other-workflow','snapshot']){s.nodes.template.value=value;await s.nodes.template.handlers.change();}}
  if(action==='reopen')await s.choose();
  await submit(s);const writes=s.calls.filter(call=>call.options);
  assert.equal(writes.length,2,action);assert.equal(writes[0].options.body,writes[1].options.body,action);
  assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key'],action);
 }
});

function sessionStore(){const values=new Map();return{values,getItem:key=>values.get(key)||null,setItem:(key,value)=>values.set(key,value),removeItem:key=>values.delete(key)};}

test('same-organization page reload recovers an unconfirmed body and key, then verified success clears it',async()=>{
 const storage=sessionStore(),currentUser={organizationId:'org-A'};
 const first=setup(true,{}, {storage,currentUser,uuidPrefix:'before-reload'});await first.choose();await submit(first);assert.equal(storage.values.size,1);
 const initial=first.calls.find(call=>call.options);
 const reloaded=setup(false,{}, {storage,currentUser,uuidPrefix:'after-reload'});await reloaded.choose();await submit(reloaded);
 const retry=reloaded.calls.find(call=>call.options);assert.equal(retry.options.body,initial.options.body);assert.equal(retry.options.headers['Idempotency-Key'],initial.options.headers['Idempotency-Key']);
 assert.equal(storage.values.size,0);assert.equal(reloaded.dispatched.length,1);
});

test('session recovery is organization-scoped and company reset removes its unconfirmed request',async()=>{
 const storage=sessionStore();
 const a=setup(true,{}, {storage,currentUser:{organizationId:'org-A'},uuidPrefix:'company-a'});await a.choose();await submit(a);
 const aKey=a.calls.find(call=>call.options).options.headers['Idempotency-Key'];assert.equal(storage.values.size,1);
 const b=setup(false,{}, {storage,currentUser:{organizationId:'org-B'},uuidPrefix:'company-b'});await b.choose();await submit(b);
 assert.notEqual(b.calls.find(call=>call.options).options.headers['Idempotency-Key'],aKey);assert.equal(storage.values.size,1);
 assert.ok([...storage.values.keys()].every(key=>key.endsWith('org-A')));a.events.get('heyi:company-reset')();assert.equal(storage.values.size,0);
});

test('unavailable session storage still retains an in-memory key across refresh',async()=>{
 const storage={getItem(){throw new Error('blocked');},setItem(){throw new Error('blocked');},removeItem(){throw new Error('blocked');}};
 const s=setup(true,{}, {storage,currentUser:{organizationId:'org-A'}});await s.choose();await submit(s);await s.choose();await submit(s);
 const writes=s.calls.filter(call=>call.options);assert.equal(writes.length,2);assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);
});

function controlledClock(){
 let now=0,next=0;const tasks=new Map();
 return{tasks,setTimeout:(callback,delay)=>{const id=++next;tasks.set(id,{callback,due:now+delay});return id;},clearTimeout:id=>tasks.delete(id),advance(ms){
  now+=ms;for(const [id,task]of [...tasks].sort((a,b)=>a[1].due-b[1].due))if(task.due<=now){tasks.delete(id);task.callback();}
 }};
}

test('a sixty-second launch timeout aborts only the client wait, unlocks the form and retries the same body/key',async()=>{
 const clock=controlledClock(),pending=deferred(),storage=sessionStore();let first=true;
 const s=setup(false,{}, {setTimeout:clock.setTimeout,clearTimeout:clock.clearTimeout,storage,currentUser:{organizationId:'org-A'},onCall:(url,options)=>{
  if(url.endsWith('/create-and-start')&&options&&first){first=false;return pending.promise;}
 }});
 await s.choose(['s2'],['p1','p3']);const launching=submit(s);await flush();
 const request=s.calls.find(call=>call.options);assert.equal(request.options.signal.aborted,false);assert.equal(s.nodes.template.disabled,true);
 assert.equal(clock.tasks.size,1);clock.advance(59999);await flush();assert.equal(s.nodes.template.disabled,true);assert.equal(request.options.signal.aborted,false);
 clock.advance(1);await launching;assert.equal(request.options.signal.aborted,true);assert.equal(clock.tasks.size,0);
 assert.equal(s.nodes.template.disabled,false);assert.equal(s.nodes.refresh.disabled,false);
 assert.match(s.nodes.status.textContent,/尚未确认启动结果，重试会核对原批次/);assert.doesNotMatch(s.nodes.status.textContent,/已取消/);
 assert.equal(s.dispatched.length,0);assert.equal(storage.values.size,1);
 assert.deepEqual(s.nodes.products.querySelectorAll('input[type="checkbox"]').filter(input=>input.checked).map(input=>input.value),['p1','p3']);
 assert.deepEqual(s.nodes.shops.querySelectorAll('input[name="shop"]:checked').map(input=>input.value),['s2']);
 await submit(s);const writes=s.calls.filter(call=>call.options);assert.equal(writes.length,2);
 assert.equal(writes[0].options.body,writes[1].options.body);assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);
 assert.notEqual(writes[0].options.signal,writes[1].options.signal);assert.equal(writes[1].options.signal.aborted,false);assert.equal(storage.values.size,0);
 const accepted=s.nodes.status.textContent;pending.resolve(launchResult('迟到旧响应','COMPLETED'));await flush();assert.equal(s.nodes.status.textContent,accepted);assert.equal(s.dispatched.length,1);
});

test('completed launch cleans its deadline so a later timer cannot abort or relabel the accepted run',async()=>{
 const clock=controlledClock(),s=setup(false,{}, {setTimeout:clock.setTimeout,clearTimeout:clock.clearTimeout});await s.choose();await submit(s);
 const request=s.calls.find(call=>call.options),accepted=s.nodes.status.textContent;assert.equal(clock.tasks.size,0);clock.advance(60000);await flush();
 assert.equal(request.options.signal.aborted,false);assert.equal(s.nodes.status.textContent,accepted);assert.equal(s.dispatched.length,1);
 assert.match(summary(s),/自动检查通过后上传到店铺草稿/);
});

test('company reset aborts the outstanding launch wait and clears its timer before any late server response',async()=>{
 const clock=controlledClock(),pending=deferred(),s=setup(false,{}, {setTimeout:clock.setTimeout,clearTimeout:clock.clearTimeout,onCall:(url,options)=>url.endsWith('/create-and-start')&&options?pending.promise:undefined});
 await s.choose();const old=submit(s);await flush();const request=s.calls.find(call=>call.options);s.events.get('heyi:company-reset')();await old;
 assert.equal(request.options.signal.aborted,true);assert.equal(clock.tasks.size,0);assert.equal(s.nodes.template.disabled,false);assert.equal(s.nodes.status.textContent,'');
 await s.choose(['s2'],['p4']);const currentSummary=summary(s);pending.resolve(launchResult('旧公司任务','RUNNING'));await flush();clock.advance(60000);await flush();
 assert.equal(s.dispatched.length,0);assert.equal(summary(s),currentSummary);assert.doesNotMatch(s.nodes.status.textContent,/旧公司|超过60秒/);
 assert.deepEqual(s.nodes.products.querySelectorAll('input[type="checkbox"]').filter(input=>input.checked).map(input=>input.value),['p4']);
});

test('every upload target is prepared before creating a paid execution and all preparation failures remain in the form',async()=>{
 const gate=deferred();let received;
 const s=setup(false,{}, {prepare:targets=>{received=targets;return gate.promise;}});
 await s.choose(['s1','s2'],['p1','p2']);const launching=submit(s);await flush();
 assert.deepEqual(JSON.parse(JSON.stringify(received)),[{captureId:'p1',shopId:'s1'},{captureId:'p2',shopId:'s2'}]);
 assert.equal(s.calls.filter(call=>call.options).length,0);
 const error=new Error('商品1 → 店1：缺少配送渠道\n商品2 → 店2：缺少仓库');error.preparationFailed=true;gate.reject(error);await launching;
 assert.equal(s.calls.filter(call=>call.options).length,0);assert.equal(s.dispatched.length,0);
 assert.match(s.nodes.status.textContent,/缺少配送渠道\n商品2.*缺少仓库/);assert.equal(s.nodes.template.disabled,false);
});

test('an unconfirmed launch retry retains prepared prices and warehouse options without preparing again',async()=>{
 let prepares=0;
 const s=setup(true,{}, {prepare:async targets=>{prepares++;return prepared(targets);}});
 await s.choose();await submit(s);await submit(s);assert.equal(prepares,1);
 const writes=s.calls.filter(call=>call.options);assert.equal(writes[0].options.body,writes[1].options.body);
});

test('company change during upload preparation prevents every launch and rejects the old wait',async()=>{
 const gate=deferred(),s=setup(false,{}, {prepare:()=>gate.promise});await s.choose();const launching=submit(s);await flush();
 s.events.get('heyi:company-reset')();await launching;gate.resolve(prepared([{captureId:'p1',shopId:'s1'}]));await flush();
 assert.equal(s.calls.filter(call=>call.options).length,0);assert.equal(s.dispatched.length,0);assert.equal(s.nodes.status.textContent,'');
});

test('explicit backend pre-generation rejection refreshes configuration but unrelated conflicts retain the frozen launch',async()=>{
 for(const [message,count]of [['自动准备发布参数未完成：店铺授权失效',2],['请求执行结果待核查',1]]){
  let attempts=0,prepares=0;
  const s=setup(false,{}, {prepare:async targets=>{prepares++;return prepared(targets);},onCall:(url,options)=>{
   if(url.endsWith('/create-and-start')&&options&&attempts++===0){const error=new Error(message);error.status=409;throw error;}
  }});
  await s.choose();await submit(s);await submit(s);assert.equal(prepares,count);
  const writes=s.calls.filter(call=>call.options);assert.equal(writes.length,2);
  assert.equal(writes[0].options.headers['Idempotency-Key']===writes[1].options.headers['Idempotency-Key'],count===1);
 }
});

test('photo placement count has priority over historical surface and area fields',async()=>{
 const printTemplates=[{snapshotId:'print-snapshot',configuration:{photoPrints:[{id:'a'},{id:'b'},{id:'c'}],surfacePrints:[{id:'legacy'}],areas:[{id:'old-one'},{id:'old-two'}]}}];
 const s=setup(false,{}, {printTemplates});await s.choose();assert.match(summary(s),/四面图片 · 3处印花位置/);await submit(s);assert.equal(s.calls.filter(call=>call.options).length,1);
});

test('zero positions in all layouts block launch before upload preparation',async()=>{
 const printTemplates=[{snapshotId:'print-snapshot',configuration:{photoPrints:[],surfacePrints:[],areas:[]}}];let preparations=0;
 const s=setup(false,{}, {printTemplates,prepare:async targets=>{preparations++;return prepared(targets);}});await s.choose();await submit(s);
 assert.match(s.nodes.status.textContent,/没有印花位置.*模板配置.*保存/);assert.equal(preparations,0);assert.equal(s.calls.filter(call=>call.options).length,0);
});

test('legacy flat areas remain usable when the surface array is empty',async()=>{
 const s=setup(false,{}, {printTemplates:[{snapshotId:'print-snapshot',configuration:{surfacePrints:[],areas:[{id:'old-a'},{id:'old-b'}]}}]});await s.choose();assert.match(summary(s),/图片印花布局 · 2处印花位置/);await submit(s);assert.equal(s.calls.filter(call=>call.options).length,1);
});

test('a mismatched bound snapshot response cannot silently substitute another layout',async()=>{
 const s=setup(false,{}, {onCall:url=>url.endsWith('/snapshots/print-snapshot')?{data:{snapshotId:'another-snapshot',configuration:{photoPrints:[{id:'p'}]}}}:undefined});
 await s.choose();await submit(s);assert.match(s.nodes.status.textContent,/读取失败.*重试/);assert.equal(s.calls.filter(call=>call.options).length,0);
});

function pickerFixture(){
 const data={selection:null,state:{reuse:false,count:0,busy:false,error:null},sets:[],resets:0,locks:[],onChange:null};
 const component={mount({onChange}){data.onChange=onChange;return{setTemplate(config){data.sets.push(config);onChange();},getSelection(){if(data.selection instanceof Error)throw data.selection;return data.selection;},getState(){return data.state;},reset(){data.resets++;data.selection=null;data.state={reuse:false,count:0,busy:false,error:null};onChange();},setLocked(value){data.locks.push(value);}};}};
 return {data,component,choose(selection){data.selection=selection;data.state={reuse:true,count:selection?.length||0,busy:false,error:null};data.onChange?.();}};
}
const reuseSelection=id=>[{groupArtifacts:{['a'.repeat(64)]:id}}];

test('reused artwork is part of the frozen launch payload and unknown retries preserve it',async()=>{
 const picker=pickerFixture(),s=setup(true,{}, {artworkPicker:picker.component});await s.choose();picker.choose(reuseSelection('artifact-one'));
 assert.match(summary(s),/已选择 1 款已有印花/);await submit(s);await submit(s);const writes=s.calls.filter(call=>call.options);
 assert.deepEqual(JSON.parse(writes[0].options.body).reusedArtworks,reuseSelection('artifact-one'));assert.equal(writes[0].options.body,writes[1].options.body);assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);assert.deepEqual(picker.data.locks,[true,false,true,false]);
});

test('same bound snapshot keeps artwork choices but choosing another workflow resets them',async()=>{
 const templates=['one','two'].map(snapshotId=>({snapshotId,name:snapshotId,configuration:{printTemplateSnapshotId:'print-snapshot'}})),picker=pickerFixture(),s=setup(false,{}, {templates,artworkPicker:picker.component});
 await s.choose();picker.choose(reuseSelection('selected'));const before=picker.data.resets;await s.nodes.template.handlers.change();assert.equal(picker.data.resets,before);assert.equal(picker.data.sets.length,1);assert.deepEqual(picker.data.selection,reuseSelection('selected'));
 s.nodes.template.value='two';await s.nodes.template.handlers.change();assert.equal(picker.data.selection,null);assert.equal(picker.data.sets.length,2);assert.equal(picker.data.resets,before+1);
});

test('changing reused artwork after an unknown launch receives a new idempotency key',async()=>{
 const picker=pickerFixture(),s=setup(true,{}, {artworkPicker:picker.component});await s.choose();picker.choose(reuseSelection('first'));await submit(s);picker.choose(reuseSelection('second'));await submit(s);
 const writes=s.calls.filter(call=>call.options);assert.notEqual(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);assert.deepEqual(JSON.parse(writes[1].options.body).reusedArtworks,reuseSelection('second'));
});

test('incomplete or loading reused artwork cannot fall back to paid print generation',async()=>{
 for(const failure of ['empty','busy','error','throws']){
  const picker=pickerFixture(),s=setup(false,{}, {artworkPicker:picker.component});await s.choose();picker.choose([]);
  if(failure==='busy')picker.data.state.busy=true;if(failure==='error')picker.data.state.error='请给全部图案组选择素材';if(failure==='throws')picker.data.selection=new Error('已有素材未完整选择');
  await submit(s);assert.equal(s.calls.filter(call=>call.options).length,0,failure);assert.match(s.nodes.status.textContent,/请选择|正在读取|全部图案组|完整选择/);
 }
});

test('company reset clears reused artwork and an old submission cannot unlock the new company picker',async()=>{
 const gate=deferred(),picker=pickerFixture(),s=setup(false,{}, {artworkPicker:picker.component,onCall:(url,options)=>url.endsWith('/create-and-start')&&options?gate.promise:undefined});
 await s.choose();picker.choose(reuseSelection('company-one'));const pending=submit(s);await flush();assert.equal(picker.data.locks.at(-1),true);
 s.events.get('heyi:company-reset')();assert.equal(picker.data.selection,null);assert.equal(picker.data.locks.at(-1),false);const count=picker.data.locks.length;gate.resolve(launchResult('old'));await pending;assert.equal(picker.data.locks.length,count);
});

test('photo-template reuse distinguishes saved print reuse from shared scene generation cost',async()=>{
 const picker=pickerFixture(),s=setup(false,{}, {artworkPicker:picker.component});await s.choose();picker.choose(reuseSelection('saved-artifact'));
 s.nodes['render-policy'].value='GROK_SCENE_V1';s.nodes['render-policy'].handlers.change();
 assert.match(summary(s),/提取商品背景风格 · 同商品多款多色共用/);assert.match(summary(s),/背景准备与印花生成分别计入实际调用/);assert.match(summary(s),/不再生成新印花/);assert.doesNotMatch(summary(s),/无需.*生图|零.*调用/);
 assert.match(summary(s),/场景氛围、配色、材质与光线重新设计背景/);
});

test('eligible new photo templates default to natural drape with one scene call per source, not per variant or color',async()=>{
 const s=setup(false,{designsPerProduct:9});await s.choose(['s1'],['p1','p3']);
 assert.equal(s.nodes['render-policy'].value,'GROK_DRAPE_REUSE_V1');assert.equal(s.nodes['render-policy-field'].hidden,false);
 assert.match(s.nodes['render-policy-hint'].textContent,/单处小面积正面胸花/);
 assert.match(s.nodes['render-policy-hint'].textContent,/纯白服装模板/);assert.match(s.nodes['render-policy-hint'].textContent,/背面、侧面补图沿用原有方式/);
 const summary=s.nodes['template-summary'].children.map(n=>n.textContent).join(' ');
 assert.match(summary,/本次最多 2 次/);assert.match(summary,/不按款数或颜色数追加/);assert.doesNotMatch(summary,/最多 18 次/);
 await submit(s);assert.equal(JSON.parse(s.calls.find(c=>c.options).options.body).renderPolicy,'GROK_DRAPE_REUSE_V1');
});
test('legacy templates cannot opt into drape and keeping the old path omits the unsupported policy',async()=>{
 const s=setup(false,{}, {printTemplates:[{snapshotId:'print-snapshot',configuration:{surfacePrints:[{id:'old'}]}}]});await s.choose();
 assert.equal(s.nodes['render-policy-field'].hidden,true);assert.equal(s.nodes['render-policy'].disabled,true);
 s.nodes['render-policy'].value='GROK_DRAPE_REUSE_V1';await submit(s);assert.equal(s.calls.filter(c=>c.options).length,0);
 assert.match(s.nodes.status.textContent,/已保存的图片印花模板/);
 s.nodes['render-policy'].value='GROK_SCENE_V1';await submit(s);assert.equal(JSON.parse(s.calls.find(c=>c.options).options.body).renderPolicy,undefined);
});
test('natural drape unknown retry freezes the chosen policy and changing policy changes the request key',async()=>{
 const s=setup(true);await s.choose();s.nodes['render-policy'].value='GROK_DRAPE_REUSE_V1';s.nodes['render-policy'].handlers.change();await submit(s);await submit(s);
 const calls=s.calls.filter(c=>c.options);assert.equal(calls[0].options.body,calls[1].options.body);assert.equal(calls[0].options.headers['Idempotency-Key'],calls[1].options.headers['Idempotency-Key']);
 const other=setup(true);await other.choose();await submit(other);other.nodes['render-policy'].value='GROK_SCENE_V1';other.nodes['render-policy'].handlers.change();await submit(other);
 const changed=other.calls.filter(c=>c.options);assert.notEqual(changed[0].options.headers['Idempotency-Key'],changed[1].options.headers['Idempotency-Key']);
});
test('a pre-selector unknown default launch replays its original body and key across an app upgrade',async()=>{
 const storage=sessionStore(),currentUser={organizationId:'legacy-org'};
 const first=setup(true,{}, {storage,currentUser});await first.choose();await submit(first);
 const [key,serialized]=[...storage.values][0],saved=JSON.parse(serialized),oldBody=JSON.parse(saved.body);delete oldBody.renderPolicy;saved.body=JSON.stringify(oldBody);storage.setItem(key,JSON.stringify(saved));
 const next=setup(false,{}, {storage,currentUser});await next.choose();await submit(next);
 const request=next.calls.find(c=>c.options).options;assert.equal(request.body,saved.body);assert.equal(request.headers['Idempotency-Key'],saved.key);
});

test('unsupported drape layouts are disabled and rejected before upload preparation',async()=>{
 const front={id:'chest',artworkId:'a'.repeat(64),side:'FRONT',x:.4,y:.25,width:.1,height:.1};
 for(const prints of [[{...front,side:'BACK'}],[front,{...front,id:'second'}],[{...front,width:.4,height:.3}],[{...front,x:.1}],[{...front,y:.5}],[{...front,width:null}]]){
  let preparations=0;const s=setup(false,{}, {printTemplates:[{snapshotId:'print-snapshot',configuration:{photoPrints:prints}}],prepare:async()=>{preparations++;return[];}});await s.choose();
  assert.equal(s.nodes['render-policy'].children[1].disabled,true);assert.match(s.nodes['render-policy-hint'].textContent,/正面胸花/);
  s.nodes['render-policy'].value='GROK_DRAPE_REUSE_V1';await submit(s);assert.equal(preparations,0);assert.equal(s.calls.filter(c=>c.options).length,0);
  s.nodes['render-policy'].handlers.change();assert.equal(s.nodes['render-policy'].value,'GROK_DRAPE_REUSE_V1');assert.match(s.nodes['render-policy-hint'].textContent,/已保留自然搭放选择/);
 }
});

test('natural drape with saved artwork retains both policy and selected assets in the launch payload',async()=>{
 const picker=pickerFixture(),s=setup(false,{}, {artworkPicker:picker.component});await s.choose(['s1'],['p1']);picker.choose(reuseSelection('saved-artifact'));
 s.nodes['render-policy'].value='GROK_DRAPE_REUSE_V1';s.nodes['render-policy'].handlers.change();assert.equal(s.nodes['render-policy'].children[1].disabled,false);
 assert.match(summary(s),/本次最多 1 次/);assert.match(summary(s),/不再生成新印花/);
 await submit(s);const body=JSON.parse(s.calls.find(c=>c.options).options.body);
 assert.equal(body.renderPolicy,'GROK_DRAPE_REUSE_V1');assert.deepEqual(body.reusedArtworks,reuseSelection('saved-artifact'));assert.equal(body.publicationRequests.length,1);
});

const eligiblePrint=(snapshotId='print-snapshot')=>({snapshotId,configuration:{photoPrints:[{id:'front',side:'FRONT',x:.45,y:.25,width:.1,height:.1,artworkId:'a'.repeat(64)}]}});
const workflowFor=(snapshotId,printTemplateSnapshotId)=>({snapshotId,name:snapshotId,version:1,configuration:{printTemplateSnapshotId,designsPerProduct:3}});

test('explicit scene and drape selections survive new-dialog refresh and uncached template loading',async()=>{
 for(const policy of ['GROK_SCENE_V1','GROK_DRAPE_REUSE_V1']){
  const gate=deferred(),s=setup(false,{}, {templates:[workflowFor('snapshot','print-snapshot'),workflowFor('next','other-print')],onCall:url=>url.endsWith('/snapshots/other-print')?gate.promise:undefined});
  await s.choose();s.nodes['render-policy'].value=policy;s.nodes['render-policy'].handlers.change();await s.choose();assert.equal(s.nodes['render-policy'].value,policy,'refresh must not clear user intent');
  s.nodes.template.value='next';const loading=s.nodes.template.handlers.change();await flush();assert.equal(s.nodes['render-policy'].value,policy,'missing cache while loading must not downgrade');
  gate.resolve({data:eligiblePrint('other-print')});await loading;assert.equal(s.nodes['render-policy'].value,policy);
  await submit(s);assert.equal(JSON.parse(s.calls.find(c=>c.options).options.body).renderPolicy,policy);
 }
});

test('explicit drape stays selected on unsupported photo or historical templates and blocks submission',async()=>{
 for(const config of [{photoPrints:[{id:'back',side:'BACK'}]},{surfacePrints:[{id:'legacy'}]}]){
  const s=setup(false,{}, {templates:[workflowFor('snapshot','print-snapshot'),workflowFor('next','unsupported')],printTemplates:[eligiblePrint(),{snapshotId:'unsupported',configuration:config}]});
  await s.choose();s.nodes['render-policy'].value='GROK_DRAPE_REUSE_V1';s.nodes['render-policy'].handlers.change();s.nodes.template.value='next';await s.nodes.template.handlers.change();
  assert.equal(s.nodes['render-policy'].value,'GROK_DRAPE_REUSE_V1');assert.equal(s.nodes['render-policy-field'].hidden,false);assert.equal(s.nodes['render-policy'].disabled,false);assert.match(s.nodes['render-policy-hint'].textContent,/已保留自然搭放选择/);
  await submit(s);assert.equal(s.calls.filter(c=>c.options).length,0);
  s.nodes['render-policy'].value='GROK_SCENE_V1';s.nodes['render-policy'].handlers.change();await submit(s);assert.equal(s.calls.filter(c=>c.options).length,1);
 }
});

test('unselected unsupported templates explain the scene default and eligible templates restore the drape default',async()=>{
 const s=setup(false,{}, {templates:[workflowFor('unsupported-workflow','unsupported'),workflowFor('eligible-workflow','print-snapshot')],printTemplates:[{snapshotId:'unsupported',configuration:{photoPrints:[{id:'back',side:'BACK'}]}},eligiblePrint()]});
 await s.choose();assert.equal(s.nodes['render-policy'].value,'GROK_SCENE_V1');assert.match(s.nodes['render-policy-hint'].textContent,/当前模板使用原有模式/);
 s.nodes.template.value='eligible-workflow';await s.nodes.template.handlers.change();assert.equal(s.nodes['render-policy'].value,'GROK_DRAPE_REUSE_V1');
});

test('old unknown scene launches restore their payload, mode and key despite the new drape default',async()=>{
 for(const legacyMissingPolicy of [false,true]){
  const storage=sessionStore(),currentUser={organizationId:'old-default-'+legacyMissingPolicy};
  const old=setup(true,{}, {storage,currentUser});await old.choose();old.nodes['render-policy'].value='GROK_SCENE_V1';old.nodes['render-policy'].handlers.change();await submit(old);
  const [key,value]=[...storage.values][0],saved=JSON.parse(value);
  if(legacyMissingPolicy){const body=JSON.parse(saved.body);delete body.renderPolicy;saved.body=JSON.stringify(body);storage.setItem(key,JSON.stringify(saved));}
  let preparations=0;const next=setup(false,{}, {storage,currentUser,prepare:async()=>{preparations++;throw new Error('must replay frozen preparation');}});await next.choose();
  assert.equal(next.nodes['render-policy'].value,'GROK_SCENE_V1');assert.match(summary(next),/继续上次未确认的请求，沿用原有模式/);
  await submit(next);const request=next.calls.find(c=>c.options).options;assert.equal(request.body,saved.body);assert.equal(request.headers['Idempotency-Key'],saved.key);assert.equal(preparations,0);assert.doesNotMatch(next.nodes.status.textContent,/自然搭放按/);
 }
});

test('recovering old saved-artwork mode does not change reuse assets and a different selection receives the new default',async()=>{
 const storage=sessionStore(),currentUser={organizationId:'reuse-old-default'},firstPicker=pickerFixture();
 const first=setup(true,{}, {storage,currentUser,artworkPicker:firstPicker.component});await first.choose();firstPicker.choose(reuseSelection('old-print'));first.nodes['render-policy'].value='GROK_SCENE_V1';first.nodes['render-policy'].handlers.change();await submit(first);
 const saved=first.calls.find(c=>c.options).options,secondPicker=pickerFixture(),next=setup(true,{}, {storage,currentUser,artworkPicker:secondPicker.component});await next.choose();secondPicker.choose(reuseSelection('old-print'));
 assert.equal(next.nodes['render-policy'].value,'GROK_SCENE_V1');await submit(next);assert.equal(next.calls.find(c=>c.options).options.body,saved.body);assert.equal(next.calls.find(c=>c.options).options.headers['Idempotency-Key'],saved.headers['Idempotency-Key']);
 secondPicker.choose(reuseSelection('different-print'));assert.equal(next.nodes['render-policy'].value,'GROK_DRAPE_REUSE_V1');
});
