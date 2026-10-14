const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ProductBulkUpload = require('../product-bulk-upload.js');
const ProductBulkHistory = require('../product-bulk-history.js');

const source = fs.readFileSync(path.join(__dirname, '../app.js'), 'utf8');
const plain = value => JSON.parse(JSON.stringify(value));
function slice(start, end) {
  const from = source.indexOf(start), to = source.indexOf(end, from);
  assert.ok(from >= 0 && to > from, `Missing app.js test boundary: ${start}`);
  return source.slice(from, to);
}

function fixture() {
  return [
    {id:'a', title:'Philippines shirt', status:'CAPTURED', version:3, currency:'PHP',
      categoryName:'Women Clothes > T-shirts', normalizedData:{package_weight:{value:0.18, unit:'kg'}},
      skus:[{externalSkuId:'shirt-red', price:'120', currency:'PHP', attributes:{Color:'Red'}},
        {externalSkuId:'shirt-blue', price:'135', currency:'PHP', attributes:{Color:'Blue'}}]},
    {id:'b', title:'Thailand socks', status:'READY', version:8, currency:'THB',
      categoryName:'Women Clothes > Socks', normalizedData:{},
      skus:[{externalSkuId:'socks-white', price:'59', currency:'THB', attributes:{Color:'White'}},
        {externalSkuId:'socks-black', price:'65', currency:'THB', attributes:{Color:'Black'}}]}
  ];
}

function harness(captures = fixture()) {
  const nodes = new Map(), storage = new Map(), captureReads = [], patches = [], posts = [], optionReads = [], updates = [], workflowReads = [], timers = new Map();
  const state = {captures:new Map(captures.map(capture => [capture.id, plain(capture)])), onRead:null, onPost:null, onWorkflowRead:null, onOptions:null, onWrite:null, keyCount:0};
  const node = selector => {
    if (!nodes.has(selector)) {
      const element={value:'', textContent:'', dataset:{}, disabled:false, open:false, options:[],
        showModal(){this.open=true;}, close(){this.open=false;}, add(option){this.options.push(option);}};
      let html='';
      Object.defineProperty(element,'innerHTML',{get:()=>html,set(value){
        html=value;
        if(selector==='#product-bulk-shop') element.options=[...value.matchAll(/<option value="([^"]*)"/g)].map(match=>({value:match[1]}));
        if(selector==='#product-bulk-items') rows=[...value.matchAll(/data-bulk-item="([^"]*)"[^>]*>[\s\S]*?<strong[^>]*>(.*?)<\/strong>/g)]
          .map(match=>({dataset:{bulkItem:match[1]},querySelector:()=>({textContent:match[2]})}));
      }});
      nodes.set(selector,element);
    }
    return nodes.get(selector);
  };
  let rows = captures.map(capture => ({dataset:{bulkItem:capture.id}, querySelector:() => ({textContent:capture.title})}));
  const poisonDetail = {id:'unrelated-open-detail', categoryName:'Wrong category', currency:'USD', weightGrams:9999,
    normalizedData:{weightGrams:9999}, skus:[{externalSkuId:'wrong-editor-sku', price:'9999'}]};
  const options = {
    categories:{category_list:[
      {category_id:1, parent_category_id:0, display_category_name:'Women Clothes', has_children:true},
      {category_id:2, parent_category_id:1, display_category_name:'T-shirts', has_children:false},
      {category_id:3, parent_category_id:1, display_category_name:'Socks', has_children:false}
    ]},
    logistics:{logistics_channel_list:[
      {logistics_channel_id:100, logistics_channel_name:'Disabled courier', enabled:false},
      {logistics_channel_id:101, logistics_channel_name:'Enabled courier', enabled:true},
      {logistics_channel_id:102, logistics_channel_name:'Remembered courier', enabled:true}
    ]}
  };
  const c = {
    ProductBulkSizeChart:{isUploading:()=>false,key:()=>state.sizeChartImageKey || '',open:()=>{},render:()=>{}},
    ProductListingTemplates:{ensure:async()=>{},get:()=>({enabled:false}),fields:()=>({})},
    ProductBulkUpload:{...ProductBulkUpload,retryRead:(operation,options)=>ProductBulkUpload.retryRead(operation,{...options,sleep:async()=>{}})},
    ProductBulkHistory,
    AbortController, Date:class extends Date {static now(){return state.now ?? Date.now();}}, currentUser:{organizationId:'org'}, selectedProductCaptureDetail:plain(poisonDetail),
    selectedProductCaptureId:poisonDetail.id, productEditorDirty:false, productCaptureSaveInFlightId:null,
    marketplaceIntegrationStatus:{livePublishingEnabled:true}, productBulkOrganization:'org', productBulkStorageKey:'heyi:bulk-upload:org:a,b',
    productBulkBusy:false, productBulkItems:[], productBulkPollTimer:null,
    productBulkSelectedIds:new Set(captures.map(capture=>capture.id)), productBulkSelectedCaptures:new Map(), productCaptures:captures,
    loadMarketplaceShops:async()=>{}, productPriceLabel:capture=>capture.currency,
    Option:class {constructor(text,value){this.text=text;this.value=value;}},
    document:{
      querySelector(selector) {
        // Bulk preparation must never read values from the open single-product editor.
        assert.ok(!selector.startsWith('#publish-'), `Bulk upload read editor field ${selector}`);
        return node(selector);
      },
      querySelectorAll:selector => selector === '[data-bulk-item]' ? rows : []
    },
    localStorage:{get length(){return storage.size;},key:index=>[...storage.keys()][index]??null,
      getItem:key => storage.get(key) ?? null, setItem:(key,value) => {state.onWrite?.(key,value);storage.set(key,value);}},
    activeMarketplaceShops:platform => platform==='SHOPEE'?[{id:'shop', name:'Original Shopee shop',region:'MY',platform:'SHOPEE'}]:[], hasAnyPermission:() => true,
    newProductIdempotencyKey:() => ++state.keyCount===1?'bulk-key':`bulk-key-${state.keyCount}`, escapeHtml:value => String(value ?? ''), safeProductUrl:value => value || '',
    productCaptureSkus:capture => capture?.skus || [], productCaptureImages:capture => capture?.images || [],
    updateProductCaptureInMemory:capture => updates.push(plain(capture)),
    productPublishWorkflowPath:(id, workflowId='') => `/captures/${encodeURIComponent(id)}/publish-workflows${workflowId ? `/${workflowId}` : ''}`,
    productPublishWorkflowIsActive:workflow => ['PENDING','RUNNING'].includes(workflow?.status),
    productPublishWorkflowPlatform:workflow => workflow.destinationPlatform,
    normalizedMarketplacePlatform:platform => platform,
    ApiRequestError:class extends Error { constructor(message,status,payload) {super(message); this.status=status; this.payload=payload;} },
    clearTimeout:id => timers.delete(id), setTimeout:(callback,delay) => {const id=Symbol();timers.set(id,{callback,delay});return id;},
    async productCaptureApi(requestPath, request = {}) {
      const id = decodeURIComponent(requestPath.slice(1));
      if (request.method === 'PATCH') {
        const body = JSON.parse(request.body);
        patches.push({id, body});
        const updated = {...state.captures.get(id), ...body, version:body.version + 1};
        state.captures.set(id, updated);
        return {data:{productCapture:plain(updated)}};
      }
      captureReads.push(id);
      if (state.onRead) await state.onRead(id, c);
      return {data:{productCapture:plain(state.captures.get(id))}};
    },
    async api(requestPath, request = {}) {
      const optionsMatch=requestPath.match(/^\/api\/v1\/shops\/([^/]+)\/publish-options$/);
      if (optionsMatch) {
        optionReads.push(requestPath);
        if (state.onOptions) { const override = await state.onOptions(decodeURIComponent(optionsMatch[1])); if (override) return override; }
        return {data:plain(options)};
      }
      const workflowMatch = requestPath.match(/^\/captures\/([^/]+)\/publish-workflows\/([^/?]+)$/);
      if (workflowMatch) {
        const id = decodeURIComponent(workflowMatch[1]), workflowId = decodeURIComponent(workflowMatch[2]);
        workflowReads.push({id,workflowId});
        if (state.onWorkflowRead) return state.onWorkflowRead(id,workflowId,c,request);
        return {data:{id:workflowId,captureId:id,status:'SUCCEEDED',
          shopId:c.productBulkItems.find(item=>item.workflow?.id===workflowId)?.shopId || 'shop',destinationPlatform:'SHOPEE'}};
      }
      const match = requestPath.match(/^\/captures\/([^/]+)\/publish-workflows$/);
      assert.ok(match && request.method === 'POST', `Unexpected API request: ${requestPath}`);
      const call = {id:decodeURIComponent(match[1]), body:JSON.parse(request.body), key:request.headers['Idempotency-Key']};
      posts.push(call);
      const storedItems=[...storage.values()].flatMap(value=>JSON.parse(value)?.items||[]);
      const saved=storedItems.filter(item=>item.captureId===call.id&&item.idempotencyKey===call.key);
      assert.equal(saved.length,1,'The request must belong to exactly one persisted batch');
      assert.deepEqual(saved[0].payload, call.body,
        'Exact payload must be persisted before the workflow POST');
      if (state.onPost) await state.onPost(call, c);
      return {data:{id:`workflow-${call.id}${state.uniqueStoreWorkflows ? '-'+call.body.shopId : ''}`, captureId:call.id, status:'PENDING', shopId:call.body.shopId, destinationPlatform:call.body.destinationPlatform}};
    }
  };
  node('#product-bulk-shop').value = 'shop';
  vm.createContext(c);
  const fragments = [
    slice('function productCaptureCanUpload(', 'function renderProductCaptureList('),
    slice('function productCapturePayload(', 'function productCaptureListPayload('),
    slice('function nullableProductValue(', 'function productSkuAttributesComplete('),
    slice('const PRODUCT_SKU_SERVER_FIELDS', 'function productSkuDrafts('),
    slice('function productSkuPriceNormalizationNeeded(', 'async function saveProductCapture('),
    slice('let productPublishPrepareVersion', 'function syncOfficialPublishFields('),
    slice('function productPublishWorkflowMatchesTarget(', 'async function openProductPublishDialog('),
    slice('function saveProductBulkItems(', "document.querySelector('#open-product-bulk-upload')")
  ];
  vm.runInContext(fragments.join('\n'), c);
  return {c, state, node, storage, captureReads, patches, posts, optionReads, updates, poisonDetail,workflowReads,timers,
    submit:() => c.submitProductBulkUpload({preventDefault(){}}), clearRuns:()=>vm.runInContext('productBulkRuns.clear()',c),
    runCount:()=>vm.runInContext('productBulkRuns.size',c)};
}

