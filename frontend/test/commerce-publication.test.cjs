const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../app.js'),'utf8');
const shops=[{id:'tiktok',name:'TikTok越南',platform:'TIKTOK_SHOP',status:'ACTIVE',sellerType:'CROSS_BORDER'},
 {id:'shopee',name:'虾皮',platform:'SHOPEE',status:'ACTIVE'}];
const capture=id=>({capture:{id,title:`商品${id}`,categoryName:'Women Clothes > T-shirts'},skus:[{id:'sku',price:159}],images:[],normalizedData:{}});
const options={tiktok:{listingMethod:'LOCAL_REPLICATION',canCreateDraft:true,
 categories:{categories:[{id:'shirt',name:'Women T-shirts',is_leaf:true,permission_statuses:['AVAILABLE']}]},
 warehouses:{warehouses:[{id:'warehouse',type:'SALES_WAREHOUSE',is_default:true}]}},
 shopee:{categories:{category_list:[{category_id:12,display_category_name:'Women T-shirts',has_children:false}]},
 logistics:{logistics_channel_list:[{logistics_channel_id:101,enabled:true}]}}};
const plain=x=>JSON.parse(JSON.stringify(x));
function harness(overrides={}){
 const calls=[];
 const context={AbortController,setTimeout,clearTimeout,currentUser:{organizationId:'org'},localStorage:{getItem:()=>null},
  api:async(url,request)=>{
   calls.push({url,request});if(overrides.api){const response=overrides.api(url,request);if(response!==undefined)return response;}
   if(url==='/api/v1/shops')return{data:shops};
   if(url.startsWith('/api/v1/product-captures/'))return{data:capture(url.split('/').pop())};
   if(url.includes('/shops/'))return{data:options[url.split('/')[4]]};
   if(request?.method==='POST')return{data:{saved:true}};
   return{data:{items:[{captureId:'p1',shopId:'tiktok'},{captureId:'p2',shopId:'shopee'}]}};
  },encodeURIComponent};
 vm.createContext(context);
 vm.runInContext(source.slice(source.indexOf('function normalizedMarketplacePlatform('),source.indexOf('function activeMarketplaceShops(')),context);
 vm.runInContext(source.slice(source.indexOf('function productCapturePayload('),source.indexOf('function productCaptureListPayload(')),context);
 vm.runInContext(source.slice(source.indexOf('function requireTikTokDraftCapability('),source.indexOf('function crossPlatformPublishShops(')),context);
 context.productCaptureApi=(part,request)=>context.api(`/api/v1/product-captures${part}`,request);
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../commerce-publication.js'),'utf8'),context);
 return{calls,context,helper:context.CommercePublication};
}
test('prepares full capture details with the existing category resolver and approved defaults for every target',async()=>{
 const h=harness(),targets=[{captureId:'p1',shopId:'tiktok'},{captureId:'p2',shopId:'shopee'},{captureId:'p1',shopId:'shopee'}];
 const prepared=plain(await h.helper.prepare(targets,{shops}));assert.equal(prepared.length,3);
 assert.equal(h.calls.filter(c=>c.url==='/api/v1/product-captures/p1').length,1);
 assert.equal(h.calls.filter(c=>c.url==='/api/v1/shops/shopee/publish-options').length,1);
 assert.equal(prepared[0].request.categoryId,'shirt');assert.equal(prepared[0].request.warehouseId,'warehouse');
 assert.equal(prepared[1].request.categoryId,'12');assert.deepEqual(prepared[1].request.logisticInfo,[{logistic_id:101,enabled:true}]);
 for(const row of prepared){assert.equal(row.request.publishStock,999);assert.equal(row.request.weightGrams,'250');assert.equal(row.request.autoConvertPrice,true);
  assert.equal(row.request.packageLengthCm,'30');assert.equal(row.request.packageWidthCm,'25');assert.equal(row.request.packageHeightCm,'2');assert.equal(row.request.submitMode,undefined);}
 assert.ok(h.calls.every(call=>call.request?.method!=='POST'),'all preparation calls are read-only');
});
test('aggregates different target failures without saving options or starting a batch',async()=>{
 const h=harness({api:url=>url.includes('/shops/tiktok/')?{data:{...options.tiktok,warehouses:{warehouses:[]}}}:
  url.includes('/shops/shopee/')?{data:{...options.shopee,logistics:{logistics_channel_list:[]}}}:undefined});
 await assert.rejects(h.helper.restore('batch'),error=>{assert.match(error.message,/商品p1.*默认发货仓库/);assert.match(error.message,/商品p2.*配送渠道/);return true;});
 assert.ok(h.calls.every(call=>call.request?.method!=='POST'));
});
test('restoration reads full batch, resolves all targets then atomically saves each capture and shop without publishing',async()=>{
 const h=harness();await h.helper.restore('batch');
 const writes=h.calls.filter(call=>call.request?.method==='POST');assert.equal(writes.length,1);
 assert.ok(writes.every(call=>call.url==='/api/v1/commerce-batches/batch/publish-options'));
 const rows=JSON.parse(writes[0].request.body).publicationRequests;assert.deepEqual(rows.map(r=>[r.captureId,r.shopId]),[['p1','tiktok'],['p2','shopee']]);
 const firstWrite=h.calls.indexOf(writes[0]);assert.ok(h.calls.slice(firstWrite).every(call=>call.request?.method==='POST'));
});
test('restoration failure or organization switch never saves incomplete prepared options',async()=>{
 let active=true;
 const h=harness({api:url=>{if(url.startsWith('/api/v1/product-captures/')){active=false;return{data:capture(url.split('/').pop())};}}});
 await assert.rejects(h.helper.restore('batch',{isCurrent:()=>active}),/已切换/);
 assert.ok(h.calls.every(call=>call.request?.method!=='POST'));
});
test('unconfirmed TikTok cross-border capability and unsupported shops stop before saving',async()=>{
 const h=harness({api:url=>url.includes('/shops/tiktok/')?{data:{...options.tiktok,listingMethod:'GLOBAL_PUBLISHING'}}:undefined});
 await assert.rejects(h.helper.prepare([{captureId:'p1',shopId:'tiktok'},{captureId:'p2',shopId:'missing'}],{shops}),error=>{
  assert.match(error.message,/跨境刊登模式未就绪/);assert.match(error.message,/目标店铺授权已失效/);return true;});
 assert.ok(h.calls.every(call=>call.request?.method!=='POST'));
});

