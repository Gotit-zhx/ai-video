const test=require('node:test'), assert=require('node:assert/strict');
const fs=require('node:fs'), vm=require('node:vm'), path=require('node:path');
function setup(failFirst=false){
  const calls=[],nodes={};let writes=0;
  function element(tag='div'){
    return {tag,children:[],handlers:{},value:'',textContent:'',disabled:false,checked:false,
      append(e){this.children.push(e);if(this.id==='commerce-batch-template'&&!this.value)this.value=e.value;},
      replaceChildren(){this.children=[];this.value='';},
      addEventListener(name,fn){this.handlers[name]=fn;},
      querySelectorAll(query){
        const all=[];const walk=e=>e.children.forEach(c=>{all.push(c);walk(c);});walk(this);
        if(query.includes(':checked'))return all.filter(e=>e.name==='shop'&&e.checked);
        if(query.includes('checkbox'))return all.filter(e=>e.type==='checkbox');
        return all.filter(e=>['input','select','button'].includes(e.tag));
      }};
  }
  ['form','status','template','shops','products','more','search','query','selected-count','refresh','detail','list'].forEach(id=>{
    nodes[id]=element();nodes[id].id='commerce-batch-'+id;
  });
  nodes.form.children=[nodes.shops,nodes.products,nodes.template];
  nodes.form.elements={namedItem:()=>({value:'每日批次'})};
  const template={snapshotId:'snapshot',name:'PH模板',version:1,configuration:{platform:'SHOPEE',market:'PH',shopCount:2}};
  const api=async(url,options)=>{
    calls.push({url,options});
    if(options){writes++;if(failFirst&&writes===1)throw new Error('network failed');return {data:{id:'batch',name:'每日批次'}};}
    if(url==='/api/v1/commerce-templates')return {data:[template]};
    if(url==='/api/v1/shops')return {data:[{id:'s1',name:'店1',platform:'SHOPEE',region:'PH',status:'ACTIVE'},{id:'s2',name:'店2',platform:'SHOPEE',region:'PH',status:'ACTIVE'},{id:'foreign-market',platform:'SHOPEE',region:'SG',status:'ACTIVE'}]};
    if(url==='/api/v1/commerce-batches')return {data:[]};
    if(url.endsWith('/review'))return {data:null};
    if(url.endsWith('/quota-reservations'))return {data:[{shopId:'s1',listingDate:'2026-09-08',timeZone:'Asia/Manila'}]};
    if(url.includes('product-captures'))return {data:{page:0,totalPages:1,content:[1,2,3,4].map(i=>({id:'p'+i,title:'商品'+i,sourcePlatform:'SHOPEE',status:'CAPTURED'}))}};
    return {data:{id:'batch',name:'每日批次',status:'DRAFT',templateSnapshotId:'snapshot',items:[{id:'item-1',captureId:'p1',captureVersion:3,shopName:'店1',plannedDay:1,status:'DRAFT'}]}};
  };
  let uuid=0;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../commerce-batches.js'),'utf8'),{
    document:{getElementById:id=>nodes[id.replace('commerce-batch-','')],createElement:element},api,
    crypto:{randomUUID:()=>`request-${++uuid}`},encodeURIComponent,console});
  async function choose(){
    await nodes.refresh.handlers.click();
    nodes.template.value='snapshot';nodes.template.handlers.change();
    nodes.shops.children.forEach(label=>label.children.filter(e=>e.tag==='input').forEach(e=>e.checked=true));
    nodes.products.children.forEach(label=>label.children.filter(e=>e.tag==='input').forEach(e=>{e.checked=true;e.handlers.change();}));
  }
  return {nodes,calls,choose};
}
test('filters shops by platform/market and distributes each product only once',async()=>{
  const s=setup();await s.choose();
  assert.equal(s.nodes.shops.children.length,2);
  await s.nodes.form.handlers.submit({preventDefault(){}});
  const write=s.calls.find(c=>c.options);
  assert.equal(write.url,'/api/v1/commerce-batches');
  const body=JSON.parse(write.options.body);
  assert.deepEqual(body.targets.map(t=>t.shopId),['s1','s2','s1','s2']);
  assert.equal(new Set(body.targets.map(t=>t.captureId)).size,4);
  assert.match(s.nodes.status.textContent,/未执行发布/);
  assert.ok(!s.calls.some(c=>c.url.includes('publish-workflows')));
});
test('batch details read and display persisted reservation dates',async()=>{
  const s=setup();await s.choose();await s.nodes.form.handlers.submit({preventDefault(){}});
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
  const s=setup();await s.choose();await s.nodes.form.handlers.submit({preventDefault(){}});
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
