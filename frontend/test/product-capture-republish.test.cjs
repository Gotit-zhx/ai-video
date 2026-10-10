const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../app.js'),'utf8');
function harness(captures,canPublish=true){
 const list={innerHTML:''};
 const context={document:{querySelector:()=>list},hasAnyPermission:()=>canPublish,
  filteredProductCaptures:()=>captures,syncProductCapturePagination:()=>{},
  productCaptureStatusGroup:()=> 'PUBLISHED',productCaptureImages:()=>[],safeProductUrl:()=>'',
  selectedProductCaptureId:null,productBulkSelectedIds:new Set(),escapeHtml:v=>String(v??'').replaceAll('<','&lt;').replaceAll('"','&quot;'),
  productSourcePlatformName:v=>v,productPriceLabel:()=>'',formatDate:()=>'',productCaptureDisplayStatus:()=> '已提交平台'};
 vm.createContext(context);
 vm.runInContext(source.slice(source.indexOf('function productCaptureCanUpload('),source.indexOf('function renderProductCaptureEmpty(')),context);
 return {context,list};
}
test('every published product remains selectable and has a shop chooser with authoritative provenance',()=>{
 const {context,list}=harness([
  {id:'original',title:'Original <shirt>',status:'PUBLISHED',originType:'CAPTURED'},
  {id:'variant',title:'Generated shirt',status:'PUBLISHED',originType:'COMMERCE_GENERATED'}]);
 context.renderProductCaptureList();
 assert.equal((list.innerHTML.match(/data-product-bulk-id=/g)||[]).length,2);
 assert.doesNotMatch(list.innerHTML,/disabled/);
 assert.equal((list.innerHTML.match(/选择店铺再次上传/g)||[]).length,2);
 assert.match(list.innerHTML,/product-origin-badge captured">原始采集/);
 assert.match(list.innerHTML,/product-origin-badge generated">裂变商品/);
 assert.match(list.innerHTML,/Original &lt;shirt>/);
});
test('provenance never guesses from names or source platform, and no publish permission keeps selection disabled',()=>{
 const {context,list}=harness([{id:'commerce-x',title:'裂变商品',sourcePlatform:'SHOPEE',status:'PUBLISHED'}],false);
 assert.equal(context.productCaptureOrigin({title:'裂变商品',sourcePlatform:'SHOPEE'}).label,'来源待确认');
 context.renderProductCaptureList();
 assert.match(list.innerHTML,/disabled/);
 assert.doesNotMatch(list.innerHTML,/data-upload-capture=/);
});
test('unfinished supported products can select a shop while unrecognized statuses stay blocked',()=>{
 const {context}=harness([]);
 for(const status of ['CAPTURED','DRAFT','IMAGE_PROCESSING','READY','APPROVED','FAILED','PUBLISHED'])assert.equal(context.productCaptureCanUpload({status}),true,status);
 for(const status of ['DELETED','UNKNOWN',undefined])assert.equal(context.productCaptureCanUpload({status}),false);
});
