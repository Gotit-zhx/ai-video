const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../app.js'),'utf8');
const crossBorderCategories=require('./fixtures/tiktok-my-cross-border-categories-20260910.json').categories;
const actual96SkuFacts=require('./fixtures/shopee-my-96sku-category-facts.json');
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};};
const options=(listingMethod='LOCAL_REPLICATION')=>({data:{listingMethod,canCreateDraft:true,
 categories:{categories:[{id:'women',name:'Women Clothes',is_leaf:false},{id:'shirt',parent_id:'women',name:'T-shirts',is_leaf:true}]},
 warehouses:{warehouses:[{id:'warehouse-'+listingMethod,type:'SALES_WAREHOUSE',is_default:true}]}}});
function harness(){
 const nodes=new Map();
 const node=id=>{if(!nodes.has(id))nodes.set(id,{value:'',dataset:{},replaceChildren(...options){this.options=options;this.value=options[0]?.value||'';}});return nodes.get(id);};
 const shops=[{id:'cross',name:'Cross-border MY',platform:'TIKTOK_SHOP',sellerType:'CROSS_BORDER'},
  {id:'cross-other',name:'Other cross-border MY',platform:'TIKTOK_SHOP',sellerType:'CROSS_BORDER'},
  {id:'local',name:'Local MY',platform:'TIKTOK_SHOP',sellerType:'LOCAL'},
  {id:'shopee',name:'Shopee MY',platform:'SHOPEE'}];
 const c={document:{querySelector:node},localStorage:{getItem:()=>null},Option:function(text,value){this.text=text;this.value=value;},
 currentUser:{organizationId:'org'},selectedProductCaptureDetail:{id:'capture',categoryName:'Women Clothes > T-shirts',normalizedData:{}},
 marketplaceIntegrationStatus:{livePublishingEnabled:true},activeMarketplaceShops:platform=>shops.filter(s=>s.platform===platform),
 api:async()=>options(),parseProductPublishStock:()=>999,parseProductSkuStocks:()=>({}),parseProductPublishJson:()=>({}),parseShopeeLogisticInfo:()=>[{logistic_id:1,enabled:true}]};
 node('#publish-shop-id').value='cross';node('#publish-destination-platform').value='TIKTOK_SHOP';
 c.ProductListingTemplates={ensure:async()=>{},get:()=>({enabled:false}),fields:()=>({})};
 vm.createContext(c);
 vm.runInContext(source.slice(source.indexOf('let productPublishPrepareVersion'),source.indexOf('function syncOfficialPublishFields()')),c);
 vm.runInContext(source.slice(source.indexOf('function buildProductPublishPayload('),source.indexOf('function productPublishWorkflowMatchesTarget(')),c);
 return{c,node,shops,prepare:()=>c.prepareSimpleProductPublish(),payload:()=>c.buildProductPublishPayload(node('#publish-destination-platform').value,node('#publish-shop-id').value,'key')};
}
test('cross-border capability failure cannot be bypassed by manually entering category and warehouse',async()=>{
 const h=harness();h.c.api=async()=>{throw Error('跨境刊登模式未就绪：尚未迁移');};await h.prepare();
 assert.match(h.node('#publish-auto-summary').textContent,/Cross-border MY.*跨境刊登模式未就绪/);
 assert.equal(h.node('#publish-auto-summary').dataset.shopId,'cross');
 assert.equal(h.node('#publish-auto-summary').dataset.loading,'false');
 assert.equal(h.node('#publish-category-id').hidden,true);assert.equal(h.node('#publish-warehouse-id').value,'');
 h.node('#publish-category-id').value='manually-entered';h.node('#publish-warehouse-id').value='manual-warehouse';
 assert.throws(h.payload,/跨境刊登模式未就绪/);
});
test('successful cross-border options must explicitly confirm LOCAL_REPLICATION and canCreateDraft',async()=>{
 for(const capability of [{},{listingMethod:'LOCAL',canCreateDraft:true},{listingMethod:'GLOBAL_PUBLISHING',canCreateDraft:true},{listingMethod:'LOCAL_REPLICATION',canCreateDraft:false}]){
  const h=harness();h.c.api=async()=>({data:{...options().data,listingMethod:undefined,canCreateDraft:undefined,...capability}});await h.prepare();
  assert.throws(h.payload,/跨境刊登模式未就绪/);
 }
 const h=harness();await h.prepare();assert.equal(h.payload().warehouseId,'warehouse-LOCAL_REPLICATION');
 assert.match(h.node('#publish-auto-summary').textContent,/已确认跨境店铺刊登模式/);
});
test('switching a failed cross-border shop to a local shop clears old failure and reads new options',async()=>{
 const h=harness(),reads=[];h.c.api=async path=>{reads.push(path);if(path.includes('/cross/'))throw Error('跨境刊登模式未就绪');return options('LOCAL');};
 await h.prepare();h.node('#publish-shop-id').value='local';await h.prepare();
 assert.equal(h.node('#publish-auto-summary').dataset.prepareError,'');
 assert.equal(h.payload().shopId,'local');assert.equal(h.payload().warehouseId,'warehouse-LOCAL');
 assert.deepEqual(reads,['/api/v1/shops/cross/publish-options','/api/v1/shops/local/publish-options']);
});
test('previous successful capability is unusable while the newly selected shop is loading or fails',async()=>{
 const h=harness();await h.prepare();assert.equal(h.payload().shopId,'cross');
 const gate=deferred();h.c.api=()=>gate.promise;h.node('#publish-shop-id').value='cross-other';const pending=h.prepare();
 assert.throws(h.payload,/正在读取/);
 gate.reject(Error('跨境刊登模式未就绪：新店未确认'));await pending;
 assert.throws(h.payload,/Other cross-border MY.*新店未确认/);
});
test('late old-shop success and failure cannot overwrite current-shop fields, error or loading flag',async()=>{
 for(const oldFails of [false,true]){
  const h=harness(),gate=deferred();h.c.api=()=>gate.promise;const old=h.prepare();await Promise.resolve();
  h.node('#publish-shop-id').value='local';h.c.api=async()=>options('LOCAL');await h.prepare();
  const snapshot=JSON.stringify({summary:h.node('#publish-auto-summary'),category:h.node('#publish-category-id'),warehouse:h.node('#publish-warehouse-id')});
  if(oldFails)gate.reject(Error('跨境刊登模式未就绪：旧店失败'));else gate.resolve(options());
  await old;
  assert.equal(JSON.stringify({summary:h.node('#publish-auto-summary'),category:h.node('#publish-category-id'),warehouse:h.node('#publish-warehouse-id')}),snapshot);
  assert.equal(h.payload().shopId,'local');
 }
});
test('a changed shop, capture or organization invalidates capability before another prepare call',async()=>{
 for(const change of [h=>h.node('#publish-shop-id').value='cross-other',h=>h.c.selectedProductCaptureDetail.id='another',h=>h.c.currentUser.organizationId='another']){
  const h=harness();await h.prepare();change(h);assert.throws(h.payload,/目标店铺已变化/);
 }
});
test('an old cross-border response is ignored when destination platform changed to Shopee',async()=>{
 const h=harness(),gate=deferred();h.c.api=()=>gate.promise;const old=h.prepare();await Promise.resolve();
 h.node('#publish-shop-id').value='shopee';h.node('#publish-destination-platform').value='SHOPEE';
 h.c.api=async()=>({data:{categories:{category_list:[{category_id:1,display_category_name:'T-shirts',has_children:false}]},logistics:{logistics_channel_list:[{logistics_channel_id:1,enabled:true}]}}});
 await h.prepare();gate.reject(Error('旧跨境接口失败'));await old;
 assert.equal(h.payload().destinationPlatform,'SHOPEE');assert.equal(h.node('#publish-category-id').value,'1');
});
test('known LOCAL and Shopee retain their existing manual fallback for ordinary configuration read failures',async()=>{
 for(const [platform,shop] of [['TIKTOK_SHOP','local'],['SHOPEE','shopee']]){
  const h=harness();h.node('#publish-shop-id').value=shop;h.node('#publish-destination-platform').value=platform;
  h.c.api=async()=>{throw Error('读取类目失败');};await h.prepare();
  assert.equal(h.node('#publish-category-id').hidden,false);h.node('#publish-category-id').value='manual';h.node('#publish-warehouse-id').value='wh';
  assert.equal(h.payload().categoryId,'manual');
 }
});

