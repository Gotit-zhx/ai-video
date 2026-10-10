const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const {storageKey,list,summary,render}=require('../product-bulk-history.js');

function memoryStorage(entries=[]) {
  const data=new Map(entries.map(([key,value])=>[key,typeof value==='string'?value:JSON.stringify(value)]));
  return {get length(){return data.size;},key:index=>[...data.keys()][index]??null,
    getItem:key=>data.get(key)??null,setItem(){throw Error('History must never write storage');},removeItem(){throw Error('History must never remove storage');}};
}
const record=(overrides={})=>({organizationId:'org-a',batchId:'batch-a',createdAt:'2026-09-10T03:00:00Z',updatedAt:'2026-09-10T03:01:00Z',items:[{captureId:'product-a',title:'测试商品',status:'WAITING'}],...overrides});

test('new batches have separate keys even when the same organization selects the same products',()=>{
  assert.notEqual(storageKey('org-a','batch-a'),storageKey('org-a','batch-b'));
  assert.notEqual(storageKey('org-a','batch-a'),storageKey('org-b','batch-a'));
  assert.equal(storageKey('公司:一','batch:/二'),'heyi:bulk-upload:batch:%E5%85%AC%E5%8F%B8%3A%E4%B8%80:batch%3A%2F%E4%BA%8C');
  for(const values of [['','batch'],['org',''],[undefined,'batch'],['org',null]])assert.throws(()=>storageKey(...values));
});

test('lists independent batches in recency order without suppressing active or failed batches',()=>{
  const storage=memoryStorage([
    [storageKey('org-a','old'),record({batchId:'old',items:[{status:'RUNNING'}]})],
    [storageKey('org-a','new'),record({batchId:'new',updatedAt:'2026-09-10T04:00:00Z',items:[{status:'FAILED'}]})],
    [storageKey('org-a','done'),record({batchId:'done',updatedAt:'2026-09-10T03:30:00Z',items:[{status:'SUCCEEDED'}]})]
  ]);
  const batches=list(storage,'org-a');
  assert.deepEqual(batches.map(batch=>batch.batchId),['new','done','old']);
  assert.equal(batches[0].key,storageKey('org-a','new'));
  assert.equal(batches[0].storageKey,batches[0].key);
  assert.equal(batches[0].legacy,false);
});

test('reads original selection-based keys without requiring a migration or timestamps',()=>{
  const key='heyi:bulk-upload:org-a:a,b';
  const saved={organizationId:'org-a',items:[{captureId:'a',status:'SUCCEEDED'},{captureId:'b',status:'CREATE_FAILED',payload:{idempotencyKey:'original-key'}}]};
  const batches=list(memoryStorage([[key,saved]]),'org-a');
  assert.equal(batches.length,1);
  assert.equal(batches[0].legacy,true);
  assert.equal(batches[0].key,key);
  assert.equal(batches[0].batchId,'legacy:a,b');
  assert.deepEqual(batches[0].items,saved.items);
  assert.match(render(batches),/历史批次/);
});

test('organization identity is checked both in the key and inside each stored record',()=>{
  const storage=memoryStorage([
    [storageKey('org-a','valid'),record({batchId:'valid'})],
    [storageKey('org-b','foreign'),record({batchId:'foreign',organizationId:'org-b'})],
    [storageKey('org-a','forged'),record({batchId:'forged',organizationId:'org-b'})],
    ['heyi:bulk-upload:org-a:a',record({organizationId:'org-b'})],
    ['heyi:bulk-upload:org-ab:a',record()],
    ['unrelated',record()]
  ]);
  assert.deepEqual(list(storage,'org-a').map(batch=>batch.batchId),['valid']);
  assert.deepEqual(list(storage,''),[]);
});

test('encoded identifiers remain separate and key/record batch mismatch is ignored',()=>{
  const organizationId='org:中文';
  const storage=memoryStorage([
    [storageKey(organizationId,'batch:a'),record({organizationId,batchId:'batch:a'})],
    [storageKey(organizationId,'mismatched'),record({organizationId,batchId:'different'})]
  ]);
  assert.deepEqual(list(storage,organizationId).map(batch=>batch.batchId),['batch:a']);
});

test('accepts the application record format that stores the full storage key as batchId',()=>{
  const key=storageKey('org-a','queued-batch');
  const saved=record({batchId:key,items:[{captureId:'a',status:'PREPARING'}]});
  const batches=list(memoryStorage([[key,saved]]),'org-a');
  assert.equal(batches.length,1);
  assert.equal(batches[0].key,key);
  assert.equal(summary(batches[0]).running,1);
});

test('a malformed or unreadable entry does not hide valid records',()=>{
  const storage=memoryStorage([
    [storageKey('org-a','damaged'),'{not json'],
    [storageKey('org-a','empty'),record({batchId:'empty',items:[]})],
    [storageKey('org-a','invalid-items'),record({batchId:'invalid-items',items:[null]})],
    [storageKey('org-a','good'),record({batchId:'good'})]
  ]);
  assert.deepEqual(list(storage,'org-a').map(batch=>batch.batchId),['good']);
  assert.deepEqual(list({get length(){throw Error('denied');}},'org-a'),[]);
  assert.deepEqual(list(null,'org-a'),[]);
});

