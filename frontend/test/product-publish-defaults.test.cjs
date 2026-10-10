const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../app.js'),'utf8');
function context() {
 const nodes=new Map();
 const node=id=> { if(!nodes.has(id)) nodes.set(id,{value:'',dataset:{},replaceChildren(...options){this.options=options;this.value=options[0]?.value||'';}});return nodes.get(id);};
 const values=new Map();
 const c={document:{querySelector:node},localStorage:{getItem:k=>values.get(k),setItem:(k,v)=>values.set(k,v)},Option:function(text,value){this.text=text;this.value=value;},currentUser:{organizationId:'org'},selectedProductCaptureDetail:{id:'p1',categoryName:'Women Clothes > T-shirts',currency:'MYR',normalizedData:{}},marketplaceIntegrationStatus:{livePublishingEnabled:true},activeMarketplaceShops:()=>[{id:'shop',region:'MY'}],api:async()=>({data:{categories:{category_list:[{category_id:1,parent_category_id:0,display_category_name:'Women Clothes',has_children:true},{category_id:2,parent_category_id:1,display_category_name:'T-shirts',has_children:false}]},logistics:{logistics_channel_list:[{logistics_channel_id:3,logistics_channel_name:'Courier',enabled:true}]}}})};
 node('#publish-shop-id').value='shop';node('#publish-destination-platform').value='SHOPEE';
 c.ProductListingTemplates={ensure:async()=>{},get:()=>({enabled:false}),fields:()=>({})};
 vm.createContext(c);vm.runInContext(source.slice(source.indexOf('let productPublishPrepareVersion'),source.indexOf('function syncOfficialPublishFields()')),c);
 return {c,node};
}
test('defaults to 999 and 250g, matches full category and single enabled logistics',async()=>{
 const {c,node}=context();await c.prepareSimpleProductPublish();
 assert.equal(node('#publish-stock').value,'999');assert.equal(node('#publish-weight-grams').value,'250');
 assert.equal(node('#publish-category-id').value,'2');assert.deepEqual(JSON.parse(node('#publish-logistic-info').value),[{logistic_id:3,enabled:true}]);
 assert.equal(node('#publish-advanced').open,false);
});
test('fixed platform template overrides original parcel in single upload but keeps source weight intact',async()=>{
 const {c,node}=context();const fields=require('../product-listing-templates.js').fields;
 const template={name:'虾皮通用模板',enabled:true,weightGrams:200,packageLengthCm:5,packageWidthCm:25,packageHeightCm:25};
 c.ProductListingTemplates={ensure:async()=>{},get:()=>template,fields};
 c.selectedProductCaptureDetail.normalizedData.weightGrams=350;
 await c.prepareSimpleProductPublish();
 assert.equal(node('#publish-weight-grams').value,'200');assert.equal(node('#publish-package-length').value,'5');
 assert.equal(node('#publish-package-width').value,'25');assert.equal(node('#publish-package-height').value,'25');
 assert.equal(c.selectedProductCaptureDetail.normalizedData.weightGrams,350);
});
test('single and bulk option resolution preserve all configured destination logistics',async()=>{
 const {c,node}=context();
 c.ProductListingTemplates.logisticInfo=require('../product-listing-templates.js').logisticInfo;
 const options={categories:{category_list:[{category_id:2,has_children:false,display_category_name:'T-shirts'}]},
   listingLogistics:{mode:'ALL_ENABLED',channelIds:[],version:1},
   logistics:{logistics_channel_list:[
     {logistics_channel_id:100,logistics_channel_name:'Shopee Self Pick-up',enabled:true},
     {logistics_channel_id:200,logistics_channel_name:'Standard Local',enabled:true}]}};
 c.api=async()=>({data:options});await c.prepareSimpleProductPublish();
 const expected=[{logistic_id:100,enabled:true},{logistic_id:200,enabled:true}];
 assert.deepEqual(JSON.parse(node('#publish-logistic-info').value),expected);
 assert.equal(node('#publish-logistic-choice').disabled,true);
 assert.match(node('#publish-auto-summary').textContent,/Shopee Self Pick-up、Standard Local/);
 assert.deepEqual(JSON.parse(JSON.stringify(c.resolveShopeePublishOptions(c.selectedProductCaptureDetail,options).logisticInfo)),expected);
});
test('preserves unit-aware original weight without interpreting size text as weight',()=>{
 const {c}=context();assert.equal(c.originalProductWeight({normalizedData:{weightGrams:180}}),'180');
 assert.equal(c.originalProductWeight({normalizedData:{package_weight:{value:0.3,unit:'kg'}}}),'300');
 assert.equal(c.originalProductWeight({normalizedData:{weight:50,description:'Size 50kg'}}),'');
});
test('saved choices are isolated by company, shop and source category; price and weight are not shop defaults',async()=>{
 const {c,node}=context();await c.prepareSimpleProductPublish();c.rememberPublishPreferences();
 assert.equal(c.readPublishPreference().logistic,'3');assert.equal(c.readPublishPreference(true).category,'2');
 assert.equal(c.readPublishPreference().weightGrams,undefined);
 c.selectedProductCaptureDetail.categoryName='Other';assert.equal(c.readPublishPreference(true).category,undefined);
 node('#publish-shop-id').value='other';assert.equal(c.readPublishPreference().logistic,undefined);
 node('#publish-shop-id').value='shop';c.currentUser.organizationId='other';assert.equal(c.readPublishPreference().logistic,undefined);
});
test('late shop response cannot overwrite current shop selection',async()=>{
 const {c,node}=context();let resolve;c.api=()=>new Promise(r=>resolve=r);const first=c.prepareSimpleProductPublish();await Promise.resolve();
 node('#publish-shop-id').value='new';c.api=async()=>({data:{categories:{category_list:[{category_id:8,has_children:false,display_category_name:'New'}]},logistics:{logistics_channel_list:[]}}});await c.prepareSimpleProductPublish();
 resolve({data:{categories:{category_list:[{category_id:99,has_children:false,display_category_name:'Old'}]}}});await first;
 assert.equal(node('#publish-category-choice').options[1].value,'8');
});
test('original category wins over saved mismatch, including sandbox display prefix',async()=>{
 const {c,node}=context();node('#publish-category-id').value='7';c.rememberPublishPreferences();
 c.api=async()=>({data:{categories:{category_list:[{category_id:1,parent_category_id:0,display_category_name:'BD Test - Women Clothes (DO NOT DELETE)',has_children:true},{category_id:2,parent_category_id:1,display_category_name:'T-shirts',has_children:false},{category_id:7,parent_category_id:1,display_category_name:'Socks',has_children:false}]},logistics:{logistics_channel_list:[]}}});
 await c.prepareSimpleProductPublish();assert.equal(node('#publish-category-id').value,'2');
});