test('actual denied garment category stops local and cross-border submission without manual fallback and permits switching shops',async()=>{
 for(const shopId of ['cross','local']){
  const h=harness();h.node('#publish-shop-id').value=shopId;
  h.c.selectedProductCaptureDetail={...structuredClone(actual96SkuFacts),id:'capture'};
  h.c.api=async()=>({data:{...options(shopId==='local'?'LOCAL':'LOCAL_REPLICATION').data,categories:{categories:crossBorderCategories}}});
  await h.prepare();
  assert.match(h.node('#publish-auto-summary').textContent,/MY.*Women's T-shirts.*601302.*主营类目/);
  assert.equal(h.node('#publish-category-id').hidden,true);
  assert.equal(h.node('#publish-advanced').open,false);
  h.node('#publish-category-id').value='600321';h.node('#publish-warehouse-id').value='manual';
  assert.throws(h.payload,/601302.*主营类目/);
  h.node('#publish-shop-id').value='local';
  h.c.api=async()=>({data:{...options('LOCAL').data,categories:{categories:crossBorderCategories.map(c=>({...c,permission_statuses:['AVAILABLE']}))}}});
  await h.prepare();
  assert.equal(h.payload().categoryId,'601302');
  assert.equal(h.node('#publish-auto-summary').dataset.prepareError,'');
 }
});

test('a successful prepare cannot submit a different denied category or an arbitrary manually entered id',async()=>{
 const h=harness();h.c.selectedProductCaptureDetail.categoryName='Home Supplies > Home Decor > Clocks';
 h.c.api=async()=>({data:{...options().data,categories:{categories:crossBorderCategories}}});
 await h.prepare();assert.equal(h.payload().categoryId,'600321');
 const denied=h.node('#publish-category-choice').options.find(option=>option.value==='601302');
 assert.equal(denied.disabled,true);assert.match(denied.text,/当前店铺无权限/);
 h.node('#publish-category-id').value='601302';assert.throws(h.payload,/601302.*主营类目/);
 h.node('#publish-category-id').value='arbitrary';assert.throws(h.payload,/当前店铺返回的有效商品类目/);
});

test('a local warehouse fallback keeps category permissions already returned by the official API',async()=>{
 const h=harness();h.node('#publish-shop-id').value='local';
 h.c.selectedProductCaptureDetail.categoryName='Home Supplies > Home Decor > Clocks';
 h.c.api=async()=>({data:{...options('LOCAL').data,categories:{categories:crossBorderCategories},warehouses:{warehouses:[]}}});
 await h.prepare();assert.equal(h.node('#publish-category-id').hidden,false);
 h.node('#publish-warehouse-id').value='manual';assert.equal(h.payload().categoryId,'600321');
 h.node('#publish-category-id').value='601302';assert.throws(h.payload,/601302.*主营类目/);
});
