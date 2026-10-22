import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,mutations=[];
w.currentUser={permissions:['finance:manage']};w.matchMedia=()=>({matches:false});
w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const shops=[{id:'other',name:'无成本店',platform:'SHOPEE',group_id:'other-group',region:'TH'},{id:'shop',name:'母婴测试店',platform:'TIKTOK_SHOP',group_id:'group',region:'PH'}],groups=[{id:'group',name:'母婴组'}];
let costs={live:true,shopId:'shop',groupId:'group',groupName:'母婴组',sourceName:'成本.csv',costs:[{sku:'PACK',warehouse:'海外仓',prices:{'2026-09':2}},{sku:'BABY-BPants-XXL',warehouse:'海外仓',prices:{'2026-09':3}}],
  products:[{shop_id:'shop',product_id:'product',sku_id:'variant',seller_sku:'PACK',name:'商品',spec:'一包'},{shop_id:'shop',product_id:'product',sku_id:'unmatched',seller_sku:'OTHER-CODE',name:'商品',spec:'其他规格'},{shop_id:'shop',product_id:'product',sku_id:'review',seller_sku:'BABY-BPants-XXL',name:'商品',spec:'Pants-XXXL'}],sampleOrders:1,orderLimit:2000,
  settings:{warehouse:'海外仓',rules:{currency:'CNY',unit:'包',basis:'shipment',missing:'pending',freight:'',inbound:''}},settingsVersion:1,mappings:{},shopCatalogs:{shop:{costRows:1,sourceName:'成本.csv'},other:{costRows:0,sourceName:''}}};
const reads=[];let failOther=false;
let expenses={rows:[],rules:[],changes:[],totals:{records:0,amount:0,posted:0,included:0,pending:0},page:1,pageSize:50};
w.api=async(path,options={})=>{
  const url=new URL(path,'https://example.test'),body=options.body?JSON.parse(options.body):null;
  if(options.method&&options.method!=='GET'){
    mutations.push({path:url.pathname,method:options.method,body});
    if(path.endsWith('/auto-match')){for(const product of costs.products)for(const cost of costs.costs)if(product.seller_sku===cost.sku)costs.mappings['shop:product:'+product.sku_id+':'+cost.warehouse]??={code:cost.sku,warehouse:cost.warehouse,factor:null,method:'EXACT_CODE',version:1,groupId:'group'};return {data:structuredClone(costs)};}
    if(path.endsWith('/mapping')){costs.mappings['shop:product:'+body.sku_id+':海外仓']={...body,version:1,groupId:'group'};return {data:'已保存'};}
    if(path.endsWith('/settings')){costs.settings={rules:body.rules,warehouse:body.warehouse};costs.settingsVersion++;return {data:'已保存'};}
    if(path.endsWith('/expenses')){const row={...body,id:'expense-id',version:1,status:'已计入'};expenses={...expenses,rows:[row],totals:{records:1,amount:12.5,posted:12.5,included:0,pending:0}};return {data:row};}
    throw Error('Unexpected mutation '+path);
  }
  if(url.pathname==='/api/v1/finance/shops')return {data:{items:shops,groups,sync:[]}};
  if(url.pathname==='/api/v1/finance/workspace/costs'){
    reads.push(url.searchParams.get('shopId'));
    if(url.searchParams.get('shopId')==='other'){
      if(failOther)throw Error('店铺读取暂时失败');
      return {data:{...structuredClone(costs),shopId:'other',groupId:'other-group',sourceName:'',costs:[],mappings:{},settings:{},products:[{shop_id:'other',product_id:'product',sku_id:'variant',seller_sku:'OTHER',name:'其他店商品',spec:'其他规格'}]}};
    }
    return {data:structuredClone(costs)};
  }
  if(url.pathname==='/api/v1/finance/workspace/expenses')return {data:structuredClone(expenses)};
  throw Error('Unexpected GET '+path);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-costs.js','finance-expenses-design.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+name,import.meta.url),'utf8'));