test('Shopee to TikTok resolves destination taxonomy and default sales warehouse',async()=>{
 const {c,node}=context();node('#publish-destination-platform').value='TIKTOK_SHOP';
 c.selectedProductCaptureDetail.sourcePlatform='SHOPEE';
 c.api=async()=>({data:{listingMethod:'LOCAL',canCreateDraft:true,categories:{categories:[{id:'10',name:'Fashion',is_leaf:false},{id:'20',parent_id:'10',name:'T-shirts',is_leaf:true}]},
 warehouses:{warehouses:[{id:'returns',type:'RETURN_WAREHOUSE',is_default:true},{id:'sales',type:'SALES_WAREHOUSE',is_default:true}]}}});
 await c.prepareSimpleProductPublish();
 assert.equal(node('#publish-category-id').value,'20');
 assert.equal(node('#publish-warehouse-id').value,'sales');
 assert.equal(node('#publish-stock').value,'999');
 assert.match(node('#publish-auto-summary').textContent,/草稿/);
});
test('cross-platform category never reuses source IDs and rejects ambiguous leaves',()=>{
 const {c}=context();
 assert.equal(c.matchDestinationCategory({categoryName:'Source > Shirts',categoryId:'2'},[{id:'9',label:'Men > Shirts'},{id:'10',label:'Women > Shirts'}]),'');
 assert.equal(c.matchDestinationCategory({categoryName:'Source > Shirts'},[{id:'9',label:'Fashion > Shirts'}]),'9');
});