test('a partially uploaded batch retains complete frozen options and fills only missing items',async()=>{
 const frozen={autoConvertPrice:false,publishPrice:199000,publishStock:42,weightGrams:350,
  packageLengthCm:31,packageWidthCm:26,packageHeightCm:3,categoryId:'original-category',warehouseId:'original-warehouse'};
 const h=harness({api:(url,request)=>url.endsWith('/publish-options')&&!url.includes('/shops/')&&request?.method!=='POST'
  ?{data:{publicationRequests:[{captureId:'p1',shopId:'tiktok',request:frozen}]}}:undefined});
 const result=plain(await h.helper.restore('batch'));
 assert.deepEqual(result[0].request,frozen);assert.equal(result[1].request.autoConvertPrice,true);
 assert.ok(!h.calls.some(call=>call.url==='/api/v1/product-captures/p1'));
 assert.ok(!h.calls.some(call=>call.url==='/api/v1/shops/tiktok/publish-options'));
});

test('filling a partial old configuration preserves its explicit price and attributes',async()=>{
 const h=harness({api:(url,request)=>url.endsWith('/publish-options')&&!url.includes('/shops/')&&request?.method!=='POST'
  ?{data:{publicationRequests:[{captureId:'p1',shopId:'tiktok',request:{publishPrice:299000,productAttributes:[{id:'condition',value:'new'}]}}]}}:undefined});
 const result=plain(await h.helper.restore('batch'));
 assert.equal(result[0].request.publishPrice,299000);assert.equal(result[0].request.autoConvertPrice,false);
 assert.deepEqual(result[0].request.productAttributes,[{id:'condition',value:'new'}]);assert.equal(result[0].request.warehouseId,'warehouse');
});

test('null blank and invalid old fields cannot overwrite newly prepared options',async()=>{
 const old={autoConvertPrice:true,publishPrice:150000,publishStock:0,weightGrams:null,
  packageLengthCm:'',packageWidthCm:-1,packageHeightCm:0,categoryId:' ',warehouseId:'',productAttributes:[{id:'condition',value:'new'}]};
 const h=harness({api:(url,request)=>url.endsWith('/publish-options')&&!url.includes('/shops/')&&request?.method!=='POST'
  ?{data:{publicationRequests:[{captureId:'p1',shopId:'tiktok',request:old}]}}:undefined});
 const result=plain(await h.helper.restore('batch')),request=result[0].request;
 assert.equal(request.categoryId,'shirt');assert.equal(request.warehouseId,'warehouse');assert.equal(request.weightGrams,'250');
 assert.equal(request.packageLengthCm,'30');assert.equal(request.packageWidthCm,'25');assert.equal(request.packageHeightCm,'2');
 assert.equal(request.publishStock,0);assert.equal(request.autoConvertPrice,true);assert.equal(request.publishPrice,undefined);
 assert.deepEqual(request.productAttributes,old.productAttributes);
});