test('multi-shop UI submits each product to both stores with separate requests and shared batch chart',async()=>{
  const h=harness(),select=h.node('#product-bulk-shop');
  h.c.activeMarketplaceShops=platform=>platform==='SHOPEE'
    ? [{id:'shop',name:'One',platform:'SHOPEE'},{id:'shop-two',name:'Two',platform:'SHOPEE'}] : [];
  select.multiple=true;
  Object.defineProperty(select,'selectedOptions',{get:()=>select.options.filter(option=>option.selected)});
  h.state.sizeChartImageKey='batch-chart';
  h.state.uniqueStoreWorkflows=true;
  await h.c.openProductBulkUpload();
  assert.equal(h.c.productBulkShopIds().length,0);
  h.c.setProductBulkShopIds(['shop','shop-two']);h.c.changeProductBulkShop();
  assert.match(h.node('#product-bulk-shop-summary').textContent,/已选 2 家/);
  await h.submit();
  assert.equal(h.posts.length,4);
  assert.equal(new Set(h.posts.map(call=>call.key)).size,4);
  for(const id of ['a','b']) {
    assert.deepEqual(h.posts.filter(call=>call.id===id).map(call=>call.body.shopId).sort(),['shop','shop-two']);
  }
  assert.ok(h.posts.every(call=>call.body.sizeChartImageKey==='batch-chart'));
  assert.equal(h.patches.length,2,'Each capture is normalized once, not once per store');
  assert.ok(h.workflowReads.length>=2,'Second store waits for first store workflow completion');
  h.c.stopProductBulkPolling();
});
test('multi-shop retries only the rejected store with the frozen payload and key, then restores both choices',async()=>{
  const h=harness([fixture()[0]]),select=h.node('#product-bulk-shop');
  h.c.activeMarketplaceShops=platform=>platform==='SHOPEE'
    ? [{id:'shop',name:'One',platform:'SHOPEE'},{id:'shop-two',name:'Two',platform:'SHOPEE'}] : [];
  select.multiple=true;
  h.state.uniqueStoreWorkflows=true;
  Object.defineProperty(select,'selectedOptions',{get:()=>select.options.filter(option=>option.selected)});
  await h.c.openProductBulkUpload();
  h.c.setProductBulkShopIds(['shop','shop-two']);
  h.state.onPost=async call=>{if(call.body.shopId==='shop-two')throw new h.c.ApiRequestError('store temporarily rejected the request',400);};
  await h.submit();
  const rejected=h.posts.find(call=>call.body.shopId==='shop-two');
  assert.equal(h.c.productBulkItems[1].status,'CREATE_FAILED');
  h.state.onPost=null;
  const before=h.posts.length;await h.submit();
  assert.equal(h.posts.length,before+1);
  assert.deepEqual(h.posts.at(-1),rejected);
  const key=h.c.productBulkStorageKey;h.c.productBulkItems=[];
  await h.c.openProductBulkUpload(key);
  assert.deepEqual(plain(h.c.productBulkShopIds()).sort(),['shop','shop-two']);
  assert.equal(h.node('#product-bulk-summary').textContent,'已选择 1 件商品');
  h.c.stopProductBulkPolling();
});

test('optional batch chart is frozen into each request; omission keeps original source behavior', async () => {
  const custom=harness();custom.state.sizeChartImageKey='tenant/batch-chart';
  await custom.submit();
  assert.equal(custom.posts.length,2);
  for(const post of custom.posts) assert.equal(post.body.sizeChartImageKey,'tenant/batch-chart');
  const original=harness();await original.submit();
  for(const post of original.posts) assert.equal(Object.hasOwn(post.body,'sizeChartImageKey'),false);
});

test('batch submission waits until the optional chart upload finishes', async () => {
  const h=harness();h.c.ProductBulkSizeChart.isUploading=()=>true;
  await h.submit();assert.equal(h.posts.length,0);assert.equal(h.optionReads.length,0);
});

test('shop-only submission prepares each product independently and preserves its source SKU prices', async () => {
  const h = harness();
  h.storage.set(JSON.stringify(['publish-settings-v1','org','SHOPEE','shop','shop']),
    JSON.stringify({'package-length':'31', logistic:'102'}));
  // Preferences of another company/shop and a mismatched saved category cannot leak into this request.
  h.storage.set(JSON.stringify(['publish-settings-v1','other-org','SHOPEE','shop','shop']), JSON.stringify({'package-length':'999'}));
  h.storage.set(JSON.stringify(['publish-settings-v1','org','SHOPEE','other-shop','shop']), JSON.stringify({'package-length':'888'}));
  h.storage.set(JSON.stringify(['publish-settings-v1','org','SHOPEE','shop','Women Clothes > T-shirts']), JSON.stringify({category:'3'}));
  await h.submit();
  assert.deepEqual(h.captureReads, ['a','b']);
  assert.equal(h.optionReads.length, 1);
  assert.deepEqual(h.posts.map(call => [call.id, call.key, call.body.categoryId, call.body.weightGrams]),
    [['a','bulk-key-0','2','180'], ['b','bulk-key-1','3','250']]);
  for (const {body, key} of h.posts) {
    assert.equal(body.confirmed, true);
    assert.equal(body.shopId, 'shop');
    assert.equal(body.destinationPlatform, 'SHOPEE');
    assert.equal(body.idempotencyKey, key);
    assert.equal(body.publishStock, 999);
    assert.equal(body.autoConvertPrice, true);
    assert.equal(body.publishPrice, undefined);
    assert.equal(body.publishCurrency, undefined);
    assert.equal(body.packageLengthCm, '31');
    assert.deepEqual(body.skuStocks, {});
    assert.deepEqual(body.logisticInfo, [{logistic_id:102, enabled:true}]);
    assert.deepEqual(body.shopeePayload, {brand:{brand_id:0, original_brand_name:'NoBrand'}});
  }
  assert.deepEqual(h.patches.map(({id,body}) => [id,body.version,body.priceMin,body.priceMax,
    body.skus.map(sku => [sku.externalSkuId,sku.price,sku.originalPrice,sku.currency])]), [
    ['a',3,'120','135',[['shirt-red','120','120','PHP'],['shirt-blue','135','135','PHP']]],
    ['b',8,'59','65',[['socks-white','59','59','THB'],['socks-black','65','65','THB']]]
  ]);
  assert.deepEqual(plain(h.c.selectedProductCaptureDetail), h.poisonDetail);
  assert.deepEqual(h.updates.map(capture => capture.id), ['a','b']);
  assert.equal(h.node('#product-bulk-error').textContent, '');
  assert.equal(h.node('#submit-product-bulk').textContent, '全部商品已提交');
  h.c.productBulkItems[0].payload.skuStocks.unique = 10;
  assert.deepEqual(plain(h.c.productBulkItems[1].payload.skuStocks), {});
});