const button=(text,root=d)=>[...root.querySelectorAll('button')].find(n=>n.textContent.trim()===text||n.textContent.trim().endsWith(text));
const settle=()=>new Promise(r=>setTimeout(r,20));
async function click(text,root=d){const n=button(text,root);assert(n,text);n.click();await settle();}
function field(name,value,root=d){const n=root.querySelector(`[aria-label="${name}"]`);assert(n,name);n.value=value;n.dispatchEvent(new w.Event('change'));}
w.FinanceWorkspace.enter('costs');await settle();
assert(d.body.textContent.includes('店铺成本管理'));assert(!d.body.textContent.includes('设计预览'));
assert.equal(mutations.filter(r=>r.path.endsWith('/auto-match')).length,1,'same codes are linked automatically without per-SKU clicks');
assert(!d.body.textContent.includes('待启用'));assert(!button('订单成本'));assert(!button('仓库与规则'));
assert.equal(reads[0],'','initial selection is chosen by persisted cost availability, not the first shop');
assert.equal(d.querySelector('[aria-label="财务店铺"]').value,'shop');
await click('SKU 关联');
assert.equal(d.querySelectorAll('.fc-shop-parent').length,2);
assert.equal(d.querySelector('.fc-shop-parent[aria-expanded=true]').getAttribute('aria-label'),'查看店铺 母婴测试店');
assert(d.querySelector('.fc-shop-children:not([hidden])').textContent.includes('成本.csv'));
assert.equal(d.querySelectorAll('.fc-spu').length,1,'SKUs share the parent platform product ID');
assert(d.querySelector('.fc-spu-parent').textContent.includes('已关联 1'));
assert(d.querySelector('.fc-spu-parent').textContent.includes('未关联 1'));
assert(d.querySelector('.fc-spu-parent').textContent.includes('需核对 1'));
assert(d.querySelector('.fc-spu-parent .fc-tag.warn'),'review state uses both an orange style and a readable label');
assert(d.querySelector('.fc-spu.danger'));
await click('核对关联',d.querySelector('tbody'));
let dialog=d.querySelector('dialog');await click('保存关联',dialog);
assert.equal(mutations.filter(r=>r.path.endsWith('/mapping')).length,0,'unmatched codes require a reviewed override');
field('成本型号','PACK',dialog);dialog.querySelector('input[type=checkbox]').checked=true;await click('保存关联',dialog);
const mapping=mutations.find(r=>r.path.endsWith('/mapping'));
assert.equal(mapping.path,'/api/v1/finance/workspace/costs/shop/mapping');
assert.equal(mapping.body.sku_id,'unmatched');assert(d.querySelector('tbody').textContent.includes('已关联'));
await click('成本表设置');field('默认计价单位','箱');await click('保存成本设置');
const settings=mutations.find(r=>r.path.endsWith('/settings'));
assert.equal(settings.body.rules.unit,'箱');assert.equal(settings.body.version,1);
await click('SKU 关联');
d.querySelector('[aria-label="查看店铺 无成本店"]').click();await settle();
assert(d.querySelector('tbody').textContent.includes('OTHER'));assert(!d.querySelector('tbody').textContent.includes('已关联'),'another shop with the same SKU identity has no inherited mapping');
assert(!d.querySelector('.fc-shop-source').textContent.includes('成本.csv'),'cost tables do not bleed into another group');
d.querySelector('[aria-label="查看店铺 母婴测试店"]').click();await settle();
assert(d.querySelector('tbody').textContent.includes('已关联'),'returning to the shop restores its saved mapping');
failOther=true;d.querySelector('[aria-label="查看店铺 无成本店"]').click();await settle();
assert(d.body.textContent.includes('店铺读取暂时失败'));assert.equal(d.querySelector('[aria-label="财务店铺"]').value,'shop');assert(d.querySelector('tbody').textContent.includes('已关联'),'failed reads preserve the last correctly labelled shop');
w.FinanceWorkspace.enter('expense-ledger');await settle();assert(d.body.textContent.includes('其他费用台账总额'));assert(d.querySelector('[aria-label="费用数据分类"]').textContent.includes('平台费用'));assert(!d.body.textContent.includes('设计预览'));
await click('新增费用');dialog=d.querySelector('dialog');
for(const [name,value]of [['归属店铺','shop'],['订单编号','known-order'],['费用日期','2026-10-22'],['计入财务日期','2026-10-22'],['金额','12.50'],['费用来源编号','waybill-1'],['计入方式','extra']])field(name,value,dialog);
await click('保存费用',dialog);
const savedExpense=mutations.find(r=>r.path.endsWith('/expenses'));assert.equal(savedExpense.method,'POST');assert.equal(savedExpense.body.amount,'12.50');
assert(d.querySelector('tbody').textContent.includes('waybill-1'));
await click('刷新');assert(d.querySelector('tbody').textContent.includes('waybill-1'),'saved record survives reload');
w.FinanceWorkspace.reset();w.currentUser={permissions:['finance:view']};w.FinanceWorkspace.enter('expense-ledger');await settle();
assert(!button('新增费用'));assert(!button('调整',d.querySelector('tbody')));
assert(!button('分摊规则'));assert(!button('来源与口径'));
w.FinanceWorkspace.leave();w.close();
console.log('Live cost/expense UI: authorized endpoints, confirmed SKU mapping, versioned settings, exact decimal input, persisted refresh and read-only roles passed.');