test('the new record takes precedence when a legacy record was already migrated',()=>{
  const current=record({updatedAt:'2026-09-10T03:00:00Z'});
  const legacy=record({updatedAt:'2026-09-10T05:00:00Z',items:[{status:'WAITING'}]});
  const batches=list(memoryStorage([['heyi:bulk-upload:org-a:a',legacy],[storageKey('org-a','batch-a'),current]]),'org-a');
  assert.equal(batches.length,1);
  assert.equal(batches[0].legacy,false);
});

test('invalid update time falls back to creation time and ties have deterministic ordering',()=>{
  const storage=memoryStorage([
    [storageKey('org-a','b'),record({batchId:'b',updatedAt:'invalid'})],
    [storageKey('org-a','a'),record({batchId:'a',updatedAt:'invalid'})],
    [storageKey('org-a','latest'),record({batchId:'latest',updatedAt:Date.parse('2026-09-10T05:00:00Z')})]
  ]);
  assert.deepEqual(list(storage,'org-a').map(batch=>batch.batchId),['latest','a','b']);
});

test('summaries partition every item into waiting, running, success or failure',()=>{
  const items=['WAITING','PREPARED','PREPARING','SUBMITTING','PENDING','RUNNING','SUCCEEDED','PREPARE_FAILED','CREATE_FAILED','FAILED','REVIEW_REQUIRED'].map(status=>({status}));
  assert.deepEqual(summary({items}),{total:11,waiting:2,running:4,succeeded:1,failed:4,status:'RUNNING'});
  assert.deepEqual(summary(null),{total:0,waiting:0,running:0,succeeded:0,failed:0,status:'EMPTY'});
});

test('accepted workflow state wins over stale local state and sync failures do not change upload success',()=>{
  assert.deepEqual(summary({items:[
    {status:'CREATE_FAILED',workflow:{id:'live',status:'RUNNING'}},
    {status:'RUNNING',workflow:{id:'done',status:'SUCCEEDED'},syncError:'detail unavailable'},
    {status:'SUCCEEDED',workflow:{id:'failed',status:'FAILED'}}
  ]}),{total:3,waiting:0,running:1,succeeded:1,failed:1,status:'RUNNING'});
  assert.equal(summary({items:[{status:'FUTURE_STATUS',workflow:{id:'unknown'}}]}).running,1);
});

test('completed and partially failed batches expose clear summary states',()=>{
  assert.equal(summary({items:[{status:'SUCCEEDED'}]}).status,'SUCCEEDED');
  assert.equal(summary({items:[{status:'SUCCEEDED'},{status:'FAILED'}]}).status,'FAILED');
  assert.equal(summary({items:[{status:'WAITING'},{status:'FAILED'}]}).status,'WAITING');
  assert.equal(summary({items:[{status:'CANCELLED'}]}).failed,1);
});

test('render exposes independent detail buttons, count labels and an empty state',()=>{
  const batches=list(memoryStorage([[storageKey('org-a','batch-a'),record({items:[{title:'甲',status:'SUCCEEDED'},{title:'乙',status:'FAILED'}]})]]),'org-a');
  const html=render(batches);
  assert.match(html,/data-product-bulk-history-key="heyi:bulk-upload:batch:org-a:batch-a"/);
  assert.match(html,/部分失败/);
  for(const label of ['共计','成功','失败','进行中','待准备','查看详情'])assert.ok(html.includes(label));
  assert.match(html,/role="listitem"/);
  assert.match(render([]),/暂无批量任务/);
});

test('stored product titles and batch keys are escaped before rendering',()=>{
  const html=render([{key:'" onclick="alert(1)',items:[{title:'<img src=x onerror=alert(1)>',status:'SUCCEEDED'}]}]);
  assert.ok(!html.includes('<img'));
  assert.ok(!html.includes('data-product-bulk-history-key="" onclick='));
  assert.match(html,/&lt;img/);
  assert.match(html,/&quot; onclick=&quot;/);
});

test('browser module has no document or global storage dependency',()=>{
  const source=fs.readFileSync(path.join(__dirname,'../product-bulk-history.js'),'utf8');
  const window={};
  vm.runInNewContext(source,{window});
  assert.equal(typeof window.ProductBulkHistory.list,'function');
  assert.equal(window.ProductBulkHistory.summary({items:[{status:'RUNNING'}]}).running,1);
});

test('history entry and dialog are accessible and load before application wiring',()=>{
  const html=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
  for(const id of ['open-product-bulk-history','open-product-bulk-history-from-detail','product-bulk-history-dialog','product-bulk-history-list','close-product-bulk-history'])assert.equal([...html.matchAll(new RegExp(`id="${id}"`,'g'))].length,1);
  assert.match(html,/id="open-product-bulk-history"[^>]*aria-controls="product-bulk-history-dialog"/);
  assert.match(html,/id="product-bulk-history-dialog"[^>]*aria-labelledby="product-bulk-history-title"/);
  assert.match(html,/product-bulk-upload\.js\?v=[^"\s]+/);
  assert.match(html,/app\.js\?v=[^"\s]+/);
  assert.ok(html.indexOf('/product-bulk-history.js?')<html.indexOf('/app.js?'));
});