test('one unmatched category does not block other products and a retry skips the accepted product', async () => {
  const captures = fixture();
  captures[0].categoryName = 'Unmatched category';
  const h = harness(captures);
  await h.submit();
  assert.deepEqual(h.posts.map(call => call.id), ['b']);
  assert.equal(h.c.productBulkItems[0].status, 'PREPARE_FAILED');
  assert.match(h.c.productBulkItems[0].error, /无法匹配/);
  assert.equal(h.c.productBulkItems[0].payload, undefined);
  h.state.captures.get('a').categoryName = 'Women Clothes > T-shirts';
  await h.submit();
  assert.deepEqual(h.posts.map(call => [call.id,call.key]), [['b','bulk-key-1'], ['a','bulk-key-0']]);
  assert.deepEqual(h.captureReads, ['a','b','a']);
  assert.equal(h.optionReads.length, 2, 'Each attempt fetches shop options once');
  assert.equal(h.node('#submit-product-bulk').textContent, '全部商品已提交');
});

test('retry after a rejected workflow request retains the prepared body and key', async () => {
  const h = harness();
  let rejectFirst = true;
  h.state.onPost = async call => {
    if (call.id === 'a' && rejectFirst) throw Object.assign(new Error('Temporary submission rejection'), {status:409});
  };
  await h.submit();
  assert.equal(h.c.productBulkItems[0].status, 'CREATE_FAILED');
  assert.equal(h.c.productBulkItems[1].workflow.id, 'workflow-b');
  const first = plain(h.posts[0]);
  h.state.captures.get('a').normalizedData.weightGrams = 1000;
  rejectFirst = false;
  await h.submit();
  assert.deepEqual(h.posts.map(call => call.id), ['a','b','a']);
  assert.deepEqual(h.posts[2], first);
  assert.deepEqual(h.captureReads, ['a','b'], 'Prepared items must not be rebuilt on retry');
  assert.equal(h.optionReads.length, 1);
});

test('organization change while fetching a capture stops before configuration, patch, or upload', async () => {
  const h = harness();
  const replacementItems = [{captureId:'other-product', status:'PREPARING'}];
  h.state.onRead = async (_id,c) => {
    c.currentUser = {organizationId:'other-org'};
    c.productBulkItems = replacementItems;
    c.productBulkBusy = true;
  };
  await h.submit();
  assert.deepEqual(h.captureReads, ['a']);
  assert.equal(h.optionReads.length, 0);
  assert.equal(h.patches.length, 0);
  assert.equal(h.posts.length, 0);
  assert.equal(h.updates.length, 0);
  assert.equal(h.c.productBulkBusy, true, 'The old request must not clear the new company task busy state');
  assert.equal(h.c.productBulkItems, replacementItems);
  assert.equal(JSON.parse(h.storage.get(h.c.productBulkStorageKey)).organizationId, 'org');
});

test('an expired session settles already-started preparations without submitting any product', async () => {
  const h = harness();
  h.state.onRead = async () => { throw Object.assign(new Error('Session expired'), {status:401}); };
  await h.submit();
  assert.deepEqual(h.captureReads, ['a','b']);
  assert.equal(h.c.productBulkItems[0].status, 'PREPARE_FAILED');
  assert.equal(h.c.productBulkItems[1].status, 'PREPARE_FAILED');
  assert.equal(h.posts.length, 0);
});

test('an unsaved selected product is isolated while other selected products can upload', async () => {
  const h = harness();
  h.c.selectedProductCaptureId = 'a';
  h.c.productEditorDirty = true;
  await h.submit();
  assert.deepEqual(h.captureReads, ['b']);
  assert.deepEqual(h.posts.map(call => call.id), ['b']);
  assert.equal(h.c.productBulkItems[0].status, 'PREPARE_FAILED');
  assert.match(h.c.productBulkItems[0].error, /尚未保存/);
  assert.equal(h.c.productEditorDirty, true);
  assert.deepEqual(plain(h.c.selectedProductCaptureDetail), h.poisonDetail);
});

const deferred = () => {let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
const flush = () => new Promise(setImmediate);
function setWorkflows(h, status='RUNNING') {
  h.node('#product-bulk-dialog').open = true;
  h.c.productBulkItems = [...h.state.captures.values()].map(capture => ({captureId:capture.id,title:capture.title,shopId:'shop',
    workflow:{id:`workflow-${capture.id}`,captureId:capture.id,shopId:'shop',destinationPlatform:'SHOPEE',status}}));
}

test('the first accepted workflow is polled while another preparation is still pending', async () => {
  const h=harness(), pending=deferred();
  h.node('#product-bulk-dialog').open=true;
  h.state.onRead=async id=>{if(id==='b')await pending.promise;};
  const submission=h.submit();
  try {
    await flush();
    assert.equal(h.c.productBulkBusy,true);
    assert.deepEqual(h.posts.map(p=>p.id),['a']);
    assert.deepEqual(h.workflowReads.map(p=>p.id),['a']);
    assert.equal(h.c.productBulkItems[0].workflow.status,'SUCCEEDED');
    assert.equal(h.c.productBulkItems[1].status,'PREPARING');
    assert.match(h.node('#product-bulk-progress').innerHTML,/上传成功/);
  } finally {
    pending.resolve();await submission;h.c.stopProductBulkPolling();await flush();
  }
});

test('terminal detail synchronization never blocks another workflow status or immediate rendering', async () => {
  const h=harness(),pending=deferred();setWorkflows(h);
  h.state.onRead=async id=>{if(id==='a')await pending.promise;};
  try {
    await h.c.pollProductBulkProgress();await flush();
    assert.deepEqual(h.workflowReads.map(r=>r.id),['a','b']);
    assert.deepEqual(h.c.productBulkItems.map(i=>i.workflow.status),['SUCCEEDED','SUCCEEDED']);
    assert.equal((h.node('#product-bulk-progress').innerHTML.match(/上传成功/g)||[]).length,2);
    assert.deepEqual(h.updates.map(c=>c.id),['b']);
  } finally {
    h.c.stopProductBulkPolling();pending.resolve();await flush();
  }
});

test('saved terminal workflows retry failed detail synchronization without replaying a workflow', async () => {
  const h=harness();setWorkflows(h,'SUCCEEDED');let failed=false;
  h.state.onRead=async id=>{if(id==='a'&&!failed){failed=true;throw new h.c.ApiRequestError('temporary detail failure',0);}};
  try {
    await h.c.pollProductBulkProgress();await flush();
    assert.match(h.c.productBulkItems[0].syncError,/正在自动重试/);
    assert.deepEqual(h.updates.map(c=>c.id),['b']);
    h.state.now=Date.now()+4000;
    await h.c.pollProductBulkProgress();await flush();
    assert.deepEqual(h.updates.map(c=>c.id),['b','a']);
    assert.equal(h.c.productBulkItems[0].syncError,'');
    assert.equal(h.workflowReads.length,0);
    assert.equal(h.posts.length,0);
  } finally {h.c.stopProductBulkPolling();}
});

test('poll reentry is coalesced and a canceled generation cannot overwrite a newer terminal result', async () => {
  const h=harness([fixture()[0]]),old=deferred();setWorkflows(h);
  let calls=0,oldSignal;
  h.state.onWorkflowRead=async(id,workflowId,_c,request)=>{
    calls++;
    if(calls===1){oldSignal=request.signal;return old.promise;}
    return {data:{id:workflowId,captureId:id,status:'SUCCEEDED'}};
  };
  const oldPoll=h.c.pollProductBulkProgress();
  await h.c.pollProductBulkProgress();await flush();
  assert.equal(calls,1,'Reentry must not issue a second GET for the same generation');
  h.c.stopProductBulkPolling();
  assert.equal(oldSignal.aborted,true);
  await h.c.pollProductBulkProgress();await flush();
  old.resolve({data:{id:'workflow-a',captureId:'a',status:'RUNNING'}});
  await oldPoll;await flush();
  assert.equal(h.c.productBulkItems[0].workflow.status,'SUCCEEDED');
  assert.match(h.node('#product-bulk-progress').innerHTML,/上传成功/);
  h.c.stopProductBulkPolling();
});

test('batch deadline includes the body read and an aborted late 401 cannot expire the current session', async () => {
  const h=harness(),body=deferred();let fetchSignal,expired=0;
  h.c.fetch=async(_path,options)=>{fetchSignal=options.signal;return {status:401,ok:false,json:()=>body.promise};};
  h.c.handleSessionExpired=()=>expired++;
  vm.runInContext(slice('async function api(', 'const GENERAL_VIDEO_API_ROOT'),h.c);
  await assert.rejects(h.c.productBulkApi('/slow-body',{}, {timeoutMs:20,retry:false}),error=>error.name==='TimeoutError'&&error.status===0);
  assert.equal(fetchSignal.aborted,true);
  body.resolve({message:'expired response from canceled request'});await flush();
  assert.equal(expired,0);
  assert.equal(h.c.currentUser.organizationId,'org');
});

test('an unknown bulk create replays only the exact saved body/key and never recovers by shop alone', async () => {
  const h=harness(),calls=[];
  const payload={shopId:'shop',destinationPlatform:'SHOPEE',idempotencyKey:'fixed-key',confirmed:true};
  const requestApi=async(path,options)=>{calls.push({path,options});throw new h.c.ApiRequestError('timeout',0);};
  const retries=[],delays=[];
  await assert.rejects(h.c.createProductPublishWorkflow('a',payload,'fixed-key',{requestApi,allowTargetRecovery:false,maxAttempts:3,
    sleep:async ms=>delays.push(ms),onRetry:retry=>retries.push(retry)}),/提交结果暂时无法确认/);
  assert.equal(calls.length,3);
  assert.deepEqual(calls.map(c=>c.options.method),['POST','POST','POST']);
  assert.equal(calls[0].options.body,calls[1].options.body);
  assert.deepEqual(calls.map(c=>c.options.headers['Idempotency-Key']),['fixed-key','fixed-key','fixed-key']);
  assert.deepEqual(delays,[2000,5000]);
  assert.deepEqual(retries.map(retry=>retry.attempt),[2,3]);
});

test('shared options failure is requested once per submission and a manual retry fetches it again', async () => {
  const base=fixture()[0],h=harness(Array.from({length:8},(_,i)=>({...base,id:`item-${i}`})));
  h.state.onOptions=async()=>{throw new h.c.ApiRequestError('shop options forbidden',403);};
  await h.submit();
  assert.equal(h.optionReads.length,1);
  assert.equal(h.posts.length,0);
  assert.ok(h.c.productBulkItems.every(item=>item.status==='PREPARE_FAILED'));
  h.state.onOptions=null;
  await h.submit();
  assert.equal(h.optionReads.length,2);
  assert.equal(h.posts.length,8);
});

test('batch request limits allow 180 seconds for shop options, 90 for submission, and 30 for progress', async () => {
  const h=harness(),limits=[];
  h.c.ProductBulkUpload={...ProductBulkUpload,withDeadline:(operation,options)=>{
    limits.push(options.timeoutMs);return ProductBulkUpload.withDeadline(operation,options);
  }};
  await h.submit();
  assert.equal(limits.filter(ms=>ms===180000).length,1);
  assert.ok(limits.filter(ms=>ms===90000).length>=6);
  h.node('#product-bulk-dialog').open=true;
  await h.c.pollProductBulkProgress();await flush();
  assert.equal(limits.filter(ms=>ms===30000).length,4);
  h.c.stopProductBulkPolling();
});

test('a shared transient options failure gets five attempts for the whole batch, then a manual retry starts a new cycle', async () => {
  const base=fixture()[0],h=harness(Array.from({length:8},(_,i)=>({...base,id:`item-${i}`})));
  h.state.onOptions=async()=>{throw new h.c.ApiRequestError('timeout',0);};
  await h.submit();
  assert.equal(h.optionReads.length,5,'One shared promise owns the complete retry budget');
  assert.equal(h.posts.length,0);
  assert.ok(h.c.productBulkItems.every(item=>item.status==='PREPARE_FAILED'));
  assert.equal(h.c.productBulkItems[0].retryCount,4);
  assert.match(h.node('#product-bulk-progress').innerHTML,/第 5\/5 次尝试/);
  h.state.onOptions=null;
  await h.submit();
  assert.equal(h.optionReads.length,6);
  assert.equal(h.posts.length,8);
  assert.equal(h.c.productBulkItems[0].retryCount,4,'Retry counts survive successful recovery');
});

test('progress reads stop after three failed attempts and reconnecting never creates a new workflow', async () => {
  const h=harness([fixture()[0]]);setWorkflows(h);h.state.now=Date.now();
  h.state.onWorkflowRead=async()=>{throw new h.c.ApiRequestError('timeout',0);};
  for(let attempt=1;attempt<=3;attempt++) {
    await h.c.pollProductBulkProgress();await flush();
    h.state.now+=6000;
  }
  assert.equal(h.workflowReads.length,3);
  assert.equal(h.c.productBulkItems[0].progressPaused,true);
  assert.equal(h.node('#submit-product-bulk').textContent,'重试进度同步');
  await h.c.pollProductBulkProgress();
  assert.equal(h.workflowReads.length,3,'Paused reads must not continue indefinitely');
  h.state.onWorkflowRead=null;
  await h.submit();await flush();
  assert.equal(h.c.productBulkItems[0].workflow.status,'SUCCEEDED');
  assert.equal(h.c.productBulkItems[0].retryCount,2);
  assert.equal(h.posts.length,0);
  h.c.stopProductBulkPolling();
});

test('terminal detail synchronization has its own three-attempt budget and can be resumed without publication', async () => {
  const h=harness([fixture()[0]]);setWorkflows(h,'SUCCEEDED');h.state.now=Date.now();
  h.state.onRead=async()=>{throw new h.c.ApiRequestError('timeout',0);};
  for(let attempt=1;attempt<=3;attempt++) {
    await h.c.pollProductBulkProgress();await flush();h.state.now+=6000;
  }
  assert.equal(h.captureReads.length,3);
  assert.equal(h.c.productBulkItems[0].syncPaused,true);
  await h.c.pollProductBulkProgress();await flush();
  assert.equal(h.captureReads.length,3);
  h.state.onRead=null;
  await h.submit();await flush();
  assert.equal(h.updates.length,1);
  assert.equal(h.c.productBulkItems[0].syncPaused,false);
  assert.equal(h.posts.length,0);
  assert.equal(h.workflowReads.length,0);
  h.c.stopProductBulkPolling();
});

test('Shopee captures bulk upload to TikTok using destination shop and official warehouse',async()=>{
 const h=harness(fixture().map(c=>({...c,sourcePlatform:'SHOPEE'})));
 h.c.activeMarketplaceShops=platform=>platform==='TIKTOK_SHOP'?[{id:'shop',region:'MY',platform:'TIKTOK_SHOP'}]:[];
 h.state.onOptions=async()=>({data:{listingMethod:'LOCAL',canCreateDraft:true,
   categories:{categories:[{id:'9',name:'T-shirts',is_leaf:true},{id:'10',name:'Socks',is_leaf:true}]},
   warehouses:{warehouses:[{id:'wh',type:'SALES_WAREHOUSE',is_default:true}]}
 }});
 await h.submit();
 assert.equal(h.posts.length,2);
 for(const {body} of h.posts){
   assert.equal(body.destinationPlatform,'TIKTOK_SHOP');assert.equal(body.warehouseId,'wh');
   assert.equal(body.publishStock,999);assert.equal(body.autoConvertPrice,true);
   assert.equal(body.logisticInfo,undefined);
 }
});
test('TikTok captures can bulk upload to Shopee without changing source identity',async()=>{
 const h=harness(fixture().map(c=>({...c,sourcePlatform:'TIKTOK'})));
 await h.submit();
 assert.equal(h.posts.length,2);
 assert.ok(h.posts.every(p=>p.body.destinationPlatform==='SHOPEE'));
 assert.ok([...h.state.captures.values()].every(c=>c.sourcePlatform==='TIKTOK'));
});

function addTikTokShop(h) {
  h.c.activeMarketplaceShops=platform=>platform==='TIKTOK_SHOP'
    ? [{id:'tiktok-shop',name:'New TikTok shop',region:'MY',platform:'TIKTOK_SHOP'}]
    : [{id:'shop',name:'Original Shopee shop',region:'MY',platform:'SHOPEE'}];
  h.state.onOptions=async shopId=>shopId==='tiktok-shop'?{data:{listingMethod:'LOCAL',canCreateDraft:true,
    categories:{categories:[{id:'tk-dress',name:'Dresses',is_leaf:true},{id:'tk-shirt',name:'T-shirts',is_leaf:true}]},
    warehouses:{warehouses:[{id:'tk-warehouse',type:'SALES_WAREHOUSE',is_default:true}]}
  }}:null;
}

test('a category preparation failure unlocks the shop and retries only that product using the new platform',async()=>{
  const captures=fixture();captures[0].categoryName='Women Clothes > Dresses';
  const h=harness(captures);addTikTokShop(h);
  await h.submit();
  assert.equal(h.c.productBulkItems[0].status,'PREPARE_FAILED');
  assert.equal(h.node('#product-bulk-settings').disabled,false);
  const originalItems=h.c.productBulkItems,accepted=originalItems[1],acceptedSnapshot=plain(accepted);
  h.node('#product-bulk-shop').value='tiktok-shop';h.c.changeProductBulkShop();
  assert.notEqual(h.c.productBulkItems,originalItems);
  assert.equal(h.c.productBulkItems[0].shopId,'tiktok-shop');
  assert.equal(h.c.productBulkItems[0].status,'WAITING');
  assert.equal(h.c.productBulkItems[0].error,undefined);
  assert.equal(h.c.productBulkItems[0].idempotencyKey,'bulk-key-2-0');
  assert.equal(h.c.productBulkItems[1],accepted);assert.deepEqual(plain(accepted),acceptedSnapshot);
  assert.match(h.node('#product-bulk-progress').innerHTML,/New TikTok shop/);
  assert.match(h.node('#product-bulk-progress').innerHTML,/Original Shopee shop/);
  await h.submit();
  assert.deepEqual(h.posts.map(call=>[call.id,call.body.shopId]),[['b','shop'],['a','tiktok-shop']]);
  const {body,key}=h.posts[1];
  assert.equal(body.destinationPlatform,'TIKTOK_SHOP');assert.equal(body.categoryId,'tk-dress');
  assert.equal(body.warehouseId,'tk-warehouse');assert.equal(body.logisticInfo,undefined);
  assert.equal(body.publishStock,999);assert.equal(body.autoConvertPrice,true);
  assert.equal(key,'bulk-key-2-0');assert.equal(body.idempotencyKey,key);
  assert.deepEqual(h.optionReads,['/api/v1/shops/shop/publish-options','/api/v1/shops/tiktok-shop/publish-options']);
  assert.deepEqual(h.captureReads,['a','b','a']);
  assert.equal(h.node('#product-bulk-settings').disabled,true);
});

test('switching unsent items never changes a successful or unknown submission and recovery replays its exact request',async()=>{
  const captures=fixture(),h=harness(captures);addTikTokShop(h);
  const items=ProductBulkUpload.plan(captures,'shop','original');
  const originalPayload={confirmed:true,shopId:'shop',destinationPlatform:'SHOPEE',idempotencyKey:'original-1',categoryId:'3',publishStock:999};
  Object.assign(items[0],{status:'PREPARE_FAILED',error:'old category failure'});
  Object.assign(items[1],{status:'CREATE_FAILED',payload:originalPayload,error:'提交结果暂时无法确认'});
  items.push({captureId:'already-done',title:'Already uploaded',shopId:'shop',status:'SUCCEEDED',idempotencyKey:'accepted-key',
    payload:{shopId:'shop',idempotencyKey:'accepted-key'},workflow:{id:'done',status:'SUCCEEDED',shopId:'shop'}});
  h.c.productBulkItems=items;h.c.saveProductBulkItems();
  const originalUnknown=plain(items[1]),originalSuccess=plain(items[2]);
  h.node('#product-bulk-shop').value='tiktok-shop';h.c.changeProductBulkShop();
  assert.equal(h.c.productBulkItems[1],items[1]);assert.equal(h.c.productBulkItems[2],items[2]);
  assert.deepEqual(plain(h.c.productBulkItems[1]),originalUnknown);
  assert.deepEqual(plain(h.c.productBulkItems[2]),originalSuccess);
  await h.submit();
  const recovered=h.posts.find(call=>call.id==='b');
  assert.deepEqual(recovered.body,originalPayload);assert.equal(recovered.key,'original-1');
  assert.equal(h.posts.filter(call=>call.id==='already-done').length,0);
  assert.deepEqual(h.captureReads,['a']);
  assert.deepEqual(h.optionReads,['/api/v1/shops/tiktok-shop/publish-options']);
});

test('storage failure rejects a shop switch without losing the original item array, keys or selected shop',()=>{
  const h=harness();addTikTokShop(h);
  h.c.productBulkItems=ProductBulkUpload.plan(fixture(),'shop','original');
  h.c.productBulkItems[0].status='PREPARE_FAILED';
  h.c.saveProductBulkItems();
  const original=h.c.productBulkItems,snapshot=plain(original),saved=h.storage.get(h.c.productBulkStorageKey);
  h.state.onWrite=()=>{throw Error('storage is full');};
  h.node('#product-bulk-shop').value='tiktok-shop';h.c.changeProductBulkShop();
  assert.equal(h.c.productBulkItems,original);assert.deepEqual(plain(original),snapshot);
  assert.equal(h.storage.get(h.c.productBulkStorageKey),saved);
  assert.equal(h.node('#product-bulk-shop').value,'shop');
  assert.equal(h.node('#product-bulk-error').textContent,'storage is full');
  assert.equal(h.node('#product-bulk-settings').disabled,false);
  assert.equal(h.posts.length,0);
});

test('shop change handler rejects busy, unauthorized and stale organization events even if a select event is forced',()=>{
  for(const reason of ['busy','permission','organization']) {
    const h=harness();addTikTokShop(h);
    h.c.productBulkItems=ProductBulkUpload.plan(fixture(),'shop','original');
    const original=h.c.productBulkItems,snapshot=plain(original);
    if(reason==='busy')h.c.productBulkBusy=true;
    if(reason==='permission')h.c.hasAnyPermission=()=>false;
    if(reason==='organization')h.c.currentUser={organizationId:'different-org'};
    h.node('#product-bulk-shop').value='tiktok-shop';h.c.changeProductBulkShop();
    assert.equal(h.c.productBulkItems,original,reason);assert.deepEqual(plain(original),snapshot,reason);
    assert.equal(h.node('#product-bulk-shop').value,'shop',reason);
    assert.equal(h.storage.size,0,reason);assert.equal(h.state.keyCount,0,reason);
  }
});

test('reopening a mixed batch restores the unsent product shop instead of the first successful product shop',async()=>{
  const h=harness();addTikTokShop(h);
  h.c.productBulkItems=ProductBulkUpload.plan(fixture(),'shop','original');
  Object.assign(h.c.productBulkItems[0],{status:'SUCCEEDED',payload:{shopId:'shop'},workflow:{id:'done-a',captureId:'a',status:'SUCCEEDED',shopId:'shop'}});
  Object.assign(h.c.productBulkItems[1],{shopId:'tiktok-shop',status:'PREPARE_FAILED',idempotencyKey:'new-b-1'});
  h.c.saveProductBulkItems();h.c.productBulkItems=[];
  try {
    await h.c.openProductBulkUpload(h.c.productBulkStorageKey);await flush();
    assert.equal(h.node('#product-bulk-shop').value,'tiktok-shop');
    assert.equal(h.node('#product-bulk-settings').disabled,false);
    assert.equal(h.c.productBulkItems[0].shopId,'shop');
    assert.equal(h.c.productBulkItems[1].idempotencyKey,'new-b-1');
    assert.match(h.node('#product-bulk-shop-hint').textContent,/1 件未提交商品/);
    assert.equal(h.posts.length,0);
  } finally {h.c.stopProductBulkPolling();}
});

test('changing an unsent shop cancels the old poll generation and ignores its late accepted-item response',async()=>{
  const h=harness(),old=deferred();addTikTokShop(h);setWorkflows(h);
  h.c.productBulkItems[1]={captureId:'b',title:'Socks',shopId:'shop',status:'PREPARE_FAILED',idempotencyKey:'original-b'};
  let calls=0,oldSignal;
  h.state.onWorkflowRead=async(id,workflowId,_c,request)=>{
    calls++;
    if(calls===1){oldSignal=request.signal;return old.promise;}
    return {data:{id:workflowId,captureId:id,status:'SUCCEEDED',shopId:'shop'}};
  };
  const oldPoll=h.c.pollProductBulkProgress();await flush();
  const original=h.c.productBulkItems;
  try {
    h.node('#product-bulk-shop').value='tiktok-shop';h.c.changeProductBulkShop();await flush();
    assert.equal(oldSignal.aborted,true);assert.notEqual(h.c.productBulkItems,original);
    assert.equal(h.c.productBulkItems[0].workflow.status,'SUCCEEDED');
    assert.equal(h.c.productBulkItems[1].shopId,'tiktok-shop');
    const saved=h.storage.get(h.c.productBulkStorageKey);
    old.resolve({data:{id:'workflow-a',captureId:'a',status:'RUNNING',shopId:'shop'}});
    await oldPoll;await flush();
    assert.equal(h.c.productBulkItems[0].workflow.status,'SUCCEEDED');
    assert.equal(h.c.productBulkItems[1].shopId,'tiktok-shop');
    assert.equal(h.storage.get(h.c.productBulkStorageKey),saved);
    assert.equal(h.posts.length,0);
  } finally {old.resolve({data:{}});h.c.stopProductBulkPolling();await oldPoll;}
});

test('mixed unsent shops fetch their own configuration and never share category or logistics results',async()=>{
  const h=harness();addTikTokShop(h);
  h.c.productBulkItems=ProductBulkUpload.plan(fixture(),'shop','mixed');
  h.c.productBulkItems[0].shopId='tiktok-shop';
  await h.submit();
  assert.deepEqual(new Set(h.optionReads),new Set(['/api/v1/shops/tiktok-shop/publish-options','/api/v1/shops/shop/publish-options']));
  const shirt=h.posts.find(call=>call.id==='a').body,socks=h.posts.find(call=>call.id==='b').body;
  assert.equal(shirt.destinationPlatform,'TIKTOK_SHOP');assert.equal(shirt.categoryId,'tk-shirt');
  assert.equal(shirt.warehouseId,'tk-warehouse');assert.equal(shirt.logisticInfo,undefined);
  assert.equal(socks.destinationPlatform,'SHOPEE');assert.equal(socks.categoryId,'3');
  assert.equal(socks.warehouseId,undefined);assert.deepEqual(socks.logisticInfo,[{logistic_id:101,enabled:true}]);
});

test('a new batch can finish while an earlier preparation or submission is pending and each retains its own storage key',async()=>{
  for(const blockedStage of ['prepare','create']) {
    const h=harness(),gate=deferred();
    if(blockedStage==='prepare')h.state.onRead=async id=>{if(id==='a')await gate.promise;};
    else h.state.onPost=async call=>{if(call.id==='a')await gate.promise;};
    h.c.productBulkSelectedIds=new Set(['a']);
    await h.c.openProductBulkUpload();
    const firstKey=h.c.productBulkStorageKey,first=h.submit();await flush();
    const firstItems=h.c.productBulkItems;
    assert.equal(firstItems[0].status,blockedStage==='prepare'?'PREPARING':'SUBMITTING');
    try {
      h.c.productBulkSelectedIds=new Set(['b']);await h.c.openProductBulkUpload();
      const secondKey=h.c.productBulkStorageKey;
      assert.notEqual(secondKey,firstKey);assert.equal(h.c.productBulkBusy,false);
      assert.equal(h.c.productBulkItems.length,0);
      await h.submit();await flush();
      const secondItems=h.c.productBulkItems,secondSaved=h.storage.get(secondKey);
      assert.equal(secondItems.length,1);assert.equal(secondItems[0].captureId,'b');
      assert.ok(secondItems[0].workflow?.id);assert.equal(h.c.productBulkBusy,false);
      assert.equal(h.runCount(),1);
      gate.resolve();await first;await flush();
      assert.equal(h.c.productBulkStorageKey,secondKey);assert.equal(h.c.productBulkItems,secondItems);
      assert.equal(h.c.productBulkBusy,false);assert.equal(h.storage.get(secondKey),secondSaved);
      assert.equal(h.runCount(),0);
      const savedFirst=JSON.parse(h.storage.get(firstKey));
      assert.equal(savedFirst.items.length,1);assert.equal(savedFirst.items[0].captureId,'a');
      assert.equal(savedFirst.items[0].workflow.id,'workflow-a');
      assert.deepEqual(new Set(ProductBulkHistory.list(h.c.localStorage,'org').map(record=>record.key)),new Set([firstKey,secondKey]));
      assert.deepEqual(new Set(h.posts.map(call=>call.id)),new Set(['a','b']));
    } finally {gate.resolve();await first;h.c.stopProductBulkPolling();}
  }
});

test('completion of a hidden batch cannot clear the visible batch busy state or replace its pending items',async()=>{
  const h=harness(),firstGate=deferred(),secondGate=deferred();
  h.state.onPost=async call=>{await(call.id==='a'?firstGate:secondGate).promise;};
  h.c.productBulkSelectedIds=new Set(['a']);await h.c.openProductBulkUpload();
  const first=h.submit();await flush();
  h.c.productBulkSelectedIds=new Set(['b']);await h.c.openProductBulkUpload();
  const secondKey=h.c.productBulkStorageKey,second=h.submit();await flush();
  const secondItems=h.c.productBulkItems;
  try {
    firstGate.resolve();await first;await flush();
    assert.equal(h.c.productBulkStorageKey,secondKey);assert.equal(h.c.productBulkItems,secondItems);
    assert.equal(h.c.productBulkBusy,true);assert.equal(secondItems[0].status,'SUBMITTING');
    assert.equal(h.node('#submit-product-bulk').disabled,true);
    assert.equal(h.node('#submit-product-bulk').textContent,'正在自动准备并提交…');
    assert.equal(h.runCount(),1);
  } finally {firstGate.resolve();secondGate.resolve();await Promise.all([first,second]);h.c.stopProductBulkPolling();}
});

test('reselecting identical products creates a fresh batch while history reopens the running batch object',async()=>{
  const h=harness([fixture()[0]]),gate=deferred();
  h.state.onRead=async()=>{await gate.promise;};
  await h.c.openProductBulkUpload();
  const firstKey=h.c.productBulkStorageKey,first=h.submit();await flush();
  const runningItems=h.c.productBulkItems;
  try {
    h.node('#product-bulk-dialog').close();h.c.stopProductBulkPolling();
    await h.c.openProductBulkUpload(firstKey);
    assert.equal(h.c.productBulkItems,runningItems);assert.equal(h.c.productBulkBusy,true);
    assert.equal(h.node('#product-bulk-settings').disabled,true);
    await h.submit();assert.equal(h.captureReads.length,1,'Opening a running history entry must not submit it again');
    h.node('#product-bulk-dialog').close();h.c.stopProductBulkPolling();
    await h.c.openProductBulkUpload();const secondKey=h.c.productBulkStorageKey;
    assert.notEqual(secondKey,firstKey);assert.equal(h.c.productBulkBusy,false);assert.equal(h.c.productBulkItems.length,0);
    await h.c.openProductBulkUpload();
    assert.notEqual(h.c.productBulkStorageKey,secondKey);assert.notEqual(h.c.productBulkStorageKey,firstKey);
    assert.equal(h.c.productBulkItems.length,0);assert.equal(h.c.productBulkBusy,false);
    assert.equal(ProductBulkHistory.list(h.c.localStorage,'org').length,1,'Opening an unsent form must not create empty history entries');
  } finally {gate.resolve();await first;h.c.stopProductBulkPolling();}
});

test('published originals and variants require an explicit shop and republish without editing source data',async()=>{
  const captures=fixture().map((item,index)=>({...item,status:'PUBLISHED',originType:index?'COMMERCE_GENERATED':'CAPTURED'}));
  const h=harness(captures);
  h.c.activeMarketplaceShops=platform=>platform==='SHOPEE'?[{id:'other-shop',name:'Second shop',region:'PH',platform:'SHOPEE'}]:[];
  await h.c.openProductBulkUpload();
  const key=h.c.productBulkStorageKey;
  assert.equal(h.node('#product-bulk-shop').value,'','Published products must not silently select the only shop');
  assert.equal(h.node('#submit-product-bulk').disabled,true);
  assert.match(h.node('#product-bulk-mode').textContent,/复用现有图片/);
  h.node('#product-bulk-shop').value='other-shop';
  await h.submit();
  assert.equal(h.patches.length,0,'Republish must not rewrite published products');
  assert.equal(h.posts.length,2);
  assert.ok(h.posts.every(call=>call.body.shopId==='other-shop'));
  const receipts=value=>JSON.parse(value).items.map(item=>({captureId:item.captureId,idempotencyKey:item.idempotencyKey,payload:item.payload,workflowId:item.workflow.id}));
  const oldReceipts=receipts(h.storage.get(key));
  await h.c.openProductBulkUpload();
  assert.notEqual(h.c.productBulkStorageKey,key);
  assert.equal(h.c.productBulkItems.length,0);
  assert.equal(h.node('#product-bulk-shop').value,'');
  assert.deepEqual(receipts(h.storage.get(key)),oldReceipts,'Starting another upload preserves previous records');
  h.c.stopProductBulkPolling();
});

test('per-card upload selects only that product without disturbing bulk selections',async()=>{
  const h=harness(fixture().map(item=>({...item,status:'PUBLISHED'})));
  const selection=[...h.c.productBulkSelectedIds];
  await h.c.openProductBulkUpload('', 'b');
  assert.match(h.node('#product-bulk-summary').textContent,/1/);
  assert.deepEqual([...h.c.productBulkSelectedIds],selection);
  h.node('#product-bulk-shop').value='shop';
  await h.submit();
  assert.deepEqual(h.posts.map(call=>call.id),['b']);
  assert.equal(h.patches.length,0);
  h.c.stopProductBulkPolling();
});

test('per-card upload respects status and permission restrictions',async()=>{
  const h=harness();
  await h.c.openProductBulkUpload('', 'missing');
  assert.equal(h.node('#product-bulk-dialog').open,false);
  h.c.hasAnyPermission=()=>false;
  await h.c.openProductBulkUpload('', 'a');
  assert.equal(h.node('#product-bulk-dialog').open,false);
  assert.equal(h.posts.length,0);
});

test('a late history status read merges into the latest saved batch without erasing another product receipt',async()=>{
  const h=harness(),gate=deferred(),historyKey=ProductBulkHistory.storageKey('org','history-merge');
  const items=ProductBulkUpload.plan(fixture(),'shop','history');
  Object.assign(items[0],{status:'RUNNING',workflow:{id:'old-a',captureId:'a',status:'RUNNING',shopId:'shop'}});
  h.c.saveProductBulkItems(items,historyKey,'org');
  h.c.productBulkStorageKey='unrelated-visible-batch';h.c.productBulkItems=[];
  h.state.onWorkflowRead=async()=>gate.promise;
  h.c.openProductBulkHistory();await flush();
  try {
    assert.equal(h.workflowReads.length,1);
    const latest=JSON.parse(h.storage.get(historyKey)).items;
    Object.assign(latest[1],{status:'SUCCEEDED',payload:{shopId:'shop',idempotencyKey:'history-1'},
      workflow:{id:'new-b',captureId:'b',status:'SUCCEEDED',shopId:'shop'}});
    h.c.saveProductBulkItems(latest,historyKey,'org');const receipt=plain(latest[1]);
    gate.resolve({data:{id:'old-a',captureId:'a',status:'SUCCEEDED',shopId:'shop'}});await flush();
    const final=JSON.parse(h.storage.get(historyKey));
    assert.equal(final.items[0].workflow.status,'SUCCEEDED');assert.deepEqual(final.items[1],receipt);
    assert.equal(h.c.productBulkItems.length,0);assert.equal(h.posts.length,0);
    assert.match(h.node('#product-bulk-history-list').innerHTML,/全部成功/);
  } finally {gate.resolve({data:{}});h.c.stopProductBulkHistoryPolling();}
});

test('history polling ignores an obsolete receipt and organization changes before saving',async()=>{
  for(const change of ['receipt','organization']) {
    const h=harness([fixture()[0]]),gate=deferred(),historyKey=ProductBulkHistory.storageKey('org',`history-${change}`);
    const items=ProductBulkUpload.plan([fixture()[0]],'shop','history');
    Object.assign(items[0],{status:'RUNNING',workflow:{id:'old-a',captureId:'a',status:'RUNNING',shopId:'shop'}});
    h.c.saveProductBulkItems(items,historyKey,'org');
    h.c.productBulkStorageKey='unrelated-view';h.c.productBulkItems=[];
    h.state.onWorkflowRead=async()=>gate.promise;
    h.c.openProductBulkHistory();await flush();
    try {
      if(change==='receipt') {
        items[0].workflow={id:'new-a',captureId:'a',status:'SUCCEEDED',shopId:'shop'};items[0].status='SUCCEEDED';
        h.c.saveProductBulkItems(items,historyKey,'org');
      } else h.c.currentUser={organizationId:'another-org'};
      const saved=h.storage.get(historyKey);
      gate.resolve({data:{id:'old-a',captureId:'a',status:'FAILED',shopId:'shop'}});await flush();
      assert.equal(h.storage.get(historyKey),saved,change);
      assert.equal(h.posts.length,0);
    } finally {gate.resolve({data:{}});h.c.stopProductBulkHistoryPolling();}
  }
});

test('organization changes or cleared run ownership reject late prepare and create results without storage writes',async()=>{
  for(const stage of ['prepare','create']) for(const invalidate of ['organization','runs']) {
    const h=harness([fixture()[0]]),gate=deferred();
    if(stage==='prepare')h.state.onRead=async()=>gate.promise;
    else h.state.onPost=async()=>gate.promise;
    const running=h.submit();await flush();
    const originalItems=h.c.productBulkItems,key=h.c.productBulkStorageKey,saved=h.storage.get(key),snapshot=plain(originalItems);
    let writes=0;h.state.onWrite=()=>{writes++;};
    if(invalidate==='organization')h.c.currentUser={organizationId:'another-org'};
    else h.clearRuns();
    gate.resolve();await running;await flush();
    assert.equal(writes,0,`${stage}/${invalidate}`);
    assert.equal(h.storage.get(key),saved);assert.deepEqual(plain(originalItems),snapshot);
    assert.equal(h.posts.length,stage==='prepare'?0:1);
    assert.equal(h.runCount(),0);
  }
});


test('cross-border options failure remains unsent and changing shops prepares fresh verified capabilities', async()=>{
  const h=harness();
  h.c.activeMarketplaceShops=platform=>platform==='TIKTOK_SHOP'
    ? [{id:'cross',name:'Cross-border MY',region:'MY',platform:'TIKTOK_SHOP',sellerType:'CROSS_BORDER'},
       {id:'local',name:'Local MY',region:'MY',platform:'TIKTOK_SHOP',sellerType:'LOCAL'}] : [];
  h.node('#product-bulk-shop').value='cross';
  h.state.onOptions=async shop=>{
    if(shop==='cross')throw new h.c.ApiRequestError('跨境刊登模式未就绪：官方未返回LOCAL_REPLICATION',409);
    return {data:{listingMethod:'LOCAL',canCreateDraft:true,categories:{categories:[
      {id:'tshirt',name:'T-shirts',is_leaf:true},{id:'socks',name:'Socks',is_leaf:true}]},
      warehouses:{warehouses:[{id:'local-wh',type:'SALES_WAREHOUSE',is_default:true}]}}};
  };
  await h.submit();
  assert.equal(h.posts.length,0);
  assert.ok(h.c.productBulkItems.every(item=>item.status==='PREPARE_FAILED' && /Cross-border MY/.test(item.error)));
  assert.equal(h.node('#product-bulk-settings').disabled,false);
  h.node('#product-bulk-shop').value='local';h.c.changeProductBulkShop();
  assert.ok(h.c.productBulkItems.every(item=>item.status==='WAITING' && item.shopId==='local' && !item.error));
  await h.submit();
  assert.equal(h.posts.length,2);
  assert.ok(h.posts.every(item=>item.body.shopId==='local' && item.body.warehouseId==='local-wh'));
  assert.deepEqual(h.optionReads,['/api/v1/shops/cross/publish-options','/api/v1/shops/local/publish-options']);
});

test('bulk rejects incomplete or incompatible cross-border capability even with valid taxonomy and warehouse',async()=>{
  for(const capability of [{},{listingMethod:'GLOBAL_PUBLISHING',canCreateDraft:true},{listingMethod:'LOCAL',canCreateDraft:true},{listingMethod:'LOCAL_REPLICATION',canCreateDraft:false}]){
    const h=harness();
    h.c.activeMarketplaceShops=platform=>platform==='TIKTOK_SHOP'?[{id:'shop',name:'Cross MY',platform:'TIKTOK_SHOP',sellerType:'CROSS_BORDER'}]:[];
    h.state.onOptions=async()=>({data:{...capability,categories:{categories:[{id:'1',name:'T-shirts',is_leaf:true},{id:'2',name:'Socks',is_leaf:true}]},warehouses:{warehouses:[{id:'wh',type:'SALES_WAREHOUSE',is_default:true}]}}});
    await h.submit();assert.equal(h.posts.length,0);assert.ok(h.c.productBulkItems.every(item=>item.status==='PREPARE_FAILED'));
  }
});

test('bulk retains the best garment category and blocks its actual cross-border permission until the user changes shops',async()=>{
  const categories=require('./fixtures/tiktok-my-cross-border-categories-20260910.json').categories;
  const facts=require('./fixtures/shopee-my-96sku-category-facts.json');
  const capture={...fixture()[0],...plain(facts)},h=harness([capture]);
  h.c.activeMarketplaceShops=platform=>platform==='TIKTOK_SHOP'
    ? [{id:'cross',name:'Cross-border MY',region:'MY',platform:'TIKTOK_SHOP',sellerType:'CROSS_BORDER'},
       {id:'local',name:'Local MY',region:'MY',platform:'TIKTOK_SHOP',sellerType:'LOCAL'}] : [];
  h.node('#product-bulk-shop').value='cross';
  h.storage.set(JSON.stringify(['publish-settings-v1','org','TIKTOK_SHOP','cross',capture.categoryName]),JSON.stringify({category:'600321'}));
  h.state.onOptions=async shop=>({data:{listingMethod:shop==='cross'?'LOCAL_REPLICATION':'LOCAL',canCreateDraft:true,
    categories:{categories:shop==='cross'?categories:categories.map(c=>({...c,permission_statuses:['AVAILABLE']}))},
    warehouses:{warehouses:[{id:'wh-'+shop,type:'SALES_WAREHOUSE',is_default:true}]}}});
  await h.submit();
  assert.equal(h.posts.length,0);assert.equal(h.patches.length,0);
  assert.equal(h.c.productBulkItems[0].status,'PREPARE_FAILED');
  assert.match(h.c.productBulkItems[0].error,/Cross-border MY.*Women's T-shirts.*601302.*主营类目/);
  assert.equal(h.node('#product-bulk-settings').disabled,false);
  h.node('#product-bulk-shop').value='local';h.c.changeProductBulkShop();
  await h.submit();
  assert.equal(h.posts.length,1);
  assert.equal(h.posts[0].body.shopId,'local');assert.equal(h.posts[0].body.categoryId,'601302');
  assert.equal(h.posts[0].body.warehouseId,'wh-local');
});

test('empty logistics and upstream 500 recover with five bounded reads and one submission per item',async()=>{
 const h=harness();let calls=0;
 h.state.onOptions=()=>{calls++;if(calls===1)throw Object.assign(Error('upstream inner error'),{status:500});if(calls<5)return {data:{logistics:{logistics_channel_list:[]}}};};
 await h.submit();assert.equal(h.optionReads.length,5);assert.equal(h.posts.length,2);assert.equal(new Set(h.posts.map(x=>x.key)).size,2);
});
test('persistently empty logistics stop after five reads without blaming shop or submitting products',async()=>{
 const h=harness();h.state.onOptions=()=>({data:{logistics:{logistics_channel_list:[]}}});
 await h.submit();assert.equal(h.optionReads.length,5);assert.equal(h.posts.length,0);assert.ok(h.c.productBulkItems.every(x=>x.error.includes('不代表店铺未开启物流')));
});
test('shop options authorization denial is not retried',async()=>{
 const h=harness();h.state.onOptions=()=>{throw Object.assign(Error('Forbidden'),{status:403});};
 await h.submit();assert.equal(h.optionReads.length,1);assert.equal(h.posts.length,0);
});

test('a truncated successful JSON read retries automatically without replaying a mutation', async () => {
  const h=harness();let reads=0;
  h.c.fetch=async()=>({status:200,ok:true,json:async()=>{
    if(++reads===1)throw new SyntaxError('Unexpected end of JSON input');
    return {data:{id:'a'}};
  }});
  vm.runInContext(slice('async function api(', 'const GENERAL_VIDEO_API_ROOT'),h.c);
  const result=await h.c.productBulkApi('/detail');
  assert.equal(result.data.id,'a');assert.equal(reads,2);assert.equal(h.posts.length,0);
  h.c.fetch=async()=>({status:403,ok:false,json:async()=>{throw new SyntaxError('bad body');}});
  await assert.rejects(h.c.api('/detail'),e=>e.status===403);
});

test('incomplete terminal detail automatically recovers and preserves upload success without creating again',async()=>{
  const h=harness([fixture()[0]]);setWorkflows(h,'SUCCEEDED');h.state.now=Date.now();
  let reads=0;
  h.c.productCaptureApi=async()=>++reads===1?{data:null}:{data:{productCapture:fixture()[0]}};
  await h.c.pollProductBulkProgress();await flush();
  assert.equal(reads,1);assert.equal(h.c.productBulkItems[0].syncPaused,false);
  assert.match(h.node('#product-bulk-progress').innerHTML,/上传已成功，状态刷新中/);
  assert.doesNotMatch(h.node('#product-bulk-progress').innerHTML,/查看失败原因/);
  h.state.now+=6000;
  await h.c.pollProductBulkProgress();await flush();
  assert.equal(reads,2);assert.equal(h.updates.length,1);
  assert.equal(h.c.productBulkItems[0].syncError,'');
  assert.equal(h.posts.length,0);assert.equal(h.workflowReads.length,0);
  h.c.stopProductBulkPolling();
});
